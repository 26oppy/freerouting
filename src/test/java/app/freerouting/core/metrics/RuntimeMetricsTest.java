package app.freerouting.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link RuntimeMetrics}, {@link RuntimeMetricsProvider}, and fallback behaviors. */
class RuntimeMetricsTest {

  @AfterEach
  void tearDown() {
    // Reset to default provider after tests
    RuntimeMetrics.setProvider(new StandardJvmMetricsProvider());
  }

  @Test
  void testDefaultProviderCollectsSensibleMetrics() {
    RuntimeMetricsProvider provider = RuntimeMetrics.getProvider();
    assertNotNull(provider);
    assertTrue(provider instanceof StandardJvmMetricsProvider);

    // Heap usage on active JVM must be positive
    float heapMb = RuntimeMetrics.currentHeapUsageMb();
    assertTrue(heapMb > 0.0F, "Heap usage should be greater than zero");

    // Thread CPU time on active JVM should be non-negative
    long cpuNanos = RuntimeMetrics.currentThreadCpuNanos();
    assertTrue(cpuNanos >= 0, "CPU nanos should be non-negative on supported JVM");

    float cpuSec = RuntimeMetrics.currentThreadCpuSeconds();
    assertTrue(cpuSec >= 0.0F, "CPU seconds should be non-negative on supported JVM");
  }

  @Test
  void testNoOpProviderReturnsSentinels() {
    RuntimeMetrics.setProvider(new NoOpMetricsProvider());

    assertEquals(-1L, RuntimeMetrics.currentThreadCpuNanos());
    assertEquals(-1.0F, RuntimeMetrics.currentThreadCpuSeconds());
    assertEquals(-1L, RuntimeMetrics.currentThreadAllocatedBytes());
    assertEquals(-1.0F, RuntimeMetrics.currentThreadAllocatedMb());
    assertEquals(-1L, RuntimeMetrics.threadCpuNanos(Thread.currentThread().threadId()));
    assertEquals(-1.0F, RuntimeMetrics.threadCpuSeconds(Thread.currentThread().threadId()));
    assertEquals(-1L, RuntimeMetrics.threadAllocatedBytes(Thread.currentThread().threadId()));
    assertEquals(-1.0F, RuntimeMetrics.threadAllocatedMb(Thread.currentThread().threadId()));
    assertEquals(0.0F, RuntimeMetrics.currentHeapUsageMb());
  }

  @Test
  void testCustomProviderOverride() {
    RuntimeMetricsProvider custom =
        new RuntimeMetricsProvider() {
          @Override
          public long currentThreadCpuNanos() {
            return 500_000_000L;
          }

          @Override
          public long threadCpuNanos(long threadId) {
            return 500_000_000L;
          }

          @Override
          public long currentThreadAllocatedBytes() {
            return 2_097_152L; // 2 MB
          }

          @Override
          public long threadAllocatedBytes(long threadId) {
            return 2_097_152L;
          }

          @Override
          public float currentHeapUsageMb() {
            return 128.5F;
          }
        };

    RuntimeMetrics.setProvider(custom);

    assertEquals(500_000_000L, RuntimeMetrics.currentThreadCpuNanos());
    assertEquals(0.5F, RuntimeMetrics.currentThreadCpuSeconds(), 0.001F);
    assertEquals(2_097_152L, RuntimeMetrics.currentThreadAllocatedBytes());
    assertEquals(2.0F, RuntimeMetrics.currentThreadAllocatedMb(), 0.001F);
    assertEquals(128.5F, RuntimeMetrics.currentHeapUsageMb(), 0.001F);
  }

  @Test
  void testSetNullProviderFallsBackToNoOp() {
    RuntimeMetrics.setProvider(null);
    assertTrue(RuntimeMetrics.getProvider() instanceof NoOpMetricsProvider);
    assertEquals(-1L, RuntimeMetrics.currentThreadCpuNanos());
  }
}
