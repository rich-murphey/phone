package org.fossify.phone.helpers

import android.content.Context
import android.os.SystemClock
import android.telecom.Call
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.isOutgoing
import java.util.concurrent.CopyOnWriteArraySet

// Recent VoIP.ms rings relayed by the Inbox companion app, keyed by E.164 caller.
// A ring can land shortly before or after Telecom adds the call it belongs to.
object RingStore {
    private const val MAX_AGE_MS = 2 * 60 * 1000L

    private class Ring(val did: String, val receivedAt: Long)

    private val rings = HashMap<String, Ring>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun put(caller: String, did: String) {
        synchronized(this) { rings[caller] = Ring(did, SystemClock.elapsedRealtime()) }
        listeners.forEach { it() }
    }

    @Synchronized
    fun getDid(caller: String): String? {
        val now = SystemClock.elapsedRealtime()
        rings.values.removeAll { now - it.receivedAt > MAX_AGE_MS }
        return rings[caller]?.did
    }

    fun getDidForCall(context: Context, call: Call?): String? {
        if (call == null || call.isOutgoing()) return null
        val number = call.details?.handle?.schemeSpecificPart
        if (number.isNullOrEmpty()) return null
        return getDid(context.config.normalizeToE164(number))
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
