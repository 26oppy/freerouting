package app.freerouting.autoroute.decoupling;

/**
 * Result of an atomic decoupling capacitor-IC pair routing attempt comprising both the power supply
 * trace and the low-inductance ground return path.
 */
public record DecouplingPairResult(
    boolean success,
    DecouplingPair pair,
    SurfaceRouteResult powerResult,
    GroundRouteResult groundResult,
    String failureReason) {

  /** Creates a successful atomic pair routing result. */
  public static DecouplingPairResult success(
      DecouplingPair pair, SurfaceRouteResult powerResult, GroundRouteResult groundResult) {
    return new DecouplingPairResult(true, pair, powerResult, groundResult, null);
  }

  /** Creates a failed pair result with a specified failure reason. */
  public static DecouplingPairResult failure(
      DecouplingPair pair,
      SurfaceRouteResult powerResult,
      GroundRouteResult groundResult,
      String reason) {
    return new DecouplingPairResult(false, pair, powerResult, groundResult, reason);
  }

  /** Creates an early failure result before sub-routing stages run. */
  public static DecouplingPairResult failure(DecouplingPair pair, String reason) {
    return new DecouplingPairResult(false, pair, null, null, reason);
  }

  /** Returns the total number of vias placed across power and ground legs. */
  public int totalViaCount() {
    return groundResult != null ? groundResult.viaCount() : 0;
  }

  /** Returns the total trace length added in micrometers across power and ground legs. */
  public double totalTraceLengthUm() {
    double powerLen = powerResult != null ? powerResult.routedTraceLength() : 0.0;
    double groundLen = groundResult != null ? groundResult.routedTraceLength() : 0.0;
    return powerLen + groundLen;
  }
}
