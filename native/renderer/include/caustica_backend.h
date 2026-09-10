#pragma once

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#define CAUSTICA_BACKEND_API __declspec(dllexport)
#else
#define CAUSTICA_BACKEND_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define CAUSTICA_BACKEND_ABI_V1 1u
#define CAUSTICA_BACKEND_ABI_V2 2u
#define CAUSTICA_ABI_VERSION CAUSTICA_BACKEND_ABI_V2

#define CAUSTICA_SHADER_FIREFLY_KILL 1u

#define CAUSTICA_OWNER_BORROWED_MINECRAFT 1u
#define CAUSTICA_OWNER_JAVA_LEGACY 2u
#define CAUSTICA_OWNER_NATIVE 3u

#define CAUSTICA_STATUS_OK 0
#define CAUSTICA_STATUS_DECLINED 1
#define CAUSTICA_STATUS_RETRYABLE_PRE_COMMIT 2
#define CAUSTICA_STATUS_FATAL_PRE_COMMIT 3
#define CAUSTICA_STATUS_FAULT_AFTER_COMMIT 4
#define CAUSTICA_STATUS_DEVICE_LOST 5

#define CAUSTICA_COMMIT_NONE 0u
#define CAUSTICA_COMMIT_PREPARED 1u
#define CAUSTICA_COMMIT_RECORDED 2u

#define CAUSTICA_DRIVER_RADV (1ull << 0)

#define CAUSTICA_BUFFER_FLAG_HOST_VISIBLE (1u << 0)
#define CAUSTICA_BUFFER_FLAG_SHADER_DEVICE_ADDRESS (1u << 1)

typedef uint64_t ca_backend_t;

typedef struct ca_device_desc {
    uint32_t size;
    uint32_t abi;
    uint64_t instance;
    uint64_t physical_device;
    uint64_t device;
    uint64_t graphics_queue;
    uint64_t compute_queue;
    uint32_t graphics_family;
    uint32_t graphics_queue_index;
    uint32_t compute_family;
    uint32_t compute_queue_index;
    uint64_t enabled_features;
    uint64_t driver_flags;
    uint64_t generation;
} ca_device_desc;

typedef struct ca_image_ref {
    uint32_t size;
    uint32_t abi;
    uint64_t image;
    uint64_t view;
    uint32_t format;
    uint32_t width;
    uint32_t height;
    uint32_t layout;
    uint32_t owner;
    uint32_t reserved;
    uint64_t generation;
} ca_image_ref;

typedef struct ca_buffer_handle {
    uint32_t size;
    uint32_t abi;
    uint64_t buffer;
    uint64_t allocation;
    uint64_t device_address;
    uint64_t mapped_ptr;
    uint64_t size_bytes;
    uint32_t usage;
    uint32_t flags;
    uint32_t memory_type_index;
    uint32_t reserved;
    uint64_t generation;
} ca_buffer_handle;

typedef struct ca_image_handle {
    uint32_t size;
    uint32_t abi;
    uint64_t image;
    uint64_t allocation;
    uint64_t view;
    uint32_t width;
    uint32_t height;
    uint32_t format;
    uint32_t memory_type_index;
    uint32_t reserved;
    uint64_t generation;
} ca_image_handle;

typedef struct ca_firefly_desc {
    uint32_t size;
    uint32_t abi;
    uint64_t frame_id;
    uint64_t command_buffer;
    ca_image_ref input;
    ca_image_ref output;
} ca_firefly_desc;

typedef struct ca_frame_result {
    int32_t status;
    uint32_t commit_state;
    uint64_t frame_token;
    uint64_t completion_semaphore;
    uint64_t completion_value;
    uint64_t diagnostic_code;
} ca_frame_result;

CAUSTICA_BACKEND_API uint32_t ca_get_abi_version(void);
CAUSTICA_BACKEND_API ca_backend_t ca_create_backend(void);
CAUSTICA_BACKEND_API void ca_destroy_backend(ca_backend_t backend);
CAUSTICA_BACKEND_API int32_t ca_attach_device(ca_backend_t backend, const ca_device_desc* desc);
CAUSTICA_BACKEND_API void ca_detach_device(ca_backend_t backend);
CAUSTICA_BACKEND_API int32_t ca_register_spirv(ca_backend_t backend, uint32_t shader_id,
                                               const void* bytes, size_t byte_count,
                                               uint64_t content_hash);
