package com.baystudio.droide.core
import android.content.Context
import java.io.File
// Unrelated operation/admission/installer boundaries only. No real package transaction or native binary.
class UnifiedPackageAuthority
internal object ApplicationWorkAdmission {
 @Volatile var isOpen = true
 fun requireOpen(): String { check(isOpen); return "host-test" }
}
class DroideCliTool(authority: UnifiedPackageAuthority, workspaceId: String) {
 suspend fun executeResult(args: List<String>): DroideCliExecutionResult = error("No CLI transaction permitted")
}
object DroideCliClientInstaller {
 fun ensureInstalled(context: Context, checkCancelled: () -> Unit): File { checkCancelled(); return File(context.filesDir, "fixture-only") }
}
