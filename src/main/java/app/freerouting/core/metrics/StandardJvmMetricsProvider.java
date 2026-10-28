package app.freerouting.core.metrics;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

/**
 * Default implementation of {@link RuntimeMetricsProvider} querying OpenJDK {@link ThreadMXBean}
 * and {@link MemoryMXBean}.
 */
public final class StandardJvmMetricsProvider implements RuntimeMetricsProvider {

  private final ThreadMXBean sunThreadBean;
  private final java.lang.management.ThreadMXBean standardThreadBean;
  private final MemoryMXBean memoryBean;

  /** Constructs a new {@link StandardJvmMetricsProvider} querying standard JVM management beans. */
  public StandardJvmMetricsProvider() {
    java.lang.management.ThreadMXBean tBean = null;
    ThreadMXBean sBean = null;
    MemoryMXBean mBean = null;

    try {
      tBean = ManagementFactory.getThreadMXBean();
      if (tBean instanceof ThreadMXBean casted) {
        sBean = casted;
        try {
          sBean.setThreadAllocatedMemoryEnabled(true);
        } catch (Throwable ignored) {
          // Allocation tracking unsupported
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

    this.standardThreadBean = tBean;
    this.sunThreadBean = sBean;
    this.memoryBean = mBean;
  }

  @Override
  public long currentThreadCpuNanos() {
    return threadCpuNanos(Thread.currentThread().threadId());
  }

  @Override
  public long threadCpuNanos(long threadId) {
    try {
      if (standardThreadBean != null) {
        return standardThreadBean.getThreadCpuTime(threadId);
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
    try {
      if (sunThreadBean != null) {
        return sunThreadBean.getThreadAllocatedBytes(threadId);
      }
    } catch (Throwable ignored) {
      // Allocation tracking unsupported
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
