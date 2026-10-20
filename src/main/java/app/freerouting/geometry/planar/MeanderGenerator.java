package app.freerouting.geometry.planar;

import app.freerouting.rules.CornerStyle;
import app.freerouting.rules.NetMeanderConstraint;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates 45-degree and orthogonal serpentine meander (accordion) patterns and differential pair
 * skew bumps along straight trace segments to achieve target trace lengths.
 */
public final class MeanderGenerator {

  private static final double SQRT2 = Math.sqrt(2.0);

  private MeanderGenerator() {
    // Utility class
  }

  /**
   * Generates a meander polyline replacing a straight segment to add {@code targetExtraLength}.
   *
   * @param startPoint starting point of the straight baseline segment
   * @param endPoint ending point of the straight baseline segment
   * @param targetExtraLength extra length (in board units) to add
   * @param constraint meander pattern geometric constraints
   * @param preferredNormal preferred perpendicular normal direction, or null for default left
   *     (+90°)
   * @param maxClearAmplitude maximum clear amplitude available from obstacles, or <= 0 for
   *     unconstrained
   * @param fortyFiveDegreeOnly if true, restricts all meander legs to 45-degree increments
   * @return array of points representing the meandered path, or null if a meander cannot fit
   */
  public static Point[] generateMeander(
      Point startPoint,
      Point endPoint,
      double targetExtraLength,
      NetMeanderConstraint constraint,
      Direction preferredNormal,
      double maxClearAmplitude,
      boolean fortyFiveDegreeOnly) {
    if (startPoint == null || endPoint == null || targetExtraLength <= 0.0 || constraint == null) {
      return null;
    }
    if (startPoint.equals(endPoint)) {
      return null;
    }

    FloatPoint p0 = startPoint.toFloat();
    FloatPoint p1 = endPoint.toFloat();

    double vx = p1.x - p0.x;
    double vy = p1.y - p0.y;
    double length = Math.hypot(vx, vy);

    if (length < 2.0) {
      return null;
    }

    Direction baseDir = Direction.getInstance(startPoint, endPoint);
    if (baseDir == null) {
      return null;
    }
    if (fortyFiveDegreeOnly && !baseDir.isMultipleOf45Degree()) {
      return null;
    }

    double ux = vx / length;
    double uy = vy / length;

    // Determine perpendicular normal vector
    double nx;
    double ny;
    if (preferredNormal != null) {
      FloatPoint normPt = preferredNormal.getVector().toFloat();
      double normLen = Math.hypot(normPt.x, normPt.y);
      if (normLen > 0) {
        nx = normPt.x / normLen;
        ny = normPt.y / normLen;
      } else {
        nx = -uy;
        ny = ux;
      }
    } else {
      nx = -uy;
      ny = ux;
    }

    double spacing = constraint.spacing() > 0 ? constraint.spacing() : 600.0;
    double minAmp = constraint.minAmplitude() > 0 ? constraint.minAmplitude() : 200.0;
    double maxAmp = constraint.maxAmplitude() > 0 ? constraint.maxAmplitude() : 1000.0;

    if (maxClearAmplitude > 0) {
      maxAmp = Math.min(maxAmp, maxClearAmplitude);
    }

    // Effective minimum amplitude: allow scaling down for small deficits (e.g. skew bumps or fine
    // tuning) with a minimum manufacturing floor of 20.0 board units (0.02 mm / 20 um)
    double effMinAmp = Math.min(minAmp, Math.max(20.0, targetExtraLength / 2.0));
    if (maxAmp < effMinAmp) {
      return null;
    }

    double effSpacing = spacing > 0 ? spacing : 600.0;
    // A single cycle needs 2 * effSpacing cycle width plus at least 0.5 * effSpacing margin at each
    // end.
    if (length < 3.0 * effSpacing) {
      return null;
    }

    // A cycle spans 2 * effSpacing along baseline. We leave margin at each end.
    double usableLength = length - effSpacing;
    int maxCycles = (int) Math.floor(usableLength / (2.0 * effSpacing));
    if (maxCycles < 1) {
      return null;
    }

    // Estimate corner trimming loss per cycle caused by chamfering or circular filleting
    CornerStyle cornerStyle = constraint.cornerStyle();
    double lossFactor =
        (cornerStyle == CornerStyle.CHAMFER || fortyFiveDegreeOnly)
            ? (2.0 - SQRT2)
            : (2.0 - (Math.PI / 2.0));
    double radiusFraction = 0.4 * (constraint.radiusPercentage() / 100.0);
    double c = 4.0 * lossFactor * radiusFraction;

    // Maximum corner loss when amplitude >= effSpacing
    double maxRadius = effSpacing * radiusFraction;
    double maxCornerLossPerCycle = 4.0 * lossFactor * maxRadius;

    // Extra length per cycle at max amplitude
    double maxEffectiveExtraPerCycle = Math.max(10.0, 2.0 * maxAmp - maxCornerLossPerCycle);
    int neededCycles = (int) Math.ceil(targetExtraLength / maxEffectiveExtraPerCycle);
    int cycleCount = Math.min(maxCycles, Math.max(1, neededCycles));

    // Calculate optimal amplitude for the chosen cycle count
    double targetPerCycle = targetExtraLength / cycleCount;
    double neededAmplitude;
    double transitionTarget = (2.0 - c) * effSpacing;
    if (targetPerCycle < transitionTarget) {
      neededAmplitude = targetPerCycle / Math.max(0.5, 2.0 - c);
    } else {
      neededAmplitude = (targetPerCycle + maxCornerLossPerCycle) / 2.0;
    }
    double amplitude = Math.max(effMinAmp, Math.min(maxAmp, neededAmplitude));

    // Center pattern along baseline segment
    double patternWidth = cycleCount * 2.0 * effSpacing;
    double startOffset = (length - patternWidth) / 2.0;

    List<Point> rawCorners = new ArrayList<>();
    rawCorners.add(startPoint);

    double currentOffset = startOffset;
    boolean singleSided = constraint.singleSided();

    Direction normDir = (preferredNormal != null) ? preferredNormal : baseDir.turn45Degree(2);
    Vector normVector = (normDir != null) ? normDir.getVector() : null;
    int normX = (normVector != null) ? Integer.signum(((IntVector) normVector).x) : 0;
    int normY = (normVector != null) ? Integer.signum(((IntVector) normVector).y) : 1;

    Vector baseDirVector = baseDir.getVector();
    int dirX = Integer.signum(((IntVector) baseDirVector).x);
    int dirY = Integer.signum(((IntVector) baseDirVector).y);
    double scale = baseDir.isDiagonal() ? SQRT2 : 1.0;

    for (int i = 0; i < cycleCount; i++) {
      double sign = singleSided ? 1.0 : ((i % 2 == 0) ? 1.0 : -1.0);
      double curAmp = amplitude * sign;

      Point b1;
      Point k1;
      Point k2;
      Point b2;

      if (fortyFiveDegreeOnly) {
        int offStep = (int) Math.round(currentOffset / scale);
        int spcStep = (int) Math.round(effSpacing / scale);
        int ampStep = (int) Math.round(curAmp / scale);

        int b1x = (int) Math.round(p0.x) + offStep * dirX;
        int b1y = (int) Math.round(p0.y) + offStep * dirY;
        b1 = new IntPoint(b1x, b1y);
        k1 = new IntPoint(b1x + ampStep * normX, b1y + ampStep * normY);
        k2 =
            new IntPoint(
                b1x + ampStep * normX + spcStep * dirX, b1y + ampStep * normY + spcStep * dirY);
        b2 = new IntPoint(b1x + spcStep * dirX, b1y + spcStep * dirY);
      } else {
        // Base point 1 (entry into meander loop)
        double b1x = p0.x + currentOffset * ux;
        double b1y = p0.y + currentOffset * uy;

        // Peak point 1
        double k1x = b1x + curAmp * nx;
        double k1y = b1y + curAmp * ny;

        // Peak point 2 (shifted along baseline by spacing)
        double k2x = k1x + effSpacing * ux;
        double k2y = k1y + effSpacing * uy;

        // Base point 2 (return to baseline)
        double b2x = b1x + effSpacing * ux;
        double b2y = b1y + effSpacing * uy;

        b1 = new IntPoint((int) Math.round(b1x), (int) Math.round(b1y));
        k1 = new IntPoint((int) Math.round(k1x), (int) Math.round(k1y));
        k2 = new IntPoint((int) Math.round(k2x), (int) Math.round(k2y));
        b2 = new IntPoint((int) Math.round(b2x), (int) Math.round(b2y));
      }

      rawCorners.add(b1);
      rawCorners.add(k1);
      rawCorners.add(k2);
      rawCorners.add(b2);

      currentOffset += 2.0 * effSpacing;
    }

    rawCorners.add(endPoint);

    // Apply corner styling (fillet or chamfer)
    double radius =
        Math.min(effSpacing * 0.4, amplitude * 0.4) * (constraint.radiusPercentage() / 100.0);

    Polyline rawPolyline = new Polyline(rawCorners.toArray(new Point[0]));
    Polyline styled =
        PolylineFillet.filletPolyline(rawPolyline, cornerStyle, radius, fortyFiveDegreeOnly);

    // Extract corners and deduplicate adjacent identical points
    List<Point> result = new ArrayList<>();
    for (int i = 0; i < styled.cornerCount(); i++) {
      Point pt = styled.corner(i);
      if (result.isEmpty() || !result.get(result.size() - 1).equals(pt)) {
        result.add(pt);
      }
    }

    return result.size() >= 2 ? result.toArray(new Point[0]) : null;
  }

