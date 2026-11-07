package app.freerouting.autoroute.decoupling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.io.specctra.DsnTestFixtures;
import app.freerouting.settings.DecouplingLockMode;
import app.freerouting.settings.DecouplingSettings;
import app.freerouting.settings.RouterSettings;
import app.freerouting.settings.sources.DefaultSettings;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class BatchDecouplingRouterTest {

  @Test
  void testDisabledReturnsNull() throws IOException {
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue508-DAC2020_bm01.dsn");
    RouterSettings settings = new DefaultSettings().getSettings();
    assertFalse(settings.isDecouplingEnabled());

    DecouplingRoutingSummary summary = BatchDecouplingRouter.run(board, settings, null);
    assertNull(summary);
  }

  @Test
  void testGoldenFixtureDecouplingPreRouting() throws IOException {
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue508-DAC2020_bm01.dsn");
    int baselineDrc = new DesignRulesChecker(board, null).getAllClearanceViolations().size();

    RouterSettings settings = new DefaultSettings().getSettings();
    settings.decoupling = new DecouplingSettings();
    settings.decoupling.enabled = true;
    settings.decoupling.lockMode = DecouplingLockMode.HARD;
    settings.decoupling.maxStrapLengthMm = 4.0;
    settings.decoupling.maxOwnerRadiusMm = 8.0;

    DecouplingRoutingSummary summary = BatchDecouplingRouter.run(board, settings, null);

    assertNotNull(summary);
    assertTrue(summary.candidatesTotal() > 0, "Expected decoupling candidates on DAC2020_bm01");
    assertNotNull(summary.metrics());
    assertEquals(summary.candidatesTotal(), summary.metrics().size());

    // Gate: No new clearance violations vs baseline
    int postDrc = new DesignRulesChecker(board, null).getAllClearanceViolations().size();
    assertTrue(
        postDrc <= baselineDrc,
        "Decoupling routing must not introduce new clearance violations (baseline="
            + baselineDrc
            + ", post="
            + postDrc
            + ")");

    // Verify protection flags on items
    for (Item item : board.getItems()) {
      if (item.isDecouplingProtected()) {
        if (!item.getFixedState().isPreservedRoute()) {
          assertEquals(FixedState.SYSTEM_FIXED, item.getFixedState());
        }
      }
    }

    // Verify per-cap metrics
    for (DecouplingMetrics m : summary.metrics()) {
      assertTrue(m.componentId() > 0);
      assertNotNull(m.componentName());
      assertNotNull(m.ownerName());
      assertTrue(m.strapLengthMm() >= 0.0);
      assertTrue(m.viaCount() >= 0);
      assertTrue(m.groundViaDistanceMm() >= 0.0);
    }
  }

  @Test
  void testAlreadyConnectedSkip() throws IOException {
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue508-DAC2020_bm01.dsn");
    RouterSettings settings = new DefaultSettings().getSettings();
    settings.decoupling = new DecouplingSettings();
    settings.decoupling.enabled = true;

    // Run first time
    DecouplingRoutingSummary firstRun = BatchDecouplingRouter.run(board, settings, null);
    assertNotNull(firstRun);

    // Run second time on same board: all previously routed or connected candidates should now be
    // alreadyConnected
    DecouplingRoutingSummary secondRun = BatchDecouplingRouter.run(board, settings, null);
    assertNotNull(secondRun);
    assertEquals(
        0, secondRun.strapsRouted(), "Second run should not re-route already connected straps");
    assertTrue(
        secondRun.alreadyConnected() >= firstRun.strapsRouted() + firstRun.alreadyConnected());
  }

  @Test
  void testFanoutSkipsDecouplingPins() throws IOException {
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue508-DAC2020_bm01.dsn");
    RouterSettings settings = new DefaultSettings().getSettings();
    settings.decoupling = new DecouplingSettings();
    settings.decoupling.enabled = true;

    DecouplingRoutingSummary summary = BatchDecouplingRouter.run(board, settings, null);
    assertNotNull(summary);
    assertTrue(summary.strapsRouted() > 0);

    java.util.Set<Item> protectedItems = new java.util.HashSet<>();
    for (Item it : board.getItems()) {
      if (it.isDecouplingProtected()) {
        protectedItems.add(it);
      }
    }
    assertFalse(protectedItems.isEmpty());

    app.freerouting.autoroute.pipeline.BatchFanout.fanoutBoard(board, settings, null);

    for (Item it : protectedItems) {
      if (it instanceof app.freerouting.board.model.items.Pin pin) {
        assertTrue(pin.isDecouplingProtected());
      }
    }
  }

  @Test
  void testInspectDecouplingTracesOnMultipleBoards() throws IOException {
    String[] testBoards = {
      "Issue508-DAC2020_bm01.dsn",
      "Issue508-DAC2020_bm02.dsn",
      "Issue508-DAC2020_bm04.dsn",
      "Issue145-smoothieboard.dsn",
      "Issue269-caniot-tiny-arm.dsn",
      "Issue367-Charger.dsn",
      "Issue957-smoke-ldo-led.dsn",
      "Issue978-esp32c3.dsn"
    };

    for (String boardName : testBoards) {
      RoutingBoard board = DsnTestFixtures.loadBoard(boardName);
      int baselineDrc = new DesignRulesChecker(board, null).getAllClearanceViolations().size();

      RouterSettings settings = new DefaultSettings().getSettings();
      settings.decoupling = new DecouplingSettings();
      settings.decoupling.enabled = true;
      settings.decoupling.lockMode = DecouplingLockMode.HARD;
      settings.decoupling.maxStrapLengthMm = 5.0;
      settings.decoupling.maxOwnerRadiusMm = 10.0;
      settings.decoupling.includeReviewCandidates = true;

      DecouplingRoutingSummary summary = BatchDecouplingRouter.run(board, settings, null);
      assertNotNull(summary, "Summary should not be null for " + boardName);

      int postDrc = new DesignRulesChecker(board, null).getAllClearanceViolations().size();
      System.out.printf(
          "[%s] candidates=%d, routed=%d, alreadyConnected=%d, vias=%d, failed=%d, len=%.2fmm,"
              + " drcBaseline=%d, drcPost=%d%n",
          boardName,
          summary.candidatesTotal(),
          summary.strapsRouted(),
          summary.alreadyConnected(),
          summary.viasPlaced(),
          summary.failed(),
          summary.totalStrapLengthMm(),
          baselineDrc,
          postDrc);

      java.util.Map<String, Long> statusCounts =
          summary.metrics().stream()
              .collect(
                  java.util.stream.Collectors.groupingBy(
                      DecouplingMetrics::status, java.util.stream.Collectors.counting()));
      System.out.printf("   [%s] Status Breakdown: %s%n", boardName, statusCounts);

      assertTrue(
          postDrc <= baselineDrc,
          "Board "
              + boardName
              + " introduced new DRC violations! (baseline="
              + baselineDrc
              + ", post="
              + postDrc
              + ")");

      // Inspect every protected trace
      int protectedTraceCount = 0;
      for (Item item : board.getItems()) {
        if (item.isDecouplingProtected()
            && item instanceof app.freerouting.board.trace.PolylineTrace trace) {
          protectedTraceCount++;
          // Verify geometric validity: trace must have non-zero length and valid corners
          assertTrue(trace.getLength() > 0, "Decoupling trace must have positive length: " + trace);
          app.freerouting.geometry.planar.Polyline poly = trace.polyline();
          assertNotNull(poly);
          assertTrue(poly.cornerCount() >= 2, "Trace must have at least 2 corners: " + trace);
          System.out.printf(
              "   [Trace #%d] net=%d, layer=%d, length=%.2fum, corners=%d%n",
              trace.getId(),
              trace.getNetNumber(0),
              trace.getLayer(),
              trace.getLength(),
              poly.cornerCount());
          for (int c = 0; c < poly.cornerCount(); c++) {
            app.freerouting.geometry.planar.Point pt = poly.corner(c);
            assertNotNull(pt);
            assertFalse(Double.isNaN(pt.toFloat().x));
            assertFalse(Double.isNaN(pt.toFloat().y));
          }
        }
      }
      System.out.printf("   [%s] total protected traces: %d%n", boardName, protectedTraceCount);
    }
  }

  @Test
  void testEndToEndAutoroutingWithDecoupling() throws IOException {
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue508-DAC2020_bm01.dsn");
    int baselineDrc = new DesignRulesChecker(board, null).getAllClearanceViolations().size();
    assertEquals(0, baselineDrc);

    app.freerouting.core.RoutingJob job = new app.freerouting.core.RoutingJob();
    job.board = board;
    job.thread =
        new app.freerouting.core.StoppableThread() {
          @Override
          protected void threadAction() {}
        };

    RouterSettings settings = new DefaultSettings().getSettings();
    settings.setLayerCount(board.getLayerCount());
    settings.applyBoardSpecificOptimizations(board);
    settings.autorouter.maxPasses = 1;
    settings.autorouter.maxItems = 10;
    settings.decoupling = new DecouplingSettings();
    settings.decoupling.enabled = true;
    settings.decoupling.lockMode = DecouplingLockMode.HARD;
    job.routerSettings = settings;

    app.freerouting.autoroute.pipeline.BatchAutorouter router =
        new app.freerouting.autoroute.pipeline.BatchAutorouter(job);
    router.runBatchLoop();

    // Verify DRC remains completely clean after decoupling + fanout + autorouting pass
    int postDrc = new DesignRulesChecker(board, null).getAllClearanceViolations().size();
    assertEquals(
        0, postDrc, "End-to-end autorouting with decoupling must introduce 0 clearance violations");

    // Verify protected items still exist and are protected
    long protectedCount = board.getItems().stream().filter(Item::isDecouplingProtected).count();
    assertTrue(protectedCount > 0, "Decoupling protected items must remain on board");
  }

  @Test
  void testComparativeEvaluationWithAndWithoutDecoupling() throws IOException {
    String[] testBoards = {
      "Issue508-DAC2020_bm01.dsn", "Issue508-DAC2020_bm04.dsn", "Issue978-esp32c3.dsn"
    };

    for (String boardName : testBoards) {
      System.out.println("======================================================================");
      System.out.println("BOARD: " + boardName);
      System.out.println("======================================================================");

      // 1. Diagnostic candidate breakdown
      RoutingBoard diagBoard = DsnTestFixtures.loadBoard(boardName);
      RouterSettings diagSettings = new DefaultSettings().getSettings();
      diagSettings.decoupling = new DecouplingSettings();
      diagSettings.decoupling.enabled = true;
      diagSettings.decoupling.lockMode = DecouplingLockMode.HARD;
      diagSettings.decoupling.maxStrapLengthMm = 5.0;
      diagSettings.decoupling.maxOwnerRadiusMm = 10.0;
      diagSettings.decoupling.includeReviewCandidates = true;

      DecouplingRoutingSummary diagSummary =
          BatchDecouplingRouter.run(diagBoard, diagSettings, null);
      assertNotNull(diagSummary);

      System.out.printf(
          "Decoupling Pre-Pass Diagnostic Summary: %d total candidates, %d routed, %d already connected, %d failed%n",
          diagSummary.candidatesTotal(),
          diagSummary.strapsRouted(),
          diagSummary.alreadyConnected(),
          diagSummary.failed());

      System.out.println("--- Per-Candidate Breakdown & Outcome Reasons ---");
      for (DecouplingMetrics m : diagSummary.metrics()) {
        System.out.printf(
            "   Cap %s -> Owner %s | Status: %s | Strap: %.2f mm | Vias: %d | GroundViaDist: %.2f mm%n",
            m.componentName(),
            m.ownerName(),
            m.status(),
            m.strapLengthMm(),
            m.viaCount(),
            m.groundViaDistanceMm());
      }

      // 2. RUN A: WITHOUT DECOUPLING ROUTER (Baseline)
      RoutingBoard boardWithout = DsnTestFixtures.loadBoard(boardName);
      app.freerouting.core.RoutingJob jobWithout = new app.freerouting.core.RoutingJob();
      jobWithout.board = boardWithout;
      jobWithout.thread =
          new app.freerouting.core.StoppableThread() {
            @Override
            protected void threadAction() {}
          };
      RouterSettings settingsWithout = new DefaultSettings().getSettings();
      settingsWithout.setLayerCount(boardWithout.getLayerCount());
      settingsWithout.applyBoardSpecificOptimizations(boardWithout);
      settingsWithout.autorouter.maxPasses = 1;
      settingsWithout.autorouter.maxItems = 15;
      settingsWithout.decoupling = new DecouplingSettings();
      settingsWithout.decoupling.enabled = false;
      jobWithout.routerSettings = settingsWithout;

      app.freerouting.autoroute.pipeline.BatchAutorouter routerWithout =
          new app.freerouting.autoroute.pipeline.BatchAutorouter(jobWithout);
      routerWithout.runBatchLoop();

      long viasWithout =
          boardWithout.getItems().stream()
              .filter(it -> it instanceof app.freerouting.board.model.items.Via)
              .count();
      double traceLenWithout =
          boardWithout.getItems().stream()
              .filter(it -> it instanceof app.freerouting.board.model.items.Trace)
              .mapToDouble(it -> ((app.freerouting.board.model.items.Trace) it).getLength())
              .sum();
      int drcWithout =
          new DesignRulesChecker(boardWithout, null).getAllClearanceViolations().size();

      // 3. RUN B: WITH DECOUPLING ROUTER
      RoutingBoard boardWith = DsnTestFixtures.loadBoard(boardName);
      app.freerouting.core.RoutingJob jobWith = new app.freerouting.core.RoutingJob();
      jobWith.board = boardWith;
      jobWith.thread =
          new app.freerouting.core.StoppableThread() {
            @Override
            protected void threadAction() {}
          };
      RouterSettings settingsWith = new DefaultSettings().getSettings();
      settingsWith.setLayerCount(boardWith.getLayerCount());
      settingsWith.applyBoardSpecificOptimizations(boardWith);
      settingsWith.autorouter.maxPasses = 1;
      settingsWith.autorouter.maxItems = 15;
      settingsWith.decoupling = new DecouplingSettings();
      settingsWith.decoupling.enabled = true;
      settingsWith.decoupling.lockMode = DecouplingLockMode.HARD;
      settingsWith.decoupling.maxStrapLengthMm = 5.0;
      settingsWith.decoupling.maxOwnerRadiusMm = 10.0;
      settingsWith.decoupling.includeReviewCandidates = true;
      jobWith.routerSettings = settingsWith;

      app.freerouting.autoroute.pipeline.BatchAutorouter routerWith =
          new app.freerouting.autoroute.pipeline.BatchAutorouter(jobWith);
      routerWith.runBatchLoop();

      long viasWith =
          boardWith.getItems().stream()
              .filter(it -> it instanceof app.freerouting.board.model.items.Via)
              .count();
      double traceLenWith =
          boardWith.getItems().stream()
              .filter(it -> it instanceof app.freerouting.board.model.items.Trace)
              .mapToDouble(it -> ((app.freerouting.board.model.items.Trace) it).getLength())
              .sum();
      int drcWith = new DesignRulesChecker(boardWith, null).getAllClearanceViolations().size();
      long decouplingProtectedItems =
          boardWith.getItems().stream().filter(Item::isDecouplingProtected).count();

      // 4. COMPARISON SUMMARY
      System.out.println("--- Comparative Analysis: WITHOUT Decoupling vs WITH Decoupling ---");
      System.out.printf(
          "   Without Decoupling: Vias = %d, Total Trace Length = %.2f mm, DRC Violations = %d%n",
          viasWithout, traceLenWithout / 10000.0, drcWithout);
      System.out.printf(
          "   With Decoupling:    Vias = %d, Total Trace Length = %.2f mm, DRC Violations = %d, Protected Items = %d%n",
          viasWith, traceLenWith / 10000.0, drcWith, decouplingProtectedItems);
      System.out.printf(
          "   Delta: Vias diff = %+d, Trace length diff = %+.2f mm, DRC diff = %+d%n",
          (viasWith - viasWithout),
          (traceLenWith - traceLenWithout) / 10000.0,
          (drcWith - drcWithout));

      assertTrue(
          drcWith <= drcWithout,
          "DRC with decoupling must be clean / no new violations vs without");
    }
  }

  @Test
  void testDecouplingEdgeCases() throws IOException {
    // 1. Thread cancellation edge case: when stop requested, pre-pass terminates early
    RoutingBoard board = DsnTestFixtures.loadBoard("Issue508-DAC2020_bm01.dsn");
    RouterSettings settings = new DefaultSettings().getSettings();
    settings.decoupling = new DecouplingSettings();
    settings.decoupling.enabled = true;
    app.freerouting.core.StoppableThread stoppedThread =
        new app.freerouting.core.StoppableThread() {
          @Override
          protected void threadAction() {}
        };
    stoppedThread.requestStop();

    DecouplingRoutingSummary stoppedSummary =
        BatchDecouplingRouter.run(board, settings, stoppedThread);
    assertNotNull(stoppedSummary);
    assertEquals(0, stoppedSummary.strapsRouted(), "Should route 0 straps when thread is stopped");

    // 2. Layer disabled edge case: when layer 0 is disabled, pre-pass skips with LAYER_DISABLED
    RoutingBoard boardLayerDisabled = DsnTestFixtures.loadBoard("Issue508-DAC2020_bm01.dsn");
    RouterSettings settingsLayerDisabled = new DefaultSettings().getSettings();
    settingsLayerDisabled.setLayerCount(boardLayerDisabled.getLayerCount());
    settingsLayerDisabled.applyBoardSpecificOptimizations(boardLayerDisabled);
    settingsLayerDisabled.setLayerActive(0, false);
    settingsLayerDisabled.decoupling = new DecouplingSettings();
    settingsLayerDisabled.decoupling.enabled = true;

    DecouplingRoutingSummary layerDisabledSummary =
        BatchDecouplingRouter.run(boardLayerDisabled, settingsLayerDisabled, null);
    assertNotNull(layerDisabledSummary);
    assertEquals(0, layerDisabledSummary.strapsRouted());
    assertTrue(
        layerDisabledSummary.metrics().stream().anyMatch(m -> "LAYER_DISABLED".equals(m.status())),
        "Expected LAYER_DISABLED status when surface layer is inactive");
  }
}
