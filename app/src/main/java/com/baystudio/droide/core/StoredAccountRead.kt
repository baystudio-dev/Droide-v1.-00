package com.baystudio.droide.core

internal enum class StoredAccountFieldType { TEXT, LONG }
/** Validate types only. No values are logged, erased, coerced, or treated as missing. */
internal fun validateStoredAccountFields(values: Map<String, *>, types: Map<String, StoredAccountFieldType>) {
    types.forEach { (key, type) ->
        val value = values[key] ?: return@forEach
        val valid = when (type) {
            StoredAccountFieldType.TEXT -> value is String
            StoredAccountFieldType.LONG -> value is Long
        }
        if (!valid) throw ClassCastException("Stored account data has an invalid type")
    }
}
/** Read/initialization boundary only. Cancellation and unexpected defects remain exceptions. */
internal inline fun <T> readStoredAccount(validate: () -> Unit, unavailable: () -> T, read: () -> T): T =
    try { validate(); read() } catch (_: ClassCastException) { unavailable() }
