package org.fossify.phone.helpers

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Identity
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.SipAddress
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.provider.CallLog.Calls
import android.provider.Telephony.Sms
import android.telecom.PhoneAccountHandle
import android.text.format.DateUtils
import android.telephony.PhoneNumberUtils
import android.text.SpannableStringBuilder
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.extensions.getPhoneNumberTypeText
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.extensions.config

// every field Android stores for the matching contacts, notes last
private const val ORDER_PHONE = 0
private const val ORDER_EMAIL = 1
private const val ORDER_ADDRESS = 2
private const val ORDER_IM = 3
private const val ORDER_SIP = 4
private const val ORDER_RELATION = 5
private const val ORDER_EVENT = 6
private const val ORDER_WEBSITE = 7
private const val ORDER_IDENTITY = 8
private const val ORDER_CUSTOM = 9
private const val ORDER_OTHER = 10
private const val ORDER_ACCOUNTS = 11

// rows Google's contacts sync adapter adds for fields Android has no type for
private const val GOOGLE_USER_DEFINED_FIELD = "vnd.com.google.cursor.item/contact_user_defined_field"
private const val GOOGLE_MISC = "vnd.com.google.cursor.item/contact_misc"

private class DetailLine(val order: Int, val text: String, val bold: Boolean = false)

fun getContactDetailsText(context: Context, contacts: List<Contact>, callingNumber: String, line: PhoneAccountHandle?): CharSequence {
    val deviceContactIds = contacts.filter { !it.isPrivate() }.map { it.contactId }.distinct()
    val numbers = (contacts.flatMap { contact -> contact.phoneNumbers.map { it.value } } + callingNumber).filter { it.isNotBlank() }.distinct()
    val sections = listOf(getScreening(context, callingNumber, line), getHistory(context, numbers)) +
        deviceContactIds.map { getDeviceContactDetails(context, it, callingNumber) } +
        contacts.filter { it.isPrivate() }.map { getPrivateContactDetails(context, it, callingNumber) }

    // the same person can be stored in several places, show identical entries once
    return sections
        .filter { it.isNotEmpty() }
        .distinctBy { it.toString() }
        .joinTo(SpannableStringBuilder(), "\n\n")
}

