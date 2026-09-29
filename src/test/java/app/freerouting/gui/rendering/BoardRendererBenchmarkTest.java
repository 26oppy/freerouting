package app.freerouting.gui.rendering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Headless benchmarks and regression tests for spatial indexing and viewport culling in {@link
 * BoardRenderer}.
 */
@Tag("gui")
class BoardRendererBenchmarkTest {

  private static final String FIXTURE = "Issue575-drc_dev-board_4_hole_clearance_violations.dsn";
  private static final int IMAGE_WIDTH = 1024;
  private static final int IMAGE_HEIGHT = 768;

  private static BasicBoard loadBoard() throws Exception {
    BoardReadResult result;
    try (FileInputStream in = new FileInputStream("fixtures/" + FIXTURE)) {
      result = DsnReader.readBoard(in, null, null, "benchmark-test");
    }
    return switch (result) {
      case BoardReadResult.Success s -> (BasicBoard) s.board();
      case BoardReadResult.OutlineMissing o -> (BasicBoard) o.board();
      default -> throw new IllegalStateException("Failed to read board: " + result);
    };
  }

  @Test
  void spatialIndexCachesCorrectlyAcrossRevisions() throws Exception {
    BasicBoard board = loadBoard();
    ItemSpatialIndex index = ItemSpatialIndex.get(board);
    assertNotNull(index, "ItemSpatialIndex must be instantiated");

    index.updateIfStale();
    int initialRevision = board.getRevision();

    // Query all items
    List<Item> initialP3 = index.query(BoardRenderer.MAX_DRAW_PRIORITY, null);
    assertFalse(initialP3.isEmpty(), "Board should have priority 3 items");

    // Calling updateIfStale again with same revision should be a no-op
    index.updateIfStale();
    assertEquals(initialP3.size(), index.query(BoardRenderer.MAX_DRAW_PRIORITY, null).size());

    // Incremented revision triggers rebuild
    board.incrementRevision();
    index.updateIfStale();
    assertEquals(initialP3.size(), index.query(BoardRenderer.MAX_DRAW_PRIORITY, null).size());
  }

  @Test
  void zoomedInViewportReturnsSubsetOfItems() throws Exception {
    BasicBoard board = loadBoard();
    ItemSpatialIndex index = ItemSpatialIndex.get(board);
    index.updateIfStale();

    IntBox fullBounds = board.getBoundingBox();
    List<Item> allP3 = index.query(BoardRenderer.MAX_DRAW_PRIORITY, fullBounds);

    // Create a small 10% sub-region box in the center
    int centerX = (fullBounds.ll.x + fullBounds.ur.x) / 2;
    int centerY = (fullBounds.ll.y + fullBounds.ur.y) / 2;
    int halfW = fullBounds.width() / 20;
    int halfH = fullBounds.height() / 20;
    IntBox subRegion =
        new IntBox(centerX - halfW, centerY - halfH, centerX + halfW, centerY + halfH);

    List<Item> subRegionP3 = index.query(BoardRenderer.MAX_DRAW_PRIORITY, subRegion);

    assertTrue(
        subRegionP3.size() < allP3.size(),
        "Sub-region query must return fewer items than full board (sub: "
            + subRegionP3.size()
            + " vs all: "
            + allP3.size()
            + ")");

    // Every item returned in the subregion must intersect the subregion bounding box
    for (Item item : subRegionP3) {
      assertTrue(
          subRegion.intersects(item.boundingBox()),
          "Queried item bounding box must intersect the query box");
    }
  }

  @Test
  void rendersOffscreenImageCorrectlyWithSpatialIndex() throws Exception {
    BasicBoard board = loadBoard();
    IntBox designBounds = board.getBoundingBox();
    GraphicsContext graphicsContext =
        new GraphicsContext(
            designBounds,
            new Dimension(IMAGE_WIDTH, IMAGE_HEIGHT),
            board.layerStructure,
            Locale.ENGLISH);

    BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setClip(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
      BoardRenderer.draw(board, graphics, graphicsContext);
    } finally {
      graphics.dispose();
    }

    int[] pixels = image.getRGB(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT, null, 0, IMAGE_WIDTH);
    long nonTransparentPixels =
        Arrays.stream(pixels).filter(pixel -> ((pixel >>> 24) & 0xFF) != 0).count();
    assertTrue(
        nonTransparentPixels > 1000,
        "Rendered image must contain rendered traces and board features");
  }

  @Test
  void benchmarkRenderFrameLatency() throws Exception {
    BasicBoard board = loadBoard();
    IntBox designBounds = board.getBoundingBox();
    GraphicsContext graphicsContext =
        new GraphicsContext(
            designBounds,
            new Dimension(IMAGE_WIDTH, IMAGE_HEIGHT),
            board.layerStructure,
            Locale.ENGLISH);

    BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);

    // Warm-up
    for (int i = 0; i < 5; i++) {
      Graphics2D graphics = image.createGraphics();
      try {
        graphics.setClip(new Rectangle(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT));
        BoardRenderer.draw(board, graphics, graphicsContext);
      } finally {
        graphics.dispose();
      }
    }

    // Benchmark 20 frames
    long start = System.nanoTime();
    int iterations = 20;
    for (int i = 0; i < iterations; i++) {
      Graphics2D graphics = image.createGraphics();
      try {
        // Zoomed-in clip
        graphics.setClip(new Rectangle(200, 200, 400, 300));
        BoardRenderer.draw(board, graphics, graphicsContext);
      } finally {
        graphics.dispose();
      }
    }
    long elapsed = System.nanoTime() - start;
    double msPerFrame = (elapsed / 1_000_000.0) / iterations;

    assertTrue(
        msPerFrame < 50.0,
        "Zoomed-in frame render time must be under 50ms (was " + msPerFrame + "ms)");
  }
}
