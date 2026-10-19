package org.fossify.phone.helpers

import android.os.SystemClock

// The screening level each recent incoming call was decided on, keyed by E.164 caller, so the call
// screen shows the level that was applied even when Inbox's ring lands after screening gave up on it
object ScreeningStore {
    private const val MAX_AGE_MS = 2 * 60 * 1000L

    class Decision(val levelKey: String?, val level: Int, val decidedAt: Long)

    private val decisions = HashMap<String, Decision>()

    @Synchronized
    fun put(caller: String, levelKey: String?, level: Int) {
        decisions[caller] = Decision(levelKey, level, SystemClock.elapsedRealtime())
    }

    @Synchronized
    fun getDecision(caller: String): Decision? {
        val now = SystemClock.elapsedRealtime()
        decisions.values.removeAll { now - it.decidedAt > MAX_AGE_MS }
        return decisions[caller]
    }
}
