// path_translator.{h,cpp} — space-aware path translation (spec §5.2)
//
// Rewrites guest-visible absolute filesystem paths into Privvy's private,
// per-space sandbox BEFORE the real libc call executes:
//
//   /data/data/<guest_pkg>/[...]              -> <privvy>/virtual/user_<id>/data/<guest_pkg>/[...]
//   /data/user/<profile>/<guest_pkg>/[...]    -> (same as /data/data)
//   /sdcard/<...>                             -> <privvy>/virtual/user_<id>/sdcard/<...>
//   /storage/emulated/<profile>/<...>         -> (same as /sdcard)
//
// Privvy's own paths and all relative paths pass through untouched.

#pragma once

#include <cstddef>
#include <string>

namespace privvyio {

// All-path prefix of this host app's private dir, e.g. "/data/user/0/com.privvy.app".
// Set once at JNI load.
extern std::string g_privvy_data_dir;

// Currently-active virtual space id (0..N); set on space switch via JNI.
extern volatile int g_active_space;

// Fixed external-storage roots. /storage/emulated/<profile> is handled
// separately because the profile component is variable.
constexpr const char* kSdcardAliases[] = {
    "/sdcard",
    "/mnt/sdcard",
};

struct TranslatedPath {
    std::string path;
    bool redirected;   // false => pass through unmodified
};

// Translate an absolute guest path for the active space. Relative and empty
// paths are returned unmodified.
TranslatedPath translate_path(const char* input);

// True when the path belongs to Privvy itself (never redirect, never log).
bool is_privvy_internal(const char* path);

// Build "<privvy>/virtual/user_<id>".
std::string virtual_root(int space_id);

} // namespace privvyio
