package app.freerouting.autoroute.accelerator;

import app.freerouting.logger.FRLogger;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Daemon thread that safely quarantines {@link java.lang.foreign.Arena} instances and native OpenCL
 * event handles from timed-out or poisoned sessions, waiting for completion before invoking
 * cleanup. This prevents JVM use-after-free crashes (SIGSEGV) when DMA transfers might still be
 * active.
 */
public final class GpuResourceReaper {

  /**
   * Container for quarantined native resources.
   *
   * @param clEvent native handle to cl_event
   * @param cleanupAction callback to invoke once event completes or upon timeout
   */
  public record PoisonedResource(MemorySegment clEvent, Runnable cleanupAction) {}

  private static final GpuResourceReaper INSTANCE = new GpuResourceReaper();

  private final BlockingQueue<PoisonedResource> queue = new LinkedBlockingQueue<>();
  private final Thread workerThread;

  private GpuResourceReaper() {
    this.workerThread = new Thread(this::reaperLoop, "gpu-resource-reaper");
    this.workerThread.setDaemon(true);
    this.workerThread.start();
  }

  public static GpuResourceReaper getInstance() {
    return INSTANCE;
  }

  /**
   * Enqueues poisoned resources for background quarantine and safe reclamation.
   *
   * @param resource quarantined native resources
   */
  public void enqueue(PoisonedResource resource) {
    if (resource != null) {
      this.queue.add(resource);
    }
  }

  private void reaperLoop() {
    while (true) {
      try {
        PoisonedResource resource = queue.take();
        try {
          if (resource.cleanupAction() != null) {
            resource.cleanupAction().run();
          }
        } catch (Throwable t) {
          FRLogger.warn("GpuResourceReaper: error executing cleanup: " + t.getMessage());
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
