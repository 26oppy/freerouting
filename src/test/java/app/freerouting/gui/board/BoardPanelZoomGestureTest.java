package app.freerouting.gui.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.core.RoutingJob;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.gui.rendering.GraphicsContext;
import app.freerouting.gui.workspace.progress.ScreenMessages;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import app.freerouting.settings.GlobalSettings;
import app.freerouting.settings.SettingsMerger;
import app.freerouting.settings.sources.DefaultSettings;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.FileInputStream;
import java.util.Locale;
import javax.swing.JLabel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("gui")
class BoardPanelZoomGestureTest {

  private static BasicBoard loadBoard() throws Exception {
    BoardReadResult result;
    try (FileInputStream in =
        new FileInputStream("fixtures/Issue575-drc_dev-board_4_hole_clearance_violations.dsn")) {
      result = DsnReader.readBoard(in, null, null, "zoom-gesture-test");
    }
    return switch (result) {
      case BoardReadResult.Success s -> (BasicBoard) s.board();
      case BoardReadResult.OutlineMissing o -> (BasicBoard) o.board();
      default -> throw new IllegalStateException("Failed to read board: " + result);
    };
  }

  @Test
  void wheelZoomActivatesSimplifiedRenderingUntilDebounceFinishes() throws Exception {
    GlobalSettings globalSettings = new GlobalSettings();
    SettingsMerger merger = new SettingsMerger(new DefaultSettings());
    ScreenMessages messages =
        new ScreenMessages(
            new JLabel(),
            new JLabel(),
            new JLabel(),
            new JLabel(),
            new JLabel(),
            new JLabel(),
            new JLabel(),
            new JLabel(),
            Locale.ENGLISH);
    BoardPanel panel =
        new BoardPanel(messages, mock(BoardFrame.class), globalSettings, new RoutingJob(), merger);
    panel.setSize(new Dimension(1200, 900));

    BasicBoard board = loadBoard();
    IntBox designBounds = board.getBoundingBox();
    panel.boardHandling.graphicsContext =
        new GraphicsContext(
            designBounds, new Dimension(1200, 900), board.layerStructure, Locale.ENGLISH);

    assertFalse(panel.isZoomInteractionActive());
    assertFalse(panel.boardHandling.graphicsContext.isSimplifiedPlaneRendering());

    // Programmatic zoom (e.g. zoomAll on board load) must not activate wheel-zoom debounce state.
    panel.zoom(1.1, new Point2D.Double(600, 450));
    assertFalse(panel.isZoomInteractionActive());
    assertFalse(panel.boardHandling.graphicsContext.isSimplifiedPlaneRendering());

    // Mouse-wheel zoom must activate simplified plane rendering until debounce finishes.
    panel.zoomWithMouseWheel(new Point2D.Double(600, 450), -1);
    assertTrue(panel.isZoomInteractionActive());
    assertTrue(panel.boardHandling.graphicsContext.isSimplifiedPlaneRendering());

    panel.finishZoomInteraction();
    assertFalse(panel.isZoomInteractionActive());
    assertFalse(panel.boardHandling.graphicsContext.isSimplifiedPlaneRendering());
  }

  @Test
  void simplifiedPlaneRenderingPreservesAntialiasingForHairlinesAndOutlines() throws Exception {
    BasicBoard board = loadBoard();
    IntBox designBounds = board.getBoundingBox();
    GraphicsContext graphicsContext =
        new GraphicsContext(
            designBounds, new Dimension(800, 600), board.layerStructure, Locale.ENGLISH);

    FloatPoint[] airline =
        new FloatPoint[] {
          new FloatPoint(designBounds.ll.x, designBounds.ll.y),
          new FloatPoint(designBounds.ur.x, designBounds.ur.y)
        };

    BufferedImage normalImg = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
    Graphics2D normalGraphics = normalImg.createGraphics();
    try {
      normalGraphics.setClip(0, 0, 800, 600);
      graphicsContext.setSimplifiedPlaneRendering(false);
      graphicsContext.draw(airline, 1.0, Color.WHITE, normalGraphics, 1.0);
      assertEquals(
          RenderingHints.VALUE_ANTIALIAS_ON,
          normalGraphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING));
    } finally {
      normalGraphics.dispose();
    }

    BufferedImage simplifiedImg = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
    Graphics2D simplifiedGraphics = simplifiedImg.createGraphics();
    try {
      simplifiedGraphics.setClip(0, 0, 800, 600);
      graphicsContext.setSimplifiedPlaneRendering(true);
      graphicsContext.draw(airline, 1.0, Color.WHITE, simplifiedGraphics, 1.0);
      assertEquals(
          RenderingHints.VALUE_ANTIALIAS_ON,
          simplifiedGraphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING));
    } finally {
      graphicsContext.setSimplifiedPlaneRendering(false);
      simplifiedGraphics.dispose();
    }
  }
}
