package app.freerouting.autoroute.decoupling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.freerouting.autoroute.decoupling.DecouplingEscapeProbe.EscapeCheckResult;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Pin;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.io.specctra.DsnTestFixtures;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DecouplingEscapeProbeTest {

  @Test
  void testRecordBaselineNullOrEmpty() {
    assertTrue(DecouplingEscapeProbe.recordBaseline(null, 1, 2, 3, 0, 3.0).isEmpty());
    RoutingBoard board = mock(RoutingBoard.class);
    when(board.getItem(2)).thenReturn(null);
    assertTrue(DecouplingEscapeProbe.recordBaseline(board, 1, 2, 3, 0, 3.0).isEmpty());
  }

  @Test
  void testVerifyNeighborsStillEscapableEmptyBaselineReturnsSuccess() {
    EscapeCheckResult result =
        DecouplingEscapeProbe.verifyNeighborsStillEscapable(null, Set.of(), 0);
    assertTrue(result.passed());

    RoutingBoard board = mock(RoutingBoard.class);
    EscapeCheckResult result2 =
        DecouplingEscapeProbe.verifyNeighborsStillEscapable(board, Set.of(), 0);
    assertTrue(result2.passed());
  }

  @Test
  void testVerifyNeighborsDetectsWalledInPin() {
    RoutingBoard board = mock(RoutingBoard.class);
    Pin neighborPin = mock(Pin.class);

    when(board.getItem(42)).thenReturn(neighborPin);
    when(neighborPin.getId()).thenReturn(42);
    when(neighborPin.name()).thenReturn("PIN_42");
    when(neighborPin.getNetNumber(0)).thenReturn(10);
    neighborPin.netNumbers = new int[] {10};
    when(neighborPin.getUnconnectedSet(10)).thenReturn(Set.of(mock(Pin.class)));
    when(neighborPin.getCenter()).thenReturn(new IntPoint(1000, 1000));
    when(neighborPin.boundingBox()).thenReturn(new IntBox(900, 900, 1100, 1100));

    // Simulate all 8 corridors blocked by returning 0 okLength
    when(board.checkTraceSegment(
            any(Point.class),
            any(Point.class),
            anyInt(),
            any(int[].class),
            anyInt(),
            anyInt(),
            anyBoolean()))
        .thenReturn(0.0);

    EscapeCheckResult check =
        DecouplingEscapeProbe.verifyNeighborsStillEscapable(board, Set.of(42), 0);
    assertFalse(check.passed(), "Should fail when all breakout corridors are blocked");
    assertEquals(42, check.blockedPinId());
    assertNotNull(check.reason());
    assertTrue(check.reason().contains("PIN_42"));
  }

  @Test
  void testVerifyNeighborsPassesWhenCorridorIsOpen() {
    RoutingBoard board = mock(RoutingBoard.class);
    Pin neighborPin = mock(Pin.class);

    when(board.getItem(42)).thenReturn(neighborPin);
    when(neighborPin.getId()).thenReturn(42);
    when(neighborPin.name()).thenReturn("PIN_42");
    when(neighborPin.getNetNumber(0)).thenReturn(10);
    neighborPin.netNumbers = new int[] {10};
    when(neighborPin.getUnconnectedSet(10)).thenReturn(Set.of(mock(Pin.class)));
    when(neighborPin.getCenter()).thenReturn(new IntPoint(1000, 1000));
    when(neighborPin.boundingBox()).thenReturn(new IntBox(900, 900, 1100, 1100));

    // Simulate unobstructed space by returning Integer.MAX_VALUE
    when(board.checkTraceSegment(
            any(Point.class),
            any(Point.class),
            anyInt(),
            any(int[].class),
            anyInt(),
            anyInt(),
            anyBoolean()))
        .thenReturn((double) Integer.MAX_VALUE);

    EscapeCheckResult check =
        DecouplingEscapeProbe.verifyNeighborsStillEscapable(board, Set.of(42), 0);
    assertTrue(check.passed(), "Should pass when breakout corridor is open");
  }

  @Test
  void testRealFixtureEscapeBaselineAndVerification() throws IOException {
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue508-DAC2020_bm01.dsn");
    List<DecouplingPair> pairs = DecouplingPairResolver.resolve(board);
    assertFalse(pairs.isEmpty(), "Expected decoupling pairs on bm01");

    DecouplingPair firstPair = pairs.get(0);
    Set<Integer> baseline =
        DecouplingEscapeProbe.recordBaseline(
            board,
            firstPair.icComponentId(),
            firstPair.icPowerPinId(),
            firstPair.icGroundPinId(),
            firstPair.layer(),
            3.0);

    assertNotNull(baseline);
    // On the unmodified board, any baseline escapable pin must still be escapable
    EscapeCheckResult check =
        DecouplingEscapeProbe.verifyNeighborsStillEscapable(board, baseline, firstPair.layer());
    assertTrue(check.passed(), "Unmodified board must have all baseline pins escapable");
  }
}