private fun getDeviceContactDetails(context: Context, contactId: Int, callingNumber: String): CharSequence {
    val res = context.resources
    var fullName = ""
    var phoneticName = ""
    var isStarred = false
    val nicknames = LinkedHashSet<String>()
    val organizations = LinkedHashSet<String>()
    val lines = mutableListOf<DetailLine>()
    val notes = LinkedHashSet<String>()
    val accounts = LinkedHashSet<String>()

    val projection = arrayOf(
        Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5, Data.DATA6, Data.DATA7, Data.DATA8, Data.DATA9,
        Data.STARRED, RawContacts.ACCOUNT_NAME
    )

    try {
        context.contentResolver.query(Data.CONTENT_URI, projection, "${Data.CONTACT_ID} = ?", arrayOf(contactId.toString()), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                isStarred = isStarred || cursor.getInt(cursor.getColumnIndexOrThrow(Data.STARRED)) == 1
                cursor.text(RawContacts.ACCOUNT_NAME).takeIf { it.isNotEmpty() }?.let { accounts.add(it) }

                when (cursor.text(Data.MIMETYPE)) {
                    StructuredName.CONTENT_ITEM_TYPE -> {
                        val name = joinNonBlank(
                            " ",
                            cursor.text(StructuredName.PREFIX),
                            cursor.text(StructuredName.GIVEN_NAME),
                            cursor.text(StructuredName.MIDDLE_NAME),
                            cursor.text(StructuredName.FAMILY_NAME),
                            cursor.text(StructuredName.SUFFIX)
                        ).ifEmpty { cursor.text(StructuredName.DISPLAY_NAME) }
                        if (fullName.isEmpty()) {
                            fullName = name
                        }

                        val phonetic = joinNonBlank(
                            " ",
                            cursor.text(StructuredName.PHONETIC_GIVEN_NAME),
                            cursor.text(StructuredName.PHONETIC_MIDDLE_NAME),
                            cursor.text(StructuredName.PHONETIC_FAMILY_NAME)
                        )
                        if (phoneticName.isEmpty()) {
                            phoneticName = phonetic
                        }
                    }

                    Nickname.CONTENT_ITEM_TYPE -> cursor.text(Nickname.NAME).takeIf { it.isNotBlank() }?.let { nicknames.add(it) }

                    Organization.CONTENT_ITEM_TYPE -> {
                        joinNonBlank(", ", cursor.text(Organization.TITLE), cursor.text(Organization.DEPARTMENT), cursor.text(Organization.COMPANY))
                            .takeIf { it.isNotEmpty() }?.let { organizations.add(it) }
                        cursor.text(Organization.JOB_DESCRIPTION).takeIf { it.isNotBlank() }?.let { organizations.add(it) }
                        cursor.text(Organization.OFFICE_LOCATION).takeIf { it.isNotBlank() }?.let { organizations.add(it) }
                    }

                    Phone.CONTENT_ITEM_TYPE -> {
                        val number = cursor.text(Phone.NUMBER)
                        val label = cursor.typeLabel(Phone.TYPE, Phone.LABEL, Phone.TYPE_CUSTOM) { type, label -> Phone.getTypeLabel(res, type, label) }
                        lines.add(DetailLine(ORDER_PHONE, labeled(label, formatNumber(context, number)), bold = PhoneNumberUtils.compare(number, callingNumber)))
                    }

                    Email.CONTENT_ITEM_TYPE -> {
                        val label = cursor.typeLabel(Email.TYPE, Email.LABEL, Email.TYPE_CUSTOM) { type, label -> Email.getTypeLabel(res, type, label) }
                        lines.add(DetailLine(ORDER_EMAIL, labeled(label, cursor.text(Email.ADDRESS))))
                    }

                    StructuredPostal.CONTENT_ITEM_TYPE -> {
                        val label = cursor.typeLabel(StructuredPostal.TYPE, StructuredPostal.LABEL, StructuredPostal.TYPE_CUSTOM) { type, label ->
                            StructuredPostal.getTypeLabel(res, type, label)
                        }
                        // Google sync leaves FORMATTED_ADDRESS empty for about half the addresses, build those from the parts
                        val address = cursor.text(StructuredPostal.FORMATTED_ADDRESS).lines().filter { it.isNotBlank() }.joinToString(", ")
                            .ifEmpty {
                                joinNonBlank(
                                    ", ",
                                    cursor.text(StructuredPostal.STREET).lines().filter { it.isNotBlank() }.joinToString(", "),
                                    cursor.text(StructuredPostal.POBOX),
                                    cursor.text(StructuredPostal.NEIGHBORHOOD),
                                    cursor.text(StructuredPostal.CITY),
                                    joinNonBlank(" ", cursor.text(StructuredPostal.REGION), cursor.text(StructuredPostal.POSTCODE)),
                                    cursor.text(StructuredPostal.COUNTRY)
                                )
                            }
                        if (address.isNotEmpty()) {
                            lines.add(DetailLine(ORDER_ADDRESS, labeled(label, address)))
                        }
                    }

                    Im.CONTENT_ITEM_TYPE -> {
                        val protocol = cursor.typeLabel(Im.PROTOCOL, Im.CUSTOM_PROTOCOL, Im.PROTOCOL_CUSTOM) { type, label -> Im.getProtocolLabel(res, type, label) }
                        lines.add(DetailLine(ORDER_IM, labeled(protocol, cursor.text(Im.DATA))))
                    }

                    SipAddress.CONTENT_ITEM_TYPE -> {
                        val label = cursor.typeLabel(SipAddress.TYPE, SipAddress.LABEL, SipAddress.TYPE_CUSTOM) { type, label -> SipAddress.getTypeLabel(res, type, label) }
                        lines.add(DetailLine(ORDER_SIP, labeled(joinNonBlank(" ", "SIP", label), cursor.text(SipAddress.SIP_ADDRESS))))
                    }

                    Relation.CONTENT_ITEM_TYPE -> {
                        val label = cursor.typeLabel(Relation.TYPE, Relation.LABEL, Relation.TYPE_CUSTOM) { type, label -> Relation.getTypeLabel(res, type, label) }
                        lines.add(DetailLine(ORDER_RELATION, labeled(label, cursor.text(Relation.NAME))))
                    }

                    Event.CONTENT_ITEM_TYPE -> {
                        val label = cursor.typeLabel(Event.TYPE, Event.LABEL, Event.TYPE_CUSTOM) { type, label ->
                            if (type == Event.TYPE_CUSTOM) label else res.getString(Event.getTypeResource(type))
                        }
                        lines.add(DetailLine(ORDER_EVENT, labeled(label, cursor.text(Event.START_DATE))))
                    }

                    Website.CONTENT_ITEM_TYPE -> lines.add(DetailLine(ORDER_WEBSITE, cursor.text(Website.URL)))

                    // Google sync stores the contact's Google profile id here, an opaque number
                    Identity.CONTENT_ITEM_TYPE -> if (cursor.text(Identity.NAMESPACE) != "com.google") {
                        val namespace = cursor.text(Identity.NAMESPACE)
                        val identity = cursor.text(Identity.IDENTITY)
                        lines.add(DetailLine(ORDER_IDENTITY, if (namespace.isNotEmpty()) "$namespace: $identity" else identity))
                    }

                    Note.CONTENT_ITEM_TYPE -> cursor.text(Note.NOTE).trim().takeIf { it.isNotEmpty() }?.let { notes.add(it) }

                    Photo.CONTENT_ITEM_TYPE -> {}

                    // DATA1 is the field's label, DATA2 its value
                    GOOGLE_USER_DEFINED_FIELD -> {
                        val value = cursor.text(Data.DATA2)
                        if (value.isNotBlank()) {
                            lines.add(DetailLine(ORDER_CUSTOM, labeled(cursor.text(Data.DATA1), value)))
                        }
                    }

                    // DATA7 is the occupation; initials (DATA4) and a numeric flag (DATA3) are left out
                    GOOGLE_MISC -> cursor.text(Data.DATA7).takeIf { it.isNotBlank() }?.let { organizations.add(it) }

                    // rows added by other apps (WhatsApp, Signal, ...) keep a human readable summary in DATA3
                    else -> {
                        val summary = cursor.text(Data.DATA3).ifBlank { cursor.text(Data.DATA2) }.ifBlank { cursor.text(Data.DATA1) }
                        if (summary.isNotBlank()) {
                            lines.add(DetailLine(ORDER_OTHER, summary))
                        }
                    }
                }
            }
        }
    } catch (ignored: Exception) {
        return ""
    }

    if (accounts.isNotEmpty()) {
        lines.add(DetailLine(ORDER_ACCOUNTS, context.getString(R.string.contact_saved_in, accounts.joinToString(", "))))
    }

    return buildSpannedString {
        bold { append(fullName) }
        if (isStarred) {
            append(" ★")
        }

        nicknames.forEach { append(" \"$it\"") }
        if (phoneticName.isNotEmpty()) {
            append("\n($phoneticName)")
        }

        organizations.forEach { append("\n$it") }
        lines.sortedBy { it.order }.distinctBy { it.text }.forEach { line ->
            if (line.bold) {
                bold { append("\n${line.text}") }
            } else {
                append("\n${line.text}")
            }
        }
        notes.forEach { append("\n\n$it") }
    }
}

