package app.freerouting.settings;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

/**
 * Configuration for the decoupling capacitor routing stage.
 *
 * <p>All fields follow the {@link RouterSettings} nullable-field contract: a {@code null} value
 * means "this source has no opinion" and is skipped by {@code SettingsMerger}. Hardcoded defaults
 * live exclusively in {@link app.freerouting.settings.sources.DefaultSettings}.
 */
public class DecouplingSettings implements Serializable, Cloneable {

  /**
   * Whether decoupling capacitor routing is enabled. When {@code false}, the router skips this
   * stage and proceeds directly to fanout and general auto-routing.
   */
  @SerializedName("enabled")
  public Boolean enabled;

  /**
   * Whether to run in decoupling-only mode. When {@code true}, only the decoupling capacitor
   * routing stage is executed, and the router completes immediately without executing fanout,
   * autoroute, or optimizer passes.
   */
  @SerializedName("only")
  public Boolean only;

  /** Maximum search radius (in millimeters) from a capacitor pad to candidate IC supply pins. */
  @SerializedName("max_radius_mm")
  public Double maxRadiusMm;

  /** Confidence score threshold (0 to 100) at or above which a capacitor candidate is routed. */
  @SerializedName("min_score_threshold")
  public Integer minScoreThreshold;

  /** Strategy for determining trace width when routing the direct surface decoupling link. */
  @SerializedName("trace_width_mode")
  public TraceWidthMode traceWidthMode;

  /**
   * Explicit custom trace width in micrometers, used when {@code traceWidthMode ==
   * TraceWidthMode.CUSTOM_UM}.
   */
  @SerializedName("trace_width_um")
  public Double traceWidthUm;

  /**
   * Maximum allowed ratio of actual routed trace length to straight-line Euclidean distance (detour
   * limit). Connections exceeding this ratio are rejected and rolled back to prevent snaking
   * traces.
   */
  @SerializedName("max_detour_ratio")
  public Double maxDetourRatio;

  /**
   * Whether to lock ({@code FixedState.SYSTEM_FIXED}) committed decoupling copper. When {@code
   * false} (default), traces remain editable ({@code FixedState.UNFIXED}) and export cleanly as
   * plain tracks in Specctra SES files.
   */
  @SerializedName("lock_traces")
  public Boolean lockTraces;

  @Override
  public DecouplingSettings clone() {
    try {
      return (DecouplingSettings) super.clone();
    } catch (CloneNotSupportedException e) {
      throw new AssertionError("Clone not supported on Cloneable class", e);
    }
  }
}
