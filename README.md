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
10/10 Maestro UI flows on each, 29/29 instrumentation tests, lint green.
Release APK: ~3.3 MB (NFR ≤ 25 MB), cold start ~2.0 s in repeated final API 33
samples. See `progress.md`
for the decision log, verification log, and known gaps.

## Global cleanup and full reset

Open **Spaces → Start fresh → Clean up Privvy** to remove all Privvy-managed data.
The confirmation dialog lists every deletion category, explains that guests will
be stopped and a fresh `Personal` space will be created, confirms that host apps
and Privvy remain installed, and warns that the action cannot be undone.

### Removed

- All spaces, PIN hashes, and space metadata.
- Imported/cloned APKs, native libraries, app registries, guest app data,
  preferences, databases, virtual shared storage, MediaStore files, and guest
  permission choices.
- Encrypted vault files, indexes, and per-space vault keys.
- Per-space guest-database encryption keys.
- Session preferences, guest tokens, and the session lock marker.
- APK import staging, optimized guest dex, vault preview/decrypted files, and
  vault import temporary directories.
- Registered guest receivers and cached guest class loaders/runtimes.

### Preserved

- The Privvy installation and package identity.
- Host-installed apps and their data.
- Privvy's host permission grants and unrelated host settings.
- Unrelated files in Privvy's private directories.
- Files outside Privvy-owned paths, including symlink targets.

### Execution and safety

`MainActivity` locks the UI, stops the session service, and cancels/joins active
import, vault, and installed-app jobs before deletion. `GlobalCleanup.reset()`
then stops and waits for guest processes, clears in-memory guest state, removes
vault/database keys, and deletes only named Privvy-owned paths.

Filesystem deletion validates the canonical parent and uses a no-follow file-tree
walk, so cleanup cannot traverse a symlink into unrelated storage. The reset is
synchronized and idempotent. Disk work is non-cancellable once started.

If cleanup fails after partial deletion, Privvy remains locked and offers
**Try again**. There is no rollback because the operation is explicitly
permanent; rerunning the complete pipeline is the supported recovery path.

### Verification and code map

The cleanup pipeline is covered by `GlobalCleanupTest`,
`FileSpaceRepositoryTest`, and `.maestro/flows/cleanup.yaml`. The full suite has
29/29 instrumentation tests and 10/10 Maestro flows on both API 33 and API 35.
The warning flow also passes in dark mode with 130% text.

See [`docs/global-cleanup.md`](docs/global-cleanup.md) for the data-path inventory,
ordered execution sequence, concurrency invariants, failure behavior, test
commands, code map, and maintenance checklist.

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