// the caller's contact labels and the called line's screening level
private fun getScreening(context: Context, callingNumber: String, line: PhoneAccountHandle?): String {
    val lines = mutableListOf<String>()
    val labels = CallScreening.getCallerLabels(context, callingNumber).orEmpty()
    if (labels.isNotEmpty()) {
        lines.add(context.getString(R.string.contact_labels, labels.sorted().joinToString(", ")))
    }

    CallScreening.getLevelKey(context, line, callingNumber)?.let { key ->
        lines.add(context.getString(R.string.screening_filter, CallScreening.getLevelText(context, context.config.getScreeningLevel(key))))
    }

    return lines.joinToString("\n")
}

// the last call and the last two SMS with any of the person's numbers, unknown callers included
private fun getHistory(context: Context, numbers: List<String>): CharSequence {
    if (numbers.isEmpty()) {
        return ""
    }

    val selection = numbers.joinToString(" OR ") { "PHONE_NUMBERS_EQUAL(%s, ?, 0)" }
    val args = numbers.toTypedArray()
    val lines = mutableListOf<String>()
    try {
        context.contentResolver.query(
            Calls.CONTENT_URI, arrayOf(Calls.DATE, Calls.TYPE, Calls.DURATION), selection.format(*Array(numbers.size) { Calls.NUMBER }), args,
            "${Calls.DATE} DESC LIMIT 1"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val duration = cursor.int(Calls.DURATION)
                val kind = when (cursor.int(Calls.TYPE)) {
                    Calls.INCOMING_TYPE -> context.getString(R.string.last_call_incoming)
                    Calls.OUTGOING_TYPE -> context.getString(R.string.last_call_outgoing)
                    Calls.MISSED_TYPE -> context.getString(R.string.last_call_missed)
                    Calls.REJECTED_TYPE -> context.getString(R.string.last_call_rejected)
                    Calls.BLOCKED_TYPE -> context.getString(R.string.last_call_blocked)
                    Calls.VOICEMAIL_TYPE -> context.getString(R.string.last_call_voicemail)
                    else -> ""
                }
                val details = joinNonBlank(", ", kind, if (duration > 0) DateUtils.formatElapsedTime(duration.toLong()) else "")
                lines.add(context.getString(R.string.last_call, formatDate(context, cursor.long(Calls.DATE)), details))
            }
        }
    } catch (ignored: Exception) {
    }

    try {
        context.contentResolver.query(
            Sms.CONTENT_URI, arrayOf(Sms.DATE, Sms.TYPE, Sms.BODY), selection.format(*Array(numbers.size) { Sms.ADDRESS }), args,
            "${Sms.DATE} DESC LIMIT 2"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val textId = if (cursor.int(Sms.TYPE) == Sms.MESSAGE_TYPE_INBOX) R.string.text_received else R.string.text_sent
                lines.add(context.getString(textId, formatDate(context, cursor.long(Sms.DATE)), cursor.text(Sms.BODY).trim()))
            }
        }
    } catch (ignored: Exception) {
    }

    return lines.joinToString("\n")
}

