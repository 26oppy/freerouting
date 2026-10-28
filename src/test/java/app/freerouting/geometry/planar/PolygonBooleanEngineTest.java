package app.freerouting.geometry.planar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Comprehensive test suite for {@link PolygonBooleanEngine} and {@link PolygonBooleanEngines}. */
class PolygonBooleanEngineTest {

  private PolygonBooleanEngine engine;

  @BeforeEach
  void setUp() {
    PolygonBooleanEngines.reset();
    engine = PolygonBooleanEngines.get();
  }

  @AfterEach
  void tearDown() {
    PolygonBooleanEngines.reset();
  }

  @Test
  void testCircleCreationAndContainment() {
    PolygonBooleanEngine.PolygonShape circle = engine.createCircle(100, 100, 50);

    assertFalse(circle.isEmpty(), "Circle should not be empty");
    assertTrue(circle.contains(100, 100), "Center should be contained");
    assertTrue(circle.contains(100, 140), "Interior point should be contained");
    assertFalse(circle.contains(200, 200), "Exterior point should not be contained");
    assertEquals(100.0, circle.getBoundsWidth(), 0.01, "Width should be diameter");
    assertEquals(100.0, circle.getBoundsHeight(), 0.01, "Height should be diameter");
  }

  @Test
  void testBoxCreationAndContainment() {
    PolygonBooleanEngine.PolygonShape box = engine.createBox(10, 20, 30, 40);

    assertFalse(box.isEmpty(), "Box should not be empty");
    assertTrue(box.contains(15, 25), "Interior point should be contained");
    assertFalse(box.contains(5, 5), "Exterior point should not be contained");
    assertEquals(30.0, box.getBoundsWidth(), 0.01);
    assertEquals(40.0, box.getBoundsHeight(), 0.01);
  }

  @Test
  void testPolygonCreationAndContainment() {
    FloatPoint[] corners =
        new FloatPoint[] {
          new FloatPoint(0, 0),
          new FloatPoint(100, 0),
          new FloatPoint(100, 100),
          new FloatPoint(0, 100)
        };
    PolygonBooleanEngine.PolygonShape poly = engine.createPolygon(corners);

    assertFalse(poly.isEmpty(), "Polygon should not be empty");
    assertTrue(poly.contains(50, 50), "Interior point should be contained");
    assertFalse(poly.contains(150, 50), "Exterior point should not be contained");
  }

  @Test
  void testBooleanOperations() {
    PolygonBooleanEngine.PolygonShape boxA = engine.createBox(0, 0, 100, 100);
    PolygonBooleanEngine.PolygonShape boxB = engine.createBox(50, 0, 100, 100);

    // Union
    PolygonBooleanEngine.PolygonShape union = engine.union(boxA, boxB);
    assertTrue(union.contains(25, 50));
    assertTrue(union.contains(125, 50));
    assertEquals(150.0, union.getBoundsWidth(), 0.01);

    // Subtract
    PolygonBooleanEngine.PolygonShape diff = engine.subtract(boxA, boxB);
    assertTrue(diff.contains(25, 50));
    assertFalse(diff.contains(75, 50));

    // Intersect
    PolygonBooleanEngine.PolygonShape inter = engine.intersect(boxA, boxB);
    assertTrue(inter.contains(75, 50));
    assertFalse(inter.contains(25, 50));
    assertEquals(50.0, inter.getBoundsWidth(), 0.01);
  }

  @Test
  void testThermalSpokesCreation() {
    PolygonBooleanEngine.PolygonShape spokes = engine.createThermalSpokes(0, 0, 10, 50);

    assertFalse(spokes.isEmpty(), "Thermal spokes should not be empty");
    assertTrue(spokes.contains(0, 0), "Center should be within spoke intersection");
  }

  @Test
  void testIslandDecomposition() {
    PolygonBooleanEngine.PolygonShape island1 = engine.createBox(0, 0, 50, 50);
    PolygonBooleanEngine.PolygonShape island2 = engine.createBox(100, 100, 50, 50);

    PolygonBooleanEngine.PolygonShape composite = engine.union(island1, island2);
    List<PolygonBooleanEngine.PolygonShape> islands = engine.decomposeIntoIslands(composite);

    assertEquals(2, islands.size(), "Should decompose into 2 disjoint islands");
  }

  @Test
  void testConversionsFromFreeroutingShapes() {
    IntBox box = new IntBox(0, 0, 100, 100);
    PolygonBooleanEngine.PolygonShape shapeFromBox = PolygonBooleanEngines.toPolygonShape(box);
    assertFalse(shapeFromBox.isEmpty());
    assertTrue(shapeFromBox.contains(50, 50));

    Circle circle = new Circle(new IntPoint(50, 50), 25);
    PolygonBooleanEngine.PolygonShape shapeFromCircle =
        PolygonBooleanEngines.toPolygonShape(circle);
    assertFalse(shapeFromCircle.isEmpty());
    assertTrue(shapeFromCircle.contains(50, 50));
  }

  @Test
  void testPluggableEngineReplacement() {
    PolygonBooleanEngine defaultEngine = PolygonBooleanEngines.get();
    assertNotNull(defaultEngine);
    assertTrue(defaultEngine instanceof AwtPolygonBooleanEngine);

    AwtPolygonBooleanEngine customEngine = new AwtPolygonBooleanEngine();
    PolygonBooleanEngines.set(customEngine);
    assertSame(customEngine, PolygonBooleanEngines.get());

    PolygonBooleanEngines.reset();
    assertTrue(PolygonBooleanEngines.get() instanceof AwtPolygonBooleanEngine);
  }
}
