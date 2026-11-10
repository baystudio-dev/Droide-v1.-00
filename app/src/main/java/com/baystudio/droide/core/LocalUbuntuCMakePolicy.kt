package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlinx.coroutines.ensureActive

/** Finite publisher closure. This is not a Python resolver or a general wheel installer. */
data class LocalUbuntuCMakeWheel(
    val name: String, val version: String, val filename: String, val url: String,
    val sha256: String, val bytes: Long, val metadataSha256: String,
    val requiresPython: String, val requiresDist: List<String>,
) {
    val distInfo: String get() = "${name.replace('-', '_')}-$version.dist-info"
    fun artifact() = TrustedArtifactSpec("cmake-$name-$version", url, sha256, filename, bytes, bytes)
}
data class LocalUbuntuCMakeRecipe(val familyId: String, val version: String, val command: String,
    val wheels: List<LocalUbuntuCMakeWheel>)

object LocalUbuntuCMakeCatalog {
    val recipe = LocalUbuntuCMakeRecipe("lsp.cmake-language-server", "0.1.11", "cmake-language-server", listOf(
        LocalUbuntuCMakeWheel("cmake-language-server", "0.1.11", "cmake_language_server-0.1.11-py3-none-any.whl", "https://files.pythonhosted.org/packages/60/b8/0ea1c0b86b8c6ca6c2dd5292762f282e779d6631c0641d043123bf8be164/cmake_language_server-0.1.11-py3-none-any.whl", "74ce1fa93a3db258776d881935172a87124ed621298404ac42f960eab6be8c04", 9619L, "d5d2c1cf2e306da7d91082e7dea2c7b36dbe7a4ab9cf9530315d8029b9070dc1", "<3.14,>=3.8.0",
            listOf("pygls>=1.1.1")),
        LocalUbuntuCMakeWheel("pygls", "1.3.1", "pygls-1.3.1-py3-none-any.whl", "https://files.pythonhosted.org/packages/11/19/b74a10dd24548e96e8c80226cbacb28b021bc3a168a7d2709fb0d0185348/pygls-1.3.1-py3-none-any.whl", "6e00f11efc56321bdeb6eac04f6d86131f654c7d49124344a9ebb968da3dd91e", 56031L, "659997cf9d4993b4d3b563b584b7d2590ca7bfaac308cefdf185956e5a02a879", ">=3.8",
            listOf("cattrs>=23.1.2", "lsprotocol==2023.0.1", "websockets>=11.0.3; extra == \"ws\"")),
        LocalUbuntuCMakeWheel("lsprotocol", "2023.0.1", "lsprotocol-2023.0.1-py3-none-any.whl", "https://files.pythonhosted.org/packages/8d/37/2351e48cb3309673492d3a8c59d407b75fb6630e560eb27ecd4da03adc9a/lsprotocol-2023.0.1-py3-none-any.whl", "c75223c9e4af2f24272b14c6375787438279369236cd568f596d4951052a60f2", 70826L, "a21eccfd5d27097fa5c7c3028a4e73d3f27c5b277b02926d765df4c16b384dbf", ">=3.7",
            listOf("attrs>=21.3.0", "cattrs!=23.2.1")),
        LocalUbuntuCMakeWheel("cattrs", "23.2.3", "cattrs-23.2.3-py3-none-any.whl", "https://files.pythonhosted.org/packages/b3/0d/cd4a4071c7f38385dc5ba91286723b4d1090b87815db48216212c6c6c30e/cattrs-23.2.3-py3-none-any.whl", "0341994d94971052e9ee70662542699a3162ea1e0c62f7ce1b4a57f563685108", 57474L, "62dbc00fc2cd17a6cf1d5455b5ccb3361909ee4e21a71f31028ac364bc974343", ">=3.8",
            listOf("attrs>=23.1.0", "exceptiongroup>=1.1.1; python_version < '3.11'", "typing-extensions!=4.6.3,>=4.1.0; python_version < '3.11'", "pymongo>=4.4.0; extra == 'bson'", "cbor2>=5.4.6; extra == 'cbor2'", "msgpack>=1.0.5; extra == 'msgpack'", "orjson>=3.9.2; implementation_name == 'cpython' and extra == 'orjson'", "pyyaml>=6.0; extra == 'pyyaml'", "tomlkit>=0.11.8; extra == 'tomlkit'", "ujson>=5.7.0; extra == 'ujson'")),
        LocalUbuntuCMakeWheel("attrs", "23.2.0", "attrs-23.2.0-py3-none-any.whl", "https://files.pythonhosted.org/packages/e0/44/827b2a91a5816512fcaf3cc4ebc465ccd5d598c45cefa6703fcf4a79018f/attrs-23.2.0-py3-none-any.whl", "99b87a485a5820b23b879f04c2305b44b951b502fd64be915879d77a7e8fc6f1", 60752L, "5b0bc6ece1f22a310fa72154642098b759f413f09ca9d45bedb96218475c9be0", ">=3.7",
            listOf("importlib-metadata; python_version < '3.8'", "attrs[tests]; extra == 'cov'", "coverage[toml]>=5.3; extra == 'cov'", "attrs[tests]; extra == 'dev'", "pre-commit; extra == 'dev'", "furo; extra == 'docs'", "myst-parser; extra == 'docs'", "sphinx; extra == 'docs'", "sphinx-notfound-page; extra == 'docs'", "sphinxcontrib-towncrier; extra == 'docs'", "towncrier; extra == 'docs'", "zope-interface; extra == 'docs'", "attrs[tests-no-zope]; extra == 'tests'", "zope-interface; extra == 'tests'", "mypy>=1.6; (platform_python_implementation == 'CPython' and python_version >= '3.8') and extra == 'tests-mypy'", "pytest-mypy-plugins; (platform_python_implementation == 'CPython' and python_version >= '3.8') and extra == 'tests-mypy'", "attrs[tests-mypy]; extra == 'tests-no-zope'", "cloudpickle; (platform_python_implementation == 'CPython') and extra == 'tests-no-zope'", "hypothesis; extra == 'tests-no-zope'", "pympler; extra == 'tests-no-zope'", "pytest-xdist[psutil]; extra == 'tests-no-zope'", "pytest>=4.3.0; extra == 'tests-no-zope'")),
    ))
    fun find(familyId: String, version: String? = null): LocalUbuntuCMakeRecipe? = recipe.takeIf {
        it.familyId == familyId && (version == null || it.version == version)
    }
}

