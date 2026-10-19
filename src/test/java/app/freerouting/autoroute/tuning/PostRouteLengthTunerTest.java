package app.freerouting.autoroute.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.core.RoutingJob;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.rules.Net;
import app.freerouting.rules.NetLengthConstraint;
import app.freerouting.settings.sources.TestingSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PostRouteLengthTunerTest extends RoutingFixtureTest {

  @Test
  @DisplayName("Handles null and unconstrained board gracefully")
  void handlesNullAndUnconstrainedBoardGracefully() {
    PostRouteLengthTuner.TuningReport report = PostRouteLengthTuner.tune(null);
    assertEquals(0, report.netsAttempted());
    assertEquals(0, report.netsSuccessfullyTuned());
    assertEquals(0, report.totalMeandersInserted());
  }

  @Test
  @DisplayName("Splices meander points into polyline trace accurately")
  void splicesMeanderPointsIntoPolylineTrace() {
    // Trace: (0, 0) -> (1000, 0) -> (1000, 1000)
    Point[] origCorners = {new IntPoint(0, 0), new IntPoint(1000, 0), new IntPoint(1000, 1000)};
    Polyline poly = new Polyline(origCorners);

    PolylineTrace dummyTrace =
        new PolylineTrace(
            poly,
            0,
            100,
            new int[] {1},
            0,
            1,
            0,
            app.freerouting.board.model.structure.FixedState.UNFIXED,
            null);

    // Meander points along segment 0 (from (0,0) to (1000, 0))
    Point[] meanderPoints = {
      new IntPoint(0, 0),
      new IntPoint(200, 0),
      new IntPoint(200, 300),
      new IntPoint(400, 300),
      new IntPoint(400, 0),
      new IntPoint(1000, 0)
    };

    Polyline spliced = PostRouteLengthTuner.spliceMeanderIntoTrace(dummyTrace, 0, meanderPoints);
    assertNotNull(spliced);
    assertEquals(new IntPoint(0, 0), spliced.firstCorner());
    assertEquals(new IntPoint(1000, 1000), spliced.lastCorner());
    // 6 meander corners + the remaining (1000, 1000) = 7 corners
    assertEquals(7, spliced.cornerCount());
  }

  @Test
  @DisplayName("Returns null on invalid splice inputs")
  void returnsNullOnInvalidSpliceInputs() {
    assertNull(
        PostRouteLengthTuner.spliceMeanderIntoTrace(null, 0, new Point[] {new IntPoint(0, 0)}));
  }

  @Test
  @DisplayName("Tuning constrained net adds trace length while maintaining clean DRC")
  void tunesConstrainedNetAddingTraceLength() {
    TestingSettings settings = new TestingSettings();
    settings.setMaxPasses(1);
    settings.setMaxItems(2);
    settings.setJobTimeoutString("00:01:00");

    RoutingJob job = getRoutingJob("Issue508-DAC2020_bm01.dsn", settings);
    runRoutingJob(job);

    Net routedNet = null;
    for (int netNo = 1; netNo <= job.board.rules.nets.maxNetNumber(); netNo++) {
      Net net = job.board.rules.nets.get(netNo);
      if (net != null && net.getTraceLength() > 2000.0) {
        routedNet = net;
        break;
      }
    }

    assertNotNull(routedNet, "Expected at least one routed net");
    double initialLength = routedNet.getTraceLength();

    // Set target length 1.5 mm higher than current routed length
    double targetLength = initialLength + 1500.0;
    routedNet.setLengthConstraint(
        new NetLengthConstraint(targetLength - 200.0, targetLength + 200.0));

    PostRouteLengthTuner.TuningReport report = PostRouteLengthTuner.tune(job.board);

    assertNotNull(report);
    assertEquals(1, report.netsAttempted());

    // Trace length must have increased
    double finalLength = routedNet.getTraceLength();
    assertTrue(
        finalLength > initialLength,
        String.format(
            "Expected finalLength (%.1f) > initialLength (%.1f)", finalLength, initialLength));

    // Must not introduce any clearance violations
    assertTrue(
        new DesignRulesChecker(job.board, null).getAllClearanceViolations().isEmpty(),
        "Clearance violations must remain 0 after length tuning");
  }
}
