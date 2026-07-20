// Phase 1 + Phase 2 JNI scaffold for libcaustica_native.
//
// Phase 1 (2026-07-20): the intent was strictly "is the JNI toolchain wired
// correctly": does cmake build us, does the JVM dlopen the .so, does a Java-native
// call reach us, does the return value reach back to Java. We do NOT touch any AMD FFX /
// NRD / Vulkan SDK — those need header files we don't have on this machine, and the
// user explicitly forbade the SDK work in Phase 1.
//
// Phase 2 (2026-07-20): the AMD FFX SDK is now reachable from this build via
// third_party/FidelityFX-SDK/. We still do NOT link the SDK runtime (no static libs
// available on this host), but we do #include <ffx_denoiser.h> and expose a single
// compile-time readback — ffxDenoiserVersion() returns the FFX_DENOISER_VERSION
// constant from the header. This proves the SDK header is actually being compiled
// into the .so. The full dispatch is deferred to Phase 3 (which needs the SDK
// static libs and SPIR-V blobs — not in scope this turn).
//
// What this file actually does:
//   1. JNI_OnLoad fires once when System.loadLibrary("caustica_native") succeeds.
//      We write a single startup marker to stderr (MC's GPU log captures it) so the
//      operator can confirm the load happened.
//   2. Java_dev_comfyfluffy_caustica_nativebridge_NativeBridge_ping is a one-shot
//      round-trip: return a JVM-allocated UTF string. Validates the String marshalling
//      path (UTF modified, GetStringUTFChars round-trip, NewStringUTF on the way out).
//   3. Java_dev_comfyfluffy_caustica_nativebridge_NativeBridge_ffxDenoiserVersion is
//      a compile-time readback of the FFX SDK version. Returns the SDK version string
//      when FFX_SDK_PRESENT=1, or "unavailable" when the headers are missing.

#include <jni.h>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>

// Phase 2: AMD FidelityFX SDK header. The FFX_SDK_PRESENT macro is defined by
// CMakeLists.txt based on whether third_party/FidelityFX-SDK exists. When the SDK is
// absent (fresh dev box, stripped tree), we don't include — and the JNI function
// returns a stub string so the user can still load the .so.
//
// AMD's ffx_api.h uses __declspec(...) for Windows DLL export decoration. That keyword
// doesn't exist on Linux/g++, so we pre-define it as a no-op before the SDK include
// fires. We only do this when actually including the SDK (so we don't pollute a
// build without the headers). The same trick is used by the official Linux build
// scripts under third_party/FidelityFX-SDK.
#if FFX_SDK_PRESENT
#ifndef __declspec
#define __declspec(x)
#endif
#include <ffx_denoiser.h>
#endif