internal object LocalUbuntuCMakePolicy {
    const val BOOTSTRAP_SHA256 = "450743c0edb7aaa554f675b3593270c864f2cb02cb430c80c911d0655ea14767"
    const val FAMILY = "lsp.cmake-language-server"
    const val VERSION = "0.1.11"
    const val COMMAND = "cmake-language-server"
    const val REVISION = "cmake-purelib-0.1.11-cpython312-v1"
    const val PYTHON = "/usr/bin/python3.12"
    const val CMAKE = "/usr/bin/cmake"
    const val BOOTSTRAP = "payload/cmake-bootstrap.py"
    const val SUPERVISOR = "payload/cmake-supervisor.py"
    const val HEALTH = "payload/cmake-lsp-health.py"
    const val PURELIB = "payload/purelib"
    const val AUTHORITY = "CMAKE_LAUNCH_AUTHORITY.json"
    const val RUNTIME = "CMAKE_RUNTIME.json"
    const val LOCK = "CMAKE_RECIPE_LOCK.json"
    const val SEAL = "CMAKE_TREE_SEAL.json"
    const val PROVENANCE = "https://github.com/regen100/cmake-language-server/tree/v0.1.11"
    val packages = listOf("python3", "cmake")
    fun requireReviewed(recipe: LocalUbuntuCMakeRecipe) {
        require(recipe == LocalUbuntuCMakeCatalog.recipe) { "Only the exact reviewed five-wheel CMake 0.1.11 closure is supported" }
    }
    val lock: JsonObject by lazy { buildJsonObject {
        put("schema", 1); put("family", FAMILY); put("version", VERSION); put("policy", REVISION)
        put("python", PYTHON); put("unicode", "15.0.0"); put("cmake", CMAKE); put("cmakeMajorMinor", "3.28")
        put("wheels", buildJsonArray { LocalUbuntuCMakeCatalog.recipe.wheels.forEach { w -> add(buildJsonObject {
            put("name", w.name); put("version", w.version); put("filename", w.filename); put("url", w.url)
            put("sha256", w.sha256); put("bytes", w.bytes); put("metadataSha256", w.metadataSha256)
            put("requiresPython", w.requiresPython); put("requiresDist", JsonArray(w.requiresDist.map(::JsonPrimitive)))
        }) } })
    } }
    val recipeSha256: String get() = hash(lock.toString().toByteArray())
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    fun requireAbsolute(value: String) {
        require(value.startsWith('/') && value.length in 2..4096 && value.none { it.code < 32 || it.code == 127 || it == '\\' } &&
            value.drop(1).split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe CMake absolute path" }
    }
    /** Bounded no-follow snapshots; observable replacements invalidate the proof. Not a same-UID sandbox. */
    fun read(file: File, limit: Long = 16_777_216, checkCancelled: () -> Unit = {}): ByteArray {
        checkCancelled(); require(limit in 1..67_108_864)
        val nativeBefore = requireNotNull(CMakeAdmissionMetadata.stamp(file));require(nativeBefore.nlink==1L)
        val a = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(a.isRegularFile && a.fileKey() != null && a.size() in 0..limit && !PathSecurity.isSymbolicLink(file))
        var parent = file.parentFile
        while (parent != null) {
            val p = Files.readAttributes(parent.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(p.isDirectory && !p.isSymbolicLink); parent = parent.parentFile
        }
        val bytes = Files.newByteChannel(file.toPath(), setOf(java.nio.file.StandardOpenOption.READ, NOFOLLOW_LINKS)).use { ch ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteBuffer.allocate(65_536)
            while (true) { checkCancelled(); buffer.clear(); val n = ch.read(buffer); if (n < 0) break
                require(n > 0 && out.size().toLong() + n <= limit && out.size().toLong() + n <= a.size())
                out.write(buffer.array(), 0, n)
            }; out.toByteArray()
        }
        val b = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(b.isRegularFile && a.fileKey() == b.fileKey() && a.size() == bytes.size.toLong() && a.size() == b.size() && a.lastModifiedTime() == b.lastModifiedTime())
        require(CMakeAdmissionMetadata.stamp(file)==nativeBefore)
        return bytes
    }
    fun canonical(value: JsonElement): String = when(value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",","{","}") { (k,v)->JsonPrimitive(k).toString()+":"+canonical(v) }
        is JsonArray -> value.joinToString(",","[","]",transform=::canonical)
        else -> value.toString()
    }
    fun text(file: File, limit: Long = 4_194_304, checkCancelled: () -> Unit = {}): String = utf8(read(file,limit,checkCancelled))
    fun json(file: File, limit: Long = 4_194_304, checkCancelled: () -> Unit = {}): JsonObject = strictObject(text(file,limit,checkCancelled))
    fun str(obj: JsonObject, key: String): String = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Missing CMake $key")
    fun sha(file: File, limit: Long = 16_777_216, checkCancelled: () -> Unit = {}): String = hash(read(file,limit,checkCancelled))
    fun tree(root: File, checkCancelled: () -> Unit = {}): JsonObject {
        val files=linkedMapOf<String,JsonElement>();val dirs=mutableListOf<String>();var total=0L;var count=0
        fun walk(directory: File, relative: String, depth: Int) {
            checkCancelled();require(depth<=26 && Files.isDirectory(directory.toPath(),NOFOLLOW_LINKS))
            for(file in requireNotNull(directory.listFiles()).sortedBy { it.name }) {
                val name=if(relative.isEmpty()) file.name else "$relative/${file.name}"
                if(name==SEAL) continue
                require(++count<=8300 && file.name.none { it.code<32 || it.code==127 || it=='\\' })
                val a=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
                if(a.isDirectory){dirs+=name;walk(file,name,depth+1)} else {
                    val bytes=read(file,16_777_216,checkCancelled);total+=bytes.size;require(total<=80_000_000)
                    files[name]=JsonPrimitive(hash(bytes))
                }
            }
        }
        walk(root,"",0)
        return buildJsonObject { put("schema",1);put("files",JsonObject(files));put("directories",JsonArray(dirs.sorted().map(::JsonPrimitive))) }
    }
    fun authority(activation: String,stageGuest: String,finalGuest: String,runtimeSha: String,
        purelibFiles: Map<String,String>): JsonObject {
        requireAbsolute(stageGuest);requireAbsolute(finalGuest)
        require(activation.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) && stageGuest!=finalGuest && runtimeSha.matches(Regex("[0-9a-f]{64}")))
        return buildJsonObject {
            put("schema",1);put("family",FAMILY);put("version",VERSION);put("policy",REVISION);put("activationId",activation)
            put("stageGuestRoot",stageGuest);put("finalGuestRoot",finalGuest);put("recipeSha256",recipeSha256)
            put("runtimeSha256",runtimeSha);put("bootstrapSha256",BOOTSTRAP_SHA256)
            put("supervisorSha256",LocalUbuntuCMakeLaunchPolicy.SUPERVISOR_SHA256);put("healthSha256",LocalUbuntuCMakeLspHealth.sha256)
            put("purelibFiles",JsonObject(purelibFiles.mapValues { JsonPrimitive(it.value) }))
        }
    }
    fun guestWrapper(guest: String): String {
        requireAbsolute(guest)
        // A terminal may explicitly request the lifecycle probe, but cannot bypass the typed owner with
        // a normal managed launch.
        return "#!/bin/sh\nset -eu\n[ \"${'$'}#\" -eq 1 ] && [ \"${'$'}1\" = --droide-health ] || { printf '%s\\n' 'Use the CMake analysis selection and Enable analysis in Droide.' >&2; exit 64; }\n" + LocalUbuntuCMakeLspHealth.handoff(guest)
    }
    fun hostWrapper(launcher: String, guest: String): String {
        requireAbsolute(launcher);requireAbsolute(guest)
        return "#!/system/bin/sh\nset -eu\nexec /system/bin/sh ${LocalExecutionSubstrate.shellQuote(launcher)} /bin/sh ${LocalExecutionSubstrate.shellQuote("$guest/payload/bin/$COMMAND")} \"${'$'}@\"\n"
    }
    private fun directoryNoFollow(directory: File) {
        var current: File? = directory
        while (current != null) {
            require(Files.isDirectory(current.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(current.toPath()))
            current = current.parentFile
        }
    }
    /** This file-only guard precedes even the initial isolated interpreter fingerprint command. */
    fun verifyLoaderBoundary(appRoot: String) {
        val etc = File(appRoot,"guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/rootfs/etc")
        directoryNoFollow(etc)
        require(!Files.exists(File(etc,"ld.so.preload").toPath(), NOFOLLOW_LINKS)) { "Global preload is not permitted for managed CMake" }
    }
    /** Fresh file-only proof of the recorded trusted runtime, including full directory inventory. */
    fun verifyRuntime(appRoot: String, runtime: JsonObject, checkCancelled: () -> Unit = {}) {
        require(runtime.keys==setOf("schema","implementation","pythonVersion","unicodeVersion","cmakeMajorMinor","packageVersions","files","links","directories","pythonZipAbsent","loaderConfigNames","loaderInventories","loaderAbsentDirectories"))
        require(runtime["schema"]==JsonPrimitive(1) && str(runtime,"implementation")=="CPython" && str(runtime,"pythonVersion").startsWith("3.12.") &&
            str(runtime,"unicodeVersion")=="15.0.0" && str(runtime,"cmakeMajorMinor")=="3.28")
        val versions=runtime.getValue("packageVersions").jsonObject
        require(versions.keys==setOf("python3.12","cmake") && str(versions,"python3.12").substringAfter(':').startsWith("3.12.") && str(versions,"cmake").substringAfter(':').startsWith("3.28."))
        verifyLoaderBoundary(appRoot)
        val guest=File(appRoot,"guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/rootfs")
        val files=runtime.getValue("files").jsonObject;val links=runtime.getValue("links").jsonObject
        val dirs=runtime.getValue("directories").jsonArray.map { it.jsonPrimitive.content }
        require(files.size in 3..16384 && links.size<=2048 && dirs.size<=20000 && dirs.distinct().size==dirs.size)
        fun path(p:String):File { requireAbsolute(p);require(p.startsWith("/usr/")||p.startsWith("/lib/")||p=="/lib"||p=="/var/lib/dpkg/status"||p=="/etc/ld.so.cache"||p=="/etc/ld.so.conf"||p=="/etc/ld.so.conf.d"||p.startsWith("/etc/ld.so.conf.d/"));return File(guest,p.removePrefix("/")) }
        require(files.keys.containsAll(listOf(PYTHON,CMAKE,"/var/lib/dpkg/status","/etc/ld.so.cache","/etc/ld.so.conf")))
        val configNames=runtime.getValue("loaderConfigNames").jsonArray.map { it.jsonPrimitive.also { p->require(p.isString) }.content }
        require(configNames.size<=128 && configNames.distinct().size==configNames.size && configNames.all { it.matches(Regex("[A-Za-z0-9_.+\\-]+\\.conf")) })
        val configRoot=path("/etc/ld.so.conf.d");directoryNoFollow(configRoot)
        require(requireNotNull(configRoot.list()).toSet()==configNames.toSet() && configNames.all { "/etc/ld.so.conf.d/$it" in files })
        val inventories=runtime.getValue("loaderInventories").jsonObject
        val absent=runtime.getValue("loaderAbsentDirectories").jsonArray.map { it.jsonPrimitive.also { p->require(p.isString) }.content }
        require(inventories.size in 1..128 && absent.size<=128 && absent.distinct().size==absent.size)
        require(inventories.values.sumOf { it.jsonObject.size }<=32768)
        for((directory, value) in inventories) {
            require(directory=="/usr/lib" || directory.startsWith("/usr/lib/") || directory=="/usr/local/lib" || directory.startsWith("/usr/local/lib/"))
            val d=path(directory);directoryNoFollow(d);val expected=value.jsonObject
            require(expected.size<=16384 && "glibc-hwcaps" !in expected && requireNotNull(d.list()).toSet()==expected.keys)
            for((name,item) in expected) {
                checkCancelled();require(name.isNotEmpty() && name.none { it.code<32 || it.code>=127 || it=='/' || it=='\\' })
                val child=File(d,name).toPath();val entry=item.jsonObject
                when(str(entry,"type")) {
                    "file" -> require(entry.keys==setOf("type") && Files.isRegularFile(child,NOFOLLOW_LINKS))
                    "directory" -> require(entry.keys==setOf("type") && Files.isDirectory(child,NOFOLLOW_LINKS))
                    "link" -> require(entry.keys==setOf("type","target") && Files.isSymbolicLink(child) && Files.readSymbolicLink(child).toString()==str(entry,"target"))
                    else -> error("Unknown runtime directory entry")
                }
            }
        }
        for(directory in absent) {
            require(directory=="/usr/lib" || directory.startsWith("/usr/lib/") || directory=="/usr/local/lib" || directory.startsWith("/usr/local/lib/"))
            val missing=path(directory);var parent=missing.parentFile
            while(parent!=null && !Files.exists(parent.toPath(),NOFOLLOW_LINKS)) parent=parent.parentFile
            requireNotNull(parent);directoryNoFollow(parent)
            require(!Files.exists(missing.toPath(),NOFOLLOW_LINKS))
        }
        var total=0L
        for((name,element) in files) {
            checkCancelled();val entry=element.jsonObject;require(entry.keys==setOf("sha256","bytes"))
            val size=entry.getValue("bytes").jsonPrimitive.long;require(size in 0..67_108_864)
            val bytes=read(path(name),maxOf(1,size),checkCancelled);require(bytes.size.toLong()==size && hash(bytes)==str(entry,"sha256"))
            total+=size;require(total<=536_870_912)
        }
        for((name,target) in links) {checkCancelled();val p=path(name).toPath();require(Files.isSymbolicLink(p) && Files.readSymbolicLink(p).toString()==target.jsonPrimitive.content)}
        val known=files.keys+links.keys+dirs
        for(directory in dirs) {
            require(directory=="/usr/lib/python3.12" || directory.startsWith("/usr/lib/python3.12/") || directory=="/usr/share/cmake-3.28" || directory.startsWith("/usr/share/cmake-3.28/"))
            val d=path(directory);require(Files.isDirectory(d.toPath(),NOFOLLOW_LINKS))
            val names=requireNotNull(d.listFiles()).map { "$directory/${it.name}" }.toSet()
            require(names==known.filter { it.substringBeforeLast('/')==directory }.toSet()) { "Runtime directory contents changed" }
        }
        require(dirs.containsAll(listOf("/usr/lib/python3.12","/usr/share/cmake-3.28")))
        if(runtime["pythonZipAbsent"]==JsonPrimitive(true)) require(!Files.exists(path("/usr/lib/python312.zip").toPath(),NOFOLLOW_LINKS))
        else require(runtime["pythonZipAbsent"]==JsonPrimitive(false) && "/usr/lib/python312.zip" in files)
    }
    fun verifyInstalled(record: ManagedPackageRecord, appRoot: String, checkCancelled: () -> Unit = {}): JsonObject {
        require(record.familyId==FAMILY && record.version==VERSION && record.abi=="arm64-v8a" && record.scope==ExecutionScope.LOCAL_LINUX_ARM64.name &&
            record.metadata[LocalManagedPackageMetadata.KIND_KEY]==LocalManagedPackageMetadata.KIND_UBUNTU_CMAKE &&
            record.metadata[PackageBackendContract.METADATA_KEY]==PackageBackendId.LOCAL_APP.name && record.environment.isEmpty() && record.dependencies.size==1 &&
            record.artifactSha256==recipeSha256 && record.metadata[LocalManagedPackageMetadata.PROFILE_KEY]==DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name &&
            record.metadata[LocalManagedPackageMetadata.LIBC_KEY]==DeclarativeLibcCompatibility.GLIBC.name)
        val paths=LocalPackageInstallJournal.paths(appRoot,FAMILY,VERSION,LocalManagedPackageMetadata.KIND_UBUNTU_CMAKE)
        require(record.installRoot==paths.final.absolutePath && record.commands==mapOf(COMMAND to "${paths.final}/payload/host-bin/$COMMAND") &&
            record.pathEntries==listOf("${paths.final}/payload/host-bin") && record.healthChecks==listOf(ManagedPackageHealthCheck("host-bin/$COMMAND",listOf("--droide-health"))))
        val root=paths.final;val guest=requireNotNull(LocalLinuxPackageEnvironment.guestPath(root.absolutePath,"$appRoot/packages/"))
        val stage=requireNotNull(LocalLinuxPackageEnvironment.guestPath(paths.stage.absolutePath,"$appRoot/packages/"))
        val activation=record.metadata.getValue(LocalManagedPackageMetadata.ACTIVATION_ID_KEY)
        require(LocalPackageInstallJournal.matchesActivation(root,activation) && text(File(root,LOCK),4_194_304,checkCancelled)==lock.toString())
        require(sha(File(root,BOOTSTRAP),1_000_000,checkCancelled)==BOOTSTRAP_SHA256 && sha(File(root,SUPERVISOR),1_000_000,checkCancelled)==LocalUbuntuCMakeLaunchPolicy.SUPERVISOR_SHA256 && text(File(root,HEALTH),1_000_000,checkCancelled)==LocalUbuntuCMakeLspHealth.script)
        require(text(File(root,"payload/bin/$COMMAND"),65536,checkCancelled)==guestWrapper(guest) && text(File(root,"payload/host-bin/$COMMAND"),65536,checkCancelled)==hostWrapper("$appRoot/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch",guest))
        require(File(root,"payload/bin/$COMMAND").canExecute() && File(root,"payload/host-bin/$COMMAND").canExecute())
        val sealText=text(File(root,SEAL),4_194_304,checkCancelled)
        require(hash(sealText.toByteArray())==record.metadata[LocalManagedPackageMetadata.SEAL_SHA256_KEY] && strictObject(sealText)==tree(root,checkCancelled))
        val purelib=tree(File(root,PURELIB),checkCancelled).getValue("files").jsonObject.mapValues { it.value.jsonPrimitive.content }
        val current=json(File(root,AUTHORITY),4_194_304,checkCancelled)
        require(current==authority(activation,stage,guest,sha(File(root,RUNTIME),4_194_304,checkCancelled),purelib))
        verifyRuntime(appRoot,json(File(root,RUNTIME),4_194_304,checkCancelled),checkCancelled)
        return current
    }
    /** Validate lexical structure before the standard parser, which otherwise discards duplicate keys. */
    fun strictObject(text: String, maxBytes: Int = 4_194_304): JsonObject {
        require(text.toByteArray(Charsets.UTF_8).size in 1..maxBytes)
        StrictJson(text).validate()
        return Json.parseToJsonElement(text) as? JsonObject ?: error("CMake JSON must be an object")
    }
    private class StrictJson(private val s: String) {
        private var i = 0
        private var values = 0
        fun validate() { value(0); white(); require(i == s.length) }
        private fun white() { while (i < s.length && s[i] in " \t\r\n") i++ }
        private fun take(c: Char): Boolean { white(); return if (i < s.length && s[i] == c) { i++; true } else false }
        private fun string(): String {
            white(); val start = i; require(i < s.length && s[i++] == '"')
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') return Json.parseToJsonElement(s.substring(start, i)).jsonPrimitive.content
                require(c.code >= 32)
                if (c == '\\') { require(i < s.length); val e = s[i++]; require(e in "\"\\/bfnrtu"); if (e == 'u') { require(i + 4 <= s.length && s.substring(i, i + 4).all { it in "0123456789abcdefABCDEF" }); i += 4 } }
            }
            error("Unterminated CMake JSON string")
        }
        private fun value(depth: Int) {
            require(depth <= 64 && ++values <= 100_000); white(); require(i < s.length)
            when (s[i]) {
                '{' -> { i++; val keys = hashSetOf<String>(); if (!take('}')) do { require(keys.add(string())) { "Duplicate CMake JSON key" }; require(take(':')); value(depth + 1); if (take('}')) break; require(take(',')) } while (true) }
                '[' -> { i++; if (!take(']')) do { value(depth + 1); if (take(']')) break; require(take(',')) } while (true) }
                '"' -> string()
                else -> { val start = i; while (i < s.length && s[i] !in ",]} \t\r\n") i++; require(i > start); val token = s.substring(start, i); require(token in setOf("true", "false", "null") || token.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))) }
            }
        }
    }
}

