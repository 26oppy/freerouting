package app.freerouting.settings;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

/**
 * Configuration for the decoupling capacitor pre-routing pass.
 *
 * <p>All fields follow the {@link RouterSettings} nullable-field contract: a {@code null} value
 * means "this source has no opinion" and is skipped by {@code SettingsMerger}. Hardcoded defaults
 * live exclusively in {@link app.freerouting.settings.sources.DefaultSettings}.
 */
public class DecouplingSettings implements Serializable, Cloneable {

  /**
   * Whether to run the decoupling capacitor pre-routing pass. Defaults to {@code false} in {@link
   * app.freerouting.settings.sources.DefaultSettings} to preserve non-regression invariants.
   */
  @SerializedName("enabled")
  public Boolean enabled;

  /** Locking mode applied to routed decoupling straps and vias. */
  @SerializedName("lock_mode")
  public DecouplingLockMode lockMode;

  /**
   * Maximum Euclidean search radius in millimetres between a decoupling capacitor and an IC pin.
   */
  @SerializedName("max_owner_radius_mm")
  public Double maxOwnerRadiusMm;

  /** Maximum allowed surface strap length in millimetres between capacitor pad and IC pin. */
  @SerializedName("max_strap_length_mm")
  public Double maxStrapLengthMm;

  /** Whether to route review-grade candidates in addition to confident decoupling candidates. */
  @SerializedName("include_review_candidates")
  public Boolean includeReviewCandidates;

  /** No-arg constructor required for deserialisation and {@link #clone()}. */
  public DecouplingSettings() {}

  /**
   * Creates a deep copy of this {@code DecouplingSettings} object. All fields are immutable
   * wrappers or enums, so a shallow clone is sufficient.
   */
  @Override
  public DecouplingSettings clone() {
    try {
      return (DecouplingSettings) super.clone();
    } catch (CloneNotSupportedException e) {
      throw new AssertionError("Clone not supported", e);
    }
  }
}
