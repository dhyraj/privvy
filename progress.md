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
    └── ui/                      # Calculator disguise, PIN pad, mini-launcher, gallery/file manager
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
  - [ ] Standalone APK (OpenCamera) boot test inside `user_0`
  - [ ] AssetManager/Resources injection for guest context (Milestone 2)

### ⬜ Backlog (per spec roadmap)
- [ ] Milestone 2: StubService/StubReceiver, IPackageManager + signature spoofing, split-APK mounting, IContentProvider/MediaStore proxy, WhatsApp/Reddit validation
- [ ] Milestone 3: Calculator/Notes disguise (`<activity-alias>`), BiometricPrompt, multi-PIN routing, Keystore encryption, Encrypted Gallery/File Manager, floating pill, ACTION_SCREEN_OFF cleanup
- [ ] Milestone 4: Cross-OEM QA (Pixel/Samsung/OnePlus/Xiaomi), Work Profile coexistence, memory profiling, APK size ≤ 25 MB

---

## Known Gaps / Risks (tracked)
- Guest APK signing-info spoofing needs verification per OEM (spec §9 risk table).
- GMS-dependent guests will crash without calling-package spoofing (spec §9) — scheduled for Milestone 2.
- JNI method names are the contract between `PrivvyNative.kt` and `privvyio.cpp`'s `JNINativeMethod` table — changing either side silently causes `NoSuchMethodError`/`UnsatisfiedLinkError` at runtime (bitten once, now documented in both files).
- Debug APK is 60 MB (DOBBY_DEBUG on in Debug config + c++_shared + debug symbols). Watch release size against the ≤ 25 MB NFR; consider `DOBBY_GENERATE_SHARED=OFF` static linking later.
- `libdobby.so` currently ships as a shared lib inside the APK; switch to static if size matters.
- **Vault entry is unauthenticated** (no PIN/biometric yet). Anyone who knows `7391=` gets in — FR-02/M3 must replace this dev trigger with the real AuthGate before release.
- Path translation currently also applies in the **host process** (the vault storage demo relies on it). Before APK import, host reads of `/sdcard/...` must bypass translation, or translation must be scoped to `:v*` guest processes.

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
