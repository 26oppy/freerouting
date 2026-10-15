package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.TestFixtures;
import app.freerouting.autoroute.pipeline.BatchOptimizer;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Trace;
import app.freerouting.board.model.items.Via;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.core.StoppableThread;
import app.freerouting.core.scoring.BoardStatistics;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.io.specctra.SesReader;
import app.freerouting.management.HeadlessBoardManager;
import app.freerouting.settings.sources.TestingSettings;
import java.nio.file.Files;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Exercises contact discovery during a complete optimizer sweep over editable, routed copper. */
@Tag("slow")
class TraceContactSearchOptimizerTest extends RoutingFixtureTest {

  @Test
  void completeOptimizerSweepPreservesConnectivityAndClearance() throws Exception {
    var job = getRoutingJob("Issue508-DAC2020_bm08.dsn", new TestingSettings());
    HeadlessBoardManager manager = new HeadlessBoardManager(job);
    manager.loadFromSpecctraDsn(job.input.getData(), null, null);
    RoutingBoard board = manager.getRoutingBoard();
    job.board = board;
    try (var stream =
        Files.newInputStream(TestFixtures.resolvePath("Issue508-DAC2020_bm08-routed.ses"))) {
      SesReader.read(stream, board);
    }
    // SES import preserves routes as USER_FIXED. Unlock them so the sweep has actual work.
    for (Item item : board.getItems()) {
      if (item instanceof Trace || item instanceof Via) {
        item.setFixedState(FixedState.UNFIXED);
      }
    }
    board.finishAutoroute();
    BoardStatistics before = new BoardStatistics(board);
    assertEquals(0, before.connections.incompleteCount);
    assertTrue(new DesignRulesChecker(board, null).getAllClearanceViolations().isEmpty());

    job.thread = new NoOpStoppableThread();
    job.routerSettings.optimizer.enabled = true;
    job.routerSettings.optimizer.maxThreads = 1;
    job.routerSettings.optimizer.maxPasses = 1;
    job.routerSettings.optimizer.maxItems = Integer.MAX_VALUE;
    job.routerSettings.optimizer.maxAutoroutePasses = 2;
    job.routerSettings.optimizer.enablePreflightGuards = false;
    job.routerSettings.optimizer.maxConsecutiveFailures = Integer.MAX_VALUE;
    job.routerSettings.optimizer.maxConsecutiveFailuresPass1 = Integer.MAX_VALUE;
    BatchOptimizer.create(job).runBatchLoop();

    BoardStatistics after = new BoardStatistics(job.board);
    assertEquals(0, after.connections.incompleteCount);
    assertTrue(new DesignRulesChecker(job.board, null).getAllClearanceViolations().isEmpty());
    assertTrue(
        after.traces.totalLength < before.traces.totalLength,
        "The unlocked fixture must exercise a successful optimization, not an empty sweep");
    assertEquals(1, job.resultPhaseMetrics.optimizer.passesCompleted);
  }

  private static class NoOpStoppableThread extends StoppableThread {
    @Override
    protected void threadAction() {}
  }
}
