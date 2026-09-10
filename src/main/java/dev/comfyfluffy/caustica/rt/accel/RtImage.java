package dev.comfyfluffy.caustica.rt.accel;

import dev.comfyfluffy.caustica.nativebridge.NativeRenderer;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;

/**
 * A Vulkan image + view, created in {@code VK_IMAGE_LAYOUT_GENERAL}. Used for RT output storage
 * images. Created via {@link dev.comfyfluffy.caustica.rt.RtContext#createStorageImage}; freed with
 * {@link #destroy()}. When the native backend owns the allocation ({@code nativeId != 0}),
 * {@link #destroy()} forwards to the C++ renderer; otherwise it falls back to the local Java
 * Vulkan + VMA path so the fallback remains allocation-safe.
 */
public final class RtImage {
    public final long image;
    public final long allocation;
    public final long view;
    public final int width;
    public final int height;

    private final long vma;
    private final VkDevice vk;
    private final long nativeId;
    private boolean destroyed;

    /** Native-backed allocation owned by the C++ renderer. */
    public RtImage(long nativeId, long image, long allocation, long view, int width, int height) {
        this.nativeId = nativeId;
        this.vma = 0L;
        this.vk = null;
        this.image = image;
        this.allocation = allocation;
        this.view = view;
        this.width = width;
        this.height = height;
    }

    /** Legacy Java-VMA-owned image used by the native-fallback path. */
    public RtImage(long vma, VkDevice vk, long image, long allocation, long view, int width, int height) {
        this.nativeId = 0L;
        this.vma = vma;
        this.vk = vk;
        this.image = image;
        this.allocation = allocation;
        this.view = view;
        this.width = width;
        this.height = height;
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        if (nativeId != 0L) {
            NativeRenderer.INSTANCE.release(nativeId);
        } else {
            if (view != 0L && vk != null) {
                VK10.vkDestroyImageView(vk, view, null);
            }
            if (image != 0L && vma != 0L) {
                Vma.vmaDestroyImage(vma, image, allocation);
            }
        }
        destroyed = true;
    }
}
