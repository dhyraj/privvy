// path_translator.{h,cpp} — space-aware path translation (spec §5.2)
//
// Rewrites every guest-visible filesystem path into Privvy's private,
// per-space sandbox BEFORE the real libc syscall executes:
//
//   /data/data/<guest_pkg>/[...]         -> /data/data/com.privvy.app/virtual/user_<id>/data/<guest_pkg>/[...]
//   /data/user/0/<guest_pkg>/[...]       -> (same as above)
//   /sdcard/<...>                        -> /data/data/com.privvy.app/virtual/user_<id>/sdcard/<...>
//   /storage/emulated/0/<...>            -> (same as above)
//   /storage/emulated/0/Android/data/<pkg> -> virtual/user_<id>/sdcard/Android/data/<pkg>
//
// Privvy's own paths must pass through untouched (else infinite recursion).

#pragma once

#include <string>
#include <cstddef>

namespace privvyio {

// All-path prefix of this host app's private dir, e.g. "/data/user/0/com.privvy.app".
// Set once at JNI load.
extern std::string g_privvy_data_dir;

// Currently-active virtual space id (0..N); set on space switch via JNI.
extern volatile int g_active_space;

// Longest guest-visible sdcard root we emulate.
constexpr const char* kSdcardAliases[] = {
    "/storage/emulated/0/",
    "/sdcard/",
    "/mnt/sdcard/",
};

struct TranslatedPath {
    std::string path;
    bool redirected;   // false => pass through unmodified
};

// Translate an arbitrary guest path for the active space.
TranslatedPath translate_path(const char* input);

// True when the path belongs to Privvy itself (never redirect, never log).
bool is_privvy_internal(const char* path);

// Build "<privvy>/virtual/user_<id>/..." for the active space.
std::string virtual_root(int space_id);

} // namespace privvyio
