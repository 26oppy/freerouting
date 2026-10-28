package app.freerouting.core.metrics;

/**
 * Global access point for JVM and runtime telemetry.
 *
 * <p>Delegates to a pluggable {@link RuntimeMetricsProvider}, defaulting to {@link
 * StandardJvmMetricsProvider} on standard JVMs, or {@link NoOpMetricsProvider} if management APIs
 * are absent.
 */
public final class RuntimeMetrics {

  private static volatile RuntimeMetricsProvider provider = createDefaultProvider();

  private RuntimeMetrics() {}

  private static RuntimeMetricsProvider createDefaultProvider() {
    try {
      return new StandardJvmMetricsProvider();
    } catch (Throwable t) {
      return new NoOpMetricsProvider();
    }
  }

  /**
   * Overrides the active metrics provider.
   *
   * @param customProvider the custom provider to use, or {@code null} to reset to no-op
   */
  public static void setProvider(RuntimeMetricsProvider customProvider) {
    provider = (customProvider != null) ? customProvider : new NoOpMetricsProvider();
  }

  /** Returns the active metrics provider. */
  public static RuntimeMetricsProvider getProvider() {
    return provider;
  }

  /** Returns the current thread's CPU time in nanoseconds, or -1 if unsupported. */
  public static long currentThreadCpuNanos() {
    return provider.currentThreadCpuNanos();
  }

  /** Returns the current thread's CPU time in seconds, or -1 if unsupported. */
  public static float currentThreadCpuSeconds() {
    return provider.currentThreadCpuSeconds();
  }

  /** Returns the specified thread's CPU time in nanoseconds, or -1 if unsupported. */
  public static long threadCpuNanos(long threadId) {
    return provider.threadCpuNanos(threadId);
  }

  /** Returns the specified thread's CPU time in seconds, or -1 if unsupported. */
  public static float threadCpuSeconds(long threadId) {
    return provider.threadCpuSeconds(threadId);
  }

  /** Returns the total memory allocated by the current thread in bytes, or -1 if unsupported. */
  public static long currentThreadAllocatedBytes() {
    return provider.currentThreadAllocatedBytes();
  }

  /**
   * Returns the total memory allocated by the current thread in megabytes, or -1 if unsupported.
   */
  public static float currentThreadAllocatedMb() {
    return provider.currentThreadAllocatedMb();
  }

  /** Returns the total memory allocated by the specified thread in bytes, or -1 if unsupported. */
  public static long threadAllocatedBytes(long threadId) {
    return provider.threadAllocatedBytes(threadId);
  }

  /**
   * Returns the total memory allocated by the specified thread in megabytes, or -1 if unsupported.
   */
  public static float threadAllocatedMb(long threadId) {
    return provider.threadAllocatedMb(threadId);
  }

  /** Returns current heap memory used in megabytes, or 0 if unsupported. */
  public static float currentHeapUsageMb() {
    return provider.currentHeapUsageMb();
  }
}