/** File-only typed provenance. Never launches Python, CMake, health, discovery, or project code. */
internal object CMakeManagedLaunchAuthority {
    suspend fun inspect(appRoot: String, authority: LocalManagedPackageAuthority, records: List<ManagedPackageRecord>,
        selections: Map<String,String>, discoveredExecutable: String): CMakeManagedLaunchIdentity? = try {
        val context=kotlinx.coroutines.currentCoroutineContext()
        val checkCancelled={ context.ensureCMakeActive() }
        checkCancelled();require(records.size<=512)
        val record=records.single { it.familyId==LocalUbuntuCMakePolicy.FAMILY && it.version==LocalUbuntuCMakePolicy.VERSION }
        require(authority.ownedReceipt(record.familyId,record.version)==record && authority.owns(record))
        require(LocalLinuxPackageEnvironment.selectedRecords(records,selections,appRoot).singleOrNull { it.familyId==record.familyId }==record &&
            (selections[record.familyId]==null || selections[record.familyId]==record.version))
        val guest=requireNotNull(LocalLinuxPackageEnvironment.guestPath(record.installRoot,"$appRoot/packages/"))
        val wrapper="$guest/payload/bin/${LocalUbuntuCMakePolicy.COMMAND}"
        val actual=if(discoveredExecutable==LocalUbuntuCMakePolicy.COMMAND) wrapper else discoveredExecutable
        require(actual==wrapper || actual=="/opt/droide/bin/${LocalUbuntuCMakePolicy.COMMAND}")
        projection(appRoot,actual,wrapper,checkCancelled)
        val proof=LocalUbuntuCMakePolicy.verifyInstalled(record,appRoot,checkCancelled)
        layer(records,record,appRoot,checkCancelled)
        CMakeManagedLaunchIdentity(record.familyId,record.version,LocalUbuntuCMakePolicy.REVISION,
            receiptHash(record,records),LocalUbuntuCMakePolicy.recipeSha256,record.metadata.getValue(LocalManagedPackageMetadata.SEAL_SHA256_KEY),
            LocalUbuntuCMakePolicy.hash((record.familyId+"@"+record.version+":"+record.metadata.getValue(LocalManagedPackageMetadata.ACTIVATION_ID_KEY)+":"+record.installRoot).toByteArray()),
            actual,wrapper,"$guest/${LocalUbuntuCMakePolicy.BOOTSTRAP}","$guest/${LocalUbuntuCMakePolicy.SUPERVISOR}","$guest/${LocalUbuntuCMakePolicy.PURELIB}",
            LocalUbuntuCMakePolicy.PYTHON,LocalUbuntuCMakePolicy.CMAKE,LocalUbuntuCMakePolicy.str(proof,"runtimeSha256"),
            LocalUbuntuCMakePolicy.BOOTSTRAP_SHA256,LocalUbuntuCMakeLaunchPolicy.SUPERVISOR_SHA256)
    } catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled} catch(_:Exception){null}

    fun proveAndCaptureAdmission(appRoot:String,identity:CMakeManagedLaunchIdentity,records:List<ManagedPackageRecord>,checkCancelled:()->Unit,
        clockNanos:()->Long=System::nanoTime):CMakeManagedAdmissionWitness = CMakeManagedAdmissionWitness.capture(appRoot,identity,records,checkCancelled,clockNanos)
    internal fun metadataMatches(appRoot:String,identity:CMakeManagedLaunchIdentity,records:List<ManagedPackageRecord>,selections:Map<String,String>):Boolean {
        require(records.size<=512)
        val record=LocalLinuxPackageEnvironment.selectedRecords(records,selections,appRoot).single {it.familyId==identity.familyId}
        require(record.version==identity.version && (selections[record.familyId]==null || selections[record.familyId]==record.version))
        return receiptHash(record,records)==identity.receiptSha256
    }

    fun prove(appRoot: String, identity: CMakeManagedLaunchIdentity, records: List<ManagedPackageRecord>, checkCancelled: ()->Unit) {
        checkCancelled();require(records.size<=512)
        val r=records.single { it.familyId==identity.familyId && it.version==identity.version }
        val proof=LocalUbuntuCMakePolicy.verifyInstalled(r,appRoot,checkCancelled);layer(records,r,appRoot,checkCancelled)
        val guest=requireNotNull(LocalLinuxPackageEnvironment.guestPath(r.installRoot,"$appRoot/packages/"))
        val wrapper="$guest/payload/bin/${LocalUbuntuCMakePolicy.COMMAND}"
        require(identity.familyId==LocalUbuntuCMakePolicy.FAMILY && identity.version==LocalUbuntuCMakePolicy.VERSION && identity.sourcePolicyRevision==LocalUbuntuCMakePolicy.REVISION &&
            identity.receiptSha256==receiptHash(r,records) && identity.recipeSha256==LocalUbuntuCMakePolicy.recipeSha256 && identity.sealSha256==r.metadata[LocalManagedPackageMetadata.SEAL_SHA256_KEY] &&
            identity.selectedPackageIdentity==LocalUbuntuCMakePolicy.hash((r.familyId+"@"+r.version+":"+r.metadata.getValue(LocalManagedPackageMetadata.ACTIVATION_ID_KEY)+":"+r.installRoot).toByteArray()) &&
            identity.launchExecutable==wrapper && identity.bootstrapGuestAbsolute=="$guest/${LocalUbuntuCMakePolicy.BOOTSTRAP}" && identity.supervisorGuestAbsolute=="$guest/${LocalUbuntuCMakePolicy.SUPERVISOR}" &&
            identity.purelibGuestAbsolute=="$guest/${LocalUbuntuCMakePolicy.PURELIB}" && identity.pythonExecutableGuestAbsolute==LocalUbuntuCMakePolicy.PYTHON && identity.cmakeExecutableGuestAbsolute==LocalUbuntuCMakePolicy.CMAKE &&
            identity.runtimeFingerprint==LocalUbuntuCMakePolicy.str(proof,"runtimeSha256") && identity.bootstrapSha256==LocalUbuntuCMakePolicy.BOOTSTRAP_SHA256 &&
            identity.supervisorSha256==LocalUbuntuCMakeLaunchPolicy.SUPERVISOR_SHA256 && identity.approvedToolBinGuestPaths.isEmpty())
        projection(appRoot,identity.discoveredExecutable,wrapper,checkCancelled)
    }
    private fun projection(appRoot:String,discovered:String,wrapper:String,checkCancelled:()->Unit) {
        require(discovered==wrapper || discovered=="/opt/droide/bin/${LocalUbuntuCMakePolicy.COMMAND}")
        if(discovered!=wrapper){val file=File(appRoot,"managed/ubuntu-bin/${LocalUbuntuCMakePolicy.COMMAND}")
            require(LocalUbuntuCMakePolicy.text(file,65536,checkCancelled)=="#!/bin/sh\nexec ${LocalExecutionSubstrate.shellQuote(wrapper)} \"${'$'}@\"\n" && file.canExecute())}
    }
    internal fun layer(records:List<ManagedPackageRecord>,record:ManagedPackageRecord,appRoot:String,checkCancelled:()->Unit) {
        val key=record.dependencies.single();val layer=records.single { "${it.familyId}@${it.version}"==key }
        require(PackageBackendContract.recordOwner(layer.installRoot,layer.metadata,appRoot)==PackageBackendId.LOCAL_APP &&
            layer.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND]==FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND &&
            layer.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES]?.split(',')?.sorted()==LocalUbuntuCMakePolicy.packages.sorted() && layer.commands.isEmpty() && layer.dependencies.isEmpty())
        val manifest=LocalUbuntuCMakePolicy.text(File(layer.installRoot,layer.checksumFile),65536,checkCancelled)
        val lines=manifest.lines().filter(String::isNotEmpty);require(lines.size==2)
        val seen=mutableSetOf<String>()
        for(line in lines){val m=requireNotNull(Regex("([0-9a-f]{64})  (LAYER\\.txt|payload/verify-layer)").matchEntire(line));require(seen.add(m.groupValues[2]));require(LocalUbuntuCMakePolicy.sha(File(layer.installRoot,m.groupValues[2]),65536,checkCancelled)==m.groupValues[1])}
        require(seen==setOf("LAYER.txt","payload/verify-layer"))
    }
    internal fun receiptHash(record:ManagedPackageRecord,records:List<ManagedPackageRecord>):String {
        fun encode(r:ManagedPackageRecord)=buildJsonObject {
            put("family",r.familyId);put("version",r.version);put("scope",r.scope);put("root",r.installRoot);put("installedAt",r.installedAtEpochMs);put("active",r.active)
            put("paths",JsonArray(r.pathEntries.map(::JsonPrimitive)));put("environment",JsonObject(r.environment.mapValues { JsonPrimitive(it.value) }));put("commands",JsonObject(r.commands.mapValues { JsonPrimitive(it.value) }))
            put("dependencies",JsonArray(r.dependencies.map(::JsonPrimitive)));put("metadata",JsonObject(r.metadata.mapValues { JsonPrimitive(it.value) }));put("artifact",r.artifactSha256?.let(::JsonPrimitive)?:JsonNull)
            put("checksum",r.checksumFile);put("abi",r.abi?.let(::JsonPrimitive)?:JsonNull);put("health",JsonArray(r.healthChecks.map { buildJsonObject { put("executable",it.executable);put("args",JsonArray(it.args.map(::JsonPrimitive))) } }))
        }
        val dependency=records.single { "${it.familyId}@${it.version}"==record.dependencies.single() }
        return LocalUbuntuCMakePolicy.hash(LocalUbuntuCMakePolicy.canonical(buildJsonObject { put("package",encode(record));put("layer",encode(dependency)) }).toByteArray())
    }
    private fun kotlin.coroutines.CoroutineContext.ensureCMakeActive() { this.ensureActive() }
}

