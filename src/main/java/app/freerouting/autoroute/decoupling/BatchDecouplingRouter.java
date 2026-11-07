package app.freerouting.autoroute.decoupling;

import app.freerouting.autoroute.AutorouteAttemptResult;
import app.freerouting.autoroute.AutorouteAttemptState;
import app.freerouting.autoroute.maze.AutorouteControl;
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
import app.freerouting.drc.ClearanceViolation;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;
import app.freerouting.settings.DecouplingLockMode;
import app.freerouting.settings.RouterSettings;
import app.freerouting.settings.sources.DefaultSettings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pre-routing pass for decoupling capacitors.
 *
 * <p>Uses {@link DecouplingAssignmentPlanner} to optimally match decoupling capacitor supply pads
 * to their paired IC supply pins (or parallel sibling primary caps) via same-layer surface straps,
 * and drops dedicated ground/plane vias adjacent to capacitor ground pads, prior to SMD fanout and
 * general autorouting.
 */
public final class BatchDecouplingRouter {

  private BatchDecouplingRouter() {}

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
            : DecouplingLockMode.HARD;
    double maxStrapLengthMm =
        settings.decoupling != null && settings.decoupling.maxStrapLengthMm != null
            ? settings.decoupling.maxStrapLengthMm
            : DefaultSettings.DEFAULT_DECOUPLING_MAX_STRAP_LENGTH_MM;

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
            if (!control.layerActive[layer]) {
              failed++;
              strapStatus = "LAYER_DISABLED";
            } else {
              control.viasAllowed = false;
              control.ripupAllowed = false;
              control.removeUnconnectedVias = false;
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
                } else {
                  int maxIdBefore = board.communication.idGenerator.maxGeneratedId();
                  Set<Item> ownerConnected = ownerPin.getConnectedSet(railNetNo);

                  var engine =
                      board.initAutoroute(
                          railNetNo,
                          control.traceClearanceClassIndex,
                          thread,
                          new TimeLimit(1000),
                          false);
                  AutorouteAttemptResult attempt =
                      engine.autorouteConnection(
                          supplyConnected, ownerConnected, control, new TreeSet<>(), null);

                  if (attempt.state == AutorouteAttemptState.ROUTED) {
                    int accuracy =
                        settings.tracePullTightAccuracy != null
                            ? settings.tracePullTightAccuracy
                            : 500;
                    board.optChangedArea(
                        new int[] {railNetNo}, null, accuracy, control.traceCosts, thread);

                    List<Item> newItems = new ArrayList<>();
                    for (Item it : supplyPin.getConnectedSet(railNetNo)) {
                      if (it.getId() > maxIdBefore) {
                        newItems.add(it);
                      }
                    }

                    candidateStrapLengthMm = 0.0;
                    for (Item newItem : newItems) {
                      if (newItem instanceof Trace trace) {
                        candidateStrapLengthMm += trace.getLength() / mmResolution;
                      }
                    }

                    boolean hasViolation = false;
                    for (Item newItem : newItems) {
                      Collection<ClearanceViolation> viols = newItem.clearanceViolations();
                      if (!viols.isEmpty()) {
                        hasViolation = true;
                        break;
                      }
                    }

                    if (hasViolation) {
                      for (Item newItem : newItems) {
                        board.removeItem(newItem);
                      }
                      failed++;
                      strapStatus = "DRC_ROLLBACK";
                    } else if (candidateStrapLengthMm > maxStrapLengthMm) {
                      for (Item newItem : newItems) {
                        board.removeItem(newItem);
                      }
                      failed++;
                      strapStatus = "DISTANCE_EXCEEDED";
                    } else {
                      strapSuccess = true;
                      strapStatus = "ROUTED";
                      routedStraps++;
                      totalStrapLengthMm += candidateStrapLengthMm;
                      tagDecouplingProtected(supplyPin, lockMode);
                      tagDecouplingProtected(ownerPin, lockMode);
                      for (Item newItem : newItems) {
                        tagDecouplingProtected(newItem, lockMode);
                      }
                    }
                  } else {
                    // Secondary overflow cap: fallback flanking attempt to primary cap pad
                    boolean flanked = false;
                    if (task.isSecondary() && task.pairedPrimaryCapPinId() > 0) {
                      Item primaryCapItem = board.getItem(task.pairedPrimaryCapPinId());
                      if (primaryCapItem instanceof Pin primaryCapPin
                          && primaryCapPin.firstLayer() == supplyPin.firstLayer()
                          && !primaryCapPin.equals(ownerPin)) {
                        Set<Item> primaryCapConnected = primaryCapPin.getConnectedSet(railNetNo);
                        int maxIdBeforeFlank = board.communication.idGenerator.maxGeneratedId();
                        var flankEngine =
                            board.initAutoroute(
                                railNetNo,
                                control.traceClearanceClassIndex,
                                thread,
                                new TimeLimit(1000),
                                false);
                        AutorouteAttemptResult flankAttempt =
                            flankEngine.autorouteConnection(
                                supplyConnected,
                                primaryCapConnected,
                                control,
                                new TreeSet<>(),
                                null);

                        if (flankAttempt.state == AutorouteAttemptState.ROUTED) {
                          int accuracy =
                              settings.tracePullTightAccuracy != null
                                  ? settings.tracePullTightAccuracy
                                  : 500;
                          board.optChangedArea(
                              new int[] {railNetNo}, null, accuracy, control.traceCosts, thread);

                          List<Item> flankItems = new ArrayList<>();
                          for (Item it : supplyPin.getConnectedSet(railNetNo)) {
                            if (it.getId() > maxIdBeforeFlank) {
                              flankItems.add(it);
                            }
                          }

                          double flankLengthMm = 0.0;
                          for (Item newItem : flankItems) {
                            if (newItem instanceof Trace trace) {
                              flankLengthMm += trace.getLength() / mmResolution;
                            }
                          }

                          boolean flankViolation = false;
                          for (Item newItem : flankItems) {
                            if (!newItem.clearanceViolations().isEmpty()) {
                              flankViolation = true;
                              break;
                            }
                          }

                          if (!flankViolation && flankLengthMm <= maxStrapLengthMm) {
                            flanked = true;
                            strapSuccess = true;
                            strapStatus = "ROUTED_FLANKING";
                            routedStraps++;
                            totalStrapLengthMm += flankLengthMm;
                            candidateStrapLengthMm = flankLengthMm;
                            tagDecouplingProtected(supplyPin, lockMode);
                            tagDecouplingProtected(primaryCapPin, lockMode);
                            for (Item newItem : flankItems) {
                              tagDecouplingProtected(newItem, lockMode);
                            }
                          } else {
                            for (Item newItem : flankItems) {
                              board.removeItem(newItem);
                            }
                          }
                        }
                      }
                    }

                    if (!flanked) {
                      failed++;
                      strapStatus = "NO_ROUTE_FOUND";
                    }
                  }
                }
              }
            }
          }
        } else {
          strapStatus = "NO_OWNER_IN_RADIUS";
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

        if (!groundAlreadyOnPlane && groundNet != null && groundNet.containsPlane()) {
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
                board.removeItem(newItem);
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

  private static void tagDecouplingProtected(Item item, DecouplingLockMode lockMode) {
    item.setDecouplingProtected(true);
    if (lockMode == DecouplingLockMode.HARD && !item.getFixedState().isPreservedRoute()) {
      item.setFixedState(FixedState.SYSTEM_FIXED);
    }
  }
}
