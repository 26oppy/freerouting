package app.freerouting.autoroute.decoupling;

import app.freerouting.autoroute.AutorouteAttemptResult;
import app.freerouting.autoroute.AutorouteAttemptState;
import app.freerouting.autoroute.maze.AutorouteControl;
import app.freerouting.autoroute.maze.AutorouteEngine;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.ConductionArea;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.items.Trace;
import app.freerouting.board.model.items.Via;
import app.freerouting.board.model.structure.Component;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.board.model.structure.Unit;
import app.freerouting.core.StoppableThread;
import app.freerouting.datastructures.TimeLimit;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;
import app.freerouting.settings.DecouplingLockMode;
import app.freerouting.settings.RouterSettings;
import app.freerouting.settings.sources.DefaultSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pre-routing pass for decoupling capacitors.
 *
 * <p>Uses {@link DecouplingAssignmentPlanner} to match decoupling capacitor supply pads to their
 * paired IC supply pins via same-layer surface straps. Integrates {@link DecouplingRouteCheckpoint}
 * to guarantee O(1) split-trace rollback fidelity (Issue #1054) and {@link CourtyardEscapeProbe}
 * with trace width scaling and automatic downgrade (1.5x to 1.0x) to eliminate signal pin trapping
 * in dense IC courtyards prior to fanout and general autorouting.
 */
public final class BatchDecouplingRouter {

  private BatchDecouplingRouter() {}

  /** Result record for a single strap routing attempt. */
  private record StrapAttemptOutcome(
      boolean success, String status, double lengthMm, List<Item> newItems) {}

  /**
   * Runs the decoupling capacitor pre-routing pass on the given board.
   *
   * @param board routing board to process
   * @param settings router settings containing decoupling configuration
   * @param thread stoppable thread context
   * @return summary of decoupling routing results, or {@code null} if disabled or skipped
   */
  public static DecouplingRoutingSummary run(
      RoutingBoard board, RouterSettings settings, StoppableThread thread) {
    if (!settings.isDecouplingEnabled()) {
      return null;
    }

    if (settings.getLayerCount() <= 0) {
      settings.setLayerCount(board.getLayerCount());
      settings.applyBoardSpecificOptimizations(board);
    }

    double maxOwnerRadiusMm =
        settings.decoupling != null && settings.decoupling.maxOwnerRadiusMm != null
            ? settings.decoupling.maxOwnerRadiusMm
            : DefaultSettings.DEFAULT_DECOUPLING_MAX_OWNER_RADIUS_MM;
    boolean includeReview =
        settings.decoupling != null
            && Boolean.TRUE.equals(settings.decoupling.includeReviewCandidates);
    DecouplingLockMode lockMode =
        settings.decoupling != null && settings.decoupling.lockMode != null
            ? settings.decoupling.lockMode
            : DecouplingLockMode.SOFT;
    double maxStrapLengthMm =
        settings.decoupling != null && settings.decoupling.maxStrapLengthMm != null
            ? settings.decoupling.maxStrapLengthMm
            : DefaultSettings.DEFAULT_DECOUPLING_MAX_STRAP_LENGTH_MM;
    double traceWidthScale =
        settings.decoupling != null && settings.decoupling.traceWidthScale != null
            ? settings.decoupling.traceWidthScale
            : 1.5;

    double mmResolution = board.communication.getResolution(Unit.MM);
    double maxStrapLengthUnits = maxStrapLengthMm * mmResolution;

    IdentifierConfig defaultConfig = IdentifierConfig.defaultConfig();
    IdentifierConfig config =
        new IdentifierConfig(
            maxOwnerRadiusMm,
            defaultConfig.minPitchMm(),
            defaultConfig.maxPitchMm(),
            defaultConfig.minPadAreaRatio(),
            defaultConfig.decouplingThreshold(),
            defaultConfig.reviewThreshold(),
            defaultConfig.ownerTopK(),
            defaultConfig.runRefDesAudit(),
            defaultConfig.auditDemotesToReview(),
            defaultConfig.groundNameHints(),
            defaultConfig.forcedGroundNets(),
            defaultConfig.forcedRejectComponents());

    IdentificationResult idResult = DecouplingIdentifier.identify(board, config);

    List<DecouplingMetrics> metricsList = new ArrayList<>();
    int routedStraps = 0;
    int alreadyConnected = 0;
    int viasPlaced = 0;
    int failed = 0;
    double totalStrapLengthMm = 0.0;
    int totalCandidates = 0;

    List<DecouplingRouteTask> tasks =
        DecouplingAssignmentPlanner.plan(
            board, idResult.candidates(), maxOwnerRadiusMm, includeReview);

    try {
      for (DecouplingRouteTask task : tasks) {
        if ((thread != null && (thread.isStopRequested() || thread.isStopAutoRouterRequested()))
            || Thread.currentThread().isInterrupted()) {
          break;
        }

        CapacitorCandidate candidate = task.candidate();
        Component capComp = board.components.get(candidate.componentId());
        String capName = capComp != null ? capComp.name : ("Comp#" + candidate.componentId());

        totalCandidates++;

        Item itemSupply = board.getItem(task.supplyPinId());
        Item itemGround = board.getItem(task.groundPinId());
        if (!(itemSupply instanceof Pin supplyPin) || !(itemGround instanceof Pin groundPin)) {
          failed++;
          metricsList.add(
              new DecouplingMetrics(
                  candidate.componentId(), capName, 0, "", 0.0, 0, 0.0, false, "INVALID_PINS"));
          continue;
        }

        int railNetNo = candidate.railNetNumber();
        if (supplyPin.hasIgnoredNets() || groundPin.hasIgnoredNets()) {
          failed++;
          metricsList.add(
              new DecouplingMetrics(
                  candidate.componentId(),
                  capName,
                  task.targetSupplyItemId(),
                  "",
                  0.0,
                  0,
                  0.0,
                  false,
                  "IGNORED_NET"));
          continue;
        }

        boolean strapSuccess = false;
        double candidateStrapLengthMm = 0.0;
        String strapStatus = "NOT_ATTEMPTED";
        int targetPinId = task.targetSupplyItemId();
        String ownerName = "NONE";

        Pin ownerPin = null;
        if (task.hasTargetSupply()) {
          Item targetItem = board.getItem(task.targetSupplyItemId());
          if (targetItem instanceof Pin pin) {
            ownerPin = pin;
          } else if (task.isSecondary() && task.pairedPrimaryCapPinId() > 0) {
            Item primaryCapItem = board.getItem(task.pairedPrimaryCapPinId());
            if (primaryCapItem instanceof Pin primaryPin) {
              ownerPin = primaryPin;
              targetPinId = primaryPin.getId();
            }
          }
        }

        if (ownerPin != null) {
          Component ownerComp = board.components.get(ownerPin.getComponentId());
          ownerName =
              (ownerComp != null ? ownerComp.name : ("Comp#" + ownerPin.getComponentId()))
                  + "-"
                  + ownerPin.name();

          if (supplyPin.firstLayer() != ownerPin.firstLayer()) {
            failed++;
            strapStatus = "LAYER_MISMATCH";
          } else {
            int layer = supplyPin.firstLayer();
            AutorouteControl control = new AutorouteControl(board, railNetNo, settings);
            if (layer < 0 || layer >= control.layerCount || !control.layerActive[layer]) {
              failed++;
              strapStatus = "LAYER_DISABLED";
            } else {
              control.viasAllowed = false;
              control.ripupAllowed = false;
              control.removeUnconnectedVias = false;
              if (settings.decoupling != null && settings.decoupling.maxDoorExpansions != null) {
                control.maxDoorExpansions = settings.decoupling.maxDoorExpansions;
              }
              for (int i = 0; i < control.layerCount; i++) {
                control.layerActive[i] = control.layerActive[i] && (i == layer);
              }

              double centerDistUnits =
                  supplyPin.getCenter().toFloat().distance(ownerPin.getCenter().toFloat());
              if (centerDistUnits > maxStrapLengthUnits) {
                failed++;
                strapStatus = "DISTANCE_EXCEEDED";
              } else {
                Set<Item> supplyConnected = supplyPin.getConnectedSet(railNetNo);
                if (supplyConnected.contains(ownerPin)) {
                  alreadyConnected++;
                  strapSuccess = true;
                  strapStatus = "ALREADY_CONNECTED";
                  tagDecouplingProtected(supplyPin, lockMode);
                  tagDecouplingProtected(ownerPin, lockMode);
                  if (ReasonCode.FILTERED_ISLAND.isSet(candidate.reasonMask())) {
                    ownerPin.setFilteredIslandNonFeeder(true);
                  }
                } else {
                  Set<Item> ownerConnected = ownerPin.getConnectedSet(railNetNo);

                  // 2-Phase width trial: try wide strap (e.g. 1.5x), then downgrade to 1.0x if
                  // trapped/DRC
                  double[] scalesToTry =
                      (traceWidthScale > 1.0)
                          ? new double[] {traceWidthScale, 1.0}
                          : new double[] {1.0};

                  StrapAttemptOutcome outcome = null;
                  for (double scale : scalesToTry) {
                    Set<Item> currentSupplyConnected = supplyPin.getConnectedSet(railNetNo);
                    Set<Item> currentOwnerConnected = ownerPin.getConnectedSet(railNetNo);
                    outcome =
                        tryRouteStrap(
                            board,
                            settings,
                            thread,
                            control,
                            supplyPin,
                            ownerPin,
                            currentSupplyConnected,
                            currentOwnerConnected,
                            layer,
                            railNetNo,
                            candidate.groundNetNumber(),
                            ownerPin.getComponentId(),
                            candidate.componentId(),
                            scale,
                            maxStrapLengthMm,
                            mmResolution);

                    if (outcome.success()) {
                      break;
                    }
                  }

                  if (outcome != null && outcome.success()) {
                    strapSuccess = true;
                    strapStatus = outcome.status();
                    routedStraps++;
                    candidateStrapLengthMm = outcome.lengthMm();
                    totalStrapLengthMm += candidateStrapLengthMm;
                    tagDecouplingProtected(supplyPin, lockMode);
                    tagDecouplingProtected(ownerPin, lockMode);
                    for (Item newItem : outcome.newItems()) {
                      tagDecouplingProtected(newItem, lockMode);
                    }
                    if (ReasonCode.FILTERED_ISLAND.isSet(candidate.reasonMask())) {
                      ownerPin.setFilteredIslandNonFeeder(true);
                      for (Item newItem : outcome.newItems()) {
                        newItem.setFilteredIslandNonFeeder(true);
                      }
                    }
                  } else {
                    // Secondary overflow cap: fallback flanking attempt to primary cap pad
                    boolean flanked = false;
                    if (task.isSecondary() && task.pairedPrimaryCapPinId() > 0) {
                      Item primaryCapItem = board.getItem(task.pairedPrimaryCapPinId());
                      if (primaryCapItem instanceof Pin primaryCapPin
                          && primaryCapPin.firstLayer() == supplyPin.firstLayer()
                          && !primaryCapPin.equals(ownerPin)
                          && !primaryCapPin.hasIgnoredNets()) {
                        Set<Item> primaryCapConnected = primaryCapPin.getConnectedSet(railNetNo);
                        if (supplyConnected.contains(primaryCapPin)) {
                          alreadyConnected++;
                          strapSuccess = true;
                          strapStatus = "ALREADY_CONNECTED";
                          tagDecouplingProtected(supplyPin, lockMode);
                          tagDecouplingProtected(primaryCapPin, lockMode);
                          if (ReasonCode.FILTERED_ISLAND.isSet(candidate.reasonMask())) {
                            primaryCapPin.setFilteredIslandNonFeeder(true);
                          }
                          flanked = true;
                        } else {
                          double flankDistUnits =
                              supplyPin
                                  .getCenter()
                                  .toFloat()
                                  .distance(primaryCapPin.getCenter().toFloat());
                          if (flankDistUnits <= maxStrapLengthUnits) {
                            StrapAttemptOutcome flankOutcome = null;
                            for (double scale : scalesToTry) {
                              Set<Item> currentSupplyConnected =
                                  supplyPin.getConnectedSet(railNetNo);
                              Set<Item> currentPrimaryConnected =
                                  primaryCapPin.getConnectedSet(railNetNo);
                              flankOutcome =
                                  tryRouteStrap(
                                      board,
                                      settings,
                                      thread,
                                      control,
                                      supplyPin,
                                      primaryCapPin,
                                      currentSupplyConnected,
                                      currentPrimaryConnected,
                                      layer,
                                      railNetNo,
                                      candidate.groundNetNumber(),
                                      primaryCapPin.getComponentId(),
                                      candidate.componentId(),
                                      scale,
                                      maxStrapLengthMm,
                                      mmResolution);
                              if (flankOutcome.success()) {
                                break;
                              }
                            }

                            if (flankOutcome != null && flankOutcome.success()) {
                              flanked = true;
                              strapSuccess = true;
                              strapStatus = "ROUTED_FLANKING";
                              routedStraps++;
                              candidateStrapLengthMm = flankOutcome.lengthMm();
                              totalStrapLengthMm += candidateStrapLengthMm;
                              tagDecouplingProtected(supplyPin, lockMode);
                              tagDecouplingProtected(primaryCapPin, lockMode);
                              for (Item newItem : flankOutcome.newItems()) {
                                tagDecouplingProtected(newItem, lockMode);
                              }
                              if (ReasonCode.FILTERED_ISLAND.isSet(candidate.reasonMask())) {
                                primaryCapPin.setFilteredIslandNonFeeder(true);
                                for (Item newItem : flankOutcome.newItems()) {
                                  newItem.setFilteredIslandNonFeeder(true);
                                }
                              }
                            }
                          }
                        }
                      }
                    }

                    if (!flanked) {
                      failed++;
                      strapStatus = outcome != null ? outcome.status() : "NO_ROUTE_FOUND";
                    }
                  }
                }
              }
            }
          }
        } else {
          strapStatus = task.isPlaneOnly() ? "PLANE_ONLY_PENDING" : "NO_OWNER_IN_RADIUS";
          if (!task.isPlaneOnly()) {
            failed++;
          }
        }

        // Dedicated ground/plane via adjacent to the pad
        int groundNetNo = candidate.groundNetNumber();
        int viasPlacedForCap = 0;
        double groundViaDistanceMm = 0.0;
        Net groundNet = board.rules.nets.get(groundNetNo);

        // Through-hole pins span all layers and connect directly to internal planes
        boolean groundIsSmd = (groundPin.firstLayer() == groundPin.lastLayer());
        boolean groundAlreadyOnPlane = !groundIsSmd;
        if (!groundAlreadyOnPlane) {
          for (Item it : groundPin.getNormalContacts()) {
            if (it instanceof ConductionArea || it instanceof Via) {
              groundAlreadyOnPlane = true;
              break;
            }
          }
        }
        if (!groundAlreadyOnPlane && groundNet != null && groundNet.containsPlane()) {
          for (Item it : groundPin.getConnectedSet(groundNetNo)) {
            if (it instanceof ConductionArea) {
              groundAlreadyOnPlane = true;
              break;
            }
          }
        }

        if (groundAlreadyOnPlane) {
          tagDecouplingProtected(groundPin, lockMode);
          if (task.isPlaneOnly()) {
            strapSuccess = true;
            strapStatus = "PLANE_ONLY_ROUTED";
          }
        } else if (groundNet != null && groundNet.containsPlane()) {
          int maxIdBeforeVia = board.communication.idGenerator.maxGeneratedId();
          AutorouteAttemptResult viaAttempt =
              board.fanout(groundPin, settings, -1, thread, new TimeLimit(1000));
          if (viaAttempt.state == AutorouteAttemptState.ROUTED) {
            List<Item> newViaItems = new ArrayList<>();
            Via placedVia = null;
            for (Item it : groundPin.getConnectedSet(groundNetNo)) {
              if (it.getId() > maxIdBeforeVia) {
                newViaItems.add(it);
                if (it instanceof Via v) {
                  placedVia = v;
                }
              }
            }

            boolean viaViolation = false;
            for (Item newItem : newViaItems) {
              if (!newItem.clearanceViolations().isEmpty()) {
                viaViolation = true;
                break;
              }
            }

            if (viaViolation) {
              for (Item newItem : newViaItems) {
                newItem.setFixedState(FixedState.UNFIXED);
                board.removeItem(newItem);
              }
              if (task.isPlaneOnly()) {
                failed++;
                strapStatus = "PLANE_VIA_FAILED";
              }
            } else {
              if (placedVia != null) {
                viasPlacedForCap = 1;
                viasPlaced++;
                groundViaDistanceMm =
                    groundPin.getCenter().toFloat().distance(placedVia.getCenter().toFloat())
                        / mmResolution;
              }
              tagDecouplingProtected(groundPin, lockMode);
              for (Item newItem : newViaItems) {
                tagDecouplingProtected(newItem, lockMode);
              }
              if (task.isPlaneOnly()) {
                strapSuccess = true;
                strapStatus = "PLANE_ONLY_ROUTED";
              }
            }
          } else {
            if (task.isPlaneOnly()) {
              failed++;
              strapStatus = "PLANE_VIA_FAILED";
            }
          }
        } else {
          if (task.isPlaneOnly()) {
            failed++;
            strapStatus = "NO_GROUND_PLANE";
          }
        }

        metricsList.add(
            new DecouplingMetrics(
                candidate.componentId(),
                capName,
                targetPinId,
                ownerName,
                candidateStrapLengthMm,
                viasPlacedForCap,
                groundViaDistanceMm,
                strapSuccess,
                strapStatus));

        board.clearTransientAutorouteState();
      }
    } finally {
      board.clearTransientAutorouteState();
    }

    DecouplingRoutingSummary summary =
        new DecouplingRoutingSummary(
            totalCandidates,
            routedStraps,
            alreadyConnected,
            viasPlaced,
            failed,
            totalStrapLengthMm,
            metricsList);

    FRLogger.info(
        String.format(
            "Decoupling routing complete: %d eligible candidates, %d straps routed, %d already"
                + " connected, %d ground vias placed, %d failed/skipped. Total strap length: %.2f"
                + " mm.",
            totalCandidates,
            routedStraps,
            alreadyConnected,
            viasPlaced,
            failed,
            totalStrapLengthMm));

    return summary;
  }

  /**
   * Attempts to route a surface strap between supply and target pins at the specified trace width
   * scale. Validates DRC and verifies that adjacent unrouted SMD signal pins are not trapped.
   */
  private static StrapAttemptOutcome tryRouteStrap(
      RoutingBoard board,
      RouterSettings settings,
      StoppableThread thread,
      AutorouteControl control,
      Pin supplyPin,
      Pin targetPin,
      Set<Item> supplyConnected,
      Set<Item> targetConnected,
      int layer,
      int railNetNo,
      int groundNetNo,
      int ownerCompId,
      int capCompId,
      double widthScale,
      double maxStrapLengthMm,
      double mmResolution) {
    IntBox padUnion = supplyPin.boundingBox().union(targetPin.boundingBox());
    int maxClearance = board.rules.clearanceMatrix.maxValue(layer);
    int searchRadius = (int) Math.ceil(maxStrapLengthMm * mmResolution) + maxClearance;
    IntBox searchBox = padUnion.offset(searchRadius);

    // Capture scoped local checkpoint before route attempt
    DecouplingRouteCheckpoint checkpoint =
        DecouplingRouteCheckpoint.capture(board, searchBox, layer, railNetNo);

    // Apply scaled trace width clamped to >= max(netClassHalfWidth, neckWidthUm)
    applyScaledTraceWidth(control, layer, railNetNo, widthScale, board, settings, mmResolution);

    var engine =
        board.initAutoroute(
            railNetNo, control.traceClearanceClassIndex, thread, new TimeLimit(1000), false);

    AutorouteAttemptResult attempt =
        engine.autorouteConnection(
            supplyConnected, targetConnected, control, new TreeSet<>(), null);

    if (attempt.state != AutorouteAttemptState.ROUTED) {
      checkpoint.rollback(board);
      return new StrapAttemptOutcome(false, "NO_ROUTE_FOUND", 0.0, List.of());
    }

    // Deterministic pull-tight optimization with timeLimit = 0
    int accuracy = settings.tracePullTightAccuracy != null ? settings.tracePullTightAccuracy : 500;
    board.optChangedArea(new int[] {railNetNo}, null, accuracy, control.traceCosts, thread, 0);

    // Collect newly added items
    List<Item> newItems = new ArrayList<>();
    int currentMaxId = board.communication.idGenerator.maxGeneratedId();
    for (int id = checkpoint.maxIdBefore + 1; id <= currentMaxId; id++) {
      Item it = board.getItem(id);
      if (it != null) {
        newItems.add(it);
      }
    }

    double lengthMm = 0.0;
    for (Item newItem : newItems) {
      if (newItem instanceof Trace trace) {
        lengthMm += trace.getLength() / mmResolution;
      }
    }

    // Check DRC clearance violations
    boolean hasViolation = false;
    for (Item newItem : newItems) {
      if (!newItem.clearanceViolations().isEmpty()) {
        hasViolation = true;
        break;
      }
    }

    if (hasViolation) {
      checkpoint.rollback(board);
      return new StrapAttemptOutcome(false, "DRC_ROLLBACK", 0.0, List.of());
    }

    if (lengthMm > maxStrapLengthMm) {
      checkpoint.rollback(board);
      return new StrapAttemptOutcome(false, "DISTANCE_EXCEEDED", 0.0, List.of());
    }

    // Run CourtyardEscapeProbe on neighbor pins
    IntBox strapBox = padUnion;
    for (Item newItem : newItems) {
      strapBox = strapBox.union(newItem.boundingBox());
    }
    IntBox probeBox = strapBox.offset(2 * Math.max(maxClearance, 1000));

    boolean escapeSafe =
        CourtyardEscapeProbe.isEscapePossible(
            board, ownerCompId, capCompId, probeBox, layer, railNetNo, groundNetNo, thread);

    if (!escapeSafe) {
      checkpoint.rollback(board);
      return new StrapAttemptOutcome(false, "TRAPPED_PIN_ROLLBACK", 0.0, List.of());
    }

    return new StrapAttemptOutcome(true, "ROUTED", lengthMm, newItems);
  }

  /** Applies scaled trace half-width to the control parameters, respecting neck-down limits. */
  private static void applyScaledTraceWidth(
      AutorouteControl control,
      int layer,
      int railNetNo,
      double scale,
      RoutingBoard board,
      RouterSettings settings,
      double mmResolution) {
    int baseHalfWidth = board.rules.getTraceHalfWidth(railNetNo, layer);
    double neckWidthUm = settings.getNeckWidthUm();
    double neckHalfWidthUnits =
        (neckWidthUm > 0) ? (neckWidthUm * (mmResolution / 1000.0) / 2.0) : 0.0;
    int minAllowedHalfWidth = (int) Math.max(baseHalfWidth, Math.round(neckHalfWidthUnits));
    int effectiveHalfWidth = (int) Math.max(minAllowedHalfWidth, Math.round(baseHalfWidth * scale));

    control.traceHalfWidth[layer] = effectiveHalfWidth;
    control.compensatedTraceHalfWidth[layer] =
        effectiveHalfWidth
            + board.rules.clearanceMatrix.clearanceCompensationValue(
                control.traceClearanceClassIndex, layer);

    int safetyMargin =
        board.rules.clearanceMatrix.getValue(
                control.traceClearanceClassIndex, control.traceClearanceClassIndex, layer, true)
            - board.rules.clearanceMatrix.getValue(
                control.traceClearanceClassIndex, control.traceClearanceClassIndex, layer, false);
    control.traceClearanceOffset[layer] =
        control.compensatedTraceHalfWidth[layer]
            + AutorouteEngine.TRACE_WIDTH_TOLERANCE
            + safetyMargin;
  }

  private static void tagDecouplingProtected(Item item, DecouplingLockMode lockMode) {
    item.setDecouplingProtected(true);
    if ((item instanceof Trace || item instanceof Via)
        && lockMode != DecouplingLockMode.NONE
        && !item.getFixedState().isPreservedRoute()) {
      item.setFixedState(FixedState.SYSTEM_FIXED);
    }
  }
}
