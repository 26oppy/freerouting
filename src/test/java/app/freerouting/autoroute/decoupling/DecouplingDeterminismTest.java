package app.freerouting.autoroute.decoupling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.io.specctra.DsnTestFixtures;
import app.freerouting.settings.DecouplingSettings;
import app.freerouting.settings.RouterSettings;
import app.freerouting.settings.sources.DefaultSettings;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Validates that the decoupling capacitor routing stage is 100% deterministic across repeated runs
 * with identical settings and inputs.
 */
class DecouplingDeterminismTest {

  @Test
  void testDecouplingStageDeterminism() throws IOException {
    String fixture = "Issue508-DAC2020_bm01.dsn";

    // Run 1
    RoutingBoard board1 = DsnTestFixtures.loadBoard(fixture);
    RouterSettings settings1 = new DefaultSettings().getSettings();
    settings1.setLayerCount(board1.getLayerCount());
    settings1.decoupling = new DecouplingSettings();
    settings1.decoupling.enabled = true;
    settings1.decoupling.maxRadiusMm = 15.0;

    DecouplingStageResult res1 = DecouplingRoutingStage.run(board1, settings1, null);

    // Run 2
    RoutingBoard board2 = DsnTestFixtures.loadBoard(fixture);
    RouterSettings settings2 = new DefaultSettings().getSettings();
    settings2.setLayerCount(board2.getLayerCount());
    settings2.decoupling = new DecouplingSettings();
    settings2.decoupling.enabled = true;
    settings2.decoupling.maxRadiusMm = 15.0;

    DecouplingStageResult res2 = DecouplingRoutingStage.run(board2, settings2, null);

    // Validate bit-for-bit deterministic outcomes
    assertNotNull(res1);
    assertNotNull(res2);
    assertEquals(res1.totalPairs(), res2.totalPairs(), "Total candidate pair count must match");
    assertEquals(res1.routedPairs(), res2.routedPairs(), "Routed pair count must match");
    assertEquals(res1.skippedPairs(), res2.skippedPairs(), "Skipped pair count must match");
    assertEquals(res1.failedPairs(), res2.failedPairs(), "Failed pair count must match");
    assertEquals(res1.totalViasPlaced(), res2.totalViasPlaced(), "Total vias placed must match");
    assertEquals(
        res1.totalTraceLengthUm(),
        res2.totalTraceLengthUm(),
        0.001,
        "Total routed trace length must match");
    assertEquals(res1.routedIcPinIds(), res2.routedIcPinIds(), "Routed IC pin IDs must match");

    assertEquals(
        board1.getTraces().size(),
        board2.getTraces().size(),
        "Board trace counts must be identical");
    assertEquals(board1.getViaCount(), board2.getViaCount(), "Board via counts must be identical");
    assertEquals(
        board1.getItems().size(),
        board2.getItems().size(),
        "Total board item counts must be identical");
    assertEquals(board1.getHash(), board2.getHash(), "Board state hashes must be identical");
  }
}
