package app.freerouting.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.Freerouting;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Via;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.board.model.structure.Layer;
import app.freerouting.board.model.structure.LayerStructure;
import app.freerouting.board.state.Communication;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.core.library.Padstack;
import app.freerouting.geometry.planar.ConvexShape;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.geometry.planar.PolylineShape;
import app.freerouting.geometry.planar.TileShape;
import app.freerouting.io.specctra.SesWriter;
import app.freerouting.rules.BoardRules;
import app.freerouting.rules.ClearanceMatrix;
import app.freerouting.rules.ViaRule;
import app.freerouting.settings.GlobalSettings;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression and validation tests for issues #958 and #959:
 *
 * <ul>
 *   <li>Verifies that branch traces meeting the centerline of preserved (USER_FIXED / SYSTEM_FIXED)
 *       traces are correctly recognized as connected items, are not classified as tails, and
 *       survive tail cleanup.
 *   <li>Verifies that trace normalization around preserved trace junctions does not trigger
 *       combine-split oscillation loops.
 *   <li>Verifies bidirectional electrical connectivity, via contact recognition, and preserved
 *       geometry integrity upon SES export.
 * </ul>
 */
public class PreservedTraceJunctionTest {

  private RoutingBoard board;
  private Padstack defaultPadstack;

  @BeforeEach
  void setUp() {
    Freerouting.globalSettings = new GlobalSettings();
    board = createTestBoard();
  }

  private RoutingBoard createTestBoard() {
    Layer layer0 = new Layer("Top", true);
    Layer layer1 = new Layer("Bottom", true);
    Layer[] layers = new Layer[] {layer0, layer1};
    LayerStructure layerStructure = new LayerStructure(layers);

    ClearanceMatrix clearanceMatrix = ClearanceMatrix.getDefaultInstance(layerStructure, 10);
    BoardRules boardRules = new BoardRules(layerStructure, clearanceMatrix);
    boardRules.createDefaultNetClass();

    Communication communication = new Communication();

    RoutingBoard routingBoard =
        new RoutingBoard(
            new IntBox(0, 0, 10000000, 10000000),
            layerStructure,
            new PolylineShape[] {TileShape.getInstance(0, 0, 10000000, 10000000)},
            0,
            boardRules,
            communication);

    routingBoard.rules.nets.add("Net1", 1, false);
    routingBoard.rules.nets.add("Net2", 2, false);

    routingBoard.library.padstacks = new app.freerouting.core.library.Padstacks(layerStructure);
    ConvexShape[] shapes =
        new ConvexShape[] {
          TileShape.getInstance(-500, -500, 500, 500), TileShape.getInstance(-500, -500, 500, 500)
        };
    defaultPadstack = routingBoard.library.padstacks.add("TestViaPadstack", shapes, true, false);
    routingBoard.library.addViaPadstack(defaultPadstack);

    ViaRule dummyViaRule = new ViaRule("DefaultViaRule");
    boardRules.viaRules.add(dummyViaRule);
    boardRules.getDefaultNetClass().setViaRule(dummyViaRule);

    return routingBoard;
  }

  /**
   * Issue #958: A branch trace C–J meets the midpoint J of a preserved trace A–B. Contact detection
   * must recognize the junction at J, mark isTail() = false, and preserve C–J during
   * removeTraceTails().
   */
  @Test
  void testIssue958PreservedTraceJunctionTailsNotDeleted() {
    // Preserved trunk trace A–B from (0, 0) to (20000, 0)
    Point pA = new IntPoint(0, 0);
    Point pB = new IntPoint(20000, 0);
    PolylineTrace trunk =
        board.insertTraceWithoutCleaning(
            new Polyline(pA, pB), 0, 500, new int[] {1}, 1, FixedState.USER_FIXED);

    // Preserved anchor trace C–D from (10000, 10000) to (10000, 20000)
    Point pC = new IntPoint(10000, 10000);
    Point pD = new IntPoint(10000, 20000);
    PolylineTrace anchor =
        board.insertTraceWithoutCleaning(
            new Polyline(pC, pD), 0, 500, new int[] {1}, 1, FixedState.USER_FIXED);

    // Unfixed branch trace C–J from (10000, 10000) to midpoint J (10000, 0)
    Point pJ = new IntPoint(10000, 0);
    PolylineTrace branch =
        board.insertTraceWithoutCleaning(
            new Polyline(pC, pJ), 0, 500, new int[] {1}, 1, FixedState.UNFIXED);

    // Verify contact detection
    assertTrue(branch.getStartContacts().contains(anchor), "Branch start should contact anchor");
    assertTrue(branch.getEndContacts().contains(trunk), "Branch end should contact trunk at J");
    assertTrue(trunk.getNormalContacts().contains(branch), "Trunk contacts must include branch");
    assertFalse(branch.isTail(), "Branch meeting preserved trunk must not be a tail");

    // Execute tail cleanup
    boolean removed = board.removeTraceTails(1, Item.StopConnectionOption.NONE);
    assertFalse(removed, "No tails should be removed because the branch is connected");
    assertTrue(branch.isOnTheBoard(), "Branch trace must remain on the board");
  }

