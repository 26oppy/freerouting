package app.freerouting.gui.windows.routing;

import app.freerouting.rules.CornerStyle;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.Locale;
import javax.swing.JPanel;

/**
 * Interactive vector-rendered Swing panel that visually demonstrates the geometric configuration of
 * serpentine meanders and differential pair skew compensation bumps.
 *
 * <p>Dynamically updates when amplitude, spacing, corner radius, corner style (fillet vs chamfer),
 * or side mode are modified, providing intuitive visual feedback mirroring EDA design dialogs.
 */
public class MeanderDiagramPanel extends JPanel {

  private static final Color BG_COLOR = new Color(28, 30, 34);
  private static final Color GRID_COLOR = new Color(45, 48, 54);
  private static final Color TRACE_COLOR = new Color(230, 140, 30);
  private static final Color TRACE_PAIR_COLOR = new Color(60, 180, 110);
  private static final Color DIM_COLOR = new Color(70, 180, 240);
  private static final Color TEXT_COLOR = new Color(220, 225, 230);
  private static final Color BASELINE_COLOR = new Color(90, 95, 105);

  private CornerStyle cornerStyle = CornerStyle.FILLET;
  private double amplitudeMm = 1.0;
  private double spacingMm = 0.6;
  private double radiusPercentage = 80.0;
  private boolean singleSided = false;
  private boolean diffPairMode = false;
  private String unitString = "mm";

  /** Creates a new meander diagram preview panel. */
  public MeanderDiagramPanel() {
    setPreferredSize(new Dimension(380, 200));
    setMinimumSize(new Dimension(280, 160));
    setBackground(BG_COLOR);
  }

  /** Updates the displayed parameters and triggers a repaint. */
  public void updateParameters(
      CornerStyle style,
      double amplitude,
      double spacing,
      double radiusPct,
      boolean singleSided,
      boolean diffPairMode) {
    updateParameters(style, amplitude, spacing, radiusPct, singleSided, diffPairMode, "mm");
  }

