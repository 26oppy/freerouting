package app.freerouting.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.autoroute.decoupling.IdentifierConfig;
import app.freerouting.settings.sources.CliSettings;
import app.freerouting.settings.sources.DefaultSettings;
import org.junit.jupiter.api.Test;

class DecouplingSettingsTest {

  @Test
  void testDefaultValuesInDefaultSettings() {
    DefaultSettings defaultSettingsSource = new DefaultSettings();
    RouterSettings defaults = defaultSettingsSource.getSettings();

    assertNotNull(defaults.decoupling, "DecouplingSettings should be non-null in DefaultSettings");
    assertFalse(
        defaults.decoupling.enabled, "Decoupling should be disabled by default for safe rollout");
    assertFalse(defaults.decoupling.only, "Decoupling-only mode should be false by default");
    assertEquals(15.0, defaults.decoupling.maxRadiusMm, 1e-6);
    assertEquals(65, defaults.decoupling.minScoreThreshold);
    assertEquals(TraceWidthMode.CLASS_WIDTH, defaults.decoupling.traceWidthMode);
    assertEquals(2.0, defaults.decoupling.maxDetourRatio, 1e-6);
    assertFalse(
        defaults.decoupling.lockTraces, "lockTraces should be false by default (unfixed output)");
  }

  @Test
  void testNullableContractInRouterSettings() {
    RouterSettings blank = new RouterSettings();
    assertNotNull(blank.decoupling);
    assertNull(blank.decoupling.enabled, "Unconfigured RouterSettings should have null enabled");
    assertNull(blank.decoupling.only, "Unconfigured RouterSettings should have null only");
    assertNull(blank.decoupling.maxRadiusMm);
    assertNull(blank.decoupling.minScoreThreshold);
    assertNull(blank.decoupling.traceWidthMode);
    assertNull(blank.decoupling.traceWidthUm);
    assertNull(blank.decoupling.maxDetourRatio);
    assertNull(blank.decoupling.lockTraces);
  }

  @Test
  void testClone() {
    RouterSettings settings = new RouterSettings();
    settings.decoupling.enabled = true;
    settings.decoupling.only = true;
    settings.decoupling.maxRadiusMm = 22.5;
    settings.decoupling.minScoreThreshold = 80;
    settings.decoupling.traceWidthMode = TraceWidthMode.NECK_WIDTH;
    settings.decoupling.traceWidthUm = 300.0;
    settings.decoupling.maxDetourRatio = 1.8;
    settings.decoupling.lockTraces = true;

    RouterSettings clone = settings.clone();
    assertNotNull(clone.decoupling);
    assertTrue(clone.decoupling.enabled);
    assertTrue(clone.decoupling.only);
    assertEquals(22.5, clone.decoupling.maxRadiusMm, 1e-6);
    assertEquals(80, clone.decoupling.minScoreThreshold);
    assertEquals(TraceWidthMode.NECK_WIDTH, clone.decoupling.traceWidthMode);
    assertEquals(300.0, clone.decoupling.traceWidthUm, 1e-6);
    assertEquals(1.8, clone.decoupling.maxDetourRatio, 1e-6);
    assertTrue(clone.decoupling.lockTraces);
  }

  @Test
  void testSettingsMergerPrecedence() {
    SettingsMerger merger = new SettingsMerger();
    merger.addOrReplaceSources(new DefaultSettings());

    RouterSettings initial = merger.merge();
    assertFalse(initial.decoupling.enabled);
    assertEquals(15.0, initial.decoupling.maxRadiusMm, 1e-6);

    // Higher-priority CLI override
    String[] cliArgs = {
      "--router.decoupling.enabled=true", "--router.decoupling.max_radius_mm=25.0"
    };
    merger.addOrReplaceSources(new CliSettings(cliArgs));

    RouterSettings merged = merger.merge();
    assertTrue(merged.decoupling.enabled, "CLI should override default enabled=false");
    assertEquals(25.0, merged.decoupling.maxRadiusMm, 1e-6, "CLI should override default radius");
    assertEquals(
        65, merged.decoupling.minScoreThreshold, "Unspecified field should retain default value");
  }

