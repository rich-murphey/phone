package org.fossify.phone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.fossify.phone.extensions.config
import org.fossify.phone.helpers.RingStore

// Rings relayed by the Inbox companion app (org.murphey.smsweb, RingRelay.kt):
// extras caller and did, both E.164; did is absent when unknown. A second broadcast for the
// same call_id may follow with Twilio's line_type and carrier, display only.
class RingReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RING = "org.murphey.smsweb.action.RING"
        private const val SENDER_PACKAGE = "org.murphey.smsweb"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RING) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && sentFromPackage != SENDER_PACKAGE) return
        val caller = intent.getStringExtra("caller").orEmpty()
        val did = intent.getStringExtra("did").orEmpty()
        if (caller.isEmpty() || did.isEmpty()) return
        val lineType = intent.getStringExtra("line_type").orEmpty()
        val carrier = intent.getStringExtra("carrier").orEmpty()
        RingStore.put(context.config.normalizeToE164(caller), did, lineType, carrier)
    }
}
