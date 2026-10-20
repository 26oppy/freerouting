package app.freerouting.autoroute.tuning;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.structure.AngleRestriction;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.board.trace.TraceCorridorScanner;
import app.freerouting.board.trace.TraceCorridorScanner.CandidateCorridor;
import app.freerouting.geometry.planar.MeanderGenerator;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.CornerStyle;
import app.freerouting.rules.DifferentialPair;
import app.freerouting.rules.MeanderPatternType;
import app.freerouting.rules.Net;
import app.freerouting.rules.NetMeanderConstraint;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * Post-route optimization engine that equalizes intra-pair trace lengths of differential pairs by
 * inserting single-sided skew bumps on the shorter trace of each pair.
 */
public final class DifferentialPairSkewTuner {

  /** Result summary of differential pair skew tuning operations. */
  public record SkewTuningReport(
      int pairsAttempted, int pairsEqualized, int bumpsInserted, double totalSkewCompensatedMm)
      implements Serializable {

    private static final long serialVersionUID = 1L;
  }

  private DifferentialPairSkewTuner() {
    // Utility class
  }

  /**
   * Executes differential pair intra-pair skew tuning across all registered differential pairs.
   *
   * @param board the routing board
   * @return summary report of skew tuning operations
   */
  public static SkewTuningReport tune(RoutingBoard board) {
    if (board == null
        || board.rules == null
        || board.rules.differentialPairs == null
        || board.rules.differentialPairs.isEmpty()) {
      return new SkewTuningReport(0, 0, 0, 0.0);
    }
    return tune(board, board.rules.differentialPairs.all());
  }

  /**
   * Executes differential pair intra-pair skew tuning on the specified collection of pairs.
   *
   * @param board the routing board
   * @param pairs collection of differential pairs to evaluate and tune
   * @return summary report of skew tuning operations
   */
  public static SkewTuningReport tune(RoutingBoard board, Collection<DifferentialPair> pairs) {
    if (board == null || board.rules == null || pairs == null || pairs.isEmpty()) {
      return new SkewTuningReport(0, 0, 0, 0.0);
    }

    int attempted = 0;
    int equalized = 0;
    int bumpsInserted = 0;
    double totalCompensated = 0.0;

    boolean fortyFiveDegreeOnly = board.rules.getTraceAngleRestriction() != AngleRestriction.NONE;

    for (DifferentialPair pair : pairs) {
      if (pair == null) {
        continue;
      }
      Net pNet = pair.positiveNet();
      Net nNet = pair.negativeNet();
      if (pNet == null || nNet == null) {
        continue;
      }

      double pLen = pNet.getTraceLength();
      double nLen = nNet.getTraceLength();
      if (pLen <= 0.0 || nLen <= 0.0) {
        continue; // Net not routed yet
      }

      double skew = Math.abs(pLen - nLen);
      double allowedSkew =
          pair.hasMaxSkew()
              ? pair.maxSkew()
              : (board.communication != null
                  ? 0.10
                      * board.communication.getResolution(
                          app.freerouting.board.model.structure.Unit.MM)
                  : 1000.0);
      double effectiveAllowed = Math.max(allowedSkew, 10.0);
      if (skew <= effectiveAllowed) {
        continue; // Skew already within tolerance
      }

      attempted++;
      Net shorterNet = (pLen < nLen) ? pNet : nNet;
      Net partnerNet = (pLen < nLen) ? nNet : pNet;

      int bumpsForPair =
          tunePairSkew(board, shorterNet, partnerNet, allowedSkew, fortyFiveDegreeOnly);
      if (bumpsForPair > 0) {
        bumpsInserted += bumpsForPair;
        double newPLen = pNet.getTraceLength();
        double newNLen = nNet.getTraceLength();
        double newSkew = Math.abs(newPLen - newNLen);
        double compensated = skew - newSkew;
        totalCompensated += Math.max(0.0, compensated);
        if (newSkew <= allowedSkew || newSkew <= skew * 0.2) {
          equalized++;
        }
        FRLogger.info(
            String.format(
                "DifferentialPairSkewTuner: tuned pair '%s' with %d bump(s) — skew reduced from %.3f mm to %.3f mm (allowed: %.3f mm)",
                pair.baseName(),
                bumpsForPair,
                toMm(board, skew),
                toMm(board, newSkew),
                toMm(board, allowedSkew)));
      }
    }

    double totalCompensatedMm = toMm(board, totalCompensated);
    return new SkewTuningReport(attempted, equalized, bumpsInserted, totalCompensatedMm);
  }

