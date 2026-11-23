
Product & Technical Requirements Specification (PRD / TRD)

Project Name: Privvy (User-Space Isolated Virtual Container)
Document Version: 1.0 (Complete Planning Baseline)
Target Platform: Android (OS-Agnostic / Cross-OEM, No Root Required)

1. Executive Summary & Product Vision

Privvy is a standalone, cross-brand Android application that creates multiple isolated, invisible virtual spaces inside user space—without requiring root access, OEM-specific tools (Samsung Knox, Xiaomi Second Space), or Android's DevicePolicyManager Work Profile (allowing seamless coexistence alongside an active corporate Work Profile).
Users enter Privvy through a functional disguise (e.g., Calculator or Notes). Depending on the secret PIN entered or biometric authentication used, Privvy unlocks a self-contained mini-launcher containing arbitrary cloned APKs (e.g., WhatsApp, Reddit, Camera) whose app data, storage, media, and notifications remain 100% isolated from the host OS and completely invisible until unlocked.

2. Scope & Platform Constraints


2.1 In-Scope

• Unlimited On-Demand Virtual Spaces: Ability to create and switch between multiple isolated virtual user spaces (user_0, user_1, etc.) inside Privvy at any time without OS-level user creation.
• Arbitrary APK Execution: Dynamic loading and execution of third-party .apk and split .apks/.xapk bundles without registering them with the system PackageManager or launcher.
• Complete Storage & Media Isolation: Transparent redirection of internal storage (/data/data/), external storage (/sdcard/), and MediaStore content insertions into Privvy's encrypted internal directory.
• Camouflage, Multi-PIN Routing & Auto-Lock: Functional disguise UI, multi-PIN routing (e.g., Decoy Space vs. Primary Vault), biometric gating, notification suppression, and instant process termination on screen-off or app exit.
• Work Profile Coexistence: Full operation on devices running an active Enterprise Work Profile or Android 15+ Private Space.

2.2 Out-of-Scope / Hardware & OS Boundaries

• iOS Support: Excluded due to iOS sandboxing, single-user architecture, and lack of public hypervisor/JIT APIs.
• OS Lock-Screen Finger Routing: Excluded because Android's Trusted Execution Environment (TEE) returns only a binary success/failure boolean to user-space apps and hides individual biometricId slots.
• Play Integrity / Hardware-DRM Apps: Banking apps, UPI apps (e.g., GPay, PhonePe, Paytm), and apps enforcing hardware-backed Google Play Integrity checks are excluded from guaranteed compatibility.

3. User Journey & UX Flow

1. Camouflage State: Privvy appears on the device's home screen/app drawer as a functional utility app (e.g., Calculator). Opening it normally operates purely as a working calculator.
2. Secret Trigger: The user types a configured numeric formula (e.g., 7391=) or long-presses a designated UI element.
3. Authentication & Space Routing:
	• Biometric Prompt (BiometricPrompt): Unlocks the default Primary Vault space via fingerprint/face.
	• Multi-PIN Keypad: Entering PIN_A routes to Decoy Space (user_0 — harmless cloned apps); entering PIN_B routes to Primary Private Space (user_1 — sensitive apps & files).
4. Inside Privvy Space (Mini-Launcher): Displays a clean, private app grid containing only the cloned apps and Privvy's internal Encrypted Gallery/File Manager for that specific space.
5. Running Cloned Apps: Tapping a cloned app (e.g., Reddit, WhatsApp, OpenCamera) launches it full-screen inside an isolated child process (:v0, :v1). A subtle floating overlay pill allows returning directly to Privvy's Space grid without exposing apps to the system launcher.
6. Panic / Auto-Lock: Pressing the Android Home button, switching apps, or turning off the screen (ACTION_SCREEN_OFF) immediately kills background virtual processes, locks the Keystore cipher, blacks out the Recents preview, and reverts the UI back to the disguised Calculator.

4. Functional Requirements