/** One owner, one immutable baseline. Metadata reuse is not a same-UID mutation barrier. */
internal class CMakeManagedAdmissionWitness private constructor(
    private val appRoot: String,
    private val identity: CMakeManagedLaunchIdentity,
    private val spec: CMakeAdmissionMetadata.Spec,
    private val baseline: CMakeAdmissionMetadata.Snapshot,
    private val clockNanos: () -> Long,
    private var lastSweep: Long,
) {
    @Volatile private var retired = false
    fun retire() { retired = true }
    @Synchronized fun isCurrent(records: List<ManagedPackageRecord>, selections: Map<String,String>, checkCancelled: () -> Unit): Boolean =
        compare(records,selections,checkCancelled,false)
    @Synchronized fun requireCurrentBeforeStart(records: List<ManagedPackageRecord>, selections: Map<String,String>, checkCancelled: () -> Unit): Boolean =
        compare(records,selections,checkCancelled,true)
    private fun compare(records: List<ManagedPackageRecord>, selections: Map<String,String>, checkCancelled: () -> Unit, forceSweep: Boolean): Boolean {
        if(retired) return false
        return try {
            val started=clockNanos();require(started>=lastSweep)
            val check={checkCancelled();require(!retired);val now=clockNanos();require(now>=started && now-started<=CMakeAdmissionMetadata.BUDGET_NANOS) { "CMake metadata observation deadline" }}
            check();require(CMakeManagedLaunchAuthority.metadataMatches(appRoot,identity,records,selections))
            for(path in spec.sentinels) {check();require(CMakeAdmissionMetadata.observe(spec,path)==baseline.stamps.getValue(path))}
            if(forceSweep || started-lastSweep>=CMakeAdmissionMetadata.INTERVAL_NANOS) {
                require(CMakeAdmissionMetadata.snapshot(spec,check)==baseline)
                check();lastSweep=clockNanos()
            }
            check();true
        } catch(cancelled:kotlinx.coroutines.CancellationException) {retired=true;throw cancelled}
        catch(_:Exception) {retired=true;false}
    }
    companion object {
        internal fun capture(appRoot:String,identity:CMakeManagedLaunchIdentity,records:List<ManagedPackageRecord>,checkCancelled:()->Unit,
            clockNanos:()->Long):CMakeManagedAdmissionWitness {
            // Bracket the byte proof. A file changed between proof and capture cannot become a new baseline.
            val spec=CMakeAdmissionMetadata.spec(appRoot,identity,records,checkCancelled)
            fun snapshot():CMakeAdmissionMetadata.Snapshot {
                val start=clockNanos()
                return CMakeAdmissionMetadata.snapshot(spec) {checkCancelled();val now=clockNanos();require(now>=start && now-start<=CMakeAdmissionMetadata.BUDGET_NANOS)}
            }
            val before=snapshot()
            CMakeManagedLaunchAuthority.prove(appRoot,identity,records,checkCancelled)
            require(snapshot()==before) { "CMake identity changed while capturing owner admission" }
            return CMakeManagedAdmissionWitness(appRoot,identity,spec,before,clockNanos,clockNanos())
        }
    }
}