  /**
   * Negative control: A branch that stops short of the trunk trace has no end contact, is
   * classified as a tail, and is removed by removeTraceTails().
   */
  @Test
  void testDanglingTailIsDeletedAsNegativeControl() {
    Point pA = new IntPoint(0, 0);
    Point pB = new IntPoint(20000, 0);
    board.insertTraceWithoutCleaning(
        new Polyline(pA, pB), 0, 500, new int[] {1}, 1, FixedState.USER_FIXED);

    Point pC = new IntPoint(10000, 10000);
    Point pD = new IntPoint(10000, 20000);
    PolylineTrace anchor =
        board.insertTraceWithoutCleaning(
            new Polyline(pC, pD), 0, 500, new int[] {1}, 1, FixedState.USER_FIXED);

    // Dangling branch stopping at (10000, 5000)
    Point pStop = new IntPoint(10000, 5000);
    PolylineTrace dangling =
        board.insertTraceWithoutCleaning(
            new Polyline(pC, pStop), 0, 500, new int[] {1}, 1, FixedState.UNFIXED);

    assertTrue(dangling.getStartContacts().contains(anchor));
    assertTrue(dangling.getEndContacts().isEmpty(), "Dangling trace end must have 0 contacts");
    assertTrue(dangling.isTail(), "Dangling trace must be classified as a tail");

    boolean removed = board.removeTraceTails(1, Item.StopConnectionOption.NONE);
    assertTrue(removed, "Dangling trace must be removed");
    assertFalse(dangling.isOnTheBoard(), "Dangling trace should no longer be on the board");
  }

  /**
   * Issue #959: Normalization around preserved traces converges without oscillating or mutating
   * preserved geometry.
   */
  @Test
  void testIssue959NormalizationDoesNotLoopOrMutatePreservedTraces() {
    // Preserved trunk B–A from (20000, 0) to (0, 0)
    Point pB = new IntPoint(20000, 0);
    Point pA = new IntPoint(0, 0);
    PolylineTrace trunk =
        board.insertTraceWithoutCleaning(
            new Polyline(pB, pA), 0, 500, new int[] {1}, 1, FixedState.USER_FIXED);

    // Two unfixed segments connecting along the branch
    Point pJ = new IntPoint(10000, 0);
    Point pMid = new IntPoint(10000, 5000);
    Point pEnd = new IntPoint(10000, 10000);

    board.insertTraceWithoutCleaning(
        new Polyline(pJ, pMid), 0, 500, new int[] {1}, 1, FixedState.UNFIXED);
    board.insertTraceWithoutCleaning(
        new Polyline(pMid, pEnd), 0, 500, new int[] {1}, 1, FixedState.UNFIXED);

    // Run trace normalization
    board.normalizeTraces(1);

    // Second normalization pass should confirm convergence (nothing changed)
    boolean secondPassChanged = board.normalizeTraces(1);
    assertFalse(secondPassChanged, "Normalization should be stable and converge immediately");

    // Preserved trunk must be intact and not split
    assertTrue(trunk.isOnTheBoard(), "Preserved trunk must remain on board");
    assertEquals(FixedState.USER_FIXED, trunk.getFixedState());
    assertEquals(2, trunk.cornerCount(), "Preserved trunk must still have exactly 2 corners");
    assertEquals(pB, trunk.firstCorner());
    assertEquals(pA, trunk.lastCorner());
  }