private fun formatDate(context: Context, millis: Long) =
    DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)

private fun getPrivateContactDetails(context: Context, contact: Contact, callingNumber: String): CharSequence {
    return buildSpannedString {
        val fullName = joinNonBlank(" ", contact.prefix, contact.firstName, contact.middleName, contact.surname, contact.suffix)
            .ifEmpty { contact.getNameToDisplay() }
        bold { append(fullName) }
        if (contact.nickname.isNotEmpty()) {
            append(" \"${contact.nickname}\"")
        }

        val organization = joinNonBlank(", ", contact.organization.jobPosition, contact.organization.company)
        if (organization.isNotEmpty()) {
            append("\n$organization")
        }

        contact.phoneNumbers.forEach { phoneNumber ->
            val label = if (phoneNumber.type == Phone.TYPE_CUSTOM && phoneNumber.label.isBlank()) {
                ""
            } else {
                context.getPhoneNumberTypeText(phoneNumber.type, phoneNumber.label)
            }
            val line = "\n${labeled(label, formatNumber(context, phoneNumber.value))}"
            if (PhoneNumberUtils.compare(phoneNumber.value, callingNumber)) {
                bold { append(line) }
            } else {
                append(line)
            }
        }

        contact.emails.forEach { append("\n${it.value}") }
        contact.addresses.forEach { append("\n${it.value}") }
        contact.websites.forEach { append("\n$it") }
        if (contact.notes.isNotBlank()) {
            append("\n\n${contact.notes.trim()}")
        }
    }
}

private fun formatNumber(context: Context, number: String) = if (context.config.formatPhoneNumbers) number.formatPhoneNumber() else number

// a custom type saved without a label would only say "Custom", leave the label out then
private fun Cursor.typeLabel(typeColumn: String, labelColumn: String, customType: Int, getTypeLabel: (type: Int, label: String) -> CharSequence): String {
    val type = int(typeColumn)
    val label = text(labelColumn)
    return if (type == customType && label.isBlank()) "" else getTypeLabel(type, label).toString()
}

private fun labeled(label: String, value: String) = if (label.isEmpty()) value else "$label: $value"

private fun joinNonBlank(separator: String, vararg parts: String?) = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(separator)

private fun Cursor.text(column: String): String {
    val index = getColumnIndex(column)
    return if (index == -1) "" else getString(index).orEmpty()
}

private fun Cursor.long(column: String): Long {
    val index = getColumnIndex(column)
    return if (index == -1 || isNull(index)) 0 else getLong(index)
}

private fun Cursor.int(column: String): Int {
    val index = getColumnIndex(column)
    return if (index == -1 || isNull(index)) 0 else getInt(index)
}
