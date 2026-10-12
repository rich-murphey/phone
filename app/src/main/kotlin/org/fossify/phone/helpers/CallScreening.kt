package org.fossify.phone.helpers

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.PhoneLookup
import android.telecom.PhoneAccountHandle
import org.fossify.phone.R
import org.fossify.phone.extensions.config

// Per-line whitelist. Each level is a contact label, matched by title across accounts;
// level n rings for callers carrying any of the first n labels, level 0 rings for everyone.
object CallScreening {
    fun allows(context: Context, handle: PhoneAccountHandle?, number: String?): Boolean {
        val level = handle?.let { context.config.getScreeningLevel(it) } ?: 0
        if (level == 0) {
            return true
        }

        if (number.isNullOrEmpty()) {
            return false
        }

        val allowed = context.config.screeningLabels.take(level).filter { it.isNotEmpty() }
        // a contacts provider failure lets the call ring rather than turning family away
        val labels = getCallerLabels(context, number) ?: return true
        return labels.any { it in allowed }
    }

    fun getLevelText(context: Context, level: Int): String {
        if (level == 0) {
            return context.getString(R.string.screening_everyone)
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
