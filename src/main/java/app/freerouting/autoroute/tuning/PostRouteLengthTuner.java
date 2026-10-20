package app.freerouting.autoroute.tuning;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.structure.AngleRestriction;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.board.trace.TraceCorridorScanner;
import app.freerouting.board.trace.TraceCorridorScanner.CandidateCorridor;
import app.freerouting.geometry.planar.Direction;
import app.freerouting.geometry.planar.MeanderGenerator;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;
import app.freerouting.rules.NetLengthConstraint;
import app.freerouting.rules.NetMeanderConstraint;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Post-route optimization engine that equalizes trace lengths to satisfy target length constraints
 * by inserting serpentine meander (accordion) patterns into straight trace corridors.
 */
public final class PostRouteLengthTuner {

  /** Result summary of a post-route length tuning execution. */
  public record TuningReport(
      int netsAttempted,
      int netsSuccessfullyTuned,
      int totalMeandersInserted,
      double totalLengthAddedMm)
      implements Serializable {

    private static final long serialVersionUID = 1L;
  }

  private PostRouteLengthTuner() {
    // Utility class
  }

  /**
   * Executes post-route trace length tuning on all constrained nets of the given board.
   *
   * @param board the routing board with routed and optimized traces
   * @return summary report of tuning operations performed
   */
  public static TuningReport tune(RoutingBoard board) {
    if (board == null || board.rules == null || board.rules.nets == null) {
      return new TuningReport(0, 0, 0, 0.0);
    }

    List<Net> allNets = new ArrayList<>();
    int netCount = board.rules.nets.maxNetNumber();
    for (int netNo = 1; netNo <= netCount; netNo++) {
      Net net = board.rules.nets.get(netNo);
      if (net != null) {
        allNets.add(net);
      }
    }
    return tune(board, allNets);
  }

  /**
   * Executes post-route trace length tuning on the specified collection of nets.
   *
   * @param board the routing board with routed and optimized traces
   * @param netsToTune collection of candidate nets to evaluate and tune
   * @return summary report of tuning operations performed
   */
  public static TuningReport tune(RoutingBoard board, Collection<Net> netsToTune) {
    if (board == null || board.rules == null || netsToTune == null || netsToTune.isEmpty()) {
      return new TuningReport(0, 0, 0, 0.0);
    }

    int attempted = 0;
    int success = 0;
    int meandersInserted = 0;
    double totalAddedLength = 0.0;

    boolean fortyFiveDegreeOnly = board.rules.getTraceAngleRestriction() != AngleRestriction.NONE;

    // Prioritize nets with larger length deficits so they secure spacious corridors first
    List<Net> sortedNets = new ArrayList<>(netsToTune);
    sortedNets.sort((n1, n2) -> Double.compare(getDeficit(n2), getDeficit(n1)));

    for (Net net : sortedNets) {
      if (net == null) {
        continue;
      }

      NetLengthConstraint lengthConstraint = net.getLengthConstraint();
      if (lengthConstraint == null || !lengthConstraint.isConstrained()) {
        continue;
      }

      double targetLength = lengthConstraint.targetLength();
      if (targetLength <= 0.0) {
        continue;
      }

      double currentLength = net.getTraceLength();
      if (currentLength <= 0.0
          || currentLength >= targetLength
          || lengthConstraint.isSatisfied(currentLength)) {
        continue;
      }

      double deficit = targetLength - currentLength;
      if (deficit <= 0.0) {
        continue;
      }

      attempted++;
      double startLength = currentLength;

      int insertedForNet = tuneNet(board, net, lengthConstraint, targetLength, fortyFiveDegreeOnly);
      if (insertedForNet > 0) {
        meandersInserted += insertedForNet;
        double endLength = net.getTraceLength();
        double added = endLength - startLength;
        totalAddedLength += added;
        if (lengthConstraint.isSatisfied(endLength) || endLength >= targetLength) {
          success++;
        }
        FRLogger.info(
            String.format(
                "PostRouteLengthTuner: tuned net '%s' — added %.2f mm (start: %.2f mm, end: %.2f mm, target: %.2f mm)",
                net.name,
                toMm(board, added),
                toMm(board, startLength),
                toMm(board, endLength),
                toMm(board, targetLength)));
      }
    }

    double totalAddedMm = toMm(board, totalAddedLength);
    return new TuningReport(attempted, success, meandersInserted, totalAddedMm);
  }

