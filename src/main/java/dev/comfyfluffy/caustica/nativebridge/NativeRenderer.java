package dev.comfyfluffy.caustica.nativebridge;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import org.lwjgl.vulkan.VK10;

public final class NativeRenderer {
    public static final NativeRenderer INSTANCE = new NativeRenderer();
    private static final String FIREFLY_SHADER = "/caustica/rt/firefly_kill.comp.spv";

    private long handle;
    private long generation = 1L;
    private long attachedDevice;
    private boolean unavailable;
    private boolean loggedActive;

    private NativeRenderer() {
    }

    public static boolean tryAttach(RtContext ctx) {
        return INSTANCE.attach(ctx) != 0L;
    }

    public synchronized boolean recordFirefly(RtContext ctx, long commandBuffer, long frameId,
                                               RtImage input, RtImage output) {
        if (unavailable || ctx == null || commandBuffer == 0L || input == null || output == null
                || !ensureAttached(ctx)) {
            return false;
        }
        ByteBuffer desc = NativeRendererAbi.fireflyDesc(frameId, commandBuffer, input, output,
                VK10.VK_FORMAT_B10G11R11_UFLOAT_PACK32, generation);
        ByteBuffer result = NativeRendererAbi.allocate(NativeRendererAbi.FRAME_RESULT_SIZE);
        int prepare = nativePrepareFirefly(handle, desc, result);
        if (prepare != NativeRendererAbi.STATUS_OK) {
            return false;
        }
        int record = nativeRecordFirefly(handle, desc, result);
        boolean committed = record == NativeRendererAbi.STATUS_OK
                && result.getInt(4) == NativeRendererAbi.COMMIT_RECORDED;
        if (committed && !loggedActive) {
            loggedActive = true;
            CausticaMod.LOGGER.info("C++ renderer live path active: native firefly pass recorded");
        }
        return committed;
    }

    public synchronized void resize(int width, int height) {
        generation++;
        if (handle != 0L) {
            nativeResize(handle, width, height, generation);
        }
    }

    public synchronized void beginReload() {
        generation++;
        if (handle != 0L) {
            nativeBeginReload(handle, generation);
        }
    }

    public synchronized void endReload() {
        if (handle != 0L) {
            nativeEndReload(handle, generation);
        }
    }

    public synchronized void invalidateHistory() {
        if (handle != 0L) {
            nativeInvalidateHistory(handle);
        }
    }

    public synchronized long fireflyDispatchCount() {
        return handle != 0L ? nativeFireflyDispatchCount(handle) : 0L;
    }

    public synchronized String status() {
        return handle != 0L ? nativeStatus(handle) : "native renderer unavailable";
    }

    public synchronized void destroy() {
        if (handle != 0L) {
            nativeDetachDevice(handle);
            nativeDestroy(handle);
        }
        handle = 0L;
        attachedDevice = 0L;
        loggedActive = false;
    }

    public synchronized boolean isAttached() {
        return handle != 0L && attachedDevice != 0L;
    }

    public synchronized long attach(RtContext ctx) {
        if (!ensureAttached(ctx)) {
            return 0L;
        }
        return handle;
    }

    public synchronized long createBuffer(long sizeBytes, int usage, int flags, long addressAlignment) {
        if (!isAttached()) {
            return 0L;
        }
        ByteBuffer out = NativeRendererAbi.allocate(NativeRendererAbi.BUFFER_HANDLE_SIZE);
        int status = nativeCreateBuffer(handle, sizeBytes, usage, flags, addressAlignment, 0L, out);
        if (status != NativeRendererAbi.STATUS_OK) {
            return 0L;
        }
        long resourceId = out.getLong(NativeRendererAbi.HANDLE_ID_OFFSET);
        ByteBuffer handleOut = NativeRendererAbi.allocate(NativeRendererAbi.BUFFER_HANDLE_SIZE);
        if (nativeLookupBuffer(handle, resourceId, handleOut) != NativeRendererAbi.STATUS_OK) {
            release(resourceId);
            return 0L;
        }
        handleOut.putLong(NativeRendererAbi.HANDLE_ID_OFFSET, resourceId);
        return resourceId;
    }

