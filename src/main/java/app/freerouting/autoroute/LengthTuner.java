package app.freerouting.autoroute;

import app.freerouting.board.AngleRestriction;
import app.freerouting.board.FixedState;
import app.freerouting.board.Item;
import app.freerouting.board.PolylineTrace;
import app.freerouting.board.RoutingBoard;
import app.freerouting.board.Unit;
import app.freerouting.core.RoutingJob;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;
import app.freerouting.rules.NetClass;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Post-route pass that attempts to increase route length for nets with configured target length.
 */
public class LengthTuner {

  private final RoutingJob job;
  private final RoutingBoard board;
  private final int amplitudeBoardUnits;
  private final int pitchBoardUnits;

  public LengthTuner(RoutingJob job) {
    this.job = job;
    this.board = job.board;
    if (this.board == null) {
      this.amplitudeBoardUnits = 1;
      this.pitchBoardUnits = 1;
      return;
    }
    int boardResolution = Math.max(1, board.communication.resolution);
    this.amplitudeBoardUnits = Math.max(1, (int) Math.round(
        Unit.scale(job.routerSettings.getLengthTuningDefaultAmplitudeUm() * boardResolution, Unit.UM,
            board.communication.unit)));
    this.pitchBoardUnits = Math.max(1, (int) Math.round(
        Unit.scale(job.routerSettings.getLengthTuningDefaultPitchUm() * boardResolution, Unit.UM,
            board.communication.unit)));
  }

  public void run() {
    if (this.board == null) {
      return;
    }
    if (this.board.rules.get_trace_angle_restriction() != AngleRestriction.NINETY_DEGREE) {
      FRLogger.info("[LengthTuner] Skipping length tuning because only 90-degree routing is supported.");
      return;
    }
    for (int netNo = 1; netNo <= board.rules.nets.max_net_no(); netNo++) {
      if (job.thread != null && job.thread.isStopRequested()) {
        return;
      }
      Net net = board.rules.nets.get(netNo);
      if (net == null) {
        continue;
      }
      NetClass netClass = net.get_class();
      if (netClass == null) {
        continue;
      }
      double targetLength = netClass.get_target_trace_length();
      double tolerance = netClass.get_length_tolerance();
      if (targetLength <= 0) {
        continue;
      }
      if (!isNetFullyRouted(net)) {
        continue;
      }
      double currentLength = net.get_trace_length();
      double deficit = targetLength - tolerance - currentLength;
      if (deficit <= 0) {
        continue;
      }
      tuneNet(net, deficit);
      double newLength = net.get_trace_length();
      if (newLength > currentLength) {
        FRLogger.info(String.format(
            "[LengthTuner] Net '%s' extended from %.3fmm to %.3fmm (Target: %.3fmm)",
            net.name,
            boardLengthToMm(currentLength),
            boardLengthToMm(newLength),
            boardLengthToMm(targetLength)));
      }
    }
  }

  private void tuneNet(Net net, double deficit) {
    double remaining = deficit;
    while (remaining > 0) {
      SegmentCandidate bestCandidate = getBestCandidate(net);
      if (bestCandidate == null) {
        return;
      }
      double lengthAdded = tryTuneSegment(bestCandidate.trace(), bestCandidate.segmentIndex(), net, remaining);
      if (lengthAdded <= 0) {
        return;
      }
      remaining -= lengthAdded;
    }
  }

  private SegmentCandidate getBestCandidate(Net net) {
    List<SegmentCandidate> candidates = new ArrayList<>();
    Collection<Item> items = board.get_connectable_items(net.net_number);
    for (Item item : items) {
      if (!(item instanceof PolylineTrace trace)) {
        continue;
      }
      if (trace.net_count() != 1 || trace.get_net_no(0) != net.net_number) {
        continue;
      }
      int cornerCount = trace.corner_count();
      for (int segmentIndex = 0; segmentIndex < cornerCount - 1; segmentIndex++) {
        Point from = trace.polyline().corner(segmentIndex);
        Point to = trace.polyline().corner(segmentIndex + 1);
        if (!(from instanceof IntPoint fromInt) || !(to instanceof IntPoint toInt)) {
          continue;
        }
        int dx = toInt.x - fromInt.x;
        int dy = toInt.y - fromInt.y;
        if ((dx != 0) == (dy != 0)) {
          continue;
        }
        int length = Math.abs(dx) + Math.abs(dy);
        if (length < 2 * pitchBoardUnits) {
          continue;
        }
        candidates.add(new SegmentCandidate(trace, segmentIndex, length));
      }
    }
    return candidates.stream()
        .max(Comparator.comparingInt(SegmentCandidate::length))
        .orElse(null);
  }

