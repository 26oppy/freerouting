package app.freerouting.board.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.geometry.planar.Point;
import app.freerouting.rules.Net;
import app.freerouting.settings.sources.TestingSettings;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TraceCorridorScannerTest extends RoutingFixtureTest {

  @Test
  @DisplayName("Handles null and invalid parameters gracefully")
  void handlesNullAndInvalidParameters() {
    List<TraceCorridorScanner.CandidateCorridor> nullBoard =
        TraceCorridorScanner.scanCorridors(null, null, 1000.0, 200.0, 1000.0);
    assertTrue(nullBoard.isEmpty());
  }

  @Test
  @DisplayName("Scans straight corridors on routed traces and detects clear depth")
  void scansCorridorsOnRoutedBoard() {
    TestingSettings testingSettings = new TestingSettings();
    testingSettings.setMaxPasses(1);
    testingSettings.setMaxItems(2);
    testingSettings.setJobTimeoutString("00:01:00");

    RoutingJob job = getRoutingJob("Issue508-DAC2020_bm01.dsn", testingSettings);
    runRoutingJob(job);

    // Look for a routed net
    Net routedNet = null;
    for (int netNo = 1; netNo <= job.board.rules.nets.maxNetNumber(); netNo++) {
      Net net = job.board.rules.nets.get(netNo);
      if (net != null && net.getTraceLength() > 2000.0) {
        routedNet = net;
        break;
      }
    }

    assertNotNull(routedNet, "Expected at least one net with routed traces");

    // Scan corridors with minimum length 1.0 mm (1000 board units)
    List<TraceCorridorScanner.CandidateCorridor> corridors =
        TraceCorridorScanner.scanCorridors(job.board, routedNet, 500.0, 100.0, 1000.0);

    assertNotNull(corridors);
    if (!corridors.isEmpty()) {
      TraceCorridorScanner.CandidateCorridor corridor = corridors.get(0);
      assertTrue(corridor.length() >= 500.0);
      assertTrue(corridor.maxClearDepth() >= 100.0);
      assertNotNull(corridor.preferredNormal());
      assertTrue(corridor.hasMinimumClearance(100.0));
      assertFalse(corridor.startPoint().equals(corridor.endPoint()));
    }
  }

  @Test
  @DisplayName(
      "Scans corridors on DoorOpener differential pair nets and verifies capacity and proximity ordering")
  void scansCorridorsOnDoorOpenerNets() {
    TestingSettings testingSettings = new TestingSettings();
    testingSettings.setMaxPasses(1);
    testingSettings.setMaxItems(100);
    testingSettings.setJobTimeoutString("00:02:00");

    RoutingJob job = getRoutingJob("DoorOpener.dsn", testingSettings);
    runRoutingJob(job);

    Net mcuPos =
        job.board.rules.nets.get("Net-(U2-GPIO27{slash}FSPICS5{slash}USB_D+)").iterator().next();
    Net mcuNeg =
        job.board.rules.nets.get("Net-(U2-GPIO26{slash}FSPICS4{slash}USB_D-)").iterator().next();

    assertNotNull(mcuPos);
    assertNotNull(mcuNeg);

    List<TraceCorridorScanner.CandidateCorridor> posCorridors =
        TraceCorridorScanner.scanCorridors(job.board, mcuPos, 1000.0, 50.0, 5000.0);
    assertFalse(posCorridors.isEmpty(), "Expected candidate corridors on mcuPos");

    // Verify corridors are sorted by capacity descending
    for (int i = 0; i < posCorridors.size() - 1; i++) {
      assertTrue(
          posCorridors.get(i).capacity() >= posCorridors.get(i + 1).capacity(),
          "Corridors should be ordered by capacity descending");
    }

    // Verify distanceTo and scanCorridorsNearPoint
    Point targetPoint = posCorridors.get(0).startPoint();
    List<TraceCorridorScanner.CandidateCorridor> nearCorridors =
        TraceCorridorScanner.scanCorridorsNearPoint(
            job.board, mcuPos, 1000.0, 50.0, 5000.0, targetPoint);
    assertFalse(nearCorridors.isEmpty());
    assertEquals(0.0, nearCorridors.get(0).distanceTo(targetPoint), 1e-6);

    for (int i = 0; i < nearCorridors.size() - 1; i++) {
      assertTrue(
          nearCorridors.get(i).distanceTo(targetPoint)
              <= nearCorridors.get(i + 1).distanceTo(targetPoint),
          "Near corridors should be sorted by distanceTo target ascending");
    }
  }
}
