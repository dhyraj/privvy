#include "path_translator.h"

#include <cstring>

namespace privvyio {

std::string g_privvy_data_dir;          // "/data/user/0/com.privvy.app"
volatile int g_active_space = 0;

namespace {

constexpr char kDataDirPrefix[] = "/data/data/";
constexpr char kUserDirPrefix[] = "/data/user/";
constexpr char kEmulatedStoragePrefix[] = "/storage/emulated/";
constexpr char kPrivvyPackage[] = "com.privvy.app";

bool path_starts_with(const char* path, const char* prefix) {
    const size_t length = std::strlen(prefix);
    if (std::strncmp(path, prefix, length) != 0) return false;
    if (length > 0 && prefix[length - 1] == '/') return true;
    return path[length] == '/' || path[length] == '\0';
}

std::string normalized_privvy_data_dir() {
    std::string dir = g_privvy_data_dir;
    while (dir.size() > 1 && dir.back() == '/') {
        dir.pop_back();
    }
    return dir;
}

bool valid_component(const char* begin, const char* end) {
    if (end == nullptr) end = begin + std::strlen(begin);
    if (begin == end) return false;
    const size_t length = static_cast<size_t>(end - begin);
    if (length == 1 && begin[0] == '.') return false;
    if (length == 2 && begin[0] == '.' && begin[1] == '.') return false;
    return true;
}

bool safe_suffix(const char* suffix) {
    if (suffix == nullptr) return true;
    for (const char* component = suffix; *component != '\0';) {
        while (*component == '/') ++component;
        if (*component == '\0') break;
        const char* end = std::strchr(component, '/');
        if (end == nullptr) end = component + std::strlen(component);
        if (!valid_component(component, end)) return false;
        component = end;
    }
    return true;
}

TranslatedPath denied_path() {
    std::string root = normalized_privvy_data_dir();
    root += "/virtual/user_" + std::to_string(g_active_space) + "/.privvy-denied";
    return {root, true};
}

std::string build_virtual_root(int space) {
    std::string root = normalized_privvy_data_dir();
    root += "/virtual/user_";
    root += std::to_string(space);
    return root;
}

TranslatedPath translate_data_dir(const char* input) {
    const char* package = nullptr;

    if (path_starts_with(input, kDataDirPrefix)) {
        package = input + std::strlen(kDataDirPrefix);
    } else if (path_starts_with(input, kUserDirPrefix)) {
        const char* profile = input + std::strlen(kUserDirPrefix);
        const char* profile_end = std::strchr(profile, '/');
        if (profile_end == nullptr || !valid_component(profile, profile_end)) {
            return {input, false};
        }
        package = profile_end + 1;
    }

    if (package == nullptr) return {input, false};
    if (!safe_suffix(std::strchr(package, '/'))) return denied_path();

    const char* package_end = std::strchr(package, '/');
    if (!valid_component(package, package_end)) {
        return {input, false};
    }

    std::string out = build_virtual_root(g_active_space);
    out += "/data/";
    if (package_end == nullptr) {
        out += package;
    } else {
        out.append(package, static_cast<size_t>(package_end - package));
        out += package_end;
    }
    return {out, true};
}

TranslatedPath translate_sdcard(const char* input) {
    auto build_sdcard_path = [](const char* suffix) {
        std::string out = build_virtual_root(g_active_space);
        out += "/sdcard";
        out += suffix;
        return out;
    };

    for (const char* alias : kSdcardAliases) {
        if (path_starts_with(input, alias)) {
            const char* suffix = input + std::strlen(alias);
            return safe_suffix(suffix) ? TranslatedPath{build_sdcard_path(suffix), true} : denied_path();
        }
    }

    if (path_starts_with(input, kEmulatedStoragePrefix)) {
        const char* profile = input + std::strlen(kEmulatedStoragePrefix);
        const char* profile_end = std::strchr(profile, '/');
        if (profile_end == nullptr) {
            profile_end = profile + std::strlen(profile);
        }
        if (!valid_component(profile, profile_end)) {
            return denied_path();
        }
        const char* suffix = *profile_end == '/' ? profile_end : "";
        return safe_suffix(suffix) ? TranslatedPath{build_sdcard_path(suffix), true} : denied_path();
    }

    return {input, false};
}

} // namespace

bool is_privvy_internal(const char* path) {
    if (path == nullptr || path[0] != '/') return false;

    const std::string data_dir = normalized_privvy_data_dir();
    if (!data_dir.empty() && path_starts_with(path, data_dir.c_str())) {
        return true;
    }

    constexpr char data_alias[] = "/data/data/";
    if (std::strncmp(path, data_alias, sizeof(data_alias) - 1) == 0 &&
        path_starts_with(path + sizeof(data_alias) - 1, kPrivvyPackage)) {
        return true;
    }

    if (std::strncmp(path, kUserDirPrefix, sizeof(kUserDirPrefix) - 1) == 0) {
        const char* profile = path + sizeof(kUserDirPrefix) - 1;
        const char* profile_end = std::strchr(profile, '/');
        if (profile_end != nullptr && valid_component(profile, profile_end) &&
            path_starts_with(profile_end + 1, kPrivvyPackage)) {
            return true;
        }
    }

    return false;
}

std::string virtual_root(int space_id) {
    return build_virtual_root(space_id);
}

TranslatedPath translate_path(const char* input) {
    if (input == nullptr || input[0] == '\0') {
        return {input ? input : "", false};
    }
    // Relative paths must retain their dirfd/cwd semantics.
    if (input[0] != '/') return {input, false};

    // Check before building any virtual path so already-translated calls made
    // by bionic wrappers pass straight through instead of redirecting again.
    if (is_privvy_internal(input)) return {input, false};
    if (g_privvy_data_dir.empty()) return {input, false};

    if (path_starts_with(input, kDataDirPrefix) || path_starts_with(input, kUserDirPrefix)) {
        TranslatedPath translated = translate_data_dir(input);
        return translated.redirected ? translated : denied_path();
    }
    if (path_starts_with(input, kEmulatedStoragePrefix)) {
        TranslatedPath translated = translate_sdcard(input);
        return translated.redirected ? translated : denied_path();
    }
    return translate_sdcard(input);
}

} // namespace privvyio
