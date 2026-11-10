package com.baystudio.droide.core

import kotlinx.serialization.json.*

// Normalize only schema nodes. Examples, defaults and argument values are opaque data.
internal object ProviderToolSchema {
    private const val MAX_DEPTH = 32
    private const val MAX_NODES = 8_192
    private const val MAX_SCHEMA_CHARS = 256_000
    private val schemaMaps = setOf("properties", "patternProperties", "dependentSchemas")
    private val schemaValues = setOf("items", "additionalItems", "additionalProperties", "not", "if", "then", "else", "contains", "propertyNames", "unevaluatedProperties", "unevaluatedItems", "contentSchema")
    private val schemaArrays = setOf("allOf", "anyOf", "oneOf", "prefixItems")
    private val types = setOf("object", "array", "string", "integer", "number", "boolean", "null")

    fun normalizeRequest(root: JsonObject): JsonObject {
        val raw = root["tools"] ?: return root
        val tools = raw as? JsonArray ?: throw IllegalArgumentException("Provider tools must be an array")
        val names = mutableSetOf<String>()
        val normalized = JsonArray(tools.map { element ->
            val wrapper = element as? JsonObject ?: throw IllegalArgumentException("Provider tool must be an object")
            require(wrapper["type"] == JsonPrimitive("function")) { "Provider tools must be function definitions" }
            val fn = wrapper["function"] as? JsonObject ?: throw IllegalArgumentException("Provider function tool is missing function")
            val name = (fn["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            require(name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Provider function tool has an invalid name: $name" }
            require(names.add(name)) { "Provider function tool name is duplicated: $name" }
            fn["description"]?.let { description ->
                require(description is JsonPrimitive && description.isString) { "Tool '$name' description must be a string" }
            }
            fn["strict"]?.let { strict ->
                require(strict is JsonPrimitive && !strict.isString && strict.booleanOrNull != null) { "Tool '$name' strict must be a boolean" }
            }
            JsonObject(wrapper + ("function" to JsonObject(fn + ("parameters" to parameters(fn["parameters"], name)))))
        })
        return if (normalized == tools) root else JsonObject(root + ("tools" to normalized))
    }

    fun parameters(raw: JsonElement?, toolName: String): JsonObject {
        val schema = when (raw) {
            null -> buildJsonObject { put("type", "object") }
            is JsonObject -> raw
            else -> throw IllegalArgumentException("Tool '$toolName' parameters must be a JSON Schema object")
        }
        require(schema.toString().length <= MAX_SCHEMA_CHARS) { "Tool '$toolName' schema is too large" }
        val normalized = Walker(schema, toolName).node(schema, "parameters", 0, emptySet()) as? JsonObject
            ?: throw IllegalArgumentException("Tool '$toolName' schema at parameters: parameters must describe an object")
        require(normalized.toString().length <= MAX_SCHEMA_CHARS) { "Tool '$toolName' expanded schema is too large" }
        val type = normalized["type"]
        // A singleton type union is identical to the scalar object type. The final rewrite below
        // canonicalizes it without weakening wider unions.
        require(type == null || type == JsonPrimitive("object") ||
            type == JsonArray(listOf(JsonPrimitive("object")))) { "Tool '$toolName' parameters must describe an object" }

        return Walker(normalized, toolName).node(JsonObject(normalized + ("type" to JsonPrimitive("object"))), "parameters", 0, emptySet()) as JsonObject
    }

    fun openAiRequest(body: String, json: Json): String = openAiRequest(body, json, null)

    fun openAiRequest(body: String, json: Json, provider: AiProvider?): String {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw IllegalArgumentException("LLM request body must be a JSON object")
        val normalized = normalizeRequest(root)
        val messages = normalized["messages"] as? JsonArray ?: return normalized.toString()
        val model = (normalized["model"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val cleaned = JsonArray(messages.map { raw ->
            if (raw !is JsonObject) raw else ChatReasoningContinuation.restore(raw, provider, model)
        })
        return JsonObject(normalized + ("messages" to cleaned)).toString()
    }

    private class Walker(private val root: JsonObject, private val tool: String) {
        private val legacyExclusiveBounds = (root["\$schema"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.removeSuffix("#") in setOf("http://json-schema.org/draft-04/schema", "https://json-schema.org/draft-04/schema")
        private var visited = 0
        private fun fail(path: String, reason: String): Nothing =
            throw IllegalArgumentException("Tool '$tool' schema at $path: $reason")

        fun node(raw: JsonElement, path: String, depth: Int, references: Set<String>): JsonElement {
            if (depth > MAX_DEPTH || ++visited > MAX_NODES) fail(path, "schema exceeds compatibility limits")
            if (raw is JsonPrimitive && !raw.isString && raw.booleanOrNull != null) return raw
            val source = raw as? JsonObject ?: fail(path, "expected a schema object or boolean")
            if ("\$dynamicRef" in source || "\$recursiveRef" in source) fail(path, "dynamic or recursive references are not supported; use a finite local reference")
            val ref = source["\$ref"]
            if (ref != null) {
                val pointer = (ref as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail(path, "invalid local reference")
                if (pointer in references) fail(path, "recursive references cannot be expanded; use a finite tool schema")
                val target = resolve(pointer, path)
                val expanded = node(target, path, depth + 1, references + pointer)
                val siblings = source.filterKeys { it != "\$ref" && it != "\$defs" && it != "definitions" }
                if (siblings.isEmpty()) return expanded
                val normalizedSiblings = node(JsonObject(siblings), path, depth + 1, references)
                if (expanded is JsonObject && normalizedSiblings is JsonObject &&
                    expanded.keys.intersect(normalizedSiblings.keys).all { expanded[it] == normalizedSiblings[it] || it in setOf("title", "description", "default") }) {
                    return JsonObject(expanded + normalizedSiblings)
                }
                // Sibling assertions intersect the referenced schema; never discard them.
                return buildJsonObject { put("allOf", JsonArray(listOf(expanded, normalizedSiblings))) }
            }
            val result = linkedMapOf<String, JsonElement>()
            source.forEach { (key, value) ->
                when {
                    key in setOf("\$schema", "\$id", "\$defs", "definitions") -> Unit
                    key in schemaMaps -> {
                        val map = value as? JsonObject ?: fail("$path.$key", "expected an object")
                        result[key] = JsonObject(map.mapValues { (name, child) -> node(child, "$path.$key.$name", depth + 1, references) })
                    }
                    key == "dependencies" -> {
                        val map = value as? JsonObject ?: fail("$path.$key", "expected an object")
                        result[key] = JsonObject(map.mapValues { (name, child) ->
                            if (child is JsonArray) propertyNames(child, "$path.$key.$name")
                            else node(child, "$path.$key.$name", depth + 1, references)
                        })
                    }
                    key == "dependentRequired" -> {
                        val map = value as? JsonObject ?: fail("$path.$key", "expected an object")
                        result[key] = JsonObject(map.mapValues { (name, child) -> propertyNames(child, "$path.$key.$name") })
                    }
                    key in schemaArrays || key == "items" && value is JsonArray -> {
                        val array = value as? JsonArray ?: fail("$path.$key", "expected an array")
                        if (array.isEmpty()) fail("$path.$key", "schema array must not be empty")
                        result[key] = JsonArray(array.mapIndexed { index, child -> node(child, "$path.$key[$index]", depth + 1, references) })
                    }
                    key in schemaValues -> result[key] = node(value, "$path.$key", depth + 1, references)
                    else -> result[key] = value
                }
            }
            validateKeywords(result, path)
            val type = result["type"]
            val names = when (type) {
                null -> emptyList()
                is JsonPrimitive -> listOf(type.takeIf { it.isString }?.content ?: fail(path, "invalid type"))
                is JsonArray -> type.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: fail(path, "invalid type union") }
                else -> fail(path, "invalid type")
            }
            if (names.any { it !in types } || names.distinct().size != names.size || type is JsonArray && names.isEmpty()) fail(path, "invalid type")
            // Nested assertions apply only to their instance type; they do not imply that type.
            val objectLike = "object" in names || "properties" in result
            if (objectLike) {
                val properties = result["properties"] as? JsonObject ?: JsonObject(emptyMap())
                result["properties"] = properties
                if (result["required"] == null || result["required"] is JsonNull) result["required"] = JsonArray(emptyList())
            }
            result["required"]?.let { required ->
                val list = propertyNames(required, "$path.required")
                result["required"] = list
                val properties = result["properties"] as? JsonObject ?: JsonObject(emptyMap())
                val missing = list.map { it.jsonPrimitive.content }.filter { it !in properties }
                // A schema-valued map, pattern or composition may legitimately supply required keys. Adding
                // unconstrained properties would bypass an additionalProperties assertion.
                if (missing.isNotEmpty() && type == JsonPrimitive("object") && result["additionalProperties"] == JsonPrimitive(false) &&
                    (result["patternProperties"] as? JsonObject).isNullOrEmpty()) {
                    fail("$path.required", "required property cannot exist in this closed object")
                }
            }
            if ("array" in names) {
                // An absent items means unconstrained elements, not strings.
                if ("items" !in result) {
                    if (result["unevaluatedItems"] != null && result["unevaluatedItems"] != JsonPrimitive(true)) {
                        fail("$path.items", "declare items explicitly when using unevaluatedItems")
                    }
                    result["items"] = JsonObject(emptyMap())
                }
            }
            return JsonObject(result)
        }

        private fun propertyNames(raw: JsonElement, path: String): JsonArray {
            val list = raw as? JsonArray ?: fail(path, "expected an array of property names")
            val names = list.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: fail(path, "expected property names") }
            if (names.distinct().size != names.size) fail(path, "duplicate property names")
            return list
        }

        private fun validateKeywords(schema: MutableMap<String, JsonElement>, path: String) {
            schema["enum"]?.let { value ->
                val values = value as? JsonArray ?: fail("$path.enum", "expected an array")
                if (values.isEmpty() || values.distinct().size != values.size) fail("$path.enum", "enum must be nonempty and unique")
            }
            for (key in listOf("title", "description", "format", "pattern")) schema[key]?.let { value ->
                if (value !is JsonPrimitive || !value.isString) fail("$path.$key", "expected a string")
            }
            for (key in listOf("uniqueItems", "readOnly", "writeOnly", "deprecated")) schema[key]?.let { value ->
                if (value !is JsonPrimitive || value.isString || value.booleanOrNull == null) fail("$path.$key", "expected a boolean")
            }
            for (key in listOf("minItems", "maxItems", "minLength", "maxLength", "minProperties", "maxProperties", "minContains", "maxContains")) {
                schema[key]?.let { value ->
                    val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBigDecimalOrNull()
                        ?: fail("$path.$key", "expected a nonnegative integer")
                    if (number.signum() < 0 || number.stripTrailingZeros().scale() > 0) fail("$path.$key", "expected a nonnegative integer")
                }
            }
            for (key in listOf("minimum", "maximum", "multipleOf")) schema[key]?.let { value ->
                val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBigDecimalOrNull()
                    ?: fail("$path.$key", "expected a number")
                if (key == "multipleOf" && number.signum() <= 0) fail("$path.$key", "must be positive")
            }
            for ((exclusive, inclusive) in listOf("exclusiveMinimum" to "minimum", "exclusiveMaximum" to "maximum")) {
                val value = schema[exclusive] ?: continue
                val boolean = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                if (legacyExclusiveBounds && boolean != null) {
                    val bound = schema[inclusive] ?: fail("$path.$exclusive", "draft-04 boolean bound requires $inclusive")

                    if (boolean) { schema[exclusive] = bound; schema.remove(inclusive) }
                    else schema.remove(exclusive)
                } else if ((value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBigDecimalOrNull() == null) {
                    fail("$path.$exclusive", "expected a number; declare draft-04 explicitly for legacy boolean bounds")
                }
            }
        }

        private fun decodeFragment(fragment: String, path: String): String = buildString {
            var offset = 0
            while (offset < fragment.length) {
                if (fragment[offset] != '%') {
                    append(fragment[offset++])
                    continue
                }
                val bytes = java.io.ByteArrayOutputStream()
                while (offset < fragment.length && fragment[offset] == '%') {
                    if (offset + 2 >= fragment.length) fail(path, "invalid local reference encoding")
                    val high = fragment[offset + 1].takeIf { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }?.digitToIntOrNull(16)
                    val low = fragment[offset + 2].takeIf { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }?.digitToIntOrNull(16)
                    if (high == null || low == null) fail(path, "invalid local reference encoding")
                    bytes.write(high * 16 + low)
                    offset += 3
                }
                val decoded = try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
                } catch (_: java.nio.charset.CharacterCodingException) {
                    fail(path, "invalid local reference UTF-8")
                }
                append(decoded)
            }
        }

        private fun resolve(pointer: String, path: String): JsonElement {
            if (!pointer.startsWith("#")) fail(path, "only local JSON Pointer references are supported")
            val fragment = decodeFragment(pointer.substring(1), path)
            if (!fragment.startsWith("/")) fail(path, "only local JSON Pointer references are supported")
            var value: JsonElement = root
            fragment.removePrefix("/").split('/').forEach { encoded ->
                val key = encoded.replace("~1", "/").replace("~0", "~")
                value = when (val current = value) {
                    is JsonObject -> current[key]
                    is JsonArray -> key.takeIf { token ->
                        token == "0" || token.isNotEmpty() && token[0] in '1'..'9' && token.all { it in '0'..'9' }
                    }?.toIntOrNull()?.let { current.getOrNull(it) }
                    else -> null
                } ?: fail(path, "unresolved local reference")
            }
            return value
        }
    }
}
