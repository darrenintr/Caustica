// Phase 1 JNI scaffold for libcaustica_native.
//
// The intent here is strictly "is the JNI toolchain wired correctly": does cmake build us,
// does the JVM dlopen the .so, does a Java-native call reach us, does the return value
// reach back to Java. We do NOT touch any AMD FFX / NRD / Vulkan SDK — those need header
// files we don't have on this machine, and the user explicitly forbade the SDK work
// in Phase 1.
//
// What this file actually does:
//   1. JNI_OnLoad fires once when System.loadLibrary("caustica_native") succeeds.
//      We write a single startup marker to stderr (MC's GPU log captures it) so the
//      operator can confirm the load happened.
//   2. Java_dev_comfyfluffy_caustica_native_NativeBridge_ping is a one-shot round-trip:
//      return a JVM-allocated UTF string. This validates the String marshalling path
//      (UTF modified, GetStringUTFChars round-trip, NewStringUTF on the way out).
//
// We intentionally do NOT throw exceptions or call back into Java on load — the load
// path is in a static initializer in the Java side and any throw there will fail-fast
// the entire JVM if not caught. The Caustica Java code wraps the call site in a
// try-catch and treats a missing library as "native not available" rather than as
// a fatal error.

#include <jni.h>
#include <cstdio>
#include <cstring>

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
    // strlen here is safe: kPong is a string literal with a known compile-time length.
    return env->NewStringUTF(kPong);
}

} // extern "C"