ID	Feature	Requirement Description	Priority
FR-01	Functional Disguise & Aliasing	Privvy shall provide selectable functional disguises (Calculator, Notes) via <activity-alias> to dynamically swap launcher icon and app name.	P0
FR-02	Biometric & Multi-PIN Gate	Privvy shall gate access via androidx.biometric.BiometricPrompt and a custom PIN pad, supporting multiple PINs mapped to distinct virtual spaces (including a Decoy Space).	P0
FR-03	On-Demand Virtual Space Manager	Users shall be able to create, rename, wipe, or delete isolated virtual user spaces (user_0 to user_N) on demand inside Privvy settings.	P0
FR-04	APK & Split-APK Importer	Users shall be able to clone installed user apps or import external .apk / .apks / .xapk files directly into a chosen virtual space.	P0
FR-05	Isolated App Execution	Cloned apps shall run in isolated Linux sub-processes (:v0–:vN) with separate cookies, databases, and account logins per virtual space.	P0
FR-06	Zero-Leak Storage & Camera Isolation	Photos captured by a cloned Camera, downloads from Reddit/WhatsApp, and app caches shall write strictly to Privvy's private directory and never appear in the host Gallery, MediaStore, or File Manager.	P0
FR-07	Encrypted Vault Gallery & File Manager	Privvy shall include a built-in encrypted media viewer and file browser per virtual space, allowing users to view, delete, or explicitly export files to host storage.	P1
FR-08	Auto-Lock & Process Termination	Upon ACTION_SCREEN_OFF, backgrounding (ON_STOP), or inactivity timeout, Privvy shall lock the session, purge decrypted memory buffers, and terminate all :v* child processes.	P0
FR-09	Notification Masking & Suppression	Notifications emitted by cloned apps shall be intercepted by Privvy's proxy and suppressed while locked (or replaced with generic, non-revealing disguise alerts when enabled).	P1
FR-10	Quick Settings Stealth Tile	Optional innocuous Quick Settings tile (e.g., "Sync" or "Reading Mode") to trigger Privvy's authentication screen from anywhere in the OS.	P2


5. Technical Architecture & System Design

Privvy utilizes a Three-Layer User-Space Virtualization Engine:

5.1 Layer 1: Java/Kotlin Framework & Binder Hooking Layer

• Manifest Stub Pool: Pre-declare proxy components in AndroidManifest.xml (StubActivityStandard, StubActivitySingleTop, StubActivitySingleTask, StubService, StubContentProvider, StubBroadcastReceiver) mapped across isolated processes (android:process=":v0" through :v15).
• Binder IPC Proxies (java.lang.reflect.Proxy): Intercept client-side Binder interfaces (IActivityTaskManager, IPackageManager, INotificationManager, IContentProvider, IAudioService). Rewrite guest package/component targets to Privvy's Stub components before forwarding calls to system_server.
• Instrumentation & LoadedApk Injection: Hook ActivityThread.mInstrumentation to unwrap original intents, instantiate guest Activity classes via a dedicated DexClassLoader, and inject the guest APK's AssetManager and Resources.
• Signature Spoofing: Cache each imported APK's original SigningInfo / Signature[] during import and return them via the IPackageManager hook so guest apps pass self-integrity signature checks.
• Hidden API Bypass: Integrate AndroidHiddenApiBypass to retain reflection access on Android 11 through Android 16+.

5.2 Layer 2: Native C++ / JNI IO Redirection Layer (libprivvyio.so)

• Inline Syscall Hooking: Integrate Dobby (or Pine) in native C++ to inline-hook bionic libc.so file operations (__openat, faccessat, mkdirat, renameat, unlinkat, statfs, readlinkat).
• Dynamic Space-Aware Path Translation:
	• /data/data/<guest_pkg>/ & /data/user/0/<guest_pkg>/ → /data/data/<privvy_pkg>/virtual/user_<id>/data/<guest_pkg>/
	• /sdcard/ & /storage/emulated/0/ → /data/data/<privvy_pkg>/virtual/user_<id>/sdcard/
• Scoped Storage (MediaStore) Proxy: Hook IContentProvider (insert, openFile, query, update) so modern apps saving photos/videos via MediaStore URIs receive a ParcelFileDescriptor backed by a private file inside virtual/user_<id>/sdcard/DCIM/ rather than the system MediaStore database.

5.3 Layer 3: Security, Cryptography & Anti-Forensics Layer

