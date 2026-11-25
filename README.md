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
    └── ui/                  # PIN gate, spaces, mini-launcher, encrypted vault, QS tile
```

## Building

Requirements: JDK 17, Android SDK (platform 36), NDK 26.3, CMake bundled with the SDK.

```
./gradlew :app:assembleDebug
```

ABI: `arm64-v8a` only for now. Android 11 (API 30) minimum.

## Status

Functional prototype with guest APK import/launch, per-space app data and
MediaStore, runtime permission mediation, encrypted vault operations, PIN and
biometric unlock, automatic session locking, and a Quick Settings tile.
End-to-end flows are verified on a Pixel 8a API 33 emulator; see `progress.md`.
The original floating overlay pill is not implemented; Android Back returns from
a guest to Privvy's space grid.

## Security boundary

- Privvy is rootless. Guest APKs share Privvy's Linux UID and Binder identity;
  this is isolation inside one app, not a device-level sandbox. Malicious
  guests sharing the UID may reach host-private files or KeyStore aliases.
- Per-space permission decisions cover runtime permissions declared by both
  the guest and Privvy. Direct sensitive framework APIs are not fully mediated.
- Only files imported into the encrypted vault are encrypted. Guest app data,
  preferences, databases, and virtual MediaStore files are not automatically
  encrypted.
- Static manifest receiver registration for arbitrary broadcasts and virtual
  MediaStore `applyBatch` remain unsupported.
- Four virtual space slots and `arm64-v8a` are currently supported. Cross-OEM
  and active Work Profile behavior remain unverified.
