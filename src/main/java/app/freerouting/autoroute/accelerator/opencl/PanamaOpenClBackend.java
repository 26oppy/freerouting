package app.freerouting.autoroute.accelerator.opencl;

import app.freerouting.autoroute.accelerator.AcceleratorDeviceInfo;
import app.freerouting.autoroute.accelerator.AcceleratorRegistry;
import app.freerouting.autoroute.accelerator.ComputeBackend;
import app.freerouting.autoroute.accelerator.GpuComputeSession;
import app.freerouting.autoroute.accelerator.GpuFallbackReason;
import app.freerouting.autoroute.accelerator.GpuResourceReaper;
import app.freerouting.logger.FRLogger;
import app.freerouting.settings.FanoutSettings;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Panama FFM implementation of ComputeBackend using OpenCL 1.2 C APIs. */
public final class PanamaOpenClBackend implements ComputeBackend {

  private final PanamaOpenClBindings bindings;
  private final boolean available;
  private final AcceleratorDeviceInfo deviceInfo;
  private final MemorySegment platformId;
  private final MemorySegment deviceId;

  public PanamaOpenClBackend() {
    this.bindings = new PanamaOpenClBindings();
    if (!bindings.isLoaded()) {
      this.available = false;
      this.deviceInfo = null;
      this.platformId = null;
      this.deviceId = null;
      return;
    }

    MemorySegment selectedPlatform = null;
    MemorySegment selectedDevice = null;
    AcceleratorDeviceInfo info = null;

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment numPlatforms = arena.allocate(ValueLayout.JAVA_INT);
      int err = bindings.clGetPlatformIDs(0, MemorySegment.NULL, numPlatforms);
      int platformCount =
          (err == PanamaOpenClBindings.CL_SUCCESS) ? numPlatforms.get(ValueLayout.JAVA_INT, 0) : 0;
      if (platformCount > 0) {
        MemorySegment platforms = arena.allocate(ValueLayout.ADDRESS, platformCount);
        bindings.clGetPlatformIDs(platformCount, platforms, MemorySegment.NULL);

        for (int p = 0; p < platformCount; p++) {
          MemorySegment plat =
              platforms.get(ValueLayout.ADDRESS, (long) p * ValueLayout.ADDRESS.byteSize());
          MemorySegment numDevices = arena.allocate(ValueLayout.JAVA_INT);
          int devErr =
              bindings.clGetDeviceIDs(
                  plat, PanamaOpenClBindings.CL_DEVICE_TYPE_ALL, 0, MemorySegment.NULL, numDevices);
          int devCount =
              (devErr == PanamaOpenClBindings.CL_SUCCESS)
                  ? numDevices.get(ValueLayout.JAVA_INT, 0)
                  : 0;
          if (devCount > 0) {
            MemorySegment devices = arena.allocate(ValueLayout.ADDRESS, devCount);
            bindings.clGetDeviceIDs(
                plat,
                PanamaOpenClBindings.CL_DEVICE_TYPE_ALL,
                devCount,
                devices,
                MemorySegment.NULL);
            selectedPlatform = plat;
            selectedDevice = devices.get(ValueLayout.ADDRESS, 0);

            // Query device name
            MemorySegment nameBuf = arena.allocate(256);
            bindings.clGetDeviceInfo(
                selectedDevice,
                PanamaOpenClBindings.CL_DEVICE_NAME,
                256,
                nameBuf,
                MemorySegment.NULL);
            String name = nameBuf.getString(0, StandardCharsets.UTF_8).trim();

            // Query vendor
            MemorySegment vendorBuf = arena.allocate(256);
            bindings.clGetDeviceInfo(
                selectedDevice,
                PanamaOpenClBindings.CL_DEVICE_VENDOR,
                256,
                vendorBuf,
                MemorySegment.NULL);
            String vendor = vendorBuf.getString(0, StandardCharsets.UTF_8).trim();

            // Query driver version
            MemorySegment driverBuf = arena.allocate(256);
            bindings.clGetDeviceInfo(
                selectedDevice,
                PanamaOpenClBindings.CL_DRIVER_VERSION,
                256,
                driverBuf,
                MemorySegment.NULL);
            String driver = driverBuf.getString(0, StandardCharsets.UTF_8).trim();

            // Compute units
            MemorySegment cuBuf = arena.allocate(ValueLayout.JAVA_INT);
            bindings.clGetDeviceInfo(
                selectedDevice,
                PanamaOpenClBindings.CL_DEVICE_MAX_COMPUTE_UNITS,
                ValueLayout.JAVA_INT.byteSize(),
                cuBuf,
                MemorySegment.NULL);
            int computeUnits = cuBuf.get(ValueLayout.JAVA_INT, 0);

            // Global mem
            MemorySegment memBuf = arena.allocate(ValueLayout.JAVA_LONG);
            bindings.clGetDeviceInfo(
                selectedDevice,
                PanamaOpenClBindings.CL_DEVICE_GLOBAL_MEM_SIZE,
                ValueLayout.JAVA_LONG.byteSize(),
                memBuf,
                MemorySegment.NULL);
            long vram = memBuf.get(ValueLayout.JAVA_LONG, 0);

            // Unified memory
            MemorySegment unifiedBuf = arena.allocate(ValueLayout.JAVA_INT);
            int unifiedErr =
                bindings.clGetDeviceInfo(
                    selectedDevice,
                    PanamaOpenClBindings.CL_DEVICE_HOST_UNIFIED_MEMORY,
                    ValueLayout.JAVA_INT.byteSize(),
                    unifiedBuf,
                    MemorySegment.NULL);
            boolean unified =
                (unifiedErr == PanamaOpenClBindings.CL_SUCCESS)
                    && (unifiedBuf.get(ValueLayout.JAVA_INT, 0) != 0);

            info = new AcceleratorDeviceInfo(name, vendor, driver, unified, computeUnits, vram);
            break;
          }
        }
      }
    } catch (Throwable t) {
      FRLogger.warn("OpenCL initialization probe failed: " + t.getMessage());
    }

