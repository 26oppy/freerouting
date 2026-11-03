package app.freerouting.autoroute.decoupling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.model.items.Pin;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.settings.RouterSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

class DecouplingPairResolverTest {

  private Pin createMockPin(int id, int compId, int net, int x, int y, int layer) {
    Pin pin = mock(Pin.class);
    when(pin.getId()).thenReturn(id);
    when(pin.getComponentId()).thenReturn(compId);
    when(pin.firstLayer()).thenReturn(layer);
    when(pin.lastLayer()).thenReturn(layer);
    when(pin.containsNet(net)).thenReturn(true);
    when(pin.getCenter()).thenReturn(new IntPoint(x, y));
    return pin;
  }

  @Test
  void testResolveBasicPairWithMatchingIcGroundPin() {
    BasicBoard board = mock(BasicBoard.class);
    BoardIndex boardIndex = mock(BoardIndex.class);

    Pin capPinA = createMockPin(10, 1, 2, 0, 0, 0); // Power net 2
    Pin capPinB = createMockPin(11, 1, 1, 1000, 0, 0); // Ground net 1
    Pin icPinPower = createMockPin(100, 10, 2, 2000, 2000, 0);
    Pin icPinGround = createMockPin(101, 10, 1, 2000, 1000, 0);

    when(board.getItem(10)).thenReturn(capPinA);
    when(board.getItem(11)).thenReturn(capPinB);
    when(board.getItem(100)).thenReturn(icPinPower);
    when(board.getItem(101)).thenReturn(icPinGround);

    when(boardIndex.getPinsOfComponent(10)).thenReturn(List.of(icPinPower, icPinGround));

    OwnerCandidate owner = new OwnerCandidate(100, 10, 16, 8000000L);
    CapacitorCandidate candidate =
        new CapacitorCandidate(
            1,
            10,
            11,
            2,
            1,
            2,
            1,
            0,
            500000L,
            500000L,
            75,
            Verdict.DECOUPLING,
            0L,
            AuditResult.AGREE,
            List.of(owner));

    IdentificationResult result =
        new IdentificationResult(List.of(candidate), new DiagnosticsSummary(1, 1, 0, 0, 0, 0));
    RouterSettings settings = new RouterSettings();
    settings.decoupling.minScoreThreshold = 65;

    List<DecouplingPair> pairs =
        DecouplingPairResolver.resolve(board, boardIndex, result, settings);

    assertEquals(1, pairs.size());
    DecouplingPair pair = pairs.get(0);
    assertEquals(1, pair.capacitorComponentId());
    assertEquals(10, pair.capPowerPinId());
    assertEquals(11, pair.capGroundPinId());
    assertEquals(10, pair.icComponentId());
    assertEquals(100, pair.icPowerPinId());
    assertEquals(101, pair.icGroundPinId());
    assertEquals(2, pair.powerNetNumber());
    assertEquals(1, pair.groundNetNumber());
    assertEquals(0, pair.layer());
    assertEquals(8000000L, pair.distanceSquared());
    assertEquals(75, pair.score());
  }

  @Test
  void testFiltersCandidatesBelowScoreThreshold() {
    BasicBoard board = mock(BasicBoard.class);
    BoardIndex boardIndex = mock(BoardIndex.class);

    OwnerCandidate owner = new OwnerCandidate(100, 10, 16, 5000000L);
    CapacitorCandidate lowScoreCandidate =
        new CapacitorCandidate(
            1,
            10,
            11,
            2,
            1,
            2,
            1,
            0,
            500000L,
            500000L,
            55, // Below default 65
            Verdict.DECOUPLING,
            0L,
            AuditResult.AGREE,
            List.of(owner));

    IdentificationResult result =
        new IdentificationResult(
            List.of(lowScoreCandidate), new DiagnosticsSummary(1, 1, 0, 0, 0, 0));
    RouterSettings settings = new RouterSettings();
    settings.decoupling.minScoreThreshold = 65;

    List<DecouplingPair> pairs =
        DecouplingPairResolver.resolve(board, boardIndex, result, settings);
    assertTrue(pairs.isEmpty(), "Candidate with score 55 should be filtered out by threshold 65");
  }

