package app.freerouting.autoroute.accelerator;

import app.freerouting.settings.FanoutSettings;

/**
 * Hardware-agnostic SPI interface for accelerator backends (OpenCL, Metal, Vulkan, or CPU
 * fallback).
 */
public interface ComputeBackend {

  /** Returns true if this compute backend is functional and has available hardware. */
  boolean isAvailable();

  /** Returns hardware device metadata, or {@code null} if unavailable. */
  AcceleratorDeviceInfo getDeviceInfo();

  /**
   * Opens an active compute session configured according to fanout settings.
   *
   * @param settings router fanout settings
   * @return active GPU compute session
   * @throws Exception if context creation, buffer allocation, or kernel compilation fails
   */
  GpuComputeSession openSession(FanoutSettings settings) throws Exception;

  /** Returns the reason why this backend is unavailable, or {@code null} if available. */
  GpuFallbackReason fallbackReason();
}