  /**
   * Verifies bidirectional graph reachability between a preserved trunk and a centerline branch.
   */
  @Test
  void testConnectedSetBidirectionalReachability() {
    Point pA = new IntPoint(0, 0);
    Point pB = new IntPoint(20000, 0);
    PolylineTrace trunk =
        board.insertTraceWithoutCleaning(
            new Polyline(pA, pB), 0, 500, new int[] {1}, 1, FixedState.USER_FIXED);

    Point pC = new IntPoint(10000, 10000);
    Point pJ = new IntPoint(10000, 0);
    PolylineTrace branch =
        board.insertTraceWithoutCleaning(
            new Polyline(pC, pJ), 0, 500, new int[] {1}, 1, FixedState.UNFIXED);

    Set<Item> trunkConnected = trunk.getConnectedSet(1);
    assertTrue(trunkConnected.contains(branch), "Trunk connected set must contain branch");

    Set<Item> branchConnected = branch.getConnectedSet(1);
    assertTrue(branchConnected.contains(trunk), "Branch connected set must contain trunk");

    Collection<Collection<Item>> connectedSets = board.getConnectedSets(1);
    assertEquals(1, connectedSets.size(), "Trunk and branch must form exactly one connected set");
  }

  /**
   * Verifies contact detection and connectivity when a via sits directly on the centerline of a
   * preserved trace.
   */
  @Test
  void testPreservedTraceWithViaJunction() {
    // Preserved trace on layer 0 from (0, 0) to (20000, 0)
    Point pA = new IntPoint(0, 0);
    Point pB = new IntPoint(20000, 0);
    PolylineTrace trunkLayer0 =
        board.insertTraceWithoutCleaning(
            new Polyline(pA, pB), 0, 500, new int[] {1}, 1, FixedState.USER_FIXED);

    // Via at midpoint (10000, 0)
    Point pVia = new IntPoint(10000, 0);
    Via via = board.insertVia(defaultPadstack, pVia, new int[] {1}, 1, FixedState.UNFIXED, true);

    // Trace on layer 1 connecting to via
    Point pC = new IntPoint(10000, 10000);
    PolylineTrace branchLayer1 =
        board.insertTraceWithoutCleaning(
            new Polyline(pVia, pC), 1, 500, new int[] {1}, 1, FixedState.UNFIXED);

    // Preserved anchor on layer 1 from (10000, 10000) to (10000, 20000)
    Point pD = new IntPoint(10000, 20000);
    PolylineTrace anchorLayer1 =
        board.insertTraceWithoutCleaning(
            new Polyline(pC, pD), 1, 500, new int[] {1}, 1, FixedState.USER_FIXED);

    // Check contacts
    assertTrue(via.getNormalContacts().contains(trunkLayer0), "Via must contact layer 0 trunk");
    assertTrue(trunkLayer0.getNormalContacts().contains(via), "Trunk must contact via");
    assertTrue(via.getNormalContacts().contains(branchLayer1), "Via must contact layer 1 branch");
    assertTrue(branchLayer1.getNormalContacts().contains(via), "Branch must contact via");
    assertTrue(
        branchLayer1.getNormalContacts().contains(anchorLayer1), "Branch must contact anchor");

    assertEquals(1, board.getConnectedSets(1).size(), "Should form a single connected set");
    assertFalse(
        branchLayer1.isTail(), "Branch connected between via and anchor must not be a tail");
  }

  /** Verifies contact detection when a branch meets a preserved trace at an internal bend. */
  @Test
  void testPreservedTraceInternalBendJunction() {
    // L-shaped preserved trace with bend at (10000, 0)
    Point p1 = new IntPoint(0, 0);
    Point pBend = new IntPoint(10000, 0);
    Point p2 = new IntPoint(10000, 10000);
    PolylineTrace lTrace =
        board.insertTraceWithoutCleaning(
            new Polyline(new Point[] {p1, pBend, p2}),
            0,
            500,
            new int[] {1},
            1,
            FixedState.USER_FIXED);

    // Anchor at (20000, 0) to (20000, 10000)
    Point p3 = new IntPoint(20000, 0);
    Point p4 = new IntPoint(20000, 10000);
    PolylineTrace anchor =
        board.insertTraceWithoutCleaning(
            new Polyline(p3, p4), 0, 500, new int[] {1}, 1, FixedState.USER_FIXED);

    // Branch from (20000, 0) meeting at the internal bend (10000, 0)
    PolylineTrace branch =
        board.insertTraceWithoutCleaning(
            new Polyline(p3, pBend), 0, 500, new int[] {1}, 1, FixedState.UNFIXED);

    assertTrue(branch.getStartContacts().contains(anchor), "Branch start must contact anchor");
    assertTrue(branch.getEndContacts().contains(lTrace), "Branch end must contact L-trace bend");
    assertTrue(lTrace.getNormalContacts().contains(branch), "L-trace must contact branch");
    assertFalse(branch.isTail(), "Branch meeting at bend must not be a tail");
  }