CAUSTICA_BACKEND_API ca_frame_result ca_prepare_firefly(ca_backend_t backend,
                                                        const ca_firefly_desc* desc);
CAUSTICA_BACKEND_API ca_frame_result ca_record_firefly(ca_backend_t backend,
                                                       const ca_firefly_desc* desc);
CAUSTICA_BACKEND_API void ca_resize(ca_backend_t backend, uint32_t width, uint32_t height,
                                    uint64_t generation);
CAUSTICA_BACKEND_API void ca_begin_reload(ca_backend_t backend, uint64_t generation);
CAUSTICA_BACKEND_API void ca_end_reload(ca_backend_t backend, uint64_t generation);
CAUSTICA_BACKEND_API void ca_invalidate_history(ca_backend_t backend);
CAUSTICA_BACKEND_API uint64_t ca_native_firefly_dispatch_count(ca_backend_t backend);
CAUSTICA_BACKEND_API const char* ca_get_status(ca_backend_t backend);

CAUSTICA_BACKEND_API int32_t ca_backend_create_buffer(ca_backend_t backend, uint64_t size_bytes,
                                                  uint32_t usage, uint32_t flags,
                                                  uint64_t address_alignment, uint64_t label_hash,
                                                  ca_buffer_handle* out);
CAUSTICA_BACKEND_API int32_t ca_backend_create_storage_image(ca_backend_t backend, uint32_t width,
                                                              uint32_t height, uint32_t format,
                                                              uint32_t extra_usage,
                                                              uint64_t label_hash,
                                                              ca_image_handle* out);
CAUSTICA_BACKEND_API int32_t ca_backend_create_transient_msaa_image(ca_backend_t backend,
                                                                     uint32_t width, uint32_t height,
                                                                     uint32_t format, uint32_t samples,
                                                                     uint64_t label_hash,
                                                                     ca_image_handle* out);
CAUSTICA_BACKEND_API int32_t ca_backend_upload_device_local(ca_backend_t backend,
                                                            uint64_t size_bytes, uint32_t usage,
                                                            uint64_t host_ptr, uint64_t host_bytes,
                                                            uint64_t address_alignment,
                                                            uint64_t label_hash,
                                                            ca_buffer_handle* out);
CAUSTICA_BACKEND_API int32_t ca_backend_release_resource(ca_backend_t backend, uint64_t handle);
CAUSTICA_BACKEND_API int32_t ca_backend_flush_buffer_range(ca_backend_t backend,
                                                            uint64_t buffer_handle, uint64_t offset,
                                                            uint64_t length);
CAUSTICA_BACKEND_API int32_t ca_backend_invalidate_buffer_range(ca_backend_t backend,
                                                                uint64_t buffer_handle, uint64_t offset,
                                                                uint64_t length);
CAUSTICA_BACKEND_API int32_t ca_backend_memory_type_of(ca_backend_t backend, uint64_t buffer_handle,
                                                      uint32_t* out);
CAUSTICA_BACKEND_API int32_t ca_backend_lookup_buffer(ca_backend_t backend, uint64_t buffer_handle,
                                                      ca_buffer_handle* out);
CAUSTICA_BACKEND_API int32_t ca_backend_lookup_image(ca_backend_t backend, uint64_t image_handle,
                                                     ca_image_handle* out);

#define CAUSTICA_PASS_FIREFLY (1u << 0)
#define CAUSTICA_PASS_DENOISE (1u << 1)
#define CAUSTICA_PASS_TAA (1u << 2)
#define CAUSTICA_PASS_UPSCALE (1u << 3)
#define CAUSTICA_PASS_CAS (1u << 4)
#define CAUSTICA_PASS_EXPOSURE (1u << 5)
#define CAUSTICA_PASS_DISPLAY (1u << 6)
#define CAUSTICA_PASS_COPY (1u << 7)

typedef struct ca_frame_input {
    uint32_t size;
    uint32_t abi;
    uint64_t frame_id;
    uint64_t command_buffer;
    uint32_t render_width;
    uint32_t render_height;
    uint32_t display_width;
    uint32_t display_height;
    uint32_t pass_mask;
    uint32_t reserved;
    ca_image_ref radiance;
    ca_image_ref firefly_killed;
    ca_image_ref denoised_color;
    ca_image_ref display_image;
    ca_image_ref rr_output;
    ca_image_ref main_target;
    uint64_t denoise_mode;
    uint64_t upscale_mode;
    uint32_t spp;
    uint32_t max_bounces;
    float jitter_x;
    float jitter_y;
    float max_ray_distance;
    uint32_t flags;
    uint32_t hdr_enabled;
    float paper_white_nits;
    float headroom;
    uint32_t generation;
    uint32_t reserved2;
} ca_frame_input;

