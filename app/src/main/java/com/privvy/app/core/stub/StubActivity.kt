package com.privvy.app.core.stub

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.privvy.app.security.SessionGuardService

/**
 * Base stub for all guest Activities (spec §5.1).
 *
 * A stub Activity is what system_server believes it launched. Its real job:
 *  1. Read the *original* guest Intent, which the hook layer attached to the
 *     stub Intent under [EXTRA_ORIGINAL].
 *  2. Ask the engine (com.privvy.app.engine) to load the guest APK with a
 *     DexClassLoader and instantiate the guest Activity class.
 *  3. Delegate lifecycle to that guest instance (Milestone 1 scope).
 */
abstract class StubActivityBase : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO(M1): delegate to GuestActivityLifecycle (engine layer) instead of
        //  failing fast. Kept visible so the stub wiring can be end-to-end tested.
        val original = intent.getParcelableExtra<Intent>(EXTRA_ORIGINAL)
        android.util.Log.i("PrivvyStub", "stub $localClassName got original=$original")
    }

    override fun onResume() {
        super.onResume()
        notifyGuard(SessionGuardService.ACTION_FOREGROUND)
    }

    override fun onStop() {
        super.onStop()
        notifyGuard(SessionGuardService.ACTION_BACKGROUND)
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent?): Boolean {
        if (event?.action == android.view.MotionEvent.ACTION_DOWN) {
            notifyGuard(SessionGuardService.ACTION_TOUCH)
        }
        return super.dispatchTouchEvent(event)
    }

    private fun notifyGuard(action: String) {
        ContextCompat.startForegroundService(
            applicationContext,
            Intent(applicationContext, SessionGuardService::class.java).setAction(action),
        )
    }

    companion object {
        const val EXTRA_ORIGINAL = "privvy.original_intent"
    }
}

class StubActivityStandard_V0 : StubActivityBase()
class StubActivitySingleTop_V0 : StubActivityBase()
class StubActivitySingleTask_V0 : StubActivityBase()

class StubActivityStandard_V1 : StubActivityBase()
class StubActivitySingleTop_V1 : StubActivityBase()
class StubActivitySingleTask_V1 : StubActivityBase()

class StubActivityStandard_V2 : StubActivityBase()
class StubActivitySingleTop_V2 : StubActivityBase()
class StubActivitySingleTask_V2 : StubActivityBase()

class StubActivityStandard_V3 : StubActivityBase()
class StubActivitySingleTop_V3 : StubActivityBase()
class StubActivitySingleTask_V3 : StubActivityBase()
