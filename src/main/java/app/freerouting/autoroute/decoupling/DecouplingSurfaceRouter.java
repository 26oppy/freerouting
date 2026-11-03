package app.freerouting.autoroute.decoupling;

import app.freerouting.autoroute.AutorouteAttemptResult;
import app.freerouting.autoroute.AutorouteAttemptState;
import app.freerouting.autoroute.maze.AutorouteControl;
import app.freerouting.autoroute.maze.AutorouteEngine;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.ConductionArea;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.items.Via;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.board.model.structure.Unit;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.core.StoppableThread;
import app.freerouting.datastructures.TimeLimit;
import app.freerouting.settings.DecouplingSettings;
import app.freerouting.settings.RouterSettings;
import app.freerouting.settings.TraceWidthMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Executes point-to-point single-layer surface routing between an IC power supply pin and a
 * decoupling capacitor power pin.
 */
public final class DecouplingSurfaceRouter {

  private DecouplingSurfaceRouter() {}

  /**
   * Attempts to route a direct surface trace from the IC power pin to the capacitor power pin.
   *
   * @param board active routing board
   * @param pair decoupling capacitor-IC pair specification
   * @param settings routing configuration
   * @param thread stoppable background thread (nullable)
   * @return {@link SurfaceRouteResult} with detailed routing and geometric metrics
   */
  public static SurfaceRouteResult routePowerLink(
      RoutingBoard board, DecouplingPair pair, RouterSettings settings, StoppableThread thread) {
    if (board == null || pair == null) {
      return SurfaceRouteResult.failure(
          AutorouteAttemptState.FAILED, "Board or decoupling pair is null.", 0.0);
    }

    Pin icPin = (board.getItem(pair.icPowerPinId()) instanceof Pin pin) ? pin : null;
    Pin capPin = (board.getItem(pair.capPowerPinId()) instanceof Pin pin) ? pin : null;
    if (icPin == null || capPin == null) {
      return SurfaceRouteResult.failure(
          AutorouteAttemptState.FAILED,
          "IC power pin (id "
              + pair.icPowerPinId()
              + ") or capacitor power pin (id "
              + pair.capPowerPinId()
              + ") not found on board.",
          0.0);
    }

    double straightDist = icPin.getCenter().toFloat().distance(capPin.getCenter().toFloat());

    if (stopped(thread)) {
      return SurfaceRouteResult.failure(
          AutorouteAttemptState.FAILED, "Routing interrupted by user/system stop.", straightDist);
    }

    DecouplingSettings decouplingSettings =
        (settings != null && settings.decoupling != null)
            ? settings.decoupling
            : new DecouplingSettings();

    final TraceWidthMode mode =
        (decouplingSettings.traceWidthMode != null)
            ? decouplingSettings.traceWidthMode
            : TraceWidthMode.CLASS_WIDTH;

    int powerNet = pair.powerNetNumber();
    int layer = pair.layer();

    Set<Item> startSet = filterSurfaceConnectable(icPin.getConnectedSet(powerNet), layer);
    Set<Item> destSet = filterSurfaceConnectable(capPin.getConnectedSet(powerNet), layer);
    if (startSet.isEmpty()) {
      startSet = Set.of(icPin);
    }
    if (destSet.isEmpty()) {
      destSet = Set.of(capPin);
    }

    // Direct surface touch check
    for (Item s : startSet) {
      if (destSet.contains(s)) {
        return SurfaceRouteResult.success(straightDist, 0.0, 1.0, List.of());
      }
    }

    if (settings != null && settings.getLayerCount() == 0 && board != null) {
      settings.setLayerCount(board.getLayerCount());
    }

    AutorouteControl control = new AutorouteControl(board, powerNet, settings);
    control.viasAllowed = false;
    control.ripupAllowed = false;
    control.removeUnconnectedVias = false;

    if (layer < 0 || layer >= control.layerCount) {
      return SurfaceRouteResult.failure(
          AutorouteAttemptState.FAILED,
          "Decoupling layer " + layer + " is out of bounds (0.." + (control.layerCount - 1) + ").",
          straightDist);
    }

    for (int i = 0; i < control.layerCount; i++) {
      control.layerActive[i] = control.layerActive[i] && (i == layer);
    }
    if (!control.layerActive[layer]) {
      return SurfaceRouteResult.failure(
          AutorouteAttemptState.FAILED,
          "Decoupling layer " + layer + " is inactive for routing.",
          straightDist);
    }

    int targetHalfWidth = resolveTraceHalfWidth(board, settings, control, mode, layer);
    applyTraceWidth(control, board, layer, targetHalfWidth);

    int maxItemIdBefore = board.communication.idGenerator.maxGeneratedId();
    long timeoutMs =
        (settings != null
                && settings.fanout != null
                && settings.fanout.maxMillisecondsPerPin != null)
            ? settings.fanout.maxMillisecondsPerPin
            : 2000L;
    TimeLimit timeLimit = new TimeLimit((int) Math.min(timeoutMs, Integer.MAX_VALUE));

    AutorouteAttemptResult routeResult;
    try {
      routeResult = runMaze(board, control, startSet, destSet, powerNet, thread, timeLimit);

      // Neckdown fallback: if failed with CLASS_WIDTH, retry with narrower neck width
      if (routeResult.state != AutorouteAttemptState.ROUTED
          && mode == TraceWidthMode.CLASS_WIDTH
          && settings != null
          && settings.getNeckWidthUm() > 0
          && !stopped(thread)) {
        int neckHalf = AutorouteControl.neckHalfWidth(board, settings);
        if (neckHalf < control.traceHalfWidth[layer]) {
          applyTraceWidth(control, board, layer, neckHalf);
          routeResult = runMaze(board, control, startSet, destSet, powerNet, thread, timeLimit);
        }
      }
    } finally {
      board.clearTransientAutorouteState();
    }

    if (routeResult.state != AutorouteAttemptState.ROUTED) {
      return SurfaceRouteResult.failure(routeResult.state, routeResult.details, straightDist);
    }

    // Collect newly inserted copper
    List<Item> createdItems = new ArrayList<>();
    double totalTraceLength = 0.0;
    for (Item item : board.getItems()) {
      if (item.getId() > maxItemIdBefore && item.containsNet(powerNet)) {
        createdItems.add(item);
        if (item instanceof PolylineTrace trace) {
          totalTraceLength += trace.getLength();
        }
      }
    }

    double detourRatio = straightDist > 0 ? (totalTraceLength / straightDist) : 1.0;
    double maxDetourRatio =
        (decouplingSettings.maxDetourRatio != null) ? decouplingSettings.maxDetourRatio : 2.0;

    if (detourRatio > maxDetourRatio) {
      return SurfaceRouteResult.failure(
          AutorouteAttemptState.FAILED,
          String.format(
              Locale.US,
              "Detour ratio %.2f exceeded limit %.2f (length %.1f vs straight %.1f)",
              detourRatio,
              maxDetourRatio,
              totalTraceLength,
              straightDist),
          straightDist,
          totalTraceLength,
          detourRatio);
    }

    // Local pull-tight optimization to eliminate redundant 45-degree bends
    board.optChangedArea(new int[] {powerNet}, null, 500, control.traceCosts, thread);

    // Recompute trace length and created items after pull-tight
    createdItems.clear();
    totalTraceLength = 0.0;
    for (Item item : board.getItems()) {
      if (item.getId() > maxItemIdBefore && item.containsNet(powerNet)) {
        createdItems.add(item);
        if (item instanceof PolylineTrace trace) {
          totalTraceLength += trace.getLength();
        }
      }
    }
    detourRatio = straightDist > 0 ? (totalTraceLength / straightDist) : 1.0;

    // Apply locking policy
    if (Boolean.TRUE.equals(decouplingSettings.lockTraces)) {
      for (Item item : createdItems) {
        item.setFixedState(FixedState.SYSTEM_FIXED);
      }
    }

    return SurfaceRouteResult.success(straightDist, totalTraceLength, detourRatio, createdItems);
  }

