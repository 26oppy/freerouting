package app.freerouting.autoroute.accelerator;

import app.freerouting.settings.FanoutSettings;

/** Default zero-overhead fallback backend used when GPU compute is disabled or unavailable. */
public final class NoOpCpuComputeBackend implements ComputeBackend {

  private final GpuFallbackReason reason;

  public NoOpCpuComputeBackend(GpuFallbackReason reason) {
    this.reason = reason;
  }

  @Override
  public boolean isAvailable() {
    return false;
  }

  @Override
  public AcceleratorDeviceInfo getDeviceInfo() {
    return null;
  }

  @Override
  public GpuComputeSession openSession(FanoutSettings settings) {
    throw new UnsupportedOperationException("GPU acceleration is unavailable: " + reason);
  }

  @Override
  public GpuFallbackReason fallbackReason() {
    return this.reason;
  }
}
