package com.privvy.app.engine

/**
 * Marker for the guest-execution engine (spec §5.1, Milestone 1 scope).
 *
 * Responsibilities per :v* process:
 *  - Load guest APK(s) (base + splits) with a DexClassLoader whose
 *    nativeLibraryDir is the unified extracted .so directory (spec §9 risk #1).
 *  - Hook ActivityThread.mInstrumentation to unwrap stub intents and
 *    instantiate guest Activity classes.
 *  - Inject the guest AssetManager/Resources so guest code resolves its own
 *    resources, and spoof SigningInfo from the import-time cache (FR-04).
 *
 * Open-source reference architecture: BlackBox-style adapter classes
 * (Api30Adapter / Api34Adapter / Api36Adapter) will live in
 * com.privvy.app.engine.adapters.
 */
object EngineLayer {
    const val DESCRIPTION = "Guest APK loading + lifecycle injection — see package docs"
}
