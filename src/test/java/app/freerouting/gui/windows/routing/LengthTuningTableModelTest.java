package app.freerouting.gui.windows.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.actions.ItemIdGenerator;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.management.HeadlessBoardManager;
import app.freerouting.rules.Net;
import app.freerouting.rules.NetLengthConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LengthTuningTableModelTest extends RoutingFixtureTest {

  @Test
  @DisplayName("Handles null board gracefully")
  void handlesNullBoard() {
    LengthTuningTableModel model = new LengthTuningTableModel();
    model.refreshData(null);
    assertEquals(0, model.getRowCount());
    assertEquals(6, model.getColumnCount());
    assertNull(model.getRowAt(0));
    assertNull(model.getNetAt(0));
  }

  @Test
  @DisplayName("Populates nets from real board and supports filtering and status")
  void populatesAndFiltersNets() {
    RoutingJob job = getRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdGenerator());
    RoutingBoard board = boardManager.getRoutingBoard();

    LengthTuningTableModel model = new LengthTuningTableModel();
    model.refreshData(board);

    assertTrue(model.getRowCount() > 0, "Expected table rows for board nets");
    assertEquals("Net", model.getColumnName(0));
    assertEquals("Class", model.getColumnName(1));
    assertEquals("Current Length", model.getColumnName(2));
    assertEquals("Target Length", model.getColumnName(3));
    assertEquals("Tolerance (±)", model.getColumnName(4));
    assertEquals("Status", model.getColumnName(5));

    // Initially none are constrained
    model.setFilter("", true, false);
    assertEquals(0, model.getRowCount(), "Zero constrained nets expected initially");

    // Add length constraint to net 1
    Net net1 = board.rules.nets.get(1);
    assertNotNull(net1);
    net1.setLengthConstraint(new NetLengthConstraint(1000.0, 1500.0));

    model.refreshData(board);
    model.setFilter("", true, false);
    assertEquals(1, model.getRowCount(), "Expected exactly 1 constrained net");

    LengthTuningTableModel.NetRow row = model.getRowAt(0);
    assertNotNull(row);
    assertEquals(net1.name, row.name);
    assertTrue(row.isConstrained);

    // Text filter test
    model.setFilter(net1.name, false, false);
    assertEquals(1, model.getRowCount());

    model.setFilter("NON_EXISTENT_NET_XYZ", false, false);
    assertEquals(0, model.getRowCount());
  }
}
