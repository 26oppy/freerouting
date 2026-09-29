package app.freerouting.datastructures.spatial;

import app.freerouting.geometry.planar.IntBox;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A 2D spatial index for fast spatial range queries over axis-aligned bounding boxes ({@link
 * IntBox}).
 *
 * @param <T> the type of element stored in the spatial index
 */
public interface SpatialIndex<T> {

  /**
   * Inserts an item with the given bounding box into the index.
   *
   * @param item the item to insert
   * @param bounds the bounding box of the item
   */
  void insert(T item, IntBox bounds);

  /**
   * Removes an item with the given bounding box from the index.
   *
   * @param item the item to remove
   * @param bounds the bounding box of the item
   * @return true if the item was found and removed, false otherwise
   */
  boolean remove(T item, IntBox bounds);

  /**
   * Queries all items whose bounding box intersects the given query box, notifying the visitor for
   * each match.
   *
   * @param queryBox the search bounding box
   * @param visitor the consumer called for each matching item
   */
  void query(IntBox queryBox, Consumer<T> visitor);

  /**
   * Queries items matching the bounding box and passing the given filter predicate.
   *
   * @param queryBox the search bounding box
   * @param filter a predicate to pre-filter items before notifying the visitor
   * @param visitor the consumer called for each matching item
   */
  void query(IntBox queryBox, Predicate<T> filter, Consumer<T> visitor);

  /**
   * Queries all items whose bounding box intersects the given query box and returns them in a list.
   *
   * @param queryBox the search bounding box
   * @return list of matching items
   */
  List<T> query(IntBox queryBox);

  /**
   * Returns the number of items stored in this spatial index.
   *
   * @return the number of items
   */
  int size();

  /**
   * Returns whether the spatial index contains no items.
   *
   * @return true if empty
   */
  boolean isEmpty();

  /** Removes all items from the spatial index. */
  void clear();

  /**
   * Returns the cumulative bounding box enclosing all items in the spatial index, or {@link
   * IntBox#EMPTY} if empty.
   *
   * @return bounding box enclosing all items
   */
  IntBox getBounds();
}
