package app.freerouting.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.settings.sources.CliSettings;
import app.freerouting.settings.sources.DefaultSettings;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class DecouplingSettingsTest {

  @Test
  void testNullabilityInvariant() throws IllegalAccessException {
    DecouplingSettings settings = new DecouplingSettings();
    for (Field field : DecouplingSettings.class.getDeclaredFields()) {
      if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      field.setAccessible(true);
      assertNull(
          field.get(settings),
          "Field '"
              + field.getName()
              + "' in DecouplingSettings must be null by default to preserve merger priority.");
    }
  }

  @Test
  void testDefaultSettingsValues() {
    DefaultSettings defaultsSource = new DefaultSettings();
    RouterSettings defaults = defaultsSource.getSettings();

    assertNotNull(defaults.decoupling);
    assertFalse(
        defaults.decoupling.enabled, "Decoupling must be disabled by default for non-regression");
    assertFalse(defaults.isDecouplingEnabled());
    assertEquals(DecouplingLockMode.HARD, defaults.decoupling.lockMode);
    assertEquals(8.0, defaults.decoupling.maxOwnerRadiusMm);
    assertEquals(4.0, defaults.decoupling.maxStrapLengthMm);
    assertFalse(defaults.decoupling.includeReviewCandidates);
  }

  @Test
  void testCliArgumentsParsing() {
    String[] args = {
      "--router.decoupling.enabled=true",
      "--router.decoupling.lock_mode=NONE",
      "--router.decoupling.max_strap_length_mm=2.5",
      "--router.decoupling.max_owner_radius_mm=5.0",
      "--router.decoupling.include_review_candidates=true"
    };

    CliSettings cli = new CliSettings(args);
    RouterSettings cliSettings = cli.getSettings();

    assertNotNull(cliSettings.decoupling);
    assertTrue(cliSettings.decoupling.enabled);
    assertEquals(DecouplingLockMode.NONE, cliSettings.decoupling.lockMode);
    assertEquals(2.5, cliSettings.decoupling.maxStrapLengthMm);
    assertEquals(5.0, cliSettings.decoupling.maxOwnerRadiusMm);
    assertTrue(cliSettings.decoupling.includeReviewCandidates);
  }

  @Test
  void testSettingsMergerPrecedence() {
    DefaultSettings defaultsSource = new DefaultSettings();
    RouterSettings base = defaultsSource.getSettings();
    assertFalse(base.isDecouplingEnabled());

    RouterSettings override = new RouterSettings();
    override.decoupling = new DecouplingSettings();
    override.decoupling.enabled = true;
    override.decoupling.maxStrapLengthMm = 3.2;

    base.applyNewValuesFrom(override);

    assertTrue(base.isDecouplingEnabled());
    assertEquals(3.2, base.decoupling.maxStrapLengthMm);
    // Unset fields in override remain defaults
    assertEquals(DecouplingLockMode.HARD, base.decoupling.lockMode);
    assertEquals(8.0, base.decoupling.maxOwnerRadiusMm);
  }

  @Test
  void testRouterSettingsClone() {
    RouterSettings original = new RouterSettings();
    original.decoupling = new DecouplingSettings();
    original.decoupling.enabled = true;
    original.decoupling.lockMode = DecouplingLockMode.HARD;
    original.decoupling.maxStrapLengthMm = 3.5;

    RouterSettings cloned = original.clone();

    assertNotNull(cloned.decoupling);
    assertNotSame(original.decoupling, cloned.decoupling);
    assertEquals(original.decoupling.enabled, cloned.decoupling.enabled);
    assertEquals(original.decoupling.lockMode, cloned.decoupling.lockMode);
    assertEquals(original.decoupling.maxStrapLengthMm, cloned.decoupling.maxStrapLengthMm);

    cloned.decoupling.enabled = false;
    assertTrue(original.decoupling.enabled);
  }
}
