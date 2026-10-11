package app.freerouting.gui.rendering;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.datastructures.spatial.RTree;
import app.freerouting.datastructures.spatial.SpatialEntry;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.Limits;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * High-performance, revision-aware spatial index for board items in GUI rendering.
 *
 * <p>Maintains per-priority {@link RTree} indexes over item bounding boxes. Automatically detects
 * when a board's revision has changed via {@link BasicBoard#getRevision()} and rebuilds the spatial
 * trees using Sort-Tile-Recursive (STR) bulk loading, avoiding per-frame allocations during panning
 * and zooming.
 */
public final class ItemSpatialIndex {

  private static final Map<BasicBoard, ItemSpatialIndex> CACHE = new WeakHashMap<>();

  private final BasicBoard board;
  private int cachedRevision = -1;

  @SuppressWarnings("unchecked")
  private final RTree<Item>[] treesByPriority = new RTree[BoardRenderer.MAX_DRAW_PRIORITY + 1];

  @SuppressWarnings("unchecked")
  private final List<Item>[] allItemsByPriority = new List[BoardRenderer.MAX_DRAW_PRIORITY + 1];

  private final List<Item> unboundedItems = new ArrayList<>();

  private ItemSpatialIndex(BasicBoard board) {
    this.board = board;
    for (int p = 0; p <= BoardRenderer.MAX_DRAW_PRIORITY; p++) {
      treesByPriority[p] = new RTree<>();
      allItemsByPriority[p] = Collections.emptyList();
    }
  }

  /**
   * Retrieves or creates the spatial index for the given board.
   *
   * @param board the board to index
   * @return the cached spatial index
   */
  public static synchronized ItemSpatialIndex get(BasicBoard board) {
    if (board == null) {
      return null;
    }
    return CACHE.computeIfAbsent(board, ItemSpatialIndex::new);
  }

  /** Synchronizes the spatial index with the board if the revision has changed. */
  public synchronized void updateIfStale() {
    int currentRevision = board.getRevision();
    if (this.cachedRevision == currentRevision) {
      return;
    }

    rebuild();
    this.cachedRevision = currentRevision;
  }

  private void rebuild() {
    unboundedItems.clear();

    @SuppressWarnings("unchecked")
    List<SpatialEntry<Item>>[] entriesByPriority = new List[BoardRenderer.MAX_DRAW_PRIORITY + 1];
    @SuppressWarnings("unchecked")
    List<Item>[] flatByPriority = new List[BoardRenderer.MAX_DRAW_PRIORITY + 1];

    for (int p = 0; p <= BoardRenderer.MAX_DRAW_PRIORITY; p++) {
      entriesByPriority[p] = new ArrayList<>();
      flatByPriority[p] = new ArrayList<>();
    }

    for (Item item : board.getItems()) {
      if (item == null) {
        continue;
      }
      int priority = BoardRenderer.drawPriority(item);
      if (priority < 0 || priority > BoardRenderer.MAX_DRAW_PRIORITY) {
        continue;
      }

      flatByPriority[priority].add(item);

      IntBox box = item.boundingBox();
      if (box != null
          && !box.isEmpty()
          && Math.abs(box.ll.x) < Limits.CRIT_INT
          && Math.abs(box.ur.x) < Limits.CRIT_INT
          && Math.abs(box.ll.y) < Limits.CRIT_INT
          && Math.abs(box.ur.y) < Limits.CRIT_INT) {
        entriesByPriority[priority].add(new SpatialEntry<>(item, box));
      } else {
        unboundedItems.add(item);
      }
    }

    for (int p = BoardRenderer.MIN_DRAW_PRIORITY; p <= BoardRenderer.MAX_DRAW_PRIORITY; p++) {
      treesByPriority[p] = RTree.bulkLoad(entriesByPriority[p]);
      allItemsByPriority[p] = Collections.unmodifiableList(flatByPriority[p]);
    }
  }

  /**
   * Queries visible items for a specific draw priority intersecting the viewport clip box.
   *
   * @param priority the draw priority
   * @param clipBox the screen viewport bounding box in board coordinates (or null for all items)
   * @param visitor the consumer called with each matching item
   */
  public synchronized void query(int priority, IntBox clipBox, Consumer<Item> visitor) {
    if (priority < 0 || priority > BoardRenderer.MAX_DRAW_PRIORITY) {
      return;
    }

    if (clipBox == null) {
      for (Item item : allItemsByPriority[priority]) {
        visitor.accept(item);
      }
      return;
    }

    treesByPriority[priority].query(clipBox, visitor);

    for (Item item : unboundedItems) {
      if (BoardRenderer.drawPriority(item) == priority) {
        visitor.accept(item);
      }
    }
  }

  /**
   * Queries visible items for a specific draw priority intersecting the viewport clip box.
   *
   * @param priority the draw priority
   * @param clipBox the screen viewport bounding box in board coordinates (or null for all items)
   * @return list of matching items
   */
  public synchronized List<Item> query(int priority, IntBox clipBox) {
    List<Item> results = new ArrayList<>();
    query(priority, clipBox, results::add);
    return results;
  }
}
