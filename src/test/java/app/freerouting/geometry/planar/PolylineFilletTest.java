package app.freerouting.geometry.planar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.rules.CornerStyle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PolylineFilletTest {

  @Nested
  @DisplayName("Corner Fillet Tests")
  class CornerFilletTests {

    @Test
    void chamferOnNinetyDegreeCornerProducesFortyFiveDegreeBevel() {
      // (0, 0) -> (0, 1000) -> (1000, 1000)
      Point p0 = new IntPoint(0, 0);
      Point corner = new IntPoint(0, 1000);
      Point p1 = new IntPoint(1000, 1000);

      Point[] replacement =
          PolylineFillet.filletCorner(p0, corner, p1, CornerStyle.CHAMFER, 200.0, true);

      assertEquals(2, replacement.length);
      assertEquals(new IntPoint(0, 800), replacement[0]);
      assertEquals(new IntPoint(200, 1000), replacement[1]);

      // Check bevel angle: dx = 200, dy = 200 -> perfect 45 degrees
      int dx = ((IntPoint) replacement[1]).x - ((IntPoint) replacement[0]).x;
      int dy = ((IntPoint) replacement[1]).y - ((IntPoint) replacement[0]).y;
      assertEquals(dx, dy);
    }

    @Test
    void filletWithFortyFiveDegreeConstraintProducesBevel() {
      Point p0 = new IntPoint(0, 0);
      Point corner = new IntPoint(0, 1000);
      Point p1 = new IntPoint(1000, 1000);

      Point[] replacement =
          PolylineFillet.filletCorner(p0, corner, p1, CornerStyle.FILLET, 200.0, true);

      assertEquals(2, replacement.length);
      assertEquals(new IntPoint(0, 800), replacement[0]);
      assertEquals(new IntPoint(200, 1000), replacement[1]);
    }

    @Test
    void filletWithoutFortyFiveDegreeConstraintProducesMultiSegmentArc() {
      Point p0 = new IntPoint(0, 0);
      Point corner = new IntPoint(0, 1000);
      Point p1 = new IntPoint(1000, 1000);

      Point[] replacement =
          PolylineFillet.filletCorner(p0, corner, p1, CornerStyle.FILLET, 200.0, false);

      // Multiple chord points approximating circular arc
      assertTrue(replacement.length >= 3);
      // Tangent start and end points
      assertEquals(0, ((IntPoint) replacement[0]).x);
      assertEquals(1000, ((IntPoint) replacement[replacement.length - 1]).y);
    }

    @Test
    void radiusIsClampedWhenSegmentsAreShort() {
      // Segments are only 100 units long, but radius is 500
      Point p0 = new IntPoint(0, 0);
      Point corner = new IntPoint(0, 100);
      Point p1 = new IntPoint(100, 100);

      Point[] replacement =
          PolylineFillet.filletCorner(p0, corner, p1, CornerStyle.CHAMFER, 500.0, true);

      assertEquals(2, replacement.length);
      // Max setback is 0.45 * 100 = 45 units
      assertEquals(new IntPoint(0, 55), replacement[0]);
      assertEquals(new IntPoint(45, 100), replacement[1]);
    }
  }

  @Nested
  @DisplayName("Edge Cases & Degeneracies")
  class EdgeCaseTests {

    @Test
    void handlesNullOrZeroRadiusGracefully() {
      Point p0 = new IntPoint(0, 0);
      Point corner = new IntPoint(0, 1000);
      Point p1 = new IntPoint(1000, 1000);

      Point[] nullPrev =
          PolylineFillet.filletCorner(null, corner, p1, CornerStyle.CHAMFER, 100, true);
      assertArrayEquals(new Point[] {corner}, nullPrev);

      Point[] zeroRadius =
          PolylineFillet.filletCorner(p0, corner, p1, CornerStyle.CHAMFER, 0.0, true);
      assertArrayEquals(new Point[] {corner}, zeroRadius);

      Point[] negRadius =
          PolylineFillet.filletCorner(p0, corner, p1, CornerStyle.CHAMFER, -100.0, true);
      assertArrayEquals(new Point[] {corner}, negRadius);
    }

    @Test
    void handlesCollinearAndTurnaroundSegments() {
      // Collinear straight line (0, 0) -> (500, 0) -> (1000, 0)
      Point p0 = new IntPoint(0, 0);
      Point corner = new IntPoint(500, 0);
      Point p1 = new IntPoint(1000, 0);

      Point[] collinear =
          PolylineFillet.filletCorner(p0, corner, p1, CornerStyle.CHAMFER, 100.0, true);
      assertArrayEquals(new Point[] {corner}, collinear);

      // Hairpin turnaround (0, 0) -> (500, 0) -> (0, 0)
      Point[] turnaround =
          PolylineFillet.filletCorner(p0, corner, p0, CornerStyle.CHAMFER, 100.0, true);
      assertArrayEquals(new Point[] {corner}, turnaround);
    }

    @Test
    void doesNotBevelFortyFiveDegreeBendInStrictFortyFiveDegreeMode() {
      // (0, 0) -> (1000, 0) -> (2000, 1000) (45 degree bend)
      Point p0 = new IntPoint(0, 0);
      Point corner = new IntPoint(1000, 0);
      Point p1 = new IntPoint(2000, 1000);

      Point[] replacement =
          PolylineFillet.filletCorner(p0, corner, p1, CornerStyle.CHAMFER, 200.0, true);

      // Must NOT bevel in 45-degree mode to prevent producing 22.5 degree segments
      assertArrayEquals(new Point[] {corner}, replacement);
    }
  }

  @Nested
  @DisplayName("Whole Polyline Fillet Tests")
  class PolylineFilletTests {

    @Test
    void filletsUturnPolyline() {
      // Box turn: (0, 0) -> (0, 1000) -> (1000, 1000) -> (1000, 0)
      Point[] corners = {
        new IntPoint(0, 0), new IntPoint(0, 1000), new IntPoint(1000, 1000), new IntPoint(1000, 0)
      };
      Polyline polyline = new Polyline(corners);

      Polyline result = PolylineFillet.filletPolyline(polyline, CornerStyle.CHAMFER, 200.0, true);

      assertNotNull(result);
      // Original had 4 corners. 2 inner corners replaced by 2 each -> total 6 corners
      assertEquals(6, result.cornerCount());
      assertEquals(new IntPoint(0, 0), result.firstCorner());
      assertEquals(new IntPoint(1000, 0), result.lastCorner());
    }
  }
}
