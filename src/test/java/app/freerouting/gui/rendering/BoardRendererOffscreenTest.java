package app.freerouting.gui.rendering;

import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.autoroute.AutorouteDiagnostic;
import app.freerouting.board.facade.BasicBoard;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.TileShape;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Early Phase 6 renderer smoke test.
 *
 * <p>The test is component-only and runs in the forced-headless {@code testGui} task. It verifies
 * that the GUI renderer entry point can render a representative board into an offscreen image
 * before any board paint APIs are removed.
 */
@Tag("gui")
class BoardRendererOffscreenTest {

  private static final String FIXTURE = "Issue575-drc_dev-board_4_hole_clearance_violations.dsn";
  private static final String PLANE_FIXTURE = "Issue754-avionics_hub.dsn";
  private static final int IMAGE_WIDTH = 800;
  private static final int IMAGE_HEIGHT = 600;

  private static BasicBoard loadBoard() throws Exception {
    return loadBoard(FIXTURE);
  }

  private static BasicBoard loadBoard(String fixture) throws Exception {
    BoardReadResult result;
    try (FileInputStream in = new FileInputStream("fixtures/" + fixture)) {
      result = DsnReader.readBoard(in, null, null, "phase6-renderer");
    }
    return switch (result) {
      case BoardReadResult.Success s -> (BasicBoard) s.board();
      case BoardReadResult.OutlineMissing o -> (BasicBoard) o.board();
      default -> throw new IllegalStateException("Failed to read board: " + result);
    };
  }

