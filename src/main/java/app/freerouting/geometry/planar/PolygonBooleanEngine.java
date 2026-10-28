package app.freerouting.geometry.planar;

import java.util.List;

/**
 * Pluggable engine for constructive solid geometry (CSG) and polygon boolean operations.
 *
 * <p>Isolates constructive solid geometry calculations required by copper pour zones ({@code
 * ConductionArea}) and DRC island analysis ({@code DesignRulesChecker}) behind a clean port.
 * Standard OpenJDK, GraalVM native image, and CheerpJ environments use {@link
 * AwtPolygonBooleanEngine} (based on {@code java.awt.geom.Area}). Lightweight or AOT environments
 * without {@code java.desktop} (such as TeaVM) can register an alternative implementation without
 * modifying Freerouting's core algorithms.
 */
public interface PolygonBooleanEngine {

  /** Represents an opaque polygonal shape supporting geometric queries. */
  interface PolygonShape {

    /** Returns {@code true} if this shape contains no interior points. */
    boolean isEmpty();

    /** Returns {@code true} if the specified point (x, y) lies inside or on the boundary. */
    boolean contains(double x, double y);

    /** Returns the width of the bounding box of this shape in board units. */
    double getBoundsWidth();

    /** Returns the height of the bounding box of this shape in board units. */
    double getBoundsHeight();

    /**
     * Unwraps the underlying platform shape object (e.g. {@link java.awt.geom.Area}).
     *
     * @return the platform-native representation, or {@code null} if unsupported
     */
    Object unwrap();
  }

  /** Creates an empty shape. */
  PolygonShape createEmpty();

  /** Creates a shape from an elliptical or circular disc. */
  PolygonShape createCircle(double centerX, double centerY, double radius);

  /** Creates a shape from an axis-aligned box. */
  PolygonShape createBox(double x, double y, double width, double height);

  /** Creates a shape from a polyline / polygon outline with coordinates in order. */
  PolygonShape createPolygon(FloatPoint[] corners);

  /**
   * Creates 45-degree rotated cross spokes for thermal relief connections.
   *
   * @param centerX center X in board units
   * @param centerY center Y in board units
   * @param spokeWidth width of the thermal spokes in board units
   * @param expansionRadius total outer reach/radius of the thermal spokes
   * @return combined cross-spoke shape
   */
  PolygonShape createThermalSpokes(
      double centerX, double centerY, double spokeWidth, double expansionRadius);

  /** Performs a union of two shapes (A ∪ B). */
  PolygonShape union(PolygonShape a, PolygonShape b);

  /** Performs a balanced union of multiple shapes. */
  PolygonShape unionAll(List<PolygonShape> shapes);

  /** Performs a difference of two shapes (A \ B). */
  PolygonShape subtract(PolygonShape a, PolygonShape b);

  /** Subtracts all cutouts from the base shape (A \ (B₁ ∪ B₂ ∪ ...)). */
  PolygonShape subtractAll(PolygonShape base, List<PolygonShape> cutouts);

  /** Performs an intersection of two shapes (A ∩ B). */
  PolygonShape intersect(PolygonShape a, PolygonShape b);

  /**
   * Decomposes a potentially disjoint shape into separate contiguous island shapes.
   *
   * @param shape the composite shape to decompose
   * @return list of contiguous, disjoint island shapes
   */
  List<PolygonShape> decomposeIntoIslands(PolygonShape shape);
}
