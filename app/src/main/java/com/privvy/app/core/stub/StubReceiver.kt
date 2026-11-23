package com.privvy.app.core.stub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Base stub for guest BroadcastReceivers (spec §5.1) — wired in Milestone 2. */
open class StubReceiverBase : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) = Unit
}

class StubReceiver_V0 : StubReceiverBase()
class StubReceiver_V1 : StubReceiverBase()
class StubReceiver_V2 : StubReceiverBase()
class StubReceiver_V3 : StubReceiverBase()
