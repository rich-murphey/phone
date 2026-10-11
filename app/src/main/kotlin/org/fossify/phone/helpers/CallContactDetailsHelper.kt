package org.fossify.phone.helpers

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
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
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import android.telephony.PhoneNumberUtils
import android.text.SpannableStringBuilder
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.extensions.getPhoneNumberTypeText
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.extensions.config

// every field Android stores for the matching contacts, notes first so they stay visible in a short panel
private const val ORDER_PHONE = 0
private const val ORDER_EMAIL = 1
private const val ORDER_ADDRESS = 2
private const val ORDER_IM = 3
private const val ORDER_SIP = 4
private const val ORDER_RELATION = 5
private const val ORDER_EVENT = 6
private const val ORDER_WEBSITE = 7
private const val ORDER_IDENTITY = 8
private const val ORDER_OTHER = 9
private const val ORDER_GROUPS = 10
private const val ORDER_ACCOUNTS = 11

private class DetailLine(val order: Int, val text: String, val bold: Boolean = false)

fun getContactDetailsText(context: Context, contacts: List<Contact>, callingNumber: String): CharSequence {
    val deviceContactIds = contacts.filter { !it.isPrivate() }.map { it.contactId }.distinct()
    val sections = deviceContactIds.map { getDeviceContactDetails(context, it, callingNumber) } +
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
    val groupIds = LinkedHashSet<Long>()
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
                        val label = Phone.getTypeLabel(res, cursor.int(Phone.TYPE), cursor.text(Phone.LABEL))
                        lines.add(DetailLine(ORDER_PHONE, "$label: ${formatNumber(context, number)}", bold = PhoneNumberUtils.compare(number, callingNumber)))
                    }

                    Email.CONTENT_ITEM_TYPE -> {
                        val label = Email.getTypeLabel(res, cursor.int(Email.TYPE), cursor.text(Email.LABEL))
                        lines.add(DetailLine(ORDER_EMAIL, "$label: ${cursor.text(Email.ADDRESS)}"))
                    }

                    StructuredPostal.CONTENT_ITEM_TYPE -> {
                        val label = StructuredPostal.getTypeLabel(res, cursor.int(StructuredPostal.TYPE), cursor.text(StructuredPostal.LABEL))
                        val address = cursor.text(StructuredPostal.FORMATTED_ADDRESS).lines().filter { it.isNotBlank() }.joinToString(", ")
                        lines.add(DetailLine(ORDER_ADDRESS, "$label: $address"))
                    }

                    Im.CONTENT_ITEM_TYPE -> {
                        val protocol = Im.getProtocolLabel(res, cursor.int(Im.PROTOCOL), cursor.text(Im.CUSTOM_PROTOCOL))
                        lines.add(DetailLine(ORDER_IM, "$protocol: ${cursor.text(Im.DATA)}"))
                    }

                    SipAddress.CONTENT_ITEM_TYPE -> {
                        val label = SipAddress.getTypeLabel(res, cursor.int(SipAddress.TYPE), cursor.text(SipAddress.LABEL))
                        lines.add(DetailLine(ORDER_SIP, "SIP $label: ${cursor.text(SipAddress.SIP_ADDRESS)}"))
                    }

                    Relation.CONTENT_ITEM_TYPE -> {
                        val label = Relation.getTypeLabel(res, cursor.int(Relation.TYPE), cursor.text(Relation.LABEL))
                        lines.add(DetailLine(ORDER_RELATION, "$label: ${cursor.text(Relation.NAME)}"))
                    }

                    Event.CONTENT_ITEM_TYPE -> {
                        val type = cursor.int(Event.TYPE)
                        val label = if (type == Event.TYPE_CUSTOM) {
                            cursor.text(Event.LABEL)
                        } else {
                            res.getString(Event.getTypeResource(type))
                        }
                        lines.add(DetailLine(ORDER_EVENT, "$label: ${cursor.text(Event.START_DATE)}"))
                    }

                    Website.CONTENT_ITEM_TYPE -> lines.add(DetailLine(ORDER_WEBSITE, cursor.text(Website.URL)))

                    Identity.CONTENT_ITEM_TYPE -> {
                        val namespace = cursor.text(Identity.NAMESPACE)
                        val identity = cursor.text(Identity.IDENTITY)
                        lines.add(DetailLine(ORDER_IDENTITY, if (namespace.isNotEmpty()) "$namespace: $identity" else identity))
                    }

                    Note.CONTENT_ITEM_TYPE -> cursor.text(Note.NOTE).trim().takeIf { it.isNotEmpty() }?.let { notes.add(it) }

                    GroupMembership.CONTENT_ITEM_TYPE -> groupIds.add(cursor.getLong(cursor.getColumnIndexOrThrow(GroupMembership.GROUP_ROW_ID)))

                    Photo.CONTENT_ITEM_TYPE -> {}

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

    val groups = getGroupTitles(context, groupIds)
    if (groups.isNotEmpty()) {
        lines.add(DetailLine(ORDER_GROUPS, context.getString(R.string.contact_groups, groups.joinToString(", "))))
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
        notes.forEach { append("\n\n$it\n") }
        lines.sortedBy { it.order }.distinctBy { it.text }.forEach { line ->
            if (line.bold) {
                bold { append("\n${line.text}") }
            } else {
                append("\n${line.text}")
            }
        }
    }
}

// user created groups only, system groups like "My Contacts" say nothing about the person
private fun getGroupTitles(context: Context, groupIds: Collection<Long>): List<String> {
    if (groupIds.isEmpty()) {
        return emptyList()
    }

    val titles = mutableListOf<String>()
    val selection = "${Groups._ID} IN (${groupIds.joinToString(",")}) AND ${Groups.SYSTEM_ID} IS NULL"
    try {
        context.contentResolver.query(Groups.CONTENT_URI, arrayOf(Groups.TITLE), selection, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                cursor.text(Groups.TITLE).takeIf { it.isNotBlank() }?.let { titles.add(it) }
            }
        }
    } catch (ignored: Exception) {
    }

    return titles.distinct()
}

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

        if (contact.notes.isNotBlank()) {
            append("\n\n${contact.notes.trim()}\n")
        }

        contact.phoneNumbers.forEach { phoneNumber ->
            val line = "\n${context.getPhoneNumberTypeText(phoneNumber.type, phoneNumber.label)}: ${formatNumber(context, phoneNumber.value)}"
            if (PhoneNumberUtils.compare(phoneNumber.value, callingNumber)) {
                bold { append(line) }
            } else {
                append(line)
            }
        }

        contact.emails.forEach { append("\n${it.value}") }
        contact.addresses.forEach { append("\n${it.value}") }
        contact.websites.forEach { append("\n$it") }
    }
}

private fun formatNumber(context: Context, number: String) = if (context.config.formatPhoneNumbers) number.formatPhoneNumber() else number

private fun joinNonBlank(separator: String, vararg parts: String?) = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(separator)

private fun Cursor.text(column: String): String {
    val index = getColumnIndex(column)
    return if (index == -1) "" else getString(index).orEmpty()
}

private fun Cursor.int(column: String): Int {
    val index = getColumnIndex(column)
    return if (index == -1 || isNull(index)) 0 else getInt(index)
}
