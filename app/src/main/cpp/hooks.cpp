// hooks.cpp — libc file-operation inline hooks via Dobby (spec §5.2)
//
// Hooked symbols (bionic libc.so on arm64):
//   __openat / open, faccessat, mkdirat, renameat, unlinkat,
//   stat (statx-backed), readlinkat, statfs/statvfs.
//
// Strategy: intercept, translate guest paths via path_translator, then call
// the ORIGINAL function pointer saved by Dobby. This keeps all behavior
// native and path-translation overhead to a single strncmp pass.

#include "path_translator.h"

#include <fcntl.h>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>

#include <android/log.h>
#include <dobby.h>

namespace privvyio {
namespace {

#define HOOK_DEF(ret, name, ...)                    \
    using name##_t = ret (*)(__VA_ARGS__);          \
    static name##_t orig_##name = nullptr;          \
    static ret hooked_##name(__VA_ARGS__)

// ---------------------------------------------------------------------------
// __openat — the single funnel bionic uses for open()/fopen()/openat().
// ---------------------------------------------------------------------------
HOOK_DEF(int, openat, int dirfd, const char* pathname, int flags, ...) {
    mode_t mode = 0;
    va_list ap;
    va_start(ap, flags);
    mode = static_cast<mode_t>(va_arg(ap, int));
    va_end(ap);

    TranslatedPath t = translate_path(pathname);
    return orig_openat(dirfd, t.path.c_str(), flags, mode);
}

// ---------------------------------------------------------------------------
// faccessat — access()/faccessat()
// ---------------------------------------------------------------------------
HOOK_DEF(int, faccessat, int dirfd, const char* pathname, int mode, int flags) {
    TranslatedPath t = translate_path(pathname);
    return orig_faccessat(dirfd, t.path.c_str(), mode, flags);
}

// ---------------------------------------------------------------------------
// mkdirat
// ---------------------------------------------------------------------------
HOOK_DEF(int, mkdirat, int dirfd, const char* pathname, mode_t mode) {
    TranslatedPath t = translate_path(pathname);
    return orig_mkdirat(dirfd, t.path.c_str(), mode);
}

// ---------------------------------------------------------------------------
// renameat / renameat2
// ---------------------------------------------------------------------------
HOOK_DEF(int, renameat, int olddirfd, const char* oldpath,
         int newdirfd, const char* newpath) {
    TranslatedPath to = translate_path(oldpath);
    TranslatedPath tn = translate_path(newpath);
    return orig_renameat(olddirfd, to.path.c_str(), newdirfd, tn.path.c_str());
}

// ---------------------------------------------------------------------------
// unlinkat — covers unlink()/rmdir()
// ---------------------------------------------------------------------------
HOOK_DEF(int, unlinkat, int dirfd, const char* pathname, int flags) {
    TranslatedPath t = translate_path(pathname);
    return orig_unlinkat(dirfd, t.path.c_str(), flags);
}

// ---------------------------------------------------------------------------
// readlinkat — used by getcwd()/procfs probes in guest apps
// ---------------------------------------------------------------------------
HOOK_DEF(ssize_t, readlinkat, int dirfd, const char* pathname,
         char* buf, size_t bufsiz) {
    TranslatedPath t = translate_path(pathname);
    return orig_readlinkat(dirfd, t.path.c_str(), buf, bufsiz);
}

// ---------------------------------------------------------------------------
// mkdir — Java File.mkdirs() calls Os.mkdir -> mkdir syscall on some bionic
// builds; on others it goes through mkdirat. Hook both to be safe.
// ---------------------------------------------------------------------------
HOOK_DEF(int, mkdir, const char* pathname, mode_t mode) {
    __android_log_print(ANDROID_LOG_INFO, "PrivvyIO", "mkdir called: %s", pathname);
    TranslatedPath t = translate_path(pathname);
    __android_log_print(ANDROID_LOG_INFO, "PrivvyIO", "mkdir translated: %s", t.path.c_str());
    return orig_mkdir(t.path.c_str(), mode);
}

// ---------------------------------------------------------------------------
// open — Java FileOutputStream uses open(2) on some paths.
// ---------------------------------------------------------------------------
HOOK_DEF(int, open, const char* pathname, int flags, ...) {
    __android_log_print(ANDROID_LOG_INFO, "PrivvyIO", "open called: %s", pathname);
    mode_t mode = 0;
    va_list ap;
    va_start(ap, flags);
    if (flags & O_CREAT) mode = static_cast<mode_t>(va_arg(ap, int));
    va_end(ap);
    TranslatedPath t = translate_path(pathname);
    __android_log_print(ANDROID_LOG_INFO, "PrivvyIO", "open translated: %s", t.path.c_str());
    if (flags & O_CREAT) return orig_open(t.path.c_str(), flags, mode);
    return orig_open(t.path.c_str(), flags);
}

// ---------------------------------------------------------------------------
// statfs — free-space checks etc. Must NOT leak host /sdcard stats.
// ---------------------------------------------------------------------------
HOOK_DEF(int, statfs, const char* path, struct statfs* stats) {
    TranslatedPath t = translate_path(path);
    return orig_statfs(t.path.c_str(), stats);
}

} // namespace

void install_file_hooks() {
    // NOTE: do NOT call DobbySymbolResolver with a null symbol name — it
    // dereferences the name in strcmp and SIGSEGVs on arm64.

    struct Entry {
        const char* candidates[2];
        void* replacement;
        void** original;
    };
    const Entry entries[] = {
        {{"__openat", "openat"}, reinterpret_cast<void*>(&hooked_openat), reinterpret_cast<void**>(&orig_openat)},
        {{"open", nullptr}, reinterpret_cast<void*>(&hooked_open), reinterpret_cast<void**>(&orig_open)},
        {{"faccessat", nullptr}, reinterpret_cast<void*>(&hooked_faccessat), reinterpret_cast<void**>(&orig_faccessat)},
        {{"mkdir", "mkdirat"}, reinterpret_cast<void*>(&hooked_mkdir), reinterpret_cast<void**>(&orig_mkdir)},
        {{"mkdirat", nullptr}, reinterpret_cast<void*>(&hooked_mkdirat), reinterpret_cast<void**>(&orig_mkdirat)},
        {{"renameat", nullptr}, reinterpret_cast<void*>(&hooked_renameat), reinterpret_cast<void**>(&orig_renameat)},
        {{"unlinkat", nullptr}, reinterpret_cast<void*>(&hooked_unlinkat), reinterpret_cast<void**>(&orig_unlinkat)},
        {{"readlinkat", nullptr}, reinterpret_cast<void*>(&hooked_readlinkat), reinterpret_cast<void**>(&orig_readlinkat)},
        {{"statfs", nullptr}, reinterpret_cast<void*>(&hooked_statfs), reinterpret_cast<void**>(&orig_statfs)},
    };

    for (const Entry& e : entries) {
        void* target = nullptr;
        for (int i = 0; i < 2 && e.candidates[i] != nullptr; ++i) {
            target = DobbySymbolResolver("libc.so", e.candidates[i]);
            if (target != nullptr) break;
        }
        if (target == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, "PrivvyIO", "symbol not found: %s", e.candidates[0]);
            continue;
        }
        if (DobbyHook(target, e.replacement, e.original) != 0) {
            __android_log_print(ANDROID_LOG_WARN, "PrivvyIO", "DobbyHook failed for %s", e.candidates[0]);
        } else {
            __android_log_print(ANDROID_LOG_INFO, "PrivvyIO", "hooked %s @ %p", e.candidates[0], target);
        }
    }
}

} // namespace privvyio