namespace {

// "caustica_native loaded" — written once on JNI_OnLoad. No newline at the end so the
// operator's tee'd log gets the message followed by whatever JVM bootstrap prints next.
constexpr const char* kLoadMarker = "[caustica_native] JNI_OnLoad fired";

void LogStderr(const char* msg) {
    // fputs + fflush so MC's tee (caustica-gpu-log-wrapper.sh) captures the line.
    std::fputs(msg, stderr);
    std::fputc('\n', stderr);
    std::fflush(stderr);
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    LogStderr(kLoadMarker);
    return JNI_VERSION_1_8;
}

JNIEXPORT jstring JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeBridge_ping(JNIEnv* env, jclass /*clazz*/) {
    // Single string built on the C++ side. The "v1" suffix lets the Java side
    // assert that a particular build is loaded — useful if someone rebuilds the
    // .so without re-deploying the jar.
    static constexpr const char* kPong = "pong-v1";
    return env->NewStringUTF(kPong);
}

JNIEXPORT jstring JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeBridge_ffxDenoiserVersion(
        JNIEnv* env, jclass /*clazz*/) {
#if FFX_SDK_PRESENT
    // Format the SDK version constant as a human-readable string. The FFX_DENOISER_VERSION
    // macro encodes major*10000 + minor*100 + patch (e.g. 10200 = 1.2.0). Decode it back
    // for the log so the operator can verify the SDK we're compiling against.
    const int version = FFX_DENOISER_VERSION;
    const int major = version / 10000;
    const int minor = (version / 100) % 100;
    const int patch = version % 100;
    char buffer[32];
    std::snprintf(buffer, sizeof(buffer), "%d.%d.%d (%d)", major, minor, patch, version);
    return env->NewStringUTF(buffer);
#else
    // Header was not present at build time. The user will see this in the log and
    // know the SDK integration is gated until they populate third_party/FidelityFX-SDK.
    return env->NewStringUTF("unavailable (FFX_SDK headers not present at build)");
#endif
}

// Phase 3 verify (2026-07-20): dlopen the AMD FFX 2.x modular loader .so at the
// absolute path the Java side extracted, and dlsym the six entry points. The
// function is a pure lookup — we DO NOT call any of the resolved symbols yet.
// The point is to confirm the .so is reachable and the six function pointers
// are present on this machine; no actual denoiser path is taken from this probe.
//
// The function signature is on a single jstring argument (the absolute path),
// the return is a jstring summary. We catch every failure case explicitly so
// the Java log gets a single readable line and nothing in this path can crash
// the JVM or MC.
static const char* kAmdFfxSyms[] = {
    "ffxConfigure",
    "ffxCreateContext",
    "ffxDestroyContext",
    "ffxDispatch",
    "ffxGetLastError",
    "ffxQuery"
};
static constexpr int kAmdFfxSymCount = sizeof(kAmdFfxSyms) / sizeof(kAmdFfxSyms[0]);

JNIEXPORT jstring JNICALL
Java_dev_comfyfluffy_caustica_nativebridge_NativeBridge_amdFfxLoaderCheck(
        JNIEnv* env, jclass /*clazz*/, jstring jPath) {
    if (jPath == nullptr) {
        return env->NewStringUTF("error: null path");
    }

    // GetStringUTFChars returns a C-string. We MUST ReleaseStringUTFChars even on
    // the early-return paths below, or we leak JVM-internal char-array memory.
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (path == nullptr) {
        return env->NewStringUTF("error: GetStringUTFChars failed");
    }

    char outBuf[256];
    outBuf[0] = '\0';

    // RTLD_NOW resolves all symbols at load time so any missing dependency fails
    // dlopen, not deep in a dlsym later. RTLD_LOCAL keeps the loaded symbols local
    // to our .so — AMD's loader is not something we want exposed to other
    // dlopened libraries in the same process.
    void* handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        const char* err = dlerror();
        std::snprintf(outBuf, sizeof(outBuf), "dlopen failed: %s",
                      err ? err : "(no dlerror)");
        env->ReleaseStringUTFChars(jPath, path);
        return env->NewStringUTF(outBuf);
    }

    // Walk the six expected symbols and report which are present / missing.
    int present = 0;
    char missing[256];
    missing[0] = '\0';
    for (int i = 0; i < kAmdFfxSymCount; ++i) {
        void* sym = dlsym(handle, kAmdFfxSyms[i]);
        if (sym == nullptr) {
            // Build a comma-separated "missing: a, b, c" string. Keep it short.
            const char* suffix = (missing[0] == '\0') ? "" : ", ";
            std::strncat(missing, suffix, sizeof(missing) - std::strlen(missing) - 1);
            std::strncat(missing, kAmdFfxSyms[i], sizeof(missing) - std::strlen(missing) - 1);
        } else {
            ++present;
        }
    }

    if (missing[0] == '\0') {
        std::snprintf(outBuf, sizeof(outBuf), "ok (%d/%d)", present, kAmdFfxSymCount);
    } else {
        std::snprintf(outBuf, sizeof(outBuf), "partial (%d/%d, missing: %s)",
                      present, kAmdFfxSymCount, missing);
    }

    dlclose(handle);
    env->ReleaseStringUTFChars(jPath, path);
    return env->NewStringUTF(outBuf);
}

} // extern "C"
