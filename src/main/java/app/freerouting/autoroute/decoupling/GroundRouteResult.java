package app.freerouting.autoroute.decoupling;

import app.freerouting.autoroute.AutorouteAttemptState;
import app.freerouting.board.model.items.Item;
import java.util.List;

/** Result of a ground return routing attempt for a decoupling capacitor. */
public record GroundRouteResult(
    boolean success,
    AutorouteAttemptState state,
    GroundTopologyType topologyType,
    int viaCount,
    double routedTraceLength,
    String failureReason,
    List<Item> createdItems) {

  /** Topological category used to satisfy the ground return. */
  public enum GroundTopologyType {
    /** Connected to a same-layer ground copper pour / flood fill (0 vias). */
    SAME_LAYER_POUR,

    /** Connected to an internal or bottom ground plane via an adjacent via drop. */
    PLANE_VIA_DROP,

    /** Connected directly to the IC ground pin on the surface layer (2-layer board). */
    SURFACE_IC_RETURN
  }

  /** Creates a successful ground return result. */
  public static GroundRouteResult success(
      GroundTopologyType type, int viaCount, double length, List<Item> items) {
    return new GroundRouteResult(
        true,
        AutorouteAttemptState.ROUTED,
        type,
        viaCount,
        length,
        null,
        items != null ? List.copyOf(items) : List.of());
  }

  /** Creates a failed ground return result. */
  public static GroundRouteResult failure(
      AutorouteAttemptState state, GroundTopologyType type, String reason) {
    return new GroundRouteResult(false, state, type, 0, 0.0, reason, List.of());
  }
}
