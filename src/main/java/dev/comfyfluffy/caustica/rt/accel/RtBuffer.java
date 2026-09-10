package dev.comfyfluffy.caustica.rt.accel;

import dev.comfyfluffy.caustica.nativebridge.NativeRenderer;
import dev.comfyfluffy.caustica.rt.VulkanDiagnostics;
import org.lwjgl.util.vma.Vma;

/**
 * A VMA-backed Vulkan buffer with a device address (for RT geometry, scratch, SBT, etc.).
 * Created via {@link dev.comfyfluffy.caustica.rt.RtContext#createBuffer}; freed with {@link #destroy()}.
 * When the native backend owns the allocation ({@code nativeId != 0}), {@link #destroy()} and
 * {@link #flush(long, long)} forward to the C++ renderer; otherwise they call into the local
 * Java VMA allocator as before.
 */
public final class RtBuffer {
    public final long handle;
    public final long allocation;
    public final long deviceAddress;
    /** Host pointer if created host-visible, else 0. */
    public final long mapped;
    /** Allocated capacity in bytes. */
    public final long size;
    /** Original usage flags passed to {@code createBuffer} (pre {@code SHADER_DEVICE_ADDRESS}). */
    public final int usage;
    /** Whether this buffer is host-visible+mapped. */
    public final boolean hostVisible;

    private final long vma;
    private final long nativeId;
    private final String label;
    private boolean destroyed;

    /** Native-backed allocation owned by the C++ renderer. The trailing sentinel distinguishes
     *  this from the VMA-owned overload so the constructor signature stays unique. */
    public RtBuffer(long nativeId, long handle, long allocation, long deviceAddress, long mapped,
                    long size, int usage, boolean hostVisible, String label, NativeRenderer sentinel) {
        this.nativeId = nativeId;
        this.vma = 0L;
        this.handle = handle;
        this.allocation = allocation;
        this.deviceAddress = deviceAddress;
        this.mapped = mapped;
        this.size = size;
        this.usage = usage;
        this.hostVisible = hostVisible;
        this.label = label != null ? label : "buffer";
        VulkanDiagnostics.registerBuffer(deviceAddress, size, handle, this.label);
    }

    /** Legacy Java-VMA-owned allocation; kept so the native-fallback path stays allocation-safe. */
    public RtBuffer(long vma, long handle, long allocation, long deviceAddress, long mapped, long size,
                    int usage, boolean hostVisible) {
        this(vma, handle, allocation, deviceAddress, mapped, size, usage, hostVisible, "buffer");
    }

    public RtBuffer(long vma, long handle, long allocation, long deviceAddress, long mapped, long size,
                    int usage, boolean hostVisible, String label) {
        this.nativeId = 0L;
        this.vma = vma;
        this.handle = handle;
        this.allocation = allocation;
        this.deviceAddress = deviceAddress;
        this.mapped = mapped;
        this.size = size;
        this.usage = usage;
        this.hostVisible = hostVisible;
        this.label = label != null ? label : "buffer";
        VulkanDiagnostics.registerBuffer(deviceAddress, size, handle, this.label);
    }

    public void destroy() {
        if (!destroyed && handle != 0L) {
            VulkanDiagnostics.unregisterBuffer(deviceAddress, handle);
            if (nativeId != 0L) {
                NativeRenderer.INSTANCE.release(nativeId);
            } else if (vma != 0L) {
                Vma.vmaDestroyBuffer(vma, handle, allocation);
            }
            destroyed = true;
        }
    }

    /** VMA allocator that owns this allocation — for diagnostics / flush helpers. */
    public long vma() {
        return vma;
    }

    /** Native resource id when the C++ renderer owns this allocation, 0 otherwise. */
    public long nativeId() {
        return nativeId;
    }

    /** Flush the entire mapped range so device reads see host writes on non-coherent memory. */
    public void flush() {
        flush(0L, size);
    }

    /** Flush a mapped subrange so device reads see host writes on non-coherent memory. */
    public void flush(long offset, long length) {
        if (!hostVisible) {
            throw new IllegalStateException("Cannot flush a non-host-visible buffer");
        }
        if (offset < 0L || length < 0L || offset > size || length > size - offset) {
            throw new IndexOutOfBoundsException("Flush range " + offset + ".." + (offset + length)
                    + " exceeds buffer size " + size);
        }
        if (nativeId != 0L) {
            NativeRenderer.INSTANCE.flushBufferRange(nativeId, offset, length);
        } else {
            Vma.vmaFlushAllocation(vma, allocation, offset, length);
        }
    }

    /**
     * Invalidate a mapped range so host reads see device writes on non-coherent memory.
     * Counterpart of {@link #flush(long, long)}: flush is host→device, invalidate is
     * device→host. On HOST_COHERENT allocations both are no-ops in the driver but remain
     * required for correctness on HOST_CACHED-without-COHERENT BAR/GTT types, where VMA
     * may place a host-visible allocation without the COHERENT bit.
     */
    public void invalidate(long offset, long length) {
        if (!hostVisible) {
            throw new IllegalStateException("Cannot invalidate a non-host-visible buffer");
        }
        if (offset < 0L || length < 0L || offset > size || length > size - offset) {
            throw new IndexOutOfBoundsException("Invalidate range " + offset + ".." + (offset + length)
                    + " exceeds buffer size " + size);
        }
        if (nativeId != 0L) {
            NativeRenderer.INSTANCE.invalidateBufferRange(nativeId, offset, length);
        } else {
            Vma.vmaInvalidateAllocation(vma, allocation, offset, length);
        }
    }
}