    public synchronized boolean lookupBuffer(long resourceId, ByteBuffer out) {
        if (!isAttached() || resourceId == 0L) {
            return false;
        }
        return nativeLookupBuffer(handle, resourceId, out) == NativeRendererAbi.STATUS_OK;
    }

    public synchronized boolean lookupImage(long resourceId, ByteBuffer out) {
        if (!isAttached() || resourceId == 0L) {
            return false;
        }
        return nativeLookupImage(handle, resourceId, out) == NativeRendererAbi.STATUS_OK;
    }

    public synchronized long createStorageImage(int width, int height, int format, int extraUsage) {
        if (!isAttached()) {
            return 0L;
        }
        ByteBuffer out = NativeRendererAbi.allocate(NativeRendererAbi.IMAGE_HANDLE_SIZE);
        int status = nativeCreateStorageImage(handle, width, height, format, extraUsage, 0L, out);
        return status == NativeRendererAbi.STATUS_OK ? out.getLong(NativeRendererAbi.HANDLE_ID_OFFSET) : 0L;
    }

    public synchronized long createTransientMsaaImage(int width, int height, int format, int samples) {
        if (!isAttached()) {
            return 0L;
        }
        ByteBuffer out = NativeRendererAbi.allocate(NativeRendererAbi.IMAGE_HANDLE_SIZE);
        int status = nativeCreateTransientMsaaImage(handle, width, height, format, samples, 0L, out);
        return status == NativeRendererAbi.STATUS_OK ? out.getLong(NativeRendererAbi.HANDLE_ID_OFFSET) : 0L;
    }

    public synchronized boolean uploadDeviceLocal(long sizeBytes, int usage, long hostPtr, long hostBytes,
                                               long addressAlignment) {
        if (!isAttached() || hostPtr == 0L || hostBytes <= 0L) {
            return false;
        }
        ByteBuffer out = NativeRendererAbi.allocate(NativeRendererAbi.BUFFER_HANDLE_SIZE);
        int status = nativeUploadDeviceLocal(handle, sizeBytes, usage, hostPtr, hostBytes,
                addressAlignment, 0L, out);
        return status == NativeRendererAbi.STATUS_OK;
    }

    public synchronized boolean release(long resourceId) {
        if (!isAttached() || resourceId == 0L) {
            return false;
        }
        return nativeReleaseResource(handle, resourceId) == NativeRendererAbi.STATUS_OK;
    }

    public synchronized boolean flushBufferRange(long resourceId, long offset, long length) {
        if (!isAttached() || resourceId == 0L) {
            return false;
        }
        return nativeFlushBufferRange(handle, resourceId, offset, length)
                == NativeRendererAbi.STATUS_OK;
    }

    public synchronized boolean invalidateBufferRange(long resourceId, long offset, long length) {
        if (!isAttached() || resourceId == 0L) {
            return false;
        }
        return nativeInvalidateBufferRange(handle, resourceId, offset, length)
                == NativeRendererAbi.STATUS_OK;
    }

    public synchronized int memoryTypeOf(long resourceId) {
        if (!isAttached() || resourceId == 0L) {
            return -1;
        }
        ByteBuffer out = NativeRendererAbi.allocate(4);
        if (nativeMemoryTypeOf(handle, resourceId, out) != NativeRendererAbi.STATUS_OK) {
            return -1;
        }
        return out.getInt(0);
    }

