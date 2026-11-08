package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.Freerouting;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Trace;
import app.freerouting.board.model.items.Via;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.core.RoutingJob;
import app.freerouting.drc.ClearanceViolation;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.logger.FRLogger;
import app.freerouting.settings.DecouplingLockMode;
import app.freerouting.settings.sources.TestingSettings;
import java.util.Collection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Validates end-to-end integration of the decoupling pre-pass with the autorouter pipeline, fanout
 * guard, and hybrid lock lifecycle.
 */
class DecouplingPipelineIntegrationTest extends RoutingFixtureTest {

  @BeforeEach
  @Override
  protected void setUp() {
    super.setUp();
    System.setProperty("freerouting.logging.console.level", "INFO");
    FRLogger.granularTraceEnabled = false;
    Freerouting.globalSettings.debugSettings.enableDetailedLogging = false;
  }

  @Test
  void testDecouplingPipelineSoftLockMode() {
    TestingSettings testSettings = new TestingSettings();
    testSettings.setDecouplingEnabled(true);
    testSettings.setDecouplingLockMode(DecouplingLockMode.SOFT);
    testSettings.setFanoutEnabled(true);
    testSettings.setMaxPasses(1);
    testSettings.setMaxItems(5);
    testSettings.setJobTimeoutString("00:01:00");

    RoutingJob job = getRoutingJob("Issue508-DAC2020_bm01.dsn", testSettings);
    assertNotNull(job);
    assertTrue(job.routerSettings.isDecouplingEnabled());
    assertEquals(DecouplingLockMode.SOFT, job.routerSettings.decoupling.lockMode);

    runRoutingJob(job);

    assertNotNull(job.board);
    DesignRulesChecker drc = new DesignRulesChecker(job.board, null);
    Collection<ClearanceViolation> violations = drc.getAllClearanceViolations();
    assertTrue(
        violations.isEmpty(), "Expected 0 clearance violations after soft lock decoupling run");

    // Verify soft-unlocked items transitioned from SYSTEM_FIXED to UNFIXED during routing
    for (Item item : job.board.getItems()) {
      if (item.isDecouplingProtected()) {
        assertFalse(
            item.getFixedState() == FixedState.SYSTEM_FIXED,
            "Decoupling item should have transitioned from SYSTEM_FIXED to UNFIXED under SOFT lock mode");
      }
    }
  }

  @Test
  void testDecouplingPipelineHardLockMode() {
    TestingSettings testSettings = new TestingSettings();
    testSettings.setDecouplingEnabled(true);
    testSettings.setDecouplingLockMode(DecouplingLockMode.HARD);
    testSettings.setFanoutEnabled(true);
    testSettings.setMaxPasses(1);
    testSettings.setMaxItems(5);
    testSettings.setJobTimeoutString("00:01:00");

    RoutingJob job = getRoutingJob("Issue508-DAC2020_bm01.dsn", testSettings);
    assertNotNull(job);
    assertTrue(job.routerSettings.isDecouplingEnabled());
    assertEquals(DecouplingLockMode.HARD, job.routerSettings.decoupling.lockMode);

    runRoutingJob(job);

    assertNotNull(job.board);
    DesignRulesChecker drc = new DesignRulesChecker(job.board, null);
    Collection<ClearanceViolation> violations = drc.getAllClearanceViolations();
    assertTrue(
        violations.isEmpty(), "Expected 0 clearance violations after hard lock decoupling run");

    // In HARD lock mode, decoupling trace and via items must remain SYSTEM_FIXED
    boolean foundDecouplingCopper = false;
    for (Item item : job.board.getItems()) {
      if ((item instanceof Trace || item instanceof Via) && item.isDecouplingProtected()) {
        foundDecouplingCopper = true;
        assertEquals(
            FixedState.SYSTEM_FIXED,
            item.getFixedState(),
            "Decoupling trace/via item must retain SYSTEM_FIXED in HARD lock mode");
      }
    }
    assertTrue(foundDecouplingCopper, "Expected decoupling copper items on board");
  }

