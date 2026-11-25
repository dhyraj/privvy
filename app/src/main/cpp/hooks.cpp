// hooks.cpp — libc file-operation inline hooks via Dobby (spec §5.2)
//
// Each hook translates only absolute guest paths, then calls the original
// libc function saved by Dobby. Relative paths and Privvy's own paths pass
// through unchanged, preserving dirfd/cwd semantics and preventing recursive
// redirection when one bionic wrapper calls another.

#include "path_translator.h"

#include <algorithm>
#include <cstdarg>
#include <mutex>
#include <vector>

#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <dobby.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/vfs.h>
#include <unistd.h>

namespace privvyio {
namespace {

constexpr const char* kLogTag = "PrivvyIO";

#define HOOK_DEF(ret, name, ...)                    \
    using name##_t = ret (*)(__VA_ARGS__);          \
    static name##_t orig_##name = nullptr;          \
    static ret hooked_##name(__VA_ARGS__)

bool needs_mode(int flags) {
    return (flags & O_CREAT) != 0 || (flags & O_TMPFILE) != 0;
}

const char* path_argument(const TranslatedPath& translated, const char* original) {
    return original == nullptr ? nullptr : translated.path.c_str();
}

// ---------------------------------------------------------------------------
// open family. __openat is the non-variadic bionic funnel; the public wrappers
// only consume a mode argument when O_CREAT or O_TMPFILE requires one.
// ---------------------------------------------------------------------------
HOOK_DEF(int, __openat, int dirfd, const char* pathname, int flags, int mode) {
    TranslatedPath t = translate_path(pathname);
    return orig___openat(dirfd, path_argument(t, pathname), flags, mode);
}

HOOK_DEF(int, __openat_2, int dirfd, const char* pathname, int flags) {
    TranslatedPath t = translate_path(pathname);
    return orig___openat_2(dirfd, path_argument(t, pathname), flags);
}

HOOK_DEF(int, openat, int dirfd, const char* pathname, int flags, ...) {
    mode_t mode = 0;
    if (needs_mode(flags)) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }

    TranslatedPath t = translate_path(pathname);
    const char* path = path_argument(t, pathname);
    if (needs_mode(flags)) return orig_openat(dirfd, path, flags, mode);
    return orig_openat(dirfd, path, flags);
}

HOOK_DEF(int, openat64, int dirfd, const char* pathname, int flags, ...) {
    mode_t mode = 0;
    if (needs_mode(flags)) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }

    TranslatedPath t = translate_path(pathname);
    const char* path = path_argument(t, pathname);
    if (needs_mode(flags)) return orig_openat64(dirfd, path, flags, mode);
    return orig_openat64(dirfd, path, flags);
}

HOOK_DEF(int, open, const char* pathname, int flags, ...) {
    mode_t mode = 0;
    if (needs_mode(flags)) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }

    TranslatedPath t = translate_path(pathname);
    const char* path = path_argument(t, pathname);
    if (needs_mode(flags)) return orig_open(path, flags, mode);
    return orig_open(path, flags);
}

HOOK_DEF(int, open64, const char* pathname, int flags, ...) {
    mode_t mode = 0;
    if (needs_mode(flags)) {
        va_list args;
        va_start(args, flags);
        mode = static_cast<mode_t>(va_arg(args, int));
        va_end(args);
    }

    TranslatedPath t = translate_path(pathname);
    const char* path = path_argument(t, pathname);
    if (needs_mode(flags)) return orig_open64(path, flags, mode);
    return orig_open64(path, flags);
}

HOOK_DEF(int, __open_2, const char* pathname, int flags) {
    TranslatedPath t = translate_path(pathname);
    return orig___open_2(path_argument(t, pathname), flags);
}

// ---------------------------------------------------------------------------
// access family.
// ---------------------------------------------------------------------------
HOOK_DEF(int, access, const char* pathname, int mode) {
    TranslatedPath t = translate_path(pathname);
    return orig_access(path_argument(t, pathname), mode);
}

HOOK_DEF(int, faccessat, int dirfd, const char* pathname, int mode, int flags) {
    TranslatedPath t = translate_path(pathname);
    return orig_faccessat(dirfd, path_argument(t, pathname), mode, flags);
}

// ---------------------------------------------------------------------------
// stat family. On arm64, struct stat and struct stat64 are ABI-compatible.
// Alias symbols are installed separately and duplicate target addresses are
// skipped by the installer.
// ---------------------------------------------------------------------------
HOOK_DEF(int, stat, const char* pathname, struct stat* buf) {
    TranslatedPath t = translate_path(pathname);
    return orig_stat(path_argument(t, pathname), buf);
}

