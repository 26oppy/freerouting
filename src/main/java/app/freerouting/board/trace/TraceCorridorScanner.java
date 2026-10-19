package app.freerouting.board.trace;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.DrillItem;
import app.freerouting.board.model.items.Item;
import app.freerouting.geometry.planar.Direction;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.rules.Net;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Scans routed traces of a net to identify candidate straight corridors suitable for inserting
 * trace length tuning meander patterns.
 */
public final class TraceCorridorScanner {

  private TraceCorridorScanner() {
    // Utility class
  }

  /**
   * Represents an evaluated straight corridor segment along a routed trace.
   *
   * @param trace the trace containing this corridor
   * @param segmentIndex the 0-based corner index where this segment begins
   * @param startPoint starting corner of the segment
   * @param endPoint ending corner of the segment
   * @param length length of the segment in board units
   * @param leftClearDepth maximum unobstructed perpendicular depth on the left side (+90°)
   * @param rightClearDepth maximum unobstructed perpendicular depth on the right side (-90°)
   */
  public record CandidateCorridor(
      PolylineTrace trace,
      int segmentIndex,
      Point startPoint,
      Point endPoint,
      double length,
      double leftClearDepth,
      double rightClearDepth)
      implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Returns the maximum clear depth across both left and right sides. */
    public double maxClearDepth() {
      return Math.max(leftClearDepth, rightClearDepth);
    }

    /** Returns true if at least one side has clear depth >= minAmplitude. */
    public boolean hasMinimumClearance(double minAmplitude) {
      return maxClearDepth() >= minAmplitude;
    }

    /** Returns the preferred normal direction with the greatest clear depth. */
    public Direction preferredNormal() {
      Direction baseDir = Direction.getInstance(startPoint, endPoint);
      if (baseDir == null) {
        return null;
      }
      return (leftClearDepth >= rightClearDepth)
          ? baseDir.turn45Degree(2)
          : baseDir.turn45Degree(-2);
    }
  }

  /**
   * Scans all traces of the specified net and extracts candidate straight corridors.
   *
   * @param board the routing board
   * @param net the net whose traces should be scanned
   * @param minLength minimum straight segment length required
   * @param minAmplitude minimum required amplitude
   * @param maxTestAmplitude maximum amplitude depth to probe
   * @return list of candidate corridors sorted by length descending
   */
  public static List<CandidateCorridor> scanCorridors(
      RoutingBoard board, Net net, double minLength, double minAmplitude, double maxTestAmplitude) {
    if (board == null || net == null || minLength <= 0.0) {
      return Collections.emptyList();
    }

    Collection<Item> connectables = board.getConnectableItems(net.netNumber);
    List<CandidateCorridor> candidates = new ArrayList<>();

    int[] netNoArr = new int[] {net.netNumber};

    for (Item item : connectables) {
      if (!(item instanceof PolylineTrace trace)) {
        continue;
      }
      if (trace.netCount() != 1 || !trace.containsNet(net.netNumber)) {
        continue;
      }

      int cornerCount = trace.cornerCount();
      if (cornerCount < 2) {
        continue;
      }

      int layer = trace.getLayer();
      int halfWidth = trace.getHalfWidth();
      int clearanceClass = trace.clearanceClassIndex();

      boolean startsAtTerminal = touchesTerminal(board, trace.firstCorner(), net.netNumber);
      boolean endsAtTerminal = touchesTerminal(board, trace.lastCorner(), net.netNumber);

      for (int i = 0; i < cornerCount - 1; i++) {
        // Protect terminal pad exit segments from meandering
        if (i == 0 && startsAtTerminal) {
          continue;
        }
        if (i == cornerCount - 2 && endsAtTerminal) {
          continue;
        }

        Point p0 = trace.polyline().corner(i);
        Point p1 = trace.polyline().corner(i + 1);

        FloatPoint fp0 = p0.toFloat();
        FloatPoint fp1 = p1.toFloat();
        double segLength = fp0.distance(fp1);

        if (segLength < minLength) {
          continue;
        }

        Direction baseDir = Direction.getInstance(p0, p1);
        if (baseDir == null || !baseDir.isMultipleOf45Degree()) {
          continue;
        }

        // Midpoint of segment
        FloatPoint midFloat = new FloatPoint((fp0.x + fp1.x) / 2.0, (fp0.y + fp1.y) / 2.0);
        IntPoint midPoint = midFloat.round();

        // Left normal (+90) and right normal (-90)
        Direction leftDir = baseDir.turn45Degree(2);
        Direction rightDir = baseDir.turn45Degree(-2);

        double probeDist = (maxTestAmplitude > 0) ? maxTestAmplitude : 2000.0;

        double leftClear =
            probeClearance(
                board, midPoint, leftDir, probeDist, layer, netNoArr, halfWidth, clearanceClass);
        double rightClear =
            probeClearance(
                board, midPoint, rightDir, probeDist, layer, netNoArr, halfWidth, clearanceClass);

        if (Math.max(leftClear, rightClear) >= minAmplitude) {
          candidates.add(new CandidateCorridor(trace, i, p0, p1, segLength, leftClear, rightClear));
        }
      }
    }

    // Sort corridors by longest available length first
    candidates.sort(Comparator.comparingDouble(CandidateCorridor::length).reversed());
    return candidates;
  }

  private static double probeClearance(
      RoutingBoard board,
      IntPoint fromPoint,
      Direction normalDir,
      double probeDistance,
      int layer,
      int[] netNumbers,
      int halfWidth,
      int clearanceClass) {
    FloatPoint normVec = normalDir.getVector().toFloat();
    double normLen = Math.hypot(normVec.x, normVec.y);
    if (normLen <= 0.0) {
      return 0.0;
    }

    double dx = (normVec.x / normLen) * probeDistance;
    double dy = (normVec.y / normLen) * probeDistance;

    IntPoint targetPoint =
        new IntPoint((int) Math.round(fromPoint.x + dx), (int) Math.round(fromPoint.y + dy));
    double allowed =
        board.checkTraceSegment(
            fromPoint, targetPoint, layer, netNumbers, halfWidth, clearanceClass, true);

    if (allowed == Integer.MAX_VALUE || allowed >= probeDistance) {
      return probeDistance;
    }
    return Math.max(0.0, allowed);
  }

  private static boolean touchesTerminal(RoutingBoard board, Point corner, int netNumber) {
    Collection<Item> atPoint = board.getConnectableItems(netNumber);
    for (Item item : atPoint) {
      if (item instanceof DrillItem drillItem && drillItem.getCenter().equals(corner)) {
        return true;
      }
    }
    return false;
  }
}
