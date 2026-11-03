package app.freerouting.autoroute.decoupling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.items.Via;
import app.freerouting.core.library.Padstack;
import app.freerouting.drc.ClearanceViolation;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.io.specctra.DsnTestFixtures;
import app.freerouting.settings.RouterSettings;
import app.freerouting.settings.sources.DefaultSettings;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DecouplingGroundRouterTest {

  @Test
  void testNullInputs() {
    assertFalse(DecouplingGroundRouter.routeGroundReturn(null, null, null, null).success());
    assertFalse(DecouplingGroundRouter.routePairAtomic(null, null, null, null).success());
    assertFalse(DecouplingGroundRouter.hasClearanceViolation(null));
    assertFalse(DecouplingGroundRouter.hasClearanceViolation(List.of()));
    assertTrue(DecouplingGroundRouter.isViaOutsidePad(null, null));
  }

  @Test
  void testIsViaOutsidePadDetection() {
    Via via = mock(Via.class);
    Pin pin = mock(Pin.class);
    Padstack padstack = mock(Padstack.class);

    when(via.getPadstack()).thenReturn(padstack);
    when(padstack.getDrillRadius()).thenReturn(150.0); // 150 um drill radius
    when(via.firstLayer()).thenReturn(0);
    when(via.lastLayer()).thenReturn(1);
    when(pin.firstLayer()).thenReturn(0);
    when(pin.lastLayer()).thenReturn(0);

    // Pad bounding box [0, 0] to [1000, 1000]
    IntBox padBox = new IntBox(0, 0, 1000, 1000);
    when(pin.getShapeOnLayer(0)).thenReturn(padBox);

    // Case 1: Via center is completely inside pad (500, 500)
    when(via.getCenter()).thenReturn(new IntPoint(500, 500));
    assertFalse(
        DecouplingGroundRouter.isViaOutsidePad(via, pin),
        "Via center inside pad must be detected and rejected");

    // Case 2: Via center is outside pad (1100, 500), but drill circle overlaps pad border (1000)
    when(via.getCenter()).thenReturn(new IntPoint(1100, 500));
    assertFalse(
        DecouplingGroundRouter.isViaOutsidePad(via, pin),
        "Via drill hole intersecting pad edge must be detected and rejected");

    // Case 3: Via center is safely outside pad (1500, 500) with zero overlap
    when(via.getCenter()).thenReturn(new IntPoint(1500, 500));
    assertTrue(
        DecouplingGroundRouter.isViaOutsidePad(via, pin),
        "Via safely placed outside pad must pass");
  }

  @Test
  void testHasClearanceViolation() {
    Item cleanItem = mock(Item.class);
    when(cleanItem.clearanceViolations()).thenReturn(Set.of());
    assertFalse(DecouplingGroundRouter.hasClearanceViolation(List.of(cleanItem)));

    Item dirtyItem = mock(Item.class);
    ClearanceViolation violation = mock(ClearanceViolation.class);
    when(dirtyItem.clearanceViolations()).thenReturn(Set.of(violation));
    assertTrue(DecouplingGroundRouter.hasClearanceViolation(List.of(cleanItem, dirtyItem)));
  }

  @Test
  void testRouteGroundReturnRealFixture() throws IOException {
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue508-DAC2020_bm01.dsn");
    List<DecouplingPair> pairs = DecouplingPairResolver.resolve(board);
    assertFalse(pairs.isEmpty(), "Expected decoupling pairs on bm01");

    RouterSettings settings = new DefaultSettings().getSettings();
    settings.setLayerCount(board.getLayerCount());
    settings.decoupling.enabled = true;

    DecouplingPair pair = pairs.get(0);
    GroundRouteResult groundResult =
        DecouplingGroundRouter.routeGroundReturn(board, pair, settings, null);

    assertTrue(
        groundResult.success(),
        "Ground return route must succeed: " + groundResult.failureReason());
    assertNotNull(groundResult.topologyType());

    // If a via was placed, verify it is strictly outside the pad
    Pin capGroundPin = (Pin) board.getItem(pair.capGroundPinId());
    for (Item item : groundResult.createdItems()) {
      if (item instanceof Via via) {
        assertTrue(
            DecouplingGroundRouter.isViaOutsidePad(via, capGroundPin),
            "Created ground via must stay strictly outside the SMD pad");
      }
    }
  }

  @Test
  void testRoutePairAtomicRealFixture() throws IOException {
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue508-DAC2020_bm01.dsn");
    List<DecouplingPair> pairs = DecouplingPairResolver.resolve(board);
    assertFalse(pairs.isEmpty(), "Expected decoupling pairs on bm01");

    RouterSettings settings = new DefaultSettings().getSettings();
    settings.setLayerCount(board.getLayerCount());
    settings.decoupling.enabled = true;

    DecouplingPair candidate = null;
    for (DecouplingPair pair : pairs) {
      if (!DecouplingTraceCrawler.hasDirectSurfaceTrace(
          board, pair.icPowerPinId(), pair.capPowerPinId(), pair.powerNetNumber(), pair.layer())) {
        candidate = pair;
        break;
      }
    }
    assertNotNull(candidate, "Expected at least one unrouted decoupling pair");

    DecouplingPairResult result =
        DecouplingGroundRouter.routePairAtomic(board, candidate, settings, null);

    assertTrue(result.success(), "Atomic pair route must succeed: " + result.failureReason());
    assertNotNull(result.powerResult());
    assertTrue(result.powerResult().success());
    assertNotNull(result.groundResult());
    assertTrue(result.groundResult().success());

    // Both power and ground now connected on board
    assertTrue(
        DecouplingTraceCrawler.hasDirectSurfaceTrace(
            board,
            candidate.icPowerPinId(),
            candidate.capPowerPinId(),
            candidate.powerNetNumber(),
            candidate.layer()),
        "Power link must be established on surface");
  }

  @Test
  void testAtomicRollbackRestoresBoardOnFailure() throws IOException {
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue508-DAC2020_bm01.dsn");
    List<DecouplingPair> pairs = DecouplingPairResolver.resolve(board);
    assertFalse(pairs.isEmpty());

    int itemCountBefore = board.getItems().size();

    RouterSettings settings = new DefaultSettings().getSettings();
    settings.setLayerCount(board.getLayerCount());
    settings.decoupling.enabled = true;
    // Set an impossible detour ratio to force rollback after routing
    settings.decoupling.maxDetourRatio = 0.05;

    DecouplingPair candidate = pairs.get(0);
    DecouplingPairResult result =
        DecouplingGroundRouter.routePairAtomic(board, candidate, settings, null);

    assertFalse(result.success(), "Pair routing must fail due to detour limit");
    assertEquals(
        itemCountBefore,
        board.getItems().size(),
        "Board item count must be restored to exact baseline count upon transaction failure");
  }
}
