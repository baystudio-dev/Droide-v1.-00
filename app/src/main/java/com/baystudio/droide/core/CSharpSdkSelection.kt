package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.serialization.json.*

/** A finite compatibility guard; does not claim to sandbox dynamic imports or future project subprocesses. */
data class CSharpSelectionInspection internal constructor(
    val workspaceRoot: String, val rootPath: String, val solutionPath: String?,
    internal val observations: Map<String, String>,
) {
    val rootGuestAbsolute: String get() = rootPath
    val solutionGuestAbsolute: String? get() = solutionPath
    val environmentIdentity: String get() = "local-linux-arm64-csharp-private-v1"
    val mappingRevision: Int get() = 1
    val identity: String get() = LspCSharpLaunchPolicy.sha((listOf(workspaceRoot, rootPath, solutionPath ?: "directory-discovery",
        environmentIdentity, mappingRevision.toString()) + observations.toSortedMap().map { "${it.key}\u0000${it.value}" })
        .joinToString("\n").toByteArray())
    fun requireCurrent() { check(CSharpSdkSelection.inspect(workspaceRoot, rootPath, solutionPath) == this) { "C# analysis selection or known SDK policy changed" } }
}

internal object CSharpSdkSelection {
    private val rollForward = setOf("disable", "patch", "feature", "minor", "major", "latestPatch", "latestFeature", "latestMinor", "latestMajor")
    fun requireCompatibleGlobal(bytes: ByteArray) {
        val root = CSharpControlRecords.strict(bytes)
        require(root.keys == setOf("sdk")) { "Unsupported global.json fields for this managed profile" }
        val sdk = root.getValue("sdk").jsonObject
        require(sdk.keys.all { it in setOf("version", "rollForward", "allowPrerelease", "paths") })
        require(sdk["version"] == JsonPrimitive("10.0.402")) { "This managed profile does not support that SDK" }
        sdk["rollForward"]?.let { require(it is JsonPrimitive && it.isString && it.content in rollForward) }
        sdk["allowPrerelease"]?.let { require(it == JsonPrimitive(false)) }
        sdk["paths"]?.let { require(it == JsonArray(listOf(JsonPrimitive("\$host\$")))) }
    }
    fun requireExplicitSelections(selections: Map<String, String>) {
        for (key in listOf("runtime.csharp-sdk", "runtime.dotnet", "toolchain.dotnet", "tool.dotnet", "dotnet")) {
            selections[key]?.let { require(it == "10.0.402") { "This managed profile does not support that explicitly selected SDK" } }
        }
    }
    private fun canonicalDirectory(file: File) {
        require(file.isAbsolute && file.canonicalPath == file.absolutePath && Files.isDirectory(file.toPath(), NOFOLLOW_LINKS))
        var at: File? = file
        while (at != null) { require(!Files.isSymbolicLink(at.toPath())); at = at.parentFile }
    }
    fun inspect(workspaceRoot: String, rootPath: String, solutionPath: String?): CSharpSelectionInspection {
        val workspace = File(workspaceRoot); val root = File(rootPath)
        canonicalDirectory(workspace); canonicalDirectory(root)
        require(root.toPath().startsWith(workspace.toPath())) { "C# analysis root escaped workspace" }
        val observations = linkedMapOf<String, String>()
        fun ancestorPolicy(start: File) {
            var at: File? = start; var count = 0; var found = false
            while (at != null) {
                require(++count <= 128); val file = File(at, "global.json")
                if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                    val bytes = CSharpControlRecords.readBytes(file, 65_536)
                    if (!found) { requireCompatibleGlobal(bytes); found = true }
                    observations[file.absolutePath] = LspCSharpLaunchPolicy.sha(bytes)
                } else observations[file.absolutePath] = "absent"
                at = at.parentFile
            }
        }
        observations[root.absolutePath + "/."] = directoryStamp(root)
        ancestorPolicy(root)
        if (solutionPath != null) {
            val solution = File(solutionPath)
            require(solution.isAbsolute && solution.canonicalPath == solution.absolutePath && solution.toPath().startsWith(root.toPath()))
            require(solution.extension.lowercase() in setOf("sln", "slnx")) { "Select an existing .sln or .slnx" }
            canonicalDirectory(requireNotNull(solution.parentFile))
            val solutionBytes=CSharpControlRecords.readBytes(solution,4_194_304)
            observations[solution.absolutePath] = LspCSharpLaunchPolicy.sha(solutionBytes)
            ancestorPolicy(solution.parentFile)
            var projectBytes=0L
            for(relative in directProjects(solution,solutionBytes)) {
                require(!relative.contains('\u0000') && !relative.matches(Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*")))
                val candidate=File(relative.replace('\\','/')).let { if(it.isAbsolute)it else File(solution.parentFile,it.path) }
                val normalized=candidate.toPath().toAbsolutePath().normalize().toFile()
                val project=candidate.canonicalFile
                require(project==normalized) { "Direct solution project paths must not cross symbolic links" }
                // Referenced projects outside the identity-bound workspace are not silently inspected in a different guest view.
                require(project.toPath().startsWith(workspace.toPath())) { "A direct solution project is outside the supported workspace mapping" }
                canonicalDirectory(requireNotNull(project.parentFile))
                require(project.extension.lowercase()=="csproj") { "Unsupported direct solution project type" }
                require(!Files.isSymbolicLink(candidate.toPath()))
                val bytes=CSharpControlRecords.readBytes(project,4_194_304)
                projectBytes+=bytes.size;require(projectBytes<=33_554_432) { "Direct project metadata bound" }
                observations[project.absolutePath]=LspCSharpLaunchPolicy.sha(bytes)
                ancestorPolicy(project.parentFile)
            }
        }
        // Bounded metadata scan finds nested known project SDK policies without evaluating project files or running discovery.
        var visited = 0
        Files.walk(root.toPath(), 24).use { paths -> paths.forEach { p ->
            require(++visited <= 32_768) { "C# metadata scan bound exceeded" }
            require(!Files.isSymbolicLink(p)) { "C# metadata scan does not follow symbolic links" }
            if (root.toPath().relativize(p).nameCount >= 24 && Files.isDirectory(p, NOFOLLOW_LINKS)) {
                Files.newDirectoryStream(p).use { children ->
                    require(!children.iterator().hasNext()) { "C# metadata depth bound leaves an uninspected subtree" }
                }
            }
            if (p.fileName.toString() == "global.json") {
                val bytes = CSharpControlRecords.readBytes(p.toFile(), 65_536)
                requireCompatibleGlobal(bytes); observations[p.toString()] = LspCSharpLaunchPolicy.sha(bytes)
            }
        } }
        return CSharpSelectionInspection(workspaceRoot, rootPath, solutionPath, observations.toMap())
    }
    /** Reads direct solution membership as data; never evaluates MSBuild imports or conditions. */
    private fun directProjects(solution:File,bytes:ByteArray):List<String> {
        val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        val text=decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        val projects=if(solution.extension.lowercase()=="sln") {
            require(text.lineSequence().any { it.trim()=="Microsoft Visual Studio Solution File, Format Version 12.00" }) { "Unsupported solution format" }
            val pattern=Regex("""^Project\("\{([0-9A-Fa-f-]{36})\}"\)\s*=\s*"[^"]*",\s*"([^"]+)",\s*"\{[0-9A-Fa-f-]{36}\}"\s*$""")
            text.lineSequence().map(String::trim).filter { it.startsWith("Project(") }.mapNotNull { line ->
                val match=requireNotNull(pattern.matchEntire(line)) { "Unsupported solution project record" }
                // Serialized MSBuild solution-folder kind; automation API kinds do not grant a project exemption.
                if(match.groupValues[1].equals("2150E333-8FDC-42A3-9474-1A3956D46DE8",ignoreCase=true))null else match.groupValues[2]
            }.toList()
        } else {
            val factory=javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware=false;factory.isXIncludeAware=false;factory.isExpandEntityReferences=false
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false)
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false)
            val builder=factory.newDocumentBuilder()
            builder.setEntityResolver { _,_ -> throw IllegalArgumentException("External solution entity is unsupported") }
            val document=builder.parse(java.io.ByteArrayInputStream(bytes))
            require(document.documentElement.tagName=="Solution")
            val entries=document.getElementsByTagName("Project");require(entries.length<=256)
            (0 until entries.length).map { index ->
                val element=entries.item(index) as org.w3c.dom.Element
                element.getAttribute("Path").also { require(it.isNotBlank()) }
            }
        }
        require(projects.size<=256 && projects.distinct().size==projects.size)
        return projects
    }
    private fun directoryStamp(file: File): String {
        val attributes = Files.readAttributes(file.toPath(), "unix:dev,ino", NOFOLLOW_LINKS)
        return "${attributes["dev"]}:${attributes["ino"]}"
    }
    /** Guest ancestors outside the identity-bound workspace may differ from the host's ancestors. */
    fun requireGuestAncestors(guestRoot: File, workspaceRoot: String) {
        var ancestor = File(workspaceRoot).parentFile; var count = 0
        while (ancestor != null) {
            require(++count <= 128)
            val file = File(guestRoot, ancestor.absolutePath.removePrefix("/") + "/global.json")
            if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) requireCompatibleGlobal(CSharpControlRecords.readBytes(file, 65_536))
            ancestor = ancestor.parentFile
        }
    }
}
