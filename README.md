# Privvy

User-space isolated virtual container for Android. Multiple invisible virtual
spaces inside one app — no root, no Work Profile, cross-OEM.

See `REQUIREMENTS.md` for the full product/technical spec and
`progress.md` for the decision log and build status.

## Project layout

```
app/src/main/
├── AndroidManifest.xml      # Stub Activity/Service/Provider/Receiver pool (:v0..:v3 now, :v15 target)
├── cpp/                     # Layer 2: libprivvyio.so — Dobby inline hooks + path translation
└── java/com/privvy/app/
    ├── core/                # Virtual space model + stub components (Layer 1)
    ├── hook/                # Binder IPC proxies (IActivityTaskManager, IPackageManager, ...)
    ├── engine/              # DexClassLoader boot + Instrumentation/LoadedApk injection
    ├── io/                  # JNI bridge to libprivvyio.so
    ├── security/            # Keystore keys, PIN hashing, session guard (Layer 3)
    └── ui/                  # Calculator disguise, PIN gate, mini-launcher, tile
```

## Building

Requirements: JDK 17, Android SDK (platform 36), NDK 26.3, CMake bundled with the SDK.

```
./gradlew :app:assembleDebug
```

ABI: `arm64-v8a` only for now. Android 11 (API 30) minimum.

## Status

Milestone 1 in progress — see `progress.md`.
