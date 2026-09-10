package dev.comfyfluffy.caustica.nativebridge;

import dev.comfyfluffy.caustica.rt.accel.RtImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class NativeRendererAbi {
    public static final int ABI_V1 = 1;
    public static final int ABI_V2 = 2;
    public static final int DEVICE_DESC_SIZE = 88;
    public static final int IMAGE_REF_SIZE = 56;
    public static final int FIREFLY_DESC_SIZE = 136;
    public static final int FRAME_RESULT_SIZE = 40;
    public static final int FRAME_INPUT_SIZE = 240;
    public static final int BUFFER_HANDLE_SIZE = 96;
    public static final int IMAGE_HANDLE_SIZE = 80;
    public static final int WORLD_PIPELINE_DESC_SIZE = 120;
    public static final int HANDLE_ID_OFFSET = 8;

    public static final int BUFFER_FLAG_HOST_VISIBLE = 1;
    public static final int BUFFER_FLAG_SHADER_DEVICE_ADDRESS = 2;

    public static final int PASS_FIREFLY = 1;
    public static final int PASS_DENOISE = 2;
    public static final int PASS_TAA = 4;
    public static final int PASS_UPSCALE = 8;
    public static final int PASS_CAS = 16;
    public static final int PASS_EXPOSURE = 32;
    public static final int PASS_DISPLAY = 64;
    public static final int PASS_COPY = 128;

    public static final int BUFFER_OFFSET_HANDLE = 8;
    public static final int BUFFER_OFFSET_ALLOCATION = 16;
    public static final int BUFFER_OFFSET_DEVICE_ADDRESS = 24;
    public static final int BUFFER_OFFSET_MAPPED = 32;
    public static final int BUFFER_OFFSET_SIZE = 40;

    public static final int IMAGE_OFFSET_HANDLE = 8;
    public static final int IMAGE_OFFSET_ALLOCATION = 16;
    public static final int IMAGE_OFFSET_VIEW = 24;

    public static final int STATUS_OK = 0;
    public static final int COMMIT_RECORDED = 2;

    private NativeRendererAbi() {
    }

    public static ByteBuffer allocate(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    public static ByteBuffer deviceDesc(long instance, long physicalDevice, long device,
                                        long graphicsQueue, int graphicsFamily,
                                        long driverFlags, long generation) {
        ByteBuffer out = allocate(DEVICE_DESC_SIZE);
        out.putInt(0, DEVICE_DESC_SIZE);
        out.putInt(4, ABI_V1);
        out.putLong(8, instance);
        out.putLong(16, physicalDevice);
        out.putLong(24, device);
        out.putLong(32, graphicsQueue);
        out.putLong(40, 0L);
        out.putInt(48, graphicsFamily);
        out.putInt(52, 0);
        out.putInt(56, 0xFFFF_FFFF);
        out.putInt(60, 0);
        out.putLong(64, 0L);
        out.putLong(72, driverFlags);
        out.putLong(80, generation);
        return out;
    }

    public static ByteBuffer fireflyDesc(long frameId, long commandBuffer, RtImage input,
                                         RtImage output, int format, long generation) {
        ByteBuffer out = allocate(FIREFLY_DESC_SIZE);
        out.putInt(0, FIREFLY_DESC_SIZE);
        out.putInt(4, ABI_V1);
        out.putLong(8, frameId);
        out.putLong(16, commandBuffer);
        putImage(out, 24, input, format, generation);
        putImage(out, 80, output, format, generation);
        return out;
    }

    private static void putImage(ByteBuffer out, int offset, RtImage image, int format,
                                 long generation) {
        out.putInt(offset, IMAGE_REF_SIZE);
        out.putInt(offset + 4, ABI_V1);
        out.putLong(offset + 8, image.image);
        out.putLong(offset + 16, image.view);
        out.putInt(offset + 24, format);
        out.putInt(offset + 28, image.width);
        out.putInt(offset + 32, image.height);
        out.putInt(offset + 36, 1); // VK_IMAGE_LAYOUT_GENERAL
        out.putInt(offset + 40, 2); // CAUSTICA_OWNER_JAVA_LEGACY
        out.putInt(offset + 44, 0);
        out.putLong(offset + 48, generation);
    }
}
