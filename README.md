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

Milestones 1–3 of `REQUIREMENTS.md` are complete; milestone 4 is complete
except physical cross-OEM / Work Profile QA (no such devices available).

Working end to end: guest APK import/launch (single and split APKs), per-space
files/preferences/databases/MediaStore isolation, runtime permission mediation,
best-effort Binder proxies, encrypted vault, guest-database at-rest encryption,
PIN + biometric unlock with multi-space routing, automatic session locking
(Home/background, screen-off, inactivity), Quick Settings tile, and a floating
return pill over guests.

Verified on Pixel 8a emulators: API 33 (4 KB pages) and API 35 (16 KB pages) —
9/9 Maestro UI flows on each, 27/27 instrumentation tests, lint green.
Release APK: ~3.3 MB (NFR ≤ 25 MB), cold start ~2.0 s in repeated final API 33
samples. See `progress.md`
for the decision log, verification log, and known gaps.

The host UI uses a warm-neutral, forest-green Material 3 light/dark theme with
consistent spacing, plain language, accessible contrast and touch targets, and
responsive large-text layouts across PIN unlock, Home, Spaces, and the encrypted
vault. Home, Spaces, and Vault stay reachable through bottom navigation on phones
or a navigation rail on wider screens. Installed-app cloning uses a searchable
adaptive grid of real app icons and names, without package names or versions.

## Security boundary

- Privvy is rootless. Guest APKs share Privvy's Linux UID and Binder identity;
  this is isolation inside one app, not a device-level sandbox. Malicious
  guests sharing the UID may reach host-private files or KeyStore aliases.
- Per-space permission decisions cover runtime permissions declared by both
  the guest and Privvy. Direct sensitive framework APIs outside the proxied
  interfaces are not fully mediated.
- The vault encrypts imported files. Guest databases are encrypted at rest
  while the session is locked (encrypt-at-lock / decrypt-at-unlock), not
  transparently while a guest runs; other guest data and virtual MediaStore
  files are plaintext inside Privvy-private storage during a session.
- Static manifest receivers are approximated by session-scoped host-side
  forwarding; protected implicit system broadcasts may not arrive. Virtual
  MediaStore `applyBatch` is media-only.
- The return pill requires the user-granted "Display over other apps"
  permission.
- Four virtual space slots and `arm64-v8a` are currently supported. Cross-OEM
  and active Work Profile behavior remain unverified.