HOOK_DEF(int, __stat, const char* pathname, struct stat* buf) {
    TranslatedPath t = translate_path(pathname);
    return orig___stat(path_argument(t, pathname), buf);
}

HOOK_DEF(int, stat64, const char* pathname, struct stat64* buf) {
    TranslatedPath t = translate_path(pathname);
    return orig_stat64(path_argument(t, pathname), buf);
}

HOOK_DEF(int, __stat64, const char* pathname, struct stat64* buf) {
    TranslatedPath t = translate_path(pathname);
    return orig___stat64(path_argument(t, pathname), buf);
}

HOOK_DEF(int, lstat, const char* pathname, struct stat* buf) {
    TranslatedPath t = translate_path(pathname);
    return orig_lstat(path_argument(t, pathname), buf);
}

HOOK_DEF(int, __lstat, const char* pathname, struct stat* buf) {
    TranslatedPath t = translate_path(pathname);
    return orig___lstat(path_argument(t, pathname), buf);
}

HOOK_DEF(int, lstat64, const char* pathname, struct stat64* buf) {
    TranslatedPath t = translate_path(pathname);
    return orig_lstat64(path_argument(t, pathname), buf);
}

HOOK_DEF(int, __lstat64, const char* pathname, struct stat64* buf) {
    TranslatedPath t = translate_path(pathname);
    return orig___lstat64(path_argument(t, pathname), buf);
}

HOOK_DEF(int, fstatat, int dirfd, const char* pathname, struct stat* buf, int flags) {
    TranslatedPath t = translate_path(pathname);
    return orig_fstatat(dirfd, path_argument(t, pathname), buf, flags);
}

HOOK_DEF(int, __fstatat, int dirfd, const char* pathname, struct stat* buf, int flags) {
    TranslatedPath t = translate_path(pathname);
    return orig___fstatat(dirfd, path_argument(t, pathname), buf, flags);
}

HOOK_DEF(int, fstatat64, int dirfd, const char* pathname, struct stat64* buf, int flags) {
    TranslatedPath t = translate_path(pathname);
    return orig_fstatat64(dirfd, path_argument(t, pathname), buf, flags);
}

HOOK_DEF(int, __fstatat64, int dirfd, const char* pathname, struct stat64* buf, int flags) {
    TranslatedPath t = translate_path(pathname);
    return orig___fstatat64(dirfd, path_argument(t, pathname), buf, flags);
}

// ---------------------------------------------------------------------------
// Directory creation and rename family.
// ---------------------------------------------------------------------------
HOOK_DEF(int, mkdir, const char* pathname, mode_t mode) {
    TranslatedPath t = translate_path(pathname);
    return orig_mkdir(path_argument(t, pathname), mode);
}

HOOK_DEF(int, mkdirat, int dirfd, const char* pathname, mode_t mode) {
    TranslatedPath t = translate_path(pathname);
    return orig_mkdirat(dirfd, path_argument(t, pathname), mode);
}

HOOK_DEF(int, rename, const char* oldpath, const char* newpath) {
    TranslatedPath old_translated = translate_path(oldpath);
    TranslatedPath new_translated = translate_path(newpath);
    return orig_rename(path_argument(old_translated, oldpath),
                       path_argument(new_translated, newpath));
}

HOOK_DEF(int, renameat, int olddirfd, const char* oldpath,
         int newdirfd, const char* newpath) {
    TranslatedPath old_translated = translate_path(oldpath);
    TranslatedPath new_translated = translate_path(newpath);
    return orig_renameat(olddirfd, path_argument(old_translated, oldpath),
                         newdirfd, path_argument(new_translated, newpath));
}

HOOK_DEF(int, renameat2, int olddirfd, const char* oldpath,
         int newdirfd, const char* newpath, unsigned int flags) {
    TranslatedPath old_translated = translate_path(oldpath);
    TranslatedPath new_translated = translate_path(newpath);
    return orig_renameat2(olddirfd, path_argument(old_translated, oldpath),
                          newdirfd, path_argument(new_translated, newpath), flags);
}

// ---------------------------------------------------------------------------
// Removal, symlink, permission and truncate family.
// ---------------------------------------------------------------------------
HOOK_DEF(int, unlink, const char* pathname) {
    TranslatedPath t = translate_path(pathname);
    return orig_unlink(path_argument(t, pathname));
}

HOOK_DEF(int, unlinkat, int dirfd, const char* pathname, int flags) {
    TranslatedPath t = translate_path(pathname);
    return orig_unlinkat(dirfd, path_argument(t, pathname), flags);
}