  @Test
  void testDecouplingPipelineWithOptimizer() {
    TestingSettings testSettings = new TestingSettings();
    testSettings.setDecouplingEnabled(true);
    testSettings.setDecouplingLockMode(DecouplingLockMode.SOFT);
    testSettings.setFanoutEnabled(true);
    testSettings.setOptimizerEnabled(true);
    testSettings.setOptimizerMaxPasses(1);
    testSettings.setOptimizerMaxItems(5);
    testSettings.setMaxPasses(1);
    testSettings.setMaxItems(5);
    testSettings.setJobTimeoutString("00:01:00");

    RoutingJob job = getRoutingJob("Issue508-DAC2020_bm01.dsn", testSettings);
    assertNotNull(job);
    assertTrue(job.routerSettings.isDecouplingEnabled());

    runRoutingJob(job);

    assertNotNull(job.board);
    DesignRulesChecker drc = new DesignRulesChecker(job.board, null);
    Collection<ClearanceViolation> violations = drc.getAllClearanceViolations();
    assertTrue(
        violations.isEmpty(), "Expected 0 clearance violations after optimization with decoupling");

    // Decoupling items should have been preserved and restored to UNFIXED after optimizer completes
    boolean hasDecouplingItems = false;
    for (Item item : job.board.getItems()) {
      if (item.isDecouplingProtected()) {
        hasDecouplingItems = true;
        assertEquals(
            FixedState.UNFIXED,
            item.getFixedState(),
            "Decoupling item should be restored to UNFIXED post-optimizer in SOFT lock mode");
      }
    }
    assertTrue(hasDecouplingItems, "Expected decoupling items to exist on the board");
  }

  @Test
  void testDecouplingPipelineNoneLockMode() {
    TestingSettings testSettings = new TestingSettings();
    testSettings.setDecouplingEnabled(true);
    testSettings.setDecouplingLockMode(DecouplingLockMode.NONE);
    testSettings.setFanoutEnabled(true);
    testSettings.setOptimizerEnabled(true);
    testSettings.setOptimizerMaxPasses(1);
    testSettings.setOptimizerMaxItems(5);
    testSettings.setMaxPasses(1);
    testSettings.setMaxItems(5);
    testSettings.setJobTimeoutString("00:01:00");

    RoutingJob job = getRoutingJob("Issue508-DAC2020_bm01.dsn", testSettings);
    assertNotNull(job);
    assertTrue(job.routerSettings.isDecouplingEnabled());
    assertEquals(DecouplingLockMode.NONE, job.routerSettings.decoupling.lockMode);

    runRoutingJob(job);

    assertNotNull(job.board);
    DesignRulesChecker drc = new DesignRulesChecker(job.board, null);
    Collection<ClearanceViolation> violations = drc.getAllClearanceViolations();
    assertTrue(violations.isEmpty(), "Expected 0 clearance violations in NONE lock mode");

    // In NONE lock mode, decoupling items must not be locked to SYSTEM_FIXED
    for (Item item : job.board.getItems()) {
      if (item.isDecouplingProtected()) {
        assertFalse(
            item.getFixedState() == FixedState.SYSTEM_FIXED,
            "Decoupling item must never be locked to SYSTEM_FIXED in NONE lock mode");
      }
    }
  }

  @Test
  void testFilteredIslandItemFlagLifecycle() {
    TestingSettings testSettings = new TestingSettings();
    testSettings.setDecouplingEnabled(true);
    testSettings.setDecouplingLockMode(DecouplingLockMode.SOFT);
    testSettings.setMaxPasses(1);
    testSettings.setMaxItems(2);
    testSettings.setJobTimeoutString("00:01:00");

    RoutingJob job = getRoutingJob("Issue508-DAC2020_bm01.dsn", testSettings);
    assertNotNull(job);
    runRoutingJob(job);
    assertNotNull(job.board);

    // Pick an item from the board, test flag lifecycle directly
    Item firstItem = job.board.getItems().iterator().next();
    assertNotNull(firstItem);
    assertFalse(firstItem.isFilteredIslandNonFeeder());

    firstItem.setFilteredIslandNonFeeder(true);
    assertTrue(firstItem.isFilteredIslandNonFeeder());

    firstItem.clearFilteredIslandNonFeeder();
    assertFalse(firstItem.isFilteredIslandNonFeeder());
  }
}
