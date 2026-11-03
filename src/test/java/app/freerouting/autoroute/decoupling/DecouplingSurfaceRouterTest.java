package app.freerouting.autoroute.decoupling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.freerouting.autoroute.AutorouteAttemptState;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.io.specctra.DsnTestFixtures;
import app.freerouting.settings.RouterSettings;
import app.freerouting.settings.TraceWidthMode;
import app.freerouting.settings.sources.DefaultSettings;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DecouplingSurfaceRouterTest {

  @Test
  void testRoutePowerLinkNullInputs() {
    SurfaceRouteResult res1 = DecouplingSurfaceRouter.routePowerLink(null, null, null, null);
    assertFalse(res1.success());

    RoutingBoard board = mock(RoutingBoard.class);
    SurfaceRouteResult res2 = DecouplingSurfaceRouter.routePowerLink(board, null, null, null);
    assertFalse(res2.success());
  }

  @Test
  void testRoutePowerLinkMissingPins() {
    RoutingBoard board = mock(RoutingBoard.class);
    when(board.getItem(1)).thenReturn(null);

    DecouplingPair pair = new DecouplingPair(10, 1, 2, 20, 3, 4, 100, 200, 0, 50000L, 85);
    SurfaceRouteResult res = DecouplingSurfaceRouter.routePowerLink(board, pair, null, null);
    assertFalse(res.success());
    assertTrue(res.failureReason().contains("not found"));
  }

  @Test
  void testRoutePowerLinkAlreadyTouchingDirectly() {
    RoutingBoard board = mock(RoutingBoard.class);
    Pin icPin = mock(Pin.class);
    Pin capPin = mock(Pin.class);

    when(board.getItem(1)).thenReturn(icPin);
    when(board.getItem(3)).thenReturn(capPin);
    when(icPin.getCenter()).thenReturn(new IntPoint(100, 100));
    when(capPin.getCenter()).thenReturn(new IntPoint(200, 100));

    // Both return the same item in surface connectable set
    when(icPin.getConnectedSet(100)).thenReturn(Set.of(icPin));
    when(capPin.getConnectedSet(100)).thenReturn(Set.of(icPin));
    when(icPin.firstLayer()).thenReturn(0);
    when(icPin.lastLayer()).thenReturn(0);

    RouterSettings settings = new RouterSettings();
    DecouplingPair pair = new DecouplingPair(10, 3, 4, 20, 1, 2, 100, 200, 0, 10000L, 90);

    SurfaceRouteResult result = DecouplingSurfaceRouter.routePowerLink(board, pair, settings, null);
    assertTrue(result.success());
    assertEquals(0.0, result.routedTraceLength());
    assertEquals(1.0, result.detourRatio());
  }

  @Test
  void testRoutePowerLinkRealFixture() throws IOException {
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue508-DAC2020_bm01.dsn");
    List<DecouplingPair> pairs = DecouplingPairResolver.resolve(board);
    assertFalse(pairs.isEmpty(), "Expected decoupling pairs on bm01");

    RouterSettings settings = new DefaultSettings().getSettings();
    settings.setLayerCount(board.getLayerCount());
    settings.decoupling.enabled = true;
    settings.decoupling.lockTraces = false; // Default: UNFIXED

    DecouplingPair candidate = null;
    for (DecouplingPair pair : pairs) {
      if (!DecouplingTraceCrawler.hasDirectSurfaceTrace(
          board, pair.icPowerPinId(), pair.capPowerPinId(), pair.powerNetNumber(), pair.layer())) {
        candidate = pair;
        break;
      }
    }
    assertNotNull(candidate, "Expected at least one unrouted decoupling pair");

    SurfaceRouteResult result =
        DecouplingSurfaceRouter.routePowerLink(board, candidate, settings, null);
    assertTrue(
        result.success(),
        "Expected successful surface route on golden fixture: " + result.failureReason());
    assertEquals(AutorouteAttemptState.ROUTED, result.state());
    assertTrue(result.routedTraceLength() > 0.0, "Routed trace length should be positive");
    assertTrue(result.detourRatio() >= 1.0, "Detour ratio must be >= 1.0");
    assertTrue(result.detourRatio() <= 2.0, "Detour ratio must be within max limit");
    assertFalse(result.createdItems().isEmpty(), "Created items should not be empty");

    // Invariant: Unfixed by default
    for (Item item : result.createdItems()) {
      assertEquals(
          FixedState.UNFIXED,
          item.getFixedState(),
          "Decoupling traces must stay UNFIXED by default");
    }

    // Direct trace crawler should now see them as connected
    assertTrue(
        DecouplingTraceCrawler.hasDirectSurfaceTrace(
            board,
            candidate.icPowerPinId(),
            candidate.capPowerPinId(),
            candidate.powerNetNumber(),
            candidate.layer()),
        "Direct surface trace crawler must recognize the newly placed connection");
  }

  @Test
  void testRoutePowerLinkDetourLimitRejection() throws IOException {
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue508-DAC2020_bm01.dsn");
    List<DecouplingPair> pairs = DecouplingPairResolver.resolve(board);
    assertFalse(pairs.isEmpty());

    DecouplingPair candidate = pairs.get(0);
    RouterSettings settings = new DefaultSettings().getSettings();
    settings.setLayerCount(board.getLayerCount());
    settings.decoupling.enabled = true;
    // Set impossible detour ratio limit (0.1)
    settings.decoupling.maxDetourRatio = 0.1;

    SurfaceRouteResult result =
        DecouplingSurfaceRouter.routePowerLink(board, candidate, settings, null);
    assertFalse(result.success(), "Route should fail when detour ratio limit is violated");
    assertNotNull(result.failureReason());
    assertTrue(result.failureReason().contains("Detour ratio"));
  }

  @Test
  void testRoutePowerLinkLockingPolicy() throws IOException {
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue508-DAC2020_bm01.dsn");
    List<DecouplingPair> pairs = DecouplingPairResolver.resolve(board);
    assertFalse(pairs.isEmpty());

    DecouplingPair candidate = null;
    for (DecouplingPair pair : pairs) {
      if (!DecouplingTraceCrawler.hasDirectSurfaceTrace(
          board, pair.icPowerPinId(), pair.capPowerPinId(), pair.powerNetNumber(), pair.layer())) {
        candidate = pair;
        break;
      }
    }
    assertNotNull(candidate);

    RouterSettings settings = new DefaultSettings().getSettings();
    settings.setLayerCount(board.getLayerCount());
    settings.decoupling.enabled = true;
    settings.decoupling.lockTraces = true; // Explicitly configured to lock

    SurfaceRouteResult result =
        DecouplingSurfaceRouter.routePowerLink(board, candidate, settings, null);
    assertTrue(result.success());
    assertFalse(result.createdItems().isEmpty());

    for (Item item : result.createdItems()) {
      assertEquals(
          FixedState.SYSTEM_FIXED,
          item.getFixedState(),
          "Should be SYSTEM_FIXED when lockTraces=true");
    }
  }

  @Test
  void testRoutePowerLinkWidthModes() throws IOException {
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue508-DAC2020_bm01.dsn");
    List<DecouplingPair> pairs = DecouplingPairResolver.resolve(board);
    assertFalse(pairs.isEmpty());

    DecouplingPair candidate = pairs.get(0);
    RouterSettings settings = new DefaultSettings().getSettings();
    settings.setLayerCount(board.getLayerCount());
    settings.decoupling.enabled = true;
    settings.decoupling.traceWidthMode = TraceWidthMode.CUSTOM_UM;
    settings.decoupling.traceWidthUm = 300.0;

    SurfaceRouteResult result =
        DecouplingSurfaceRouter.routePowerLink(board, candidate, settings, null);
    assertTrue(result.success());
  }
}
