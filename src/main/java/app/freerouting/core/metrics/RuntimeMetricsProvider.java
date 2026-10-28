package app.freerouting.core.metrics;

/**
 * Strategy interface providing JVM runtime telemetry such as thread CPU time, thread memory
 * allocations, and heap utilization.
 *
 * <p>Isolating these metrics behind an interface allows Freerouting to run in Ahead-Of-Time (AOT)
 * or WebAssembly (WASM) environments where {@code java.lang.management} and {@code
 * com.sun.management} may not be available.
 */
public interface RuntimeMetricsProvider {

  /** Returns the current thread's CPU time in nanoseconds, or -1 if unsupported. */
  long currentThreadCpuNanos();

  /** Returns the current thread's CPU time in seconds, or -1 if unsupported. */
  default float currentThreadCpuSeconds() {
    long nanos = currentThreadCpuNanos();
    return nanos < 0 ? -1.0F : nanos / 1_000_000_000.0F;
  }

  /** Returns the specified thread's CPU time in nanoseconds, or -1 if unsupported. */
  long threadCpuNanos(long threadId);

  /** Returns the specified thread's CPU time in seconds, or -1 if unsupported. */
  default float threadCpuSeconds(long threadId) {
    long nanos = threadCpuNanos(threadId);
    return nanos < 0 ? -1.0F : nanos / 1_000_000_000.0F;
  }

  /** Returns the total memory allocated by the current thread in bytes, or -1 if unsupported. */
  long currentThreadAllocatedBytes();

  /**
   * Returns the total memory allocated by the current thread in megabytes, or -1 if unsupported.
   */
  default float currentThreadAllocatedMb() {
    long bytes = currentThreadAllocatedBytes();
    return bytes < 0 ? -1.0F : bytes / (1024.0F * 1024.0F);
  }

  /** Returns the total memory allocated by the specified thread in bytes, or -1 if unsupported. */
  long threadAllocatedBytes(long threadId);

  /**
   * Returns the total memory allocated by the specified thread in megabytes, or -1 if unsupported.
   */
  default float threadAllocatedMb(long threadId) {
    long bytes = threadAllocatedBytes(threadId);
    return bytes < 0 ? -1.0F : bytes / (1024.0F * 1024.0F);
  }

  /** Returns current heap memory used in megabytes, or 0 if unsupported. */
  float currentHeapUsageMb();
}
