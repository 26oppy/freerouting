package app.freerouting.datastructures.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.geometry.planar.IntBox;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RTreeTest {

  @Test
  void emptyTreeHasExpectedProperties() {
    RTree<String> tree = new RTree<>();
    assertEquals(0, tree.size());
    assertTrue(tree.isEmpty());
    assertEquals(IntBox.EMPTY, tree.getBounds());
    assertTrue(tree.query(new IntBox(0, 0, 100, 100)).isEmpty());
    assertFalse(tree.remove("none", new IntBox(0, 0, 10, 10)));
  }

  @Test
  void singleItemInsertAndQuery() {
    RTree<String> tree = new RTree<>();
    IntBox box = new IntBox(10, 10, 50, 50);
    tree.insert("item1", box);

    assertEquals(1, tree.size());
    assertFalse(tree.isEmpty());
    assertEquals(box, tree.getBounds());

    // Intersecting query
    List<String> hits = tree.query(new IntBox(20, 20, 30, 30));
    assertEquals(1, hits.size());
    assertEquals("item1", hits.get(0));

    // Non-intersecting query
    assertTrue(tree.query(new IntBox(100, 100, 200, 200)).isEmpty());
  }

  @Test
  void insertSplitsLeafAndInnerNodesCorrectly() {
    // maxEntries=4, minEntries=2 to force splits early
    RTree<Integer> tree = new RTree<>(4, 2);

    for (int i = 0; i < 25; i++) {
      IntBox box = new IntBox(i * 10, i * 10, i * 10 + 5, i * 10 + 5);
      tree.insert(i, box);
    }

    assertEquals(25, tree.size());

    // Query covering first 3 items: i=0, 1, 2
    List<Integer> hits = tree.query(new IntBox(0, 0, 25, 25));
    assertEquals(Set.of(0, 1, 2), new HashSet<>(hits));

    // Query covering all
    List<Integer> all = tree.query(new IntBox(0, 0, 1000, 1000));
    assertEquals(25, all.size());
  }

  @Test
  void queryWithFilterPredicate() {
    RTree<Integer> tree = new RTree<>();
    for (int i = 0; i < 50; i++) {
      tree.insert(i, new IntBox(i * 10, 0, i * 10 + 8, 10));
    }

    List<Integer> evenHits = new ArrayList<>();
    tree.query(new IntBox(0, 0, 500, 10), val -> val % 2 == 0, evenHits::add);

    assertEquals(25, evenHits.size());
    for (int val : evenHits) {
      assertTrue(val % 2 == 0);
    }
  }

  @Test
  void removeItemsAndCondenseTree() {
    RTree<String> tree = new RTree<>(4, 2);
    List<SpatialEntry<String>> entries = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      SpatialEntry<String> e =
          new SpatialEntry<>("item_" + i, new IntBox(i * 10, i * 10, i * 10 + 8, i * 10 + 8));
      entries.add(e);
      tree.insert(e.value(), e.bounds());
    }

    assertEquals(20, tree.size());

    // Remove half of the items
    for (int i = 0; i < 10; i++) {
      SpatialEntry<String> e = entries.get(i);
      boolean removed = tree.remove(e.value(), e.bounds());
      assertTrue(removed, "item should be successfully removed: " + e.value());
    }

    assertEquals(10, tree.size());

    // Verify removed items are no longer returned
    for (int i = 0; i < 10; i++) {
      SpatialEntry<String> e = entries.get(i);
      assertTrue(tree.query(e.bounds()).isEmpty());
    }

    // Verify remaining items are still intact
    for (int i = 10; i < 20; i++) {
      SpatialEntry<String> e = entries.get(i);
      List<String> hits = tree.query(e.bounds());
      assertEquals(1, hits.size());
      assertEquals(e.value(), hits.get(0));
    }
  }

  @Test
  void bulkLoadProducesIdenticalQueryResultsAsDynamicInsert() {
    List<SpatialEntry<Integer>> entries = new ArrayList<>();
    RTree<Integer> dynamicTree = new RTree<>(8, 3);

    for (int i = 0; i < 100; i++) {
      int x = (i % 10) * 50;
      int y = (i / 10) * 50;
      IntBox box = new IntBox(x, y, x + 20, y + 20);
      SpatialEntry<Integer> entry = new SpatialEntry<>(i, box);
      entries.add(entry);
      dynamicTree.insert(i, box);
    }

    RTree<Integer> bulkTree = RTree.bulkLoad(entries, 8, 3);

    assertEquals(100, bulkTree.size());
    assertEquals(dynamicTree.size(), bulkTree.size());

    // Test across several overlapping query boxes
    for (int q = 0; q < 50; q++) {
      int qx = (q % 7) * 60;
      int qy = (q / 7) * 60;
      IntBox queryBox = new IntBox(qx, qy, qx + 70, qy + 70);

      Set<Integer> dynamicHits = new HashSet<>(dynamicTree.query(queryBox));
      Set<Integer> bulkHits = new HashSet<>(bulkTree.query(queryBox));

      assertEquals(
          dynamicHits, bulkHits, "bulk loaded tree must match dynamic tree for box: " + queryBox);
    }
  }

  @Test
  void randomizedStressTestMatchesBruteForceLinearScan() {
    Random rand = new Random(42);
    int itemCount = 1000;
    int queryCount = 200;

    List<SpatialEntry<Integer>> allEntries = new ArrayList<>(itemCount);
    RTree<Integer> tree = new RTree<>(16, 4);

    for (int i = 0; i < itemCount; i++) {
      int x1 = rand.nextInt(10_000);
      int y1 = rand.nextInt(10_000);
      int w = 1 + rand.nextInt(200);
      int h = 1 + rand.nextInt(200);
      IntBox box = new IntBox(x1, y1, x1 + w, y1 + h);
      allEntries.add(new SpatialEntry<>(i, box));
      tree.insert(i, box);
    }

    assertEquals(itemCount, tree.size());

    // Verify against brute-force linear scan
    for (int q = 0; q < queryCount; q++) {
      int qx = rand.nextInt(10_000);
      int qy = rand.nextInt(10_000);
      int qw = rand.nextInt(1000);
      int qh = rand.nextInt(1000);
      IntBox queryBox = new IntBox(qx, qy, qx + qw, qy + qh);

      // Brute-force ground truth
      Set<Integer> expected = new HashSet<>();
      for (SpatialEntry<Integer> entry : allEntries) {
        if (entry.bounds().intersects(queryBox)) {
          expected.add(entry.value());
        }
      }

      Set<Integer> actual = new HashSet<>(tree.query(queryBox));
      assertEquals(expected, actual, "R-Tree query must match brute-force ground truth");
    }
  }

  @Test
  void invalidParametersThrowExpectedExceptions() {
    assertThrows(IllegalArgumentException.class, () -> new RTree<String>(3, 1));
    assertThrows(IllegalArgumentException.class, () -> new RTree<String>(8, 1));
    assertThrows(IllegalArgumentException.class, () -> new RTree<String>(8, 5));
  }
}
