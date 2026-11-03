package app.freerouting.autoroute.decoupling;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.structure.Unit;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.rules.Net;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Probes breakout corridors around adjacent IC pins to prevent decoupling copper from walling
 * neighbouring pins in.
 */
public final class DecouplingEscapeProbe {

  private static final double[][] DIRECTIONS = {
    {1.0, 0.0},
    {-1.0, 0.0},
    {0.0, 1.0},
    {0.0, -1.0},
    {0.70710678, 0.70710678},
    {-0.70710678, -0.70710678},
    {0.70710678, -0.70710678},
    {-0.70710678, 0.70710678}
  };

  private DecouplingEscapeProbe() {}

  /** Result of escape verification for neighboring pins. */
  public record EscapeCheckResult(boolean passed, int blockedPinId, String reason) {

    /** Creates a passing escape verification result. */
    public static EscapeCheckResult success() {
      return new EscapeCheckResult(true, 0, null);
    }

    /** Creates a failed escape verification result identifying the blocked pin. */
    public static EscapeCheckResult failure(int pinId, String reason) {
      return new EscapeCheckResult(false, pinId, reason);
    }
  }

  /**
   * Records the IDs of adjacent unrouted pins of the specified IC component that currently have at
   * least one open breakout corridor at baseline.
   *
   * @param board routing board
   * @param icComponentId component id of the IC
   * @param icPowerPinId id of the IC power pin being routed
   * @param icGroundPinId id of the IC ground pin (or 0/-1 if not applicable)
   * @param layer surface layer of the connection
   * @param searchRadiusMm search radius in mm to consider a pin "adjacent"
   * @return set of pin IDs that are escapable prior to candidate placement
   */
  public static Set<Integer> recordBaseline(
      RoutingBoard board,
      int icComponentId,
      int icPowerPinId,
      int icGroundPinId,
      int layer,
      double searchRadiusMm) {
    if (board == null || icComponentId <= 0) {
      return Set.of();
    }

    Pin icPowerPin = (board.getItem(icPowerPinId) instanceof Pin pin) ? pin : null;
    if (icPowerPin == null) {
      return Set.of();
    }

    double resolutionMm =
        (board.communication != null) ? board.communication.getResolution(Unit.MM) : 1000.0;
    if (resolutionMm <= 0.0) {
      resolutionMm = 1000.0;
    }

    double effectiveRadiusMm = searchRadiusMm > 0.0 ? searchRadiusMm : 3.0;
    double searchRadiusUnits = effectiveRadiusMm * resolutionMm;
    double maxRadiusSq = searchRadiusUnits * searchRadiusUnits;

    Point refCenter = icPowerPin.getCenter();
    Collection<Pin> componentPins = board.getComponentPins(icComponentId);
    if (componentPins == null || componentPins.isEmpty()) {
      return Set.of();
    }

    Set<Integer> baselineEscapable = new HashSet<>();
    for (Pin pin : componentPins) {
      if (pin.getId() == icPowerPinId || pin.getId() == icGroundPinId) {
        continue;
      }
      if (pin.firstLayer() != layer || pin.lastLayer() != layer) {
        continue;
      }
      if (pin.netCount() == 0 || pin.hasIgnoredNets()) {
        continue;
      }

      int netNumber = pin.getNetNumber(0);
      if (pin.getUnconnectedSet(netNumber).isEmpty()) {
        continue;
      }

      Point pinCenter = pin.getCenter();
      double dx = pinCenter.toFloat().x - refCenter.toFloat().x;
      double dy = pinCenter.toFloat().y - refCenter.toFloat().y;
      if (dx * dx + dy * dy > maxRadiusSq) {
        continue;
      }

      int openCorridors = countOpenCorridors(board, pin, layer, resolutionMm);
      if (openCorridors > 0) {
        baselineEscapable.add(pin.getId());
      }
    }

    return baselineEscapable;
  }

  /**
   * Verifies that all neighbor pins recorded as escapable at baseline still retain at least one
   * open breakout corridor after candidate copper has been placed.
   *
   * @param board routing board
   * @param baselineEscapablePinIds set of pin IDs that had open corridors at baseline
   * @param layer surface layer of the connection
   * @return {@link EscapeCheckResult} indicating whether any pin was newly walled in
   */
  public static EscapeCheckResult verifyNeighborsStillEscapable(
      RoutingBoard board, Set<Integer> baselineEscapablePinIds, int layer) {
    if (board == null || baselineEscapablePinIds == null || baselineEscapablePinIds.isEmpty()) {
      return EscapeCheckResult.success();
    }

    double resolutionMm =
        (board.communication != null) ? board.communication.getResolution(Unit.MM) : 1000.0;
    if (resolutionMm <= 0.0) {
      resolutionMm = 1000.0;
    }

    for (int pinId : baselineEscapablePinIds) {
      if (!(board.getItem(pinId) instanceof Pin pin)) {
        continue;
      }

      int netNumber = pin.getNetNumber(0);
      if (pin.getUnconnectedSet(netNumber).isEmpty()) {
        // Pin became connected during routing; it is no longer an unrouted escape candidate
        continue;
      }

      int openCorridors = countOpenCorridors(board, pin, layer, resolutionMm);
      if (openCorridors == 0) {
        return EscapeCheckResult.failure(
            pinId,
            "Neighbor pin '"
                + pin.name()
                + "' (id "
                + pinId
                + ", net "
                + netNumber
                + ") was walled in (0 of 8 breakout corridors open).");
      }
    }

    return EscapeCheckResult.success();
  }

  /** Probes outward from the pin center along 8 directions to count obstacle-free corridors. */
  static int countOpenCorridors(RoutingBoard board, Pin pin, int layer, double resolutionMm) {
    int netNumber = pin.getNetNumber(0);
    Net net =
        (board.rules != null && board.rules.nets != null) ? board.rules.nets.get(netNumber) : null;
    int clClassNo =
        (net != null && net.getNetClass() != null) ? net.getNetClass().getTraceClearanceClass() : 1;
    int traceHalfWidth =
        (board.rules != null) ? board.rules.getTraceHalfWidth(netNumber, layer) : 100;
    int[] pinNetNumbers = pin.netNumbers;

    IntBox box = pin.boundingBox();
    int maxDim = Math.max(box.width(), box.height());
    double probeDistance = Math.max(1.0 * resolutionMm, 2.5 * maxDim);

    Point fromPoint = pin.getCenter();
    FloatPoint fromFloat = fromPoint.toFloat();
    int openCount = 0;

    for (double[] dir : DIRECTIONS) {
      int targetX = (int) Math.round(fromFloat.x + dir[0] * probeDistance);
      int targetY = (int) Math.round(fromFloat.y + dir[1] * probeDistance);
      Point toPoint = new IntPoint(targetX, targetY);

      double lineLength = fromFloat.distance(toPoint.toFloat());
      if (lineLength <= 0.0) {
        continue;
      }

      double okLength =
          board.checkTraceSegment(
              fromPoint, toPoint, layer, pinNetNumbers, traceHalfWidth, clClassNo, false);

      if (okLength >= lineLength - 1.0) {
        openCount++;
      }
    }

    return openCount;
  }
}
