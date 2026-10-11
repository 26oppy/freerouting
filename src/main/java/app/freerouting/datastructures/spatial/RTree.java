package app.freerouting.datastructures.spatial;

import app.freerouting.geometry.planar.IntBox;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A high-performance 2D R-Tree spatial index for fast bounding-box queries over {@link IntBox}.
 *
 * <p>Implements Guttman's quadratic split algorithm for dynamic insertions and deletions, as well
 * as the Sort-Tile-Recursive (STR) algorithm for high-performance bulk loading.
 *
 * @param <T> the type of element stored in the spatial index
 */
public class RTree<T> implements SpatialIndex<T> {

  public static final int DEFAULT_MAX_ENTRIES = 16;
  public static final int DEFAULT_MIN_ENTRIES = 4;

  private final int maxEntries;
  private final int minEntries;
  private Node<T> root;
  private int size;

  /** Creates an empty RTree with default capacity parameters (max 16, min 4). */
  public RTree() {
    this(DEFAULT_MAX_ENTRIES, DEFAULT_MIN_ENTRIES);
  }

  /**
   * Creates an empty RTree with custom maximum and minimum entries per node.
   *
   * @param maxEntries maximum number of entries per node before splitting (must be &gt;= 4)
   * @param minEntries minimum number of entries per node before underflow (must be &gt;= 2 and
   *     &lt;= maxEntries / 2)
   */
  public RTree(int maxEntries, int minEntries) {
    if (maxEntries < 4) {
      throw new IllegalArgumentException("maxEntries must be at least 4");
    }
    if (minEntries < 2 || minEntries > maxEntries / 2) {
      throw new IllegalArgumentException("minEntries must be between 2 and maxEntries / 2");
    }
    this.maxEntries = maxEntries;
    this.minEntries = minEntries;
    this.root = null;
    this.size = 0;
  }

  /**
   * Builds an R-Tree from a collection of pre-existing spatial entries using the
   * Sort-Tile-Recursive (STR) bulk loading algorithm.
   *
   * @param <T> the element type
   * @param entries the spatial entries to index
   * @return a packed, balanced RTree containing all entries
   */
  public static <T> RTree<T> bulkLoad(List<SpatialEntry<T>> entries) {
    return bulkLoad(entries, DEFAULT_MAX_ENTRIES, DEFAULT_MIN_ENTRIES);
  }

  /**
   * Builds an R-Tree from a collection of pre-existing spatial entries using the
   * Sort-Tile-Recursive (STR) bulk loading algorithm with custom capacity parameters.
   *
   * @param <T> the element type
   * @param entries the spatial entries to index
   * @param maxEntries maximum entries per node
   * @param minEntries minimum entries per node
   * @return a packed, balanced RTree containing all entries
   */
  public static <T> RTree<T> bulkLoad(
      List<SpatialEntry<T>> entries, int maxEntries, int minEntries) {
    RTree<T> tree = new RTree<>(maxEntries, minEntries);
    if (entries == null || entries.isEmpty()) {
      return tree;
    }

    List<SpatialEntry<T>> validEntries = new ArrayList<>();
    for (SpatialEntry<T> entry : entries) {
      if (entry != null
          && entry.value() != null
          && entry.bounds() != null
          && !entry.bounds().isEmpty()) {
        validEntries.add(entry);
      }
    }

    if (validEntries.isEmpty()) {
      return tree;
    }

    tree.size = validEntries.size();
    if (validEntries.size() <= maxEntries) {
      LeafNode<T> leaf = new LeafNode<>();
      leaf.entries.addAll(validEntries);
      leaf.recalculateBounds();
      tree.root = leaf;
      return tree;
    }

    // Sort-Tile-Recursive (STR) algorithm
    tree.root = strBulkLoad(validEntries, maxEntries);
    return tree;
  }

