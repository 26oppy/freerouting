package app.freerouting.autoroute.accelerator;

/**
 * Metadata and hardware capabilities of an accelerator device (GPU or iGPU).
 *
 * @param deviceName the name of the device (e.g. "Intel(R) Arc(TM) Graphics")
 * @param vendor the vendor string (e.g. "Intel", "NVIDIA Corporation")
 * @param driverVersion driver version string
 * @param isUnifiedMemory true if host memory is unified (typical for iGPUs)
 * @param computeUnits number of hardware compute units / execution units
 * @param maxVramBytes maximum addressable memory on the device in bytes
 */
public record AcceleratorDeviceInfo(
    String deviceName,
    String vendor,
    String driverVersion,
    boolean isUnifiedMemory,
    int computeUnits,
    long maxVramBytes) {}
