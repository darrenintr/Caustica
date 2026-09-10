#include "backend_internal.hpp"

#include <cstring>

namespace caustica::renderer {
namespace {

struct FrameGraph {
    bool prepared = false;
    uint64_t last_frame_id = 0;
    uint32_t render_width = 0;
    uint32_t render_height = 0;
    uint32_t display_width = 0;
    uint32_t display_height = 0;
    uint32_t pass_mask = 0;
    ca_image_ref radiance{};
    ca_image_ref firefly_killed{};
    ca_image_ref denoised_color{};
    ca_image_ref display_image{};
    ca_image_ref rr_output{};
    ca_image_ref main_target{};
    VkImageView radiance_view = VK_NULL_HANDLE;
    VkImageView firefly_killed_view = VK_NULL_HANDLE;
    VkImageView denoised_view = VK_NULL_HANDLE;
    VkImageView display_view = VK_NULL_HANDLE;
    VkImageView rr_output_view = VK_NULL_HANDLE;
    VkImage main_target_image = VK_NULL_HANDLE;
};

ca_frame_result make_result(int32_t status, uint32_t commit_state, uint64_t token = 0,
                            uint64_t diagnostic = 0) {
    ca_frame_result out{};
    out.status = status;
    out.commit_state = commit_state;
    out.frame_token = token;
    out.diagnostic_code = diagnostic;
    return out;
}

bool valid_image(const ca_image_ref& image, const Backend& backend) {
    return image.size == sizeof(ca_image_ref) && image.abi == CAUSTICA_BACKEND_ABI_V2
            && image.image != 0 && image.view != 0 && image.width > 0 && image.height > 0
            && image.owner != CAUSTICA_OWNER_NATIVE
            && image.generation == backend.generation;
}

int32_t prepare_inputs(Backend& backend, FrameGraph& graph, const ca_frame_input& input) {
    if (!backend.attached || backend.device == VK_NULL_HANDLE) {
        return CAUSTICA_STATUS_DECLINED;
    }
    if (input.size != sizeof(ca_frame_input) || input.abi != CAUSTICA_BACKEND_ABI_V2) {
        return CAUSTICA_STATUS_FATAL_PRE_COMMIT;
    }
    if (input.pass_mask == 0 || input.command_buffer == 0) {
        return CAUSTICA_STATUS_DECLINED;
    }
    const bool need_firefly = (input.pass_mask & CAUSTICA_PASS_FIREFLY) != 0;
    if (need_firefly
            && (!valid_image(input.radiance, backend) || !valid_image(input.firefly_killed, backend))) {
        return CAUSTICA_STATUS_DECLINED;
    }
    if ((input.pass_mask & CAUSTICA_PASS_DENOISE) != 0 && !valid_image(input.denoised_color, backend)) {
        return CAUSTICA_STATUS_DECLINED;
    }
    if ((input.pass_mask & (CAUSTICA_PASS_DISPLAY | CAUSTICA_PASS_COPY)) != 0
            && !valid_image(input.main_target, backend)) {
        return CAUSTICA_STATUS_DECLINED;
    }
    graph.prepared = true;
    graph.last_frame_id = input.frame_id;
    graph.render_width = input.render_width;
    graph.render_height = input.render_height;
    graph.display_width = input.display_width;
    graph.display_height = input.display_height;
    graph.pass_mask = input.pass_mask;
    graph.radiance = input.radiance;
    graph.firefly_killed = input.firefly_killed;
    graph.denoised_color = input.denoised_color;
    graph.display_image = input.display_image;
    graph.rr_output = input.rr_output;
    graph.main_target = input.main_target;
    graph.radiance_view = reinterpret_cast<VkImageView>(input.radiance.view);
    graph.firefly_killed_view = reinterpret_cast<VkImageView>(input.firefly_killed.view);
    graph.denoised_view = reinterpret_cast<VkImageView>(input.denoised_color.view);
    graph.display_view = input.display_image.view != 0
            ? reinterpret_cast<VkImageView>(input.display_image.view) : VK_NULL_HANDLE;
    graph.rr_output_view = input.rr_output.view != 0
            ? reinterpret_cast<VkImageView>(input.rr_output.view) : VK_NULL_HANDLE;
    graph.main_target_image = reinterpret_cast<VkImage>(input.main_target.image);
    return CAUSTICA_STATUS_OK;
}

void dispatch_firefly(Backend& backend, FrameGraph& graph, VkCommandBuffer cmd) {
    if (graph.firefly_killed_view == VK_NULL_HANDLE || graph.radiance_view == VK_NULL_HANDLE) {
        return;
    }
    ca_firefly_desc firefly{};
    firefly.size = sizeof(firefly);
    firefly.abi = CAUSTICA_BACKEND_ABI_V2;
    firefly.frame_id = graph.last_frame_id;
    firefly.command_buffer = reinterpret_cast<uint64_t>(cmd);
    firefly.input = graph.radiance;
    firefly.output = graph.firefly_killed;
    if (ensure_firefly_prepared(backend, firefly) != CAUSTICA_STATUS_OK) {
        return;
    }
    (void) record_firefly(backend, firefly);
}

} // namespace

ca_frame_result backend_prepare_frame(Backend& backend, const ca_frame_input& input) {
    FrameGraph graph;
    static thread_local FrameGraph cached_graph;
    if (input.frame_id == cached_graph.last_frame_id && cached_graph.prepared) {
        graph = cached_graph;
        return make_result(CAUSTICA_STATUS_OK, CAUSTICA_COMMIT_PREPARED, input.frame_id);
    }
    cached_graph = {};
    const int32_t status = prepare_inputs(backend, cached_graph, input);
    if (status != CAUSTICA_STATUS_OK) {
        return make_result(status, CAUSTICA_COMMIT_NONE);
    }
    const uint64_t token = ++backend.next_frame_token;
    return make_result(CAUSTICA_STATUS_OK, CAUSTICA_COMMIT_PREPARED, token);
}

ca_frame_result backend_record_frame(Backend& backend, const ca_frame_input& input) {
    static thread_local FrameGraph cached_graph;
    if (!cached_graph.prepared || cached_graph.last_frame_id != input.frame_id) {
        return make_result(CAUSTICA_STATUS_DECLINED, CAUSTICA_COMMIT_NONE);
    }
    const VkCommandBuffer cmd = reinterpret_cast<VkCommandBuffer>(input.command_buffer);
    if (cmd == VK_NULL_HANDLE) {
        return make_result(CAUSTICA_STATUS_FATAL_PRE_COMMIT, CAUSTICA_COMMIT_NONE);
    }
    if ((cached_graph.pass_mask & CAUSTICA_PASS_FIREFLY) != 0) {
        dispatch_firefly(backend, cached_graph, cmd);
    }
    cached_graph.prepared = false;
    return make_result(CAUSTICA_STATUS_OK, CAUSTICA_COMMIT_RECORDED, input.frame_id);
}

} // namespace caustica::renderer

extern "C" {

ca_frame_result ca_backend_prepare_frame(ca_backend_t backend, const ca_frame_input* input) {
    auto* state = caustica::renderer::from_handle(backend);
    if (state == nullptr || input == nullptr) {
        ca_frame_result result{};
        result.status = CAUSTICA_STATUS_FATAL_PRE_COMMIT;
        return result;
    }
    std::lock_guard lock(state->mutex);
    return caustica::renderer::backend_prepare_frame(*state, *input);
}

ca_frame_result ca_backend_record_frame(ca_backend_t backend, const ca_frame_input* input) {
    auto* state = caustica::renderer::from_handle(backend);
    if (state == nullptr || input == nullptr) {
        ca_frame_result result{};
        result.status = CAUSTICA_STATUS_FATAL_PRE_COMMIT;
        return result;
    }
    std::lock_guard lock(state->mutex);
    return caustica::renderer::backend_record_frame(*state, *input);
}

} // extern "C"
