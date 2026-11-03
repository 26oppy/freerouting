package app.freerouting.autoroute.decoupling;

import java.io.Serializable;

/**
 * Immutable routing task representing a paired decoupling capacitor and IC supply load pin.
 *
 * <p>All endpoints are referenced exclusively by primitive integer IDs (rather than object
 * references) to guarantee reference safety across board snapshot restorations during rollbacks.
 *
 * @param capacitorComponentId component ID of the decoupling capacitor
 * @param capPowerPinId pin ID of the capacitor pad on the power rail net
 * @param capGroundPinId pin ID of the capacitor pad on the ground return net
 * @param icComponentId component ID of the decoupled IC
 * @param icPowerPinId pin ID of the target IC supply load pin
 * @param icGroundPinId pin ID of the nearest IC ground pin, or -1 if none identified
 * @param powerNetNumber electrical net number of the power rail
 * @param groundNetNumber electrical net number of the ground return
 * @param layer copper layer of the capacitor SMD pads
 * @param distanceSquared squared Euclidean distance between capacitor and IC supply pin
 * @param score candidate confidence score (0 to 100)
 */
public record DecouplingPair(
    int capacitorComponentId,
    int capPowerPinId,
    int capGroundPinId,
    int icComponentId,
    int icPowerPinId,
    int icGroundPinId,
    int powerNetNumber,
    int groundNetNumber,
    int layer,
    long distanceSquared,
    int score)
    implements Serializable {}
