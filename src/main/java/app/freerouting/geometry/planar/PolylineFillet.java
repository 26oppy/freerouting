package app.freerouting.geometry.planar;

import app.freerouting.rules.CornerStyle;
import java.util.ArrayList;
import java.util.List;

/**
 * Geometric utility for rounding and chamfering corners between polyline segments.
 *
 * <p>Supports both 45-degree constrained beveling (for standard PCB routing) and multi-segment
 * chord approximations for smooth circular fillets in any-angle routing.
 */
public final class PolylineFillet {

  private PolylineFillet() {
    // Utility class
  }

  /**
   * Applies corner filleting or chamfering to a single corner between two line segments.
   *
   * @param prev the previous corner point
   * @param corner the corner vertex to fillet
   * @param next the next corner point
   * @param style the corner style (CHAMFER or FILLET)
   * @param radius the desired fillet radius or chamfer setback distance
   * @param fortyFiveDegreeOnly if true, restricts resulting segments to 45-degree increments
   * @return array of points replacing the single corner vertex (never null, at least 1 point)
   */
  public static Point[] filletCorner(
      Point prev,
      Point corner,
      Point next,
      CornerStyle style,
      double radius,
      boolean fortyFiveDegreeOnly) {
    if (prev == null || corner == null || next == null || radius <= 0.0) {
      return new Point[] {corner};
    }
    if (prev.equals(corner) || next.equals(corner)) {
      return new Point[] {corner};
    }

    FloatPoint p0 = prev.toFloat();
    FloatPoint c = corner.toFloat();
    FloatPoint p1 = next.toFloat();

    double vx0 = c.x - p0.x;
    double vy0 = c.y - p0.y;
    double vx1 = p1.x - c.x;
    double vy1 = p1.y - c.y;

    double len0 = Math.hypot(vx0, vy0);
    double len1 = Math.hypot(vx1, vy1);

    if (len0 < 1.0 || len1 < 1.0) {
      return new Point[] {corner};
    }

    double u0x = vx0 / len0;
    double u0y = vy0 / len0;
    double u1x = vx1 / len1;
    double u1y = vy1 / len1;

    double dot = u0x * u1x + u0y * u1y;
    // Collinear segments (straight line) or turnaround (180 degree hairpin)
    if (dot > 0.9999 || dot < -0.9999) {
      return new Point[] {corner};
    }

    double cross = u0x * u1y - u0y * u1x;
    double turnAngle = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));

    // In 45-degree routing, only 90-degree corners can be beveled without violating 45-degree rules
    if (fortyFiveDegreeOnly) {
      // 90-degree turn has dot product ~ 0
      if (Math.abs(dot) > 0.05) {
        return new Point[] {corner};
      }
    }

    // Tangent distance from vertex: d = R * tan(turnAngle / 2)
    double dTangent = radius * Math.tan(turnAngle / 2.0);
    double maxDistance = 0.45 * Math.min(len0, len1);
    double d = Math.min(dTangent, maxDistance);

    if (d < 1.0) {
      return new Point[] {corner};
    }

    CornerStyle effectiveStyle = (style != null) ? style : CornerStyle.FILLET;

    if (effectiveStyle == CornerStyle.CHAMFER || fortyFiveDegreeOnly) {
      // 45-degree bevel: cut back along incoming and outgoing segments
      int dInt = (int) Math.round(d);
      if (dInt < 1) {
        return new Point[] {corner};
      }

      int ax = (int) Math.round(c.x - dInt * u0x);
      int ay = (int) Math.round(c.y - dInt * u0y);
      int bx = (int) Math.round(c.x + dInt * u1x);
      int by = (int) Math.round(c.y + dInt * u1y);

      IntPoint ptA = new IntPoint(ax, ay);
      IntPoint ptB = new IntPoint(bx, by);

      if (ptA.equals(ptB)) {
        return new Point[] {corner};
      }
      return new Point[] {ptA, ptB};
    }

    // Any-angle smooth fillet: approximate circle arc with 3 chord segments (4 points)
    double normalSign = (cross > 0) ? 1.0 : -1.0;
    double n0x = -u0y * normalSign;
    double n0y = u0x * normalSign;

    double rActual = d / Math.tan(turnAngle / 2.0);
    double cx = (c.x - d * u0x) + rActual * n0x;
    double cy = (c.y - d * u0y) + rActual * n0y;

    double startAngle = Math.atan2((c.y - d * u0y) - cy, (c.x - d * u0x) - cx);
    double endAngle = Math.atan2((c.y + d * u1y) - cy, (c.x + d * u1x) - cx);

    // Normalize angle sweep in direction of cross product
    double sweep = endAngle - startAngle;
    if (cross > 0 && sweep < 0) {
      sweep += 2 * Math.PI;
    } else if (cross < 0 && sweep > 0) {
      sweep -= 2 * Math.PI;
    }

    int chordCount = 3;
    List<Point> points = new ArrayList<>(chordCount + 1);
    for (int i = 0; i <= chordCount; i++) {
      double t = (double) i / chordCount;
      double angle = startAngle + t * sweep;
      int px = (int) Math.round(cx + rActual * Math.cos(angle));
      int py = (int) Math.round(cy + rActual * Math.sin(angle));
      IntPoint pt = new IntPoint(px, py);
      if (points.isEmpty() || !points.get(points.size() - 1).equals(pt)) {
        points.add(pt);
      }
    }

    return points.isEmpty() ? new Point[] {corner} : points.toArray(new Point[0]);
  }

  /**
   * Applies corner filleting or chamfering to all inner corners of a polyline.
   *
   * @param polyline the input polyline to fillet
   * @param style the corner style (CHAMFER or FILLET)
   * @param radius the desired fillet radius or chamfer setback distance
   * @param fortyFiveDegreeOnly if true, restricts resulting segments to 45-degree increments
   * @return new Polyline with filleted corners, or the original if no changes were applied
   */
  public static Polyline filletPolyline(
      Polyline polyline, CornerStyle style, double radius, boolean fortyFiveDegreeOnly) {
    if (polyline == null || polyline.cornerCount() < 3 || radius <= 0.0) {
      return polyline;
    }

    int cornerCount = polyline.cornerCount();
    List<Point> resultCorners = new ArrayList<>();
    resultCorners.add(polyline.corner(0));

    boolean modified = false;
    for (int i = 1; i < cornerCount - 1; i++) {
      Point prev = polyline.corner(i - 1);
      Point curr = polyline.corner(i);
      Point next = polyline.corner(i + 1);

      Point[] replacement = filletCorner(prev, curr, next, style, radius, fortyFiveDegreeOnly);
      if (replacement.length > 1 || !replacement[0].equals(curr)) {
        modified = true;
      }
      for (Point pt : replacement) {
        if (resultCorners.isEmpty() || !resultCorners.get(resultCorners.size() - 1).equals(pt)) {
          resultCorners.add(pt);
        }
      }
    }

    Point lastCorner = polyline.corner(cornerCount - 1);
    if (resultCorners.isEmpty()
        || !resultCorners.get(resultCorners.size() - 1).equals(lastCorner)) {
      resultCorners.add(lastCorner);
    }

    if (!modified || resultCorners.size() < 2) {
      return polyline;
    }

    return new Polyline(resultCorners.toArray(new Point[0]));
  }
}