  @Test
  void testCliParsingOptions() {
    String[] args = {
      "--router.decoupling.enabled=true",
      "--router.decoupling.only=true",
      "--router.decoupling.max_radius_mm=18.5",
      "--router.decoupling.min_score_threshold=72",
      "--router.decoupling.trace_width_mode=neck_width",
      "--router.decoupling.trace_width_um=250.0",
      "--router.decoupling.max_detour_ratio=1.75",
      "--router.decoupling.lock_traces=true"
    };

    CliSettings cliSettings = new CliSettings(args);
    RouterSettings parsed = cliSettings.getSettings();

    assertNotNull(parsed.decoupling);
    assertTrue(parsed.decoupling.enabled);
    assertTrue(parsed.decoupling.only);
    assertEquals(18.5, parsed.decoupling.maxRadiusMm, 1e-6);
    assertEquals(72, parsed.decoupling.minScoreThreshold);
    assertEquals(TraceWidthMode.NECK_WIDTH, parsed.decoupling.traceWidthMode);
    assertEquals(250.0, parsed.decoupling.traceWidthUm, 1e-6);
    assertEquals(1.75, parsed.decoupling.maxDetourRatio, 1e-6);
    assertTrue(parsed.decoupling.lockTraces);
  }

  @Test
  void testStandaloneDecouplingOnlyFlag() {
    String[] args = {"--decoupling-only"};
    CliSettings cliSettings = new CliSettings(args);
    RouterSettings parsed = cliSettings.getSettings();

    assertNotNull(parsed.decoupling);
    assertTrue(parsed.decoupling.only);
  }

  @Test
  void testValidationPrecedenceAndContradiction() {
    RouterSettings settings = new RouterSettings();
    settings.decoupling.only = true;
    settings.decoupling.enabled = false;

    // Contradiction: only=true and enabled=false
    assertThrows(IllegalArgumentException.class, settings::validate);

    // Valid: only=true implies enabled=true
    RouterSettings validSettings = new RouterSettings();
    validSettings.decoupling.only = true;
    validSettings.decoupling.enabled = null;
    validSettings.validate();
    assertTrue(validSettings.decoupling.enabled);

    // Negative radius clamped
    RouterSettings clampedRadius = new RouterSettings();
    clampedRadius.decoupling.maxRadiusMm = -5.0;
    clampedRadius.validate();
    assertEquals(15.0, clampedRadius.decoupling.maxRadiusMm, 1e-6);

    // Detour ratio < 1.0 clamped
    RouterSettings clampedDetour = new RouterSettings();
    clampedDetour.decoupling.maxDetourRatio = 0.5;
    clampedDetour.validate();
    assertEquals(2.0, clampedDetour.decoupling.maxDetourRatio, 1e-6);

    // Score threshold out of range clamped
    RouterSettings clampedScore = new RouterSettings();
    clampedScore.decoupling.minScoreThreshold = 150;
    clampedScore.validate();
    assertEquals(100, clampedScore.decoupling.minScoreThreshold);
  }

  @Test
  void testTraceWidthModeFromString() {
    assertEquals(TraceWidthMode.CLASS_WIDTH, TraceWidthMode.fromString("class_width"));
    assertEquals(TraceWidthMode.CLASS_WIDTH, TraceWidthMode.fromString("class-width"));
    assertEquals(TraceWidthMode.CLASS_WIDTH, TraceWidthMode.fromString("CLASS_WIDTH"));
    assertEquals(TraceWidthMode.NECK_WIDTH, TraceWidthMode.fromString("neck_width"));
    assertEquals(TraceWidthMode.CUSTOM_UM, TraceWidthMode.fromString("custom_um"));
    assertNull(TraceWidthMode.fromString(null));
    assertNull(TraceWidthMode.fromString("   "));
    assertThrows(IllegalArgumentException.class, () -> TraceWidthMode.fromString("invalid_mode"));
  }

  @Test
  void testIdentifierConfigFromSettings() {
    RouterSettings settings = new RouterSettings();
    settings.decoupling.maxRadiusMm = 20.0;
    settings.decoupling.minScoreThreshold = 75;

    IdentifierConfig config = IdentifierConfig.fromSettings(settings);
    assertEquals(20.0, config.maxOwnerRadiusMm(), 1e-6);
    assertEquals(75, config.decouplingThreshold());

    IdentifierConfig defaultConfig = IdentifierConfig.fromSettings(null);
    assertEquals(15.0, defaultConfig.maxOwnerRadiusMm(), 1e-6);
    assertEquals(60, defaultConfig.decouplingThreshold());
  }
}