    public synchronized boolean recordFrame(RtContext ctx, long commandBuffer, long frameId,
                                            int renderW, int renderH, int displayW, int displayH,
                                            int passMask, RtImage radiance, RtImage fireflyKilled,
                                            RtImage denoisedColor, RtImage displayImage,
                                            RtImage rrOutput, RtImage mainTarget) {
        if (!ensureAttached(ctx) || passMask == 0 || commandBuffer == 0L) {
            return false;
        }
        java.nio.ByteBuffer input = NativeRendererAbi.allocate(NativeRendererAbi.FRAME_INPUT_SIZE);
        java.nio.ByteBuffer result = NativeRendererAbi.allocate(NativeRendererAbi.FRAME_RESULT_SIZE);
        populateFrameInput(input, frameId, commandBuffer, renderW, renderH, displayW, displayH,
                passMask, generation, radiance, fireflyKilled, denoisedColor, displayImage,
                rrOutput, mainTarget);
        int prepareStatus = nativePrepareFrame(handle, input, result);
        if (prepareStatus != NativeRendererAbi.STATUS_OK) {
            return false;
        }
        int recordStatus = nativeRecordFrame(handle, input, result);
        return recordStatus == NativeRendererAbi.STATUS_OK
                && result.getInt(4) == NativeRendererAbi.COMMIT_RECORDED;
    }

    public synchronized long createWorldPipeline(RtContext ctx, String rgenShader, String[] missShaders,
                                                  String chitShader, String ahitShader,
                                                  int pushConstantSize, boolean withAtlasSampler,
                                                  int extraStorageCount, int bindlessCapacity,
                                                  boolean blockMaterialAtlases, boolean skyAtlas,
                                                  boolean positionFetch, boolean opacityMicromap,
                                                  long generation) {
        if (!ensureAttached(ctx) || !isAttached()) {
            CausticaMod.LOGGER.warn("Native RT pipeline skipped: backend unavailable (ctx={}, attached={})",
                    ctx == null ? "null" : "non-null", isAttached());
            return 0L;
        }
        // TEMP: simplify to diagnose RADV crash. Drop bindless + block material atlases + sky.
        bindlessCapacity = 0;
        blockMaterialAtlases = false;
        skyAtlas = false;
        withAtlasSampler = false;
        // RADV skip is intentional — debug so the boot log does not cry wolf every session.
        CausticaMod.LOGGER.debug("Native RT pipeline: SIMPLIFIED (no bindless, no atlas samplers) to isolate RADV crash");
        int rgenId;
        int[] missIds;
        int chitId;
        int ahitId;
        try {
            rgenId = registerWorldShader(rgenShader);
            missIds = new int[missShaders == null ? 0 : missShaders.length];
            for (int i = 0; i < missIds.length; ++i) {
                missIds[i] = registerWorldShader(missShaders[i]);
            }
            chitId = registerWorldShader(chitShader);
            ahitId = ahitShader == null ? 0 : registerWorldShader(ahitShader);
        } catch (IOException e) {
            CausticaMod.LOGGER.warn("Native RT pipeline shader registration failed: {}", e.toString());
            return 0L;
        }
        java.nio.ByteBuffer desc = NativeRendererAbi.allocate(NativeRendererAbi.WORLD_PIPELINE_DESC_SIZE);
        desc.putInt(0, NativeRendererAbi.WORLD_PIPELINE_DESC_SIZE);
        desc.putInt(4, NativeRendererAbi.ABI_V2);
        desc.putInt(8, rgenId);
        desc.putInt(12, chitId);
        desc.putInt(16, ahitId);
        desc.putInt(20, missIds.length);
        for (int i = 0; i < 8; ++i) {
            desc.putInt(24 + i * 4, i < missIds.length ? missIds[i] : 0);
        }
        desc.putInt(56, pushConstantSize);
        desc.putInt(60, withAtlasSampler ? 1 : 0);
        desc.putInt(64, extraStorageCount);
        desc.putInt(68, extraStorageBufferSlots());
        desc.putInt(72, bindlessCapacity);
        desc.putInt(76, blockMaterialAtlases ? 1 : 0);
        desc.putInt(80, skyAtlas ? 1 : 0);
        desc.putInt(84, positionFetch ? 1 : 0);
        desc.putInt(88, opacityMicromap ? 1 : 0);
        desc.putInt(92, 0);
        desc.putInt(96, 0);
        desc.putLong(104, generation);
        long pipelineId = nativeCreateWorldPipeline(handle, desc);
        if (pipelineId == 0L) {
            // RADV skip is intentional (world_pipeline.cpp) — debug there, warn elsewhere.
            if (RtDeviceBringup.isRadv()) {
                CausticaMod.LOGGER.debug("Native RT pipeline skipped on RADV for rgen={} chit={} ahit={} miss={}",
                        rgenShader, chitShader, ahitShader, missShaders == null ? 0 : missShaders.length);
            } else {
                CausticaMod.LOGGER.warn("Native RT pipeline create returned 0 for rgen={} chit={} ahit={} miss={}",
                        rgenShader, chitShader, ahitShader, missShaders == null ? 0 : missShaders.length);
            }
        } else {
            CausticaMod.LOGGER.info("Native RT pipeline created (id={}, miss={}, bindless={})",
                    pipelineId, missIds.length, bindlessCapacity);
        }
        return pipelineId;
    }