  /**
   * Negative controls: Verifies that traces on different nets, different layers, or with
   * non-coincident coordinates do not register as contacts.
   */
  @Test
  void testNegativeControls() {
    Point pA = new IntPoint(0, 0);
    Point pB = new IntPoint(20000, 0);
    PolylineTrace trunk =
        board.insertTraceWithoutCleaning(
            new Polyline(pA, pB), 0, 500, new int[] {1}, 1, FixedState.USER_FIXED);

    // Case 1: Different net (Net 2)
    Point pC1 = new IntPoint(10000, 10000);
    Point pJ = new IntPoint(10000, 0);
    PolylineTrace diffNet =
        board.insertTraceWithoutCleaning(
            new Polyline(pC1, pJ), 0, 500, new int[] {2}, 1, FixedState.UNFIXED);
    assertFalse(diffNet.getEndContacts().contains(trunk), "Different net must not contact");
    assertFalse(trunk.getNormalContacts().contains(diffNet));

    // Case 2: Different layer (Layer 1)
    PolylineTrace diffLayer =
        board.insertTraceWithoutCleaning(
            new Polyline(pC1, pJ), 1, 500, new int[] {1}, 1, FixedState.UNFIXED);
    assertFalse(diffLayer.getEndContacts().contains(trunk), "Different layer must not contact");
    assertFalse(trunk.getNormalContacts().contains(diffLayer));

    // Case 3: Near miss by 1 unit (10000, 1)
    Point pNearMiss = new IntPoint(10000, 1);
    PolylineTrace nearMiss =
        board.insertTraceWithoutCleaning(
            new Polyline(pC1, pNearMiss), 0, 500, new int[] {1}, 1, FixedState.UNFIXED);
    assertFalse(nearMiss.getEndContacts().contains(trunk), "Near miss must not contact");

    // Case 4: Collinear but outside segment bounds (25000, 0)
    Point pOutside = new IntPoint(25000, 0);
    Point pFar = new IntPoint(30000, 0);
    PolylineTrace outside =
        board.insertTraceWithoutCleaning(
            new Polyline(pFar, pOutside), 0, 500, new int[] {1}, 1, FixedState.UNFIXED);
    assertFalse(
        outside.getEndContacts().contains(trunk), "Outside collinear point must not contact");
  }

  /**
   * Verifies that preserved trace geometry is preserved completely without being split into
   * multiple wire statements when exported to Specctra SES format.
   */
  @Test
  void testPreservedTraceIntegrityOnSesExport() throws Exception {
    Point pA = new IntPoint(0, 0);
    Point pB = new IntPoint(20000, 0);
    board.insertTraceWithoutCleaning(
        new Polyline(pA, pB), 0, 500, new int[] {1}, 1, FixedState.USER_FIXED);

    Point pC = new IntPoint(10000, 10000);
    Point pJ = new IntPoint(10000, 0);
    board.insertTraceWithoutCleaning(
        new Polyline(pC, pJ), 0, 500, new int[] {1}, 1, FixedState.UNFIXED);

    board.normalizeTraces(1);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    SesWriter.write(board, out, "testDesign.dsn");
    String sesContent = out.toString(StandardCharsets.UTF_8);

    // Verify SES contains route wires
    assertTrue(sesContent.contains("(wire"), "SES output must contain wire definitions");

    // Verify trunk is written as a single wire from 0 to 20000 (scaled)
    // Scale factor: dsnToBoard(1) / resolution. With defaults, coordinates match.
    // Confirm there are exactly 2 wires exported for Net1 (trunk + branch), not 3 (split trunk +
    // branch)
    int wireCount = 0;
    int index = 0;
    while ((index = sesContent.indexOf("(wire", index)) != -1) {
      wireCount++;
      index += 5;
    }
    assertEquals(
        2, wireCount, "Exactly 2 wires should be exported; preserved trunk must not be split");
  }
}