    if (selectedDevice != null && info != null) {
      this.platformId = selectedPlatform;
      this.deviceId = selectedDevice;
      this.deviceInfo = info;
      this.available = true;
    } else {
      this.platformId = null;
      this.deviceId = null;
      this.deviceInfo = null;
      this.available = false;
    }
  }

  @Override
  public boolean isAvailable() {
    return this.available;
  }

  @Override
  public AcceleratorDeviceInfo getDeviceInfo() {
    return this.deviceInfo;
  }

  @Override
  public GpuComputeSession openSession(FanoutSettings settings) throws Exception {
    if (!this.available) {
      throw new IllegalStateException("OpenCL backend is not available");
    }

    try {
      Arena sessionArena = Arena.ofShared();
      MemorySegment errBuf = sessionArena.allocate(ValueLayout.JAVA_INT);
      MemorySegment devArr = sessionArena.allocate(ValueLayout.ADDRESS);
      devArr.set(ValueLayout.ADDRESS, 0, this.deviceId);

      MemorySegment context =
          bindings.clCreateContext(
              MemorySegment.NULL, 1, devArr, MemorySegment.NULL, MemorySegment.NULL, errBuf);
      if (context == null || context.equals(MemorySegment.NULL)) {
        sessionArena.close();
        AcceleratorRegistry.tripCircuitBreaker(GpuFallbackReason.INIT_OR_COMPILE_FAILED);
        throw new IllegalStateException(
            "clCreateContext failed with code: " + errBuf.get(ValueLayout.JAVA_INT, 0));
      }

      MemorySegment queue = bindings.clCreateCommandQueue(context, this.deviceId, 0, errBuf);
      if (queue == null || queue.equals(MemorySegment.NULL)) {
        bindings.clReleaseContext(context);
        sessionArena.close();
        AcceleratorRegistry.tripCircuitBreaker(GpuFallbackReason.INIT_OR_COMPILE_FAILED);
        throw new IllegalStateException(
            "clCreateCommandQueue failed with code: " + errBuf.get(ValueLayout.JAVA_INT, 0));
      }

      // Load kernel source
      String source;
      try (InputStream is =
          getClass()
              .getResourceAsStream(
                  "/app/freerouting/autoroute/accelerator/kernels/bga_escape_kernels.cl")) {
        if (is == null) {
          source = "";
        } else {
          source = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
      }

      MemorySegment prog = null;
      if (!source.isBlank()) {
        MemorySegment srcSeg = sessionArena.allocateFrom(source);
        MemorySegment srcArr = sessionArena.allocate(ValueLayout.ADDRESS);
        srcArr.set(ValueLayout.ADDRESS, 0, srcSeg);
        prog = bindings.clCreateProgramWithSource(context, 1, srcArr, MemorySegment.NULL, errBuf);
        if (prog != null && !prog.equals(MemorySegment.NULL)) {
          int buildErr =
              bindings.clBuildProgram(
                  prog, 1, devArr, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
          if (buildErr != PanamaOpenClBindings.CL_SUCCESS) {
            FRLogger.warn("OpenCL program build returned status: " + buildErr);
          }
        }
      }

      return new PanamaGpuSession(
          this.bindings, this.deviceInfo, sessionArena, context, queue, prog);
    } catch (Throwable t) {
      AcceleratorRegistry.tripCircuitBreaker(GpuFallbackReason.INIT_OR_COMPILE_FAILED);
      throw (t instanceof Exception e) ? e : new RuntimeException(t);
    }
  }

  @Override
  public GpuFallbackReason fallbackReason() {
    return this.available ? null : GpuFallbackReason.NO_DEVICE_FOUND;
  }

  private static final class PanamaGpuSession implements GpuComputeSession {
    private final PanamaOpenClBindings bindings;
    private final AcceleratorDeviceInfo deviceInfo;
    private final Arena arena;
    private final MemorySegment context;
    private final MemorySegment queue;
    private final MemorySegment program;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    PanamaGpuSession(
        PanamaOpenClBindings bindings,
        AcceleratorDeviceInfo info,
        Arena arena,
        MemorySegment context,
        MemorySegment queue,
        MemorySegment program) {
      this.bindings = bindings;
      this.deviceInfo = info;
      this.arena = arena;
      this.context = context;
      this.queue = queue;
      this.program = program;
    }

    @Override
    public AcceleratorDeviceInfo deviceInfo() {
      return this.deviceInfo;
    }

    @Override
    public boolean isAlive() {
      return this.alive.get() && !this.closed.get();
    }

    @Override
    public void poison() {
      if (this.alive.compareAndSet(true, false)) {
        FRLogger.warn("Poisoning GPU compute session and handing memory to GpuResourceReaper");
        GpuResourceReaper.getInstance()
            .enqueue(
                new GpuResourceReaper.PoisonedResource(
                    MemorySegment.NULL, this::closeHandlesAndArena));
      }
    }

    @Override
    public void close() {
      if (this.closed.compareAndSet(false, true)) {
        if (this.alive.get()) {
          closeHandlesAndArena();
        }
      }
    }

    private void closeHandlesAndArena() {
      try {
        if (program != null && !program.equals(MemorySegment.NULL)) {
          bindings.clReleaseProgram(program);
        }
        if (queue != null && !queue.equals(MemorySegment.NULL)) {
          bindings.clReleaseCommandQueue(queue);
        }
        if (context != null && !context.equals(MemorySegment.NULL)) {
          bindings.clReleaseContext(context);
        }
      } catch (Throwable t) {
        FRLogger.warn("Error releasing OpenCL native handles: " + t.getMessage());
      } finally {
        try {
          if (arena.scope().isAlive()) {
            arena.close();
          }
        } catch (Throwable t) {
          FRLogger.warn("Error closing session arena: " + t.getMessage());
        }
      }
    }
  }
}