CAUSTICA_BACKEND_API ca_frame_result ca_backend_prepare_frame(ca_backend_t backend,
                                                              const ca_frame_input* input);
CAUSTICA_BACKEND_API ca_frame_result ca_backend_record_frame(ca_backend_t backend,
                                                             const ca_frame_input* input);

typedef uint64_t ca_pipeline_t;

typedef struct ca_world_pipeline_desc {
    uint32_t size;
    uint32_t abi;
    uint32_t rgen_shader_id;
    uint32_t chit_shader_id;
    uint32_t ahit_shader_id;
    uint32_t miss_shader_count;
    uint32_t miss_shader_ids[8];
    uint32_t push_constant_size;
    uint32_t with_atlas_sampler;
    uint32_t extra_storage_count;
    uint32_t extra_storage_buffer_slots;
    uint32_t bindless_capacity;
    uint32_t with_block_material_atlas;
    uint32_t with_sky_atlas;
    uint32_t with_position_fetch;
    uint32_t with_opacity_micromap;
    uint64_t generation;
} ca_world_pipeline_desc;

CAUSTICA_BACKEND_API ca_pipeline_t ca_backend_create_world_pipeline(ca_backend_t backend,
                                                                    const ca_world_pipeline_desc* desc);
CAUSTICA_BACKEND_API void ca_backend_destroy_world_pipeline(ca_backend_t backend, ca_pipeline_t pipeline);

CAUSTICA_BACKEND_API int32_t ca_backend_world_set_tlas(ca_backend_t backend, ca_pipeline_t pipeline,
                                                      uint64_t tlas_handle);
CAUSTICA_BACKEND_API int32_t ca_backend_world_set_storage_image(ca_backend_t backend, ca_pipeline_t pipeline,
                                                                uint64_t image_view);
CAUSTICA_BACKEND_API int32_t ca_backend_world_set_extra_storage_image(ca_backend_t backend,
                                                                     ca_pipeline_t pipeline,
                                                                     uint32_t slot,
                                                                     uint64_t image_view);
CAUSTICA_BACKEND_API int32_t ca_backend_world_set_extra_storage_buffer(ca_backend_t backend,
                                                                      ca_pipeline_t pipeline,
                                                                      uint32_t slot,
                                                                      uint64_t buffer_handle,
                                                                      uint64_t size_bytes);
CAUSTICA_BACKEND_API int32_t ca_backend_world_set_atlas_sampler(ca_backend_t backend, ca_pipeline_t pipeline,
                                                               uint64_t image_view, uint64_t sampler);
CAUSTICA_BACKEND_API int32_t ca_backend_world_set_block_spec_atlas(ca_backend_t backend, ca_pipeline_t pipeline,
                                                                  uint64_t image_view, uint64_t sampler);
CAUSTICA_BACKEND_API int32_t ca_backend_world_set_block_normal_atlas(ca_backend_t backend,
                                                                    ca_pipeline_t pipeline,
                                                                    uint64_t image_view, uint64_t sampler);
CAUSTICA_BACKEND_API int32_t ca_backend_world_set_sky_atlas(ca_backend_t backend, ca_pipeline_t pipeline,
                                                            uint64_t image_view, uint64_t sampler);
CAUSTICA_BACKEND_API int32_t ca_backend_world_init_bindless_fallback(ca_backend_t backend,
                                                                     ca_pipeline_t pipeline,
                                                                     uint64_t image_view, uint64_t sampler);
CAUSTICA_BACKEND_API int32_t ca_backend_world_set_bindless_texture(ca_backend_t backend,
                                                                  ca_pipeline_t pipeline,
                                                                  uint32_t binding, uint32_t slot,
                                                                  uint64_t image_view, uint64_t sampler);
CAUSTICA_BACKEND_API int32_t ca_backend_world_trace(ca_backend_t backend, ca_pipeline_t pipeline,
                                                   uint64_t command_buffer, uint32_t width, uint32_t height,
                                                   uint64_t push_constants_ptr,
                                                   uint32_t push_constants_size);

#ifdef __cplusplus
}
#endif
