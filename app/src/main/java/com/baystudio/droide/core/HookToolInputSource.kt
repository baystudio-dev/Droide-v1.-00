package com.baystudio.droide.core

import java.io.OutputStream

/** Construction is internal, from completed validated TOOL_ARGUMENTS only. */

class HookToolInputSource private constructor(
    private val session: OwnedResponseContentStore.Session,
    private val reference: OwnedNormalizedResponse.Reference,
    private val checkOwner: () -> Unit,
) {
    internal fun writeJsonString(output: OutputStream) {
        checkOwner()
        reference.verify(session)
        session.openBytes(reference.handle).use { input ->
            OwnedJsonStringWriter.writeValidatedUtf8(input, output, checkOwner)
        }
        checkOwner()
    }
    companion object {
        internal fun admit(session: OwnedResponseContentStore.Session,
                           handle: OwnedResponseContentStore.Handle,
                           checkOwner: () -> Unit): HookToolInputSource {
            checkOwner()
            val ref = OwnedNormalizedResponse.Reference.complete(session, handle, OwnedResponseContentStore.Kind.TOOL_ARGUMENTS)
            session.openBytes(handle).use { StreamedJsonValidator.requireObject(it) }
            checkOwner()
            return HookToolInputSource(session, ref, checkOwner)
        }
    }
    override fun toString() = "HookToolInputSource(owned=true, content=redacted)"
}
