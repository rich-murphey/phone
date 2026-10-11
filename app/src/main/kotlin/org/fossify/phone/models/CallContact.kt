package org.fossify.phone.models

import org.fossify.commons.models.contacts.Contact

// a simpler Contact model containing just info needed at the call screen
data class CallContact(
    var name: String,
    var photoUri: String,
    var number: String,
    var numberLabel: String,
    var callerIdName: String = "",
    var matchingContacts: List<Contact> = emptyList(),
)
