package app.freerouting.geometry.planar;

import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Default OpenJDK / GraalVM / CheerpJ implementation of {@link PolygonBooleanEngine} backed by
 * {@link java.awt.geom.Area}.
 */
public class AwtPolygonBooleanEngine implements PolygonBooleanEngine {

  /** Wrapper record/class around {@link Area}. */
  public static class AwtPolygonShape implements PolygonShape {
    private final Area area;

    public AwtPolygonShape(Area area) {
      this.area = area != null ? area : new Area();
    }

    @Override
    public boolean isEmpty() {
      return area.isEmpty();
    }

    @Override
    public boolean contains(double x, double y) {
      return area.contains(x, y);
    }

    @Override
    public double getBoundsWidth() {
      return area.getBounds2D().getWidth();
    }

    @Override
    public double getBoundsHeight() {
      return area.getBounds2D().getHeight();
    }

    @Override
    public Area unwrap() {
      return area;
    }

    /** Returns the underlying {@link Area}. */
    public Area getAwtArea() {
      return area;
    }
  }

  private static Area toArea(PolygonShape shape) {
    if (shape == null) {
      return new Area();
    }
    if (shape instanceof AwtPolygonShape awt) {
      return awt.getAwtArea();
    }
    Object unwrapped = shape.unwrap();
    if (unwrapped instanceof Area a) {
      return a;
    }
    return new Area();
  }

  private static List<Area> toAreaList(List<PolygonShape> shapes) {
    if (shapes == null || shapes.isEmpty()) {
      return Collections.emptyList();
    }
    List<Area> areas = new ArrayList<>(shapes.size());
    for (PolygonShape shape : shapes) {
      if (shape != null && !shape.isEmpty()) {
        areas.add(new Area(toArea(shape)));
      }
    }
    return areas;
  }

  @Override
  public PolygonShape createEmpty() {
    return new AwtPolygonShape(new Area());
  }

  @Override
  public PolygonShape createCircle(double centerX, double centerY, double radius) {
    double diameter = 2 * radius;
    return new AwtPolygonShape(
        new Area(new Ellipse2D.Double(centerX - radius, centerY - radius, diameter, diameter)));
  }

  @Override
  public PolygonShape createBox(double x, double y, double width, double height) {
    return new AwtPolygonShape(new Area(new Rectangle2D.Double(x, y, width, height)));
  }

  @Override
  public PolygonShape createPolygon(FloatPoint[] corners) {
    if (corners == null || corners.length == 0) {
      return createEmpty();
    }
    Path2D.Double path = new Path2D.Double();
    path.moveTo(corners[0].x, corners[0].y);
    for (int i = 1; i < corners.length; i++) {
      path.lineTo(corners[i].x, corners[i].y);
    }
    path.closePath();
    return new AwtPolygonShape(new Area(path));
  }

  @Override
  public PolygonShape createThermalSpokes(
      double centerX, double centerY, double spokeWidth, double expansionRadius) {
    double halfSpoke = spokeWidth / 2.0;
    Rectangle2D.Double baseSpoke =
        new Rectangle2D.Double(
            centerX - halfSpoke, centerY - expansionRadius, spokeWidth, 2 * expansionRadius);

    AffineTransform rotP45 = AffineTransform.getRotateInstance(Math.PI / 4.0, centerX, centerY);
    AffineTransform rotM45 = AffineTransform.getRotateInstance(-Math.PI / 4.0, centerX, centerY);

    Area spokes = new Area(rotP45.createTransformedShape(baseSpoke));
    spokes.add(new Area(rotM45.createTransformedShape(baseSpoke)));
    return new AwtPolygonShape(spokes);
  }

  @Override
  public PolygonShape union(PolygonShape a, PolygonShape b) {
    Area result = new Area(toArea(a));
    result.add(toArea(b));
    return new AwtPolygonShape(result);
  }

  @Override
  public PolygonShape unionAll(List<PolygonShape> shapes) {
    List<Area> areas = toAreaList(shapes);
    if (areas.isEmpty()) {
      return createEmpty();
    }
    return new AwtPolygonShape(AwtAreas.unionAll(areas));
  }

  @Override
  public PolygonShape subtract(PolygonShape a, PolygonShape b) {
    Area result = new Area(toArea(a));
    result.subtract(toArea(b));
    return new AwtPolygonShape(result);
  }

  @Override
  public PolygonShape subtractAll(PolygonShape base, List<PolygonShape> cutouts) {
    Area result = new Area(toArea(base));
    List<Area> cutoutAreas = toAreaList(cutouts);
    AwtAreas.subtractAll(result, cutoutAreas);
    return new AwtPolygonShape(result);
  }

  @Override
  public PolygonShape intersect(PolygonShape a, PolygonShape b) {
    Area result = new Area(toArea(a));
    result.intersect(toArea(b));
    return new AwtPolygonShape(result);
  }

  @Override
  public List<PolygonShape> decomposeIntoIslands(PolygonShape shape) {
    Area area = toArea(shape);
    List<PolygonShape> islands = new ArrayList<>();
    if (area == null || area.isEmpty()) {
      return islands;
    }

    PathIterator it = area.getPathIterator(null);
    Path2D.Double currentPolygon = null;
    double[] coords = new double[6];

    while (!it.isDone()) {
      int segType = it.currentSegment(coords);
      switch (segType) {
        case PathIterator.SEG_MOVETO -> {
          if (currentPolygon != null) {
            Area piece = new Area(currentPolygon);
            if (!piece.isEmpty()) {
              mergeOrAddIsland(islands, piece);
            }
          }
          currentPolygon = new Path2D.Double();
          currentPolygon.moveTo(coords[0], coords[1]);
        }
        case PathIterator.SEG_LINETO -> {
          if (currentPolygon != null) {
            currentPolygon.lineTo(coords[0], coords[1]);
          }
        }
        case PathIterator.SEG_QUADTO -> {
          if (currentPolygon != null) {
            currentPolygon.quadTo(coords[0], coords[1], coords[2], coords[3]);
          }
        }
        case PathIterator.SEG_CUBICTO -> {
          if (currentPolygon != null) {
            currentPolygon.curveTo(
                coords[0], coords[1], coords[2], coords[3], coords[4], coords[5]);
          }
        }
        case PathIterator.SEG_CLOSE -> {
          if (currentPolygon != null) {
            currentPolygon.closePath();
          }
        }
        default -> {}
      }
      it.next();
    }

    if (currentPolygon != null) {
      Area piece = new Area(currentPolygon);
      if (!piece.isEmpty()) {
        mergeOrAddIsland(islands, piece);
      }
    }

    return islands;
  }

  private void mergeOrAddIsland(List<PolygonShape> islands, Area piece) {
    boolean incorporated = false;
    for (int i = 0; i < islands.size(); i++) {
      Area existing = toArea(islands.get(i));
      Area intersection = new Area(existing);
      intersection.intersect(piece);
      if (!intersection.isEmpty()) {
        Area testSubtract = new Area(piece);
        testSubtract.subtract(existing);
        if (testSubtract.isEmpty()) {
          // 'piece' is completely inside 'existing' -> hole contour, subtract it
          existing.subtract(piece);
        } else {
          existing.exclusiveOr(piece);
        }
        incorporated = true;
        break;
      }
    }
    if (!incorporated) {
      islands.add(new AwtPolygonShape(piece));
    }
  }
}
