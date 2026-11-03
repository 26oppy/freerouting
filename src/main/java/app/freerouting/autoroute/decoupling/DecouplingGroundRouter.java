package app.freerouting.autoroute.decoupling;

import app.freerouting.autoroute.AutorouteAttemptResult;
import app.freerouting.autoroute.AutorouteAttemptState;
import app.freerouting.autoroute.decoupling.DecouplingEscapeProbe.EscapeCheckResult;
import app.freerouting.autoroute.decoupling.GroundRouteResult.GroundTopologyType;
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
import app.freerouting.geometry.planar.Circle;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Shape;
import app.freerouting.settings.RouterSettings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Executes plane-aware, low-inductance ground return routing for decoupling capacitors and manages
 * atomic pair route transactions with strict differential DRC and breakout escape verification.
 */
public final class DecouplingGroundRouter {

  private DecouplingGroundRouter() {}

  /**
   * Routes an atomic decoupling pair (both power supply link and ground return path). If either
   * connection fails routing, exceeds detour limits, introduces clearance violations, or walls in
   * neighbouring pins, the entire transaction is rolled back via board undo snapshotting.
   *
   * @param board active routing board
   * @param pair decoupling capacitor-IC pair specification
   * @param settings routing configuration
   * @param thread stoppable background thread (nullable)
   * @return {@link DecouplingPairResult} describing transaction outcome
   */
  public static DecouplingPairResult routePairAtomic(
      RoutingBoard board, DecouplingPair pair, RouterSettings settings, StoppableThread thread) {
    if (board == null || pair == null) {
      return DecouplingPairResult.failure(pair, "Board or decoupling pair is null.");
    }

    if (stopped(thread)) {
      return DecouplingPairResult.failure(pair, "Routing interrupted by user/system stop.");
    }

    // 1. Establish undo snapshot for strict pair atomicity
    board.generateSnapshot();

    Set<Integer> affectedNets = new HashSet<>();
    affectedNets.add(pair.powerNetNumber());
    affectedNets.add(pair.groundNetNumber());

    try {
      // 2. Record baseline breakout escapability of neighbouring IC pins
      Set<Integer> baselineEscapable =
          DecouplingEscapeProbe.recordBaseline(
              board,
              pair.icComponentId(),
              pair.icPowerPinId(),
              pair.icGroundPinId(),
              pair.layer(),
              3.0);

      // 3. Route direct surface power trace
      SurfaceRouteResult powerResult =
          DecouplingSurfaceRouter.routePowerLink(board, pair, settings, thread);
      if (!powerResult.success()) {
        board.undo(affectedNets);
        return DecouplingPairResult.failure(
            pair, powerResult, null, "Power link failed: " + powerResult.failureReason());
      }

      // 4. Differential DRC on newly added power copper
      if (hasClearanceViolation(powerResult.createdItems())) {
        board.undo(affectedNets);
        return DecouplingPairResult.failure(
            pair, powerResult, null, "Clearance violation detected on candidate power link.");
      }

      // 5. Route plane-aware ground return
      GroundRouteResult groundResult = routeGroundReturn(board, pair, settings, thread);
      if (!groundResult.success()) {
        board.undo(affectedNets);
        return DecouplingPairResult.failure(
            pair,
            powerResult,
            groundResult,
            "Ground return failed: " + groundResult.failureReason());
      }

      // 6. Differential DRC on newly added ground copper
      if (hasClearanceViolation(groundResult.createdItems())) {
        board.undo(affectedNets);
        return DecouplingPairResult.failure(
            pair,
            powerResult,
            groundResult,
            "Clearance violation detected on candidate ground return.");
      }

      // 7. Verify adjacent IC pins still retain at least one open breakout corridor
      EscapeCheckResult escapeCheck =
          DecouplingEscapeProbe.verifyNeighborsStillEscapable(
              board, baselineEscapable, pair.layer());
      if (!escapeCheck.passed()) {
        board.undo(affectedNets);
        return DecouplingPairResult.failure(
            pair, powerResult, groundResult, "Neighbour pin walled in: " + escapeCheck.reason());
      }

      // 8. Transaction succeeded cleanly; pop undo snapshot
      board.popSnapshot();
      return DecouplingPairResult.success(pair, powerResult, groundResult);
    } catch (Exception e) {
      board.undo(affectedNets);
      return DecouplingPairResult.failure(
          pair, "Unexpected exception during atomic pair route: " + e.getMessage());
    }
  }