    private static int extraStorageBufferSlots() {
        return 0x4; // bit 2 = block light buffer (SSBO) per existing Java RtPipeline layout
    }

    private int registerWorldShader(String shaderPath) throws IOException {
        if (shaderPath == null || shaderPath.isEmpty()) {
            return 0;
        }
        final String resourcePath;
        if (shaderPath.startsWith("/")) {
            resourcePath = shaderPath;
        } else {
            resourcePath = "/caustica/rt/" + shaderPath;
        }
        byte[] bytes = readShaderBytes(resourcePath);
        java.nio.ByteBuffer buf = NativeRendererAbi.allocate(bytes.length);
        buf.put(bytes).flip();
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes);
        long hash = crc.getValue();
        int shaderId = (int) (hash & 0x7FFFFFFFL);
        if (shaderId == 0) {
            shaderId = 1;
        }
        int status = nativeRegisterSpirv(handle, shaderId, buf, hash);
        if (status != NativeRendererAbi.STATUS_OK) {
            throw new IOException("nativeRegisterSpirv failed for " + shaderPath + ": " + status);
        }
        return shaderId;
    }

    private static byte[] readShaderBytes(String resourcePath) throws IOException {
        ClassLoader cl = NativeRenderer.class.getClassLoader();
        try (java.io.InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (java.util.zip.ZipException ignored) {
            // Some Fabric classloader paths return a corrupted ZipFile stream; fall through to
            // Class.getResourceAsStream which goes through the canonical classpath URL handler.
        }
        try (java.io.InputStream in = NativeRenderer.class.getResourceAsStream(resourcePath)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (java.util.zip.ZipException ignored) {
            // Continue to URL-based fallback below.
        }
        java.net.URL url = NativeRenderer.class.getResource(resourcePath);
        if (url == null) {
            throw new IOException("missing shader: " + resourcePath);
        }
        try (java.io.InputStream in = url.openStream()) {
            return in.readAllBytes();
        }
    }

    public synchronized boolean worldSetTlas(long pipelineId, long tlas) {
        if (!isAttached() || pipelineId == 0L) {
            return false;
        }
        return nativeWorldSetTlas(handle, pipelineId, tlas) == NativeRendererAbi.STATUS_OK;
    }

    public synchronized boolean worldSetStorageImage(long pipelineId, long imageView) {
        if (!isAttached() || pipelineId == 0L) {
            return false;
        }
        return nativeWorldSetStorageImage(handle, pipelineId, imageView) == NativeRendererAbi.STATUS_OK;
    }

    public synchronized boolean worldSetExtraStorageImage(long pipelineId, int slot, long imageView) {
        if (!isAttached() || pipelineId == 0L) {
            return false;
        }
        return nativeWorldSetExtraStorageImage(handle, pipelineId, slot, imageView)
                == NativeRendererAbi.STATUS_OK;
    }

    public synchronized boolean worldSetExtraStorageBuffer(long pipelineId, int slot, long bufferHandle,
                                                            long sizeBytes) {
        if (!isAttached() || pipelineId == 0L) {
            return false;
        }
        return nativeWorldSetExtraStorageBuffer(handle, pipelineId, slot, bufferHandle, sizeBytes)
                == NativeRendererAbi.STATUS_OK;
    }

    public synchronized boolean worldSetAtlasSampler(long pipelineId, long imageView, long sampler) {
        if (!isAttached() || pipelineId == 0L) {
            return false;
        }
        return nativeWorldSetAtlasSampler(handle, pipelineId, imageView, sampler)
                == NativeRendererAbi.STATUS_OK;
    }

    public synchronized boolean worldSetBlockSpecAtlas(long pipelineId, long imageView, long sampler) {
        if (!isAttached() || pipelineId == 0L) {
            return false;
        }
        return nativeWorldSetBlockSpecAtlas(handle, pipelineId, imageView, sampler)
                == NativeRendererAbi.STATUS_OK;
    }

    public synchronized boolean worldSetBlockNormalAtlas(long pipelineId, long imageView, long sampler) {
        if (!isAttached() || pipelineId == 0L) {
            return false;
        }
        return nativeWorldSetBlockNormalAtlas(handle, pipelineId, imageView, sampler)
                == NativeRendererAbi.STATUS_OK;
    }

    public synchronized boolean worldSetSkyAtlas(long pipelineId, long imageView, long sampler) {
        if (!isAttached() || pipelineId == 0L) {
            return false;
        }
        return nativeWorldSetSkyAtlas(handle, pipelineId, imageView, sampler)
                == NativeRendererAbi.STATUS_OK;
    }

    public synchronized boolean worldInitBindlessFallback(long pipelineId, long imageView, long sampler) {
        if (!isAttached() || pipelineId == 0L) {
            return false;
        }
        return nativeWorldInitBindlessFallback(handle, pipelineId, imageView, sampler)
                == NativeRendererAbi.STATUS_OK;
    }

    public synchronized boolean worldSetBindlessTexture(long pipelineId, int binding, int slot,
                                                       long imageView, long sampler) {
        if (!isAttached() || pipelineId == 0L) {
            return false;
        }
        return nativeWorldSetBindlessTexture(handle, pipelineId, binding, slot, imageView, sampler)
                == NativeRendererAbi.STATUS_OK;
    }

    public synchronized boolean worldTrace(long pipelineId, long commandBuffer, int width, int height,
                                            ByteBuffer pushConstants) {
        if (!isAttached() || pipelineId == 0L) {
            return false;
        }
        java.nio.ByteBuffer buf = pushConstants != null ? pushConstants : null;
        return nativeWorldTrace(handle, pipelineId, commandBuffer, width, height, buf)
                == NativeRendererAbi.STATUS_OK;
    }

    public synchronized void destroyWorldPipeline(long pipelineId) {
        if (!isAttached() || pipelineId == 0L) {
            return;
        }
        nativeDestroyWorldPipeline(handle, pipelineId);
    }

    public synchronized long generation() {
        return generation;
    }

    static RtContext lastContext() {
        return lastContext;
    }

    private static volatile RtContext lastContext;

    private static void populateFrameInput(java.nio.ByteBuffer input, long frameId, long commandBuffer,
                                           int renderW, int renderH, int displayW, int displayH,
                                           int passMask, long generation,
                                           RtImage radiance, RtImage fireflyKilled,
                                           RtImage denoisedColor, RtImage displayImage,
                                           RtImage rrOutput, RtImage mainTarget) {
        input.putInt(0, NativeRendererAbi.FRAME_INPUT_SIZE);
        input.putInt(4, NativeRendererAbi.ABI_V2);
        input.putLong(8, frameId);
        input.putLong(16, commandBuffer);
        input.putInt(24, renderW);
        input.putInt(28, renderH);
        input.putInt(32, displayW);
        input.putInt(36, displayH);
        input.putInt(40, passMask);
        input.putInt(44, 0);
        putImage(input, 48, radiance, generation);
        putImage(input, 104, fireflyKilled, generation);
        putImage(input, 160, denoisedColor, generation);
        putImage(input, 216, displayImage, generation);
        putImage(input, 272, rrOutput, generation);
        putImage(input, 328, mainTarget, generation);
        input.putLong(384, 0L);
        input.putLong(392, 0L);
        input.putInt(400, 0);
        input.putInt(404, 0);
        input.putFloat(408, 0f);
        input.putFloat(412, 0f);
        input.putFloat(416, 0f);
        input.putInt(420, 0);
        input.putInt(424, 0);
        input.putFloat(428, 0f);
        input.putFloat(432, 0f);
        input.putInt(436, (int) generation);
        input.putInt(440, 0);
    }

    private static void putImage(java.nio.ByteBuffer input, int offset, RtImage image, long generation) {
        if (image == null) {
            return;
        }
        input.putInt(offset, NativeRendererAbi.IMAGE_REF_SIZE);
        input.putInt(offset + 4, NativeRendererAbi.ABI_V2);
        input.putLong(offset + 8, image.image);
        input.putLong(offset + 16, image.view);
        input.putInt(offset + 24, 0);
        input.putInt(offset + 28, image.width);
        input.putInt(offset + 32, image.height);
        input.putInt(offset + 36, 1);
        input.putInt(offset + 40, 2);
        input.putInt(offset + 44, 0);
        input.putLong(offset + 48, generation);
    }

    private boolean ensureAttached(RtContext ctx) {
        if (!NativeBridge.isLoaded()) {
            CausticaMod.LOGGER.warn("C++ renderer attach: native lib not loaded");
            return false;
        }
        try {
            if (handle == 0L) {
                int abi = nativeAbiVersion();
                CausticaMod.LOGGER.info("C++ renderer ABI probe: native={} expected={} loaded={}",
                        abi, NativeRendererAbi.ABI_V2, NativeBridge.isLoaded());
                if (abi != NativeRendererAbi.ABI_V2) {
                    unavailable = true;
                    CausticaMod.LOGGER.warn("C++ renderer ABI mismatch (got {} expected {}); using Java backend",
                            abi, NativeRendererAbi.ABI_V2);
                    return false;
                }
                handle = nativeCreate();
                if (handle == 0L) {
                    unavailable = true;
                    CausticaMod.LOGGER.warn("C++ renderer nativeCreate returned 0");
                    return false;
                }
            }
            lastContext = ctx;
            long device = ctx.vk().address();
            if (attachedDevice == device) {
                return true;
            }
            if (attachedDevice != 0L) {
                nativeDetachDevice(handle);
            }
            long driverFlags = RtDeviceBringup.isRadv() ? 1L : 0L;
            ByteBuffer deviceDesc = NativeRendererAbi.deviceDesc(
                    ctx.vk().getPhysicalDevice().getInstance().address(),
                    ctx.vk().getPhysicalDevice().address(), device,
                    ctx.device().graphicsQueue().vkQueue().address(),
                    ctx.device().graphicsQueue().queueFamilyIndex(), driverFlags, generation);
            int attachStatus = nativeAttachDevice(handle, deviceDesc);
            if (attachStatus != NativeRendererAbi.STATUS_OK) {
                CausticaMod.LOGGER.warn("C++ renderer nativeAttachDevice status={}", attachStatus);
                return false;
            }
            registerShader();
            attachedDevice = device;
            return true;
        } catch (Throwable t) {
            unavailable = true;
            CausticaMod.LOGGER.warn("C++ renderer attach failed; using Java backend", t);
            return false;
        }
    }

    private void registerShader() throws IOException {
        byte[] bytes;
        try (InputStream in = NativeRenderer.class.getResourceAsStream(FIREFLY_SHADER)) {
            if (in == null) {
                throw new IOException("Missing " + FIREFLY_SHADER);
            }
            bytes = in.readAllBytes();
        }
        ByteBuffer shader = NativeRendererAbi.allocate(bytes.length);
        shader.put(bytes).flip();
        CRC32 crc = new CRC32();
        crc.update(bytes);
        int status = nativeRegisterSpirv(handle, 1, shader, crc.getValue());
        if (status != NativeRendererAbi.STATUS_OK) {
            throw new IOException("native shader registration failed: " + status);
        }
    }

    private static native int nativeAbiVersion();
    private static native long nativeCreate();
    private static native void nativeDestroy(long handle);
    private static native int nativeAttachDevice(long handle, ByteBuffer desc);
    private static native void nativeDetachDevice(long handle);
    private static native int nativeRegisterSpirv(long handle, int shaderId, ByteBuffer bytes,
                                                   long contentHash);
    private static native int nativePrepareFirefly(long handle, ByteBuffer desc, ByteBuffer result);
    private static native int nativeRecordFirefly(long handle, ByteBuffer desc, ByteBuffer result);
    private static native void nativeResize(long handle, int width, int height, long generation);
    private static native void nativeBeginReload(long handle, long generation);
    private static native void nativeEndReload(long handle, long generation);
    private static native void nativeInvalidateHistory(long handle);
    private static native long nativeFireflyDispatchCount(long handle);
    private static native String nativeStatus(long handle);
    private static native int nativeCreateBuffer(long handle, long sizeBytes, int usage, int flags,
                                                 long addressAlignment, long labelHash,
                                                 ByteBuffer out);
    private static native int nativeCreateStorageImage(long handle, int width, int height,
                                                        int format, int extraUsage, long labelHash,
                                                        ByteBuffer out);
    private static native int nativeCreateTransientMsaaImage(long handle, int width, int height,
                                                              int format, int samples, long labelHash,
                                                              ByteBuffer out);
    private static native int nativeUploadDeviceLocal(long handle, long sizeBytes, int usage,
                                                       long hostPtr, long hostBytes,
                                                       long addressAlignment, long labelHash,
                                                       ByteBuffer out);
    private static native int nativeReleaseResource(long handle, long resourceId);
    private static native int nativeFlushBufferRange(long handle, long bufferHandle, long offset,
                                                     long length);
    private static native int nativeInvalidateBufferRange(long handle, long bufferHandle, long offset,
                                                          long length);
    private static native int nativeMemoryTypeOf(long handle, long bufferHandle, ByteBuffer out);
    private static native int nativeLookupBuffer(long handle, long bufferHandle, ByteBuffer out);
    private static native int nativeLookupImage(long handle, long imageHandle, ByteBuffer out);
    private static native int nativePrepareFrame(long handle, ByteBuffer input, ByteBuffer result);
    private static native int nativeRecordFrame(long handle, ByteBuffer input, ByteBuffer result);
    private static native long nativeCreateWorldPipeline(long handle, ByteBuffer desc);
    private static native void nativeDestroyWorldPipeline(long handle, long pipeline);
    private static native int nativeWorldSetTlas(long handle, long pipeline, long tlasHandle);
    private static native int nativeWorldSetStorageImage(long handle, long pipeline, long imageView);
    private static native int nativeWorldSetExtraStorageImage(long handle, long pipeline, int slot,
                                                            long imageView);
    private static native int nativeWorldSetExtraStorageBuffer(long handle, long pipeline, int slot,
                                                             long bufferHandle, long sizeBytes);
    private static native int nativeWorldSetAtlasSampler(long handle, long pipeline, long imageView,
                                                       long sampler);
    private static native int nativeWorldSetBlockSpecAtlas(long handle, long pipeline, long imageView,
                                                           long sampler);
    private static native int nativeWorldSetBlockNormalAtlas(long handle, long pipeline, long imageView,
                                                             long sampler);
    private static native int nativeWorldSetSkyAtlas(long handle, long pipeline, long imageView,
                                                    long sampler);
    private static native int nativeWorldInitBindlessFallback(long handle, long pipeline, long imageView,
                                                              long sampler);
    private static native int nativeWorldSetBindlessTexture(long handle, long pipeline, int binding,
                                                           int slot, long imageView, long sampler);
    private static native int nativeWorldTrace(long handle, long pipeline, long commandBuffer,
                                                int width, int height, ByteBuffer pushConstants);
}
