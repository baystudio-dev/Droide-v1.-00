package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class CSharpSdkSelectionTest {
    private fun compatible(text:String)=runCatching { CSharpSdkSelection.requireCompatibleGlobal(text.toByteArray()) }.isSuccess
    @Test fun exactSupportedSubsetOnly() {
        assertTrue(compatible("""{"sdk":{"version":"10.0.402"}}"""))
        assertTrue(compatible("""{"sdk":{"version":"10.0.402","rollForward":"latestMajor","allowPrerelease":false,"paths":["${'$'}host${'$'}"]}}"""))
        for(text in listOf("{}","""{"sdk":{"version":"9.0.100"}}""","""{"sdk":{"version":"10.0.402","paths":["/elsewhere"]}}""",
            """{"sdk":{"version":"10.0.402","allowPrerelease":true}}""","""{"sdk":{"version":"10.0.402","version":"10.0.402"}}""")) assertFalse(text,compatible(text))
    }
    @Test fun deepNonemptyBoundaryFailsClosed() {
        val root=Files.createTempDirectory("csharp-depth").toFile()
        try {var leaf=root;repeat(25){leaf=File(leaf,"d$it").apply{mkdir()}}
            File(leaf,"global.json").writeText("""{"sdk":{"version":"9.0.100"}}""")
            assertTrue(runCatching { CSharpSdkSelection.inspect(root.absolutePath,root.absolutePath,null) }.isFailure)
        } finally {root.deleteRecursively()}
    }
    @Test fun mutationAndExplicitSelectionAreNotOverridden() {
        val root=Files.createTempDirectory("csharp-global").toFile()
        try {val inspection=CSharpSdkSelection.inspect(root.absolutePath,root.absolutePath,null)
            File(root,"global.json").writeText("""{"sdk":{"version":"9.0.100"}}""")
            assertTrue(runCatching { inspection.requireCurrent() }.isFailure)
            assertTrue(runCatching { CSharpSdkSelection.requireExplicitSelections(mapOf("runtime.dotnet" to "9.0.100")) }.isFailure)
        } finally {root.deleteRecursively()}
    }
    @Test fun directSolutionProjectSdkIsGuardedOutsideAnalysisDirectory() {
        val workspace=Files.createTempDirectory("csharp-project-policy").toFile()
        try {
            val root=File(workspace,"selected").apply{mkdir()};val external=File(workspace,"other").apply{mkdir()}
            File(external,"Lib.csproj").writeText("<Project Sdk=\"Microsoft.NET.Sdk\" />")
            File(external,"global.json").writeText("""{"sdk":{"version":"9.0.100"}}""")
            val sln=File(root,"App.sln");sln.writeText("""Microsoft Visual Studio Solution File, Format Version 12.00
Project("{FAE04EC0-301F-11D3-BF4B-00C04F79EFBC}") = "Lib", "../other/Lib.csproj", "{11111111-1111-1111-1111-111111111111}"
EndProject
""")
            assertTrue("Known direct project SDK outside the selected directory must be checked",runCatching { CSharpSdkSelection.inspect(workspace.absolutePath,root.absolutePath,sln.absolutePath) }.isFailure)
        } finally {workspace.deleteRecursively()}
    }

    @Test fun directProjectSymlinkAncestorIsUnsupported() {
        val workspace=Files.createTempDirectory("csharp-project-link").toFile()
        try {
            val root=File(workspace,"selected").apply{mkdir()};val actual=File(workspace,"actual").apply{mkdir()}
            File(actual,"Lib.csproj").writeText("<Project />")
            Files.createSymbolicLink(File(workspace,"alias").toPath(),actual.toPath())
            val sln=File(root,"App.slnx").apply{writeText("<Solution><Project Path=\"../alias/Lib.csproj\" /></Solution>")}
            assertTrue("Symlink ancestor routes must not change the guest project mapping",runCatching { CSharpSdkSelection.inspect(workspace.absolutePath,root.absolutePath,sln.absolutePath) }.isFailure)
        } finally {workspace.deleteRecursively()}
    }

    @Test fun supportedSolutionMetadataBindsDirectProjectChanges() {
        val workspace=Files.createTempDirectory("csharp-solutions").toFile()
        try {
            val root=File(workspace,"selected").apply{mkdir()};val other=File(workspace,"other").apply{mkdir()}
            val project=File(other,"Lib.csproj").apply{writeText("<Project />")}
            val slnx=File(root,"App.slnx").apply{writeText("<Solution><Folder Name=\"/Folder/\"><Project Path=\"../other/Lib.csproj\" /></Folder></Solution>")}
            val xmlInspection=CSharpSdkSelection.inspect(workspace.absolutePath,root.absolutePath,slnx.absolutePath)
            val sln=File(root,"App.sln").apply{writeText("""Microsoft Visual Studio Solution File, Format Version 12.00
Project("{2150E333-8FDC-42A3-9474-1A3956D46DE8}") = "Folder", "Folder", "{22222222-2222-2222-2222-222222222222}"
EndProject
Project("{FAE04EC0-301F-11D3-BF4B-00C04F79EFBC}") = "Lib", "../other/Lib.csproj", "{11111111-1111-1111-1111-111111111111}"
EndProject
""")}
            val textInspection=CSharpSdkSelection.inspect(workspace.absolutePath,root.absolutePath,sln.absolutePath)
            xmlInspection.requireCurrent();textInspection.requireCurrent()
            project.writeText("<Project><PropertyGroup /></Project>")
            assertTrue(runCatching {xmlInspection.requireCurrent()}.isFailure)
            assertTrue(runCatching {textInspection.requireCurrent()}.isFailure)
        } finally {workspace.deleteRecursively()}
    }
    @Test fun solutionExternalEntitiesFailClosed() {
        val root=Files.createTempDirectory("csharp-slnx-dtd").toFile()
        try {
            val slnx=File(root,"App.slnx").apply{writeText("""<!DOCTYPE Solution [<!ENTITY external SYSTEM "file:///unsupported-secret">]><Solution>&external;</Solution>""")}
            assertTrue(runCatching {CSharpSdkSelection.inspect(root.absolutePath,root.absolutePath,slnx.absolutePath)}.isFailure)
        } finally {root.deleteRecursively()}
    }

    @Test fun automationKindDoesNotHideAReferencedProjectSdk() {
        val workspace=Files.createTempDirectory("csharp-project-kind").toFile()
        try {
            val root=File(workspace,"selected").apply{mkdir()};val other=File(workspace,"other").apply{mkdir()}
            File(other,"Lib.csproj").writeText("<Project />")
            File(other,"global.json").writeText("""{"sdk":{"version":"9.0.100"}}""")
            val sln=File(root,"App.sln").apply{writeText("""Microsoft Visual Studio Solution File, Format Version 12.00
Project("{66A26720-8FB5-11D2-AA7E-00C04F688DDE}") = "Lib", "../other/Lib.csproj", "{11111111-1111-1111-1111-111111111111}"
EndProject
""")}
            assertTrue(runCatching {CSharpSdkSelection.inspect(workspace.absolutePath,root.absolutePath,sln.absolutePath)}.isFailure)
        } finally {workspace.deleteRecursively()}
    }

}