  /**
   * Routes the ground return path for the capacitor's ground pin according to board topology:
   *
   * <ul>
   *   <li><b>SAME_LAYER_POUR</b>: Direct connection to surface ground flood pour (0 vias).
   *   <li><b>PLANE_VIA_DROP</b>: Adjacent via drop outside pad to inner/opposite ground plane.
   *   <li><b>SURFACE_IC_RETURN</b>: Surface trace directly to IC ground pin (2-layer board).
   * </ul>
   */
  public static GroundRouteResult routeGroundReturn(
      RoutingBoard board, DecouplingPair pair, RouterSettings settings, StoppableThread thread) {
    if (board == null || pair == null) {
      return GroundRouteResult.failure(
          AutorouteAttemptState.FAILED,
          GroundTopologyType.SAME_LAYER_POUR,
          "Board or pair is null.");
    }

    Pin capGroundPin = (board.getItem(pair.capGroundPinId()) instanceof Pin pin) ? pin : null;
    if (capGroundPin == null) {
      return GroundRouteResult.failure(
          AutorouteAttemptState.FAILED,
          GroundTopologyType.SAME_LAYER_POUR,
          "Capacitor ground pin (id " + pair.capGroundPinId() + ") not found on board.");
    }

    if (stopped(thread)) {
      return GroundRouteResult.failure(
          AutorouteAttemptState.FAILED,
          GroundTopologyType.SAME_LAYER_POUR,
          "Routing interrupted by user/system stop.");
    }

    int groundNet = pair.groundNetNumber();
    int layer = pair.layer();

    // 1. Check if ground pin is already grounded
    if (capGroundPin.getUnconnectedSet(groundNet).isEmpty()) {
      return GroundRouteResult.success(GroundTopologyType.SAME_LAYER_POUR, 0, 0.0, List.of());
    }
    for (Item contact : capGroundPin.getNormalContacts()) {
      if (contact instanceof ConductionArea area
          && area.containsNet(groundNet)
          && !area.getIsObstacle()
          && contact.firstLayer() <= layer
          && contact.lastLayer() >= layer) {
        return GroundRouteResult.success(GroundTopologyType.SAME_LAYER_POUR, 0, 0.0, List.of());
      }
    }

    if (settings != null && settings.getLayerCount() == 0) {
      settings.setLayerCount(board.getLayerCount());
    }

    double resolutionMm =
        (board.communication != null) ? board.communication.getResolution(Unit.MM) : 1000.0;
    if (resolutionMm <= 0.0) {
      resolutionMm = 1000.0;
    }

    // 2. Identify topology
    Set<Item> sameLayerPours = new HashSet<>();
    Set<Item> otherLayerPlanes = new HashSet<>();
    for (ConductionArea area : board.getConductionAreas()) {
      if (area.containsNet(groundNet) && !area.getIsObstacle()) {
        if (area.firstLayer() <= layer && area.lastLayer() >= layer) {
          sameLayerPours.add(area);
        } else {
          otherLayerPlanes.add(area);
        }
      }
    }

    // Case A: Same-layer pour exists
    if (!sameLayerPours.isEmpty()) {
      return routeSameLayerPour(
          board, capGroundPin, sameLayerPours, groundNet, layer, settings, thread);
    }

    // Case B: Multi-layer board with internal/opposite ground planes or via capability
    boolean hasDedicatedPowerPlanes = false;
    for (int l = 0; l < board.getLayerCount(); l++) {
      if (!board.layerStructure.layers[l].isSignal) {
        hasDedicatedPowerPlanes = true;
        break;
      }
    }

    if (!otherLayerPlanes.isEmpty() || hasDedicatedPowerPlanes || board.getLayerCount() > 2) {
      return routePlaneViaDrop(
          board, capGroundPin, otherLayerPlanes, groundNet, layer, resolutionMm, settings, thread);
    }

    // Case C: 2-layer board without planes; route directly to IC ground pin
    if (pair.icGroundPinId() > 0) {
      Pin icGroundPin = (board.getItem(pair.icGroundPinId()) instanceof Pin pin) ? pin : null;
      if (icGroundPin != null) {
        return routeSurfaceIcReturn(
            board, capGroundPin, icGroundPin, groundNet, layer, settings, thread);
      }
    }

    return GroundRouteResult.failure(
        AutorouteAttemptState.FAILED,
        GroundTopologyType.PLANE_VIA_DROP,
        "No ground plane, surface pour, or IC ground pin found for ground return.");
  }

