package app.freerouting.board.trace;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.DrillItem;
import app.freerouting.board.model.items.Item;
import app.freerouting.geometry.planar.Direction;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntDirection;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.rules.Net;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scans routed traces of a net to identify candidate straight corridors suitable for inserting
 * trace length tuning meander patterns.
 */
public final class TraceCorridorScanner {

  private static final double[] SAMPLE_FRACTIONS = {
    0.10, 0.20, 0.30, 0.40, 0.50, 0.60, 0.70, 0.80, 0.90
  };

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
      double rightClearDepth) {

    /** Returns the maximum clear depth across both left and right sides. */
    public double maxClearDepth() {
      return Math.max(leftClearDepth, rightClearDepth);
    }

    /** Returns the usable area capacity score (length * maxClearDepth). */
    public double capacity() {
      return length * maxClearDepth();
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

    /**
     * Calculates the minimum Euclidean distance from this corridor segment to a target point.
     * Useful for prioritizing corridors closest to uncoupled differential pair bends.
     */
    public double distanceTo(Point point) {
      if (point == null) {
        return Double.MAX_VALUE;
      }
      FloatPoint fp = point.toFloat();
      FloatPoint s = startPoint.toFloat();
      FloatPoint e = endPoint.toFloat();
      double segLenSq = (e.x - s.x) * (e.x - s.x) + (e.y - s.y) * (e.y - s.y);
      if (segLenSq <= 0.0) {
        return fp.distance(s);
      }
      double t =
          Math.max(
              0.0,
              Math.min(1.0, ((fp.x - s.x) * (e.x - s.x) + (fp.y - s.y) * (e.y - s.y)) / segLenSq));
      double projX = s.x + t * (e.x - s.x);
      double projY = s.y + t * (e.y - s.y);
      return Math.hypot(fp.x - projX, fp.y - projY);
    }

    /**
     * Computes an EDA quality-weighted score balancing corridor usable capacity with proximity to a
     * target point. Spacious, high-clearance corridors near the target score highest, while cramped
     * stubs are heavily penalized.
     *
     * @param targetPoint the reference point (e.g. uncoupled bend or pin)
     * @param characteristicDistance distance normalization constant in board units
     * @return quality score for sorting candidate corridors
     */
    public double qualityScore(Point targetPoint, double characteristicDistance) {
      if (targetPoint == null) {
        return capacity();
      }
      double dist = distanceTo(targetPoint);
      double normDist = dist / Math.max(1000.0, characteristicDistance);
      return capacity() / (1.0 + normDist);
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

    Set<Point> terminalCenters = new HashSet<>();
    for (Item item : connectables) {
      if (item instanceof DrillItem drillItem) {
        terminalCenters.add(drillItem.getCenter());
      }
    }

    double oneMm =
        (board.communication != null)
            ? board.communication.getResolution(app.freerouting.board.model.structure.Unit.MM)
            : 10000.0;

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

      double terminalTolerance = Math.max(500.0, 3.0 * halfWidth);
      double leadInDistance = Math.max(oneMm, 6.0 * halfWidth);

      boolean startsAtTerminal =
          isNearTerminal(trace.firstCorner(), terminalCenters, terminalTolerance);
      boolean endsAtTerminal =
          isNearTerminal(trace.lastCorner(), terminalCenters, terminalTolerance);

      for (int i = 0; i < cornerCount - 1; i++) {
        Point p0 = trace.polyline().corner(i);
        Point p1 = trace.polyline().corner(i + 1);

        FloatPoint fp0 = p0.toFloat();
        FloatPoint fp1 = p1.toFloat();
        double segLength = fp0.distance(fp1);

        boolean segStartsAtTerminal =
            (i == 0 && startsAtTerminal) || isNearTerminal(p0, terminalCenters, terminalTolerance);
        boolean segEndsAtTerminal =
            (i == cornerCount - 2 && endsAtTerminal)
                || isNearTerminal(p1, terminalCenters, terminalTolerance);

        double leadInStart = segStartsAtTerminal ? leadInDistance : 0.0;
        double leadInEnd = segEndsAtTerminal ? leadInDistance : 0.0;
        double totalLeadIn = leadInStart + leadInEnd;

        // Reject stubs too short to accommodate required terminal breakout keepout plus meanders
        if (segLength < minLength + totalLeadIn) {
          continue;
        }

        Direction baseDir = Direction.getInstance(p0, p1);
        if (baseDir == null || !baseDir.isMultipleOf45Degree()) {
          continue;
        }

        // Trim segment endpoints away from terminals so meanders never violate breakout keepouts
        Point effStart = p0;
        Point effEnd = p1;
        double effLength = segLength;

        if (totalLeadIn > 0.0) {
          double ux = (fp1.x - fp0.x) / segLength;
          double uy = (fp1.y - fp0.y) / segLength;

          if (segStartsAtTerminal) {
            effStart =
                new IntPoint(
                    (int) Math.round(fp0.x + ux * leadInStart),
                    (int) Math.round(fp0.y + uy * leadInStart));
          }
          if (segEndsAtTerminal) {
            effEnd =
                new IntPoint(
                    (int) Math.round(fp1.x - ux * leadInEnd),
                    (int) Math.round(fp1.y - uy * leadInEnd));
          }
          effLength = effStart.toFloat().distance(effEnd.toFloat());
          if (effLength < minLength) {
            continue;
          }
        }

        FloatPoint effFp0 = effStart.toFloat();
        FloatPoint effFp1 = effEnd.toFloat();

        // Left normal (+90) and right normal (-90)
        Direction leftDir = baseDir.turn45Degree(2);
        Direction rightDir = baseDir.turn45Degree(-2);

        double probeDist = (maxTestAmplitude > 0) ? maxTestAmplitude : 2000.0;
        double[] leftOffset = getNormalOffset(leftDir, probeDist);
        double[] rightOffset = getNormalOffset(rightDir, probeDist);

        // Sample clearance at points along trimmed segment to detect intruding obstacles
        double leftClear = probeDist;
        double rightClear = probeDist;
        for (double f : SAMPLE_FRACTIONS) {
          IntPoint samplePoint =
              new IntPoint(
                  (int) Math.round(effFp0.x + f * (effFp1.x - effFp0.x)),
                  (int) Math.round(effFp0.y + f * (effFp1.y - effFp0.y)));
          double curLeft =
              probeClearance(
                  board,
                  samplePoint,
                  leftOffset,
                  probeDist,
                  layer,
                  netNoArr,
                  halfWidth,
                  clearanceClass);
          double curRight =
              probeClearance(
                  board,
                  samplePoint,
                  rightOffset,
                  probeDist,
                  layer,
                  netNoArr,
                  halfWidth,
                  clearanceClass);
          leftClear = Math.min(leftClear, curLeft);
          rightClear = Math.min(rightClear, curRight);
        }

        if (Math.max(leftClear, rightClear) >= minAmplitude) {
          candidates.add(
              new CandidateCorridor(trace, i, effStart, effEnd, effLength, leftClear, rightClear));
        }
      }
    }

    // Sort corridors primarily by usable area capacity descending, tie-breaking by length
    // descending
    candidates.sort(
        Comparator.comparingDouble(CandidateCorridor::capacity)
            .thenComparingDouble(CandidateCorridor::length)
            .reversed());
    return candidates;
  }

  /**
   * Scans candidate straight corridors for the specified net and sorts them using an EDA quality
   * metric that balances usable capacity with proximity to a target point (e.g. an uncoupled
   * differential pair bend or pin), prioritizing spacious, high-clearance corridors near the target
   * over cramped stubs.
   *
   * @param board the routing board
   * @param net the net whose traces should be scanned
   * @param minLength minimum straight segment length required
   * @param minAmplitude minimum required amplitude
   * @param maxTestAmplitude maximum amplitude depth to probe
   * @param targetPoint the reference point to measure proximity against
   * @return list of candidate corridors sorted by EDA quality score descending
   */
  public static List<CandidateCorridor> scanCorridorsNearPoint(
      RoutingBoard board,
      Net net,
      double minLength,
      double minAmplitude,
      double maxTestAmplitude,
      Point targetPoint) {
    List<CandidateCorridor> corridors =
        scanCorridors(board, net, minLength, minAmplitude, maxTestAmplitude);
    if (corridors.isEmpty() || targetPoint == null) {
      return corridors;
    }
    double charDist =
        (board != null && board.communication != null)
            ? 2.5 * board.communication.getResolution(app.freerouting.board.model.structure.Unit.MM)
            : 25000.0;

    List<CandidateCorridor> sorted = new ArrayList<>(corridors);
    sorted.sort(
        Comparator.comparingDouble((CandidateCorridor c) -> c.qualityScore(targetPoint, charDist))
            .reversed()
            .thenComparing(Comparator.comparingDouble(CandidateCorridor::capacity).reversed()));
    return sorted;
  }

  private static boolean isNearTerminal(Point point, Set<Point> terminalCenters, double tolerance) {
    if (point == null || terminalCenters.isEmpty()) {
      return false;
    }
    if (terminalCenters.contains(point)) {
      return true;
    }
    FloatPoint fp = point.toFloat();
    for (Point tc : terminalCenters) {
      if (fp.distance(tc.toFloat()) <= tolerance) {
        return true;
      }
    }
    return false;
  }

  private static double[] getNormalOffset(Direction normalDir, double distance) {
    if (normalDir == null || distance <= 0.0) {
      return new double[] {0.0, 0.0};
    }
    double vx;
    double vy;
    if (normalDir instanceof IntDirection intDir) {
      vx = intDir.x;
      vy = intDir.y;
    } else {
      FloatPoint normVec = normalDir.getVector().toFloat();
      vx = normVec.x;
      vy = normVec.y;
    }
    double normLen = Math.hypot(vx, vy);
    if (normLen <= 0.0) {
      return new double[] {0.0, 0.0};
    }
    return new double[] {(vx / normLen) * distance, (vy / normLen) * distance};
  }

  private static double probeClearance(
      RoutingBoard board,
      IntPoint fromPoint,
      double[] normalOffset,
      double probeDistance,
      int layer,
      int[] netNumbers,
      int halfWidth,
      int clearanceClass) {
    if (normalOffset == null || (normalOffset[0] == 0.0 && normalOffset[1] == 0.0)) {
      return 0.0;
    }

    IntPoint targetPoint =
        new IntPoint(
            (int) Math.round(fromPoint.x + normalOffset[0]),
            (int) Math.round(fromPoint.y + normalOffset[1]));
    double allowed =
        board.checkTraceSegment(
            fromPoint, targetPoint, layer, netNumbers, halfWidth, clearanceClass, false);

    if (allowed == Integer.MAX_VALUE || allowed >= probeDistance) {
      return probeDistance;
    }
    return Math.max(0.0, allowed);
  }
}
