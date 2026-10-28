package app.freerouting.settings;

import java.util.EventListener;

/**
 * Listener interface for receiving property change events from {@link RouterSettings}.
 *
 * <p>This interface replaces {@code java.beans.PropertyChangeListener} in the core settings domain,
 * ensuring zero dependencies on {@code java.desktop} for headless, embedded, and WebAssembly
 * targets.
 */
@FunctionalInterface
public interface SettingsChangeListener extends EventListener {

  /**
   * Invoked when a router setting is changed.
   *
   * @param event details of the setting change
   */
  void settingChanged(SettingsChangeEvent event);
}
