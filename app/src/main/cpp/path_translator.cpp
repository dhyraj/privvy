#include "path_translator.h"

#include <cstring>
#include <cstdlib>

namespace privvyio {

std::string g_privvy_data_dir;          // "/data/user/0/com.privvy.app"
volatile int g_active_space = 0;

namespace {

// "/data/user/0" is the canonical view of /data/data on modern Android.
bool path_starts_with(const char* p, const char* prefix) {
    const size_t n = std::strlen(prefix);
    if (std::strncmp(p, prefix, n) != 0) return false;
    // If the prefix already ends in '/', the separator is consumed by it.
    if (n > 0 && prefix[n - 1] == '/') return true;
    return (p[n] == '/' || p[n] == '\0');
}

// "<privvy>/virtual/user_<id>"
std::string build_virtual_root(int space) {
    std::string root = g_privvy_data_dir;
    root += "/virtual/user_";
    root += std::to_string(space);
    return root;
}

// Rewrite the guest data dir family.
TranslatedPath translate_data_dir(const char* input) {
    // Matches: /data/data/<pkg>[...], /data/user/0/<pkg>[...],
    //          /data/user/10/<pkg>... (work-profile style ids -> treat as user space)
    const char* markers[] = {"/data/data/", "/data/user/0/", "/data/user/"};

    for (const char* marker : markers) {
        const char* pkg_start = std::strstr(input, marker);
        if (pkg_start != input) continue;   // must be a prefix

        const char* pkg = input + std::strlen(marker);
        // TODO(M1): validate package charset; skip if it is privvy itself.
        if (is_privvy_internal(input)) {
            return {input, false};
        }

        std::string out = build_virtual_root(g_active_space);
        out += "/data/";
        out += pkg;   // "<pkg>/rest/of/path" or "<pkg>"
        return {out, true};
    }
    return {input, false};
}

// Rewrite external storage family (spec FR-06).
TranslatedPath translate_sdcard(const char* input) {
    if (is_privvy_internal(input)) {
        return {input, false};
    }
    for (const char* alias : kSdcardAliases) {
        if (path_starts_with(input, alias)) {
            std::string out = build_virtual_root(g_active_space);
            out += "/sdcard/";
            out += (input + std::strlen(alias));
            // Collapse trailing duplicate slashes if alias ended with '/'
            while (out.size() > 1 && out.back() == '/' && out[out.size() - 2] == '/') {
                out.pop_back();
            }
            return {out, true};
        }
    }
    return {input, false};
}

} // namespace

bool is_privvy_internal(const char* path) {
    if (g_privvy_data_dir.empty()) return false;
    return path_starts_with(path, g_privvy_data_dir.c_str()) ||
           // /data/data alias of our own dir
           std::strncmp(path, "/data/data/com.privvy.app", 24) == 0;
}

std::string virtual_root(int space_id) {
    return build_virtual_root(space_id);
}

TranslatedPath translate_path(const char* input) {
    if (input == nullptr || input[0] == '\0') {
        return {input ? input : "", false};
    }
    // Privvy's own traffic must flow untouched — check FIRST to avoid recursion.
    if (is_privvy_internal(input)) {
        return {input, false};
    }
    TranslatedPath t = translate_data_dir(input);
    if (t.redirected) return t;
    return translate_sdcard(input);
}

} // namespace privvyio
