package app.freerouting.rules;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class NetMeanderConstraintTest {

  @Test
  public void testMergeWithInheritsNullProperties() {
    NetMeanderConstraint fallback =
        new NetMeanderConstraint(1.0, 0.5, 0.6, true, NetMeanderConstraint.CornerStyle.FILLET, 80);

    // Provide only gap override
    NetMeanderConstraint explicit = new NetMeanderConstraint(null, null, 1.2, null, null, null);

    NetMeanderConstraint resolved = explicit.mergeWith(fallback);

    assertEquals(1.0, resolved.maxAmplitude());
    assertEquals(0.5, resolved.minAmplitude());
    assertEquals(1.2, resolved.gap());
    assertEquals(true, resolved.singleSided());
    assertEquals(NetMeanderConstraint.CornerStyle.FILLET, resolved.cornerStyle());
    assertEquals(80, resolved.cornerRadiusPercentage());
  }

  @Test
  public void testMergeWithOverridesEverything() {
    NetMeanderConstraint fallback =
        new NetMeanderConstraint(1.0, 0.5, 0.6, true, NetMeanderConstraint.CornerStyle.FILLET, 80);

    NetMeanderConstraint explicit =
        new NetMeanderConstraint(
            2.0, 1.0, 1.2, false, NetMeanderConstraint.CornerStyle.CHAMFER, 50);

    NetMeanderConstraint resolved = explicit.mergeWith(fallback);

    assertEquals(2.0, resolved.maxAmplitude());
    assertEquals(1.0, resolved.minAmplitude());
    assertEquals(1.2, resolved.gap());
    assertEquals(false, resolved.singleSided());
    assertEquals(NetMeanderConstraint.CornerStyle.CHAMFER, resolved.cornerStyle());
    assertEquals(50, resolved.cornerRadiusPercentage());
  }

  @Test
  public void testEffectiveGapSpecctraFallback() {
    NetMeanderConstraint constraint = new NetMeanderConstraint(null, null, null, null, null, null);

    // gap = max(3*W, W+C). Wait, traceWidth = 10, clearance = 5.
    // 3*W = 30. W+C = 15. max is 30.
    assertEquals(30.0, constraint.resolveEffectiveGap(10.0, 5.0), 1e-6);

    // traceWidth = 1, clearance = 5.
    // 3*W = 3. W+C = 6. max is 6.
    assertEquals(6.0, constraint.resolveEffectiveGap(1.0, 5.0), 1e-6);
  }

  @Test
  public void testEffectiveMinAmplitudeSpecctraFallback() {
    NetMeanderConstraint constraint = new NetMeanderConstraint(null, null, null, null, null, null);

    // gap = max(3*W, W+C). Wait, traceWidth = 10, clearance = 5.
    // 3*W = 30. W+C = 15. max is 30.
    assertEquals(30.0, constraint.resolveEffectiveMinAmplitude(10.0, 5.0), 1e-6);

    // traceWidth = 1, clearance = 5.
    // 3*W = 3. W+C = 6. max is 6.
    assertEquals(6.0, constraint.resolveEffectiveMinAmplitude(1.0, 5.0), 1e-6);
  }
}
