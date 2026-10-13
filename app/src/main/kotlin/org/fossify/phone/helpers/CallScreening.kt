package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.PhoneLookup
import android.telecom.PhoneAccountHandle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.extensions.telecomManager
import org.fossify.commons.helpers.isSPlus
import org.fossify.phone.R
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.getAvailableSIMCardLabels

// Per-line whitelist. Each level is a contact label, matched by title across accounts;
// level n rings for callers carrying any of the first n labels, level 0 rings for everyone,
// and SCREENING_NOBODY for no one.
// A line is a SIM, or a VoIP.ms number whose forwarded calls are known from Inbox's ring broadcast.
object CallScreening {
    class Line(val key: String, val title: String)

    fun getLines(context: Context): List<Line> =
        context.getAvailableSIMCardLabels().map { Line(it.handle.id, it.formattedNumber.ifEmpty { it.label }) } +
            context.config.screenedVoipNumbers.sorted().map { Line(it, it.formatPhoneNumber()) }

    // the VoIP.ms number's level when the call was forwarded from one with its own, else the SIM's
    fun getLevelKey(context: Context, handle: PhoneAccountHandle?, number: String?): String? {
        val did = number?.takeIf { it.isNotEmpty() }?.let { RingStore.getDid(context.config.normalizeToE164(it)) }
        return did?.takeIf { it in context.config.screenedVoipNumbers } ?: handle?.id
    }

    fun allows(context: Context, handle: PhoneAccountHandle?, number: String?): Boolean {
        val level = getLevelKey(context, handle, number)?.let { context.config.getScreeningLevel(it) } ?: 0
        return allowsAt(context, level, number)
    }

    // whether a ring broadcast still to come could change the answer for this caller
    fun mayDependOnRing(context: Context, handle: PhoneAccountHandle?, number: String?): Boolean {
        val config = context.config
        if (number.isNullOrEmpty() || config.screenedVoipNumbers.isEmpty() || RingStore.getDid(config.normalizeToE164(number)) != null) {
            return false
        }

        val now = allows(context, handle, number)
        return config.screenedVoipNumbers.any { allowsAt(context, config.getScreeningLevel(it), number) != now }
    }

    private fun allowsAt(context: Context, level: Int, number: String?): Boolean {
        if (level == 0) {
            return true
        }

        if (level == SCREENING_NOBODY || number.isNullOrEmpty()) {
            return false
        }

        val allowed = context.config.screeningLabels.take(level).filter { it.isNotEmpty() }
        // a contacts provider failure lets the call ring rather than turning family away
        val labels = getCallerLabels(context, number) ?: return true
        return labels.any { it in allowed }
    }

    // Telecom hands screening services the call without its PhoneAccountHandle, so take the line
    // from the SIM subscription that is ringing
    @SuppressLint("MissingPermission")
    fun getRingingLine(context: Context): PhoneAccountHandle? {
        if (!isSPlus()) {
            return null
        }

        return try {
            val telephonyManager = context.getSystemService(TelephonyManager::class.java)
            context.telecomManager.callCapablePhoneAccounts.firstOrNull { handle ->
                val subId = telephonyManager.getSubscriptionId(handle)
                subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
                    telephonyManager.createForSubscriptionId(subId).callStateForSubscription == TelephonyManager.CALL_STATE_RINGING
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getLevelText(context: Context, level: Int): String {
        if (level == 0) {
            return context.getString(R.string.screening_everyone)
        }

        if (level == SCREENING_NOBODY) {
            return context.getString(R.string.screening_nobody)
        }

        val labels = context.config.screeningLabels.take(level).filter { it.isNotEmpty() }
        return context.getString(R.string.screening_level, level, labels.joinToString(", ").ifEmpty { context.getString(R.string.screening_no_label) })
    }

    fun getLabelTitles(context: Context): List<String> {
        val titles = mutableListOf<String>()
        try {
            context.contentResolver.query(Groups.CONTENT_URI, arrayOf(Groups.TITLE), "${Groups.DELETED} = 0", null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { titles.add(it) }
                }
            }
        } catch (ignored: Exception) {
        }

        return titles.distinct().sorted()
    }

    fun getCallerLabels(context: Context, number: String): Set<String>? {
        val resolver = context.contentResolver
        return try {
            val contactIds = mutableListOf<Long>()
            val lookupUri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            resolver.query(lookupUri, arrayOf(PhoneLookup._ID), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    contactIds.add(cursor.getLong(0))
                }
            }

            if (contactIds.isEmpty()) {
                return emptySet()
            }

            val groupIds = mutableSetOf<Long>()
            val selection = "${Data.CONTACT_ID} IN (${contactIds.joinToString(",")}) AND ${Data.MIMETYPE} = ?"
            resolver.query(Data.CONTENT_URI, arrayOf(GroupMembership.GROUP_ROW_ID), selection, arrayOf(GroupMembership.CONTENT_ITEM_TYPE), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    groupIds.add(cursor.getLong(0))
                }
            }

            if (groupIds.isEmpty()) {
                return emptySet()
            }

            val titles = mutableSetOf<String>()
            resolver.query(Groups.CONTENT_URI, arrayOf(Groups.TITLE), "${Groups._ID} IN (${groupIds.joinToString(",")})", null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { titles.add(it) }
                }
            }
            titles
        } catch (e: Exception) {
            null
        }
    }
}
