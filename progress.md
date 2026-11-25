# Privvy — Build Progress & Decision Log

Source spec: `REQUIREMENTS.md` v1.0
Target: Android 11 (API 30) → Android 16+ (API 36), no root, cross-OEM.

---

## Decision Log

| # | Date | Decision | Rationale / Alternatives |
|---|------|----------|--------------------------|
| D-001 | 2026-11-18 | Project structure: single-module Gradle project, `app/` module | Keeps native + Kotlin in one APK; multi-module split can come later if APK size or build times demand it. |
| D-002 | 2026-11-18 | Package name `com.privvy.app` | Matches spec's directory layout (`/data/data/com.privvy.app/`). |
| D-003 | 2026-11-18 | `minSdk = 30`, `targetSdk = 36`, `compileSdk = 36` | Spec mandates Android 11+ through Android 16+. Platform `android-36` is installed locally. |
| D-004 | 2026-11-18 | Language: Kotlin (JVM 17) + C++20 for `libprivvyio.so` | Spec's three-layer engine requires Kotlin framework hooks + native inline hooks. |
| D-005 | 2026-11-18 | Build system: Gradle wrapper 8.9 + AGP 8.5.x + CMake via NDK 26.3.11579264 | Local SDK has NDK 26.3; CMake/ninja supplied by SDK dir. No global gradle installed → use wrapper. |
| D-006 | 2026-11-18 | Hidden API bypass: `org.lsposed.hiddenapibypass:hiddenapibypass` (Maven Central) | Spec calls for AndroidHiddenApiBypass; it is published by LSposed on Maven Central, no vendoring needed. |
| D-007 | 2026-11-18 | Native hooking: Dobby vendored in `third_party/dobby`, pinned to `JingMatrix/Dobby@05a09ac6807a6bb1726350e40ea4b127c1c79809` | Upstream `jmpews/Dobby` master no longer compiles against NDK 26+ (open issues #277/#298: `@PAGE` asm syntax + `RuntimeModule` API drift). JingMatrix fork (maintained for PlayIntegrityFix) is the community-validated fix. FetchContent was abandoned after flaky clone runs corrupted a source file — vendoring makes builds deterministic and offline. One vendored patch applied: `LINE_MAX` guard for bionic headers in `ProcessRuntimeUtility.cc`. |
| D-011 | 2026-11-18 | NDK pinned to `26.3.11579264` via `ndkVersion` | AGP auto-installed NDK 27.0.12077973; Dobby arm64 asm fails on its clang. NDK 26.3 is the local, Dobby-compatible toolchain. |
| D-012 | 2026-11-18 | `hiddenapibypass` pinned to `4.3` | `6.2` requires compileSdk 37; AGP 8.9.1 supports max 36. Revisit when AGP supports 37. |
| D-013 | 2026-11-18 | `biometric` pinned to `1.1.0` (stable) | `1.2.0-beta05` doesn't exist on Google Maven. |
| D-014 | 2026-11-18 | `useLegacyPackaging = true` for jniLibs | Required because manifest sets `extractNativeLibs=true`; guest APKs are DexClassLoader-loaded from the extracted native lib dir, not mmap'd from the host APK. |
| D-015 | 2026-11-23 | UI testing: Maestro 2.12.1 + Maestro MCP (`mcp.servers.maestro` in `opencode.jsonc`), plus Compose instrumented tests | Maestro gives black-box E2E flows and MCP-driven interaction; Compose tests (androidTest) cover committed regressions. Maestro installed at `~/.maestro/bin`; MCP uses the absolute path because MCP servers may not inherit shell PATH. FLAG_SECURE makes screenshots black — use view-hierarchy assertions. |
| D-016 | 2026-11-23 | Early vault shell: secret trigger `7391=` (and long-press) opens `VaultActivity`; calculator performs real arithmetic; only `.alias.Calculator` is a launcher entry; `VirtualStorage.bootstrap()` creates `virtual/apks` + `user_0..3/{data,sdcard}` | The app looked like a dead calculator because the spec's auth gate is Milestone 3. This dev entry unblocks manual testing now. **Not ship-ready:** it is unauthenticated until FR-02 PIN/biometric gating lands. |
| D-017 | 2026-11-24 | No disguise: removed `ui/disguise/*`, `VaultActivity`, calculator/secret Maestro flows and launcher aliases; `MainActivity` is the sole launcher entry labelled "Privvy" with the Privvy shield icon. | User explicitly removed the calculator disguise; app now opens directly to Privvy. D-016 is **superseded**. |
| D-018 | 2026-11-24 | Auth gate: salted PBKDF2 PIN (4–8 digits) + `androidx.biometric.BiometricPrompt` (weak+strong) with PIN fallback; `MainActivity` extends `FragmentActivity`; `FLAG_SECURE` set. | PIN hash stored in private prefs (Argon2id + Keystore in M3). Biometric only when enrolled; PIN guarantees access on every device. |
| D-019 | 2026-11-24 | Session notification: `SessionGuardService` foreground service with ongoing "Privvy is active" notification, Close action, `POST_NOTIFICATIONS` runtime request (API 33+), `FOREGROUND_SERVICE_SPECIAL_USE` (API 34+). Started after unlock; stopped on lock/close/task removal. | FGS is the only reliable ongoing-notification mechanism; verified present while unlocked and removed on lock via `dumpsys notification`/`activity services`. |
| D-020 | 2026-11-24 | Native IO hooks are guest-process only: installed in `:v*` processes; host process keeps real filesystem access for APK import. | Required so the importer can read external APKs without being redirected; self-test now runs only in guest processes. |
| D-021 | 2026-11-24 | APK importer: shared bytes under `virtual/apks/<pkg>/`, per-space `virtual/user_N/apps.json` registry, APK bundle (.apks/.xapk) support, native `.so` extraction, icon + launch activity/theme via a small binary AXML parser. | Keeps installation records per-space while sharing bytes; AXML parser avoids hidden PackageParser APIs across Android 11–16. |
| D-022 | 2026-11-24 | Guest boot: stub Activity → PrivvyInstrumentation instantiates the guest class from a cached DexClassLoader; guest Resources built from framework-res + guest APKs via reflection on AssetManager; storage writes redirect into `virtual/user_0`. | Verified end-to-end: guest test app shows "Hello from guest APK", and `/sdcard/guest-boot-test.txt` lands in virtual storage (PASS). |
| D-023 | 2026-11-25 | Guest class loading is parented to the **system** ClassLoader, not the host `PathClassLoader`; after `Activity.attach`, `PrivvyInstrumentation` swaps the Activity's `mBase` for a `GuestContext` that returns guest Resources, a guest `LayoutInflater`, and the guest DexClassLoader. Guest theme is applied before the guest `onCreate`. | The host bundles AppCompat 1.2.0 transitively via `biometric`; OpenCamera bundles a newer AppCompat. Host-first delegation shadowed the guest's AppCompat classes, mismatching styleable/resource IDs (`Resources$NotFoundException`), and the host base context made inflation resolve `ContentFrameLayout` through the host loader (`ClassCastException`). Isolating the guest loader + context fixes both. |
| D-024 | 2026-11-25 | Per-space runtime permission mediation: guest permission requests are intercepted, Allow/Deny is persisted per space and guest package, and effective access requires both guest and host grants. Missing host permissions are requested for Privvy. | `Activity.requestPermissions` is validated against the host UID; a guest package name throws `SecurityException: package=... does not belong to uid=...`. Only guest-manifest permissions also declared by the host are mediated; guests still share Privvy's UID and direct framework APIs are not fully sandboxed. |

---

## Architecture Map (mirrors spec §5)

```
app/src/main/
├── AndroidManifest.xml          # Stub pool: StubActivity(S/T/ST), StubService, StubProvider, StubReceiver → :v0..:vN
├── cpp/                         # Layer 2: libprivvyio.so (Dobby inline hooks, path translation, MediaStore proxy helpers)
└── java/com/privvy/app/
    ├── core/                    # Space model, APK registry, virtual process bookkeeping (Layer 1 state)
    ├── hook/                    # Binder proxies: IActivityTaskManager, IPackageManager, INotificationManager, IContentProvider (Layer 1)
    ├── engine/                  # DexClassLoader boot, Instrumentation/LoadedApk injection, resource injection (Layer 1)
    ├── io/                      # JNI bridge to libprivvyio.so, path translation rules (Layer 2)
    ├── security/                # Keystore AES-256-GCM per-space keys, PIN hashing (Layer 3)
    └── ui/                      # Privvy launcher, PIN/biometric gate, spaces, encrypted vault, QS tile
```

---

## Progress

### ✅ Completed
- [x] 2026-11-18 — Read & analyzed REQUIREMENTS.md; verified local toolchain (SDK platforms 29–36, NDK 26.3, build-tools 36, Java 17).
- [x] 2026-11-18 — Created progress.md (this file) with decision log.
- [x] 2026-11-18 — Scaffolded Gradle project: settings, version catalog, root + app build files, gradle wrapper, gradle.properties.
- [x] 2026-11-18 — AndroidManifest.xml with full Stub Activity/Service/Provider/Receiver pool across `:v0`–`:v3` processes + launcher activity-aliases for disguise.
- [x] 2026-11-18 — C++ skeleton: `CMakeLists.txt`, JNI bridge, path-translation module, Dobby hook scaffolding.
- [x] 2026-11-18 — Kotlin skeleton for core/hook/engine/io/security/ui layers with placeholder classes and TODO markers.
- [x] 2026-11-18 — **First full build green**: `./gradlew :app:assembleDebug` produces `app-debug.apk` (60 MB debug; size optimization deferred to Milestone 4 per spec NFR ≤ 25 MB base, which applies to release builds).
- [x] 2026-11-18 — **Emulator smoke test PASSED** (Pixel 8a API 33): app installs, launches, `PrivvyIO: privvyio initialized, data_dir=/data/user/0/com.privvy.app`, Calculator disguise renders, process stable, no SIGABRT.
- [x] 2026-11-18 — Vendored Dobby into `third_party/dobby` (JingMatrix fork @ `05a09ac`, 1.4 MB); replaced FetchContent (see D-007).

- [x] 2026-11-23 — **UI testing capability set up**: Maestro 2.12.1 installed; Maestro MCP configured in `opencode.jsonc` (`mcp.servers.maestro`, absolute path, no analytics) and verified via `list_devices` → emulator-5554 connected; skill `.opencode/skills/android-ui-test/SKILL.md` created; reusable flow `.maestro/flows/disguise-calculator.yaml` added; Compose instrumented tests wired (`androidTest`). NOTE: Maestro screenshots are black on Privvy windows due to FLAG_SECURE — use hierarchy assertions.
- [x] 2026-11-23 — **Unlock path exists**: calculator now performs real arithmetic; `7391=` (or long-press on the display) opens `VaultActivity` with space status and a live storage-isolation test; duplicate launcher entry removed (only `.alias.Calculator`); `VirtualStorage.bootstrap()` creates `virtual/apks` + `user_0..3/{data,sdcard}` at startup.
- [x] 2026-11-24 — **Phase A (no disguise + auth + session notification) complete**:
- [x] 2026-11-24 — **Phase B (Milestone 1 importer + guest boot) complete**: `GuestApkImporter`, `SpaceAppRegistry`, binary AXML parser, `VirtualAppLauncher`, `GuestResources`, `guest-test-app` module; verified import + launch + isolated `/sdcard` write on Pixel 8a API 33. Maestro flow `.maestro/flows/guest-boot.yaml` (partial: import+launch+storage PASS). removed calculator/disguise code, aliases and calculator Maestro flows; added `MainActivity` (FragmentActivity) with PIN setup/unlock + biometric; updated manifest label/icon to Privvy; added `SessionGuardService` FGS notification with Close action and permissions. Compose build green; new Maestro flow `.maestro/flows/auth-session.yaml` PASS 20/20; notification verified present while unlocked and removed on lock.

### 🔄 In Progress
- [ ] Milestone 1 (Weeks 1–3): Native IO engine & single-APK boot
  - [x] Wire Dobby and get `libprivvyio.so` compiling for arm64 (vendored JingMatrix fork + NDK 26.3)
  - [x] Enable `PrivvyNative.installFileHooks()` in PrivvyApplication — all 7 libc hooks install successfully on emulator
  - [x] End-to-end hook validation on emulator: mkdir + open redirect `/data/data/com.example.guest/selftest.txt` → `virtual/user_0/.../selftest.txt`; file written and read back with correct content
  - [x] Translation table verified for all families: `/data/data/<pkg>`, `/data/user/0/<pkg>`, `/sdcard/`, `/storage/emulated/0/` → `virtual/user_0/...`; Privvy's own paths pass through untouched
  - [x] ActivityThread.mInstrumentation hooked (PrivvyInstrumentation) — builds DexClassLoader per guest APK path; newActivity instantiates guest class from guest DexClassLoader
  - [x] PrivvyInstrumentation delegates all `callActivityOn*` lifecycle callbacks to the original Instrumentation and is installed **only in `:v*` guest processes** (host process skipped). Fixes activities never receiving onCreate when the hook wrapped AndroidJUnitRunner (UI-test hang at "Tests 0/3")
  - [x] VirtualEngine scaffolding: buildDexClassLoader (base + split APKs, unified nativeLibraryDir), extractNativeLibs (TODO extraction impl)
  - [x] `GuestApkImporter` + `SpaceAppRegistry` (per-space `apps.json`), APK parsing (label/icon/launch activity/theme), native `.so` extraction
  - [x] Guest boot path: stub activity → `PrivvyInstrumentation` → DexClassLoader + Resources injection; `guest-test-app` module added; debug import entry point
  - [x] Verified: import debug APK into `user_0`, launch guest, UI renders, `/sdcard` write redirects to virtual storage (PASS)
  - [x] Standalone APK (OpenCamera 1.56.2, F-Droid) boot test inside `user_0`: onboarding + full camera UI render, preview/controls/settings present, process stable
  - [x] AssetManager/Resources injection for guest context (D-022, D-023)
  - [x] Guest class-loader isolation + guest `LayoutInflater`/theme application (D-023)
  - [x] Per-space runtime permission mediation for guest-requested, host-declared permissions (D-024); host/guest grant intersection verified
  - [x] Per-space guest files, SharedPreferences, and MediaStore isolation verified across Personal and Work, including preservation after switching back
  - [x] Session locking on Home/background, screen-off, and inactivity; guest-process termination and Quick Settings tile session state verified
  - [ ] Full guest Binder identity spoofing and direct sensitive-API mediation (guests still run under Privvy's host UID)

### ⬜ Remaining Hardening
- [ ] Full guest Binder identity spoofing and direct sensitive-API mediation
- [ ] Static manifest receiver registration for arbitrary system broadcasts
- [ ] Transparent guest database encryption (the imported vault is already encrypted)
- [ ] Floating overlay return pill (Android Back currently returns to Privvy)
- [ ] Cross-OEM QA (Pixel/Samsung/OnePlus/Xiaomi) and active Work Profile coexistence
- [ ] Memory profiling and release APK size ≤ 25 MB

---

## Known Gaps / Risks (tracked)
- Guest APK signing-info spoofing needs verification per OEM (spec §9 risk table).
- GMS-dependent guests will crash without calling-package spoofing (spec §9) — scheduled for Milestone 2.
- JNI method names are the contract between `PrivvyNative.kt` and `privvyio.cpp`'s `JNINativeMethod` table — changing either side silently causes `NoSuchMethodError`/`UnsatisfiedLinkError` at runtime (bitten once, now documented in both files).
- Debug APK is 60 MB (DOBBY_DEBUG on in Debug config + c++_shared + debug symbols). Watch release size against the ≤ 25 MB NFR; consider `DOBBY_GENERATE_SHARED=OFF` static linking later.
- `libdobby.so` currently ships as a shared lib inside the APK; switch to static if size matters.
- Guest apps run under Privvy's host UID and Binder identity. D-024 mediates runtime requests for permissions declared by both the guest and Privvy, but direct sensitive framework APIs can still bypass per-space decisions; malicious guests sharing the UID may reach host-private storage or KeyStore aliases.
- Guest class loading is intentionally isolated (system ClassLoader parent). Guests that expect host-provided shared libraries (e.g. GMS) still need explicit class-loader bridges; the current guest test app and OpenCamera both bundle what they need.
- Static manifest receiver registration for arbitrary system broadcasts and virtual MediaStore `applyBatch` remain unsupported.
- The binary AXML parser does not yet read `android:theme` reliably (OpenCamera's `launchTheme` came back `0`); the importer now falls back to `PackageInfo.activities[].theme` and `ApplicationInfo.theme`, so theme application works. Fix the parser or drop it once hidden `PackageParser` adapters land.

## Verification Log
| Date | What was verified | Result |
|------|-------------------|--------|
| 2026-11-18 | Local toolchain scan (java/SDK/NDK) | Java 17 OK; SDK platforms 29–36 + NDK 26.3 present; gradle/cmake not on PATH → wrapper + SDK cmake |
| 2026-11-18 | `./gradlew :app:assembleDebug` | BUILD SUCCESSFUL — APK 60 MB debug, contains libprivvyio.so + libdobby.so (arm64-v8a) |
| 2026-11-18 | `aapt2 dump badging` | package com.privvy.app, minSdk 30, targetSdk 36, label "Calculator" (disguise active) |
| 2026-11-23 | `./gradlew :app:assembleDebug` (hooks enabled) | BUILD SUCCESSFUL |
| 2026-11-23 | Emulator hook smoke test (Pixel 8a API 33) | PASS — all 7 libc hooks installed; mkdir+open redirect verified; file written & read back at translated path |
| 2026-11-23 | Emulator boot with instrumentation hook | PASS — ActivityThread.mInstrumentation hooked, process stable, app renders |
| 2026-11-23 | Compose instrumented UI tests (`./gradlew :app:connectedDebugAndroidTest`) | PASS 3/3 — `calculatorStartsAtZero`, `tappingDigitsAppendsToDisplay`, `clearResetsDisplay` (Pixel 8a API 33 emulator) |
| 2026-11-23 | Maestro E2E flow `.maestro/flows/disguise-calculator.yaml` (via Maestro MCP) | PASS 9/9 commands — launch+clearState → tap 7, 9 → assert "79" → tap C → assert "0"; app pid alive, no FATAL/ANR. Hierarchy confirms display + 17 buttons |
| 2026-11-23 | Maestro E2E flow `.maestro/flows/secret-trigger.yaml` | PASS 14/14 — launch → 7391 → "=" → vault opens → storage test → Lock returns to calculator |
| 2026-11-23 | Virtual storage isolation (UI storage demo) | PASS — wrote `virtual/user_0/sdcard/privvy-demo.txt`; host `/sdcard/privvy-demo.txt` does not exist |
| 2026-11-23 | Compose instrumented tests after calculator rewrite | PASS 3/3 — no regression |
| 2026-11-25 | OpenCamera 1.56.2 import + boot (`user_0`, Pixel 8a API 33) | PASS — imported `net.sourceforge.opencamera` (4.7 MB), launched in `:v0`, onboarding dialog + full camera UI (preview, exposure, settings, shutter, video/front camera/gallery) rendered; no FATAL after D-023/D-024 |
| 2026-11-25 | Guest class-loader isolation / `GuestContext` | PASS — host AppCompat 1.2.0 no longer shadows OpenCamera's bundled AppCompat; inflation resolves `ContentFrameLayout` from the guest loader; guest theme applied |
| 2026-11-25 | Host permission bridge | PASS — `dumpsys package com.privvy.app` shows `CAMERA`/`RECORD_AUDIO` granted; OpenCamera enters camera UI without `SecurityException` |
| 2026-11-25 | Maestro flow `.maestro/flows/guest-boot.yaml` | PASS — import, launch, virtual package/signature identity, `/sdcard` and preferences, second Activity, Service, Receiver, MediaStore image, and guest ContentProvider |
| 2026-11-25 | Camera2 per-space permission mediation (Pixel 8a API 33) | PASS — guest Allow/Deny persisted independently; host-missing fallback displayed PermissionController for Privvy; optional location Deny did not block camera use |
| 2026-11-25 | Camera2 capture + virtual MediaStore metadata | PASS — 144,446-byte JPEG captured at `virtual/user_0/sdcard/Pictures/IMG_20261125_173902.jpg`; metadata reports 960×1280, final size, and `IS_PENDING=0` |
| 2026-11-25 | Instrumentation regression suite (`./gradlew :app:connectedDebugAndroidTest`) | PASS 10/10 — vault crypto, virtual MediaStore, permission extra key, per-space/package persistence, and host/guest grant intersection |
| 2026-11-25 | Instrumentation regression suite after permission/vault hardening (`./gradlew :app:connectedDebugAndroidTest`) | PASS 13/13 (final regression) — host-declared permission filtering, permission cleanup isolation, per-space vault key cleanup, and existing vault/MediaStore coverage |
| 2026-11-25 | App + guest fixture build and lint | BUILD SUCCESSFUL — `assembleDebug`, `assembleDebugAndroidTest`, and both `lintDebug` tasks; zero lint errors |
| 2026-11-25 | JVM unit test tasks (`./gradlew :app:testDebugUnitTest :guest-test-app:testDebugUnitTest`) | BUILD SUCCESSFUL — both tasks complete; no JVM unit-test sources are currently present |
| 2026-11-25 | Fresh reinstall after instrumentation tests | PASS — debug APK cold-launched on the emulator and displayed the Privvy PIN setup screen |
| 2026-11-25 | Maestro flow `.maestro/flows/permissions.yaml` | PASS (final regression) — per-app manager, guest Deny persistence, re-Allow, and `CAMERA granted` with host permission pre-granted; PermissionController fallback also verified separately by ADB |
| 2026-11-25 | Session lifecycle and Quick Settings tile | PASS — Home/background and screen-off created the lock and terminated `:v0`; six-minute idle test locked after five minutes; tile changed between `Tap to sync` and `Sync active` and opened the correct session state |
| 2026-11-25 | Maestro flow `.maestro/flows/spaces.yaml` | PASS — create, rename, PIN change, wrong-PIN rejection, wipe, delete, and Personal-space app preservation |
| 2026-11-25 | Maestro flow `.maestro/flows/space-isolation.yaml` | PASS — Work started with `prefs=missing`, `sdcard=missing`, `media=0`; Personal retained `prefs=isolated`, `sdcard=guest-wrote-this`, `media=1` after switching back |
| 2026-11-25 | Vault Open/preview and stored ciphertext inspection | PASS — external HTML Viewer displayed the exact sample plaintext; vault ciphertext and encrypted index contained no sample plaintext |
| 2026-11-25 | Maestro flow `.maestro/flows/vault.yaml` | PASS — import, Open dispatch, empty Work vault, preserved Personal vault, export, delete, and final empty state |
| 2026-11-25 | Maestro flow `.maestro/flows/auth-session.yaml` (final regression) | PASS — initial PIN setup, wrong-PIN rejection, successful unlock, and active-session state |
