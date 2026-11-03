package app.freerouting.autoroute.decoupling;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.model.items.ConductionArea;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.trace.PolylineTrace;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DecouplingTraceCrawlerTest {

  @Test
  void testNoDirectTraceReturnsFalse() {
    BasicBoard board = mock(BasicBoard.class);
    Pin pinA = mock(Pin.class);
    Pin pinB = mock(Pin.class);

    when(board.getItem(1)).thenReturn(pinA);
    when(board.getItem(2)).thenReturn(pinB);
    when(pinA.firstLayer()).thenReturn(0);
    when(pinA.lastLayer()).thenReturn(0);
    when(pinB.firstLayer()).thenReturn(0);
    when(pinB.lastLayer()).thenReturn(0);

    // No contacts
    when(pinA.getNormalContacts()).thenReturn(Set.of());

    assertFalse(DecouplingTraceCrawler.hasDirectSurfaceTrace(board, 1, 2, 5, 0));
  }

  @Test
  void testCommonConductionAreaIgnoredReturnsFalse() {
    BasicBoard board = mock(BasicBoard.class);
    Pin pinA = mock(Pin.class);
    Pin pinB = mock(Pin.class);
    ConductionArea pour = mock(ConductionArea.class);

    when(board.getItem(1)).thenReturn(pinA);
    when(board.getItem(2)).thenReturn(pinB);
    when(pinA.firstLayer()).thenReturn(0);
    when(pinA.lastLayer()).thenReturn(0);
    when(pinB.firstLayer()).thenReturn(0);
    when(pinB.lastLayer()).thenReturn(0);

    // Both touch a shared copper pour, but have NO polyline trace between them
    when(pinA.getNormalContacts()).thenReturn(Set.of(pour));
    when(pinB.getNormalContacts()).thenReturn(Set.of(pour));

    // Must return false so the pair is NOT falsely assumed to be already routed
    assertFalse(DecouplingTraceCrawler.hasDirectSurfaceTrace(board, 1, 2, 5, 0));
  }

  @Test
  void testUnbrokenSurfaceTraceChainReturnsTrue() {
    BasicBoard board = mock(BasicBoard.class);
    Pin pinA = mock(Pin.class);
    Pin pinB = mock(Pin.class);
    PolylineTrace trace1 = mock(PolylineTrace.class);
    PolylineTrace trace2 = mock(PolylineTrace.class);

    when(board.getItem(1)).thenReturn(pinA);
    when(board.getItem(2)).thenReturn(pinB);
    when(pinA.firstLayer()).thenReturn(0);
    when(pinA.lastLayer()).thenReturn(0);
    when(pinB.firstLayer()).thenReturn(0);
    when(pinB.lastLayer()).thenReturn(0);

    // Trace 1 on net 5, layer 0
    when(trace1.containsNet(5)).thenReturn(true);
    when(trace1.firstLayer()).thenReturn(0);
    when(trace1.lastLayer()).thenReturn(0);

    // Trace 2 on net 5, layer 0
    when(trace2.containsNet(5)).thenReturn(true);
    when(trace2.firstLayer()).thenReturn(0);
    when(trace2.lastLayer()).thenReturn(0);

    // pinA -> trace1 -> trace2 -> pinB
    when(pinA.getNormalContacts()).thenReturn(Set.of(trace1));
    when(trace1.getNormalContacts()).thenReturn(Set.of(pinA, trace2));
    when(trace2.getNormalContacts()).thenReturn(Set.of(trace1, pinB));
    when(pinB.getNormalContacts()).thenReturn(Set.of(trace2));

    assertTrue(DecouplingTraceCrawler.hasDirectSurfaceTrace(board, 1, 2, 5, 0));
  }

  @Test
  void testTraceOnDifferentLayerReturnsFalse() {
    BasicBoard board = mock(BasicBoard.class);
    Pin pinA = mock(Pin.class);
    Pin pinB = mock(Pin.class);
    PolylineTrace innerTrace = mock(PolylineTrace.class);

    when(board.getItem(1)).thenReturn(pinA);
    when(board.getItem(2)).thenReturn(pinB);
    when(pinA.firstLayer()).thenReturn(0);
    when(pinA.lastLayer()).thenReturn(0);
    when(pinB.firstLayer()).thenReturn(0);
    when(pinB.lastLayer()).thenReturn(0);

    // Inner layer trace (layer 1 instead of layer 0)
    when(innerTrace.containsNet(5)).thenReturn(true);
    when(innerTrace.firstLayer()).thenReturn(1);
    when(innerTrace.lastLayer()).thenReturn(1);

    when(pinA.getNormalContacts()).thenReturn(Set.of(innerTrace));
    when(innerTrace.getNormalContacts()).thenReturn(Set.of(pinA, pinB));

    // Target is layer 0
    assertFalse(DecouplingTraceCrawler.hasDirectSurfaceTrace(board, 1, 2, 5, 0));
  }

  @Test
  void testNullSafety() {
    assertFalse(DecouplingTraceCrawler.hasDirectSurfaceTrace(null, 1, 2, 5, 0));
    assertFalse(DecouplingTraceCrawler.hasDirectSurfaceTrace(mock(BasicBoard.class), 1, 1, 5, 0));
  }
}
