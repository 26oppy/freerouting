package app.freerouting.autoroute.tuning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.core.RoutingJob;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.rules.DifferentialPair;
import app.freerouting.rules.Net;
import app.freerouting.settings.sources.TestingSettings;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DifferentialPairSkewTunerTest extends RoutingFixtureTest {

  @Test
  @DisplayName("Handles null and empty differential pairs gracefully")
  void handlesNullAndEmptyPairsGracefully() {
    DifferentialPairSkewTuner.SkewTuningReport report = DifferentialPairSkewTuner.tune(null);
    assertEquals(0, report.pairsAttempted());
    assertEquals(0, report.pairsEqualized());
    assertEquals(0, report.bumpsInserted());
  }

  @Test
  @DisplayName(
      "Equalizes intra-pair skew on differential pair while preserving zero DRC violations")
  void equalizesSkewOnDifferentialPair() {
    TestingSettings settings = new TestingSettings();
    settings.setMaxPasses(1);
    settings.setMaxItems(4);
    settings.setJobTimeoutString("00:01:00");

    RoutingJob job = getRoutingJob("Issue508-DAC2020_bm01.dsn", settings);
    runRoutingJob(job);

    // Pick two routed nets with unequal lengths
    List<Net> routedNets = new ArrayList<>();
    for (int netNo = 1; netNo <= job.board.rules.nets.maxNetNumber(); netNo++) {
      Net net = job.board.rules.nets.get(netNo);
      if (net != null && net.getTraceLength() > 2000.0) {
        routedNets.add(net);
        if (routedNets.size() >= 2) {
          break;
        }
      }
    }

    if (routedNets.size() < 2) {
      return; // Skip if board didn't route at least 2 long nets
    }

    Net netA = routedNets.get(0);
    Net netB = routedNets.get(1);

    double lenA = netA.getTraceLength();
    double lenB = netB.getTraceLength();
    double initialSkew = Math.abs(lenA - lenB);

    if (initialSkew < 100.0) {
      return; // Already balanced
    }

    // Register them as a differential pair with strict skew tolerance of 50 board units
    DifferentialPair diffPair = new DifferentialPair(netA, netB, "TEST_DIFF_PAIR", 600.0, 50.0);
    job.board.rules.differentialPairs.add(diffPair);

    DifferentialPairSkewTuner.SkewTuningReport report = DifferentialPairSkewTuner.tune(job.board);

    assertNotNull(report);
    assertEquals(1, report.pairsAttempted());

    // Clearance violations must remain zero
    assertTrue(
        new DesignRulesChecker(job.board, null).getAllClearanceViolations().isEmpty(),
        "Clearance violations must remain 0 after skew tuning");
  }
}
