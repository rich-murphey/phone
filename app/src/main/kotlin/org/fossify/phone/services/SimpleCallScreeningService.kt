package org.fossify.phone.services

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallScreeningService
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.isQPlus
import org.fossify.phone.extensions.config
import org.fossify.phone.helpers.CallScreening
import org.fossify.phone.helpers.RingStore

class SimpleCallScreeningService : CallScreeningService() {
    companion object {
        // Inbox's ring broadcast can trail the call; Telecom waits about 5 s for a screening answer
        private const val RING_WAIT_MS = 2500L
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart
        when {
            number != null && isNumberBlocked(number) -> {
                respondToCall(callDetails, isBlocked = true)
            }

            isIncoming(callDetails) && CallScreening.mayDependOnRing(this, CallScreening.getRingingLine(this), number) -> {
                screenAfterRing(callDetails, number)
            }

            isIncoming(callDetails) && !CallScreening.allows(this, CallScreening.getRingingLine(this), number) -> {
                decline(callDetails)
            }

            number != null && baseConfig.blockUnknownNumbers -> {
                val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
                val result = SimpleContactsHelper(this).existsSync(number, privateCursor)
                respondToCall(callDetails, isBlocked = result == ContactLookupResult.NotFound)
            }

            number == null && baseConfig.blockHiddenNumbers -> {
                respondToCall(callDetails, isBlocked = true)
            }

            else -> {
                respondToCall(callDetails, isBlocked = false)
            }
        }
    }

    // Declined calls go to the line's busy forwarding (a VoIP.ms voicemail number) and stay in the call log
    private fun decline(callDetails: Call.Details) {
        val response = CallResponse.Builder()
            .setDisallowCall(true)
            .setRejectCall(true)
            .setSkipNotification(true)
            .build()
        respondToCall(callDetails, response)
    }

    // A call forwarded from a VoIP.ms number with its own level is known only once Inbox's ring
    // broadcast lands, so wait briefly for it when it would change the answer
    private fun screenAfterRing(callDetails: Call.Details, number: String?) {
        val handler = Handler(Looper.getMainLooper())
        var responded = false
        lateinit var onRing: () -> Unit
        fun respond() {
            if (responded) return
            responded = true
            RingStore.removeListener(onRing)
            handler.removeCallbacksAndMessages(null)
            if (CallScreening.allows(this, CallScreening.getRingingLine(this), number)) {
                respondToCall(callDetails, isBlocked = false)
            } else {
                decline(callDetails)
            }
        }

        onRing = { handler.post { if (number != null && RingStore.getDid(config.normalizeToE164(number)) != null) respond() } }
        RingStore.addListener(onRing)
        handler.postDelayed({ respond() }, RING_WAIT_MS)
    }

    // the screening role also sees outgoing calls from Android 10
    private fun isIncoming(callDetails: Call.Details) =
        !isQPlus() || callDetails.callDirection == Call.Details.DIRECTION_INCOMING

    private fun respondToCall(callDetails: Call.Details, isBlocked: Boolean) {
        val response = CallResponse.Builder()
            .setDisallowCall(isBlocked)
            .setRejectCall(isBlocked)
            .setSkipCallLog(isBlocked)
            .setSkipNotification(isBlocked)
            .build()

        respondToCall(callDetails, response)
    }
}
