package app.freerouting.core.metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;

/**
 * Default implementation of {@link RuntimeMetricsProvider} querying standard Java {@link
 * ThreadMXBean} and {@link MemoryMXBean}.
 *
 * <p>Uses reflection to query {@code com.sun.management.ThreadMXBean} for thread allocation
 * tracking when available, avoiding static compile-time symbol dependencies on proprietary classes.
 */
public final class StandardJvmMetricsProvider implements RuntimeMetricsProvider {

  private final ThreadMXBean threadBean;
  private final MemoryMXBean memoryBean;
  private final Method getThreadAllocatedBytesMethod;

  /** Constructs a new {@link StandardJvmMetricsProvider} querying standard JVM management beans. */
  public StandardJvmMetricsProvider() {
    ThreadMXBean tBean = null;
    MemoryMXBean mBean = null;
    Method allocMethod = null;

    try {
      tBean = ManagementFactory.getThreadMXBean();
      if (tBean != null) {
        try {
          Class<?> sunBeanClass = Class.forName("com.sun.management.ThreadMXBean");
          if (sunBeanClass.isInstance(tBean)) {
            try {
              Method setAllocEnabled =
                  sunBeanClass.getMethod("setThreadAllocatedMemoryEnabled", boolean.class);
              setAllocEnabled.invoke(tBean, true);
            } catch (Throwable ignored) {
              // Allocation tracking enablement unsupported
            }
            allocMethod = sunBeanClass.getMethod("getThreadAllocatedBytes", long.class);
          }
        } catch (Throwable ignored) {
          // com.sun.management.ThreadMXBean not found on this runtime (e.g. TeaVM / CheerpJ)
        }
      }
    } catch (Throwable ignored) {
      // ManagementFactory unsupported
    }

    try {
      mBean = ManagementFactory.getMemoryMXBean();
    } catch (Throwable ignored) {
      // Memory bean unsupported
    }

    this.threadBean = tBean;
    this.memoryBean = mBean;
    this.getThreadAllocatedBytesMethod = allocMethod;
  }

  @Override
  public long currentThreadCpuNanos() {
    return threadCpuNanos(Thread.currentThread().threadId());
  }

  @Override
  public long threadCpuNanos(long threadId) {
    try {
      if (threadBean != null) {
        return threadBean.getThreadCpuTime(threadId);
      }
    } catch (Throwable ignored) {
      // Unsupported thread or JVM
    }
    return -1L;
  }

  @Override
  public long currentThreadAllocatedBytes() {
    return threadAllocatedBytes(Thread.currentThread().threadId());
  }

  @Override
  public long threadAllocatedBytes(long threadId) {
    if (getThreadAllocatedBytesMethod != null && threadBean != null) {
      try {
        return (long) getThreadAllocatedBytesMethod.invoke(threadBean, threadId);
      } catch (Throwable ignored) {
        // Allocation tracking invocation unsupported
      }
    }
    return -1L;
  }

  @Override
  public float currentHeapUsageMb() {
    try {
      if (memoryBean != null) {
        long used = memoryBean.getHeapMemoryUsage().getUsed();
        return used / (1024.0F * 1024.0F);
      }
    } catch (Throwable ignored) {
      // Memory bean unsupported
    }
    return 0.0F;
  }
}
