// Caustica — Vulkan cube spike entry point
//
// Goal: prove that this build host (RADV + glslc + Vulkan 1.2 SDK) can
// open a physical device and dispatch a compute shader. Nothing more.
// On success: prints "MCVR-BRIDGE-SPIKE-OK" and exits 0. On failure:
// prints the last status message and exits non-zero.
//
// If you run this and it crashes on driver / loader errors, that tells
// us the actual gap between Caustica's Java/LWJGL world and a future C++
// renderer using the same drivers. No MCVR-style migration decision
// can be made until we see this exit 0.
#include "vk_renderer.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

namespace {
// Resolve the bundled .spv next to the executable. argv[0] may be
// a bare name on PATH; we only need the directory part, not the full
// pathname.
std::string exeDir(const char* argv0) {
    if (!argv0 || !argv0[0]) return ".";
    std::string s(argv0);
    auto p = s.find_last_of('/');
    if (p == std::string::npos) return ".";
    return s.substr(0, p);
}
} // namespace

int main(int argc, char** argv) {
    (void)argc;
    std::string spvPath = exeDir(argv[0]) + "/vk_cube.comp.spv";

    caustica_mcvr::VkRenderer renderer;
    VkResult r = renderer.init(spvPath.c_str());
    if (r != VK_SUCCESS) {
        std::fprintf(stderr, "init failed: VkResult=%d, status=%s\n",
                     (int)r, renderer.statusMessage().c_str());
        return 1;
    }
    std::printf("init ok: %s\n", renderer.statusMessage().c_str());
    int rc = renderer.runSpike();
    if (rc != 0) {
        std::fprintf(stderr, "spike dispatch returned %d, status=%s\n",
                     rc, renderer.statusMessage().c_str());
        return 1;
    }
    std::printf("%s\n", renderer.statusMessage().c_str());
    std::printf("MCVR-BRIDGE-SPIKE-OK\n");
    return 0;
}
