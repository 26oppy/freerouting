package app.freerouting.board.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.Freerouting;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.ConductionArea;
import app.freerouting.board.model.items.DrillItem;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Trace;
import app.freerouting.board.model.items.Via;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.board.model.structure.Layer;
import app.freerouting.board.model.structure.LayerStructure;
import app.freerouting.board.searchtree.SearchTreeObject;
import app.freerouting.board.state.Communication;
import app.freerouting.core.library.Padstack;
import app.freerouting.geometry.planar.ConvexShape;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.IntVector;
import app.freerouting.geometry.planar.Line;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.geometry.planar.PolylineShape;
import app.freerouting.geometry.planar.TileShape;
import app.freerouting.rules.BoardRules;
import app.freerouting.rules.ClearanceMatrix;
import app.freerouting.settings.GlobalSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class TraceContactSearchTest {

  @ParameterizedTest
  @CsvSource({
    "0, false",
    "1, false",
    "10, false",
    "100, false",
    "0, true",
    "1, true",
    "10, true",
    "100, true"
  })
  void parallelDiagonalsDoNotProduceQuadraticContactCandidates(int halfWidth, boolean compensated) {
    CountingBoard board = createBoard(compensated);
    List<PolylineTrace> traces = new ArrayList<>();
    for (int i = 0; i < 128; i++) {
      int y = 10000 + 1000 * i;
      traces.add(
          insert(
              board,
              new Polyline(new IntPoint(10000, y), new IntPoint(910000, y + 900000)),
              0,
              1,
              halfWidth));
    }

    for (PolylineTrace trace : traces) {
      board.candidateCount = 0;
      assertTrue(trace.getNormalContacts().isEmpty());
      // Count spatial candidates rather than asserting a machine-dependent duration.
      assertEquals(
          3, board.candidateCount, "Only self should overlap each of the three contact queries");
    }
  }

  @ParameterizedTest
  @CsvSource({
    "false, false, false",
    "false, false, true",
    "false, true, false",
    "false, true, true",
    "true, false, false",
    "true, false, true",
    "true, true, false",
    "true, true, true"
  })
  void narrowerSearchRetainsBranchesBendsAndDrills(
      boolean anyAngle, boolean reverse, boolean compensated) {
    CountingBoard board = createBoard(compensated);
    int bendY = anyAngle ? 60000 : 40000;
    Point start = new IntPoint(10000, 10000);
    Point middle = new IntPoint(25000, (10000 + bendY) / 2);
    Point bend = new IntPoint(40000, bendY);
    Point end = new IntPoint(80000, bendY);
    Polyline path = new Polyline(new Point[] {start, bend, end});
    final PolylineTrace trunk = insert(board, reverse ? path.reverse() : path, 0, 1, 100);
    final PolylineTrace interiorBranch =
        insert(board, new Polyline(middle, new IntPoint(15000, bendY)), 0, 1, 10);
    final PolylineTrace bendBranch =
        insert(board, new Polyline(new IntPoint(40000, bendY + 10000), bend), 0, 1, 10);
    final PolylineTrace endBranch =
        insert(board, new Polyline(end, new IntPoint(90000, bendY)), 0, 1, 10);
    // These candidates have the wrong net/layer, or overlap copper without a centerline contact.
    insert(board, new Polyline(middle, new IntPoint(5000, bendY)), 0, 2, 10);
    insert(board, new Polyline(bend, new IntPoint(60000, bendY + 10000)), 1, 1, 10);
    insert(
        board,
        new Polyline(new IntPoint(50000, bendY + 50), new IntPoint(60000, bendY + 50)),
        0,
        1,
        100);

    board.library.padstacks = new app.freerouting.core.library.Padstacks(board.layerStructure);
    ConvexShape padShape = TileShape.getInstance(-50, -50, 50, 50);
    Padstack pad =
        board.library.padstacks.add(
            "ContactVia", new ConvexShape[] {padShape, padShape}, true, false);
    Via via =
        board.insertVia(
            pad, new IntPoint(60000, bendY), new int[] {1}, 1, FixedState.USER_FIXED, false);

    Set<Item> expected = Set.of(interiorBranch, bendBranch, endBranch, via);
    assertEquals(expected, trunk.getNormalContacts());
    for (Item contact : expected) {
      assertTrue(contact.getNormalContacts().contains(trunk), "Contacts must remain bidirectional");
    }
    assertEquals(3, trunk.cornerCount(), "The preserved trunk must remain unsplit");
  }

  @ParameterizedTest(name = "{0}, offset={1}, width={2}, reverse={3}, compensated={4}")
  @MethodSource("geometryCases")
  void contactSearchMatchesExhaustiveReference(
      String geometry, int offset, int halfWidth, boolean reverse, boolean compensated) {
    CountingBoard board = createBoard(compensated);
    int[][] coordinates =
        switch (geometry) {
          case "horizontal" -> new int[][] {{0, 0}, {60000, 0}};
          case "diagonal" -> new int[][] {{0, 0}, {60000, 60000}};
          case "arbitrary" -> new int[][] {{0, 0}, {30000, 50000}, {60000, 40000}};
          case "u" -> new int[][] {{0, 60000}, {0, 0}, {60000, 0}, {60000, 60000}};
          case "winding" ->
              new int[][] {
                {0, 0}, {60000, 0}, {60000, 60000}, {20000, 60000}, {20000, 20000}, {40000, 20000}
              };
          case "short" -> new int[][] {{0, 0}, {2, 2}};
          default -> throw new IllegalArgumentException(geometry);
        };
    Point[] corners = new Point[coordinates.length];
    for (int i = 0; i < coordinates.length; i++) {
      corners[i] = new IntPoint(offset + coordinates[i][0], offset + coordinates[i][1]);
    }
    Polyline path = new Polyline(corners);
    PolylineTrace trunk = insert(board, reverse ? path.reverse() : path, 0, 1, halfWidth);
    Padstack pad = createPadstack(board);
    for (int net : new int[] {1, 2}) {
      board.insertConductionArea(
          TileShape.getInstance(offset - 10, offset - 10, offset + 10, offset + 10),
          0,
          new int[] {net},
          1,
          false,
          FixedState.USER_FIXED);
    }
    for (int i = 0; i < coordinates.length; i++) {
      int x = offset + coordinates[i][0];
      int y = offset + coordinates[i][1];
      insert(board, new Polyline(corners[i], new IntPoint(x + 37, y + 83)), 0, 1, 10);
      if (i == 0) {
        continue;
      }
      IntPoint midpoint =
          new IntPoint(
              offset + (coordinates[i - 1][0] + coordinates[i][0]) / 2,
              offset + (coordinates[i - 1][1] + coordinates[i][1]) / 2);
      insert(
          board, new Polyline(midpoint, new IntPoint(midpoint.x + 101, midpoint.y - 73)), 0, 1, 10);
      board.insertVia(pad, midpoint, new int[] {1}, 1, FixedState.USER_FIXED, false);
      insert(
          board, new Polyline(midpoint, new IntPoint(midpoint.x - 101, midpoint.y + 73)), 0, 2, 10);
      insert(
          board, new Polyline(midpoint, new IntPoint(midpoint.x + 101, midpoint.y + 73)), 1, 1, 10);
      insert(
          board,
          new Polyline(
              new IntPoint(midpoint.x + 1, midpoint.y + 2),
              new IntPoint(midpoint.x + 11, midpoint.y + 2)),
          0,
          1,
          100);
    }
    // Same-net copper in the empty interior of a bent trace's convex envelope is not a contact.
    insert(
        board,
        new Polyline(
            new IntPoint(offset + 35000, offset + 35000),
            new IntPoint(offset + 36000, offset + 35000)),
        0,
        1,
        100);
    assertFalse(trunk.getNormalContacts().isEmpty(), "The oracle must exercise actual contacts");
    assertAllContactsMatchReference(board);
  }

  private static Stream<Arguments> geometryCases() {
    Stream.Builder<Arguments> cases = Stream.builder();
    for (String geometry :
        List.of("horizontal", "diagonal", "arbitrary", "u", "winding", "short")) {
      // Simplex bounds require all eight projections (including x+y) within Limits.CRIT_INT.
      for (int offset : new int[] {-16700000, 0, 16700000}) {
        for (int halfWidth : new int[] {0, 100}) {
          for (boolean reverse : new boolean[] {false, true}) {
            for (boolean compensated : new boolean[] {false, true}) {
              cases.add(Arguments.of(geometry, offset, halfWidth, reverse, compensated));
            }
          }
        }
      }
    }
    return cases.build();
  }

  @ParameterizedTest
  @CsvSource({
    "0, false, false", "0, true, false", "1, false, false", "1, true, false",
    "100, false, false", "100, true, false", "0, false, true", "0, true, true",
    "1, false, true", "1, true, true", "100, false, true", "100, true, true"
  })
  void rationalSharedEndpointsMatchExhaustiveReference(
      int halfWidth, boolean compensated, boolean anyAngle) {
    CountingBoard board = createBoard(compensated);
    Line firstCap =
        anyAngle
            ? new Line(new IntPoint(-3000, -2000), new IntPoint(-2998, -2003))
            : new Line(new IntPoint(-1000, 0), new IntPoint(0, -1000));
    Line rising = new Line(new IntPoint(0, 0), anyAngle ? new IntPoint(3, 2) : new IntPoint(1, 1));
    Line falling =
        new Line(new IntPoint(0, 1), anyAngle ? new IntPoint(7, -4) : new IntPoint(1, 0));
    Line lastCap =
        anyAngle
            ? new Line(new IntPoint(7000, -4999), new IntPoint(7005, -4992))
            : new Line(new IntPoint(1001, 0), new IntPoint(2001, 1000));
    PolylineTrace first =
        insert(board, new Polyline(new Line[] {firstCap, rising, falling}), 0, 1, halfWidth);
    PolylineTrace second =
        insert(board, new Polyline(new Line[] {rising, falling, lastCap}), 0, 1, halfWidth);
    assertEquals(Set.of(second), first.getNormalContacts());
    assertEquals(Set.of(first), second.getNormalContacts());
    assertAllContactsMatchReference(board);
  }

  @ParameterizedTest
  @CsvSource({"false", "true"})
  void contactSearchReflectsNetFixedStateAndGeometryChanges(boolean compensated) {
    CountingBoard board = createBoard(compensated);
    PolylineTrace trunk =
        insert(
            board, new Polyline(new IntPoint(10000, 10000), new IntPoint(50000, 50000)), 0, 1, 100);
    PolylineTrace branch =
        insert(
            board, new Polyline(new IntPoint(30000, 30000), new IntPoint(40000, 30000)), 0, 1, 100);
    assertEquals(Set.of(branch), trunk.getNormalContacts());
    branch.assignNetNo(2);
    assertTrue(trunk.getNormalContacts().isEmpty());
    assertAllContactsMatchReference(board);
    branch.assignNetNo(1);
    trunk.setFixedState(FixedState.UNFIXED);
    assertEquals(Set.of(branch), trunk.getNormalContacts());
    board.removeItem(trunk);
    trunk.translateBy(new IntVector(200000, 200000));
    board.insertItem(trunk);
    assertTrue(trunk.getNormalContacts().isEmpty());
    assertTrue(branch.getNormalContacts().isEmpty());
    assertAllContactsMatchReference(board);
    board.removeItem(trunk);
    trunk.translateBy(new IntVector(-200000, -200000));
    board.insertItem(trunk);
    assertEquals(Set.of(branch), trunk.getNormalContacts());
    assertAllContactsMatchReference(board);
  }

  private static Padstack createPadstack(RoutingBoard board) {
    board.library.padstacks = new app.freerouting.core.library.Padstacks(board.layerStructure);
    ConvexShape shape = TileShape.getInstance(-50, -50, 50, 50);
    return board.library.padstacks.add("ContactVia", new ConvexShape[] {shape, shape}, true, false);
  }

  private static void assertAllContactsMatchReference(RoutingBoard board) {
    List<Item> items = new ArrayList<>(board.getItems());
    for (Item item : items) {
      if (item instanceof Trace trace) {
        assertEquals(
            new ArrayList<>(exhaustiveContacts(trace, items)),
            new ArrayList<>(trace.getNormalContacts()),
            "Complete ordered contact set for trace " + trace.getId());
      }
    }
  }

  /** Enumerates board items without consulting a spatial index or production query bounds. */
  private static Set<Item> exhaustiveContacts(Trace trace, List<Item> items) {
    Set<Item> result = new TreeSet<>();
    for (Item item : items) {
      if (item == trace || !trace.sharesNet(item) || !trace.sharesLayer(item)) {
        continue;
      }
      if (item instanceof Trace other) {
        if (other.contains(trace.firstCorner())
            || other.contains(trace.lastCorner())
            || trace.contains(other.firstCorner())
            || trace.contains(other.lastCorner())) {
          result.add(other);
        }
      } else if (item instanceof DrillItem drill) {
        if (trace.contains(drill.getCenter())) {
          result.add(drill);
        }
      } else if (item instanceof ConductionArea area) {
        if (area.getArea().contains(trace.firstCorner())
            || area.getArea().contains(trace.lastCorner())) {
          result.add(area);
        }
      }
    }
    return result;
  }

  private static PolylineTrace insert(
      RoutingBoard board, Polyline path, int layer, int net, int halfWidth) {
    return board.insertTraceWithoutCleaning(
        path, layer, halfWidth, new int[] {net}, 1, FixedState.USER_FIXED);
  }

  private static CountingBoard createBoard(boolean compensated) {
    Freerouting.globalSettings = new GlobalSettings();
    LayerStructure layers =
        new LayerStructure(new Layer[] {new Layer("Top", true), new Layer("Bottom", true)});
    BoardRules rules = new BoardRules(layers, ClearanceMatrix.getDefaultInstance(layers, 10));
    rules.createDefaultNetClass();
    CountingBoard board = new CountingBoard(layers, rules);
    board.rules.nets.add("Net1", 1, false);
    board.rules.nets.add("Net2", 2, false);
    board.searchTreeManager.setClearanceCompensationUsed(compensated);
    return board;
  }

  private static class CountingBoard extends RoutingBoard {
    private int candidateCount;

    CountingBoard(LayerStructure layers, BoardRules rules) {
      super(
          new IntBox(-16770000, -16770000, 16770000, 16770000),
          layers,
          new PolylineShape[0],
          0,
          rules,
          new Communication());
    }

    @Override
    public Set<SearchTreeObject> overlappingObjects(ConvexShape shape, int layer) {
      Set<SearchTreeObject> result = super.overlappingObjects(shape, layer);
      candidateCount += result.size();
      return result;
    }
  }
}
