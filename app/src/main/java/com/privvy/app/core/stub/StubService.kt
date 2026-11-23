package com.privvy.app.core.stub

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Base stub for guest Services (spec §5.1) — wired in Milestone 2. */
open class StubServiceBase : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}

class StubService_V0 : StubServiceBase()
class StubService_V1 : StubServiceBase()
class StubService_V2 : StubServiceBase()
class StubService_V3 : StubServiceBase()
