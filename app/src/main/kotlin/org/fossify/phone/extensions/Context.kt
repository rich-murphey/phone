package org.fossify.phone.extensions

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Context.KEYGUARD_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.extensions.launchActivityIntent
import org.fossify.commons.extensions.telecomManager
import org.fossify.commons.helpers.KEY_PHONE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.helpers.isTiramisuPlus
import org.fossify.phone.helpers.Config
import org.fossify.phone.models.SIMAccount

val Context.config: Config get() = Config.newInstance(applicationContext)

val Context.audioManager: AudioManager
    get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

val Context.powerManager: PowerManager
    get() = getSystemService(Context.POWER_SERVICE) as PowerManager

val Context.keyguardManager: KeyguardManager
    get() = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

@SuppressLint("MissingPermission")
fun Context.getAvailableSIMCardLabels(): List<SIMAccount> {
    val simAccounts = mutableListOf<SIMAccount>()
    try {
        telecomManager.callCapablePhoneAccounts.forEachIndexed { index, account ->
            val phoneAccount = telecomManager.getPhoneAccount(account)
            var label = phoneAccount.label.toString()
            var address = phoneAccount.address.toString()
            var formattedNumber = ""
            if (address.startsWith("tel:") && address.substringAfter("tel:").isNotEmpty()) {
                address = Uri.decode(address.substringAfter("tel:"))
                formattedNumber = formatLineNumber(address)
                label += " ($formattedNumber)"
            } else {
                val subscriptionNumber = getSubscriptionPhoneNumber(account)
                if (subscriptionNumber.isNotEmpty()) {
                    address = subscriptionNumber
                    formattedNumber = formatLineNumber(address)
                    label += " ($formattedNumber)"
                }
            }

            simAccounts.add(
                SIMAccount(
                    id = index + 1,
                    handle = phoneAccount.accountHandle,
                    label = label,
                    phoneNumber = address.substringAfter("tel:"),
                    color = phoneAccount.highlightColor,
                    formattedNumber = formattedNumber,
                )
            )
        }
    } catch (ignored: Exception) {
    }

    return simAccounts
}

// subscriptions often store NANP numbers as "15555550100", which formats as "1 555-555-0100" rather than the "+1 555-555-0100" of the caller line
private fun Context.formatLineNumber(number: String): String {
    if (!config.formatPhoneNumbers) {
        return number
    }

    val isNanpWithoutPlus = number.length == 11 && number.startsWith("1") && number.all { it.isDigit() }
    return (if (isNanpWithoutPlus) "+$number" else number).formatPhoneNumber()
}

// many carriers leave the phone account address empty, the subscription usually still knows the line number
@SuppressLint("MissingPermission")
private fun Context.getSubscriptionPhoneNumber(handle: PhoneAccountHandle): String {
    if (!isRPlus() || checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) {
        return ""
    }

    val telephonyManager = getSystemService(TelephonyManager::class.java)
    val subscriptionId = telephonyManager.getSubscriptionId(handle)
    if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
        return ""
    }

    return if (isTiramisuPlus()) {
        getSystemService(SubscriptionManager::class.java).getPhoneNumber(subscriptionId)
    } else {
        @Suppress("DEPRECATION")
        telephonyManager.createForSubscriptionId(subscriptionId).line1Number.orEmpty()
    }
}

@SuppressLint("MissingPermission")
fun Context.areMultipleSIMsAvailable(): Boolean {
    return try {
        telecomManager.callCapablePhoneAccounts.size > 1
    } catch (ignored: Exception) {
        false
    }
}

fun Context.clearMissedCalls() {
    ensureBackgroundThread {
        try {
            // notification cancellation triggers MissedCallNotifier.clearMissedCalls() which, in turn,
            // should update the database and reset the cached missed call count in MissedCallNotifier.java
            // https://android.googlesource.com/platform/packages/services/Telecomm/+/master/src/com/android/server/telecom/ui/MissedCallNotifierImpl.java#170
            telecomManager.cancelMissedCallsNotification()
        } catch (ignored: Exception) {
        }
    }
}

fun Context.canLaunchAccountsConfiguration(): Boolean {
    return Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
        .resolveActivity(packageManager) != null
}

fun Context.launchAccountsConfiguration() {
    startActivity(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS))
}

fun Activity.startAddContactIntent(phoneNumber: String) {
    Intent().apply {
        action = Intent.ACTION_INSERT_OR_EDIT
        type = "vnd.android.cursor.item/contact"
        putExtra(KEY_PHONE, phoneNumber)
        launchActivityIntent(this)
    }
}
