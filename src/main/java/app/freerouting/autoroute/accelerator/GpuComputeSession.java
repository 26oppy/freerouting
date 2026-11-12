package app.freerouting.autoroute.accelerator;

/**
 * A live compute session managing driver buffers, compiled kernels, and memory arenas.
 * Implementations must be thread-confined or safely synchronized.
 */
public interface GpuComputeSession extends AutoCloseable {

  /** Returns hardware device information associated with this session. */
  AcceleratorDeviceInfo deviceInfo();

  /** Returns whether this session is still operational and has not been poisoned by a timeout. */
  boolean isAlive();

  /**
   * Marks this session as poisoned and hands off pending in-flight memory to the resource reaper.
   */
  void poison();

  /** Releases session resources. Safe to call multiple times. */
  @Override
  void close();
}
