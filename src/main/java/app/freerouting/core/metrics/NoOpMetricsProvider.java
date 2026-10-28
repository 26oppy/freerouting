package app.freerouting.core.metrics;

/**
 * A fallback metrics provider that returns sentinel values (-1 / 0) when telemetry is unavailable.
 */
public final class NoOpMetricsProvider implements RuntimeMetricsProvider {

  @Override
  public long currentThreadCpuNanos() {
    return -1L;
  }

  @Override
  public long threadCpuNanos(long threadId) {
    return -1L;
  }

  @Override
  public long currentThreadAllocatedBytes() {
    return -1L;
  }

  @Override
  public long threadAllocatedBytes(long threadId) {
    return -1L;
  }

  @Override
  public float currentHeapUsageMb() {
    return 0.0F;
  }
}
