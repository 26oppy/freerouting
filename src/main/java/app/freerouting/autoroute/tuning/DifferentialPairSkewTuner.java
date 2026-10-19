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
import app.freerouting.rules.DifferentialPair;
import app.freerouting.rules.Net;
import app.freerouting.rules.NetMeanderConstraint;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * Post-route optimization engine that equalizes intra-pair trace lengths of differential pairs by
 * inserting single-sided skew bumps on the shorter trace of each pair.
 */
public final class DifferentialPairSkewTuner implements Serializable {

  private static final long serialVersionUID = 1L;

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

    int attempted = 0;
    int equalized = 0;
    int bumpsInserted = 0;
    double totalCompensated = 0.0;

    boolean fortyFiveDegreeOnly = board.rules.getTraceAngleRestriction() != AngleRestriction.NONE;

    Collection<DifferentialPair> pairs = board.rules.differentialPairs.all();
    for (DifferentialPair pair : pairs) {
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
      double allowedSkew = pair.hasMaxSkew() ? pair.maxSkew() : 0.0;
      if (skew <= allowedSkew) {
        continue; // Skew already within tolerance
      }

      attempted++;
      Net shorterNet = (pLen < nLen) ? pNet : nNet;
      double skewDeficit = skew;

      boolean tuned =
          tunePairSkew(board, shorterNet, skewDeficit, allowedSkew, fortyFiveDegreeOnly);
      if (tuned) {
        bumpsInserted++;
        double newPLen = pNet.getTraceLength();
        double newNLen = nNet.getTraceLength();
        double newSkew = Math.abs(newPLen - newNLen);
        double compensated = skew - newSkew;
        totalCompensated += Math.max(0.0, compensated);
        if (newSkew <= allowedSkew || newSkew < skew * 0.2) {
          equalized++;
        }
        FRLogger.info(
            String.format(
                "DifferentialPairSkewTuner: tuned pair '%s' — skew reduced from %.3f mm to %.3f mm (allowed: %.3f mm)",
                pair.baseName(),
                toUserUnits(board, skew),
                toUserUnits(board, newSkew),
                toUserUnits(board, allowedSkew)));
      }
    }

    double totalCompensatedMm = toUserUnits(board, totalCompensated);
    return new SkewTuningReport(attempted, equalized, bumpsInserted, totalCompensatedMm);
  }

  private static boolean tunePairSkew(
      RoutingBoard board,
      Net shorterNet,
      double skewDeficit,
      double allowedSkew,
      boolean fortyFiveDegreeOnly) {
    NetMeanderConstraint constraint = shorterNet.getMeanderConstraint();
    double spacing = constraint.spacing() > 0 ? constraint.spacing() : 600.0;
    double minLength = 4.0 * spacing;

    List<CandidateCorridor> corridors =
        TraceCorridorScanner.scanCorridors(
            board, shorterNet, minLength, constraint.minAmplitude(), constraint.maxAmplitude());

    if (corridors.isEmpty()) {
      return false;
    }

    for (CandidateCorridor corridor : corridors) {
      // Preferred normal automatically points into the side with larger clearance (away from
      // partner)
      Point[] bumpPoints =
          MeanderGenerator.generateSkewBump(
              corridor.startPoint(),
              corridor.endPoint(),
              skewDeficit,
              constraint,
              corridor.preferredNormal(),
              corridor.maxClearDepth(),
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

      board.removeItem(corridor.trace());
      boolean fits =
          board.checkPolylineTrace(newPolyline, layer, halfWidth, netNumbers, clearanceClass);

      if (fits) {
        board.insertTrace(
            newPolyline, layer, halfWidth, netNumbers, clearanceClass, FixedState.USER_FIXED);
        return true;
      } else {
        board.insertTrace(
            corridor.trace().polyline(),
            layer,
            halfWidth,
            netNumbers,
            clearanceClass,
            corridor.trace().getFixedState());
      }
    }

    return false;
  }

  private static double toUserUnits(RoutingBoard board, double boardUnits) {
    if (board != null
        && board.communication != null
        && board.communication.coordinateTransform != null) {
      return board.communication.coordinateTransform.boardToDsn(boardUnits);
    }
    return boardUnits;
  }
}