HOOK_DEF(int, rmdir, const char* pathname) {
    TranslatedPath t = translate_path(pathname);
    return orig_rmdir(path_argument(t, pathname));
}

HOOK_DEF(ssize_t, readlink, const char* pathname, char* buf, size_t bufsiz) {
    TranslatedPath t = translate_path(pathname);
    return orig_readlink(path_argument(t, pathname), buf, bufsiz);
}

HOOK_DEF(ssize_t, readlinkat, int dirfd, const char* pathname,
         char* buf, size_t bufsiz) {
    TranslatedPath t = translate_path(pathname);
    return orig_readlinkat(dirfd, path_argument(t, pathname), buf, bufsiz);
}

HOOK_DEF(int, chmod, const char* pathname, mode_t mode) {
    TranslatedPath t = translate_path(pathname);
    return orig_chmod(path_argument(t, pathname), mode);
}

HOOK_DEF(int, fchmodat, int dirfd, const char* pathname, mode_t mode, int flags) {
    TranslatedPath t = translate_path(pathname);
    return orig_fchmodat(dirfd, path_argument(t, pathname), mode, flags);
}

HOOK_DEF(int, truncate, const char* pathname, off_t length) {
    TranslatedPath t = translate_path(pathname);
    return orig_truncate(path_argument(t, pathname), length);
}

HOOK_DEF(int, truncate64, const char* pathname, off64_t length) {
    TranslatedPath t = translate_path(pathname);
    return orig_truncate64(path_argument(t, pathname), length);
}

// ---------------------------------------------------------------------------
// Directory enumeration and filesystem-space probes.
// ---------------------------------------------------------------------------
HOOK_DEF(DIR*, opendir, const char* pathname) {
    TranslatedPath t = translate_path(pathname);
    return orig_opendir(path_argument(t, pathname));
}

HOOK_DEF(int, statfs, const char* pathname, struct statfs* buf) {
    TranslatedPath t = translate_path(pathname);
    return orig_statfs(path_argument(t, pathname), buf);
}

HOOK_DEF(int, statfs64, const char* pathname, struct statfs64* buf) {
    TranslatedPath t = translate_path(pathname);
    return orig_statfs64(path_argument(t, pathname), buf);
}

HOOK_DEF(int, statvfs, const char* pathname, struct statvfs* buf) {
    TranslatedPath t = translate_path(pathname);
    return orig_statvfs(path_argument(t, pathname), buf);
}

HOOK_DEF(int, statvfs64, const char* pathname, struct statvfs64* buf) {
    TranslatedPath t = translate_path(pathname);
    return orig_statvfs64(path_argument(t, pathname), buf);
}

#undef HOOK_DEF

struct HookEntry {
    const char* name;
    void* replacement;
    void** original;
};

