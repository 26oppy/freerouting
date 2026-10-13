package app.freerouting.gui.rendering;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.model.items.ComponentObstacleArea;
import app.freerouting.board.model.items.ComponentOutline;
import app.freerouting.board.model.items.Item;
import app.freerouting.geometry.planar.IntBox;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Revision-aware 2D spatial index partitioning board items by draw priority and layer step, backed
 * by a flat-array Bounding Volume Hierarchy (BVH) per bucket.
 *
 * <p>Preserves exact deterministic item draw order while pruning off-screen subtrees in {@code
 * O(log N + k)} time when zoomed in and bypassing per-item bounding-box checks in {@code O(1)} when
 * the viewport encompasses the bucket bounds.
 */
final class BoardSpatialIndex {

  private static final int MIN_DRAW_PRIORITY = 1;
  private static final int MAX_DRAW_PRIORITY = 3;
  private static final int VIRTUAL_LAYER_COUNT = 6;
  private static final int LEAF_CAPACITY = 8;

  private static volatile BoardSpatialIndex cachedIndex;

  private final WeakReference<BasicBoard> boardRef;
  private final long revision;
  private final int layerCount;
  private final BucketIndex[][] buckets;

  private BoardSpatialIndex(BasicBoard board, long revision, int layerCount) {
    this.boardRef = new WeakReference<>(board);
    this.revision = revision;
    this.layerCount = layerCount;
    this.buckets = buildBuckets(board, layerCount);
  }

  static BoardSpatialIndex getOrBuild(BasicBoard board) {
    long currentRevision = board.getRevision();
    int layers = board.getLayerCount();
    BoardSpatialIndex current = cachedIndex;
    if (current != null
        && current.revision == currentRevision
        && current.layerCount == layers
        && current.boardRef.get() == board) {
      return current;
    }
    BoardSpatialIndex built = new BoardSpatialIndex(board, currentRevision, layers);
    cachedIndex = built;
    return built;
  }

  BucketIndex getBucket(int priority, int stepBucketIndex) {
    if (priority < MIN_DRAW_PRIORITY || priority > MAX_DRAW_PRIORITY) {
      return null;
    }
    BucketIndex[] priorityBuckets = buckets[priority];
    if (stepBucketIndex < 0 || stepBucketIndex >= priorityBuckets.length) {
      return null;
    }
    return priorityBuckets[stepBucketIndex];
  }

  private static BucketIndex[][] buildBuckets(BasicBoard board, int layerCount) {
    int totalSteps = layerCount + VIRTUAL_LAYER_COUNT;
    @SuppressWarnings("unchecked")
    List<Item>[][] rawLists = (List<Item>[][]) new List[MAX_DRAW_PRIORITY + 1][totalSteps];
    for (Item item : board.getItems()) {
      int priority = BoardRenderer.drawPriority(item);
      if (priority < MIN_DRAW_PRIORITY || priority > MAX_DRAW_PRIORITY) {
        continue;
      }
      switch (item.getBoardItemType()) {
        case COMPONENT_OUTLINE -> {
          int stepIdx = layerCount + BoardRenderer.virtualLayerFor((ComponentOutline) item);
          addToBucket(rawLists, priority, stepIdx, item);
        }
        case COMPONENT_OBSTACLE_AREA -> {
          int stepIdx = layerCount + BoardRenderer.virtualLayerFor((ComponentObstacleArea) item);
          addToBucket(rawLists, priority, stepIdx, item);
        }
        case BOARD_OUTLINE -> {
          for (int layer = 0; layer < layerCount; layer++) {
            addToBucket(rawLists, priority, layer, item);
          }
        }
        case PIN, VIA -> {
          int fromLayer = Math.max(0, item.firstLayer());
          int toLayer = Math.min(layerCount - 1, item.lastLayer());
          for (int layer = fromLayer; layer <= toLayer; layer++) {
            addToBucket(rawLists, priority, layer, item);
          }
        }
        case TRACE, CONDUCTION_AREA, OBSTACLE_AREA, VIA_OBSTACLE_AREA -> {
          int layer = item.firstLayer();
          if (layer >= 0 && layer < layerCount) {
            addToBucket(rawLists, priority, layer, item);
          }
        }
        default -> {}
      }
    }

    BucketIndex[][] result = new BucketIndex[MAX_DRAW_PRIORITY + 1][totalSteps];
    for (int priority = MIN_DRAW_PRIORITY; priority <= MAX_DRAW_PRIORITY; priority++) {
      for (int step = 0; step < totalSteps; step++) {
        List<Item> list = rawLists[priority][step];
        if (list != null && !list.isEmpty()) {
          result[priority][step] = new BucketIndex(list);
        }
      }
    }
    return result;
  }

