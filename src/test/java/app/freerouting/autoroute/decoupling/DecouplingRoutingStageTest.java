package app.freerouting.autoroute.decoupling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.autoroute.pipeline.BatchAutorouter;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.core.RoutingJob;
import app.freerouting.core.RoutingJobState;
import app.freerouting.core.StoppableThread;
import app.freerouting.io.specctra.DsnTestFixtures;
import app.freerouting.settings.DecouplingSettings;
import app.freerouting.settings.RouterSettings;
import app.freerouting.settings.sources.DefaultSettings;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class DecouplingRoutingStageTest {

  @Test
  void testDecouplingRoutingStageNullGuards() throws IOException {
    RouterSettings settings = new DefaultSettings().getSettings();
    settings.decoupling = new DecouplingSettings();
    settings.decoupling.enabled = true;

    // Null board
    DecouplingStageResult res1 = DecouplingRoutingStage.run(null, settings, null);
    assertNotNull(res1);
    assertEquals(0, res1.totalPairs());

    // Null settings
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue508-DAC2020_bm01.dsn");
    DecouplingStageResult res2 = DecouplingRoutingStage.run(board, null, null);
    assertNotNull(res2);
    assertEquals(0, res2.totalPairs());

    // Disabled decoupling
    settings.decoupling.enabled = false;
    DecouplingStageResult res3 = DecouplingRoutingStage.run(board, settings, null);
    assertNotNull(res3);
    assertEquals(0, res3.totalPairs());
  }

  @Test
  void testDecouplingRoutingStageCancelledThread() throws IOException {
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue508-DAC2020_bm01.dsn");
    RouterSettings settings = new DefaultSettings().getSettings();
    settings.decoupling = new DecouplingSettings();
    settings.decoupling.enabled = true;

    StoppableThread thread =
        new StoppableThread() {
          @Override
          protected void threadAction() {}

          @Override
          public boolean isStopRequested() {
            return true;
          }
        };

    DecouplingStageResult res = DecouplingRoutingStage.run(board, settings, thread);
    assertNotNull(res);
    assertEquals(0, res.totalPairs());
  }

  @Test
  void testDecouplingRoutingStageExecutionOnRealFixture() throws IOException {
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue508-DAC2020_bm01.dsn");
    RouterSettings settings = new DefaultSettings().getSettings();
    settings.setLayerCount(board.getLayerCount());
    settings.decoupling = new DecouplingSettings();
    settings.decoupling.enabled = true;
    settings.decoupling.maxRadiusMm = 15.0;

    DecouplingStageResult result = DecouplingRoutingStage.run(board, settings, null);
    assertNotNull(result);
    assertTrue(result.totalPairs() > 0, "Should detect candidate decoupling pairs");
    assertTrue(result.routedPairs() > 0, "Should successfully route at least one pair");
    assertFalse(result.routedIcPinIds().isEmpty(), "Routed IC pin IDs set should be populated");
    assertTrue(result.totalTraceLengthUm() > 0.0, "Total copper trace length should be positive");
    assertTrue(result.routedPercentage() > 0.0, "Routed percentage should be > 0");
    assertNotNull(result.pairResults(), "Per-pair results list must not be null");
  }

  @Test
  void testDecouplingStageResultMethods() {
    DecouplingStageResult empty = DecouplingStageResult.empty();
    assertEquals(0, empty.totalPairs());
    assertEquals(0, empty.routedPairs());
    assertEquals(0.0, empty.routedPercentage());
    assertTrue(empty.routedIcPinIds().isEmpty());

    DecouplingStageResult cancelled = DecouplingStageResult.cancelled();
    assertEquals(0, cancelled.totalPairs());
  }

  @Test
  void testDecouplingOnlyModePipelineExecution() throws IOException {
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue508-DAC2020_bm01.dsn");
    RouterSettings settings = new DefaultSettings().getSettings();
    settings.setLayerCount(board.getLayerCount());
    settings.decoupling = new DecouplingSettings();
    settings.decoupling.enabled = true;
    settings.decoupling.only = true;
    settings.decoupling.maxRadiusMm = 15.0;
    settings.autorouter.maxPasses = 99; // Ensure passes are configured but will be skipped

    RoutingJob job = new RoutingJob();
    job.board = board;
    job.routerSettings = settings;

    BatchAutorouter router = new BatchAutorouter(job);
    boolean completed = router.runBatchLoop();

    assertTrue(completed, "Batch loop should report completion in decoupling-only mode");
    assertEquals(
        RoutingJobState.COMPLETED,
        job.state,
        "Decoupling-only job should complete with COMPLETED state");
  }
}
