# Global Cleanup and Full Reset

## Purpose

Global cleanup is the user-facing **full reset** for Privvy. It removes all
Privvy-managed spaces, guest apps, guest data, vault content, permission choices,
session state, and temporary files, then creates one fresh PIN-less `Personal`
space.

The operation is intentionally different from uninstalling Privvy:

- The Privvy APK and installation remain.
- Apps installed normally on the phone remain.
- Privvy's host runtime permission grants are not intentionally revoked.
- Unrelated files in Privvy's private directories are not deleted.

The feature is implemented by decision `D-033` in [`progress.md`](../progress.md).

## User Flow

1. Open **Spaces**.
2. Scroll to **Start fresh** and tap **Clean up Privvy**.
3. Review the confirmation dialog. Its warning lists:
   - all spaces and their PINs;
   - cloned apps and all guest data;
   - vault files;
   - guest permission choices;
   - session data and temporary files.
4. The dialog also states that guest apps will be stopped, a fresh `Personal`
   space will be created, host-installed apps and Privvy will remain, and the
   action cannot be undone.
5. Tap **Delete everything** to confirm, or **Cancel** to leave data untouched.
6. A non-dismissible progress dialog remains visible while cleanup runs.
7. On success, Privvy returns to **Create a PIN to protect this space**.
8. On failure, Privvy stays locked and offers **Try again**.

### UI Test Tags

| Tag | Purpose |
|-----|---------|
| `cleanup_card` | Start-fresh card in Spaces |
| `cleanup_button` | Opens the confirmation dialog |
| `cleanup_dialog` | Irreversible confirmation dialog |
| `cleanup_warning` | Itemized deletion warning |
| `confirm_cleanup` | **Delete everything** button |
| `cleanup_progress_dialog` | Blocking progress dialog |
| `cleanup_failure_dialog` | Partial-failure dialog |
| `retry_cleanup` | Retry button after a failure |

## Data Scope

### Removed

| Data | Location / owner |
|------|------------------|
| Space metadata and PIN hashes | `files/keystore_meta/` |
| Imported/cloned APK bytes and native libraries | `virtual/apks/` |
| Per-space app registries | `virtual/user_<id>/apps.json` |
| Guest app data, preferences, and databases | `virtual/user_<id>/data/` |
| Virtual shared storage and MediaStore files | `virtual/user_<id>/sdcard/` |
| Guest permission decisions | `virtual/user_<id>/permissions/` |
| Encrypted vault files and indexes | `files/vault/` |
| Vault encryption keys | AndroidKeyStore aliases `privvy.vault.space.<id>.v2` |
| Guest database encryption keys | AndroidKeyStore aliases `privvy.guestdb.space.<id>` |
| Session preferences | SharedPreferences `privvy_session` |
| Guest session tokens | `files/guest-session/` |
| Lock marker | `files/session.lock` |
| Debug/import staging files | `files/import/` |
| Optimized guest dex | `codeCache/privvy-dex/` |
| Vault preview plaintext | `cache/vault-preview/` |
| Decrypted vault temporary files | `cache/vault-decrypted/` |
| Vault import temporary directories | `cache/vault-import-*` |
| Registered guest receivers | In-memory `GuestReceiverRegistrar` state |
| Guest class loaders and runtimes | In-memory `VirtualEngine` / `GuestRuntimeFactory` caches |

`<id>` ranges over the four supported virtual space slots (`0..3`).

After deletion, `VirtualStorage.bootstrap()` recreates the empty virtual storage
layout and `FileSpaceRepository.resetToDefault()` writes only:

```json
[
  {
    "id": 0,
    "name": "Personal",
    "pinHashHex": null,
    "pinSaltHex": null
  }
]
```

The exact JSON representation may include default fields such as `createdAt` and
`isDecoy`; the important invariant is one PIN-less space with ID `0`.

### Preserved

- The Privvy installation and package identity.
- Host-installed applications and their data.
- Privvy's host permission grants and unrelated host settings.
- Unrelated files under the app's data, files, cache, and code-cache directories.
- Targets outside Privvy-owned directories when a symlink is encountered.

Global cleanup does not call `PackageManager` to remove host packages and does
not clear all app data with `pm clear`.

## Execution Sequence

### 1. Activity-side quiescing

[`MainActivity.cleanUpPrivvy()`](../app/src/main/java/com/privvy/app/ui/MainActivity.kt)
performs the UI-side lifecycle work before disk deletion:

1. Ignores duplicate requests while `cleanupInProgress` is true.
2. Locks the UI and increments `sessionEpoch`, invalidating stale callbacks.
3. Stops `SessionGuardService`.
4. Cancels and joins tracked installed-app scanning, vault, and APK import jobs.
5. Calls `applyLock()` to clear transient UI state and cancel picker/import work.
6. Runs `GlobalCleanup.reset()` on `Dispatchers.IO` inside `NonCancellable`.

Cancelling and joining jobs is essential: an import or vault operation that
continued after deletion could recreate files or metadata.

### 2. Guest shutdown

[`GlobalCleanup.reset()`](../app/src/main/java/com/privvy/app/core/GlobalCleanup.kt)
starts with [`SessionLockManager.lockForReset()`](../app/src/main/java/com/privvy/app/security/SessionLockManager.kt):

1. Publish `files/session.lock`.
2. Delete guest session tokens.
3. Lock `EngineState` so guests cannot begin new work.
4. Kill Privvy's `:v*` guest processes.
5. Wait up to three seconds for those processes to disappear.

Unlike normal session locking, reset treats a guest that fails to exit as a
fatal error. Cleanup must not delete databases or APKs while a guest still holds
open descriptors.

### 3. In-memory teardown

After guest processes stop, cleanup:

- unregisters all session-scoped guest receivers;
- clears cached guest `DexClassLoader` instances;
- clears cached guest runtimes.

This prevents stale receiver registrations or runtime references from surviving
the reset.

### 4. Vault and key removal

[`VaultCrypto.wipeAll()`](../app/src/main/java/com/privvy/app/security/VaultCrypto.kt)
removes the vault tree and then deletes every per-space vault key.
[`GuestDatabaseCrypto.wipeAll()`](../app/src/main/java/com/privvy/app/security/GuestDatabaseCrypto.kt)
deletes every per-space guest-database key.

Deleting the keys matters even though the corresponding files are also removed:
it ensures no old encryption key remains available for later reuse.

### 5. Owned-path deletion

Cleanup deletes only named Privvy-owned paths. Each deletion goes through
[`VirtualStorage.deleteTreeNoFollow()`](../app/src/main/java/com/privvy/app/core/VirtualStorage.kt),
which enforces two safety rules:

1. The path's canonical parent must equal the expected canonical parent.
2. `Files.walkFileTree` runs without `FOLLOW_LINKS`, so symlinks are deleted as
   links rather than traversed.

If either rule fails, cleanup throws instead of continuing with an unsafe delete.

### 6. Session reset and fresh bootstrap

[`SessionLockManager.clearSessionState()`](../app/src/main/java/com/privvy/app/security/SessionLockManager.kt)
clears `privvy_session`, removes guest tokens, republishes the lock marker, and
locks `EngineState`.

Cleanup then bootstraps empty storage and calls
[`FileSpaceRepository.resetToDefault()`](../app/src/main/java/com/privvy/app/core/FileSpaceRepository.kt)
to create the single PIN-less `Personal` space.

### 7. Return to PIN setup

On success, `MainActivity` replaces its in-memory space list with the fresh
`Personal` space, selects it as the setup/active space, clears any required-space
route, and applies the lock. The user must create a new PIN before using Privvy.

## Concurrency and Safety Invariants

- `GlobalCleanup.reset()` is `@Synchronized`, preventing two reset pipelines from
  running concurrently in the host process.
- Disk work uses `NonCancellable`; once deletion starts, ordinary coroutine
  cancellation does not leave a half-executed background job.
- Tracked import, vault, and installed-app jobs are cancelled and joined before
  deletion starts.
- Guest processes must exit before vault/database/APK data is removed.
- Deletion never follows symlinks.
- Deletion is restricted to explicit Privvy-owned paths with canonical-parent
  checks.
- Host packages are never uninstalled.
- Reset is idempotent: running it again on an already-clean state produces the
  same one-space result.

These invariants provide application-level cleanup safety. They do not change
Privvy's rootless security boundary: guests still share Privvy's Linux UID while
running.

## Failure and Retry Behavior

Cleanup is not transactional. A failure can occur after some data has already
been removed. The implementation therefore favors a locked, retry-safe state:

- `cleanupInProgress` is cleared.
- The UI remains locked.
- A failure dialog warns that some data may already have been removed.
- **Try again** reruns the complete idempotent pipeline.
- Space state is reloaded from disk when possible so the UI does not retain
  references to deleted metadata.

