package app.freerouting.settings;

import java.io.Serializable;

/**
 * Event delivered to {@link SettingsChangeListener} when a property on {@link RouterSettings}
 * changes.
 *
 * @param propertyName name of the changed property
 * @param oldValue previous value of the property, or null
 * @param newValue new value of the property, or null
 */
public record SettingsChangeEvent(String propertyName, Object oldValue, Object newValue)
    implements Serializable {

  /** Returns the name of the property that changed. */
  public String getPropertyName() {
    return propertyName;
  }

  /** Returns the old value of the property, or null. */
  public Object getOldValue() {
    return oldValue;
  }

  /** Returns the new value of the property, or null. */
  public Object getNewValue() {
    return newValue;
  }
}
