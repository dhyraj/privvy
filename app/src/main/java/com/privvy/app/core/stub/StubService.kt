package com.privvy.app.core.stub

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.privvy.app.core.InstalledApp
import com.privvy.app.engine.GuestComponentRouter
import com.privvy.app.engine.GuestRuntimeFactory
import com.privvy.app.security.SessionLockManager

open class StubServiceBase : Service() {

    private var guestApp: InstalledApp? = null
    private var guestService: Service? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val guest = guestService ?: createGuest(intent) ?: return START_NOT_STICKY
        val original = GuestComponentRouter.original(intent)
        val result = guest.onStartCommand(original, flags, startId)
        if (result == Service.START_NOT_STICKY) {
            stopSelf(startId)
        }
        return result
    }

    override fun onBind(intent: Intent?): IBinder? {
        val guest = guestService ?: createGuest(intent) ?: return null
        return guest.onBind(GuestComponentRouter.original(intent))
    }

    override fun onUnbind(bindIntent: Intent?): Boolean =
        guestService?.onUnbind(GuestComponentRouter.original(bindIntent)) ?: false

    override fun onRebind(bindIntent: Intent?) {
        guestService?.onRebind(GuestComponentRouter.original(bindIntent))
    }

    override fun onDestroy() {
        guestService?.onDestroy()
        guestService = null
        super.onDestroy()
    }

    private fun createGuest(startIntent: Intent?): Service? {
        if (guestService != null) return guestService
        if (!SessionLockManager.ensureGuestAllowed(this)) return null
        val app = GuestComponentRouter.appFrom(startIntent) ?: return null
        val original = GuestComponentRouter.original(startIntent)
        val className = original?.component?.className
            ?: app.serviceNames.firstOrNull()
            ?: return null
        return runCatching { GuestRuntimeFactory.createService(this, app, className) }
            .onSuccess { guestApp = app; guestService = it }
            .onFailure { android.util.Log.e("PrivvyStub", "guest service failed", it) }
            .getOrNull()
    }
}

class StubService_V0 : StubServiceBase()
class StubService_V1 : StubServiceBase()
class StubService_V2 : StubServiceBase()
class StubService_V3 : StubServiceBase()
