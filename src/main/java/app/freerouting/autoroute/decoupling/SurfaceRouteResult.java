package app.freerouting.autoroute.decoupling;

import app.freerouting.autoroute.AutorouteAttemptState;
import app.freerouting.board.model.items.Item;
import java.util.List;

/**
 * Result of a point-to-point surface routing attempt between an IC power pin and a decoupling
 * capacitor power pin.
 */
public record SurfaceRouteResult(
    boolean success,
    AutorouteAttemptState state,
    double straightDistance,
    double routedTraceLength,
    double detourRatio,
    String failureReason,
    List<Item> createdItems) {

  /** Creates a successful routing result. */
  public static SurfaceRouteResult success(
      double straightDistance,
      double routedTraceLength,
      double detourRatio,
      List<Item> createdItems) {
    return new SurfaceRouteResult(
        true,
        AutorouteAttemptState.ROUTED,
        straightDistance,
        routedTraceLength,
        detourRatio,
        null,
        createdItems != null ? List.copyOf(createdItems) : List.of());
  }

  /** Creates a failure result with straight-line distance. */
  public static SurfaceRouteResult failure(
      AutorouteAttemptState state, String failureReason, double straightDistance) {
    return new SurfaceRouteResult(
        false, state, straightDistance, 0.0, 0.0, failureReason, List.of());
  }

  /** Creates a failure result with detour metrics (e.g. detour ratio exceeded). */
  public static SurfaceRouteResult failure(
      AutorouteAttemptState state,
      String failureReason,
      double straightDistance,
      double routedTraceLength,
      double detourRatio) {
    return new SurfaceRouteResult(
        false, state, straightDistance, routedTraceLength, detourRatio, failureReason, List.of());
  }
}
