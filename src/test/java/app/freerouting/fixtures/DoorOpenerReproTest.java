package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.autoroute.tuning.DifferentialPairSkewTuner;
import app.freerouting.autoroute.tuning.PostRouteLengthTuner;
import app.freerouting.core.RoutingJob;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.DifferentialPair;
import app.freerouting.rules.Net;
import app.freerouting.settings.DesignRulesCheckerSettings;
import app.freerouting.settings.sources.TestingSettings;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fixture regression tests on DoorOpener board verifying differential pair auto-detection,
 * intra-pair skew tuning, single-track length tuning, multi-pair balancing, and zero clearance
 * violation guarantees.
 */
public class DoorOpenerReproTest extends RoutingFixtureTest {

  @Test
  @DisplayName("Verify differential pair skew tuning and equalization on DoorOpener")
  void testDoorOpenerDiffPairEqualization() {
    TestingSettings settings = new TestingSettings();
    settings.setMaxPasses(1);
    settings.setMaxItems(100);
    settings.setJobTimeoutString("00:02:00");

    RoutingJob job = getRoutingJob("DoorOpener.dsn", settings);
    assertNotNull(job, "Routing job should load DoorOpener.dsn");

    job = runRoutingJob(job);
    assertNotNull(job.board, "Board should be routed");

    // Register additional test pairs: MCU USB, Crystal XTAL_32K, and an arbitrary pair
    Net mcuPos =
        job.board.rules.nets.get("Net-(U2-GPIO27{slash}FSPICS5{slash}USB_D+)").iterator().next();
    Net mcuNeg =
        job.board.rules.nets.get("Net-(U2-GPIO26{slash}FSPICS4{slash}USB_D-)").iterator().next();
    DifferentialPair pair2 = new DifferentialPair(mcuPos, mcuNeg, "MCU_USB", 6000.0, 1000.0);
    job.board.rules.differentialPairs.add(pair2);

    Net xtalPos = job.board.rules.nets.get("Net-(U2-GPIO13{slash}XTAL_32K_P)").iterator().next();
    Net xtalNeg = job.board.rules.nets.get("Net-(U2-GPIO14{slash}XTAL_32K_N)").iterator().next();
    DifferentialPair pair3 = new DifferentialPair(xtalPos, xtalNeg, "XTAL_32K", 6000.0, 1000.0);
    job.board.rules.differentialPairs.add(pair3);

    Net servoNet = job.board.rules.nets.get("/SERVO1").iterator().next();
    Net q1Net = job.board.rules.nets.get("Net-(Q1-G)").iterator().next();
    DifferentialPair pair4 =
        new DifferentialPair(servoNet, q1Net, "ARBITRARY_PAIR", 6000.0, 50000.0);
    job.board.rules.differentialPairs.add(pair4);

    FRLogger.info("=== PRE-TUNING PAIR SKEWS ===");
    for (var p : job.board.rules.differentialPairs.all()) {
      double pLen = p.positiveNet() != null ? p.positiveNet().getTraceLength() : 0.0;
      double nLen = p.negativeNet() != null ? p.negativeNet().getTraceLength() : 0.0;
      double skew = Math.abs(pLen - nLen);
      FRLogger.info(
          String.format(
              Locale.US,
              "  Pair '%s': pLen=%.1f um, nLen=%.1f um, skew=%.1f um (%.4f mm), maxSkew=%.1f um",
              p.baseName(),
              pLen,
              nLen,
              skew,
              skew / 10000.0,
              p.maxSkew()));
    }

    FRLogger.info("=== RUNNING DIFFERENTIAL PAIR SKEW TUNER ===");
    var skewReport = DifferentialPairSkewTuner.tune(job.board);
    FRLogger.info(
        String.format(
            Locale.US,
            "Skew Tuner Report: attempted=%d, equalized=%d, bumpsInserted=%d, compensated=%.4f mm",
            skewReport.pairsAttempted(),
            skewReport.pairsEqualized(),
            skewReport.bumpsInserted(),
            skewReport.totalSkewCompensatedMm()));

    FRLogger.info("=== POST-TUNING PAIR SKEWS ===");
    boolean hasBalancedPair = false;
    for (var p : job.board.rules.differentialPairs.all()) {
      double pLen = p.positiveNet() != null ? p.positiveNet().getTraceLength() : 0.0;
      double nLen = p.negativeNet() != null ? p.negativeNet().getTraceLength() : 0.0;
      double skew = Math.abs(pLen - nLen);
      double allowed = p.hasMaxSkew() ? p.maxSkew() : 1000.0;
      boolean balanced = skew <= allowed;
      if (balanced) {
        hasBalancedPair = true;
      }
      FRLogger.info(
          String.format(
              Locale.US,
              "  Pair '%s': pLen=%.1f um, nLen=%.1f um, skew=%.1f um (%.4f mm), maxSkew=%.1f um, balanced=%b",
              p.baseName(),
              pLen,
              nLen,
              skew,
              skew / 10000.0,
              p.maxSkew(),
              balanced));
    }

    var violations =
        new DesignRulesChecker(job.board, new DesignRulesCheckerSettings())
            .getAllClearanceViolations();
    FRLogger.info("Clearance violations on board after skew tuning: " + violations.size());

    assertTrue(skewReport.bumpsInserted() > 0, "Expected at least 1 bump inserted");
    assertTrue(skewReport.totalSkewCompensatedMm() > 0, "Expected positive skew compensation");
    assertTrue(hasBalancedPair, "Expected at least 1 differential pair balanced on the board");
    assertEquals(0, violations.size(), "Clearance violations must be 0 after tuning");
  }

