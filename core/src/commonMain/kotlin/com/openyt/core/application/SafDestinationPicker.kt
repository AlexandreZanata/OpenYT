package com.openyt.core.application

/**
 * Chooses an Android Storage Access Framework destination tree. The returned value
 * is the persistable tree URI string.
 */
interface SafDestinationPicker {
    val canPick: Boolean

    fun pick(onPicked: (String?) -> Unit)
}