  private static void addToBucket(List<Item>[][] buckets, int priority, int stepIdx, Item item) {
    List<Item> list = buckets[priority][stepIdx];
    if (list == null) {
      list = new ArrayList<>();
      buckets[priority][stepIdx] = list;
    }
    list.add(item);
  }

  /** Spatial BVH index over a single {@code (priority, step)} item bucket. */
  static final class BucketIndex {
    final Item[] items;
    private final int bucketMinX;
    private final int bucketMinY;
    private final int bucketMaxX;
    private final int bucketMaxY;

    // Spatially-ordered leaf entries
    private final int[] leafMinX;
    private final int[] leafMinY;
    private final int[] leafMaxX;
    private final int[] leafMaxY;
    private final int[] leafOrigIdx;

    // Flat BVH node arrays (nodeLeft < 0 encodes leaf range: start = ~nodeLeft, end = nodeRight)
    private final int[] nodeMinX;
    private final int[] nodeMinY;
    private final int[] nodeMaxX;
    private final int[] nodeMaxY;
    private final int[] nodeLeft;
    private final int[] nodeRight;
    private int nodeCount;

    BucketIndex(List<Item> itemList) {
      int n = itemList.size();
      this.items = itemList.toArray(new Item[n]);
      this.leafMinX = new int[n];
      this.leafMinY = new int[n];
      this.leafMaxX = new int[n];
      this.leafMaxY = new int[n];
      this.leafOrigIdx = new int[n];

      int bMinX = Integer.MAX_VALUE;
      int bMinY = Integer.MAX_VALUE;
      int bMaxX = Integer.MIN_VALUE;
      int bMaxY = Integer.MIN_VALUE;

      for (int i = 0; i < n; i++) {
        IntBox box = items[i].boundingBox();
        int lx = box.ll.x;
        int ly = box.ll.y;
        int ux = box.ur.x;
        int uy = box.ur.y;
        leafMinX[i] = lx;
        leafMinY[i] = ly;
        leafMaxX[i] = ux;
        leafMaxY[i] = uy;
        leafOrigIdx[i] = i;
        if (lx < bMinX) bMinX = lx;
        if (ly < bMinY) bMinY = ly;
        if (ux > bMaxX) bMaxX = ux;
        if (uy > bMaxY) bMaxY = uy;
      }

      this.bucketMinX = bMinX;
      this.bucketMinY = bMinY;
      this.bucketMaxX = bMaxX;
      this.bucketMaxY = bMaxY;

      if (n > LEAF_CAPACITY * 2) {
        int maxNodes = Math.max(4, 4 * ((n + LEAF_CAPACITY - 1) / LEAF_CAPACITY));
        this.nodeMinX = new int[maxNodes];
        this.nodeMinY = new int[maxNodes];
        this.nodeMaxX = new int[maxNodes];
        this.nodeMaxY = new int[maxNodes];
        this.nodeLeft = new int[maxNodes];
        this.nodeRight = new int[maxNodes];
        buildBvh(0, n);
      } else {
        this.nodeMinX = null;
        this.nodeMinY = null;
        this.nodeMaxX = null;
        this.nodeMaxY = null;
        this.nodeLeft = null;
        this.nodeRight = null;
      }
    }

    private int buildBvh(int start, int end) {
      int nodeIdx = nodeCount++;
      int minX = Integer.MAX_VALUE;
      int minY = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE;
      int maxY = Integer.MIN_VALUE;
      long sumCx = 0;
      long sumCy = 0;

      for (int i = start; i < end; i++) {
        int lx = leafMinX[i];
        int ly = leafMinY[i];
        int ux = leafMaxX[i];
        int uy = leafMaxY[i];
        if (lx < minX) minX = lx;
        if (ly < minY) minY = ly;
        if (ux > maxX) maxX = ux;
        if (uy > maxY) maxY = uy;
        sumCx += ((long) lx + ux);
        sumCy += ((long) ly + uy);
      }

      nodeMinX[nodeIdx] = minX;
      nodeMinY[nodeIdx] = minY;
      nodeMaxX[nodeIdx] = maxX;
      nodeMaxY[nodeIdx] = maxY;

      int count = end - start;
      if (count <= LEAF_CAPACITY || nodeCount + 2 >= nodeMinX.length) {
        nodeLeft[nodeIdx] = ~start;
        nodeRight[nodeIdx] = end;
        return nodeIdx;
      }

      boolean splitX = (maxX - minX) >= (maxY - minY);
      long splitVal2 = splitX ? (sumCx / count) : (sumCy / count);

      int mid = partition(start, end, splitX, splitVal2);
      if (mid == start || mid == end) {
        mid = (start + end) >>> 1;
      }

      int leftChild = buildBvh(start, mid);
      int rightChild = buildBvh(mid, end);
      nodeLeft[nodeIdx] = leftChild;
      nodeRight[nodeIdx] = rightChild;
      return nodeIdx;
    }

