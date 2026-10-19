package app.freerouting.geometry.planar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.rules.CornerStyle;
import app.freerouting.rules.MeanderPatternType;
import app.freerouting.rules.NetMeanderConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MeanderGeneratorTest {

  @Nested
  @DisplayName("Meander Generation Tests")
  class GenerationTests {

    @Test
    void generatesAccordionMeanderAddingTraceLength() {
      // 10 mm horizontal segment: (0, 0) to (10000, 0)
      Point start = new IntPoint(0, 0);
      Point end = new IntPoint(10000, 0);
      double targetExtraLength = 2000.0; // 2 mm extra length

      NetMeanderConstraint constraint =
          new NetMeanderConstraint(200.0, 1000.0, 600.0, CornerStyle.CHAMFER, 80.0, false);

      Point[] meanderPoints =
          MeanderGenerator.generateMeander(
              start, end, targetExtraLength, constraint, null, 1000.0, true);

      assertNotNull(meanderPoints);
      assertTrue(meanderPoints.length > 2);
      assertEquals(start, meanderPoints[0]);
      assertEquals(end, meanderPoints[meanderPoints.length - 1]);

      Polyline polyline = new Polyline(meanderPoints);
      double originalLength = 10000.0;
      double newLength = polyline.lengthApprox();

      assertTrue(newLength > originalLength);
      double extraAdded = newLength - originalLength;
      // Extra length should be within 20% of target (discretized by amplitude and cycles)
      assertTrue(extraAdded >= 1000.0 && extraAdded <= 3000.0);
    }

    @Test
    void singleSidedMeanderOnlyExtendsToOneSide() {
      Point start = new IntPoint(0, 0);
      Point end = new IntPoint(10000, 0);

      // Normal is +y (upwards)
      NetMeanderConstraint constraint =
          new NetMeanderConstraint(300.0, 800.0, 600.0, CornerStyle.CHAMFER, 80.0, true);

      Point[] meanderPoints =
          MeanderGenerator.generateMeander(
              start, end, 1500.0, constraint, Direction.UP, 800.0, true);

      assertNotNull(meanderPoints);
      for (Point pt : meanderPoints) {
        IntPoint ip = (IntPoint) pt;
        // All y coordinates must be >= 0 (strictly single-sided to the north)
        assertTrue(ip.y >= 0, "Point y should be >= 0 but was " + ip.y);
      }
    }

    @Test
    void generatesSkewBumpForDifferentialPairEqualization() {
      Point start = new IntPoint(0, 0);
      Point end = new IntPoint(6000, 0);
      double skewDeficit = 500.0; // 0.5 mm skew difference

      NetMeanderConstraint preset = NetMeanderConstraint.createDefaultPreset(MeanderPatternType.DIFF_PAIR_SKEW, 1000.0);

      Point[] bumpPoints =
          MeanderGenerator.generateSkewBump(
              start, end, skewDeficit, preset, Direction.UP, 1000.0, true);

      assertNotNull(bumpPoints);
      assertEquals(start, bumpPoints[0]);
      assertEquals(end, bumpPoints[bumpPoints.length - 1]);

      Polyline polyline = new Polyline(bumpPoints);
      double extraAdded = polyline.lengthApprox() - 6000.0;
      assertTrue(extraAdded > 0.0);
    }
  }

  @Nested
  @DisplayName("Edge Cases & Bounds")
  class EdgeCaseTests {

    @Test
    void returnsNullWhenSegmentIsTooShort() {
      // 1000 units is shorter than required 2 * spacing margin + 2 * spacing cycle
      Point start = new IntPoint(0, 0);
      Point end = new IntPoint(1000, 0);

      NetMeanderConstraint constraint =
          new NetMeanderConstraint(200.0, 1000.0, 600.0, CornerStyle.CHAMFER, 80.0, false);

      Point[] result =
          MeanderGenerator.generateMeander(
              start, end, 500.0, constraint, null, 1000.0, true);

      assertNull(result);
    }

    @Test
    void returnsNullWhenExtraLengthIsZeroOrNegative() {
      Point start = new IntPoint(0, 0);
      Point end = new IntPoint(10000, 0);

      NetMeanderConstraint constraint =
          new NetMeanderConstraint(200.0, 1000.0, 600.0, CornerStyle.CHAMFER, 80.0, false);

      assertNull(MeanderGenerator.generateMeander(start, end, 0.0, constraint, null, 1000.0, true));
      assertNull(MeanderGenerator.generateMeander(start, end, -500.0, constraint, null, 1000.0, true));
    }

    @Test
    void returnsNullWhenAvailableClearanceIsLessThanMinAmplitude() {
      Point start = new IntPoint(0, 0);
      Point end = new IntPoint(10000, 0);

      NetMeanderConstraint constraint =
          new NetMeanderConstraint(400.0, 1000.0, 600.0, CornerStyle.CHAMFER, 80.0, false);

      // Available clear amplitude is only 200, but min is 400
      Point[] result =
          MeanderGenerator.generateMeander(
              start, end, 1000.0, constraint, null, 200.0, true);

      assertNull(result);
    }

    @Test
    void returnsNullOnDegenerateOrNullInputs() {
      Point p = new IntPoint(0, 0);
      NetMeanderConstraint constraint = NetMeanderConstraint.UNCONSTRAINED;

      assertNull(MeanderGenerator.generateMeander(null, p, 1000.0, constraint, null, 0, true));
      assertNull(MeanderGenerator.generateMeander(p, null, 1000.0, constraint, null, 0, true));
      assertNull(MeanderGenerator.generateMeander(p, p, 1000.0, constraint, null, 0, true));
      assertNull(MeanderGenerator.generateMeander(p, new IntPoint(1000, 0), 1000.0, null, null, 0, true));
    }
  }
}

