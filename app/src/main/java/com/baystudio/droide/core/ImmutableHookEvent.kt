package com.baystudio.droide.core

import java.io.Closeable
import java.io.OutputStream
import java.util.UUID

/** A sealed, seekable event. No workspace pathname is used as authority. */
internal interface ImmutableHookEvent : Closeable {
    val parentDescriptorPath: String
    fun requireSealed()
}

internal fun interface ImmutableHookEventFactory {
    fun create(write: (OutputStream) -> Unit, takeOwnership: (ImmutableHookEvent) -> Unit, checkCurrent: () -> Unit): ImmutableHookEvent
}

/** FD9 is reserved only in this one supervised hook shell and its descendants.
 * A late child uses its own inherited descriptor, never a reused app-process descriptor.
 */
internal class ImmutableHookInvocation(private val event: ImmutableHookEvent) {
    private val marker = "__DROIDE_SEALED_HOOK_${UUID.randomUUID().toString().replace("-", "")}:"
    val eventPath = "/proc/self/fd/9"

    fun command(handler: String): String {
        event.requireSealed()
        require(event.parentDescriptorPath.matches(Regex("/proc/[1-9][0-9]*/fd/[0-9]+")))
        return "exec 9<${quote(event.parentDescriptorPath)} || exit 126; " +
            "if [ ! -f ${quote(eventPath)} ]; then exit 126; fi; " +
            "( : <${quote(eventPath)} ) || exit 126; " +
            "( eval ${quote(" $handler")} ); __droide_hook_exit=\$?; " +
            "printf '\\n${marker}%s\\n' \"\$__droide_hook_exit\"; exit \"\$__droide_hook_exit\""
    }

    fun completed(result: ExecResult): ExecResult {
        event.requireSealed()
        if (result.timedOut || result.outputIncomplete) throw SourceBackedHookTransportUnavailableException()
        val text = result.output.trimEnd('\r', '\n')
        val lineStart = text.lastIndexOf('\n') + 1
        val receipt = text.substring(lineStart)
        val code = receipt.takeIf { it.startsWith(marker) }?.removePrefix(marker)?.toIntOrNull()
        if (code == null || code !in 0..255 || code != result.exitCode)
            throw SourceBackedHookTransportUnavailableException()
        return result.copy(output = text.substring(0, lineStart).trimEnd('\r', '\n'))
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
