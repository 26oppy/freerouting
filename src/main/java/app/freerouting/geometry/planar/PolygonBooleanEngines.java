package app.freerouting.geometry.planar;

import java.util.ArrayList;
import java.util.List;

/** Global registry and conversion utilities for {@link PolygonBooleanEngine}. */
public final class PolygonBooleanEngines {

  private static volatile PolygonBooleanEngine currentEngine = new AwtPolygonBooleanEngine();

  private PolygonBooleanEngines() {}

  /** Returns the active polygon boolean engine. */
  public static PolygonBooleanEngine get() {
    return currentEngine;
  }

  /**
   * Replaces the active polygon boolean engine (e.g. for TeaVM or headless WebAssembly runtimes).
   *
   * @param engine the replacement engine, or {@code null} to reset to the default AWT engine
   */
  public static void set(PolygonBooleanEngine engine) {
    currentEngine = engine != null ? engine : new AwtPolygonBooleanEngine();
  }

  /** Resets the active engine to the default {@link AwtPolygonBooleanEngine}. */
  public static void reset() {
    currentEngine = new AwtPolygonBooleanEngine();
  }

  /**
   * Converts a Freerouting planar {@link Shape} into an opaque {@link
   * PolygonBooleanEngine.PolygonShape}.
   */
  public static PolygonBooleanEngine.PolygonShape toPolygonShape(
      PolygonBooleanEngine engine, Shape shape) {
    if (shape == null) {
      return engine.createEmpty();
    }
    if (shape instanceof Circle circle) {
      return engine.createCircle(circle.center.x, circle.center.y, circle.radius);
    }
    if (shape instanceof IntBox box) {
      return engine.createBox(box.ll.x, box.ll.y, box.width(), box.height());
    }
    if (shape instanceof PolylineShape poly && poly.isBounded()) {
      int count = poly.borderLineCount();
      FloatPoint[] corners = new FloatPoint[count];
      for (int i = 0; i < count; i++) {
        corners[i] = poly.cornerApprox(i);
      }
      return engine.createPolygon(corners);
    }
    return engine.createEmpty();
  }

  /**
   * Converts a Freerouting planar {@link Shape} into a {@link PolygonBooleanEngine.PolygonShape}
   * using the active engine.
   */
  public static PolygonBooleanEngine.PolygonShape toPolygonShape(Shape shape) {
    return toPolygonShape(get(), shape);
  }

  /**
   * Converts a Freerouting planar {@link Area} (with outer border and holes) into a {@link
   * PolygonBooleanEngine.PolygonShape}.
   */
  public static PolygonBooleanEngine.PolygonShape toPolygonShape(
      PolygonBooleanEngine engine, Area area) {
    if (area == null || area.isEmpty()) {
      return engine.createEmpty();
    }
    if (area instanceof Circle circle) {
      return engine.createCircle(circle.center.x, circle.center.y, circle.radius);
    }
    Shape border = area.getBorder();
    PolygonBooleanEngine.PolygonShape borderShape = toPolygonShape(engine, border);
    if (borderShape.isEmpty()) {
      return engine.createEmpty();
    }
    Shape[] holes = area.getHoles();
    if (holes != null && holes.length > 0) {
      List<PolygonBooleanEngine.PolygonShape> holeShapes = new ArrayList<>(holes.length);
      for (Shape hole : holes) {
        PolygonBooleanEngine.PolygonShape hs = toPolygonShape(engine, hole);
        if (!hs.isEmpty()) {
          holeShapes.add(hs);
        }
      }
      return engine.subtractAll(borderShape, holeShapes);
    }
    return borderShape;
  }

  /**
   * Converts a Freerouting planar {@link Area} into a {@link PolygonBooleanEngine.PolygonShape}
   * using the active engine.
   */
  public static PolygonBooleanEngine.PolygonShape toPolygonShape(Area area) {
    return toPolygonShape(get(), area);
  }
}