  @Test
  void rendersRepresentativeBoardIntoOffscreenImage() throws Exception {
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
        nonTransparentPixels > 0,
        "renderer must draw at least one visible pixel into the offscreen image");
  }

  @Test
  void rendersConductionAreaDetailedAndSimplifiedModes() throws Exception {
    BasicBoard board = loadBoard(PLANE_FIXTURE);
    IntBox designBounds = board.getBoundingBox();
    GraphicsContext graphicsContext =
        new GraphicsContext(
            designBounds,
            new Dimension(IMAGE_WIDTH * 3, IMAGE_HEIGHT * 3),
            board.layerStructure,
            Locale.ENGLISH);

    BufferedImage detailedImage =
        new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    Graphics2D detailedGraphics = detailedImage.createGraphics();
    java.awt.geom.AffineTransform expectedTransform;
    java.awt.geom.AffineTransform actualTransform;
    try {
      detailedGraphics.translate(-IMAGE_WIDTH, -IMAGE_HEIGHT);
      detailedGraphics.setClip(IMAGE_WIDTH, IMAGE_HEIGHT, IMAGE_WIDTH, IMAGE_HEIGHT);
      expectedTransform = detailedGraphics.getTransform();
      BoardRenderer.draw(board, detailedGraphics, graphicsContext);
      actualTransform = detailedGraphics.getTransform();
    } finally {
      detailedGraphics.dispose();
    }

    assertTrue(
        expectedTransform.equals(actualTransform),
        "BoardRenderer must restore Graphics2D transform after rendering detailed conduction areas");
    int[] detailedPixels =
        detailedImage.getRGB(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT, null, 0, IMAGE_WIDTH);
    long detailedVisible =
        Arrays.stream(detailedPixels).filter(pixel -> ((pixel >>> 24) & 0xFF) != 0).count();
    assertTrue(
        detailedVisible > 0,
        "zoomed detailed conduction area render must draw visible pixels into the offscreen image");

    BufferedImage simplifiedImage =
        new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    Graphics2D simplifiedGraphics = simplifiedImage.createGraphics();
    try {
      simplifiedGraphics.translate(-IMAGE_WIDTH, -IMAGE_HEIGHT);
      simplifiedGraphics.setClip(IMAGE_WIDTH, IMAGE_HEIGHT, IMAGE_WIDTH, IMAGE_HEIGHT);
      graphicsContext.setSimplifiedPlaneRendering(true);
      BoardRenderer.draw(board, simplifiedGraphics, graphicsContext);
    } finally {
      graphicsContext.setSimplifiedPlaneRendering(false);
      simplifiedGraphics.dispose();
    }

    int[] simplifiedPixels =
        simplifiedImage.getRGB(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT, null, 0, IMAGE_WIDTH);
    long simplifiedVisible =
        Arrays.stream(simplifiedPixels).filter(pixel -> ((pixel >>> 24) & 0xFF) != 0).count();
    assertTrue(
        simplifiedVisible > 0,
        "simplified conduction area render must draw visible pixels into the offscreen image");
  }

  @Test
  void rendersAutorouteDiagnosticSnapshotIntoOffscreenImage() throws Exception {
    BasicBoard board = loadBoard();
    IntBox designBounds = board.getBoundingBox();
    GraphicsContext graphicsContext =
        new GraphicsContext(
            designBounds,
            new Dimension(IMAGE_WIDTH, IMAGE_HEIGHT),
            board.layerStructure,
            Locale.ENGLISH);
    int centerX = (designBounds.ll.x + designBounds.ur.x) / 2;
    int centerY = (designBounds.ll.y + designBounds.ur.y) / 2;
    TileShape shape =
        TileShape.getInstance(centerX - 1000, centerY - 1000, centerX + 1000, centerY + 1000);
    AutorouteDiagnostic diagnostic =
        new AutorouteDiagnostic(AutorouteDiagnostic.Kind.FREE_SPACE_ROOM, shape, 0, 1.0);
    BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);

    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setClip(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
      AutorouteDiagnosticRenderer.render(diagnostic, graphics, graphicsContext);
    } finally {
      graphics.dispose();
    }

    int[] pixels = image.getRGB(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT, null, 0, IMAGE_WIDTH);
    long nonTransparentPixels =
        Arrays.stream(pixels).filter(pixel -> ((pixel >>> 24) & 0xFF) != 0).count();
    assertTrue(
        nonTransparentPixels > 0,
        "diagnostic adapter must draw a snapshot into the offscreen image");
  }

  @Test
  void reusesUnchangedBucketsAndConductionFillAcrossUnrelatedBoardEdits() throws Exception {
    BasicBoard board = loadBoard(PLANE_FIXTURE);
    BoardSpatialIndex indexBefore = BoardSpatialIndex.getOrBuild(board);

    app.freerouting.board.model.items.ConductionArea conductionArea = null;
    for (app.freerouting.board.model.items.Item item : board.getItems()) {
      if (item instanceof app.freerouting.board.model.items.ConductionArea ca && ca.getIsFilled()) {
        conductionArea = ca;
        break;
      }
    }
    assertTrue(conductionArea != null, "fixture must contain at least one filled ConductionArea");
    java.awt.geom.Area fillBefore = conductionArea.getDetailedFillArea();
    assertTrue(fillBefore != null && !fillBefore.isEmpty(), "detailed fill area must not be empty");

    // Insert a tiny trace far outside the board bounds on a different layer (or non-overlapping)
    int otherLayer = (conductionArea.getLayer() + 1) % board.getLayerCount();
    IntBox bounds = board.getBoundingBox();
    app.freerouting.geometry.planar.Point[] points =
        new app.freerouting.geometry.planar.Point[] {
          new app.freerouting.geometry.planar.IntPoint(bounds.ur.x + 50000, bounds.ur.y + 50000),
          new app.freerouting.geometry.planar.IntPoint(bounds.ur.x + 60000, bounds.ur.y + 50000)
        };
    board.insertTrace(
        new app.freerouting.geometry.planar.Polyline(points),
        otherLayer,
        100,
        new int[] {1},
        0,
        app.freerouting.board.model.structure.FixedState.UNFIXED);

    BoardSpatialIndex indexAfter = BoardSpatialIndex.getOrBuild(board);
    assertTrue(
        indexAfter != indexBefore, "spatial index wrapper must update when board revision changes");
    assertTrue(
        indexAfter.getBucket(3, conductionArea.getLayer())
            == indexBefore.getBucket(3, conductionArea.getLayer()),
        "unmodified layer bucket must reuse its existing BucketIndex and BVH without rebuilding");
    assertTrue(
        conductionArea.getDetailedFillArea() == fillBefore,
        "ConductionArea must reuse cachedBoardFillArea when overlapping obstacles on its layer are unchanged");
  }
}
