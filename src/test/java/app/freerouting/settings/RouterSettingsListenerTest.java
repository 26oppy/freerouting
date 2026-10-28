package app.freerouting.settings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SettingsChangeListener} and {@link SettingsChangeEvent} in {@link
 * RouterSettings}.
 */
class RouterSettingsListenerTest {

  private RouterSettings settings;
  private List<SettingsChangeEvent> events;

  @BeforeEach
  void setUp() {
    settings = new RouterSettings();
    events = new ArrayList<>();
  }

  @Test
  void testMaxPassesFiresEvent() {
    settings.addSettingsChangeListener(events::add);
    settings.setMaxPasses(42);

    assertEquals(1, events.size());
    SettingsChangeEvent event = events.getFirst();
    assertEquals("maxPasses", event.propertyName());
    assertEquals("maxPasses", event.getPropertyName());
    assertNull(event.oldValue());
    assertEquals(42, event.newValue());
    assertEquals(42, event.getNewValue());
  }

  @Test
  void testSetEnabledFiresEvent() {
    settings.addSettingsChangeListener(events::add);
    settings.setEnabled(true);

    assertEquals(1, events.size());
    SettingsChangeEvent event = events.getFirst();
    assertEquals("enabled", event.propertyName());
    assertNull(event.oldValue());
    assertEquals(Boolean.TRUE, event.newValue());
  }

  @Test
  void testSetMaxThreadsFiresEvent() {
    settings.addSettingsChangeListener(events::add);
    settings.setMaxThreads(4);

    assertEquals(1, events.size());
    SettingsChangeEvent event = events.getFirst();
    assertEquals("maxThreads", event.propertyName());
    assertEquals(4, event.newValue());
  }

  @Test
  void testSetJobTimeoutStringFiresEvent() {
    settings.addSettingsChangeListener(events::add);
    settings.setJobTimeoutString("00:05:00");

    assertEquals(1, events.size());
    SettingsChangeEvent event = events.getFirst();
    assertEquals("jobTimeoutString", event.propertyName());
    assertEquals("00:05:00", event.newValue());
  }

  @Test
  void testSetViasAllowedFiresEvent() {
    settings.addSettingsChangeListener(events::add);
    settings.setViasAllowed(Boolean.FALSE);

    assertEquals(1, events.size());
    SettingsChangeEvent event = events.getFirst();
    assertEquals("viasAllowed", event.propertyName());
    assertEquals(Boolean.FALSE, event.newValue());
  }

  @Test
  void testSetAlgorithmFiresEvent() {
    settings.addSettingsChangeListener(events::add);
    settings.setAlgorithm("freerouting-router");

    assertEquals(1, events.size());
    SettingsChangeEvent event = events.getFirst();
    assertEquals("algorithm", event.propertyName());
    assertEquals("freerouting-router", event.newValue());
  }

  @Test
  void testSetOptimizerEnabledFiresEvent() {
    settings.addSettingsChangeListener(events::add);
    settings.setOptimizerEnabled(true);

    assertEquals(1, events.size());
    SettingsChangeEvent event = events.getFirst();
    assertEquals("optimizer.enabled", event.propertyName());
    assertEquals(Boolean.TRUE, event.newValue());
  }

  @Test
  void testSetFanoutEnabledFiresEvent() {
    settings.addSettingsChangeListener(events::add);
    settings.setFanoutEnabled(true);

    assertEquals(1, events.size());
    SettingsChangeEvent event = events.getFirst();
    assertEquals("fanout.enabled", event.propertyName());
    assertEquals(Boolean.TRUE, event.newValue());
  }

  @Test
  void testSetPlaneNetsFiresEvent() {
    settings.addSettingsChangeListener(events::add);
    String[] nets = new String[] {"GND", "VCC"};
    settings.setPlaneNets(nets);

    assertEquals(1, events.size());
    SettingsChangeEvent event = events.getFirst();
    assertEquals("planeNets", event.propertyName());
    assertTrue(event.newValue() instanceof String[]);
  }

  @Test
  void testSetPlaneAsObstacleFiresEvent() {
    settings.addSettingsChangeListener(events::add);
    settings.setPlaneAsObstacle(true);

    assertEquals(1, events.size());
    SettingsChangeEvent event = events.getFirst();
    assertEquals("planeAsObstacle", event.propertyName());
    assertEquals(Boolean.TRUE, event.newValue());
  }

  @Test
  void testRemoveSettingsChangeListenerStopsReceivingEvents() {
    SettingsChangeListener listener = events::add;
    settings.addSettingsChangeListener(listener);
    settings.setMaxPasses(10);
    assertEquals(1, events.size());

    settings.removeSettingsChangeListener(listener);
    settings.setMaxPasses(20);
    assertEquals(1, events.size());
  }

  @Test
  void testCompatibilityAliasAddPropertyChangeListener() {
    SettingsChangeListener listener = events::add;
    settings.addPropertyChangeListener(listener);
    settings.setMaxPasses(15);
    assertEquals(1, events.size());

    settings.removePropertyChangeListener(listener);
    settings.setMaxPasses(30);
    assertEquals(1, events.size());
  }

  @Test
  void testNullListenerHandlingDoesNotThrow() {
    assertDoesNotThrow(() -> settings.addSettingsChangeListener(null));
    assertDoesNotThrow(() -> settings.removeSettingsChangeListener(null));
    assertDoesNotThrow(() -> settings.addPropertyChangeListener(null));
    assertDoesNotThrow(() -> settings.removePropertyChangeListener(null));
  }

  @Test
  void testApplyNewValuesFromFiresSynchronizationEvents() {
    settings.addSettingsChangeListener(events::add);

    RouterSettings source = new RouterSettings();
    source.setMaxPasses(50);
    source.setMaxThreads(2);
    source.setJobTimeoutString("00:10:00");
    source.setEnabled(true);
    source.setOptimizerEnabled(true);
    source.setFanoutEnabled(true);

    events.clear();
    settings.applyNewValuesFrom(source);

    // Verifies the 6 key sync properties were fired
    List<String> propertyNames = events.stream().map(SettingsChangeEvent::propertyName).toList();
    assertTrue(propertyNames.contains("maxPasses"));
    assertTrue(propertyNames.contains("maxThreads"));
    assertTrue(propertyNames.contains("jobTimeoutString"));
    assertTrue(propertyNames.contains("enabled"));
    assertTrue(propertyNames.contains("optimizer.enabled"));
    assertTrue(propertyNames.contains("fanout.enabled"));
  }
}
