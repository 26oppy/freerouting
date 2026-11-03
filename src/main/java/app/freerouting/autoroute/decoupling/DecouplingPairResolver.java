package app.freerouting.autoroute.decoupling;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.settings.RouterSettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves identified decoupling capacitor candidates into deterministic, conflict-free, 1:1
 * routing tasks.
 *
 * <p>Enforces greedy nearest/smallest package assignment when multiple capacitors target the same
 * IC power pin, filters out already-routed surface connections, and resolves endpoints exclusively
 * as primitive IDs for snapshot/rollback reference safety.
 */
public final class DecouplingPairResolver {

  private static final int DEFAULT_MIN_SCORE_THRESHOLD = 65;

  private DecouplingPairResolver() {}

  /**
   * Resolves decoupling candidates into prioritized routing pairs.
   *
   * @param board the active routing board
   * @param boardIndex spatial and topological index of the board
   * @param identificationResult candidate identification result
   * @param settings router settings containing decoupling thresholds
   * @return ordered list of unique, deterministic decoupling pairs
   */
  public static List<DecouplingPair> resolve(
      BasicBoard board,
      BoardIndex boardIndex,
      IdentificationResult identificationResult,
      RouterSettings settings) {
    if (board == null || boardIndex == null || identificationResult == null) {
      return List.of();
    }

    int minScore = DEFAULT_MIN_SCORE_THRESHOLD;
    if (settings != null
        && settings.decoupling != null
        && settings.decoupling.minScoreThreshold != null) {
      minScore = settings.decoupling.minScoreThreshold;
    }

    // 1. Filter candidates passing score threshold and hard gate
    List<CapacitorCandidate> eligibleCandidates = new ArrayList<>();
    for (CapacitorCandidate candidate : identificationResult.candidates()) {
      if (candidate.verdict() == Verdict.DECOUPLING
          && candidate.score() >= minScore
          && candidate.railNetNumber() > 0
          && candidate.groundNetNumber() > 0
          && !candidate.owners().isEmpty()) {
        eligibleCandidates.add(candidate);
      }
    }

    if (eligibleCandidates.isEmpty()) {
      return List.of();
    }

    // 2. Sort candidates to prioritize smaller packages and closer proximity:
    // (minPadArea ASC, primaryDistanceSquared ASC, score DESC, componentId ASC, pinIdA ASC)
    Collections.sort(
        eligibleCandidates,
        (c1, c2) -> {
          long minArea1 = Math.min(c1.padAreaA(), c1.padAreaB());
          long minArea2 = Math.min(c2.padAreaA(), c2.padAreaB());
          int cmp = Long.compare(minArea1, minArea2);
          if (cmp != 0) {
            return cmp;
          }
          long dist1 = c1.owners().get(0).distanceSquared();
          long dist2 = c2.owners().get(0).distanceSquared();
          cmp = Long.compare(dist1, dist2);
          if (cmp != 0) {
            return cmp;
          }
          cmp = Integer.compare(c2.score(), c1.score());
          if (cmp != 0) {
            return cmp;
          }
          cmp = Integer.compare(c1.componentId(), c2.componentId());
          if (cmp != 0) {
            return cmp;
          }
          return Integer.compare(c1.pinIdA(), c2.pinIdA());
        });

    // 3. Greedy 1:1 assignment
    Set<Integer> assignedIcPowerPins = new HashSet<>();
    Set<Integer> assignedCapacitors = new HashSet<>();
    List<DecouplingPair> resolvedPairs = new ArrayList<>();

    for (CapacitorCandidate candidate : eligibleCandidates) {
      if (assignedCapacitors.contains(candidate.componentId())) {
        continue;
      }

      int capPowerPinId;
      int capGroundPinId;
      if (candidate.netNumberA() == candidate.railNetNumber()) {
        capPowerPinId = candidate.pinIdA();
        capGroundPinId = candidate.pinIdB();
      } else if (candidate.netNumberB() == candidate.railNetNumber()) {
        capPowerPinId = candidate.pinIdB();
        capGroundPinId = candidate.pinIdA();
      } else {
        continue;
      }

      for (OwnerCandidate owner : candidate.owners()) {
        if (assignedIcPowerPins.contains(owner.pinId())) {
          continue;
        }

        // Check if already directly connected on the surface
        if (DecouplingTraceCrawler.hasDirectSurfaceTrace(
            board, capPowerPinId, owner.pinId(), candidate.railNetNumber(), candidate.layer())) {
          // Mark assigned so secondary caps do not attempt redundant routing
          assignedIcPowerPins.add(owner.pinId());
          assignedCapacitors.add(candidate.componentId());
          break;
        }

        // Resolve matching IC ground pin
        int icGroundPinId =
            findClosestIcGroundPin(
                board,
                boardIndex,
                owner.componentId(),
                capGroundPinId,
                candidate.groundNetNumber());

        DecouplingPair pair =
            new DecouplingPair(
                candidate.componentId(),
                capPowerPinId,
                capGroundPinId,
                owner.componentId(),
                owner.pinId(),
                icGroundPinId,
                candidate.railNetNumber(),
                candidate.groundNetNumber(),
                candidate.layer(),
                owner.distanceSquared(),
                candidate.score());

        resolvedPairs.add(pair);
        assignedIcPowerPins.add(owner.pinId());
        assignedCapacitors.add(candidate.componentId());
        break;
      }
    }

    // 4. Sort final pairs deterministically:
    // (distanceSquared ASC, score DESC, capacitorComponentId ASC, capPowerPinId ASC)
    Collections.sort(
        resolvedPairs,
        Comparator.comparingLong(DecouplingPair::distanceSquared)
            .thenComparing(Comparator.comparingInt(DecouplingPair::score).reversed())
            .thenComparingInt(DecouplingPair::capacitorComponentId)
            .thenComparingInt(DecouplingPair::capPowerPinId));

    return Collections.unmodifiableList(resolvedPairs);
  }

  private static int findClosestIcGroundPin(
      BasicBoard board,
      BoardIndex boardIndex,
      int icComponentId,
      int capGroundPinId,
      int groundNetNumber) {
    List<Pin> icPins = boardIndex.getPinsOfComponent(icComponentId);
    if (icPins == null || icPins.isEmpty()) {
      return -1;
    }

    Item capGroundItem = board.getItem(capGroundPinId);
    if (!(capGroundItem instanceof Pin capGroundPin)) {
      return -1;
    }

    Point capGroundCenter = capGroundPin.getCenter();
    if (capGroundCenter == null) {
      return -1;
    }

    long minDistanceSq = Long.MAX_VALUE;
    int bestPinId = -1;

    for (Pin icPin : icPins) {
      if (icPin.containsNet(groundNetNumber)) {
        Point icPinCenter = icPin.getCenter();
        if (icPinCenter != null) {
          long distSq = computeDistanceSquared(capGroundCenter, icPinCenter);
          if (distSq < minDistanceSq) {
            minDistanceSq = distSq;
            bestPinId = icPin.getId();
          }
        }
      }
    }

    return bestPinId;
  }

  private static long computeDistanceSquared(Point p1, Point p2) {
    if (p1 == null || p2 == null) {
      return Long.MAX_VALUE;
    }
    long dx = getCoord(p1, true) - getCoord(p2, true);
    long dy = getCoord(p1, false) - getCoord(p2, false);
    return dx * dx + dy * dy;
  }

  private static long getCoord(Point p, boolean isX) {
    if (p instanceof IntPoint ip) {
      return isX ? ip.x : ip.y;
    }
    return Math.round(isX ? p.toFloat().x : p.toFloat().y);
  }
}
