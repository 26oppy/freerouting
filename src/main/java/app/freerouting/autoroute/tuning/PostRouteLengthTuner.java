package app.freerouting.autoroute.tuning;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.structure.AngleRestriction;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.board.trace.TraceCorridorScanner;
import app.freerouting.board.trace.TraceCorridorScanner.CandidateCorridor;
import app.freerouting.geometry.planar.MeanderGenerator;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;
import app.freerouting.rules.NetLengthConstraint;
import app.freerouting.rules.NetMeanderConstraint;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Post-route optimization engine that equalizes trace lengths to satisfy target length constraints
 * by inserting serpentine meander (accordion) patterns into straight trace corridors.
 */
public final class PostRouteLengthTuner implements Serializable {

  private static final long serialVersionUID = 1L;

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

    int attempted = 0;
    int success = 0;
    int meandersInserted = 0;
    double totalAddedLength = 0.0;

    boolean fortyFiveDegreeOnly = board.rules.getTraceAngleRestriction() != AngleRestriction.NONE;

    int netCount = board.rules.nets.maxNetNumber();
    for (int netNo = 1; netNo <= netCount; netNo++) {
      Net net = board.rules.nets.get(netNo);
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
      if (currentLength >= targetLength || lengthConstraint.isSatisfied(currentLength)) {
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
                toUserUnits(board, added),
                toUserUnits(board, startLength),
                toUserUnits(board, endLength),
                toUserUnits(board, targetLength)));
      }
    }

    double totalAddedMm = toUserUnits(board, totalAddedLength);
    return new TuningReport(attempted, success, meandersInserted, totalAddedMm);
  }

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
    double minLength = 4.0 * spacing;

    for (int iter = 0; iter < maxIterations; iter++) {
      double currentLength = net.getTraceLength();
      double remainingDeficit = targetLength - currentLength;
      if (remainingDeficit <= 0.0 || lengthConstraint.isSatisfied(currentLength)) {
        break;
      }

      List<CandidateCorridor> corridors =
          TraceCorridorScanner.scanCorridors(
              board,
              net,
              minLength,
              meanderConstraint.minAmplitude(),
              meanderConstraint.maxAmplitude());

      if (corridors.isEmpty()) {
        break;
      }

      boolean replacedAny = false;
      for (CandidateCorridor corridor : corridors) {
        Point[] meanderPoints =
            MeanderGenerator.generateMeander(
                corridor.startPoint(),
                corridor.endPoint(),
                remainingDeficit,
                meanderConstraint,
                corridor.preferredNormal(),
                corridor.maxClearDepth(),
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
        board.removeItem(corridor.trace());
        boolean fits =
            board.checkPolylineTrace(newPolyline, layer, halfWidth, netNumbers, clearanceClass);

        if (fits) {
          board.insertTrace(
              newPolyline, layer, halfWidth, netNumbers, clearanceClass, FixedState.USER_FIXED);
          insertedCount++;
          replacedAny = true;
          break; // Break inner loop to re-scan fresh corridors on updated board state
        } else {
          // Revert removal if candidate meander failed clearance check
          board.insertTrace(
              corridor.trace().polyline(),
              layer,
              halfWidth,
              netNumbers,
              clearanceClass,
              corridor.trace().getFixedState());
        }
      }

      if (!replacedAny) {
        break;
      }
    }

    return insertedCount;
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

  private static double toUserUnits(RoutingBoard board, double boardUnits) {
    if (board != null
        && board.communication != null
        && board.communication.coordinateTransform != null) {
      return board.communication.coordinateTransform.boardToDsn(boardUnits);
    }
    return boardUnits;
  }
}
