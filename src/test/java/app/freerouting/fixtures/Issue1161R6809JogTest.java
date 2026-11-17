package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.model.items.Trace;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.core.RoutingJob;
import app.freerouting.core.RoutingJobState;
import app.freerouting.core.scoring.BoardStatistics;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.geometry.planar.Point;
import app.freerouting.rules.Net;
import app.freerouting.settings.sources.TestingSettings;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("slow")
class Issue1161R6809JogTest extends RoutingFixtureTest {

  @Test
  void testR6809RoutingJogsAndChains() {
    TestingSettings ts = new TestingSettings();
    ts.setMaxPasses(1);
    ts.setJobTimeoutString("00:05:00");
    ts.setRouterEnabled(true);
    ts.setOptimizerEnabled(true);

    RoutingJob job = getRoutingJob("Issue1161-r6809-stripped.dsn", ts);
    runRoutingJob(job);

    assertEquals(RoutingJobState.COMPLETED, job.state);

    // Assert 0 clearance violations
    var violations = new DesignRulesChecker(job.board, null).getAllClearanceViolations();
    assertTrue(violations.isEmpty(), () -> "Clearance violations found: " + violations.size());

    // Assert 0 undersized traces (< 100 half-width = 200 um)
    List<String> undersized = new ArrayList<>();
    for (Trace trace : job.board.getTraces()) {
      if (trace.getHalfWidth() < 100) {
        undersized.add("Net " + trace.getNetNumber(0) + " trace halfWidth " + trace.getHalfWidth());
      }
    }
    assertTrue(undersized.isEmpty(), () -> "Undersized traces found: " + undersized);

    // Count chains of 3+ connected segments shorter than 1 mm (10000 units)
    int shortChainCount = 0;
    int totalShortSegmentsInChains = 0;
    for (Trace trace : job.board.getTraces()) {
      if (!(trace instanceof PolylineTrace polyTrace)) {
        continue;
      }
      var poly = polyTrace.polyline();
      int consecutiveShort = 0;
      for (int i = 0; i < poly.cornerCount() - 1; i++) {
        Point p1 = poly.corner(i);
        Point p2 = poly.corner(i + 1);
        double len = p1.toFloat().distance(p2.toFloat());
        if (len < 10000.0) { // 1 mm = 10000 dsn units
          consecutiveShort++;
        } else {
          if (consecutiveShort >= 3) {
            shortChainCount++;
            totalShortSegmentsInChains += consecutiveShort;
          }
          consecutiveShort = 0;
        }
      }
      if (consecutiveShort >= 3) {
        shortChainCount++;
        totalShortSegmentsInChains += consecutiveShort;
      }
    }

    System.out.println(
        "R6809 stats: incomplete="
            + new BoardStatistics(job.board).connections.incompleteCount
            + ", shortChains="
            + shortChainCount
            + ", shortSegmentsInChains="
            + totalShortSegmentsInChains);

    // Also inspect net VD5 specifically
    Net vd5Net = job.board.rules.nets.get("VD5", 0);
    int vd5NetNo = vd5Net != null ? vd5Net.netNumber : -1;
    List<Trace> vd5Traces = new ArrayList<>();
    for (Trace trace : job.board.getTraces()) {
      if (trace.containsNet(vd5NetNo) && trace.getLayer() == 0) { // F.Cu
        vd5Traces.add(trace);
      }
    }
    System.out.println("VD5 F.Cu trace count: " + vd5Traces.size());
    for (Trace t : vd5Traces) {
      if (t instanceof PolylineTrace pt) {
        System.out.println(
            "VD5 trace corners: " + pt.polyline().cornerCount() + " corners: " + pt.polyline());
      }
    }
  }
}
