package app.freerouting.autoroute.decoupling;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Pin;
import app.freerouting.core.StoppableThread;
import app.freerouting.logger.FRLogger;
import app.freerouting.settings.RouterSettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pipeline stage that identifies and routes decoupling capacitor pairs.
 *
 * <p>Executes prior to SMD fanout and the ripup autorouter. Routes low-inductance surface traces
 * between IC power pins and decoupling capacitor power pins, followed by ground returns (via drops
 * or plane flood connections), with atomic transactional rollback on failure.
 */
public final class DecouplingRoutingStage {

  private DecouplingRoutingStage() {}

  /**
   * Executes the decoupling routing stage on the given board.
   *
   * @param board The active routing board
   * @param settings Active router settings containing decoupling configuration
   * @param thread The execution thread for cancellation checks (may be null)
   * @return A {@link DecouplingStageResult} containing execution statistics and routed pin IDs
   */
  public static DecouplingStageResult run(
      RoutingBoard board, RouterSettings settings, StoppableThread thread) {
    if (board == null || settings == null || settings.decoupling == null) {
      return DecouplingStageResult.empty();
    }
    if (!Boolean.TRUE.equals(settings.decoupling.enabled)) {
      return DecouplingStageResult.empty();
    }
    if (thread != null && (thread.isStopRequested() || thread.isStopAutoRouterRequested())) {
      return DecouplingStageResult.cancelled();
    }

    long startTime = System.currentTimeMillis();

    // 1. Resolve 1:1 candidate pairs
    List<DecouplingPair> candidatePairs = DecouplingPairResolver.resolve(board, settings);
    if (candidatePairs.isEmpty()) {
      FRLogger.info("Decoupling routing stage: no candidate capacitor pairs identified.");
      return DecouplingStageResult.empty();
    }

    FRLogger.info(
        String.format(
            Locale.US,
            "Decoupling routing stage started on board '%s' with %d candidate pairs.",
            board.getHash(),
            candidatePairs.size()));

    int routedPairs = 0;
    int skippedPairs = 0;
    int failedPairs = 0;
    int totalViasPlaced = 0;
    double totalTraceLengthUm = 0.0;
    Set<Integer> routedIcPinIds = new HashSet<>();
    List<DecouplingPairResult> pairResults = new ArrayList<>(candidatePairs.size());

    // 2. Iterate pairs sequentially with atomic transaction guarantees
    for (DecouplingPair pair : candidatePairs) {
      if (thread != null && (thread.isStopRequested() || thread.isStopAutoRouterRequested())) {
        FRLogger.info("Decoupling routing stage interrupted by stop request.");
        break;
      }

      Pin icPowerPin = (board.getItem(pair.icPowerPinId()) instanceof Pin pin) ? pin : null;
      Pin capPowerPin = (board.getItem(pair.capPowerPinId()) instanceof Pin pin) ? pin : null;
      if (icPowerPin == null || capPowerPin == null) {
        failedPairs++;
        pairResults.add(
            DecouplingPairResult.failure(pair, null, null, "Power pins missing from board."));
        continue;
      }

      // Check if power pins are already connected via a direct surface trace
      boolean alreadyConnected =
          DecouplingTraceCrawler.hasDirectSurfaceTrace(
              board,
              pair.capPowerPinId(),
              pair.icPowerPinId(),
              pair.powerNetNumber(),
              pair.layer());
      if (alreadyConnected) {
        skippedPairs++;
        routedIcPinIds.add(pair.icPowerPinId());
        FRLogger.debug(
            String.format(
                Locale.US,
                "Decoupling pair already connected via surface trace: IC pin %d to Cap pin %d",
                pair.icPowerPinId(),
                pair.capPowerPinId()));
        continue;
      }

      // Route pair atomically
      DecouplingPairResult result =
          DecouplingGroundRouter.routePairAtomic(board, pair, settings, thread);
      pairResults.add(result);

      if (result.success()) {
        routedPairs++;
        totalViasPlaced += result.totalViaCount();
        totalTraceLengthUm += result.totalTraceLengthUm();
        routedIcPinIds.add(pair.icPowerPinId());
        FRLogger.debug(
            String.format(
                Locale.US,
                "Decoupling pair routed successfully: IC pin %d to Cap pin %d (length=%.1f um, vias=%d)",
                pair.icPowerPinId(),
                pair.capPowerPinId(),
                result.totalTraceLengthUm(),
                result.totalViaCount()));
      } else {
        failedPairs++;
        FRLogger.debug(
            String.format(
                Locale.US,
                "Decoupling pair failed: IC pin %d to Cap pin %d (reason: %s)",
                pair.icPowerPinId(),
                pair.capPowerPinId(),
                result.failureReason()));
      }
    }

    long durationMs = System.currentTimeMillis() - startTime;
    double routedPercentage =
        candidatePairs.isEmpty() ? 0.0 : (routedPairs * 100.0 / candidatePairs.size());

    FRLogger.info(
        String.format(
            Locale.US,
            "Decoupling stage completed: %d/%d pairs routed (%.1f%%), %d skipped, %d failed, "
                + "%d vias placed, %.2f mm copper added in %.2f s.",
            routedPairs,
            candidatePairs.size(),
            routedPercentage,
            skippedPairs,
            failedPairs,
            totalViasPlaced,
            totalTraceLengthUm / 1000.0,
            durationMs / 1000.0));

    return new DecouplingStageResult(
        candidatePairs.size(),
        routedPairs,
        skippedPairs,
        failedPairs,
        totalViasPlaced,
        totalTraceLengthUm,
        durationMs,
        Collections.unmodifiableSet(routedIcPinIds),
        Collections.unmodifiableList(pairResults));
  }
}