  /**
   * Equalizes trace lengths across an arbitrary group of nets (such as a data bus, address bus, or
   * matched signal lines) to match the longest net in the group within the given tolerance.
   *
   * @param board the routing board with routed traces
   * @param netGroup collection of nets to equalize
   * @param toleranceBoardUnits allowable tolerance window in board units (e.g. 1000 = 0.1 mm)
   * @return summary report of tuning operations performed
   */
  public static TuningReport equalizeNetGroup(
      RoutingBoard board, Collection<Net> netGroup, double toleranceBoardUnits) {
    if (board == null || netGroup == null || netGroup.size() < 2) {
      return new TuningReport(0, 0, 0, 0.0);
    }

    double maxLen = 0.0;
    for (Net net : netGroup) {
      if (net != null) {
        maxLen = Math.max(maxLen, net.getTraceLength());
      }
    }
    if (maxLen <= 0.0) {
      return new TuningReport(0, 0, 0, 0.0);
    }

    double tol = Math.max(100.0, toleranceBoardUnits);
    for (Net net : netGroup) {
      if (net != null) {
        net.setLengthConstraint(new NetLengthConstraint(maxLen - tol, maxLen + tol));
      }
    }

    return tune(board, netGroup);
  }

  /**
   * Equalizes trace lengths across nets identified by name to match the longest net in the group.
   *
   * @param board the routing board with routed traces
   * @param netNames names of the nets in the group
   * @param toleranceBoardUnits allowable tolerance window in board units
   * @return summary report of tuning operations performed
   */
  public static TuningReport equalizeNetGroupByName(
      RoutingBoard board, Collection<String> netNames, double toleranceBoardUnits) {
    if (board == null
        || netNames == null
        || netNames.size() < 2
        || board.rules == null
        || board.rules.nets == null) {
      return new TuningReport(0, 0, 0, 0.0);
    }
    List<Net> group = new ArrayList<>();
    for (String name : netNames) {
      if (name != null) {
        Collection<Net> found = board.rules.nets.get(name);
        if (found != null) {
          group.addAll(found);
        }
      }
    }
    return equalizeNetGroup(board, group, toleranceBoardUnits);
  }

  /** Configuration for a single meander generation attempt along a corridor. */
  private record TuningAttemptConfig(
      Direction normal, double clearance, boolean forceSingleSided) {}

  /**
   * Iteratively inserts meanders into available straight corridors of a single net until its length
   * deficit is satisfied or no further corridors can be found.
   */
  private static int tuneNet(
      RoutingBoard board,
      Net net,
      NetLengthConstraint lengthConstraint,
      double targetLength,
      boolean fortyFiveDegreeOnly) {
    int insertedCount = 0;
    int maxIterations = 20;

    NetMeanderConstraint meanderConstraint = net.getMeanderConstraint();
    double spacing = meanderConstraint.spacing() > 0 ? meanderConstraint.spacing() : 600.0;
    double minLength = Math.min(4.0 * spacing, Math.max(1500.0, 1.5 * spacing));
    double scanMinAmp = Math.min(250.0, meanderConstraint.minAmplitude());

    for (int iter = 0; iter < maxIterations; iter++) {
      double currentLength = net.getTraceLength();
      double remainingDeficit = targetLength - currentLength;
      if (remainingDeficit <= 0.0 || lengthConstraint.isSatisfied(currentLength)) {
        break;
      }

      List<CandidateCorridor> corridors =
          TraceCorridorScanner.scanCorridors(
              board, net, minLength, scanMinAmp, meanderConstraint.maxAmplitude());

      if (corridors.isEmpty()) {
        break;
      }

      boolean replacedAny = false;
      for (CandidateCorridor corridor : corridors) {
        if (corridor.trace() == null || !corridor.trace().isOnTheBoard()) {
          continue;
        }

        Direction prefNorm = corridor.preferredNormal();
        if (prefNorm == null) {
          continue;
        }
        Direction oppNorm = prefNorm.turn45Degree(4);

        double prefClear = Math.max(corridor.leftClearDepth(), corridor.rightClearDepth());
        double oppClear = Math.min(corridor.leftClearDepth(), corridor.rightClearDepth());

        // Build candidate configurations to attempt:
        // 1. If double-sided requested and both sides have clearance >= scanMinAmp: try
        // double-sided
        // 2. If double-sided requested but opposite side is cramped: auto-adapt to single-sided on
        // open side
        // 3. Preferred single-sided direction
        // 4. Opposite single-sided direction (fallback if preferred side hits obstacle)
        List<TuningAttemptConfig> configs = new ArrayList<>();
        boolean reqSingleSided = meanderConstraint.singleSided();

        if (!reqSingleSided) {
          if (oppClear >= scanMinAmp) {
            configs.add(new TuningAttemptConfig(prefNorm, oppClear, false));
          }
        }

        if (prefClear >= scanMinAmp) {
          configs.add(new TuningAttemptConfig(prefNorm, prefClear, true));
        }

        if (oppClear >= scanMinAmp) {
          configs.add(new TuningAttemptConfig(oppNorm, oppClear, true));
        }

        for (TuningAttemptConfig config : configs) {
          double startAmp = Math.min(config.clearance, meanderConstraint.maxAmplitude());
          double minAmp = Math.min(scanMinAmp, startAmp);
          if (startAmp < minAmp || startAmp <= 0.0) {
            continue;
          }

          // Smoothly distribute large deficits across multiple corridors when available
          double targetForCorridor = remainingDeficit;
          if (corridors.size() > 1 && remainingDeficit > 4.0 * (2.0 * spacing)) {
            targetForCorridor =
                Math.min(remainingDeficit, Math.max(remainingDeficit / 2.0, 3.0 * (2.0 * spacing)));
          }

          NetMeanderConstraint activeConstraint =
              config.forceSingleSided ? meanderConstraint.withSingleSided(true) : meanderConstraint;

          double step = Math.max(100.0, (startAmp - minAmp) / 4.0);
          for (double testAmp = startAmp; testAmp >= minAmp; testAmp -= step) {
            Point[] meanderPoints =
                MeanderGenerator.generateMeander(
                    corridor.startPoint(),
                    corridor.endPoint(),
                    targetForCorridor,
                    activeConstraint,
                    config.normal,
                    testAmp,
                    fortyFiveDegreeOnly);

            if (meanderPoints == null || meanderPoints.length < 3) {
              continue;
            }

            Polyline newPolyline =
                spliceMeanderIntoTrace(corridor.trace(), corridor.segmentIndex(), meanderPoints);
            if (newPolyline == null) {
              continue;
            }

            int layer = corridor.trace().getLayer();
            int halfWidth = corridor.trace().getHalfWidth();
            int[] netNumbers = corridor.trace().netNumbers;
            int clearanceClass = corridor.trace().clearanceClassIndex();

            // Check if replacing this trace causes any clearance conflicts
            FixedState originalState = corridor.trace().getFixedState();
            corridor.trace().setFixedState(FixedState.UNFIXED);
            board.removeItem(corridor.trace());
            boolean fits =
                board.checkPolylineTrace(newPolyline, layer, halfWidth, netNumbers, clearanceClass);

            if (fits) {
              board.insertTrace(
                  newPolyline, layer, halfWidth, netNumbers, clearanceClass, originalState);
              insertedCount++;
              replacedAny = true;
              break; // Break inner amplitude relaxation loop
            } else {
              // Revert removal if candidate meander failed clearance check
              board.insertTrace(
                  corridor.trace().polyline(),
                  layer,
                  halfWidth,
                  netNumbers,
                  clearanceClass,
                  originalState);
            }
          }

          if (replacedAny) {
            break; // Break config loop
          }
        }

        if (replacedAny) {
          break; // Break corridor loop to re-scan fresh corridors on updated board state
        }
      }

      if (!replacedAny) {
        break;
      }
    }

    return insertedCount;
  }

