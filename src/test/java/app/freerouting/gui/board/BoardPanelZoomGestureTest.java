package app.freerouting.gui.board;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import app.freerouting.core.RoutingJob;
import app.freerouting.gui.a11y.GuiA11yHarness;
import app.freerouting.gui.rendering.GraphicsContext;
import app.freerouting.gui.workspace.progress.ScreenMessages;
import app.freerouting.settings.GlobalSettings;
import app.freerouting.settings.SettingsMerger;
import java.awt.Point;
import java.awt.geom.Point2D;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Tests mouse-wheel zoom gesture debouncing and simplified plane rendering state transitions. */
@Tag("gui")
class BoardPanelZoomGestureTest {

  @Test
  void zoomWithMouseWheelActivatesSimplifiedPlaneRenderingAndDebounces() {
    GuiA11yHarness.onEdt(
        () -> {
          ScreenMessages screenMessages = mock(ScreenMessages.class);
          BoardFrame boardFrame = mock(BoardFrame.class);

          GlobalSettings globalSettings = new GlobalSettings();
          globalSettings.currentLocale = Locale.ENGLISH;
          RoutingJob routingJob = new RoutingJob(UUID.randomUUID());
          SettingsMerger settingsMerger = new SettingsMerger();

          BoardPanel panel =
              new BoardPanel(
                  screenMessages, boardFrame, globalSettings, routingJob, settingsMerger);

          GraphicsContext graphicsContext = mock(GraphicsContext.class);
          panel.boardHandling.graphicsContext = graphicsContext;

          assertFalse(panel.isZoomGestureActive(), "Zoom gesture should be inactive initially");

          // Simulate mouse wheel zoom in
          Point2D center = new Point(400, 300);
          panel.zoomWithMouseWheel(center, -1);

          assertTrue(panel.isZoomGestureActive(), "Zoom gesture should be active on wheel event");
          org.mockito.Mockito.verify(graphicsContext, org.mockito.Mockito.atLeastOnce())
              .setSimplifiedPlaneRendering(true);

          // Second wheel event keeps gesture active
          panel.zoomWithMouseWheel(center, -1);
          assertTrue(
              panel.isZoomGestureActive(),
              "Zoom gesture should remain active on second wheel event");

          // Stop timer to simulate gesture completion
          panel.resetBoardHandling(routingJob);
          assertFalse(
              panel.isZoomGestureActive(),
              "Zoom gesture should be inactive after resetBoardHandling");
        });
  }
}
