package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Exact managed PSES profile. Custom commands never inherit these host/adapter guarantees. */
internal object LspPowerShellProfile {
    const val ID = "powershell-editor-services"
    val extensions = setOf("ps1", "psm1", "psd1")
    const val IDENTITY = "pses-4.7.0-pwsh-7.6.7-managed-pipe-consented-profiles-v1"
    const val ROOT_POLICY = "nearest-real-vcs-then-workspace-with-absent-parent-barrier-v1"
    const val READINESS_POLICY = "registered-90s-60s-initialize-15s-replay-current-buffers-v1"
    fun supportsPath(path: String): Boolean = path.replace('\\', '/').substringAfterLast('/').substringAfterLast('.', "") in extensions
    fun initializationOptions(workingDirectory: String): JsonObject = buildJsonObject {
        put("enableProfileLoading", true)
        put("initialWorkingDirectory", workingDirectory)
    }
    fun clientCapabilities(base: JsonObject): JsonObject {
        val workspace = JsonObject(base.getValue("workspace").jsonObject - "didChangeWatchedFiles" + mapOf(
            "configuration" to JsonPrimitive(true),
            "didChangeConfiguration" to buildJsonObject { put("dynamicRegistration", false) }))
        val documents = base.getValue("textDocument").jsonObject
        val actions = (documents["codeAction"] as? JsonObject)?.let { JsonObject(it - "resolveSupport") }
        val semantic = (documents["semanticTokens"] as? JsonObject)?.let { JsonObject(it + ("multilineTokenSupport" to JsonPrimitive(true))) }
        val revised = JsonObject(documents + buildMap {
            actions?.let { put("codeAction", it) }; semantic?.let { put("semanticTokens", it) }
        })
        return JsonObject(base + ("workspace" to workspace) + ("textDocument" to revised))
    }
    fun requireInitializeContract(result: JsonElement) {
        val capabilities = (result as? JsonObject)?.get("capabilities") as? JsonObject
            ?: error("PowerShell returned no capability object")
        (capabilities["semanticTokensProvider"] as? JsonObject)?.let { provider ->
            val legend = provider["legend"] as? JsonObject ?: error("PowerShell semantic legend is missing")
            for ((name, maximum) in listOf("tokenTypes" to 128, "tokenModifiers" to 31)) {
                val values = legend[name] as? JsonArray ?: error("PowerShell semantic legend is malformed")
                require(values.size <= maximum && values.all { (it as? JsonPrimitive)?.let { p ->
                    p.isString && p.content.isNotBlank() && p.content.length <= 64 } == true } && values.distinct().size == values.size) {
                    "PowerShell semantic legend cannot be normalized without changing native indices"
                }
            }
        }
        val sync = capabilities["textDocumentSync"] as? JsonObject
            ?: error("PowerShell requires its reviewed object synchronization contract")
        check(sync["openClose"] == JsonPrimitive(true) && sync["change"] == JsonPrimitive(2) &&
            (sync["save"] as? JsonObject)?.get("includeText") == JsonPrimitive(true)) {
            "PowerShell synchronization contract is incompatible with the reviewed profile"
        }
    }
    fun configurationReply(params: JsonElement?, ownsScope: (String?) -> Boolean): JsonArray {
        val objectValue = params as? JsonObject ?: error("Invalid PowerShell configuration request")
        val items = objectValue["items"] as? JsonArray ?: error("Invalid PowerShell configuration items")
        require(items.size <= 64) { "PowerShell configuration request exceeds its budget" }
        return JsonArray(items.map { value ->
            val item = value as? JsonObject ?: error("Invalid PowerShell configuration item")
            val sectionValue = item["section"]
            val section = if (sectionValue == null || sectionValue == JsonNull) null else
                (sectionValue as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= 128 }?.content
                    ?: error("Invalid PowerShell configuration section")
            val scopeValue = item["scopeUri"]
            val uri = if (scopeValue == null || scopeValue == JsonNull) null else
                (scopeValue as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= 8192 }?.content
                    ?: error("Invalid PowerShell configuration scope")
            if (section == "powershell.rename" && ownsScope(uri)) buildJsonObject {
                put("acceptDisclaimer", true); put("createParameterAlias", false)
            } else JsonNull
        })
    }
    /** Valid native prompts are always cancelled. Malformed shapes throw promptly (-32603). */
    fun cancelPrompt(method: String, params: JsonElement?): JsonObject {
        val value = params as? JsonObject ?: error("Invalid PowerShell prompt")
        var total = 0
        fun text(obj: JsonObject, key: String, required: Boolean = true): String? {
            val item = obj[key]
            if (!required && (item == null || item == JsonNull)) return null
            val s = (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid PowerShell prompt field")
            require(s.length <= 8192) { "PowerShell prompt field exceeds its budget" }
            total += s.length; require(total <= 65_536) { "PowerShell prompt exceeds its budget" }; return s
        }
        if (method == "powerShell/showInputPrompt") {
            require(value.keys.all { it in setOf("name", "label") }) { "Unsupported PowerShell input prompt shape" }
            text(value, "name"); text(value, "label", false)
        } else {
            require(method == "powerShell/showChoicePrompt" && value.keys.all {
                it in setOf("isMultiChoice", "caption", "message", "choices", "defaultChoices")
            }) { "Unsupported PowerShell choice prompt shape" }
            require(value["isMultiChoice"] == JsonPrimitive(true) || value["isMultiChoice"] == JsonPrimitive(false)) { "Invalid PowerShell choice mode" }
            text(value, "caption"); text(value, "message")
            val choices = value["choices"] as? JsonArray ?: error("Invalid PowerShell choices")
            require(choices.size <= 128) { "PowerShell choices exceed their budget" }
            choices.forEach { element ->
                val choice = element as? JsonObject ?: error("Invalid PowerShell choice")
                require(choice.keys.all { it in setOf("label", "helpMessage", "hotKeyIndex", "hotKeyCharacter") }) { "Unsupported PowerShell choice shape" }
                text(choice, "label"); text(choice, "helpMessage", false)
                choice["hotKeyIndex"]?.takeUnless { it == JsonNull }?.let {
                    require((it as? JsonPrimitive)?.let { p -> !p.isString && p.intOrNull != null } == true) { "Invalid PowerShell hot key index" }
                }
                text(choice, "hotKeyCharacter", false)?.let { require(it.length == 1) { "Invalid PowerShell hot key" } }
            }
            value["defaultChoices"]?.takeUnless { it == JsonNull }?.let { element ->
                val defaults = element as? JsonArray ?: error("Invalid PowerShell default choices")
                require(defaults.size <= 128 && defaults.all { item ->
                    (item as? JsonPrimitive)?.let { !it.isString && it.intOrNull in choices.indices } == true
                }) { "Invalid PowerShell default choice index" }
            }
        }
        return buildJsonObject { put("promptCancelled", true); put("responseText", JsonNull) }
    }
    const val INSTALLATION_DISCLOSURE = "Enabling analysis does not install packages. Install the exact reviewed PowerShell runtime and Editor Services package separately. This grant does not authorize database migrations or deployment."
    /** Check the result envelope, leaving bounded item filtering to the existing parser. */
    fun acceptsCompletionEnvelope(result: JsonElement): Boolean = when (result) {
        JsonNull -> true
        is JsonArray -> true
        is JsonObject -> result["items"] is JsonArray &&
            (!result.containsKey("isIncomplete") ||
                (result["isIncomplete"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != null)
        else -> false
    }

    const val CUSTOM_APPROVAL_EXPLANATION = "This custom PowerShell command may load profiles, modules, live completers or analyzer rules and execute project/user code. It may read or change files, access the network and use guest HOME/configuration/credential files, including outside this project. This is ongoing trust in the exact custom command, not a sandbox. Its profile selection, native prompt behavior, artifact provenance and local logging are unverified. The built-in reviewed host, prompt cancellation, readiness, rename acknowledgement and client adapters do not apply. Review the exact command, its prompt behavior and origin before enabling it."
    const val APPROVAL_EXPLANATION = "PowerShell analysis loads the four system/user PowerShell profiles for the Droide host and can run live completers, module discovery, help and custom analyzer rules. Profiles and modules can read or change files, access the network and use guest HOME/configuration/credential files, including outside this project. This is ongoing trust in that code, not a sandbox. Known IDE writes and discarded buffers restart the server on later demand and can run profiles again. Native standard prompts are denied by the reviewed noninteractive host; IDE prompt callbacks are cancelled. Arbitrary trusted code may bypass host prompts or catch their errors. Private local session logs and temporary state can contain project information; cleanup is bounded and residue can remain after hard termination. Rename is best-effort and single-file: preview every edit; scoped/native rename may miss or change unintended references. No IDE command execution, REPL or debugging is added."
    const val FEATURE_NOTE = "Ready means initialization and exact current-buffer replay, not asynchronous index, analyzer, profile or module success. Push diagnostics are unversioned and may lag; empty results do not certify validity. Rename is best-effort/single-file and requires edit review. Completion resolution, command-backed actions, prepare-rename, code lenses, folding/highlight UI, REPL and debugging are not integrated. IDE mutations and owned buffer closes retire the index; external changes require Restart/reopen. Cancelled UI waits may leave bounded native requests running under this grant; native deadlines require explicit Retry. Managed launch proof covers host-shell creation and observed lifecycle, not hostile same-UID changes, later lazy loads or all escaped descendants."
}
