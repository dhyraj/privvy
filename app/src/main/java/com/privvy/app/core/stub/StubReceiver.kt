package com.privvy.app.core.stub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.privvy.app.engine.GuestComponentRouter
import com.privvy.app.engine.GuestRuntimeFactory

open class StubReceiverBase : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val app = GuestComponentRouter.appFrom(intent) ?: return
        val original = GuestComponentRouter.original(intent) ?: return
        val className = original.component?.className ?: app.receiverNames.firstOrNull() ?: return
        val appContext = context ?: return
        runCatching {
            val runtime = GuestRuntimeFactory.get(appContext, app)
            val guest = runtime.loader.loadClass(className)
                .getDeclaredConstructor().newInstance() as BroadcastReceiver
            guest.onReceive(runtime.guestContext, original)
        }.onFailure { android.util.Log.e("PrivvyStub", "guest receiver failed", it) }
    }
}

class StubReceiver_V0 : StubReceiverBase()
class StubReceiver_V1 : StubReceiverBase()
class StubReceiver_V2 : StubReceiverBase()
class StubReceiver_V3 : StubReceiverBase()
