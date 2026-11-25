# libprivvyio.so — Layer 2: Native IO Redirection (spec §5.2)
cmake_minimum_required(VERSION 3.22.1)
project(privvyio LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------------
# Dobby — inline hooking engine (Decision D-007, revised)
#
# VENDORED at third_party/dobby, pinned to JingMatrix/Dobby@05a09ac6807a6bb1726
# (upstream jmpews/Dobby master no longer compiles against NDK 26+ — issues
# #277/#298; the JingMatrix fork, maintained for PlayIntegrityFix, fixes the
# @PAGE asm syntax and the RuntimeModule API drift).
#
# To re-pin: git clone, verify, rsync --exclude .git into third_party/dobby,
# and update the pin note below.
# ---------------------------------------------------------------------------
set(DOBBY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/dobby)
set(DOBBY_DISABLE_GENERATE_OBJS ON CACHE BOOL "" FORCE)
add_subdirectory(${DOBBY_DIR} dobby_build)

# 16 KB page-size devices (Android 15+): ELF LOAD segments must be 16 KB aligned.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# ---------------------------------------------------------------------------
# Core sources
# ---------------------------------------------------------------------------
add_library(privvyio SHARED
    privvyio.cpp           # JNI entry points
    path_translator.cpp    # space-aware path translation rules (spec §5.2)
    hooks.cpp              # libc inline hooks (openat family)
)

target_include_directories(privvyio PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${DOBBY_DIR}/include
)

target_link_libraries(privvyio
    dobby_static   # static inline-hook engine (vendored Dobby builds both; shared is excluded from packaging)
    log            # __android_log_print
)

# arm64-v8a only for now (Decision D-009)