  private static <T> Node<T> strBulkLoad(List<SpatialEntry<T>> entries, int maxEntries) {
    int total = entries.size();
    int leafCount = (int) Math.ceil((double) total / maxEntries);
    int sliceCount = (int) Math.ceil(Math.sqrt(leafCount));
    int entriesPerSlice = sliceCount * maxEntries;

    entries.sort(Comparator.comparingDouble(SpatialEntry::centerX));

    List<Node<T>> currentLevelNodes = new ArrayList<>(leafCount);
    for (int i = 0; i < total; i += entriesPerSlice) {
      int sliceEnd = Math.min(i + entriesPerSlice, total);
      List<SpatialEntry<T>> slice = new ArrayList<>(entries.subList(i, sliceEnd));
      slice.sort(Comparator.comparingDouble(SpatialEntry::centerY));

      for (int j = 0; j < slice.size(); j += maxEntries) {
        int nodeEnd = Math.min(j + maxEntries, slice.size());
        LeafNode<T> leaf = new LeafNode<>();
        leaf.entries.addAll(slice.subList(j, nodeEnd));
        leaf.recalculateBounds();
        currentLevelNodes.add(leaf);
      }
    }

    while (currentLevelNodes.size() > 1) {
      int nodeCount = currentLevelNodes.size();
      if (nodeCount <= maxEntries) {
        InnerNode<T> parent = new InnerNode<>();
        for (Node<T> child : currentLevelNodes) {
          parent.addChild(child);
        }
        parent.recalculateBounds();
        return parent;
      }

      int parentCount = (int) Math.ceil((double) nodeCount / maxEntries);
      int parentSliceCount = (int) Math.ceil(Math.sqrt(parentCount));
      int nodesPerSlice = parentSliceCount * maxEntries;

      currentLevelNodes.sort(Comparator.comparingDouble(Node::centerX));
      List<Node<T>> nextLevelNodes = new ArrayList<>(parentCount);

      for (int i = 0; i < nodeCount; i += nodesPerSlice) {
        int sliceEnd = Math.min(i + nodesPerSlice, nodeCount);
        List<Node<T>> slice = new ArrayList<>(currentLevelNodes.subList(i, sliceEnd));
        slice.sort(Comparator.comparingDouble(Node::centerY));

        for (int j = 0; j < slice.size(); j += maxEntries) {
          int nodeEnd = Math.min(j + maxEntries, slice.size());
          InnerNode<T> parent = new InnerNode<>();
          for (Node<T> child : slice.subList(j, nodeEnd)) {
            parent.addChild(child);
          }
          parent.recalculateBounds();
          nextLevelNodes.add(parent);
        }
      }
      currentLevelNodes = nextLevelNodes;
    }

    return currentLevelNodes.get(0);
  }

  @Override
  public void insert(T item, IntBox bounds) {
    Objects.requireNonNull(item, "item must not be null");
    if (bounds == null || bounds.isEmpty()) {
      return;
    }

    SpatialEntry<T> newEntry = new SpatialEntry<>(item, bounds);
    if (root == null) {
      LeafNode<T> newRoot = new LeafNode<>();
      newRoot.entries.add(newEntry);
      newRoot.recalculateBounds();
      root = newRoot;
      size++;
      return;
    }

    LeafNode<T> targetLeaf = chooseLeaf(root, bounds);
    targetLeaf.entries.add(newEntry);
    size++;

    if (targetLeaf.entries.size() > maxEntries) {
      splitLeaf(targetLeaf);
    } else {
      adjustBoundsUpwards(targetLeaf);
    }
  }

  @Override
  public boolean remove(T item, IntBox bounds) {
    if (root == null || item == null || bounds == null || bounds.isEmpty()) {
      return false;
    }

    LeafNode<T> targetLeaf = findLeafWithEntry(root, item, bounds);
    if (targetLeaf == null) {
      return false;
    }

    int removeIndex = -1;
    for (int i = 0; i < targetLeaf.entries.size(); i++) {
      SpatialEntry<T> entry = targetLeaf.entries.get(i);
      if (entry.value().equals(item) && entry.bounds().equals(bounds)) {
        removeIndex = i;
        break;
      }
    }

    if (removeIndex == -1) {
      return false;
    }

    targetLeaf.entries.remove(removeIndex);
    size--;

    condenseTree(targetLeaf);

    if (root instanceof InnerNode<T> inner) {
      if (inner.children.size() == 1) {
        root = inner.children.get(0);
        root.parent = null;
      } else if (inner.children.isEmpty()) {
        root = null;
      }
    } else if (root instanceof LeafNode<T> leafRoot && leafRoot.entries.isEmpty()) {
      root = null;
    }

    return true;
  }

