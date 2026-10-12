package org.fossify.phone.services

import android.telecom.Call
import android.telecom.CallScreeningService
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.isQPlus
import org.fossify.phone.helpers.CallScreening

class SimpleCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart
        when {
            number != null && isNumberBlocked(number) -> {
                respondToCall(callDetails, isBlocked = true)
            }

            isIncoming(callDetails) && !CallScreening.allows(this, CallScreening.getRingingLine(this), number) -> {
                // declined, so T-Mobile hands it to the line's busy forwarding (a VoIP.ms voicemail
                // number); kept in the call log so turned-away callers can be seen
                val response = CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipNotification(true)
                    .build()
                respondToCall(callDetails, response)
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
