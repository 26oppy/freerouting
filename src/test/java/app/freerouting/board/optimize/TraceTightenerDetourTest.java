package app.freerouting.board.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.Freerouting;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.structure.AngleRestriction;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.board.model.structure.Layer;
import app.freerouting.board.model.structure.LayerStructure;
import app.freerouting.board.state.Communication;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.geometry.planar.PolylineShape;
import app.freerouting.rules.BoardRules;
import app.freerouting.rules.ClearanceMatrix;
import app.freerouting.settings.GlobalSettings;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TraceTightenerDetourTest {
  private RoutingBoard board;

  @BeforeEach
  void setUp() {
    Freerouting.globalSettings = new GlobalSettings();
    LayerStructure layers = new LayerStructure(new Layer[] {new Layer("Top", true)});
    BoardRules rules = new BoardRules(layers, ClearanceMatrix.getDefaultInstance(layers, 10));
    rules.createDefaultNetClass();
    board =
        new RoutingBoard(
            new IntBox(0, 0, 500000, 500000),
            layers,
            new PolylineShape[0],
            0,
            rules,
            new Communication());
    board.rules.nets.add("Net1", 1, false);
    board.rules.nets.add("Net2", 2, false);
    board.rules.setTraceAngleRestriction(AngleRestriction.FORTYFIVE_DEGREE);
  }

  private PolylineTrace insertTrace(List<Point> corners) {
    Polyline polyline = new Polyline(corners.toArray(Point[]::new));
    PolylineTrace trace =
        board.insertTraceWithoutCleaning(polyline, 0, 100, new int[] {1}, 1, FixedState.UNFIXED);
    board.additionalUpdateAfterChange(trace);
    return trace;
  }

  private static List<Point> corners(PolylineTrace trace) {
    List<Point> result = new ArrayList<>();
    for (int i = 0; i < trace.polyline().cornerCount(); i++) {
      result.add(trace.polyline().corner(i));
    }
    return result;
  }

  @Test
  void shallowDetourCollapsesWhenStraightPathIsClear() {
    // A 3-segment detour along a straight horizontal baseline y=10000
    // (10000, 10000) -> (20000, 10000) -> (23700, 13700) -> (28700, 13700) -> (32400, 10000) ->
    // (40000, 10000)
    PolylineTrace trace =
        insertTrace(
            List.of(
                new IntPoint(10000, 10000),
                new IntPoint(20000, 10000),
                new IntPoint(23700, 13700),
                new IntPoint(28700, 13700),
                new IntPoint(32400, 10000),
                new IntPoint(40000, 10000)));

    assertEquals(6, trace.polyline().cornerCount());
    boolean changed = trace.pullTight(true, 100, null);
    List<Point> after = corners(trace);

    // If successfully collapsed, it should become a single straight line from (10000, 10000) to
    // (40000, 10000)
    System.out.println("Changed: " + changed + ", corners after: " + after);
    assertEquals(0, new DesignRulesChecker(board, null).getAllClearanceViolations().size());
    assertEquals(
        2, trace.polyline().cornerCount(), "Shallow detour should collapse into a straight line");
    assertEquals(new IntPoint(10000, 10000), after.get(0));
    assertEquals(new IntPoint(40000, 10000), after.get(1));
  }

  @Test
  void triangleDetourCollapsesWhenStraightPathIsClear() {
    // A 2-segment triangular detour along a straight horizontal baseline y=10000
    // (10000, 10000) -> (20000, 10000) -> (23700, 13700) -> (27400, 10000) -> (40000, 10000)
    PolylineTrace trace =
        insertTrace(
            List.of(
                new IntPoint(10000, 10000),
                new IntPoint(20000, 10000),
                new IntPoint(23700, 13700),
                new IntPoint(27400, 10000),
                new IntPoint(40000, 10000)));

    assertEquals(5, trace.polyline().cornerCount());
    boolean changed = trace.pullTight(true, 100, null);
    List<Point> after = corners(trace);

    System.out.println("Triangle detour changed: " + changed + ", corners after: " + after);
    assertEquals(0, new DesignRulesChecker(board, null).getAllClearanceViolations().size());
    assertEquals(
        2, trace.polyline().cornerCount(), "Triangle detour should collapse into a straight line");
    assertEquals(new IntPoint(10000, 10000), after.get(0));
    assertEquals(new IntPoint(40000, 10000), after.get(1));
  }

  @Test
  void dogLegDetourCollapsesWhenStraightPathIsClear() {
    // A vertical route with a 0.37 mm (3700 dsn units) dog-leg detour
    PolylineTrace trace =
        insertTrace(
            List.of(
                new IntPoint(256200, 100000),
                new IntPoint(256200, 105000),
                new IntPoint(259900, 108700),
                new IntPoint(259900, 115000),
                new IntPoint(256200, 118700),
                new IntPoint(256200, 125000)));

    assertEquals(6, trace.polyline().cornerCount());
    boolean changed = trace.pullTight(true, 100, null);
    List<Point> after = corners(trace);

    System.out.println("Dog-leg detour changed: " + changed + ", corners after: " + after);
    assertEquals(0, new DesignRulesChecker(board, null).getAllClearanceViolations().size());
    assertEquals(
        2, trace.polyline().cornerCount(), "Dog-leg detour should collapse into a straight line");
    assertEquals(new IntPoint(256200, 100000), after.get(0));
    assertEquals(new IntPoint(256200, 125000), after.get(1));
  }

  @Test
  void r6809OscillatingJiggleCollapses() {
    // Exact oscillating sequence from r6809.dsn VD5:
    // (276041, 102930) -> (276041, 103055) -> (276163, 103177) -> (276163, 103510)
    // -> (276001, 103672) -> (276001, 103838) -> (276148, 103986) -> (276148, 105441)
    PolylineTrace trace =
        insertTrace(
            List.of(
                new IntPoint(276041, 102930),
                new IntPoint(276041, 103055),
                new IntPoint(276163, 103177),
                new IntPoint(276163, 103510),
                new IntPoint(276001, 103672),
                new IntPoint(276001, 103838),
                new IntPoint(276148, 103985),
                new IntPoint(276148, 105441)));

    assertEquals(8, trace.polyline().cornerCount());
    boolean changed = trace.pullTight(true, 100, null);
    List<Point> after = corners(trace);

    System.out.println(
        "r6809 oscillating jiggle changed: " + changed + ", corners after: " + after);
    assertEquals(0, new DesignRulesChecker(board, null).getAllClearanceViolations().size());
    // Should collapse the tiny 16-micron oscillations into at most 2 or 3 corners
    assertTrue(
        trace.polyline().cornerCount() <= 3,
        "Oscillating jiggle should collapse, but had: "
            + trace.polyline().cornerCount()
            + " corners: "
            + after);
  }
}
