package app.freerouting.autoroute.tuning;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.autoroute.pipeline.RoutingPipeline;
import app.freerouting.core.RoutingJob;
import app.freerouting.core.StoppableThread;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.settings.sources.TestingSettings;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PostRouteLengthTuningPipelineTest extends RoutingFixtureTest {

  private static final class NoOpStoppableThread extends StoppableThread {
    @Override
    protected void threadAction() {}

    @Override
    public synchronized void requestStop() {}

    @Override
    public synchronized boolean isStopRequested() {
      return false;
    }
  }

  @Test
  @DisplayName("RoutingPipeline executes length tuning stage and triggers lifecycle callback")
  void pipelineExecutesLengthTuningStage() {
    TestingSettings settings = new TestingSettings();
    settings.setMaxPasses(1);
    settings.setMaxItems(2);
    settings.setJobTimeoutString("00:01:00");

    RoutingJob job = getRoutingJob("Issue508-DAC2020_bm01.dsn", settings);
    job = runRoutingJob(job);

    job.thread = new NoOpStoppableThread();
    job.routerSettings.autorouter.maxPasses = 0;
    RoutingPipeline pipeline = RoutingPipeline.createForHeadless(job);
    AtomicBoolean lengthTuningFired = new AtomicBoolean(false);
    pipeline.addStageListener(
        new RoutingPipeline.StageListener() {
          @Override
          public void afterLengthTuning(RoutingJob rJob) {
            lengthTuningFired.set(true);
          }
        });

    pipeline.run();

    assertTrue(lengthTuningFired.get(), "Expected afterLengthTuning lifecycle event to fire");
    assertTrue(
        new DesignRulesChecker(job.board, null).getAllClearanceViolations().isEmpty(),
        "Zero clearance violations expected after pipeline execution");
  }

  @Test
  @DisplayName("RoutingPipeline respects lengthTuning=false CLI/settings switch")
  void pipelineRespectsLengthTuningDisabled() {
    TestingSettings settings = new TestingSettings();
    settings.setMaxPasses(1);
    settings.setMaxItems(2);
    settings.setJobTimeoutString("00:01:00");

    RoutingJob job = getRoutingJob("Issue508-DAC2020_bm01.dsn", settings);
    job = runRoutingJob(job);

    job.thread = new NoOpStoppableThread();
    job.routerSettings.autorouter.maxPasses = 0;
    job.routerSettings.setLengthTuningEnabled(false);
    RoutingPipeline pipeline = RoutingPipeline.createForHeadless(job);
    AtomicBoolean lengthTuningFired = new AtomicBoolean(false);
    pipeline.addStageListener(
        new RoutingPipeline.StageListener() {
          @Override
          public void afterLengthTuning(RoutingJob rJob) {
            lengthTuningFired.set(true);
          }
        });

    pipeline.run();

    assertFalse(lengthTuningFired.get(), "Length tuning stage must NOT fire when disabled");
  }
}
