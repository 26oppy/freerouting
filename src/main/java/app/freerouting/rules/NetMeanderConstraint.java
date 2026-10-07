package app.freerouting.rules;

import java.io.Serializable;

/**
 * Defines the geometric pattern constraints for trace length tuning (meandering).
 *
 * <p>Unlike primitive-based records, this class uses object wrappers ({@link Double}, {@link
 * Boolean}, {@link Integer}) so that {@code null} explicitly represents an "unspecified" property
 * that should inherit its value from a parent fallback (e.g., from a NetClass or BoardRule).
 *
 * @param maxAmplitude maximum excursion height (0 = prohibited, &lt; 0 = unspecified)
 * @param minAmplitude minimum excursion height (&lt; 0 = unspecified)
 * @param gap spacing between adjacent folds (&lt; 0 = Specctra 3W fallback)
 * @param singleSided true if meanders should only grow on one side of the baseline
 * @param cornerStyle corner geometry style (auto, 45-degree chamfer, rounded fillet).
 * @param cornerRadiusPercentage corner radius percentage for rounded or mitered turns (0-100%).
 */
public record NetMeanderConstraint(
    Double maxAmplitude,
    Double minAmplitude,
    Double gap,
    Boolean singleSided,
    CornerStyle cornerStyle,
    Integer cornerRadiusPercentage)
    implements Serializable {

  /** Corner geometry style for meander fold crests and turns. */
  public enum CornerStyle {
    AUTO,
    CHAMFER,
    FILLET;

    /** Parses the corner style from a DSN identifier. */
    public static CornerStyle parse(String name) {
      if (name == null) return AUTO;
      return switch (name.toLowerCase()) {
        case "chamfer", "miter" -> CHAMFER;
        case "fillet", "round" -> FILLET;
        default -> AUTO;
      };
    }

    /** Returns standard DSN keyword representation. */
    public String toDsn() {
      return switch (this) {
        case CHAMFER -> "chamfer";
        case FILLET -> "fillet";
        case AUTO -> "auto";
      };
    }
  }

  /**
   * Merges this constraint with a fallback constraint (e.g., from a NetClass or BoardRules). Any
   * property in this constraint that is null will inherit the value from the fallback.
   *
   * @param fallback the parent constraint to inherit from
   * @return a new fully or partially resolved constraint
   */
  public NetMeanderConstraint mergeWith(NetMeanderConstraint fallback) {
    if (fallback == null) {
      return this;
    }
    return new NetMeanderConstraint(
        this.maxAmplitude != null ? this.maxAmplitude : fallback.maxAmplitude,
        this.minAmplitude != null ? this.minAmplitude : fallback.minAmplitude,
        this.gap != null ? this.gap : fallback.gap,
        this.singleSided != null ? this.singleSided : fallback.singleSided,
        this.cornerStyle != null ? this.cornerStyle : fallback.cornerStyle,
        this.cornerRadiusPercentage != null
            ? this.cornerRadiusPercentage
            : fallback.cornerRadiusPercentage);
  }

  /**
   * Resolves the effective meander gap (spacing). If unspecified, applies the standard Specctra
   * fallback rule: max(3 * W, W + C).
   *
   * @param traceWidth current trace half-width or full width (depending on context)
   * @param clearance current clearance rule
   * @return the resolved gap in coordinate units
   */
  public double resolveEffectiveGap(double traceWidth, double clearance) {
    if (this.gap != null && this.gap > 0) {
      return this.gap;
    }
    return Math.max(3.0 * traceWidth, traceWidth + clearance);
  }

  /**
   * Resolves the effective minimum amplitude. If unspecified, applies the standard Specctra
   * fallback rule: max(3 * W, W + C).
   *
   * @param traceWidth current trace width
   * @param clearance current clearance
   * @return the resolved minimum amplitude
   */
  public double resolveEffectiveMinAmplitude(double traceWidth, double clearance) {
    if (this.minAmplitude != null && this.minAmplitude > 0) {
      return this.minAmplitude;
    }
    return Math.max(3.0 * traceWidth, traceWidth + clearance);
  }

  /**
   * Returns whether this meander pattern should be strictly single-sided. Unspecified defaults to
   * false (symmetric dual-sided).
   */
  public boolean isSingleSided() {
    return this.singleSided != null ? this.singleSided : false;
  }

  /** Resolves the effective corner radius percentage. Unspecified defaults to 80%. */
  public int resolveEffectiveCornerRadiusPercentage() {
    return this.cornerRadiusPercentage != null ? this.cornerRadiusPercentage : 80;
  }
}