  private static int tunePairSkew(
      RoutingBoard board,
      Net shorterNet,
      Net partnerNet,
      double allowedSkew,
      boolean fortyFiveDegreeOnly) {
    NetMeanderConstraint constraint = shorterNet.getMeanderConstraint();
    if (constraint == null || !constraint.isConstrained()) {
      constraint =
          board.rules != null
              ? board.rules.getDefaultMeanderConstraint(MeanderPatternType.DIFF_PAIR_SKEW)
              : null;
    }
    if (constraint == null || !constraint.isConstrained()) {
      constraint = new NetMeanderConstraint(500.0, 5000.0, 6000.0, CornerStyle.CHAMFER, 0.0, true);
    }
    double spacing = constraint.spacing() > 0 ? constraint.spacing() : 600.0;

    int bumpsAdded = 0;
    int maxPasses = 10;

    for (int pass = 0; pass < maxPasses; pass++) {
      double currentSkew = Math.abs(shorterNet.getTraceLength() - partnerNet.getTraceLength());
      if (currentSkew <= allowedSkew) {
        break;
      }

      double effSpacing = Math.min(spacing, Math.max(300.0, currentSkew));
      double minLength = Math.min(4.0 * spacing, Math.max(1000.0, 1.2 * effSpacing));
      double scanMinAmp = Math.min(constraint.minAmplitude(), Math.max(50.0, currentSkew / 4.0));

      List<CandidateCorridor> corridors =
          TraceCorridorScanner.scanCorridors(
              board, shorterNet, minLength, scanMinAmp, constraint.maxAmplitude());
      if (corridors.isEmpty()) {
        break;
      }

      boolean insertedInPass = false;
      for (CandidateCorridor corridor : corridors) {
        if (corridor.trace() == null || !corridor.trace().isOnTheBoard()) {
          continue;
        }

        double maxClear = corridor.maxClearDepth();
        double startAmp = Math.min(maxClear, constraint.maxAmplitude());
        double minAmp = Math.min(scanMinAmp, Math.max(20.0, currentSkew / 4.0));
        if (startAmp < minAmp) {
          continue;
        }

        // Amplitude relaxation: start with best amplitude, step down if obstacles intervene
        double step = Math.max(100.0, (startAmp - minAmp) / 4.0);
        for (double testAmp = startAmp; testAmp >= minAmp; testAmp -= step) {
          double passSpacing = Math.min(spacing, corridor.length() / 4.0);
          NetMeanderConstraint stepConstraint =
              new NetMeanderConstraint(
                  minAmp,
                  testAmp,
                  passSpacing,
                  CornerStyle.CHAMFER,
                  constraint.radiusPercentage(),
                  true);

          Point[] bumpPoints =
              MeanderGenerator.generateSkewBump(
                  corridor.startPoint(),
                  corridor.endPoint(),
                  currentSkew,
                  stepConstraint,
                  corridor.preferredNormal(),
                  testAmp,
                  fortyFiveDegreeOnly);

          if (bumpPoints == null || bumpPoints.length < 3) {
            continue;
          }

          Polyline newPolyline =
              PostRouteLengthTuner.spliceMeanderIntoTrace(
                  corridor.trace(), corridor.segmentIndex(), bumpPoints);
          if (newPolyline == null) {
            continue;
          }

          int layer = corridor.trace().getLayer();
          int halfWidth = corridor.trace().getHalfWidth();
          int[] netNumbers = corridor.trace().netNumbers;
          int clearanceClass = corridor.trace().clearanceClassIndex();

          FixedState originalState = corridor.trace().getFixedState();
          corridor.trace().setFixedState(FixedState.UNFIXED);
          board.removeItem(corridor.trace());
          boolean fits =
              board.checkPolylineTrace(newPolyline, layer, halfWidth, netNumbers, clearanceClass);

          if (fits) {
            board.insertTrace(
                newPolyline, layer, halfWidth, netNumbers, clearanceClass, originalState);
            bumpsAdded++;
            insertedInPass = true;
            break; // Succeeded with this corridor
          } else {
            board.insertTrace(
                corridor.trace().polyline(),
                layer,
                halfWidth,
                netNumbers,
                clearanceClass,
                originalState);
          }
        }

        if (insertedInPass) {
          break; // Re-scan updated corridors on new trace layout
        }
      }

      if (!insertedInPass) {
        break; // No further bumps could fit in any corridor
      }
    }

    return bumpsAdded;
  }

  private static double toMm(RoutingBoard board, double boardUnits) {
    if (board != null && board.communication != null) {
      double mmRes =
          board.communication.getResolution(app.freerouting.board.model.structure.Unit.MM);
      if (mmRes > 0) {
        return boardUnits / mmRes;
      }
    }
    return boardUnits;
  }
}
