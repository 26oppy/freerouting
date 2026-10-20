package app.freerouting.gui.windows.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import app.freerouting.board.actions.ItemIdGenerator;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.management.HeadlessBoardManager;
import app.freerouting.rules.DifferentialPair;
import app.freerouting.rules.Net;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DiffPairTableModelTest extends RoutingFixtureTest {

  @Test
  @DisplayName("Handles null board gracefully")
  void handlesNullBoard() {
    DiffPairTableModel model = new DiffPairTableModel();
    model.refreshData(null);
    assertEquals(0, model.getRowCount());
    assertEquals(6, model.getColumnCount());
    assertNull(model.getRowAt(0));
    assertNull(model.getPairAt(0));
  }

  @Test
  @DisplayName("Populates differential pairs and reflects status")
  void populatesDifferentialPairs() {
    RoutingJob job = getRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdGenerator());
    RoutingBoard board = boardManager.getRoutingBoard();

    board.rules.differentialPairs.clear();

    Net net1 = board.rules.nets.get(1);
    Net net2 = board.rules.nets.get(2);
    assertNotNull(net1);
    assertNotNull(net2);

    DifferentialPair pair = new DifferentialPair(net1, net2, "PAIR_TEST", 600.0, 50.0);
    board.rules.differentialPairs.add(pair);

    DiffPairTableModel model = new DiffPairTableModel();
    model.refreshData(board);

    assertEquals(1, model.getRowCount());
    assertEquals("PAIR_TEST", model.getValueAt(0, 0));
    assertEquals(net1.name, model.getValueAt(0, 1));
    assertEquals(net2.name, model.getValueAt(0, 2));

    DiffPairTableModel.PairRow row = model.getRowAt(0);
    assertNotNull(row);
    assertEquals("PAIR_TEST", row.baseName);
  }

  @Test
  @DisplayName("Auto-detects complementary differential pair names")
  void autoDetectsComplementaryPairs() {
    RoutingJob job = getRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdGenerator());
    RoutingBoard board = boardManager.getRoutingBoard();

    board.rules.differentialPairs.clear();

    // Add two complementary nets with _P and _N suffixes
    board.rules.nets.add("USB_D_P", 1, false);
    board.rules.nets.add("USB_D_N", 1, false);

    DiffPairTableModel model = new DiffPairTableModel();
    int detected = model.autoDetectPairs(board);

    // D+ / D- from fixture and USB_D_P / USB_D_N both detected
    assertEquals(2, detected, "Expected D and USB_D pairs to be auto-detected");
    assertEquals(2, model.getRowCount());
  }
}
