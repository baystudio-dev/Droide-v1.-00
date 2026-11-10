package com.baystudio.droide.core

import kotlinx.serialization.json.*

data class LspSignatureParameter(
    val label: String,
    val documentation: String? = null,
    val startOffset: Int? = null,
    val endOffset: Int? = null,
)

data class LspSignatureInformation(
    val label: String,
    val documentation: String? = null,
    val parameters: List<LspSignatureParameter> = emptyList(),
    val activeParameter: Int? = null,
)

data class LspSignatureHelp(
    val signatures: List<LspSignatureInformation>,
    val activeSignature: Int = 0,
    val activeParameter: Int = 0,
    val applicationGuard: LspCompletionApplicationGuard? = null,
)

data class LspSignatureTriggers(
    val triggerCharacters: Set<String> = emptySet(),
    val retriggerCharacters: Set<String> = emptySet(),
)

enum class LspSignatureTriggerKind(val wireValue: Int) {
    INVOKED(1),
    TRIGGER_CHARACTER(2),
    CONTENT_CHANGE(3),
}

internal object LspSignatureHelpProtocol {
    const val MAX_SIGNATURES = 12
    const val MAX_PARAMETERS = 64
    const val MAX_LABEL_CHARS = 2_000
    const val MAX_DOCUMENTATION_CHARS = 1_500

