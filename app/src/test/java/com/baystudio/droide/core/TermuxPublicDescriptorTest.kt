package com.baystudio.droide.core

import com.termux.terminal.TerminalSession
import org.junit.Assert.*
import org.junit.Test

/** Source/JVM surface checks only. The obsolete PFD adapter was removed, not made safe by a shadow. */
class TermuxPublicDescriptorTest {
    private fun jniClass(): Class<*> = Class.forName("com.termux.terminal.JNI", false, TerminalSession::class.java.classLoader)

    @Test fun noPublicOrPrivateRawDescriptorAdapterSurvives() {
        assertFalse(TerminalSession::class.java.declaredMethods.any { it.name == "openPtyDescriptors" })
        assertFalse(TerminalSession::class.java.declaredFields.any { it.name == "mTerminalFileDescriptor" })
        val methods = jniClass().declaredMethods
        assertFalse(methods.any { it.name in setOf("createSubprocess", "waitFor", "close", "setPtyWindowSize", "dupOwnedPty") })
        assertFalse(methods.any { method ->
            method.returnType.name in setOf("java.io.FileDescriptor", "android.os.ParcelFileDescriptor") ||
                method.parameterTypes.any { it.name in setOf("java.io.FileDescriptor", "android.os.ParcelFileDescriptor") }
        })
    }

    @Test fun everyIoEntryUsesTheOpaqueLongAndNoArbitraryFd() {
        val methods = jniClass().declaredMethods.associateBy { it.name }
        listOf("readOwnedPtyOnce", "writeOwnedPtyOnce", "pollOwnedPtyOnce", "cancelOwnedPtyIo",
            "releaseOwnedPtyLane", "resizeOwnedPty", "requestOwnedStop", "waitForOwnedProcess", "releaseOwnedProcess").forEach {
            assertEquals(java.lang.Long.TYPE, methods.getValue(it).parameterTypes.first())
        }
        assertEquals(java.lang.Long.TYPE, methods.getValue("reserveOwnedProcess").returnType)
        assertEquals(java.lang.Integer.TYPE, methods.getValue("ownedProcessApiVersion").returnType)
    }

    @Test fun inputAndProcessFailuresHaveExplicitReceipts() {
        assertEquals(java.lang.Integer.TYPE, TerminalSession::class.java.getMethod("getInputWriteFailureReason").returnType)
        assertEquals(java.lang.Integer.TYPE, TerminalSession::class.java.getMethod("getProcessExitFailureReason").returnType)
        assertTrue(TerminalSession.InputHandler::class.java.getMethod("onPtyWriteFailure", Int::class.javaPrimitiveType).isDefault)
    }
}