• Hardware-Backed Keystore: Generate per-space AES-256-GCM master keys inside the Android Keystore (TEE/StrongBox), bound to user authentication.
• At-Rest File Encryption: Encrypt files stored inside virtual/user_<id>/sdcard/ and internal gallery directories using Jetpack Security (EncryptedFile) or transparent file-level encryption.
• Anti-Forensics: Enforce WindowManager.LayoutParams.FLAG_SECURE across all host and stub activities to block screenshots, screen recording, and Android Recents thumbnails. Exclude Privvy tasks from Recents via android:excludeFromRecents="true".

6. Storage & Directory Layout

text
/data/data/com.privvy.app/
├── files/
│   └── keystore_meta/              # Encrypted space configs, PIN hashes (Argon2id), & APK metadata
└── virtual/
    ├── apks/                       # Extracted base.apk, split APKs, & native .so libs (shared across spaces)
    │   └── com.reddit.frontpage/
    ├── user_0/                     # Virtual Space 0 (e.g., Decoy Space)
    │   ├── data/                   # Redirected /data/data/<guest_pkg>/ directories
    │   └── sdcard/                 # Redirected /sdcard/ (DCIM, Downloads, Pictures)
    └── user_1/                     # Virtual Space 1 (e.g., Primary Vault)
        ├── data/
        └── sdcard/
Use code with caution.

7. Non-Functional Requirements


Category	Specification
OS Support	Android 11 (API 30) through Android 16+ (API 36).
CPU Architectures	arm64-v8a (mandatory), armeabi-v7a (optional).
Cross-OEM Parity	Identical behavior across Pixel (AOSP), Samsung (OneUI), OnePlus/Oppo (OxygenOS/ColorOS), Xiaomi (HyperOS/MIUI), and Motorola without vendor SDKs.
Cold-Start Latency	Virtualized app launch overhead ≤ +800ms over native execution; native IO hook overhead < 5%.
Memory Management	Maximum 4 concurrent :v* child processes active; LRU eviction of oldest background virtual process when limit is reached.
Base Host Size	Base Privvy APK size ≤ 25 MB (excluding user-imported guest APKs).


8. Phased Implementation Roadmap (12 Weeks)

• Milestone 1: Native IO Engine & Single-APK Boot (Weeks 1–3)
	• Set up Gradle + CMake project with AndroidHiddenApiBypass and C++ Dobby hooks (libprivvyio.so).
	• Implement basic DexClassLoader, AssetManager injection, and StubActivityStandard to boot a standalone APK (e.g., OpenCamera) inside user_0.
• Milestone 2: Multi-Component Proxies & MediaStore Isolation (Weeks 4–7)
	• Implement StubService, StubBroadcastReceiver, IPackageManager (with signature spoofing), and Split-APK mounting.
	• Build IContentProvider / MediaStore proxy; verify zero photo/download leakage to host Gallery or Files app.
	• Validate complex target apps (Reddit, WhatsApp).
• Milestone 3: Multi-Space, Camouflage UI & Security Hardening (Weeks 8–10)
	• Build functional Calculator/Notes disguise (<activity-alias>), BiometricPrompt, Multi-PIN routing (user_0 vs. user_1), and Android Keystore encryption.
	• Implement built-in Encrypted Gallery/File Manager, floating mini-launcher pill, and ACTION_SCREEN_OFF process cleanup.
• Milestone 4: Cross-OEM QA & Work Profile Coexistence Testing (Weeks 11–12)
	• End-to-end QA across Pixel, Samsung, OnePlus, and Xiaomi devices running active Enterprise Work Profiles.
	• Memory profiling, crash recovery hardening, and APK size optimization.

9. Key Technical Risks & Mitigations


Risk	Impact	Mitigation Strategy
Split APKs (AAB)	Modern apps (Reddit, WhatsApp) fail to boot if split native libs or resources are missing.	Parse all split APKs (base.apk + split_config.*.apk), extract native .so files into a unified nativeLibraryDir, and pass all APK paths to DexClassLoader and AssetManager.
Google Play Services (GMS) Crashes	Guest apps calling GMS APIs (Maps, Google Sign-In, FCM) may crash if package name mismatches.	Spoof calling package name in IPackageManager / Binder identity where permitted, and document email/password or QR Companion login for apps like Reddit and WhatsApp.
Android OS Framework Updates	Hidden API field/method signatures change on new Android releases (e.g., Android 16+).	Isolate all reflection hooks behind version-gated adapter classes (Api30Adapter, Api34Adapter, Api36Adapter) modeled after open-source BlackBox patterns.