  /** Updates the displayed parameters with active user unit and triggers a repaint. */
  public void updateParameters(
      CornerStyle style,
      double amplitude,
      double spacing,
      double radiusPct,
      boolean singleSided,
      boolean diffPairMode,
      String unit) {
    this.cornerStyle = style != null ? style : CornerStyle.FILLET;
    this.amplitudeMm = amplitude > 0 ? amplitude : 1.0;
    this.spacingMm = spacing > 0 ? spacing : 0.6;
    this.radiusPercentage = Math.max(0.0, Math.min(100.0, radiusPct));
    this.singleSided = singleSided;
    this.diffPairMode = diffPairMode;
    this.unitString = (unit != null && !unit.isBlank()) ? unit : "mm";
    repaint();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g.create();
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

      int width = getWidth();
      int height = getHeight();

      drawGrid(g2, width, height);
      drawHeaderBanner(g2, width);

      if (diffPairMode) {
        drawDiffPairDiagram(g2, width, height);
      } else {
        drawSingleNetDiagram(g2, width, height);
      }
    } finally {
      g2.dispose();
    }
  }

  private void drawHeaderBanner(Graphics2D g2, int width) {
    g2.setColor(new Color(36, 39, 45));
    g2.fillRect(0, 0, width, 26);
    g2.setColor(new Color(55, 60, 70));
    g2.drawLine(0, 26, width, 26);

    g2.setFont(new Font("SansSerif", Font.BOLD, 11));
    g2.setColor(TEXT_COLOR);
    String styleStr =
        String.format(
            Locale.US,
            "Style: %s (%.1f%% %s) | %s",
            cornerStyle.name(),
            radiusPercentage,
            cornerStyle == CornerStyle.FILLET ? "Radius" : "Bevel",
            singleSided ? "Single-Sided" : "Two-Sided");
    g2.drawString(styleStr, 12, 17);
  }

  private void drawGrid(Graphics2D g2, int width, int height) {
    g2.setColor(GRID_COLOR);
    g2.setStroke(new BasicStroke(1.0f));
    int step = 20;
    for (int x = 0; x < width; x += step) {
      g2.drawLine(x, 26, x, height);
    }
    for (int y = 26; y < height; y += step) {
      g2.drawLine(0, y, width, y);
    }
  }

  private void drawSingleNetDiagram(Graphics2D g2, int width, int height) {
    int topMargin = 32;
    int baselineY = singleSided ? height - 35 : (topMargin + height - 20) / 2;
    int maxAllowedAmp = singleSided ? baselineY - topMargin - 20 : (height - topMargin - 40) / 2;
    int ampPx = Math.max(20, Math.min(maxAllowedAmp, (int) (amplitudeMm * 35)));
    int spacingPx = Math.max(24, Math.min(60, (int) (spacingMm * 40)));

    // Draw dashed baseline
    g2.setColor(BASELINE_COLOR);
    float[] dash = {4.0f, 4.0f};
    g2.setStroke(
        new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, dash, 0.0f));
    g2.drawLine(20, baselineY, width - 20, baselineY);

    int startX = 40;
    int cycles = Math.max(2, Math.min(4, (width - 120) / (spacingPx * 2)));

    // Generate meander path
    GeneralPath path = new GeneralPath();
    path.moveTo(20, baselineY);
    path.lineTo(startX, baselineY);

    int curX = startX;
    boolean up = true;
    int bevel = (int) Math.round((spacingPx * 0.4) * (radiusPercentage / 100.0));

    for (int c = 0; c < cycles; c++) {
      int topY = up ? baselineY - ampPx : baselineY + ampPx;
      if (singleSided) {
        topY = baselineY - ampPx;
      }

      int nextX = curX + spacingPx;

      if (cornerStyle == CornerStyle.FILLET) {
        // Smooth rounded crests
        path.lineTo(curX, up ? topY + bevel : topY - bevel);
        path.quadTo(curX, topY, curX + (spacingPx / 2.0), topY);
        path.quadTo(nextX, topY, nextX, up ? topY + bevel : topY - bevel);
      } else {
        // 45° beveled chamfers
        path.lineTo(curX, up ? topY + bevel : topY - bevel);
        path.lineTo(curX + bevel, topY);
        path.lineTo(nextX - bevel, topY);
        path.lineTo(nextX, up ? topY + bevel : topY - bevel);
      }

      if (singleSided) {
        path.lineTo(nextX, baselineY);
      }
      curX = nextX;
      if (!singleSided) {
        up = !up;
      }
    }

    path.lineTo(curX, baselineY);
    path.lineTo(width - 20, baselineY);

    // Render trace
    g2.setColor(TRACE_COLOR);
    g2.setStroke(new BasicStroke(4.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g2.draw(path);

    // Dimension lines
    drawDimension(
        g2,
        startX + spacingPx,
        baselineY,
        startX + spacingPx,
        baselineY - ampPx,
        String.format(Locale.US, "A = %.2f %s", amplitudeMm, unitString),
        true);

    drawDimension(
        g2,
        startX,
        baselineY - ampPx - 10,
        startX + spacingPx,
        baselineY - ampPx - 10,
        String.format(Locale.US, "s = %.2f %s", spacingMm, unitString),
        false);
  }

  private void drawDiffPairDiagram(Graphics2D g2, int width, int height) {
    int topY = 55;
    int gap = 30;
    int botY = topY + gap;
    int bumpHeight = Math.max(22, Math.min(45, (int) (amplitudeMm * 30)));
    int bumpLen = 80;
    int startX = (width - bumpLen) / 2;

    // Positive Net (Straight)
    g2.setColor(TRACE_PAIR_COLOR);
    g2.setStroke(new BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g2.drawLine(25, topY, width - 25, topY);

    // Negative Net (with Outward Skew Bump)
    GeneralPath bump = new GeneralPath();
    bump.moveTo(25, botY);
    bump.lineTo(startX, botY);
    int bevel = (int) Math.round((bumpHeight * 0.4) * (radiusPercentage / 100.0));

    if (cornerStyle == CornerStyle.FILLET) {
      bump.quadTo(startX + (bevel / 2.0), botY + bumpHeight, startX + bevel, botY + bumpHeight);
      bump.lineTo(startX + bumpLen - bevel, botY + bumpHeight);
      bump.quadTo(startX + bumpLen - (bevel / 2.0), botY + bumpHeight, startX + bumpLen, botY);
    } else {
      bump.lineTo(startX + bevel, botY + bumpHeight);
      bump.lineTo(startX + bumpLen - bevel, botY + bumpHeight);
      bump.lineTo(startX + bumpLen, botY);
    }
    bump.lineTo(width - 25, botY);

    g2.setColor(TRACE_COLOR);
    g2.draw(bump);

    // Dimensions
    drawDimension(
        g2,
        startX + (bumpLen / 2),
        botY,
        startX + (bumpLen / 2),
        botY + bumpHeight,
        String.format(Locale.US, "Skew Bump A = %.2f %s", amplitudeMm, unitString),
        true);

    // Labels
    g2.setFont(new Font("SansSerif", Font.BOLD, 11));
    g2.setColor(TRACE_PAIR_COLOR);
    g2.drawString("Net+ (Reference trace)", 25, topY - 8);
    g2.setColor(TRACE_COLOR);
    g2.drawString("Net- (Shorter trace with skew compensation bump)", 25, botY + bumpHeight + 22);
  }

  private void drawDimension(
      Graphics2D g2, int x1, int y1, int x2, int y2, String text, boolean vertical) {
    g2.setColor(DIM_COLOR);
    g2.setStroke(new BasicStroke(1.2f));
    g2.draw(new Line2D.Double(x1, y1, x2, y2));

    int arrowSize = 4;
    g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
    FontMetrics fm = g2.getFontMetrics();
    int tw = fm.stringWidth(text);

    if (vertical) {
      int minY = Math.min(y1, y2);
      int maxY = Math.max(y1, y2);
      drawArrow(g2, x1, minY, 0, 1, arrowSize);
      drawArrow(g2, x1, maxY, 0, -1, arrowSize);

      int tx = x1 + 8;
      int ty = (minY + maxY) / 2 + 4;
      g2.setColor(new Color(28, 30, 34, 220));
      g2.fillRoundRect(tx - 3, ty - fm.getAscent() - 1, tw + 6, fm.getHeight() + 2, 4, 4);
      g2.setColor(TEXT_COLOR);
      g2.drawString(text, tx, ty);
    } else {
      int minX = Math.min(x1, x2);
      int maxX = Math.max(x1, x2);
      drawArrow(g2, minX, y1, 1, 0, arrowSize);
      drawArrow(g2, maxX, y1, -1, 0, arrowSize);

      int tx = (minX + maxX - tw) / 2;
      int ty = y1 - 4;
      g2.setColor(new Color(28, 30, 34, 220));
      g2.fillRoundRect(tx - 3, ty - fm.getAscent() - 1, tw + 6, fm.getHeight() + 2, 4, 4);
      g2.setColor(TEXT_COLOR);
      g2.drawString(text, tx, ty);
    }
  }

  private void drawArrow(Graphics2D g2, int x, int y, int dx, int dy, int size) {
    Path2D arrow = new Path2D.Double();
    arrow.moveTo(x, y);
    if (dx == 0) {
      arrow.lineTo(x - size, y + (dy * size * 2));
      arrow.lineTo(x + size, y + (dy * size * 2));
    } else {
      arrow.lineTo(x + (dx * size * 2), y - size);
      arrow.lineTo(x + (dx * size * 2), y + size);
    }
    arrow.closePath();
    g2.fill(arrow);
  }
}