  private static GroundRouteResult routeSameLayerPour(
      RoutingBoard board,
      Pin capGroundPin,
      Set<Item> sameLayerPours,
      int groundNet,
      int layer,
      RouterSettings settings,
      StoppableThread thread) {
    AutorouteControl control = new AutorouteControl(board, groundNet, settings);
    control.viasAllowed = false;
    control.ripupAllowed = false;
    control.removeUnconnectedVias = false;
    for (int i = 0; i < control.layerCount; i++) {
      control.layerActive[i] = control.layerActive[i] && (i == layer);
    }

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
      routeResult =
          runMaze(
              board, control, Set.of(capGroundPin), sameLayerPours, groundNet, thread, timeLimit);
    } finally {
      board.clearTransientAutorouteState();
    }

    if (routeResult.state != AutorouteAttemptState.ROUTED) {
      return GroundRouteResult.failure(
          routeResult.state, GroundTopologyType.SAME_LAYER_POUR, routeResult.details);
    }

    board.optChangedArea(new int[] {groundNet}, null, 500, control.traceCosts, thread);

    List<Item> createdItems = collectNewItems(board, maxItemIdBefore, groundNet);
    double length = calculateTotalLength(createdItems);

    applyLockPolicy(createdItems, settings);
    return GroundRouteResult.success(GroundTopologyType.SAME_LAYER_POUR, 0, length, createdItems);
  }

  private static GroundRouteResult routePlaneViaDrop(
      RoutingBoard board,
      Pin capGroundPin,
      Set<Item> otherLayerPlanes,
      int groundNet,
      int layer,
      double resolutionMm,
      RouterSettings settings,
      StoppableThread thread) {
    AutorouteControl control = new AutorouteControl(board, groundNet, settings);
    control.viasAllowed = true;
    control.attachSmdAllowed = false; // Strictly enforce via outside pad
    control.isFanout = true; // Complete immediately on via drop
    control.removeUnconnectedVias = false;
    control.ripupAllowed = false;
    control.fanoutStartPinName = capGroundPin.name();
    control.fanoutStartPinCenter = capGroundPin.getCenter();
    control.fanoutStartPinLayer = layer;

    if (control.viaRule == null && !board.rules.viaRules.isEmpty()) {
      control.viaRule = board.rules.viaRules.firstElement();
      control.rebuildViaInfo(board, settings.getViaCosts(), groundNet);
    }

    Set<Item> destSet = new HashSet<>(otherLayerPlanes);
    if (destSet.isEmpty()) {
      destSet.addAll(capGroundPin.getUnconnectedSet(groundNet));
    }
    if (destSet.isEmpty()) {
      destSet.addAll(board.getConnectableItems(groundNet));
      destSet.remove(capGroundPin);
    }

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
      routeResult =
          runMaze(board, control, Set.of(capGroundPin), destSet, groundNet, thread, timeLimit);
    } finally {
      board.clearTransientAutorouteState();
    }

    if (routeResult.state != AutorouteAttemptState.ROUTED) {
      return GroundRouteResult.failure(
          routeResult.state, GroundTopologyType.PLANE_VIA_DROP, routeResult.details);
    }

    List<Item> createdItems = collectNewItems(board, maxItemIdBefore, groundNet);
    List<Via> createdVias = new ArrayList<>();
    for (Item item : createdItems) {
      if (item instanceof Via via) {
        createdVias.add(via);
      }
    }

    if (createdVias.isEmpty()) {
      return GroundRouteResult.failure(
          AutorouteAttemptState.FAILED,
          GroundTopologyType.PLANE_VIA_DROP,
          "Plane via drop completed without inserting a via.");
    }

    // Strict no-via-in-pad geometric assertion
    for (Via via : createdVias) {
      if (!isViaOutsidePad(via, capGroundPin)) {
        return GroundRouteResult.failure(
            AutorouteAttemptState.FAILED,
            GroundTopologyType.PLANE_VIA_DROP,
            "Ground via fell inside SMD pad, violating no-via-in-pad invariant.");
      }
    }

    // Bounded distance check
    double maxViaDistance = 3.0 * resolutionMm;
    for (Via via : createdVias) {
      double dist = via.getCenter().toFloat().distance(capGroundPin.getCenter().toFloat());
      if (dist > maxViaDistance) {
        return GroundRouteResult.failure(
            AutorouteAttemptState.FAILED,
            GroundTopologyType.PLANE_VIA_DROP,
            String.format(
                Locale.US,
                "Ground via distance %.1f exceeded max distance threshold %.1f",
                dist,
                maxViaDistance));
      }
    }

    board.optChangedArea(new int[] {groundNet}, null, 500, control.traceCosts, thread);

    createdItems = collectNewItems(board, maxItemIdBefore, groundNet);
    double length = calculateTotalLength(createdItems);

    applyLockPolicy(createdItems, settings);
    return GroundRouteResult.success(
        GroundTopologyType.PLANE_VIA_DROP, createdVias.size(), length, createdItems);
  }

  private static GroundRouteResult routeSurfaceIcReturn(
      RoutingBoard board,
      Pin capGroundPin,
      Pin icGroundPin,
      int groundNet,
      int layer,
      RouterSettings settings,
      StoppableThread thread) {
    AutorouteControl control = new AutorouteControl(board, groundNet, settings);
    control.viasAllowed = false;
    control.ripupAllowed = false;
    control.removeUnconnectedVias = false;
    for (int i = 0; i < control.layerCount; i++) {
      control.layerActive[i] = control.layerActive[i] && (i == layer);
    }

    double straightDist =
        capGroundPin.getCenter().toFloat().distance(icGroundPin.getCenter().toFloat());

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
      routeResult =
          runMaze(
              board,
              control,
              Set.of(capGroundPin),
              Set.of(icGroundPin),
              groundNet,
              thread,
              timeLimit);
    } finally {
      board.clearTransientAutorouteState();
    }

    if (routeResult.state != AutorouteAttemptState.ROUTED) {
      return GroundRouteResult.failure(
          routeResult.state, GroundTopologyType.SURFACE_IC_RETURN, routeResult.details);
    }

    board.optChangedArea(new int[] {groundNet}, null, 500, control.traceCosts, thread);

    List<Item> createdItems = collectNewItems(board, maxItemIdBefore, groundNet);
    double length = calculateTotalLength(createdItems);
    double detourRatio = straightDist > 0.0 ? (length / straightDist) : 1.0;
    double maxDetourRatio =
        (settings != null
                && settings.decoupling != null
                && settings.decoupling.maxDetourRatio != null)
            ? settings.decoupling.maxDetourRatio
            : 2.5;

    if (detourRatio > maxDetourRatio) {
      return GroundRouteResult.failure(
          AutorouteAttemptState.FAILED,
          GroundTopologyType.SURFACE_IC_RETURN,
          String.format(
              Locale.US,
              "Ground return detour ratio %.2f exceeded limit %.2f",
              detourRatio,
              maxDetourRatio));
    }

    applyLockPolicy(createdItems, settings);
    return GroundRouteResult.success(GroundTopologyType.SURFACE_IC_RETURN, 0, length, createdItems);
  }

  /**
   * Verifies that the via's center and physical drill hole do not intersect or lie within the
   * copper pad of {@code pin}.
   */
  public static boolean isViaOutsidePad(Via via, Pin pin) {
    if (via == null || pin == null) {
      return true;
    }
    double drillRadius = via.getPadstack().getDrillRadius();
    Point center = via.getCenter();
    Circle drillHole =
        (center instanceof IntPoint intCenter && drillRadius > 0.0)
            ? new Circle(intCenter, (int) Math.ceil(drillRadius))
            : null;

    int fromLayer = Math.max(via.firstLayer(), pin.firstLayer());
    int toLayer = Math.min(via.lastLayer(), pin.lastLayer());
    for (int layer = fromLayer; layer <= toLayer; layer++) {
      Shape pad = pin.getShapeOnLayer(layer);
      if (pad != null) {
        if (pad.containsInside(center) || pad.containsOnBorder(center)) {
          return false;
        }
        if (drillHole != null && pad.intersects(drillHole)) {
          return false;
        }
      }
    }
    return true;
  }

  /** Evaluates whether any newly created item has an unresolvable clearance violation. */
  public static boolean hasClearanceViolation(Collection<Item> items) {
    if (items == null || items.isEmpty()) {
      return false;
    }
    for (Item item : items) {
      if (!item.clearanceViolations().isEmpty()) {
        return true;
      }
    }
    return false;
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

  private static List<Item> collectNewItems(
      RoutingBoard board, int maxItemIdBefore, int netNumber) {
    List<Item> created = new ArrayList<>();
    for (Item item : board.getItems()) {
      if (item.getId() > maxItemIdBefore && item.containsNet(netNumber)) {
        created.add(item);
      }
    }
    return created;
  }

  private static double calculateTotalLength(List<Item> items) {
    double total = 0.0;
    for (Item item : items) {
      if (item instanceof PolylineTrace trace) {
        total += trace.getLength();
      }
    }
    return total;
  }

  private static void applyLockPolicy(List<Item> items, RouterSettings settings) {
    if (settings != null
        && settings.decoupling != null
        && Boolean.TRUE.equals(settings.decoupling.lockTraces)) {
      for (Item item : items) {
        item.setFixedState(FixedState.SYSTEM_FIXED);
      }
    }
  }

  private static boolean stopped(StoppableThread thread) {
    return thread != null && (thread.isStopRequested() || thread.isStopAutoRouterRequested());
  }
}