  /**
   * Generates a single-sided skew tuning bump for differential pair intra-pair length equalization.
   *
   * @param startPoint starting point of straight baseline segment
   * @param endPoint ending point of straight baseline segment
   * @param skewDeficit extra length needed on this trace to match its partner
   * @param constraint meander constraints (uses DIFF_PAIR_SKEW parameters)
   * @param outwardNormal normal vector pointing away from partner trace
   * @param maxClearAmplitude maximum clearance available
   * @param fortyFiveDegreeOnly if true, restricts bump to 45-degree chamfers
   * @return array of points representing the trace with the skew bump, or null if bump cannot fit
   */
  public static Point[] generateSkewBump(
      Point startPoint,
      Point endPoint,
      double skewDeficit,
      NetMeanderConstraint constraint,
      Direction outwardNormal,
      double maxClearAmplitude,
      boolean fortyFiveDegreeOnly) {
    if (startPoint == null || endPoint == null || skewDeficit <= 0.0 || constraint == null) {
      return null;
    }

    // Force single-sided for intra-pair skew bumps
    NetMeanderConstraint skewConstraint =
        new NetMeanderConstraint(
            constraint.minAmplitude(),
            constraint.maxAmplitude(),
            constraint.spacing(),
            CornerStyle.CHAMFER, // KiCad standard for skew bumps
            constraint.radiusPercentage(),
            true);

    return generateMeander(
        startPoint,
        endPoint,
        skewDeficit,
        skewConstraint,
        outwardNormal,
        maxClearAmplitude,
        fortyFiveDegreeOnly);
  }
}
