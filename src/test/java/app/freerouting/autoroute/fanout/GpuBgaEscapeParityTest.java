package app.freerouting.autoroute.fanout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.freerouting.core.RoutingJob;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.settings.sources.TestingSettings;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** End-to-end parity and DRC verification test for GPU BGA escape routing. */
class GpuBgaEscapeParityTest extends RoutingFixtureTest {

  @Test
  void testSmokeBgaFixtureZeroDrcClearanceViolations() {
    TestingSettings settings = new TestingSettings();
    settings.setMaxPasses(1);
    settings.setMaxItems(50);

    RoutingJob job = getRoutingJob("Issue508-DAC2020_bm01.dsn", settings);
    assertNotNull(job);
    job = runRoutingJob(job);
    assertNotNull(job.board);

    // Verify full board DRC produces 0 clearance violations
    List<?> violations =
        new java.util.ArrayList<>(
            new DesignRulesChecker(job.board, null).getAllClearanceViolations());
    assertEquals(0, violations.size(), "Board must have 0 DRC clearance violations");
  }

  @Test
  @Tag("slow")
  void testExtendedBgaParityDeterminism() {
    TestingSettings settings = new TestingSettings();
    settings.setMaxPasses(1);
    settings.setMaxItems(50);

    RoutingJob job1 = runRoutingJob(getRoutingJob("Issue508-DAC2020_bm01.dsn", settings));
    String hash1 = job1.board.getHash();

    RoutingJob job2 = runRoutingJob(getRoutingJob("Issue508-DAC2020_bm01.dsn", settings));
    String hash2 = job2.board.getHash();

    assertEquals(hash1, hash2, "Repeated BGA fanout runs must produce identical board state");
  }
}
