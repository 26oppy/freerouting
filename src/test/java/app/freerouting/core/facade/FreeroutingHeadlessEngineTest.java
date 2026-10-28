package app.freerouting.core.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.drc.ClearanceViolation;
import app.freerouting.io.specctra.DsnTestFixtures;
import app.freerouting.settings.RouterSettings;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Comprehensive test suite for {@link FreeroutingHeadlessEngine}. */
class FreeroutingHeadlessEngineTest {

  private static final String FIXTURE = "Issue508-DAC2020_bm01.dsn";

  @Test
  void testSuccessfulRoutingInMemory() throws IOException {
    byte[] dsnBytes;
    try (var is = DsnTestFixtures.openResource(FIXTURE)) {
      dsnBytes = is.readAllBytes();
    }

    RouterSettings overrides = new RouterSettings();
    overrides.autorouter.maxPasses = 1;
    overrides.autorouter.maxItems = 10;
    overrides.setRunOptimizer(false);

    List<HeadlessRoutingProgress> progressEvents = new ArrayList<>();

    HeadlessRoutingRequest request =
        HeadlessRoutingRequest.builder()
            .dsnBytes(dsnBytes)
            .designName(FIXTURE)
            .settings(overrides)
            .runDrc(true)
            .computeSes(true)
            .progressListener(progressEvents::add)
            .build();

    HeadlessRoutingResult result = FreeroutingHeadlessEngine.route(request);

    assertTrue(result.isSuccess(), "Routing should report success");
    assertFalse(result.isCancelled(), "Routing should not be cancelled");
    assertNotNull(result.board(), "Board should be populated");
    assertNotNull(result.statistics(), "Statistics should be populated");
    assertTrue(result.sesBytes().length > 0, "SES bytes should be non-empty");
    assertTrue(result.sesContent().contains("(session"), "SES content should have session header");
    assertTrue(result.executionTimeMillis() >= 0, "Execution time should be non-negative");
    assertFalse(progressEvents.isEmpty(), "Progress listener should receive events");
  }

  @Test
  void testCancellationBeforeStart() throws IOException {
    byte[] dsnBytes;
    try (var is = DsnTestFixtures.openResource(FIXTURE)) {
      dsnBytes = is.readAllBytes();
    }

    HeadlessRoutingRequest request =
        HeadlessRoutingRequest.builder().dsnBytes(dsnBytes).cancellationToken(() -> true).build();

    HeadlessRoutingResult result = FreeroutingHeadlessEngine.route(request);

    assertTrue(result.isCancelled(), "Result should be cancelled");
    assertFalse(result.isCompleted(), "Result should not be completed");
    assertFalse(result.isSuccess(), "Result should not report success");
  }

  @Test
  void testCancellationDuringExecution() throws IOException {
    byte[] dsnBytes;
    try (var is = DsnTestFixtures.openResource(FIXTURE)) {
      dsnBytes = is.readAllBytes();
    }

    RouterSettings overrides = new RouterSettings();
    overrides.autorouter.maxPasses = 10;
    overrides.setRunOptimizer(false);

    AtomicBoolean stopRequested = new AtomicBoolean(false);

    HeadlessRoutingRequest request =
        HeadlessRoutingRequest.builder()
            .dsnBytes(dsnBytes)
            .settings(overrides)
            .cancellationToken(stopRequested::get)
            .progressListener(
                progress -> {
                  // Cancel after the first update
                  stopRequested.set(true);
                })
            .build();

    HeadlessRoutingResult result = FreeroutingHeadlessEngine.route(request);

    assertTrue(result.isCancelled(), "Result should be marked cancelled");
    assertNotNull(result.board(), "Partial board should be preserved");
  }

  @Test
  void testInvalidDsnReturnsFailureResult() {
    byte[] invalidDsn = "invalid non-dsn text".getBytes(StandardCharsets.UTF_8);

    HeadlessRoutingRequest request = HeadlessRoutingRequest.of(invalidDsn);
    HeadlessRoutingResult result = FreeroutingHeadlessEngine.route(request);

    assertFalse(result.isSuccess(), "Invalid DSN should not succeed");
    assertFalse(result.isCancelled(), "Should not be marked cancelled");
    assertNotNull(result.errorMessage(), "Error message should describe the failure");
  }

  @Test
  void testValidationGuards() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FreeroutingHeadlessEngine.route((HeadlessRoutingRequest) null),
        "Null request must be rejected");

    assertThrows(
        IllegalArgumentException.class,
        () -> HeadlessRoutingRequest.builder().dsnBytes(null).build(),
        "Null dsnBytes must be rejected");

    assertThrows(
        IllegalArgumentException.class,
        () -> HeadlessRoutingRequest.builder().dsnBytes(new byte[0]).build(),
        "Empty dsnBytes must be rejected");
  }

  @Test
  void testConvenienceOverloads() throws IOException {
    byte[] dsnBytes;
    try (var is = DsnTestFixtures.openResource(FIXTURE)) {
      dsnBytes = is.readAllBytes();
    }

    // Direct DRC check from DSN bytes
    Collection<ClearanceViolation> violations = FreeroutingHeadlessEngine.checkDrc(dsnBytes);
    assertNotNull(violations, "DRC violations should not be null");

    // Route from InputStream
    HeadlessRoutingRequest streamRequest =
        HeadlessRoutingRequest.of(new ByteArrayInputStream(dsnBytes));
    assertEquals("design.dsn", streamRequest.designName());
    assertEquals(dsnBytes.length, streamRequest.dsnBytes().length);
  }
}