  private static double getDeficit(Net net) {
    if (net == null) {
      return 0.0;
    }
    NetLengthConstraint c = net.getLengthConstraint();
    if (c == null || !c.isConstrained()) {
      return 0.0;
    }
    double target = c.targetLength();
    double current = net.getTraceLength();
    return (target > current && !c.isSatisfied(current)) ? (target - current) : 0.0;
  }

  /**
   * Splices meander corner points into the given trace's polyline at the specified segment index.
   */
  public static Polyline spliceMeanderIntoTrace(
      PolylineTrace trace, int segmentIndex, Point[] meanderPoints) {
    if (trace == null || meanderPoints == null || meanderPoints.length < 2) {
      return null;
    }

    Polyline poly = trace.polyline();
    int cornerCount = poly.cornerCount();
    if (segmentIndex < 0 || segmentIndex >= cornerCount - 1) {
      return null;
    }

    List<Point> newCorners = new ArrayList<>(cornerCount + meanderPoints.length);

    // Keep all corners preceding the segment
    for (int i = 0; i <= segmentIndex; i++) {
      Point pt = poly.corner(i);
      if (newCorners.isEmpty() || !newCorners.get(newCorners.size() - 1).equals(pt)) {
        newCorners.add(pt);
      }
    }

    // Insert intermediate meander points (skipping first and last if they duplicate segment ends)
    for (int i = 0; i < meanderPoints.length; i++) {
      Point pt = meanderPoints[i];
      if (newCorners.isEmpty() || !newCorners.get(newCorners.size() - 1).equals(pt)) {
        newCorners.add(pt);
      }
    }

    // Keep all corners following the segment
    for (int i = segmentIndex + 1; i < cornerCount; i++) {
      Point pt = poly.corner(i);
      if (newCorners.isEmpty() || !newCorners.get(newCorners.size() - 1).equals(pt)) {
        newCorners.add(pt);
      }
    }

    if (newCorners.size() < 2) {
      return null;
    }

    return new Polyline(newCorners.toArray(new Point[0]));
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