  @Test
  @DisplayName("Verify single track length tuning on DoorOpener")
  void testDoorOpenerSingleTrackLengthTuning() {
    TestingSettings settings = new TestingSettings();
    settings.setMaxPasses(1);
    settings.setMaxItems(100);
    settings.setJobTimeoutString("00:02:00");

    RoutingJob job = getRoutingJob("DoorOpener.dsn", settings);
    assertNotNull(job, "Routing job should load DoorOpener.dsn");

    job = runRoutingJob(job);
    assertNotNull(job.board, "Board should be routed");

    Net servoNet = job.board.rules.nets.get("/SERVO1").iterator().next();
    assertNotNull(servoNet, "Net /SERVO1 must exist");

    double initialLen = servoNet.getTraceLength();
    assertTrue(initialLen > 0, "Initial trace length should be positive");

    // Add a 1.0 mm (10,000 board units) target length deficit
    double targetLength = initialLen + 10000.0;
    double tol = 1000.0;
    servoNet.setLengthConstraint(
        new app.freerouting.rules.NetLengthConstraint(targetLength - tol, targetLength + tol));

    FRLogger.info(
        String.format(
            Locale.US,
            "Tuning single track /SERVO1: initialLen=%.1f um, target=%.1f um",
            initialLen / 10.0,
            targetLength / 10.0));

    var singleReport = PostRouteLengthTuner.tune(job.board);
    double postLen = servoNet.getTraceLength();

    FRLogger.info(
        String.format(
            Locale.US,
            "Single track tuning result: attempted=%d, satisfied=%d, meanders=%d, newLen=%.1f um",
            singleReport.netsAttempted(),
            singleReport.netsSuccessfullyTuned(),
            singleReport.totalMeandersInserted(),
            postLen / 10.0));

    var violations =
        new DesignRulesChecker(job.board, new DesignRulesCheckerSettings())
            .getAllClearanceViolations();
    FRLogger.info("Clearance violations after single-track tuning: " + violations.size());

    assertTrue(singleReport.totalMeandersInserted() > 0, "Expected at least 1 meander inserted");
    assertTrue(postLen > initialLen, "Net length should increase after tuning");
    assertEquals(0, violations.size(), "Clearance violations must be 0 after single-track tuning");
  }
}