There is no rollback because the user explicitly requested permanent deletion.
Retrying is the supported recovery path.

## Testing

### Instrumentation

[`GlobalCleanupTest`](../app/src/androidTest/java/com/privvy/app/core/GlobalCleanupTest.kt)
uses a sandboxed `ContextWrapper` and verifies:

- two populated spaces are replaced by one PIN-less `Personal` space;
- app registries, permissions, APKs, databases, vault files, session tokens,
  import staging, dex cache, and vault temporary files are removed;
- vault and guest-database KeyStore aliases are deleted;
- the session is locked;
- unrelated host files survive;
- a symlink target outside the deleted tree survives;
- a second reset is idempotent.

[`FileSpaceRepositoryTest`](../app/src/androidTest/java/com/privvy/app/core/FileSpaceRepositoryTest.kt)
separately verifies the repository-level reset contract.

### Maestro

[`.maestro/flows/cleanup.yaml`](../.maestro/flows/cleanup.yaml) exercises the
complete user flow:

1. Create a PIN and import/launch the guest fixture.
2. Create a second space and import a vault file.
3. Open cleanup, inspect the itemized warning, and cancel once.
4. Confirm cleanup.
5. Verify PIN setup, empty apps/vault, and one workspace.
6. Relaunch Settings and Privvy to confirm host apps and Privvy remain usable.

The flow sets the fresh test PIN to `2468`.

### Running the Checks

Use an explicit emulator ID and run Maestro sequentially:

```bash
./gradlew :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest

adb -s emulator-5554 shell pm clear com.privvy.app
adb -s emulator-5554 shell run-as com.privvy.app mkdir -p files/import
adb -s emulator-5554 shell run-as com.privvy.app cp \
  /data/local/tmp/guest-test-app-debug.apk files/import/guest-test-app.apk

maestro --udid emulator-5554 test .maestro/flows/cleanup.yaml \
  --format junit --output /tmp/privvy-cleanup.xml
```

Always parse the generated JUnit XML; a successful CLI exit alone is not enough
to prove every flow passed. After cleanup, verify no guest process remains:

```bash
adb -s emulator-5554 shell pidof com.privvy.app:v0
```

An empty result is expected.

## Code Map

| Component | Responsibility |
|----------|----------------|
| [`SpaceManagerScreen.kt`](../app/src/main/java/com/privvy/app/ui/spaces/SpaceManagerScreen.kt) | Start-fresh card, itemized warning, confirmation/cancel |
| [`MainActivity.kt`](../app/src/main/java/com/privvy/app/ui/MainActivity.kt) | Job cancellation, lifecycle, progress/failure UI, state reset |
| [`GlobalCleanup.kt`](../app/src/main/java/com/privvy/app/core/GlobalCleanup.kt) | Ordered cleanup orchestration |
| [`SessionLockManager.kt`](../app/src/main/java/com/privvy/app/security/SessionLockManager.kt) | Guest shutdown, token/preferences cleanup, lock marker |
| [`VaultCrypto.kt`](../app/src/main/java/com/privvy/app/security/VaultCrypto.kt) | Vault tree and vault key removal |
| [`GuestDatabaseCrypto.kt`](../app/src/main/java/com/privvy/app/security/GuestDatabaseCrypto.kt) | Guest database key removal |
| [`VirtualStorage.kt`](../app/src/main/java/com/privvy/app/core/VirtualStorage.kt) | Owned paths and symlink-safe deletion |
| [`FileSpaceRepository.kt`](../app/src/main/java/com/privvy/app/core/FileSpaceRepository.kt) | Fresh `Personal` metadata |
| [`VirtualEngine.kt`](../app/src/main/java/com/privvy/app/engine/VirtualEngine.kt) | Guest class-loader cache reset |
| [`GuestRuntime.kt`](../app/src/main/java/com/privvy/app/engine/GuestRuntime.kt) | Guest runtime cache reset |

## Maintenance Checklist

When adding new Privvy-managed persistent data:

1. Add its owned path or key alias to the cleanup pipeline.
2. Add canonical-parent/no-follow protection for filesystem deletion.
3. Add an instrumentation assertion that the data is removed.
4. Update the itemized warning if the user-visible category changes.
5. Update the Maestro warning assertions when copy changes.
6. Update this guide and the README data-scope table.
