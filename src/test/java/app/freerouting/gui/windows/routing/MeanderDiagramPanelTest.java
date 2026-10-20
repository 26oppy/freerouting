package app.freerouting.gui.windows.routing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import app.freerouting.rules.CornerStyle;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MeanderDiagramPanelTest {

  @Test
  @DisplayName("Renders single-net fillet and chamfer diagrams without exceptions")
  void rendersSingleNetDiagrams() {
    MeanderDiagramPanel panel = new MeanderDiagramPanel();
    panel.setSize(400, 200);

    BufferedImage img = new BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2 = img.createGraphics();

    assertDoesNotThrow(
        () -> {
          panel.updateParameters(CornerStyle.FILLET, 1.2, 0.6, 80.0, false, false);
          panel.paintComponent(g2);

          panel.updateParameters(CornerStyle.CHAMFER, 0.8, 0.5, 50.0, true, false);
          panel.paintComponent(g2);
        });

    g2.dispose();
  }

  @Test
  @DisplayName("Renders differential pair skew bump diagram without exceptions")
  void rendersDiffPairDiagram() {
    MeanderDiagramPanel panel = new MeanderDiagramPanel();
    panel.setSize(400, 200);

    BufferedImage img = new BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2 = img.createGraphics();

    assertDoesNotThrow(
        () -> {
          panel.updateParameters(CornerStyle.FILLET, 0.5, 0.6, 70.0, true, true);
          panel.paintComponent(g2);

          panel.updateParameters(CornerStyle.CHAMFER, 0.5, 0.6, 40.0, true, true);
          panel.paintComponent(g2);
        });

    g2.dispose();
  }
}
