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

    class Ring(val did: String, val lineType: String, val carrier: String, val receivedAt: Long)

    private val rings = HashMap<String, Ring>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    // a second ring for the same call adds Twilio's line type and carrier; keep them if a later one lacks them
    fun put(caller: String, did: String, lineType: String = "", carrier: String = "") {
        synchronized(this) {
            val previous = rings[caller]
            rings[caller] = Ring(
                did,
                lineType.ifEmpty { previous?.lineType.orEmpty() },
                carrier.ifEmpty { previous?.carrier.orEmpty() },
                SystemClock.elapsedRealtime()
            )
        }
        listeners.forEach { it() }
    }

    @Synchronized
    fun getRing(caller: String): Ring? {
        val now = SystemClock.elapsedRealtime()
        rings.values.removeAll { now - it.receivedAt > MAX_AGE_MS }
        return rings[caller]
    }

    fun getDid(caller: String): String? = getRing(caller)?.did

    fun getRingForCall(context: Context, call: Call?): Ring? {
        if (call == null || call.isOutgoing()) return null
        val number = call.details?.handle?.schemeSpecificPart
        if (number.isNullOrEmpty()) return null
        return getRing(context.config.normalizeToE164(number))
    }

    fun getDidForCall(context: Context, call: Call?): String? = getRingForCall(context, call)?.did

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