    fun parse(result: JsonElement?): LspSignatureHelp? {
        val root = result as? JsonObject ?: return null
        val rawSignatures = root["signatures"] as? JsonArray ?: return null
        if (rawSignatures.isEmpty()) return null
        // Keep the server-selected overload even when the presentation window is bounded. Retain source
        // indexes until parsing finishes: filtering must not select a different overload.
        val requestedSignature = ((root["activeSignature"] as? JsonPrimitive)?.intOrNull ?: 0)
            .takeIf { it in rawSignatures.indices } ?: 0
        val selectedIndexes = if (requestedSignature < MAX_SIGNATURES) {
            rawSignatures.indices.take(MAX_SIGNATURES)
        } else {
            (0 until (MAX_SIGNATURES - 1)).toList() + requestedSignature
        }
        val indexedSignatures = selectedIndexes.mapNotNull { sourceIndex ->
            val obj = rawSignatures[sourceIndex] as? JsonObject ?: return@mapNotNull null
            // Offset labels are UTF-16 ranges in the complete wire label, not its UI preview.
            val label = (obj["label"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val rawParameters = obj["parameters"] as? JsonArray ?: JsonArray(emptyList())
            val localActive = (obj["activeParameter"] as? JsonPrimitive)?.intOrNull
            val requestedParameter = localActive
                ?: (root["activeParameter"] as? JsonPrimitive)?.intOrNull
                ?: 0
            // Resolve against the original list before filtering or bounding presentation. An invalid index
            // defaults to zero; a valid late parameter must not become another one.
            val sourceParameter = requestedParameter.takeIf { it in rawParameters.indices } ?: 0
            val parameterIndexes = if (sourceParameter < MAX_PARAMETERS) {
                rawParameters.indices.take(MAX_PARAMETERS)
            } else {
                (0 until (MAX_PARAMETERS - 1)).toList() + sourceParameter
            }
            val indexedParameters = parameterIndexes.mapNotNull { index ->
                parseParameter(rawParameters[index], label)?.let { index to it }
            }
            val parameters = indexedParameters.map { it.second }
            val mappedParameter = indexedParameters.indexOfFirst { it.first == sourceParameter }
            if (sourceIndex == requestedSignature && rawParameters.isNotEmpty() && mappedParameter < 0) {
                return@mapNotNull null
            }
            val active = mappedParameter.takeIf {
                it >= 0 && (localActive != null || sourceIndex == requestedSignature)
            }
            sourceIndex to LspSignatureInformation(
                label = label,
                documentation = documentation(obj["documentation"]),
                parameters = parameters,
                activeParameter = active,
            )
        }
        val activeSignature = indexedSignatures.indexOfFirst { it.first == requestedSignature }
        if (activeSignature < 0) return null
        val signatures = indexedSignatures.map { it.second }
        val selected = signatures[activeSignature]
        val requestedParameter = selected.activeParameter
            ?: (root["activeParameter"] as? JsonPrimitive)?.intOrNull
            ?: 0
        val activeParameter = if (selected.parameters.isEmpty()) 0
        else requestedParameter.coerceIn(0, selected.parameters.lastIndex)
        return LspSignatureHelp(signatures, activeSignature, activeParameter)
    }

    /** Presentation-only bound; never use this preview to validate or retransmit wire offsets. */
    fun displayLabel(label: String): String {
        if (label.length <= MAX_LABEL_CHARS) return label
        var end = MAX_LABEL_CHARS
        if (Character.isHighSurrogate(label[end - 1]) && Character.isLowSurrogate(label[end])) end--
        return label.substring(0, end)
    }

    fun context(
        kind: LspSignatureTriggerKind,
        triggerCharacter: String?,
        isRetrigger: Boolean,
        previous: LspSignatureHelp?,
    ): JsonObject = buildJsonObject {
        put("triggerKind", kind.wireValue)
        if (kind == LspSignatureTriggerKind.TRIGGER_CHARACTER && !triggerCharacter.isNullOrEmpty()) {
            put("triggerCharacter", triggerCharacter.take(8))
        }
        put("isRetrigger", isRetrigger)
        if (isRetrigger && previous != null) put("activeSignatureHelp", toJson(previous))
    }

    private fun parseParameter(raw: JsonElement, signatureLabel: String): LspSignatureParameter? {
        val obj = raw as? JsonObject ?: return null
        val labelElement = obj["label"] ?: return null
        val documentation = documentation(obj["documentation"])
        if (labelElement is JsonPrimitive && labelElement.isString) {
            val label = labelElement.content.take(500)
            if (label.isEmpty()) return null
            val start = signatureLabel.indexOf(label).takeIf { it >= 0 }
            return LspSignatureParameter(
                label = label,
                documentation = documentation,
                startOffset = start,
                endOffset = start?.plus(label.length),
            )
        }
        val offsets = labelElement as? JsonArray ?: return null
        if (offsets.size != 2) return null
        val start = (offsets[0] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return null
        val end = (offsets[1] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return null
        if (start < 0 || end < start || end > signatureLabel.length) return null
        return LspSignatureParameter(
            label = signatureLabel.substring(start, minOf(end, start + minOf(500, end - start))),
            documentation = documentation,
            startOffset = start,
            endOffset = end,
        )
    }

    private fun documentation(raw: JsonElement?): String? {
        val text = when (raw) {
            is JsonPrimitive -> raw.contentOrNull
            is JsonObject -> (raw["value"] as? JsonPrimitive)?.contentOrNull
            else -> null
        } ?: return null
        return text.trim().take(MAX_DOCUMENTATION_CHARS).takeIf { it.isNotBlank() }
    }

    private fun toJson(help: LspSignatureHelp): JsonObject = buildJsonObject {
        put("signatures", buildJsonArray {
            help.signatures.take(MAX_SIGNATURES).forEach { signature ->
                add(buildJsonObject {
                    put("label", signature.label)
                    signature.documentation?.let { put("documentation", it.take(MAX_DOCUMENTATION_CHARS)) }
                    signature.activeParameter?.let { put("activeParameter", it) }
                    put("parameters", buildJsonArray {
                        signature.parameters.take(MAX_PARAMETERS).forEach { parameter ->
                            add(buildJsonObject {
                                if (parameter.startOffset != null && parameter.endOffset != null) {
                                    put("label", buildJsonArray {
                                        add(parameter.startOffset)
                                        add(parameter.endOffset)
                                    })
                                } else {
                                    put("label", parameter.label.take(500))
                                }
                                parameter.documentation?.let { put("documentation", it.take(MAX_DOCUMENTATION_CHARS)) }
                            })
                        }
                    })
                })
            }
        })
        put("activeSignature", help.activeSignature)
        put("activeParameter", help.activeParameter)
    }
}
