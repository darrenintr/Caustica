#include "caustica_backend.h"

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <exception>

namespace {

template <typename T>
T* direct_struct(JNIEnv* env, jobject buffer) {
    if (buffer == nullptr || env->GetDirectBufferCapacity(buffer) < static_cast<jlong>(sizeof(T))) {
        return nullptr;
    }
    return static_cast<T*>(env->GetDirectBufferAddress(buffer));
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeAbiVersion(JNIEnv*, jclass) {
    return static_cast<jint>(ca_get_abi_version());
}

JNIEXPORT jlong JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeCreate(JNIEnv*, jclass) {
    try {
        return static_cast<jlong>(ca_create_backend());
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeDestroy(JNIEnv*, jclass,
                                                                         jlong handle) {
    try {
        ca_destroy_backend(static_cast<ca_backend_t>(handle));
    } catch (...) {
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeAttachDevice(
        JNIEnv* env, jclass, jlong handle, jobject desc_buffer) {
    try {
        const auto* desc = direct_struct<ca_device_desc>(env, desc_buffer);
        return desc != nullptr
                ? ca_attach_device(static_cast<ca_backend_t>(handle), desc)
                : CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT void JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeDetachDevice(JNIEnv*, jclass,
                                                                              jlong handle) {
    try {
        ca_detach_device(static_cast<ca_backend_t>(handle));
    } catch (...) {
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeRegisterSpirv(
        JNIEnv* env, jclass, jlong handle, jint shader_id, jobject bytes_buffer, jlong content_hash) {
    try {
        if (bytes_buffer == nullptr) {
            return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
        }
        const jlong capacity = env->GetDirectBufferCapacity(bytes_buffer);
        const void* bytes = env->GetDirectBufferAddress(bytes_buffer);
        if (capacity <= 0 || bytes == nullptr) {
            return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
        }
        return ca_register_spirv(static_cast<ca_backend_t>(handle),
                                 static_cast<uint32_t>(shader_id), bytes,
                                 static_cast<size_t>(capacity),
                                 static_cast<uint64_t>(content_hash));
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativePrepareFirefly(
        JNIEnv* env, jclass, jlong handle, jobject desc_buffer, jobject result_buffer) {
    try {
        const auto* desc = direct_struct<ca_firefly_desc>(env, desc_buffer);
        auto* result = direct_struct<ca_frame_result>(env, result_buffer);
        if (desc == nullptr || result == nullptr) {
            return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
        }
        *result = ca_prepare_firefly(static_cast<ca_backend_t>(handle), desc);
        return result->status;
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeRecordFirefly(
        JNIEnv* env, jclass, jlong handle, jobject desc_buffer, jobject result_buffer) {
    try {
        const auto* desc = direct_struct<ca_firefly_desc>(env, desc_buffer);
        auto* result = direct_struct<ca_frame_result>(env, result_buffer);
        if (desc == nullptr || result == nullptr) {
            return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
        }
        *result = ca_record_firefly(static_cast<ca_backend_t>(handle), desc);
        return result->status;
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT void JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeResize(
        JNIEnv*, jclass, jlong handle, jint width, jint height, jlong generation) {
    try {
        ca_resize(static_cast<ca_backend_t>(handle), static_cast<uint32_t>(width),
                  static_cast<uint32_t>(height), static_cast<uint64_t>(generation));
    } catch (...) {
    }
}

JNIEXPORT void JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeBeginReload(
        JNIEnv*, jclass, jlong handle, jlong generation) {
    try {
        ca_begin_reload(static_cast<ca_backend_t>(handle), static_cast<uint64_t>(generation));
    } catch (...) {
    }
}

JNIEXPORT void JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeEndReload(
        JNIEnv*, jclass, jlong handle, jlong generation) {
    try {
        ca_end_reload(static_cast<ca_backend_t>(handle), static_cast<uint64_t>(generation));
    } catch (...) {
    }
}

JNIEXPORT void JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeInvalidateHistory(
        JNIEnv*, jclass, jlong handle) {
    try {
        ca_invalidate_history(static_cast<ca_backend_t>(handle));
    } catch (...) {
    }
}

JNIEXPORT jlong JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeFireflyDispatchCount(
        JNIEnv*, jclass, jlong handle) {
    try {
        return static_cast<jlong>(ca_native_firefly_dispatch_count(
                static_cast<ca_backend_t>(handle)));
    } catch (...) {
        return 0;
    }
}

JNIEXPORT jstring JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeStatus(
        JNIEnv* env, jclass, jlong handle) {
    try {
        return env->NewStringUTF(ca_get_status(static_cast<ca_backend_t>(handle)));
    } catch (...) {
        return env->NewStringUTF("native status unavailable");
    }
}

} // extern "C"

namespace {

template <typename T>
T* direct_buffer(JNIEnv* env, jobject buffer) {
    if (buffer == nullptr || env->GetDirectBufferCapacity(buffer) < static_cast<jlong>(sizeof(T))) {
        return nullptr;
    }
    return static_cast<T*>(env->GetDirectBufferAddress(buffer));
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeCreateBuffer(
        JNIEnv* env, jclass, jlong handle, jlong size_bytes, jint usage, jint flags,
        jlong address_alignment, jlong label_hash, jobject out_buffer) {
    try {
        auto* out = direct_buffer<ca_buffer_handle>(env, out_buffer);
        if (out == nullptr) {
            return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
        }
        return ca_backend_create_buffer(static_cast<ca_backend_t>(handle),
                                         static_cast<uint64_t>(size_bytes),
                                         static_cast<uint32_t>(usage), static_cast<uint32_t>(flags),
                                         static_cast<uint64_t>(address_alignment),
                                         static_cast<uint64_t>(label_hash), out);
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeCreateStorageImage(
        JNIEnv* env, jclass, jlong handle, jint width, jint height, jint format, jint extra_usage,
        jlong label_hash, jobject out_buffer) {
    try {
        auto* out = direct_buffer<ca_image_handle>(env, out_buffer);
        if (out == nullptr) {
            return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
        }
        return ca_backend_create_storage_image(static_cast<ca_backend_t>(handle),
                                               static_cast<uint32_t>(width),
                                               static_cast<uint32_t>(height),
                                               static_cast<uint32_t>(format),
                                               static_cast<uint32_t>(extra_usage),
                                               static_cast<uint64_t>(label_hash), out);
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeCreateTransientMsaaImage(
        JNIEnv* env, jclass, jlong handle, jint width, jint height, jint format, jint samples,
        jlong label_hash, jobject out_buffer) {
    try {
        auto* out = direct_buffer<ca_image_handle>(env, out_buffer);
        if (out == nullptr) {
            return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
        }
        return ca_backend_create_transient_msaa_image(static_cast<ca_backend_t>(handle),
                                                     static_cast<uint32_t>(width),
                                                     static_cast<uint32_t>(height),
                                                     static_cast<uint32_t>(format),
                                                     static_cast<uint32_t>(samples),
                                                     static_cast<uint64_t>(label_hash), out);
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeUploadDeviceLocal(
        JNIEnv* env, jclass, jlong handle, jlong size_bytes, jint usage, jlong host_ptr,
        jlong host_bytes, jlong address_alignment, jlong label_hash, jobject out_buffer) {
    try {
        auto* out = direct_buffer<ca_buffer_handle>(env, out_buffer);
        if (out == nullptr) {
            return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
        }
        return ca_backend_upload_device_local(static_cast<ca_backend_t>(handle),
                                              static_cast<uint64_t>(size_bytes),
                                              static_cast<uint32_t>(usage),
                                              static_cast<uint64_t>(host_ptr),
                                              static_cast<uint64_t>(host_bytes),
                                              static_cast<uint64_t>(address_alignment),
                                              static_cast<uint64_t>(label_hash), out);
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeReleaseResource(
        JNIEnv*, jclass, jlong handle, jlong resource_id) {
    try {
        return ca_backend_release_resource(static_cast<ca_backend_t>(handle),
                                            static_cast<uint64_t>(resource_id));
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeFlushBufferRange(
        JNIEnv*, jclass, jlong handle, jlong buffer_handle, jlong offset, jlong length) {
    try {
        return ca_backend_flush_buffer_range(static_cast<ca_backend_t>(handle),
                                             static_cast<uint64_t>(buffer_handle),
                                             static_cast<uint64_t>(offset),
                                             static_cast<uint64_t>(length));
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeInvalidateBufferRange(
        JNIEnv*, jclass, jlong handle, jlong buffer_handle, jlong offset, jlong length) {
    try {
        return ca_backend_invalidate_buffer_range(static_cast<ca_backend_t>(handle),
                                                  static_cast<uint64_t>(buffer_handle),
                                                  static_cast<uint64_t>(offset),
                                                  static_cast<uint64_t>(length));
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeMemoryTypeOf(
        JNIEnv* env, jclass, jlong handle, jlong buffer_handle, jobject out_buffer) {
    try {
        auto* out = direct_buffer<uint32_t>(env, out_buffer);
        if (out == nullptr) {
            return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
        }
        return ca_backend_memory_type_of(static_cast<ca_backend_t>(handle),
                                        static_cast<uint64_t>(buffer_handle), out);
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeLookupBuffer(
        JNIEnv* env, jclass, jlong handle, jlong buffer_handle, jobject out_buffer) {
    try {
        auto* out = direct_buffer<ca_buffer_handle>(env, out_buffer);
        if (out == nullptr) {
            return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
        }
        return ca_backend_lookup_buffer(static_cast<ca_backend_t>(handle),
                                        static_cast<uint64_t>(buffer_handle), out);
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeLookupImage(
        JNIEnv* env, jclass, jlong handle, jlong image_handle, jobject out_buffer) {
    try {
        auto* out = direct_buffer<ca_image_handle>(env, out_buffer);
        if (out == nullptr) {
            return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
        }
        return ca_backend_lookup_image(static_cast<ca_backend_t>(handle),
                                       static_cast<uint64_t>(image_handle), out);
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativePrepareFrame(
        JNIEnv* env, jclass, jlong handle, jobject input_buffer, jobject result_buffer) {
    try {
        const auto* input = direct_buffer<ca_frame_input>(env, input_buffer);
        auto* result = direct_buffer<ca_frame_result>(env, result_buffer);
        if (input == nullptr || result == nullptr) {
            return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
        }
        *result = ca_backend_prepare_frame(static_cast<ca_backend_t>(handle), input);
        return result->status;
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeRecordFrame(
        JNIEnv* env, jclass, jlong handle, jobject input_buffer, jobject result_buffer) {
    try {
        const auto* input = direct_buffer<ca_frame_input>(env, input_buffer);
        auto* result = direct_buffer<ca_frame_result>(env, result_buffer);
        if (input == nullptr || result == nullptr) {
            return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
        }
        *result = ca_backend_record_frame(static_cast<ca_backend_t>(handle), input);
        return result->status;
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jlong JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeCreateWorldPipeline(
        JNIEnv* env, jclass, jlong handle, jobject desc_buffer) {
    try {
        auto* desc = direct_buffer<ca_world_pipeline_desc>(env, desc_buffer);
        if (desc == nullptr) {
            return 0;
        }
        return static_cast<jlong>(ca_backend_create_world_pipeline(
                static_cast<ca_backend_t>(handle), desc));
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeDestroyWorldPipeline(
        JNIEnv*, jclass, jlong handle, jlong pipeline) {
    try {
        ca_backend_destroy_world_pipeline(static_cast<ca_backend_t>(handle),
                                          static_cast<ca_pipeline_t>(pipeline));
    } catch (...) {
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeWorldSetTlas(
        JNIEnv*, jclass, jlong handle, jlong pipeline, jlong tlas_handle) {
    try {
        return ca_backend_world_set_tlas(static_cast<ca_backend_t>(handle),
                                          static_cast<ca_pipeline_t>(pipeline),
                                          static_cast<uint64_t>(tlas_handle));
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeWorldSetStorageImage(
        JNIEnv*, jclass, jlong handle, jlong pipeline, jlong image_view) {
    try {
        return ca_backend_world_set_storage_image(static_cast<ca_backend_t>(handle),
                                                  static_cast<ca_pipeline_t>(pipeline),
                                                  static_cast<uint64_t>(image_view));
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeWorldSetExtraStorageImage(
        JNIEnv*, jclass, jlong handle, jlong pipeline, jint slot, jlong image_view) {
    try {
        return ca_backend_world_set_extra_storage_image(static_cast<ca_backend_t>(handle),
                                                       static_cast<ca_pipeline_t>(pipeline),
                                                       static_cast<uint32_t>(slot),
                                                       static_cast<uint64_t>(image_view));
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeWorldSetExtraStorageBuffer(
        JNIEnv*, jclass, jlong handle, jlong pipeline, jint slot, jlong buffer_handle, jlong size_bytes) {
    try {
        return ca_backend_world_set_extra_storage_buffer(static_cast<ca_backend_t>(handle),
                                                        static_cast<ca_pipeline_t>(pipeline),
                                                        static_cast<uint32_t>(slot),
                                                        static_cast<uint64_t>(buffer_handle),
                                                        static_cast<uint64_t>(size_bytes));
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeWorldSetAtlasSampler(
        JNIEnv*, jclass, jlong handle, jlong pipeline, jlong image_view, jlong sampler) {
    try {
        return ca_backend_world_set_atlas_sampler(static_cast<ca_backend_t>(handle),
                                                   static_cast<ca_pipeline_t>(pipeline),
                                                   static_cast<uint64_t>(image_view),
                                                   static_cast<uint64_t>(sampler));
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeWorldSetBlockSpecAtlas(
        JNIEnv*, jclass, jlong handle, jlong pipeline, jlong image_view, jlong sampler) {
    try {
        return ca_backend_world_set_block_spec_atlas(static_cast<ca_backend_t>(handle),
                                                    static_cast<ca_pipeline_t>(pipeline),
                                                    static_cast<uint64_t>(image_view),
                                                    static_cast<uint64_t>(sampler));
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeWorldSetBlockNormalAtlas(
        JNIEnv*, jclass, jlong handle, jlong pipeline, jlong image_view, jlong sampler) {
    try {
        return ca_backend_world_set_block_normal_atlas(static_cast<ca_backend_t>(handle),
                                                      static_cast<ca_pipeline_t>(pipeline),
                                                      static_cast<uint64_t>(image_view),
                                                      static_cast<uint64_t>(sampler));
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeWorldSetSkyAtlas(
        JNIEnv*, jclass, jlong handle, jlong pipeline, jlong image_view, jlong sampler) {
    try {
        return ca_backend_world_set_sky_atlas(static_cast<ca_backend_t>(handle),
                                               static_cast<ca_pipeline_t>(pipeline),
                                               static_cast<uint64_t>(image_view),
                                               static_cast<uint64_t>(sampler));
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeWorldInitBindlessFallback(
        JNIEnv*, jclass, jlong handle, jlong pipeline, jlong image_view, jlong sampler) {
    try {
        return ca_backend_world_init_bindless_fallback(static_cast<ca_backend_t>(handle),
                                                       static_cast<ca_pipeline_t>(pipeline),
                                                       static_cast<uint64_t>(image_view),
                                                       static_cast<uint64_t>(sampler));
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeWorldSetBindlessTexture(
        JNIEnv*, jclass, jlong handle, jlong pipeline, jint binding, jint slot,
        jlong image_view, jlong sampler) {
    try {
        return ca_backend_world_set_bindless_texture(static_cast<ca_backend_t>(handle),
                                                      static_cast<ca_pipeline_t>(pipeline),
                                                      static_cast<uint32_t>(binding),
                                                      static_cast<uint32_t>(slot),
                                                      static_cast<uint64_t>(image_view),
                                                      static_cast<uint64_t>(sampler));
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

JNIEXPORT jint JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeRenderer_nativeWorldTrace(
        JNIEnv* env, jclass, jlong handle, jlong pipeline, jlong command_buffer,
        jint width, jint height, jobject push_constants_buffer) {
    try {
        uint64_t push_addr = 0;
        uint32_t push_size = 0;
        if (push_constants_buffer != nullptr) {
            push_size = static_cast<uint32_t>(env->GetDirectBufferCapacity(push_constants_buffer));
            push_addr = reinterpret_cast<uint64_t>(env->GetDirectBufferAddress(push_constants_buffer));
        }
        return ca_backend_world_trace(static_cast<ca_backend_t>(handle),
                                       static_cast<ca_pipeline_t>(pipeline),
                                       static_cast<uint64_t>(command_buffer),
                                       static_cast<uint32_t>(width),
                                       static_cast<uint32_t>(height), push_addr, push_size);
    } catch (...) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
}

} // extern "C"
