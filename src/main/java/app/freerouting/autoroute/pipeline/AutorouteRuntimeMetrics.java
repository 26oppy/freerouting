package app.freerouting.autoroute.pipeline;

import app.freerouting.core.RoutingJob;
import app.freerouting.core.metrics.RuntimeMetrics;

/** Collects optional runtime metrics used by autoroute stage diagnostics. */
final class AutorouteRuntimeMetrics {

  private AutorouteRuntimeMetrics() {}

  static float cpuSecondsSnapshot(RoutingJob job) {
    if (job == null || job.resourceUsage == null) {
      return 0f;
    }
    return job.resourceUsage.cpuTimeUsed;
  }

  static float allocatedMemoryMbSnapshot(RoutingJob job) {
    if (job == null || job.resourceUsage == null) {
      return 0f;
    }
    return job.resourceUsage.maxMemoryUsed;
  }

  static float peakHeapMbSnapshot(RoutingJob job) {
    if (job == null || job.resourceUsage == null) {
      return 0f;
    }
    return job.resourceUsage.peakMemoryUsed;
  }

  static long currentThreadCpuNanos() {
    return RuntimeMetrics.currentThreadCpuNanos();
  }

  static float currentThreadCpuSeconds() {
    return RuntimeMetrics.currentThreadCpuSeconds();
  }

  static long currentThreadAllocatedBytes() {
    return RuntimeMetrics.currentThreadAllocatedBytes();
  }

  static float currentThreadAllocatedMb() {
    return RuntimeMetrics.currentThreadAllocatedMb();
  }

  static float currentHeapUsageMb() {
    return RuntimeMetrics.currentHeapUsageMb();
  }

  static double nanosToMillis(long nanos) {
    return nanos / 1_000_000.0;
  }
}
