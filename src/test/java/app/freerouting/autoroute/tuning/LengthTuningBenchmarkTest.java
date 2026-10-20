package app.freerouting.autoroute.tuning;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.core.RoutingJob;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.gui.windows.routing.DiffPairTableModel;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.DifferentialPair;
import app.freerouting.rules.Net;
import app.freerouting.rules.NetLengthConstraint;
import app.freerouting.settings.sources.TestingSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end benchmark and verification test comparing trace lengths and differential pair skew
 * before and after post-route length tuning.
 */
class LengthTuningBenchmarkTest extends RoutingFixtureTest {

  @Test
  @DisplayName(
      "Benchmark post-route length tuning and diff-pair skew equalization on routed fixture")
  void benchmarkLengthAndSkewTuning() {
    TestingSettings settings = new TestingSettings();
    settings.setMaxPasses(1);
    settings.setMaxItems(50);
    settings.setJobTimeoutString("00:01:00");

    RoutingJob job = getRoutingJob("Issue508-DAC2020_bm01.dsn", settings);
    job = runRoutingJob(job);

    assertNotNull(job.board, "Board must be loaded and routed");

    // 1. Auto-detect differential pairs via DiffPairTableModel
    DiffPairTableModel diffModel = new DiffPairTableModel();
    int detected = diffModel.autoDetectPairs(job.board);
    FRLogger.info(String.format(Locale.US, "Auto-detected pairs count: %d", detected));

    // 2. Identify routed nets for bus length matching and diff pair skew testing
    List<Net> routedNets = new ArrayList<>();
    for (int i = 1; i <= job.board.rules.nets.maxNetNumber(); i++) {
      Net n = job.board.rules.nets.get(i);
      if (n != null && n.getTraceLength() > 5000.0) {
        routedNets.add(n);
      }
    }

    assertTrue(routedNets.size() >= 2, "Expected at least 2 routed nets for benchmark");

    Net net1 = routedNets.get(0);
    Net net2 = routedNets.get(1);

    // Register a pair between two routed traces with a max skew tolerance of 0.05 mm
    DifferentialPair testRoutedPair = new DifferentialPair(net1, net2, "PAIR_SIGNAL", 600.0, 500.0);
    job.board.rules.differentialPairs.add(testRoutedPair);

    double beforeLen1 = net1.getTraceLength();
    double beforeLen2 = net2.getTraceLength();

    // Set target length 10% higher on net 1
    double targetLen1 = beforeLen1 * 1.10;
    net1.setLengthConstraint(new NetLengthConstraint(targetLen1 - 500.0, targetLen1 + 500.0));

    // Measure differential pairs before tuning
    List<DifferentialPair> pairs = new ArrayList<>(job.board.rules.differentialPairs.all());
    double[] beforeSkew = new double[pairs.size()];
    for (int p = 0; p < pairs.size(); p++) {
      DifferentialPair pair = pairs.get(p);
      double pLen = pair.positiveNet() != null ? pair.positiveNet().getTraceLength() : 0.0;
      double nLen = pair.negativeNet() != null ? pair.negativeNet().getTraceLength() : 0.0;
      beforeSkew[p] = Math.abs(pLen - nLen);
    }

    // 3. Execute Skew Tuning
    DifferentialPairSkewTuner.SkewTuningReport skewReport =
        DifferentialPairSkewTuner.tune(job.board);

    // 4. Execute Length Tuning
    PostRouteLengthTuner.TuningReport lengthReport = PostRouteLengthTuner.tune(job.board);

    double afterLen1 = net1.getTraceLength();
    double afterLen2 = net2.getTraceLength();

    // 5. Measure differential pairs after tuning
    double[] afterSkew = new double[pairs.size()];
    for (int p = 0; p < pairs.size(); p++) {
      DifferentialPair pair = pairs.get(p);
      double pLen = pair.positiveNet() != null ? pair.positiveNet().getTraceLength() : 0.0;
      double nLen = pair.negativeNet() != null ? pair.negativeNet().getTraceLength() : 0.0;
      afterSkew[p] = Math.abs(pLen - nLen);
    }

    // Print benchmark comparison summary
    FRLogger.info("=================================================================");
    FRLogger.info("POST-ROUTE LENGTH TUNING BENCHMARK REPORT");
    FRLogger.info("=================================================================");
    FRLogger.info(
        String.format(
            Locale.US,
            "Net '%s': Before = %.2f mm, Target = %.2f mm, After = %.2f mm (Delta = +%.2f mm)",
            net1.name,
            beforeLen1 / 10000.0,
            targetLen1 / 10000.0,
            afterLen1 / 10000.0,
            (afterLen1 - beforeLen1) / 10000.0));
    FRLogger.info(
        String.format(
            Locale.US,
            "Net '%s': Before = %.2f mm, After = %.2f mm",
            net2.name,
            beforeLen2 / 10000.0,
            afterLen2 / 10000.0));

    for (int p = 0; p < pairs.size(); p++) {
      DifferentialPair pair = pairs.get(p);
      double pLen = pair.positiveNet() != null ? pair.positiveNet().getTraceLength() : 0.0;
      double nLen = pair.negativeNet() != null ? pair.negativeNet().getTraceLength() : 0.0;
      FRLogger.info(
          String.format(
              Locale.US,
              "Diff Pair '%s': Pos '%s' Len = %.2f mm, Neg '%s' Len = %.2f mm, Before Skew = %.4f mm, After Skew = %.4f mm, Allowed Max = %.4f mm",
              pair.baseName(),
              pair.positiveNet().name,
              pLen / 10000.0,
              pair.negativeNet().name,
              nLen / 10000.0,
              beforeSkew[p] / 10000.0,
              afterSkew[p] / 10000.0,
              pair.maxSkew() / 10000.0));
    }

    FRLogger.info(
        String.format(
            Locale.US,
            "Tuning stats: %d meanders inserted, %d skew bumps inserted, +%.2f mm total length added",
            lengthReport.totalMeandersInserted(),
            skewReport.bumpsInserted(),
            lengthReport.totalLengthAddedMm()));
    FRLogger.info("=================================================================");

    // Assert that net length grew towards target without regressions
    assertTrue(afterLen1 >= beforeLen1, "Net length after tuning must be >= initial routed length");

    // Assert zero DRC clearance violations
    var violations = new DesignRulesChecker(job.board, null).getAllClearanceViolations();
    assertTrue(violations.isEmpty(), "Zero DRC clearance violations expected after tuning");
  }
}
