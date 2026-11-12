package app.freerouting.autoroute.accelerator;

/** Diagnostic reasons why GPU/iGPU acceleration fell back to CPU execution. */
public enum GpuFallbackReason {
  /** GPU acceleration was disabled in settings or preference was set to CPU. */
  DISABLED_BY_SETTINGS,
  /** No OpenCL ICD or device was discovered on the system. */
  NO_DEVICE_FOUND,
  /**
   * Platform driver is unsupported (e.g. deprecated macOS arm64 OpenCL without experimental
   * override).
   */
  UNSUPPORTED_PLATFORM_DRIVER,
  /** Component has fewer SMD pins than the minimum threshold required for GPU acceleration. */
  BELOW_PIN_THRESHOLD,
  /** OpenCL context creation or program compilation failed. */
  INIT_OR_COMPILE_FAILED,
  /** Required grid or buffer allocation exceeds available VRAM / host memory cap. */
  VRAM_EXHAUSTED,
  /** Kernel execution exceeded the watchdog timeout limit. */
  KERNEL_TIMEOUT,
  /** Proposed trace stub or via failed exact host continuous DRC. */
  DRC_REJECTED
}
