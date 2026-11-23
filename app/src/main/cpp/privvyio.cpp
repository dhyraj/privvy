// privvyio.cpp — JNI bridge between the Kotlin engine (com.privvy.app.io) and
// libprivvyio.so. Registration is explicit (RegisterNatives) so symbol names
// survive R8 (see proguard-rules.pro keep rules).

#include "path_translator.h"

namespace privvyio {
void install_file_hooks();
}

#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/stat.h>
#include <cstring>
#include <string>

#define LOG_TAG "PrivvyIO"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using namespace privvyio;

// void nativeInit(String privvyDataDir)
void nativeInit(JNIEnv* env, jclass, jstring privvyDataDir) {
    const char* dir = env->GetStringUTFChars(privvyDataDir, nullptr);
    g_privvy_data_dir = dir ? dir : "";
    env->ReleaseStringUTFChars(privvyDataDir, dir);
    LOGI("privvyio initialized, data_dir=%s", g_privvy_data_dir.c_str());
}

// void nativeSetActiveSpace(int spaceId)
void nativeSetActiveSpace(JNIEnv*, jclass, jint spaceId) {
    g_active_space = spaceId;
    LOGI("active virtual space -> user_%d", spaceId);
}

// String nativeTranslatePath(String path)  — debugging/verification helper
jstring nativeTranslatePath(JNIEnv* env, jclass, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    TranslatedPath t = translate_path(p);
    env->ReleaseStringUTFChars(path, p);
    return env->NewStringUTF(t.path.c_str());
}

// String nativeProbeOpen(String path) — exercises the hooked open() path end-to-end.
jstring nativeProbeOpen(JNIEnv* env, jclass, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    // Ensure the (translated) parent directory exists so O_CREAT succeeds.
    std::string guestParent(p);
    size_t slash = guestParent.find_last_of('/');
    if (slash != std::string::npos) {
        guestParent = guestParent.substr(0, slash);
        std::string vparent = translate_path(guestParent.c_str()).path;
        // Create each component of the VIRTUAL parent path.
        std::string cur;
        size_t pos = 0;
        if (!vparent.empty() && vparent[0] == '/') { cur = "/"; pos = 1; }
        while (pos < vparent.size()) {
            size_t next = vparent.find('/', pos);
            if (next == std::string::npos) next = vparent.size();
            if (next > pos) {
                cur += vparent.substr(pos, next - pos);
                mkdir(cur.c_str(), 0755); // direct (not hooked) on virtual path
                cur += "/";
            }
            pos = next + 1;
        }
    }
    int fd = open(p, O_CREAT | O_WRONLY | O_TRUNC, 0644);
    std::string result;
    if (fd >= 0) {
        const char* msg = "privvy-hook-ok";
        (void)write(fd, msg, std::strlen(msg));
        close(fd);
        result = translate_path(p).path;
    } else {
        result = std::string("OPEN_FAILED errno=") + std::to_string(errno);
    }
    env->ReleaseStringUTFChars(path, p);
    return env->NewStringUTF(result.c_str());
}

// void nativeInstallFileHooks()
void nativeInstallFileHooks(JNIEnv*, jclass) {
    install_file_hooks();
    LOGI("libc file-op hooks installed");
}

// NOTE: method names MUST match the Kotlin declarations in PrivvyNative.kt
// (external @JvmStatic funs compile to these exact JVM names).
JNINativeMethod kMethods[] = {
    {"init",             "(Ljava/lang/String;)V",           reinterpret_cast<void*>(&nativeInit)},
    {"setActiveSpace",   "(I)V",                            reinterpret_cast<void*>(&nativeSetActiveSpace)},
    {"translatePath",    "(Ljava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(&nativeTranslatePath)},
    {"probeOpen",        "(Ljava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*>(&nativeProbeOpen)},
    {"installFileHooks", "()V",                             reinterpret_cast<void*>(&nativeInstallFileHooks)},
};

} // namespace

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass clazz = env->FindClass("com/privvy/app/io/PrivvyNative");
    if (clazz == nullptr) {
        LOGE("FindClass(com/privvy/app/io/PrivvyNative) failed");
        return JNI_ERR;
    }
    if (env->RegisterNatives(clazz, kMethods,
                             sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