  @Test
  void testMultiCapacitorAssignmentToSameIcPin() {
    BasicBoard board = mock(BasicBoard.class);
    BoardIndex boardIndex = mock(BoardIndex.class);

    // IC has 1 power pin (ID 100)
    Pin icPowerPin = createMockPin(100, 50, 2, 5000, 5000, 0);
    when(board.getItem(100)).thenReturn(icPowerPin);
    when(boardIndex.getPinsOfComponent(50)).thenReturn(List.of(icPowerPin));

    // Cap 1: 0402 ceramic (smaller pad area 200,000, distance 2,000,000)
    Pin c1PinA = createMockPin(1, 10, 2, 4000, 4000, 0);
    Pin c1PinB = createMockPin(2, 10, 1, 4000, 4500, 0);
    when(board.getItem(1)).thenReturn(c1PinA);
    when(board.getItem(2)).thenReturn(c1PinB);
    CapacitorCandidate cap1 =
        new CapacitorCandidate(
            10,
            1,
            2,
            2,
            1,
            2,
            1,
            0,
            200000L,
            200000L,
            80,
            Verdict.DECOUPLING,
            0L,
            AuditResult.AGREE,
            List.of(new OwnerCandidate(100, 50, 20, 2000000L)));

    // Cap 2: 1206 bulk (larger pad area 800,000, distance 1,500,000)
    Pin c2PinA = createMockPin(3, 11, 2, 4200, 4200, 0);
    Pin c2PinB = createMockPin(4, 11, 1, 4200, 4900, 0);
    when(board.getItem(3)).thenReturn(c2PinA);
    when(board.getItem(4)).thenReturn(c2PinB);
    CapacitorCandidate cap2 =
        new CapacitorCandidate(
            11,
            3,
            4,
            2,
            1,
            2,
            1,
            0,
            800000L,
            800000L,
            80,
            Verdict.DECOUPLING,
            0L,
            AuditResult.AGREE,
            List.of(new OwnerCandidate(100, 50, 20, 1500000L)));

    IdentificationResult result =
        new IdentificationResult(List.of(cap2, cap1), new DiagnosticsSummary(2, 2, 0, 0, 0, 0));
    RouterSettings settings = new RouterSettings();

    List<DecouplingPair> pairs =
        DecouplingPairResolver.resolve(board, boardIndex, result, settings);

    // Greedy 1:1 assignment: Smaller 0402 package (cap1) must be prioritized over larger 1206 bulk
    // cap
    assertEquals(1, pairs.size());
    assertEquals(
        10, pairs.get(0).capacitorComponentId(), "Smaller package Cap 1 should win IC pin 100");
    assertEquals(1, pairs.get(0).capPowerPinId());
  }

  @Test
  void testNoIcGroundPinSetsNegativeOne() {
    BasicBoard board = mock(BasicBoard.class);
    BoardIndex boardIndex = mock(BoardIndex.class);

    Pin capPinA = createMockPin(10, 1, 2, 0, 0, 0);
    Pin capPinB = createMockPin(11, 1, 1, 1000, 0, 0);
    Pin icPinPower = createMockPin(100, 10, 2, 2000, 2000, 0);

    when(board.getItem(10)).thenReturn(capPinA);
    when(board.getItem(11)).thenReturn(capPinB);
    when(board.getItem(100)).thenReturn(icPinPower);

    // IC has NO pins on ground net 1
    when(boardIndex.getPinsOfComponent(10)).thenReturn(List.of(icPinPower));

    CapacitorCandidate candidate =
        new CapacitorCandidate(
            1,
            10,
            11,
            2,
            1,
            2,
            1,
            0,
            500000L,
            500000L,
            70,
            Verdict.DECOUPLING,
            0L,
            AuditResult.AGREE,
            List.of(new OwnerCandidate(100, 10, 8, 8000000L)));

    IdentificationResult result =
        new IdentificationResult(List.of(candidate), new DiagnosticsSummary(1, 1, 0, 0, 0, 0));

    List<DecouplingPair> pairs = DecouplingPairResolver.resolve(board, boardIndex, result, null);

    assertEquals(1, pairs.size());
    assertEquals(-1, pairs.get(0).icGroundPinId());
  }

  @Test
  void testNullSafety() {
    assertTrue(DecouplingPairResolver.resolve(null, null, null, null).isEmpty());
    assertTrue(
        DecouplingPairResolver.resolve(
                mock(BasicBoard.class),
                null,
                new IdentificationResult(List.of(), new DiagnosticsSummary(0, 0, 0, 0, 0, 0)),
                null)
            .isEmpty());
  }
}