void install_file_hooks_impl() {
    // DobbySymbolResolver may return the same address for strong aliases.
    // Hooking that address twice would replace the first hook with a wrapper
    // whose original points back into the same hook.
    const HookEntry entries[] = {
        {"__openat", reinterpret_cast<void*>(&hooked___openat), reinterpret_cast<void**>(&orig___openat)},
        {"__openat_2", reinterpret_cast<void*>(&hooked___openat_2), reinterpret_cast<void**>(&orig___openat_2)},
        {"open", reinterpret_cast<void*>(&hooked_open), reinterpret_cast<void**>(&orig_open)},
        {"open64", reinterpret_cast<void*>(&hooked_open64), reinterpret_cast<void**>(&orig_open64)},
        {"openat", reinterpret_cast<void*>(&hooked_openat), reinterpret_cast<void**>(&orig_openat)},
        {"openat64", reinterpret_cast<void*>(&hooked_openat64), reinterpret_cast<void**>(&orig_openat64)},
        {"__open_2", reinterpret_cast<void*>(&hooked___open_2), reinterpret_cast<void**>(&orig___open_2)},
        {"access", reinterpret_cast<void*>(&hooked_access), reinterpret_cast<void**>(&orig_access)},
        {"faccessat", reinterpret_cast<void*>(&hooked_faccessat), reinterpret_cast<void**>(&orig_faccessat)},
        {"stat", reinterpret_cast<void*>(&hooked_stat), reinterpret_cast<void**>(&orig_stat)},
        {"__stat", reinterpret_cast<void*>(&hooked___stat), reinterpret_cast<void**>(&orig___stat)},
        {"stat64", reinterpret_cast<void*>(&hooked_stat64), reinterpret_cast<void**>(&orig_stat64)},
        {"__stat64", reinterpret_cast<void*>(&hooked___stat64), reinterpret_cast<void**>(&orig___stat64)},
        {"lstat", reinterpret_cast<void*>(&hooked_lstat), reinterpret_cast<void**>(&orig_lstat)},
        {"__lstat", reinterpret_cast<void*>(&hooked___lstat), reinterpret_cast<void**>(&orig___lstat)},
        {"lstat64", reinterpret_cast<void*>(&hooked_lstat64), reinterpret_cast<void**>(&orig_lstat64)},
        {"__lstat64", reinterpret_cast<void*>(&hooked___lstat64), reinterpret_cast<void**>(&orig___lstat64)},
        {"fstatat", reinterpret_cast<void*>(&hooked_fstatat), reinterpret_cast<void**>(&orig_fstatat)},
        {"__fstatat", reinterpret_cast<void*>(&hooked___fstatat), reinterpret_cast<void**>(&orig___fstatat)},
        {"fstatat64", reinterpret_cast<void*>(&hooked_fstatat64), reinterpret_cast<void**>(&orig_fstatat64)},
        {"__fstatat64", reinterpret_cast<void*>(&hooked___fstatat64), reinterpret_cast<void**>(&orig___fstatat64)},
        {"mkdir", reinterpret_cast<void*>(&hooked_mkdir), reinterpret_cast<void**>(&orig_mkdir)},
        {"mkdirat", reinterpret_cast<void*>(&hooked_mkdirat), reinterpret_cast<void**>(&orig_mkdirat)},
        {"rename", reinterpret_cast<void*>(&hooked_rename), reinterpret_cast<void**>(&orig_rename)},
        {"renameat", reinterpret_cast<void*>(&hooked_renameat), reinterpret_cast<void**>(&orig_renameat)},
        {"renameat2", reinterpret_cast<void*>(&hooked_renameat2), reinterpret_cast<void**>(&orig_renameat2)},
        {"unlink", reinterpret_cast<void*>(&hooked_unlink), reinterpret_cast<void**>(&orig_unlink)},
        {"unlinkat", reinterpret_cast<void*>(&hooked_unlinkat), reinterpret_cast<void**>(&orig_unlinkat)},
        {"rmdir", reinterpret_cast<void*>(&hooked_rmdir), reinterpret_cast<void**>(&orig_rmdir)},
        {"readlink", reinterpret_cast<void*>(&hooked_readlink), reinterpret_cast<void**>(&orig_readlink)},
        {"readlinkat", reinterpret_cast<void*>(&hooked_readlinkat), reinterpret_cast<void**>(&orig_readlinkat)},
        {"chmod", reinterpret_cast<void*>(&hooked_chmod), reinterpret_cast<void**>(&orig_chmod)},
        {"fchmodat", reinterpret_cast<void*>(&hooked_fchmodat), reinterpret_cast<void**>(&orig_fchmodat)},
        {"truncate", reinterpret_cast<void*>(&hooked_truncate), reinterpret_cast<void**>(&orig_truncate)},
        {"truncate64", reinterpret_cast<void*>(&hooked_truncate64), reinterpret_cast<void**>(&orig_truncate64)},
        {"opendir", reinterpret_cast<void*>(&hooked_opendir), reinterpret_cast<void**>(&orig_opendir)},
        {"statfs", reinterpret_cast<void*>(&hooked_statfs), reinterpret_cast<void**>(&orig_statfs)},
        {"statfs64", reinterpret_cast<void*>(&hooked_statfs64), reinterpret_cast<void**>(&orig_statfs64)},
        {"statvfs", reinterpret_cast<void*>(&hooked_statvfs), reinterpret_cast<void**>(&orig_statvfs)},
        {"statvfs64", reinterpret_cast<void*>(&hooked_statvfs64), reinterpret_cast<void**>(&orig_statvfs64)},
    };

    std::vector<void*> hooked_targets;
    hooked_targets.reserve(sizeof(entries) / sizeof(entries[0]));
    for (const HookEntry& entry : entries) {
        void* target = dlsym(RTLD_DEFAULT, entry.name);
        if (target == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "symbol not found: %s", entry.name);
            continue;
        }
        if (std::find(hooked_targets.begin(), hooked_targets.end(), target) != hooked_targets.end()) {
            continue;
        }
        if (DobbyHook(target, entry.replacement, entry.original) != 0) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "DobbyHook failed for %s", entry.name);
            continue;
        }
        hooked_targets.push_back(target);
        __android_log_print(ANDROID_LOG_INFO, kLogTag, "hooked %s @ %p", entry.name, target);
    }
}

std::once_flag install_once;

} // namespace

void install_file_hooks() {
    std::call_once(install_once, install_file_hooks_impl);
}

} // namespace privvyio
