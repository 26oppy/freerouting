package app.freerouting.settings;

import com.google.gson.annotations.SerializedName;

/** Strategy for determining the trace width of direct surface decoupling capacitor links. */
public enum TraceWidthMode {
  /** Uses the net class default trace width defined in design rules. */
  @SerializedName("class_width")
  CLASS_WIDTH,

  /** Uses the board neck width setting for fine-pitch pad entries. */
  @SerializedName("neck_width")
  NECK_WIDTH,

  /** Uses an explicit custom trace width in micrometers. */
  @SerializedName("custom_um")
  CUSTOM_UM;

  /**
   * Parses a string representation into a {@link TraceWidthMode}, supporting both snake_case and
   * kebab-case variants.
   *
   * @param value the string value to parse
   * @return the resolved {@link TraceWidthMode}, or {@code null} if null or empty
   */
  public static TraceWidthMode fromString(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    String normalized = value.trim().replace('-', '_');
    for (TraceWidthMode mode : values()) {
      if (mode.name().equalsIgnoreCase(normalized)) {
        return mode;
      }
    }
    throw new IllegalArgumentException("Unknown TraceWidthMode: " + value);
  }
}