  private static AutorouteAttemptResult runMaze(
      RoutingBoard board,
      AutorouteControl control,
      Set<Item> startSet,
      Set<Item> destSet,
      int netNumber,
      StoppableThread thread,
      TimeLimit timeLimit) {
    AutorouteEngine engine =
        board.initAutoroute(netNumber, control.traceClearanceClassIndex, thread, timeLimit, false);
    return engine.autorouteConnection(startSet, destSet, control, new TreeSet<>(), null);
  }

  private static void applyTraceWidth(
      AutorouteControl control, RoutingBoard board, int layer, int halfWidth) {
    int compensation = control.compensatedTraceHalfWidth[layer] - control.traceHalfWidth[layer];
    control.traceHalfWidth[layer] = halfWidth;
    control.compensatedTraceHalfWidth[layer] = halfWidth + compensation;
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

  private static int resolveTraceHalfWidth(
      RoutingBoard board,
      RouterSettings settings,
      AutorouteControl control,
      TraceWidthMode mode,
      int layer) {
    return switch (mode) {
      case NECK_WIDTH -> AutorouteControl.neckHalfWidth(board, settings);
      case CUSTOM_UM -> {
        double widthUm =
            (settings != null
                    && settings.decoupling != null
                    && settings.decoupling.traceWidthUm != null
                    && settings.decoupling.traceWidthUm > 0.0)
                ? settings.decoupling.traceWidthUm
                : (settings != null ? settings.getNeckWidthUm() : 0.0);
        if (widthUm > 0.0) {
          int boardResolution = Math.max(1, board.communication.resolution);
          int traceWidth =
              (int)
                  Math.round(
                      Unit.scale(widthUm * boardResolution, Unit.UM, board.communication.unit));
          yield Math.max(Math.max(1, traceWidth / 2), board.rules.getMinAllowedTraceHalfWidth());
        }
        yield control.traceHalfWidth[layer];
      }
      case CLASS_WIDTH -> control.traceHalfWidth[layer];
    };
  }

  private static Set<Item> filterSurfaceConnectable(Set<Item> connectedSet, int layer) {
    if (connectedSet == null || connectedSet.isEmpty()) {
      return Set.of();
    }
    Set<Item> result = new HashSet<>();
    for (Item item : connectedSet) {
      if (item instanceof ConductionArea || item instanceof Via) {
        continue;
      }
      if (item.firstLayer() <= layer && item.lastLayer() >= layer) {
        result.add(item);
      }
    }
    return result;
  }

  private static boolean stopped(StoppableThread thread) {
    return thread != null && (thread.isStopRequested() || thread.isStopAutoRouterRequested());
  }
}