    private int partition(int start, int end, boolean splitX, long splitVal2) {
      int i = start;
      int j = end - 1;
      while (i <= j) {
        long val2 =
            splitX ? ((long) leafMinX[i] + leafMaxX[i]) : ((long) leafMinY[i] + leafMaxY[i]);
        if (val2 < splitVal2) {
          i++;
        } else {
          swapLeaf(i, j);
          j--;
        }
      }
      return i;
    }

    private void swapLeaf(int a, int b) {
      int tx = leafMinX[a];
      leafMinX[a] = leafMinX[b];
      leafMinX[b] = tx;

      int ty = leafMinY[a];
      leafMinY[a] = leafMinY[b];
      leafMinY[b] = ty;

      int tux = leafMaxX[a];
      leafMaxX[a] = leafMaxX[b];
      leafMaxX[b] = tux;

      int tuy = leafMaxY[a];
      leafMaxY[a] = leafMaxY[b];
      leafMaxY[b] = tuy;

      int tidx = leafOrigIdx[a];
      leafOrigIdx[a] = leafOrigIdx[b];
      leafOrigIdx[b] = tidx;
    }

    boolean isEmpty() {
      return items.length == 0;
    }

    boolean isDisjoint(IntBox clipBox) {
      return clipBox.ur.x < bucketMinX
          || clipBox.ll.x > bucketMaxX
          || clipBox.ur.y < bucketMinY
          || clipBox.ll.y > bucketMaxY;
    }

    boolean isContainedIn(IntBox clipBox) {
      return clipBox.ll.x <= bucketMinX
          && clipBox.ll.y <= bucketMinY
          && clipBox.ur.x >= bucketMaxX
          && clipBox.ur.y >= bucketMaxY;
    }

    long[] computeVisibleMask(IntBox clipBox) {
      int cMinX = clipBox.ll.x;
      int cMinY = clipBox.ll.y;
      int cMaxX = clipBox.ur.x;
      int cMaxY = clipBox.ur.y;
      long[] mask = new long[(items.length + 63) >>> 6];
      if (nodeLeft == null) {
        for (int i = 0; i < items.length; i++) {
          if (leafMaxX[i] >= cMinX
              && leafMinX[i] <= cMaxX
              && leafMaxY[i] >= cMinY
              && leafMinY[i] <= cMaxY) {
            int orig = leafOrigIdx[i];
            mask[orig >>> 6] |= (1L << orig);
          }
        }
      } else {
        queryNode(0, cMinX, cMinY, cMaxX, cMaxY, mask);
      }
      return mask;
    }

    private void queryNode(int nodeIdx, int cMinX, int cMinY, int cMaxX, int cMaxY, long[] mask) {
      int nMinX = nodeMinX[nodeIdx];
      int nMinY = nodeMinY[nodeIdx];
      int nMaxX = nodeMaxX[nodeIdx];
      int nMaxY = nodeMaxY[nodeIdx];

      if (nMaxX < cMinX || nMinX > cMaxX || nMaxY < cMinY || nMinY > cMaxY) {
        return;
      }

      int left = nodeLeft[nodeIdx];
      int right = nodeRight[nodeIdx];

      if (cMinX <= nMinX && cMinY <= nMinY && cMaxX >= nMaxX && cMaxY >= nMaxY) {
        markSubtree(nodeIdx, mask);
        return;
      }

      if (left < 0) {
        int start = ~left;
        for (int i = start; i < right; i++) {
          if (leafMaxX[i] >= cMinX
              && leafMinX[i] <= cMaxX
              && leafMaxY[i] >= cMinY
              && leafMinY[i] <= cMaxY) {
            int orig = leafOrigIdx[i];
            mask[orig >>> 6] |= (1L << orig);
          }
        }
      } else {
        queryNode(left, cMinX, cMinY, cMaxX, cMaxY, mask);
        queryNode(right, cMinX, cMinY, cMaxX, cMaxY, mask);
      }
    }

    private void markSubtree(int nodeIdx, long[] mask) {
      int left = nodeLeft[nodeIdx];
      int right = nodeRight[nodeIdx];
      if (left < 0) {
        int start = ~left;
        for (int i = start; i < right; i++) {
          int orig = leafOrigIdx[i];
          mask[orig >>> 6] |= (1L << orig);
        }
      } else {
        markSubtree(left, mask);
        markSubtree(right, mask);
      }
    }
  }
}
