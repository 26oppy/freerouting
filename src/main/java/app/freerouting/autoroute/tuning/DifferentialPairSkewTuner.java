package app.freerouting.autoroute.tuning;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.DrillItem;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.structure.AngleRestriction;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.board.trace.TraceCorridorScanner;
import app.freerouting.board.trace.TraceCorridorScanner.CandidateCorridor;
import app.freerouting.geometry.planar.Direction;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntPoint;
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
import java.util.ArrayList;
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

      // Determine physical DRC clearance floor: 2 * halfWidth + clearance
      int defaultHalfWidth =
          board.rules != null ? board.rules.getTraceHalfWidth(shorterNet.netNumber, 0) : 1000;
      int clClass =
          shorterNet.getNetClass() != null ? shorterNet.getNetClass().getTraceClearanceClass() : 0;
      int minClearance =
          board.rules != null && board.rules.clearanceMatrix != null
              ? board.rules.clearanceMatrix.getValue(clClass, clClass, 0, false)
              : 1500;
      double minPhysicalSpacing = 2.0 * defaultHalfWidth + minClearance;
      double effSpacing = Math.max(spacing > 0 ? spacing : 6000.0, minPhysicalSpacing);
      double minLength = 3.0 * effSpacing;
      double scanMinAmp = Math.min(constraint.minAmplitude(), Math.max(30.0, currentSkew / 4.0));

      // Locate bends where intra-pair phase divergence was introduced
      List<Point> bends = findBendsOnNet(board, shorterNet);
      Point primaryBend = (!bends.isEmpty()) ? bends.get(0) : null;

      // Prioritize straight corridors immediately adjacent to or exiting the bend
      List<CandidateCorridor> corridors =
          (primaryBend != null)
              ? TraceCorridorScanner.scanCorridorsNearPoint(
                  board, shorterNet, minLength, scanMinAmp, constraint.maxAmplitude(), primaryBend)
              : TraceCorridorScanner.scanCorridors(
                  board, shorterNet, minLength, scanMinAmp, constraint.maxAmplitude());

      if (corridors.isEmpty()) {
        break;
      }

      boolean insertedInPass = false;
      for (CandidateCorridor corridor : corridors) {
        if (corridor.trace() == null || !corridor.trace().isOnTheBoard()) {
          continue;
        }

        // Guarantee outward deflection away from the partner trace into open board space
        Direction normalDir = determineOutwardNormal(corridor, board, partnerNet);
        if (normalDir == null) {
          normalDir = corridor.preferredNormal();
        }

        double maxClear =
            (normalDir.equals(corridor.preferredNormal()) && corridor.maxClearDepth() > 0)
                ? corridor.maxClearDepth()
                : Math.max(corridor.leftClearDepth(), corridor.rightClearDepth());
        double startAmp = Math.min(maxClear, constraint.maxAmplitude());
        double minAmp = Math.min(scanMinAmp, Math.max(20.0, currentSkew / 4.0));
        if (startAmp < minAmp) {
          continue;
        }

        int traceHalfWidth = corridor.trace().getHalfWidth();
        int traceClClass = corridor.trace().clearanceClassIndex();
        int traceLayer = corridor.trace().getLayer();
        int traceClearance =
            (board.rules != null && board.rules.clearanceMatrix != null)
                ? board.rules.clearanceMatrix.getValue(
                    traceClClass, traceClClass, traceLayer, false)
                : 0;
        double corridorMinSpacing = 2.0 * traceHalfWidth + traceClearance;
        double passSpacing = Math.max(effSpacing, corridorMinSpacing);
        if (corridor.length() < 3.0 * passSpacing) {
          continue;
        }

        // Amplitude relaxation: start with best amplitude, step down if obstacles intervene
        double step = Math.max(100.0, (startAmp - minAmp) / 4.0);
        for (double testAmp = startAmp; testAmp >= minAmp; testAmp -= step) {
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
                  normalDir,
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

  /**
   * Identifies all corners where a net changes direction (bends), representing points of curvature
   * where intra-pair phase skew accumulates.
   */
  private static List<Point> findBendsOnNet(RoutingBoard board, Net net) {
    List<Point> bends = new ArrayList<>();
    Collection<Item> items = board.getConnectableItems(net.netNumber);
    for (Item item : items) {
      if (item instanceof PolylineTrace trace) {
        Polyline poly = trace.polyline();
        int count = poly.cornerCount();
        for (int i = 1; i < count - 1; i++) {
          Point prev = poly.corner(i - 1);
          Point curr = poly.corner(i);
          Point next = poly.corner(i + 1);
          Direction d1 = Direction.getInstance(prev, curr);
          Direction d2 = Direction.getInstance(curr, next);
          if (d1 != null && d2 != null && !d1.equals(d2)) {
            bends.add(curr);
          }
        }
      }
    }
    return bends;
  }

  /**
   * Determines the outward normal direction pointing away from the partner net to preserve
   * differential coupling and odd-mode impedance.
   */
  private static Direction determineOutwardNormal(
      CandidateCorridor corridor, RoutingBoard board, Net partnerNet) {
    Direction baseDir = Direction.getInstance(corridor.startPoint(), corridor.endPoint());
    if (baseDir == null) {
      return corridor.preferredNormal();
    }
    Direction leftDir = baseDir.turn45Degree(2);
    Direction rightDir = baseDir.turn45Degree(-2);

    FloatPoint p0 = corridor.startPoint().toFloat();
    FloatPoint p1 = corridor.endPoint().toFloat();
    Point midPoint =
        new IntPoint((int) Math.round((p0.x + p1.x) / 2.0), (int) Math.round((p0.y + p1.y) / 2.0));

    Point nearestPartnerPoint = findNearestPointOnNet(board, partnerNet, midPoint);
    if (nearestPartnerPoint == null) {
      return corridor.preferredNormal();
    }

    FloatPoint mid = midPoint.toFloat();
    FloatPoint partner = nearestPartnerPoint.toFloat();
    double toPartnerX = partner.x - mid.x;
    double toPartnerY = partner.y - mid.y;

    FloatPoint leftVec = leftDir.getVector().toFloat();
    double leftDot = leftVec.x * toPartnerX + leftVec.y * toPartnerY;

    FloatPoint rightVec = rightDir.getVector().toFloat();
    double rightDot = rightVec.x * toPartnerX + rightVec.y * toPartnerY;

    // Normal with smaller/negative dot product points AWAY from the partner net
    Direction outwardDir = (leftDot <= rightDot) ? leftDir : rightDir;
    double outwardClear =
        (outwardDir.equals(leftDir)) ? corridor.leftClearDepth() : corridor.rightClearDepth();

    if (outwardClear >= 50.0) {
      return outwardDir;
    }
    return corridor.preferredNormal();
  }

  /** Finds the closest point on any trace or terminal of the specified net to the query point. */
  private static Point findNearestPointOnNet(RoutingBoard board, Net net, Point queryPoint) {
    if (board == null || net == null || queryPoint == null) {
      return null;
    }
    FloatPoint qp = queryPoint.toFloat();
    Point bestPoint = null;
    double minDistanceSq = Double.MAX_VALUE;

    Collection<Item> items = board.getConnectableItems(net.netNumber);
    for (Item item : items) {
      if (item instanceof PolylineTrace trace) {
        Polyline poly = trace.polyline();
        int count = poly.cornerCount();
        for (int i = 0; i < count - 1; i++) {
          FloatPoint p0 = poly.corner(i).toFloat();
          FloatPoint p1 = poly.corner(i + 1).toFloat();
          double segLenSq = (p1.x - p0.x) * (p1.x - p0.x) + (p1.y - p0.y) * (p1.y - p0.y);
          if (segLenSq <= 0.0) {
            double dSq = qp.distanceSquare(p0);
            if (dSq < minDistanceSq) {
              minDistanceSq = dSq;
              bestPoint = poly.corner(i);
            }
            continue;
          }
          double t =
              Math.max(
                  0.0,
                  Math.min(
                      1.0,
                      ((qp.x - p0.x) * (p1.x - p0.x) + (qp.y - p0.y) * (p1.y - p0.y)) / segLenSq));
          double projX = p0.x + t * (p1.x - p0.x);
          double projY = p0.y + t * (p1.y - p0.y);
          double dSq = (qp.x - projX) * (qp.x - projX) + (qp.y - projY) * (qp.y - projY);
          if (dSq < minDistanceSq) {
            minDistanceSq = dSq;
            bestPoint = new IntPoint((int) Math.round(projX), (int) Math.round(projY));
          }
        }
      } else if (item instanceof DrillItem drillItem) {
        Point center = drillItem.getCenter();
        if (center != null) {
          double dSq = qp.distanceSquare(center.toFloat());
          if (dSq < minDistanceSq) {
            minDistanceSq = dSq;
            bestPoint = center;
          }
        }
      }
    }
    return bestPoint;
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