/** Finite no-follow metadata, using Android native identity plus the available FileTime precision. */
internal object CMakeAdmissionMetadata {
    const val POLICY_ID="r96-cmake-owner-metadata-admission-v2"
    const val CONTRACT_SHA256="5e8a1427a187a20b50db97e71bc02a6095f49b3e300a06738ed99ba14c4fe69b"
    const val INTERVAL_NANOS=5_000_000_000L
    const val BUDGET_NANOS=2_000_000_000L // Cooperative between operations; not a hard syscall timeout.
    const val MAX_ENTRIES=100_000
    const val MAX_PATH_BYTES=16_777_216
    const val MAX_SENTINELS=512
    data class Stamp(val device:Long,val inode:Long,val uid:Int,val gid:Int,val mode:Int,val nlink:Long,val size:Long,
        val mtimeNanos:Long,val ctimeSeconds:Long,val link:String?)
    data class Spec(val paths:Set<String>,val directories:Set<String>,val sentinels:Set<String>)
    data class Snapshot(val stamps:Map<String,Stamp?>,val children:Map<String,List<String>>)
    private fun native(file:File):android.system.StructStat? = try {android.system.Os.lstat(file.absolutePath)}
        catch(failure:android.system.ErrnoException) {if(failure.errno==android.system.OsConstants.ENOENT) null else throw failure}
    private fun nativeFields(s:android.system.StructStat)=listOf(s.st_dev,s.st_ino,s.st_uid.toLong(),s.st_gid.toLong(),s.st_mode.toLong(),s.st_nlink,s.st_size,s.st_mtime,s.st_ctime)
    fun observe(spec:Spec,path:String):Stamp?=stamp(File(path),path !in spec.directories)
    fun stamp(file:File,identityOnlyDirectory:Boolean=false):Stamp? {
        val before=native(file) ?: return null
        val identityOnly=identityOnlyDirectory && before.st_mode and 0xf000==0x4000
        val basic=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
        val link=if(basic.isSymbolicLink) Files.readSymbolicLink(file.toPath()).toString().also {require(it.toByteArray().size<=4096)} else null
        val after=requireNotNull(native(file));require(if(identityOnly) nativeFields(before).take(5)==nativeFields(after).take(5) else nativeFields(before)==nativeFields(after))
        require((identityOnly || basic.size()==before.st_size) && (before.st_mode and 0xf000) in setOf(0x4000,0x8000,0xa000))
        require(basic.isDirectory==(before.st_mode and 0xf000==0x4000) && basic.isRegularFile==(before.st_mode and 0xf000==0x8000) && basic.isSymbolicLink==(before.st_mode and 0xf000==0xa000))
        return if(identityOnly) Stamp(before.st_dev,before.st_ino,before.st_uid,before.st_gid,before.st_mode,0,0,0,0,null)
        else Stamp(before.st_dev,before.st_ino,before.st_uid,before.st_gid,before.st_mode,before.st_nlink,before.st_size,
            basic.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS),before.st_ctime,link)
    }
    fun snapshot(spec:Spec,check:()->Unit):Snapshot {
        require(spec.paths.size<=MAX_ENTRIES && spec.sentinels.size<=MAX_SENTINELS && spec.paths.containsAll(spec.sentinels))
        var bytes=spec.paths.sumOf {it.toByteArray().size.toLong()};require(bytes<=MAX_PATH_BYTES)
        val stamps=linkedMapOf<String,Stamp?>();val children=linkedMapOf<String,List<String>>()
        for(path in spec.paths) {check();val value=observe(spec,path);stamps[path]=value;bytes+=value?.link?.toByteArray()?.size?:0;require(bytes<=MAX_PATH_BYTES)}
        for(path in spec.directories) {
            check();val before=requireNotNull(stamps[path]);require(before.mode and 0xf000==0x4000 && stamp(File(path))==before)
            val names=mutableListOf<String>()
            Files.newDirectoryStream(File(path).toPath()).use {stream->for(child in stream) {
                check();require(names.size<MAX_ENTRIES);val name=child.fileName.toString();require(name.isNotEmpty() && name !in setOf(".","..") && '/' !in name && '\u0000' !in name)
                require(child.toAbsolutePath().toString() in spec.paths) { "Unrecorded CMake runtime directory entry" }
                names+=name;bytes+=name.toByteArray().size;require(bytes<=MAX_PATH_BYTES)
            }}
            check();require(stamp(File(path))==before);children[path]=names.sorted()
        }
        check();return Snapshot(stamps,children)
    }
    fun spec(appRoot:String,identity:CMakeManagedLaunchIdentity,records:List<ManagedPackageRecord>,checkCancelled:()->Unit):Spec {
        val app=File(appRoot);require(app.canonicalPath==appRoot)
        val record=records.single {it.familyId==identity.familyId && it.version==identity.version}
        val layer=records.single {"${it.familyId}@${it.version}"==record.dependencies.single()}
        val root=File(record.installRoot);val guest=File(app,"guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/rootfs")
        val paths=sortedSetOf<String>();val dirs=sortedSetOf<String>();val sentinels=sortedSetOf<String>()
        fun add(file:File,sentinel:Boolean=false,directory:Boolean=false) {
            checkCancelled();val path=file.absolutePath;LocalUbuntuCMakePolicy.requireAbsolute(path)
            require(path==appRoot || path.startsWith("$appRoot/"));require(path.toByteArray().size<=4096)
            paths+=path;if(sentinel)sentinels+=path;if(directory)dirs+=path
            var parent=file.parentFile
            while(parent!=null && (parent.path==appRoot || parent.path.startsWith("$appRoot/"))) {
                paths+=parent.path;if(sentinel)sentinels+=parent.path
                if(parent.path==appRoot)break;parent=parent.parentFile
            }
            require(paths.size<=MAX_ENTRIES && sentinels.size<=MAX_SENTINELS)
        }
        fun relative(base:File,path:String):File {require(path.isNotEmpty() && !path.startsWith('/') && path.split('/').none {it.isEmpty()||it=="."||it==".."});return File(base,path)}
        fun runtime(path:String):File {LocalUbuntuCMakePolicy.requireAbsolute(path);return relative(guest,path.removePrefix("/"))}
        val seal=LocalUbuntuCMakePolicy.json(File(root,LocalUbuntuCMakePolicy.SEAL),4_194_304,checkCancelled)
        add(root,true,true)
        seal.getValue("files").jsonObject.keys.forEach {add(relative(root,it))}
        seal.getValue("directories").jsonArray.forEach {add(relative(root,it.jsonPrimitive.content),directory=true)}
        for(path in listOf("payload","payload/purelib","payload/bin","payload/host-bin","DROIDE_ACTIVATION_ID",LocalUbuntuCMakePolicy.LOCK,
            LocalUbuntuCMakePolicy.AUTHORITY,LocalUbuntuCMakePolicy.SEAL,LocalUbuntuCMakePolicy.RUNTIME,LocalUbuntuCMakePolicy.BOOTSTRAP,
            LocalUbuntuCMakePolicy.SUPERVISOR,LocalUbuntuCMakePolicy.HEALTH,"payload/bin/${LocalUbuntuCMakePolicy.COMMAND}","payload/host-bin/${LocalUbuntuCMakePolicy.COMMAND}")) add(relative(root,path),true)
        if(identity.discoveredExecutable=="/opt/droide/bin/${LocalUbuntuCMakePolicy.COMMAND}")add(File(app,"managed/ubuntu-bin/${LocalUbuntuCMakePolicy.COMMAND}"),true)
        add(File(layer.installRoot),true)
        for(path in listOf("LAYER.txt","payload/verify-layer",layer.checksumFile))add(relative(File(layer.installRoot),path),true)
        val manifest=LocalUbuntuCMakePolicy.json(File(root,LocalUbuntuCMakePolicy.RUNTIME),4_194_304,checkCancelled)
        manifest.getValue("files").jsonObject.keys.forEach {add(runtime(it),it.substringAfterLast('/')=="ld-linux-aarch64.so.1")}
        manifest.getValue("links").jsonObject.keys.forEach {add(runtime(it),it=="/lib" || it.substringAfterLast('/')=="ld-linux-aarch64.so.1")}
        manifest.getValue("directories").jsonArray.forEach {add(runtime(it.jsonPrimitive.content),directory=true)}
        for((directory,entries) in manifest.getValue("loaderInventories").jsonObject) {
            add(runtime(directory),true,true);entries.jsonObject.keys.forEach {add(relative(runtime(directory),it))}
        }
        manifest.getValue("loaderAbsentDirectories").jsonArray.forEach {add(runtime(it.jsonPrimitive.content),true)}
        add(runtime("/etc/ld.so.conf.d"),true,true)
        manifest.getValue("loaderConfigNames").jsonArray.forEach {add(relative(runtime("/etc/ld.so.conf.d"),it.jsonPrimitive.content),true)}
        for(path in listOf(LocalUbuntuCMakePolicy.PYTHON,LocalUbuntuCMakePolicy.CMAKE,"/etc/ld.so.cache","/etc/ld.so.conf","/etc/ld.so.preload",
            "/var/lib/dpkg/status","/usr/lib/python3.12","/usr/lib/python3.12/lib-dynload","/usr/share/cmake-3.28","/usr/share/cmake-3.28/Modules","/usr/lib/python312.zip"))add(runtime(path),true)
        add(guest,true);require(paths.sumOf {it.toByteArray().size.toLong()}<=MAX_PATH_BYTES)
        return Spec(paths,dirs,sentinels)
    }
}