  @Override
  public void query(IntBox queryBox, Consumer<T> visitor) {
    query(queryBox, null, visitor);
  }

  @Override
  public void query(IntBox queryBox, Predicate<T> filter, Consumer<T> visitor) {
    if (root == null || queryBox == null || queryBox.isEmpty() || visitor == null) {
      return;
    }
    if (!root.bounds.intersects(queryBox)) {
      return;
    }
    queryRecursive(root, queryBox, filter, visitor);
  }

  private void queryRecursive(
      Node<T> current, IntBox queryBox, Predicate<T> filter, Consumer<T> visitor) {
    if (current instanceof LeafNode<T> leaf) {
      for (SpatialEntry<T> entry : leaf.entries) {
        if (entry.bounds().intersects(queryBox)) {
          if (filter == null || filter.test(entry.value())) {
            visitor.accept(entry.value());
          }
        }
      }
    } else if (current instanceof InnerNode<T> inner) {
      for (Node<T> child : inner.children) {
        if (child.bounds.intersects(queryBox)) {
          queryRecursive(child, queryBox, filter, visitor);
        }
      }
    }
  }

  @Override
  public List<T> query(IntBox queryBox) {
    List<T> results = new ArrayList<>();
    query(queryBox, results::add);
    return results;
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public boolean isEmpty() {
    return size == 0;
  }

  @Override
  public void clear() {
    root = null;
    size = 0;
  }

  @Override
  public IntBox getBounds() {
    return root != null ? root.bounds : IntBox.EMPTY;
  }

  private LeafNode<T> chooseLeaf(Node<T> current, IntBox newBounds) {
    if (current instanceof LeafNode<T> leaf) {
      return leaf;
    }

    InnerNode<T> inner = (InnerNode<T>) current;
    Node<T> bestChild = null;
    double bestEnlargement = Double.POSITIVE_INFINITY;
    double bestArea = Double.POSITIVE_INFINITY;

    for (Node<T> child : inner.children) {
      double currentArea = child.bounds.area();
      double enlargedArea = child.bounds.union(newBounds).area();
      double enlargement = enlargedArea - currentArea;

      if (enlargement < bestEnlargement) {
        bestEnlargement = enlargement;
        bestArea = currentArea;
        bestChild = child;
      } else if (Math.abs(enlargement - bestEnlargement) < 1e-9 && currentArea < bestArea) {
        bestArea = currentArea;
        bestChild = child;
      }
    }

    return chooseLeaf(bestChild, newBounds);
  }

  private void splitLeaf(LeafNode<T> leaf) {
    List<SpatialEntry<T>> allEntries = new ArrayList<>(leaf.entries);
    leaf.entries.clear();

    List<SpatialEntry<T>> group1 = new ArrayList<>();
    List<SpatialEntry<T>> group2 = new ArrayList<>();

    // Pick Seeds for quadratic split
    int seed1 = 0;
    int seed2 = 1;
    double maxWaste = Double.NEGATIVE_INFINITY;

    for (int i = 0; i < allEntries.size(); i++) {
      for (int j = i + 1; j < allEntries.size(); j++) {
        IntBox b1 = allEntries.get(i).bounds();
        IntBox b2 = allEntries.get(j).bounds();
        double waste = b1.union(b2).area() - b1.area() - b2.area();
        if (waste > maxWaste) {
          maxWaste = waste;
          seed1 = i;
          seed2 = j;
        }
      }
    }

    group1.add(allEntries.get(seed1));
    group2.add(allEntries.get(seed2));

    List<SpatialEntry<T>> remaining = new ArrayList<>(allEntries);
    // Remove higher index first to preserve lower index
    if (seed1 > seed2) {
      remaining.remove(seed1);
      remaining.remove(seed2);
    } else {
      remaining.remove(seed2);
      remaining.remove(seed1);
    }

    IntBox box1 = group1.get(0).bounds();
    IntBox box2 = group2.get(0).bounds();

    while (!remaining.isEmpty()) {
      if (group1.size() + remaining.size() == minEntries) {
        group1.addAll(remaining);
        break;
      }
      if (group2.size() + remaining.size() == minEntries) {
        group2.addAll(remaining);
        break;
      }

      int nextCandidate = 0;
      double maxDiff = Double.NEGATIVE_INFINITY;
      boolean preferGroup1 = true;

      for (int i = 0; i < remaining.size(); i++) {
        SpatialEntry<T> candidate = remaining.get(i);
        double enlargement1 = box1.union(candidate.bounds()).area() - box1.area();
        double enlargement2 = box2.union(candidate.bounds()).area() - box2.area();
        double diff = Math.abs(enlargement1 - enlargement2);

        if (diff > maxDiff) {
          maxDiff = diff;
          nextCandidate = i;
          preferGroup1 =
              enlargement1 < enlargement2
                  || (Math.abs(enlargement1 - enlargement2) < 1e-9 && box1.area() < box2.area());
        }
      }

      SpatialEntry<T> chosen = remaining.remove(nextCandidate);
      if (preferGroup1) {
        group1.add(chosen);
        box1 = box1.union(chosen.bounds());
      } else {
        group2.add(chosen);
        box2 = box2.union(chosen.bounds());
      }
    }

    leaf.entries.addAll(group1);
    leaf.recalculateBounds();

    LeafNode<T> newLeaf = new LeafNode<>();
    newLeaf.entries.addAll(group2);
    newLeaf.recalculateBounds();

    propagateSplit(leaf, newLeaf);
  }

  private void splitInner(InnerNode<T> inner) {
    List<Node<T>> allChildren = new ArrayList<>(inner.children);
    inner.children.clear();

    List<Node<T>> group1 = new ArrayList<>();
    List<Node<T>> group2 = new ArrayList<>();

    int seed1 = 0;
    int seed2 = 1;
    double maxWaste = Double.NEGATIVE_INFINITY;

    for (int i = 0; i < allChildren.size(); i++) {
      for (int j = i + 1; j < allChildren.size(); j++) {
        IntBox b1 = allChildren.get(i).bounds;
        IntBox b2 = allChildren.get(j).bounds;
        double waste = b1.union(b2).area() - b1.area() - b2.area();
        if (waste > maxWaste) {
          maxWaste = waste;
          seed1 = i;
          seed2 = j;
        }
      }
    }

    group1.add(allChildren.get(seed1));
    group2.add(allChildren.get(seed2));

    List<Node<T>> remaining = new ArrayList<>(allChildren);
    if (seed1 > seed2) {
      remaining.remove(seed1);
      remaining.remove(seed2);
    } else {
      remaining.remove(seed2);
      remaining.remove(seed1);
    }

    IntBox box1 = group1.get(0).bounds;
    IntBox box2 = group2.get(0).bounds;

    while (!remaining.isEmpty()) {
      if (group1.size() + remaining.size() == minEntries) {
        group1.addAll(remaining);
        break;
      }
      if (group2.size() + remaining.size() == minEntries) {
        group2.addAll(remaining);
        break;
      }

      int nextCandidate = 0;
      double maxDiff = Double.NEGATIVE_INFINITY;
      boolean preferGroup1 = true;

      for (int i = 0; i < remaining.size(); i++) {
        Node<T> candidate = remaining.get(i);
        double enlargement1 = box1.union(candidate.bounds).area() - box1.area();
        double enlargement2 = box2.union(candidate.bounds).area() - box2.area();
        double diff = Math.abs(enlargement1 - enlargement2);

        if (diff > maxDiff) {
          maxDiff = diff;
          nextCandidate = i;
          preferGroup1 =
              enlargement1 < enlargement2
                  || (Math.abs(enlargement1 - enlargement2) < 1e-9 && box1.area() < box2.area());
        }
      }

      Node<T> chosen = remaining.remove(nextCandidate);
      if (preferGroup1) {
        group1.add(chosen);
        box1 = box1.union(chosen.bounds);
      } else {
        group2.add(chosen);
        box2 = box2.union(chosen.bounds);
      }
    }

    for (Node<T> child : group1) {
      inner.addChild(child);
    }
    inner.recalculateBounds();

    InnerNode<T> newInner = new InnerNode<>();
    for (Node<T> child : group2) {
      newInner.addChild(child);
    }
    newInner.recalculateBounds();

    propagateSplit(inner, newInner);
  }

  private void propagateSplit(Node<T> oldNode, Node<T> newNode) {
    if (oldNode == root) {
      InnerNode<T> newRoot = new InnerNode<>();
      newRoot.addChild(oldNode);
      newRoot.addChild(newNode);
      newRoot.recalculateBounds();
      root = newRoot;
    } else {
      InnerNode<T> parent = oldNode.parent;
      parent.addChild(newNode);
      if (parent.children.size() > maxEntries) {
        splitInner(parent);
      } else {
        adjustBoundsUpwards(parent);
      }
    }
  }

  private void adjustBoundsUpwards(Node<T> start) {
    Node<T> current = start;
    while (current != null) {
      current.recalculateBounds();
      current = current.parent;
    }
  }

  private LeafNode<T> findLeafWithEntry(Node<T> current, T item, IntBox bounds) {
    if (current == null || !current.bounds.intersects(bounds)) {
      return null;
    }

    if (current instanceof LeafNode<T> leaf) {
      for (SpatialEntry<T> entry : leaf.entries) {
        if (entry.value().equals(item) && entry.bounds().equals(bounds)) {
          return leaf;
        }
      }
      return null;
    }

    InnerNode<T> inner = (InnerNode<T>) current;
    for (Node<T> child : inner.children) {
      if (child.bounds.intersects(bounds)) {
        LeafNode<T> result = findLeafWithEntry(child, item, bounds);
        if (result != null) {
          return result;
        }
      }
    }
    return null;
  }

  private void condenseTree(Node<T> node) {
    List<SpatialEntry<T>> entriesToReinsert = new ArrayList<>();
    Node<T> current = node;

    while (current != root && current != null) {
      InnerNode<T> parent = current.parent;
      if (current.count() < minEntries) {
        parent.removeChild(current);
        collectLeafEntries(current, entriesToReinsert);
      } else {
        current.recalculateBounds();
      }
      current = parent;
    }

    if (root != null) {
      root.recalculateBounds();
    }

    for (SpatialEntry<T> entry : entriesToReinsert) {
      insert(entry.value(), entry.bounds());
      size--; // Balanced because insert() increments size
    }
  }

  private void collectLeafEntries(Node<T> node, List<SpatialEntry<T>> output) {
    if (node instanceof LeafNode<T> leaf) {
      output.addAll(leaf.entries);
    } else if (node instanceof InnerNode<T> inner) {
      for (Node<T> child : inner.children) {
        collectLeafEntries(child, output);
      }
    }
  }

  // --- Internal Node Hierarchy ---

  private abstract static class Node<T> {
    IntBox bounds = IntBox.EMPTY;
    InnerNode<T> parent;

    abstract boolean isLeaf();

    abstract int count();

    abstract void recalculateBounds();

    double centerX() {
      return bounds.ll.x + ((double) bounds.width()) / 2.0;
    }

    double centerY() {
      return bounds.ll.y + ((double) bounds.height()) / 2.0;
    }
  }

  private static final class LeafNode<T> extends Node<T> {
    final List<SpatialEntry<T>> entries = new ArrayList<>();

    @Override
    boolean isLeaf() {
      return true;
    }

    @Override
    int count() {
      return entries.size();
    }

    @Override
    void recalculateBounds() {
      if (entries.isEmpty()) {
        bounds = IntBox.EMPTY;
        return;
      }
      IntBox b = entries.get(0).bounds();
      for (int i = 1; i < entries.size(); i++) {
        b = b.union(entries.get(i).bounds());
      }
      bounds = b;
    }
  }

  private static final class InnerNode<T> extends Node<T> {
    final List<Node<T>> children = new ArrayList<>();

    @Override
    boolean isLeaf() {
      return false;
    }

    @Override
    int count() {
      return children.size();
    }

    void addChild(Node<T> child) {
      children.add(child);
      child.parent = this;
    }

    void removeChild(Node<T> child) {
      children.remove(child);
      child.parent = null;
    }

    @Override
    void recalculateBounds() {
      if (children.isEmpty()) {
        bounds = IntBox.EMPTY;
        return;
      }
      IntBox b = children.get(0).bounds;
      for (int i = 1; i < children.size(); i++) {
        b = b.union(children.get(i).bounds);
      }
      bounds = b;
    }
  }
}
