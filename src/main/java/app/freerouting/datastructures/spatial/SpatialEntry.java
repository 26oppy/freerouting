package app.freerouting.datastructures.spatial;

import app.freerouting.geometry.planar.IntBox;
import java.util.Objects;

/**
 * An entry within a 2D spatial index holding an item value and its axis-aligned bounding box.
 *
 * @param <T> the type of element
 * @param value the item stored
 * @param bounds the bounding box of the item
 */
public record SpatialEntry<T>(T value, IntBox bounds) {

  /** Validates that neither the value nor the bounds are null or empty. */
  public SpatialEntry {
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(bounds, "bounds must not be null");
    if (bounds.isEmpty()) {
      throw new IllegalArgumentException("bounds must not be empty");
    }
  }

  /** Returns the horizontal center coordinate of the bounding box. */
  public double centerX() {
    return bounds.ll.x + ((double) bounds.width()) / 2.0;
  }

  /** Returns the vertical center coordinate of the bounding box. */
  public double centerY() {
    return bounds.ll.y + ((double) bounds.height()) / 2.0;
  }
}