  private double tryTuneSegment(PolylineTrace trace, int segmentIndex, Net net, double deficit) {
    Point from = trace.polyline().corner(segmentIndex);
    Point to = trace.polyline().corner(segmentIndex + 1);
    if (!(from instanceof IntPoint start) || !(to instanceof IntPoint end)) {
      return 0;
    }
    int dx = end.x - start.x;
    int dy = end.y - start.y;
    if ((dx != 0) == (dy != 0)) {
      return 0;
    }

    int segmentLength = Math.abs(dx) + Math.abs(dy);
    int cyclesBySpace = segmentLength / (2 * pitchBoardUnits);
    int cyclesByDeficit = (int) Math.ceil(deficit / (2.0 * amplitudeBoardUnits));
    int cycleCount = Math.max(0, Math.min(cyclesBySpace, cyclesByDeficit));
    if (cycleCount <= 0) {
      return 0;
    }

    int dirX = Integer.signum(dx);
    int dirY = Integer.signum(dy);
    int perpX = -dirY;
    int perpY = dirX;

    List<IntPoint> replacementPoints = new ArrayList<>();
    replacementPoints.add(start);
    IntPoint cursor = start;
    int[] netNoArr = new int[] { net.net_number };

    for (int i = 0; i < cycleCount; i++) {
      IntPoint step1 = move(cursor, perpX * amplitudeBoardUnits, perpY * amplitudeBoardUnits);
      IntPoint step2 = move(step1, dirX * pitchBoardUnits, dirY * pitchBoardUnits);
      IntPoint step3 = move(cursor, dirX * pitchBoardUnits, dirY * pitchBoardUnits);
      IntPoint step4 = move(step3, dirX * pitchBoardUnits, dirY * pitchBoardUnits);
      if (!isSegmentClear(cursor, step1, trace, netNoArr)
          || !isSegmentClear(step1, step2, trace, netNoArr)
          || !isSegmentClear(step2, step3, trace, netNoArr)
          || !isSegmentClear(step3, step4, trace, netNoArr)) {
        return 0;
      }
      replacementPoints.add(step1);
      replacementPoints.add(step2);
      replacementPoints.add(step3);
      replacementPoints.add(step4);
      cursor = step4;
    }

    if (!cursor.equals(end) && !isSegmentClear(cursor, end, trace, netNoArr)) {
      return 0;
    }
    if (!cursor.equals(end)) {
      replacementPoints.add(end);
    }

    Point[] newCorners = replaceSegmentCorners(trace, segmentIndex, replacementPoints);
    if (newCorners.length < 2) {
      return 0;
    }

    double netLengthBefore = net.get_trace_length();
    FixedState fixedState = trace.get_fixed_state();
    int layer = trace.get_layer();
    int halfWidth = trace.get_half_width();
    int clearanceClass = trace.clearance_class_no();
    board.remove_item(trace);
    board.insert_trace(newCorners, layer, halfWidth, netNoArr, clearanceClass, fixedState);

    // Read back effective new length from current net items after normalization/combining.
    double netLengthAfter = net.get_trace_length();
    return Math.max(0.0, netLengthAfter - netLengthBefore);
  }

  private boolean isSegmentClear(IntPoint from, IntPoint to, PolylineTrace trace, int[] netNoArr) {
    if (from.equals(to)) {
      return true;
    }
    double needed = from.to_float().distance(to.to_float());
    double allowed = board.check_trace_segment(from, to, trace.get_layer(), netNoArr, trace.get_half_width(),
        trace.clearance_class_no(), true);
    return allowed == Integer.MAX_VALUE || allowed >= needed - 1.0;
  }

  private Point[] replaceSegmentCorners(PolylineTrace trace, int segmentIndex, List<IntPoint> replacementSegment) {
    List<Point> corners = new ArrayList<>();
    int cornerCount = trace.corner_count();
    for (int i = 0; i < segmentIndex; i++) {
      corners.add(trace.polyline().corner(i));
    }
    corners.addAll(replacementSegment);
    for (int i = segmentIndex + 2; i < cornerCount; i++) {
      corners.add(trace.polyline().corner(i));
    }
    List<Point> deduplicated = new ArrayList<>();
    for (Point point : corners) {
      if (deduplicated.isEmpty() || !deduplicated.get(deduplicated.size() - 1).equals(point)) {
        deduplicated.add(point);
      }
    }
    return deduplicated.toArray(new Point[0]);
  }

  private boolean isNetFullyRouted(Net net) {
    Collection<Item> connectables = board.get_connectable_items(net.net_number);
    for (Item item : connectables) {
      if (!item.get_unconnected_set(net.net_number).isEmpty()) {
        return false;
      }
    }
    return !connectables.isEmpty();
  }

  private IntPoint move(IntPoint point, int dx, int dy) {
    return new IntPoint(point.x + dx, point.y + dy);
  }

  private double boardLengthToMm(double boardLength) {
    double inBoardUnit = boardLength / Math.max(1, board.communication.resolution);
    return Unit.scale(inBoardUnit, board.communication.unit, Unit.MM);
  }

  private record SegmentCandidate(PolylineTrace trace, int segmentIndex, int length) {
  }
}
