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
    return loadBoard("fixtures/" + FIXTURE);
  }

  private static BasicBoard loadBoard(String path) throws Exception {
    BoardReadResult result;
    try (FileInputStream in = new FileInputStream(path)) {
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

  @Test
  void benchmarkPCBenchBoards() throws Exception {
    String[] fixtures = {
      "scripts/benchmark/fixtures/PCBench/oskirby_logicbone/reference-routed.dsn",
      "scripts/benchmark/fixtures/PCBench/kitspace_EEZ%20DIB%20MCU%20r1B2/reference-routed.dsn",
      "scripts/benchmark/fixtures/PCBench/Aleste-520EX_aleste/reference-routed.dsn",
      "scripts/benchmark/fixtures/PCBench/LeeChee_1800/reference-routed.dsn"
    };

    System.out.printf(
        Locale.US,
        "%n============================= PCBENCH CANVAS BENCHMARK =============================%n");
    System.out.printf(
        Locale.US,
        "%-30s | %-6s | %-10s | %-10s | %-10s | %-10s | %-10s%n",
        "Board Name",
        "Items",
        "FirstPaint",
        "ZoomOut",
        "ZoomIn",
        "Pan(Manip)",
        "ManualEdit");
    System.out.printf(
        Locale.US,
        "-------------------------------+--------+------------+------------+------------+------------+-----------%n");

    for (String fixturePath : fixtures) {
      java.io.File file = new java.io.File(fixturePath);
      if (!file.exists()) {
        continue;
      }

      // 1. Measure Cold First Paint (board read + initial draw)
      long coldStart = System.nanoTime();
      BasicBoard board = loadBoard(fixturePath);
      IntBox designBounds = board.getBoundingBox();
      GraphicsContext gc =
          new GraphicsContext(
              designBounds,
              new Dimension(IMAGE_WIDTH, IMAGE_HEIGHT),
              board.layerStructure,
              Locale.ENGLISH);
      BufferedImage img = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
      Graphics2D gCold = img.createGraphics();
      try {
        gCold.setClip(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
        BoardRenderer.draw(board, gCold, gc);
      } finally {
        gCold.dispose();
      }
      double coldFirstPaintMs = (System.nanoTime() - coldStart) / 1_000_000.0;

      // 2. Measure Zoomed-Out Redraw (full board in viewport)
      int iters = 10;
      long startZoomOut = System.nanoTime();
      for (int i = 0; i < iters; i++) {
        Graphics2D g = img.createGraphics();
        try {
          g.setClip(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
          BoardRenderer.draw(board, g, gc);
        } finally {
          g.dispose();
        }
      }
      double zoomOutMs = ((System.nanoTime() - startZoomOut) / 1_000_000.0) / iters;

      // 3. Measure Zoomed-In Redraw (10% sub-region in center)
      int cx = (designBounds.ll.x + designBounds.ur.x) / 2;
      int cy = (designBounds.ll.y + designBounds.ur.y) / 2;
      int hw = designBounds.width() / 20;
      int hh = designBounds.height() / 20;
      GraphicsContext zoomedGc =
          new GraphicsContext(
              new IntBox(cx - hw, cy - hh, cx + hw, cy + hh),
              new Dimension(IMAGE_WIDTH, IMAGE_HEIGHT),
              board.layerStructure,
              Locale.ENGLISH);

      long startZoomIn = System.nanoTime();
      for (int i = 0; i < iters; i++) {
        Graphics2D g = img.createGraphics();
        try {
          g.setClip(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
          BoardRenderer.draw(board, g, zoomedGc);
        } finally {
          g.dispose();
        }
      }
      double zoomInMs = ((System.nanoTime() - startZoomIn) / 1_000_000.0) / iters;

      // 4. Measure Panning / Manipulation (viewport translated with simplified planes active)
      zoomedGc.setSimplifiedPlaneRendering(true);
      int panSteps = 20;
      long startPan = System.nanoTime();
      for (int i = 0; i < panSteps; i++) {
        Graphics2D g = img.createGraphics();
        try {
          g.setClip(i * 10, i * 5, IMAGE_WIDTH - 200, IMAGE_HEIGHT - 200);
          BoardRenderer.draw(board, g, zoomedGc);
        } finally {
          g.dispose();
        }
      }
      double panMs = ((System.nanoTime() - startPan) / 1_000_000.0) / panSteps;
      zoomedGc.setSimplifiedPlaneRendering(false);

      // 5. Measure Manual Edit / Routing (revision changes on every frame, simplified planes)
      zoomedGc.setSimplifiedPlaneRendering(true);
      long startEdit = System.nanoTime();
      for (int i = 0; i < iters; i++) {
        board.incrementRevision();
        Graphics2D g = img.createGraphics();
        try {
          g.setClip(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
          BoardRenderer.draw(board, g, zoomedGc);
        } finally {
          g.dispose();
        }
      }
      double editMs = ((System.nanoTime() - startEdit) / 1_000_000.0) / iters;

      String boardName = file.getParentFile().getName();
      System.out.printf(
          Locale.US,
          "%-30s | %6d | %9.1f ms | %9.2f ms | %9.2f ms | %9.2f ms | %8.2f ms%n",
          boardName,
          board.getItems().size(),
          coldFirstPaintMs,
          zoomOutMs,
          zoomInMs,
          panMs,
          editMs);
    }
    System.out.printf(
        Locale.US,
        "=====================================================================================%n%n");
  }

  @Test
  void benchmarkPanningSingleVariable() throws Exception {
    String fixturePath =
        "scripts/benchmark/fixtures/PCBench/kitspace_EEZ%20DIB%20MCU%20r1B2/reference-routed.dsn";
    java.io.File file = new java.io.File(fixturePath);
    if (!file.exists()) {
      return;
    }
    BasicBoard board = loadBoard(fixturePath);
    IntBox designBounds = board.getBoundingBox();

    // Simulate 3x zoom level: panel size is 3072x2304, viewport is 1024x768
    Dimension panelSize = new Dimension(3072, 2304);
    GraphicsContext gc =
        new GraphicsContext(designBounds, panelSize, board.layerStructure, Locale.ENGLISH);

    BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);

    // Warm-up renderer & caches
    Graphics2D gWarm = image.createGraphics();
    try {
      gWarm.setClip(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
      BoardRenderer.draw(board, gWarm, gc);
    } finally {
      gWarm.dispose();
    }

    int steps = 20;
    int stepDx = 50;
    int stepDy = 30;

    // Test 1: Standard panning (current BoardRenderer.draw with detailed cached fill)
    gc.setSimplifiedPlaneRendering(false);
    long t1Start = System.nanoTime();
    for (int i = 0; i < steps; i++) {
      int viewX = 200 + i * stepDx;
      int viewY = 200 + i * stepDy;
      Graphics2D g = image.createGraphics();
      try {
        g.setClip(viewX, viewY, IMAGE_WIDTH, IMAGE_HEIGHT);
        BoardRenderer.draw(board, g, gc);
      } finally {
        g.dispose();
      }
    }
    double t1Ms = ((System.nanoTime() - t1Start) / 1_000_000.0) / steps;

    // Test 2: Panning with simplifiedPlaneRendering = true
    gc.setSimplifiedPlaneRendering(true);
    long t2Start = System.nanoTime();
    for (int i = 0; i < steps; i++) {
      int viewX = 200 + i * stepDx;
      int viewY = 200 + i * stepDy;
      Graphics2D g = image.createGraphics();
      try {
        g.setClip(viewX, viewY, IMAGE_WIDTH, IMAGE_HEIGHT);
        BoardRenderer.draw(board, g, gc);
      } finally {
        g.dispose();
      }
    }
    double t2Ms = ((System.nanoTime() - t2Start) / 1_000_000.0) / steps;

    // Test 3: Spatial Index Query alone for each pan frame (no rendering)
    ItemSpatialIndex spatialIndex = ItemSpatialIndex.get(board);
    spatialIndex.updateIfStale();
    long t3Start = System.nanoTime();
    int queryItemCount = 0;
    for (int i = 0; i < steps; i++) {
      int viewX = 200 + i * stepDx;
      int viewY = 200 + i * stepDy;
      Rectangle clipRect = new Rectangle(viewX, viewY, IMAGE_WIDTH, IMAGE_HEIGHT);
      IntBox clipBox = gc.coordinateTransform.screenToBoard(clipRect);
      List<Item> p1 = spatialIndex.query(BoardRenderer.MIN_DRAW_PRIORITY, clipBox);
      List<Item> p3 = spatialIndex.query(BoardRenderer.MAX_DRAW_PRIORITY, clipBox);
      queryItemCount += p1.size() + p3.size();
    }
    double t3Ms = ((System.nanoTime() - t3Start) / 1_000_000.0) / steps;
    int avgQueriedItems = queryItemCount / steps;

    // Test 4: Panning with only Traces & Vias (MAX_DRAW_PRIORITY only, no planes, no labels)
    long t4Start = System.nanoTime();
    for (int i = 0; i < steps; i++) {
      int viewX = 200 + i * stepDx;
      int viewY = 200 + i * stepDy;
      Rectangle clipRect = new Rectangle(viewX, viewY, IMAGE_WIDTH, IMAGE_HEIGHT);
      IntBox clipBox = gc.coordinateTransform.screenToBoard(clipRect);
      List<Item> p3 = spatialIndex.query(BoardRenderer.MAX_DRAW_PRIORITY, clipBox);
      Graphics2D g = image.createGraphics();
      try {
        g.setClip(viewX, viewY, IMAGE_WIDTH, IMAGE_HEIGHT);
        for (Item item : p3) {
          BoardRenderer.drawOverlayItem(item, g, gc);
        }
      } finally {
        g.dispose();
      }
    }
    double t4Ms = ((System.nanoTime() - t4Start) / 1_000_000.0) / steps;

    System.out.printf(
        Locale.US,
        "%n=== SINGLE-VARIABLE PANNING BENCHMARK on [%s] ===%n"
            + "Total board items: %d%n"
            + "Average visible items in viewport: %d%n"
            + "1. Standard Full Render (detailed planes): %.2f ms / frame (%.1f FPS)%n"
            + "2. Simplified Plane Render:              %.2f ms / frame (%.1f FPS)%n"
            + "3. Spatial Index Query Time alone:         %.3f ms / frame%n"
            + "4. Traces & Vias only (no planes/labels):  %.2f ms / frame (%.1f FPS)%n"
            + "========================================================%n%n",
        file.getName(),
        board.getItems().size(),
        avgQueriedItems,
        t1Ms,
        1000.0 / t1Ms,
        t2Ms,
        1000.0 / t2Ms,
        t3Ms,
        t4Ms,
        1000.0 / t4Ms);
  }

  @Test
  void benchmarkCopperPlaneFillTechniques() throws Exception {
    String fixturePath =
        "scripts/benchmark/fixtures/PCBench/kitspace_EEZ%20DIB%20MCU%20r1B2/reference-routed.dsn";
    java.io.File file = new java.io.File(fixturePath);
    if (!file.exists()) {
      return;
    }
    BasicBoard board = loadBoard(fixturePath);
    IntBox designBounds = board.getBoundingBox();
    GraphicsContext gc =
        new GraphicsContext(
            designBounds,
            new Dimension(IMAGE_WIDTH, IMAGE_HEIGHT),
            board.layerStructure,
            Locale.ENGLISH);

    // Warm-up and populate fill caches on all conduction areas
    BufferedImage dummyImg =
        new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    Graphics2D gWarm = dummyImg.createGraphics();
    try {
      gWarm.setClip(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
      BoardRenderer.draw(board, gWarm, gc);
    } finally {
      gWarm.dispose();
    }

    List<app.freerouting.board.model.items.ConductionArea> planes =
        board.getItems().stream()
            .filter(app.freerouting.board.model.items.ConductionArea.class::isInstance)
            .map(app.freerouting.board.model.items.ConductionArea.class::cast)
            .filter(app.freerouting.board.model.items.ConductionArea::getIsFilled)
            .toList();

    var p0 = gc.coordinateTransform.boardToScreen(app.freerouting.geometry.planar.FloatPoint.ZERO);
    var px =
        gc.coordinateTransform.boardToScreen(new app.freerouting.geometry.planar.FloatPoint(1, 0));
    var py =
        gc.coordinateTransform.boardToScreen(new app.freerouting.geometry.planar.FloatPoint(0, 1));
    var boardToScreen =
        new java.awt.geom.AffineTransform(
            px.getX() - p0.getX(),
            px.getY() - p0.getY(),
            py.getX() - p0.getX(),
            py.getY() - p0.getY(),
            p0.getX(),
            p0.getY());

    int iters = 30;

    // Technique A: Area.createTransformedArea() + g.fill()
    BufferedImage imgA = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    long tA = System.nanoTime();
    for (int it = 0; it < iters; it++) {
      Graphics2D g = imgA.createGraphics();
      try {
        g.setClip(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        for (var plane : planes) {
          java.awt.geom.Area cached = plane.getCachedDetailedFillArea();
          if (cached != null && !cached.isEmpty()) {
            java.awt.geom.Area screen = cached.createTransformedArea(boardToScreen);
            g.setColor(java.awt.Color.RED);
            g.fill(screen);
          }
        }
      } finally {
        g.dispose();
      }
    }
    double msA = ((System.nanoTime() - tA) / 1_000_000.0) / iters;

    // Technique B: g.transform(boardToScreen) + g.fill(cached)
    BufferedImage imgB = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    long tB = System.nanoTime();
    for (int it = 0; it < iters; it++) {
      Graphics2D g = imgB.createGraphics();
      try {
        g.setClip(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        for (var plane : planes) {
          java.awt.geom.Area cached = plane.getCachedDetailedFillArea();
          if (cached != null && !cached.isEmpty()) {
            Graphics2D gSub = (Graphics2D) g.create();
            try {
              gSub.transform(boardToScreen);
              gSub.setColor(java.awt.Color.RED);
              gSub.fill(cached);
            } finally {
              gSub.dispose();
            }
          }
        }
      } finally {
        g.dispose();
      }
    }
    double msB = ((System.nanoTime() - tB) / 1_000_000.0) / iters;

    // Technique C: gc.fillArea(plane.getArea()) [simplified solid fill]
    BufferedImage imgC = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    long tC = System.nanoTime();
    for (int it = 0; it < iters; it++) {
      Graphics2D g = imgC.createGraphics();
      try {
        g.setClip(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
        for (var plane : planes) {
          gc.fillArea(plane.getArea(), g, java.awt.Color.RED, 1.0);
        }
      } finally {
        g.dispose();
      }
    }
    double msC = ((System.nanoTime() - tC) / 1_000_000.0) / iters;

    // Verify visual fidelity between Technique A and Technique B
    int[] pxA = imgA.getRGB(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT, null, 0, IMAGE_WIDTH);
    int[] pxB = imgB.getRGB(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT, null, 0, IMAGE_WIDTH);
    int diffCount = 0;
    for (int i = 0; i < pxA.length; i++) {
      if (pxA[i] != pxB[i]) {
        diffCount++;
      }
    }
    double diffPct = (diffCount * 100.0) / pxA.length;

    System.out.printf(
        Locale.US,
        "%n=== COPPER POUR FILL TECHNIQUES (%d zones) ===%n"
            + "Technique A (Area.createTransformedArea): %.2f ms / frame%n"
            + "Technique B (g2d.transform directly):    %.2f ms / frame (%.1fx faster)%n"
            + "Technique C (solid polygon fill):        %.2f ms / frame (%.1fx faster)%n"
            + "Pixel mismatch between A and B:           %d px (%.3f%%)%n"
            + "==============================================%n%n",
        planes.size(),
        msA,
        msB,
        msA / msB,
        msC,
        msA / msC,
        diffCount,
        diffPct);

    assertTrue(
        diffPct < 0.1,
        "Technique B must visually match Technique A (<0.1% subpixel antialias diff)");
  }
}
