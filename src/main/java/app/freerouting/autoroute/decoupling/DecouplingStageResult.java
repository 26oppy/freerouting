package app.freerouting.autoroute.decoupling;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Summary result of executing the {@link DecouplingRoutingStage}.
 *
 * @param totalPairs Total candidate pairs identified and considered
 * @param routedPairs Pairs successfully routed (both power and ground legs)
 * @param skippedPairs Pairs skipped (e.g. already connected via surface trace)
 * @param failedPairs Pairs that could not be routed or failed escape/DRC
 * @param totalViasPlaced Total ground vias inserted
 * @param totalTraceLengthUm Total length of trace copper added in micrometers
 * @param durationMs Execution time in milliseconds
 * @param routedIcPinIds IDs of IC power pins that received decoupling routes
 * @param pairResults Detailed per-pair execution results
 */
public record DecouplingStageResult(
    int totalPairs,
    int routedPairs,
    int skippedPairs,
    int failedPairs,
    int totalViasPlaced,
    double totalTraceLengthUm,
    long durationMs,
    Set<Integer> routedIcPinIds,
    List<DecouplingPairResult> pairResults) {

  /** Returns an empty result when the stage is disabled or no candidates were found. */
  public static DecouplingStageResult empty() {
    return new DecouplingStageResult(
        0, 0, 0, 0, 0, 0.0, 0L, Collections.emptySet(), Collections.emptyList());
  }

  /** Returns a result when the stage was cancelled by user request or timeout. */
  public static DecouplingStageResult cancelled() {
    return new DecouplingStageResult(
        0, 0, 0, 0, 0, 0.0, 0L, Collections.emptySet(), Collections.emptyList());
  }

  /** Returns the percentage of candidate pairs successfully routed (0.0 to 100.0). */
  public double routedPercentage() {
    return totalPairs > 0 ? (routedPairs * 100.0 / totalPairs) : 0.0;
  }
}
