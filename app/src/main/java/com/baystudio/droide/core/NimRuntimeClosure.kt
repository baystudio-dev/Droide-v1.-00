package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Finite read-only native admission. Never executes ldd, an original program, or project code. */
internal object NimRuntimeClosure {
    const val INTERPRETER = "/lib/ld-linux-aarch64.so.1"
    const val MAX_TREE_BYTES = 1024L * 1024 * 1024
    private val nativeLibraries = emptySet<String>()
    enum class NativeRole { EXECUTABLE, SHARED_LIBRARY, GUEST_LOADER, GUEST_LIBC }
    data class NativeSymbol(val name:String,val type:Int,val version:String?,val provider:String?,val defaultVersion:Boolean,val weak:Boolean)
    data class NativeImage(val relativePath: String, val role: NativeRole, val needed: List<String>, val soname: String?,
        val originSearch: Boolean, val versionNeeds: Map<String, Set<String>>, val versionDefinitions: Set<String>, val fileBackedRanges: List<Pair<Long,Long>> = emptyList(), val exports:List<NativeSymbol> = emptyList(), val imports:List<NativeSymbol> = emptyList())
    data class Inventory(val root: File, val files: Set<String>, val native: Map<String, NativeImage>, val hashes: Map<String,String>, val links: Map<String,String>)
    data class Closure(val files: Map<String,String>, val sha256: String)
    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun inspectTree(root: File, executables: Set<String>, required: Set<String>, maxFiles: Int, maxBytes: Long,
        top: String? = null, safeLinks: Boolean = false, active: () -> Unit = {}): Inventory {
        requireDirectory(root)
        val files = linkedSetOf<String>(); val native = linkedMapOf<String,NativeImage>(); val hashes = linkedMapOf<String,String>()
        val links = linkedMapOf<String,String>(); val folded = hashSetOf<String>(); var total = 0L; var entries = 0
        fun walk(dir: File, prefix: String, depth: Int) {
            require(depth <= 32); requireDirectory(dir)
            Files.newDirectoryStream(dir.toPath()).use { paths -> for (path in paths) {
                active(); require(++entries <= maxFiles * 2)
                val relative = prefix + path.fileName.toString(); requireRelative(relative)
                require(top == null || relative == top || relative.startsWith("$top/")) { "Nim archive has an unexpected top-level member" }
                require(folded.add(relative.lowercase(java.util.Locale.ROOT))) { "Nim case-colliding member" }
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (attrs.isSymbolicLink) {
                    require(safeLinks && links.size < 256 && relative !in executables)
                    val target = Files.readSymbolicLink(path).toString()
                    require(target.length in 1..4096 && !target.startsWith('/') && '\\' !in target && target.none { it.code < 32 })
                    val resolved = path.parent.resolve(target).normalize()
                    require(resolved.startsWith(root.toPath().resolve(requireNotNull(top))) && resolved != root.toPath().resolve(top))
                    require(path.toFile().canonicalPath.startsWith(File(root, top).canonicalPath + File.separator) && Files.isRegularFile(path))
                    links[relative] = target
                } else if (attrs.isDirectory) walk(path.toFile(), "$relative/", depth + 1)
                else {
                    require(attrs.isRegularFile && files.size < maxFiles)
                    total = Math.addExact(total, attrs.size()); require(total <= maxBytes)
                    files += relative; hashes[relative] = measure(path.toFile(), maxBytes, active).second
                    val magic = readPrefix(path.toFile(), 4)
                    if (magic.contentEquals(byteArrayOf(127,69,76,70))) {
                        require(relative in executables) { "Unreviewed Nim original native member: $relative" }
                        native[relative] = inspectElf(path.toFile(), relative, NativeRole.EXECUTABLE, active)
                        require(!native.getValue(relative).originSearch) { "Nim original loader search paths require review" }
                    } else require(relative !in executables) { "Nim expected original executable is not ELF" }
                }
            } }
        }
        walk(root, "", 0)
        require(files.containsAll(required) && native.keys == executables) { "Nim original tree required layout/member missing" }
        required.forEach { require(File(root,it).length() > 0) }
        return Inventory(root, files, native, hashes, links)
    }

    private const val SSL_SUFFIXES = "(.3|.1.1|.1.0.2|.1.0.1|.1.0.0|.0.9.9|.0.9.8|.48|.47|.46|.45|.44|.43|.41|.39|.38|.10|)"
    private val loaderPatterns = mapOf("libssl.so" to "libssl.so$SSL_SUFFIXES", "libcrypto.so" to "libcrypto.so$SSL_SUFFIXES", "libpcre.so" to "libpcre.so(.3|.1|)")
    /** Positive original data evidence for the ordinary source-default loader path, not a universal
     * dlopen or SBOM proof. */
    fun requireDefaultLoaderEvidence(inventories: List<Inventory>, active: () -> Unit = {}): Map<String,String> {
        val evidence = linkedMapOf<String,String>()
        for (inventory in inventories) for ((relative,image) in inventory.native) {
            if (relative == LocalUbuntuNimHelperPolicy.ENTRY) continue
            val observed = linkedSetOf<String>()
            image.needed.filter { name -> loaderPatterns.keys.any { name.startsWith(it) } }.forEach(observed::add)
            loadIdentifiers(inventory,relative,image,active).filter { value -> loaderPatterns.keys.any { value.contains(it) } }.forEach { value ->
                for(prefix in loaderPatterns.keys) {
                    val index=value.indexOf(prefix)
                    if(index>=0) {
                        val literal=value.substring(index)
                        require(literal==loaderPatterns.getValue(prefix)||literal=="$prefix.3") { "Unsupported original Nim loader identity: $literal" }
                        observed+=literal
                    }
                }
            }
            for(prefix in loaderPatterns.keys) require(observed.count { it.startsWith(prefix) }<=1) { "Ambiguous original Nim loader build evidence" }
            val ssl=observed.singleOrNull { it.startsWith("libssl.so") };val crypto=observed.singleOrNull { it.startsWith("libcrypto.so") }
            if(ssl!=null && crypto!=null) require(ssl.removePrefix("libssl")==crypto.removePrefix("libcrypto")) { "Mixed original SSL/crypto build identities" }
            observed.forEach { literal -> evidence["$relative:$literal"] = inventory.hashes.getValue(relative) }
            loadIdentifiers(inventory,relative,image,active).forEach { identifier ->
                ordinaryDirectExports.entries.singleOrNull { identifier in it.value }?.let { (provider,_) ->
                    evidence["$relative:symbol:$provider:$identifier"]=inventory.hashes.getValue(relative)
                }
            }
        }
        require(evidence.keys.any { it.substringAfter(':').startsWith("libssl.so") } &&
            evidence.keys.any { it.substringAfter(':').startsWith("libcrypto.so") }) {
            "Original Nimble ordinary OpenSSL loader identity is unsupported or unobserved; no default-build identity was inferred from missing strings"
        }
        return evidence.toSortedMap()
    }

    // Nim commit8e8fbf6 wrappers: openssl SHA6277b2b1..., pcre SHA7933860c...; optional nullable
    // compatibility names are deliberately not blanket requirements.
    internal val ordinaryMandatoryExports=mapOf(
        "libssl.so.3" to setOf("OPENSSL_init_ssl","TLS_method","TLS_client_method","TLS_server_method","SSL_in_init","SSL_CTX_set_ciphersuites","SSL_get1_peer_certificate","SSL_new","SSL_free","SSL_CTX_new","SSL_CTX_free"),
        "libcrypto.so.3" to setOf("ERR_get_error","ERR_error_string","BIO_new","BIO_free"),
        "libpcre.so.3" to setOf("pcre_compile","pcre_exec","pcre_fullinfo","pcre_study","pcre_free_study","pcre_version")
    )
    private val nullableCompatibilityExports=setOf("CRYPTO_set_mem_functions", "ERR_load_BIO_strings", "OPENSSL_add_all_algorithms_conf", "OpenSSL_version_num", "SSL_load_error_strings", "SSLeay")
    private val legacyCompatibilityExports=setOf("SSL_get_peer_certificate", "SSL_library_init", "SSL_state", "SSLv23_client_method", "SSLv23_method", "SSLv23_server_method", "SSLv2_method", "SSLv3_method")
    private val ordinaryDirectExports=mapOf(
        "libcrypto.so.3" to """BIO_ctrl BIO_ctrl_pending BIO_free BIO_free_all BIO_new BIO_new_mem_buf BIO_read BIO_s_mem BIO_write CRYPTO_set_mem_functions ERR_clear_error ERR_error_string ERR_free_strings ERR_get_error ERR_load_BIO_strings ERR_peek_last_error ERR_print_errors_fp ERR_remove_state EVP_DigestFinal_ex EVP_DigestInit_ex EVP_DigestSignFinal EVP_DigestSignInit EVP_DigestUpdate EVP_MD_CTX_cleanup EVP_MD_CTX_free EVP_MD_CTX_new EVP_MD_size EVP_PKEY_CTX_free EVP_PKEY_CTX_new EVP_PKEY_free EVP_PKEY_sign_init EVP_dss EVP_dss1 EVP_ecdsa EVP_md2 EVP_md4 EVP_md5 EVP_md_null EVP_mdc2 EVP_ripemd160 EVP_sha EVP_sha1 EVP_sha224 EVP_sha256 EVP_sha384 EVP_sha512 EVP_whirlpool HMAC MD5 MD5_Final MD5_Init MD5_Transform MD5_Update OPENSSL_config OpenSSL_version_num PEM_read_RSAPrivateKey PEM_read_RSAPublicKey PEM_read_RSA_PUBKEY PEM_read_bio_PrivateKey PEM_read_bio_RSAPrivateKey PEM_read_bio_RSAPublicKey PEM_read_bio_RSA_PUBKEY RSA_free RSA_private_decrypt RSA_private_encrypt RSA_public_decrypt RSA_public_encrypt RSA_size RSA_verify SSLeay X509_OBJECT_free X509_OBJECT_new X509_STORE_add_cert X509_STORE_free X509_STORE_lock X509_STORE_new X509_STORE_set_flags X509_STORE_set_purpose X509_STORE_set_trust X509_STORE_unlock X509_STORE_up_ref X509_get_issuer_name d2i_X509 i2d_X509""".split(' ').toSet(),
        "libpcre.so.3" to """pcre_assign_jit_stack pcre_compile pcre_compile2 pcre_config pcre_copy_named_substring pcre_copy_substring pcre_dfa_exec pcre_exec pcre_free_study pcre_free_substring pcre_free_substring_list pcre_fullinfo pcre_get_named_substring pcre_get_stringnumber pcre_get_stringtable_entries pcre_get_substring pcre_get_substring_list pcre_jit_exec pcre_jit_free_unused_memory pcre_jit_stack_alloc pcre_jit_stack_free pcre_maketables pcre_pattern_to_host_byte_order pcre_refcount pcre_study pcre_version""".split(' ').toSet(),
        "libssl.so.3" to """BIO_new_ssl_connect OPENSSL_add_all_algorithms_conf OPENSSL_init_ssl OPENSSL_sk_num OPENSSL_sk_value SSL_CTX_callback_ctrl SSL_CTX_check_private_key SSL_CTX_ctrl SSL_CTX_free SSL_CTX_get_ex_data SSL_CTX_get_ex_new_index SSL_CTX_load_verify_locations SSL_CTX_new SSL_CTX_set_alpn_protos SSL_CTX_set_alpn_select_cb SSL_CTX_set_cipher_list SSL_CTX_set_ciphersuites SSL_CTX_set_ex_data SSL_CTX_set_next_proto_select_cb SSL_CTX_set_next_protos_advertised_cb SSL_CTX_set_psk_client_callback SSL_CTX_set_psk_server_callback SSL_CTX_set_session_id_context SSL_CTX_set_verify SSL_CTX_use_PrivateKey_file SSL_CTX_use_certificate_chain_file SSL_CTX_use_certificate_file SSL_CTX_use_psk_identity_hint SSL_accept SSL_connect SSL_ctrl SSL_do_handshake SSL_free SSL_get0_alpn_selected SSL_get0_next_proto_negotiated SSL_get0_verified_chain SSL_get1_peer_certificate SSL_get_SSL_CTX SSL_get_error SSL_get_peer_certificate SSL_get_psk_identity SSL_get_servername SSL_get_shutdown SSL_get_verify_result SSL_in_init SSL_library_init SSL_load_error_strings SSL_new SSL_peek SSL_pending SSL_read SSL_select_next_proto SSL_set_SSL_CTX SSL_set_accept_state SSL_set_alpn_protos SSL_set_bio SSL_set_connect_state SSL_set_fd SSL_set_shutdown SSL_shutdown SSL_state SSL_write SSLv23_client_method SSLv23_method SSLv23_server_method SSLv2_method SSLv3_method TLS_client_method TLS_method TLS_server_method TLSv1_method X509_NAME_get_text_by_NID X509_NAME_oneline X509_check_host X509_free X509_get_subject_name""".split(' ').toSet()
    )

    private fun loadIdentifiers(inventory:Inventory,relative:String,image:NativeImage,active:()->Unit):Set<String> {
        val file=File(inventory.root,relative);val before=snapshot(file);var total=0L;val run=StringBuilder();val result=linkedSetOf<String>()
        fun flush() {
            val value=run.toString();run.setLength(0)
            if(loaderPatterns.keys.any { value.contains(it) } || value.matches(Regex("(?:SSL|TLS|OPENSSL|OpenSSL|SSLeay|BIO|ERR|EVP|X509|CRYPTO|RSA|PEM|MD5|HMAC|d2i|i2d|pcre)[A-Za-z0-9_]*"))) {
                require(result.size<4096);result+=value
            }
        }
        FileChannel.open(file.toPath(),StandardOpenOption.READ,NOFOLLOW_LINKS).use { input ->
            val buffer=ByteBuffer.allocate(65536);require(image.fileBackedRanges.isNotEmpty())
            for((offset,length) in image.fileBackedRanges) {
                require(offset>=0 && length>=0 && offset<=before.size() && length<=before.size()-offset)
                input.position(offset);var remaining=length
                while(remaining>0) { active();buffer.clear();buffer.limit(minOf(remaining,buffer.capacity().toLong()).toInt());val n=input.read(buffer);require(n>0);remaining-=n;total=Math.addExact(total,n.toLong());require(total<=MAX_TREE_BYTES)
                    for(i in 0 until n) { val value=buffer.array()[i].toInt() and 255
                        if(value in 33..126) { if(run.length<4096)run.append(value.toChar()) else { require(loaderPatterns.keys.none { run.contains(it) });run.setLength(0) } }
                        else flush()
                    }
                };flush()
            };require(input.size()==before.size() && sameState(before,snapshot(file)))
        };return result
    }

    /** Observed recursive DT_NEEDED/version closure plus source-reviewed runtime loads, resolved only in the owned guest. */
    fun requireExecutionClosure(inventories: List<Inventory>, guestRoot: File, sourceLoads: Set<String> = emptySet(), active: () -> Unit = {}): Closure {
        require(inventories.size in 1..3 && sourceLoads.size <= 16); requireDirectory(guestRoot)
        val observed = linkedMapOf<String,NativeImage>(); val paths = linkedMapOf<String,String>(); val hashes = linkedMapOf<String,String>()
        var total = 0L
        fun resolve(name: String): NativeImage {
            require(name.matches(Regex("[A-Za-z0-9_+.-]{1,180}")) && name !in setOf(".",".."))
            observed[name]?.let { return it }
            require(observed.size < 64)
            val candidates = (if (name == "ld-linux-aarch64.so.1") listOf(INTERPRETER, "/usr/lib/aarch64-linux-gnu/$name", "/lib/aarch64-linux-gnu/$name")
                else listOf("/usr/lib/aarch64-linux-gnu/$name", "/lib/aarch64-linux-gnu/$name"))
                .mapNotNull { resolveGuestFile(guestRoot, it, active) }.distinctBy { it.absolutePath }
            val file = candidates.singleOrNull() ?: error("Nim native dependency unresolved or ambiguous: $name")
            val role = when(name) { "ld-linux-aarch64.so.1" -> NativeRole.GUEST_LOADER; "libc.so.6" -> NativeRole.GUEST_LIBC; else -> NativeRole.SHARED_LIBRARY }
            val parsed = inspectElf(file,name,role,active)
            require(parsed.soname == name && !parsed.originSearch) { "Nim guest SONAME/search path changed" }
            total = Math.addExact(total, file.length()); require(total <= 512L*1024*1024)
            observed[name] = parsed; paths[name] = file.absolutePath
            hashes[file.relativeTo(guestRoot).invariantSeparatorsPath] = measure(file,512L*1024*1024,active).second
            return parsed
        }
        val visiting = hashSetOf<String>(); val done = hashSetOf<String>()
        fun visit(image: NativeImage) {
            active(); val key = image.relativePath
            if (key in done || !visiting.add(key)) return
            require(image.versionNeeds.keys.all { it in image.needed })
            for (name in image.needed) {
                val dependency = resolve(name)
                require(dependency.versionDefinitions.containsAll(image.versionNeeds[name].orEmpty())) { "Nim required symbol version unresolved: $name" }
                visit(dependency)
            }
            visiting.remove(key); done += key
        }
        val shellFile=requireNotNull(resolveGuestFile(guestRoot,"/bin/sh",active)) { "Nim fixed guest shell is missing" }
        val shellPath=shellFile.relativeTo(guestRoot).invariantSeparatorsPath
        require(shellPath in setOf("bin/sh","usr/bin/sh","bin/dash","usr/bin/dash")) { "Nim fixed guest shell identity is unreviewed" }
        val shell=inspectElf(shellFile,shellPath,NativeRole.EXECUTABLE,active)
        require(shell.soname==null && !shell.originSearch)
        hashes[shellPath]=measure(shellFile,16L*1024*1024,active).second
        inventories.forEach { it.native.values.forEach(::visit) };visit(shell)
        (sourceLoads + "ld-linux-aarch64.so.1").forEach { visit(resolve(it)) }
        require(resolveGuestFile(guestRoot,INTERPRETER,active)?.absolutePath == paths["ld-linux-aarch64.so.1"])
        // Resolve strong ELF imports against actual admitted providers, not version labels alone.
        fun scope(image:NativeImage):List<NativeImage> {
            val result=linkedMapOf<String,NativeImage>()
            fun add(value:NativeImage) { if(result.putIfAbsent(value.relativePath,value)==null)value.needed.forEach { add(resolve(it)) } }
            add(image);return result.values.toList()
        }
        for(image in inventories.flatMap { it.native.values }+observed.values+shell) {
            val providers=scope(image)
            for(symbol in image.imports.filterNot { it.weak }) {
                active()
                val candidates=if(symbol.provider==null)providers else scope(resolve(symbol.provider))
                require(candidates.any { candidate -> candidate.exports.any { export ->
                    export.name==symbol.name && (if(symbol.version==null)export.defaultVersion else export.version==symbol.version) &&
                        (symbol.type==0 || symbol.type==export.type || symbol.type==2 && export.type==10)
                } }) { "Nim native mandatory ELF import unresolved: ${image.relativePath}:${symbol.name}" }
            }
        }
        if(sourceLoads.isNotEmpty()) {
            val demanded=ordinaryMandatoryExports.mapValues { it.value.toMutableSet() }.toMutableMap()
            for(inventory in inventories) for((relative,image) in inventory.native) {
                if(relative==LocalUbuntuNimHelperPolicy.ENTRY)continue
                for(identifier in loadIdentifiers(inventory,relative,image,active)) {
                    val provider=ordinaryDirectExports.entries.singleOrNull { identifier in it.value }?.key ?: continue
                    if(identifier !in nullableCompatibilityExports && identifier !in legacyCompatibilityExports)
                        demanded.getOrPut(provider) { linkedSetOf() }.add(identifier)
                }
            }
            for((library,symbols) in demanded) {
                val providers=scope(resolve(library))
                for(symbol in symbols) require(providers.any { candidate -> candidate.exports.any {
                    it.name==symbol && it.type in setOf(2,10) && it.defaultVersion
                } }) { "Nim ordinary built-in mandatory export unresolved: $library:$symbol" }
            }
        }
        requireGuestLoaderConfiguration(guestRoot,paths,active)
        for (path in listOf("/etc/ld.so.cache","/etc/ld.so.preload")) resolveGuestFile(guestRoot,path,active)?.let {
            hashes[path.removePrefix("/")] = measure(it,4L*1024*1024,active).second
        }
        val material = hashes.toSortedMap().entries.joinToString("\n") { "${it.key}\u0000${it.value}" }
        return Closure(hashes.toMap(),sha256(material))
    }
    internal fun resolveGuestFile(root: File, guestPath: String, active: () -> Unit = {}, directory: Boolean = false): File? {
        require(guestPath.startsWith('/') && '\\' !in guestPath && '\u0000' !in guestPath)
        requireDirectory(root)
        val base = root.absoluteFile.toPath().normalize(); var remaining = guestPath.split('/').filter { it.isNotEmpty() }.toMutableList()
        val done = mutableListOf<String>(); var links = 0; var steps = 0
        while (remaining.isNotEmpty()) {
            active(); require(++steps <= 256 && remaining.size + done.size <= 64)
            val part = remaining.removeAt(0)
            if (part == ".") continue
            if (part == "..") { require(done.isNotEmpty()) { "Nim guest link escapes guest root" }; done.removeAt(done.lastIndex); continue }
            requireName(part)
            val path = done.fold(base) { at, p -> at.resolve(p) }.resolve(part)
            if (Files.isSymbolicLink(path)) {
                require(++links <= 40) { "Nim guest link cycle" }
                val target = Files.readSymbolicLink(path).toString(); require(target.length in 1..4096 && '\\' !in target && '\u0000' !in target)
                if (target.startsWith('/')) done.clear()
                remaining = (target.split('/').filter { it.isNotEmpty() } + remaining).toMutableList()
            } else {
                if (!Files.exists(path, NOFOLLOW_LINKS)) return null
                if (remaining.isNotEmpty()) require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Nim guest native ancestor is not a directory" }
                else require(if (directory) Files.isDirectory(path, NOFOLLOW_LINKS) else Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Nim guest native path has an unexpected type" }
                done += part
            }
        }
        return done.fold(base) { at, p -> at.resolve(p) }.toFile()
    }

    /** All matching cache targets must resolve to the inspected file; unknown/hwcaps variants are not
     * guessed. */
    private fun requireGuestLoaderConfiguration(root: File, expected: Map<String, String>, active: () -> Unit) {
        resolveGuestFile(root, "/etc/ld.so.preload", active)?.let {
            require(readBytes(it, 16_384, active, allowEmpty = true).all { byte -> byte.toInt().toChar() in " \t\r\n" }) {
                "Nim guest /etc/ld.so.preload contains unreviewed loader code"
            }
        }
        for (base in listOf("/usr/lib/aarch64-linux-gnu", "/lib/aarch64-linux-gnu", "/usr/lib", "/lib")) {
            for (suffix in listOf("glibc-hwcaps", "tls")) resolveGuestFile(root, "$base/$suffix", active, directory = true)?.let { directory ->
                Files.newDirectoryStream(directory.toPath()).use { require(!it.iterator().hasNext()) { "Nim guest loader capability directory requires exact review: $base/$suffix" } }
            }
        }
        val file = resolveGuestFile(root, "/etc/ld.so.cache", active) ?: return
        val bytes = readBytes(file, 4 * 1024 * 1024, active)
        require(bytes.size >= 48 && bytes.copyOfRange(0, 20).contentEquals("glibc-ld.so.cache1.1".toByteArray(Charsets.US_ASCII))) { "Nim guest loader cache format is unreviewed" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun u32(at: Int): Long = b.getInt(at).toLong() and 0xffffffffL
        val count = u32(20); val stringSize = u32(24); val stringsAt = 48 + count * 24
        require(count <= 8192 && stringSize in 1..4_194_304 && stringsAt <= bytes.size && stringSize <= bytes.size - stringsAt &&
            (bytes[28].toInt() and 255) in setOf(0, 2) && (29..31).all { bytes[it].toInt() == 0 } && (36..47).all { bytes[it].toInt() == 0 }) { "Nim guest loader cache bounds or byte order invalid" }
        val stringsEnd = stringsAt + stringSize
        fun text(index: Long): String {
            require(index in stringsAt until stringsEnd); var end = index.toInt()
            while (end < stringsEnd && bytes[end].toInt() != 0 && end - index <= 4096) end++
            require(end < stringsEnd && bytes[end].toInt() == 0)
            return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, index.toInt(), end - index.toInt())).toString()
        }
        repeat(count.toInt()) { index ->
            active(); val at = 48 + index * 24; val name = text(u32(at + 4)); val path = text(u32(at + 8))
            if (name in expected) {
                require(b.getInt(at) == 0x0a03 && u32(at + 12) == 0L && b.getLong(at + 16) == 0L) { "Nim guest loader cache ABI/hwcaps variant is unreviewed: $name" }
                require(path.startsWith('/') && resolveGuestFile(root, path, active)?.absolutePath == expected[name]) { "Nim guest loader cache redirects inspected dependency: $name" }
            }
        }
        val extension = u32(32)
        if (extension != 0L) {
            require(extension >= stringsEnd && extension % 4 == 0L && extension + 8 <= bytes.size)
            val at = extension.toInt(); require(b.getInt(at) == -358342284)
            val sections = u32(at + 4); require(sections <= 16 && extension + 8 + sections * 16 <= bytes.size)
            val seen = hashSetOf<Long>()
            repeat(sections.toInt()) { i ->
                val pos = at + 8 + i * 16; val tag = u32(pos); val offset = u32(pos + 8); val size = u32(pos + 12)
                require(seen.add(tag) && tag == 0L && u32(pos + 4) == 0L && offset >= stringsEnd && offset <= bytes.size && size <= bytes.size - offset) { "Nim guest loader cache extension is unreviewed" }
            }
        } else require(stringsEnd == bytes.size.toLong()) { "Nim guest loader cache trailing data is unreviewed" }
    }

    internal fun inspectElf(file: File, relative: String, role: NativeRole, active: () -> Unit = {}): NativeImage {
        val before = snapshot(file); require(before.size() in 64..MAX_TREE_BYTES)
        FileChannel.open(file.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS).use { input ->
            val length = input.size(); require(length == before.size())
            fun read(at: Long, count: Int): ByteBuffer {
                active(); require(at >= 0 && count >= 0 && at <= length && count <= length - at) { "Nim ELF file range invalid: $relative" }
                val b = ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN); input.position(at)
                while (b.hasRemaining()) { active(); require(input.read(b) > 0) { "Nim truncated ELF: $relative" } }; return b
            }
            val h = read(0, 64)
            require(h.getInt(0) == 0x464c457f && h.get(4).toInt() == 2 && h.get(5).toInt() == 1 && h.get(6).toInt() == 1 &&
                h.getShort(18).toInt() == 183 && h.getInt(20) == 1 && h.getShort(52).toInt() == 64) { "Nim native member must be ELF64 little-endian AArch64: $relative" }
            val type = h.getShort(16).toInt(); val entry = h.getLong(24); val phoff = h.getLong(32)
            val phnum = h.getShort(56).toInt() and 65535
            require(phoff >= 64 && phnum in 1..512 && h.getShort(54).toInt() == 56 && phoff <= length && phnum * 56L <= length - phoff)
            require(if (role == NativeRole.EXECUTABLE) type in setOf(2, 3) && entry > 0 else type == 3 && (role != NativeRole.SHARED_LIBRARY || entry == 0L)) { "Nim native executable/library role mismatch: $relative" }
            data class Segment(val type: Int, val flags: Int, val offset: Long, val address: Long, val bytes: Long, val memory: Long)
            val segments = (0 until phnum).map { i ->
                val p = read(phoff + i * 56L, 56); val off = p.getLong(8); val va = p.getLong(16); val size = p.getLong(32); val mem = p.getLong(40); val align = p.getLong(48)
                require(off >= 0 && size >= 0 && off <= length && size <= length - off && va >= 0 && mem >= size && va <= Long.MAX_VALUE - mem)
                require(align >= 0 && (align <= 1 || align and (align - 1) == 0L))
                if (p.getInt(0) == 1 && align > 1) require(off % align == va % align)
                Segment(p.getInt(0), p.getInt(4), off, va, size, mem)
            }
            val loads = segments.filter { it.type == 1 }; require(loads.isNotEmpty() && loads.any { it.flags and 1 != 0 })
            loads.forEach { require(it.flags and 7 == it.flags && it.flags and 3 != 3) { "Nim ELF writable executable load is unreviewed" } }
            val sorted = loads.sortedBy { it.address }
            sorted.zipWithNext().forEach { (a, b) -> require(a.address + a.memory <= b.address) { "Nim overlapping ELF load segments" } }
            if (entry != 0L) require(loads.count { it.flags and 1 != 0 && entry >= it.address && entry - it.address < it.bytes } == 1) { "Nim ELF entry is not uniquely file-backed executable code" }
            fun map(va: Long, size: Long): Long {
                require(va >= 0 && size > 0)
                val segment = loads.singleOrNull { va >= it.address && va - it.address <= it.bytes && size <= it.bytes - (va - it.address) }
                    ?: error("Nim ELF dynamic range has no unique file-backed load: $relative")
                return segment.offset + va - segment.address
            }
            val interp = segments.filter { it.type == 3 }
            if (role in setOf(NativeRole.EXECUTABLE, NativeRole.GUEST_LIBC)) {
                val p = interp.singleOrNull() ?: error("Nim ELF requires one glibc interpreter")
                require(p.bytes == INTERPRETER.length + 1L && read(p.offset, p.bytes.toInt()).array().contentEquals((INTERPRETER + '\u0000').toByteArray(Charsets.US_ASCII)))
            } else require(interp.isEmpty()) { "Nim shared/loader role cannot contain an interpreter" }
            val dyn = segments.singleOrNull { it.type == 2 } ?: error("Nim ELF requires one dynamic table")
            require(dyn.bytes in 16..65536 && dyn.bytes % 16 == 0L && map(dyn.address, dyn.bytes) == dyn.offset)
            val tags = linkedMapOf<Long, MutableList<Long>>(); var ended = false
            for (at in 0 until dyn.bytes step 16) {
                val d = read(dyn.offset + at, 16); val tag = d.getLong(0); val value = d.getLong(8)
                if (ended) require(tag == 0L && value == 0L) { "Nim ELF dynamic data follows DT_NULL" }
                else if (tag == 0L) { require(value == 0L); ended = true }
                else { require(value >= 0); tags.getOrPut(tag) { mutableListOf() }.add(value) }
            }
            require(ended); tags.filterKeys { it != 1L }.values.forEach { require(it.size == 1) { "Nim duplicate ELF dynamic singleton" } }
            // Finite ELF64/AArch64 ABI metadata only. DT_CONFIG, audit, filters and unknown extensions fail
            // closed.
            val standardTags = (1L..30L).toSet() + (32L..37L).toSet()
            val gnuTags = setOf(0x6ffffef5L, 0x6ffffff0L, 0x6ffffff9L, 0x6ffffffaL, 0x6ffffffbL,
                0x6ffffffcL, 0x6ffffffdL, 0x6ffffffeL, 0x6fffffffL)
            val aarch64Tags = setOf(0x70000001L, 0x70000003L, 0x70000005L)
            require(tags.keys.all { it in standardTags || it in gnuTags || it in aarch64Tags }) { "Nim ELF loader tag is unreviewed" }
            fun tag(n: Long): Long? = tags[n]?.single()
            val stringSize = tag(10) ?: error("Nim ELF string table length missing")
            require(stringSize in 1..4_194_304)
            val stringAt = map(tag(5) ?: error("Nim ELF string table missing"), stringSize)
            val strings = read(stringAt, stringSize.toInt()).array()
            fun stringAt(index: Long): String {
                require(index in 0 until stringSize); var end = index.toInt()
                while (end < strings.size && strings[end].toInt() != 0 && end - index < 4096) end++
                require(end < strings.size && strings[end].toInt() == 0)
                val bytes = strings.copyOfRange(index.toInt(), end)
                require(bytes.all { (it.toInt() and 255) in 32..126 }) { "Nim ELF loader string is not bounded ASCII" }
                return bytes.toString(Charsets.US_ASCII)
            }
            fun soname(value: String): String { require(value.matches(Regex("[A-Za-z0-9_+.-]{1,180}")) && value != "." && value != ".."); return value }
            val needed = tags[1].orEmpty().map { soname(stringAt(it)) }
            require(needed.toSet().size == needed.size)
            val name = tag(14)?.let { soname(stringAt(it)) }
            if (role == NativeRole.SHARED_LIBRARY && relative in nativeLibraries) require(name == null || name == relative)
            require(!(tags.containsKey(15) && tags.containsKey(29))) { "Nim ambiguous ELF search tags" }
            val search = (tag(15) ?: tag(29))?.let(::stringAt)
            require(search == null || search == "\$ORIGIN" || search == "\${ORIGIN}") { "Nim ELF has external or unreviewed loader search path: $relative" }
            val versionNeeds = linkedMapOf<String, MutableSet<String>>(); val definitions = linkedSetOf<String>()
            val needIndices=linkedMapOf<Int,Pair<String,String>>();val definitionIndices=linkedMapOf<Int,String>()
            fun versions(addressTag: Long, countTag: Long, need: Boolean) {
                val addr = tag(addressTag); val count = tag(countTag)
                require((addr == null) == (count == null)); if (addr == null) return
                require(count != null && count in 1..1024)
                var va: Long = addr
                val visited = hashSetOf<Long>()
                repeat(count.toInt()) { index ->
                    require(visited.add(va)); val p = read(map(va, if (need) 16 else 20), if (need) 16 else 20)
                    require(p.getShort(0).toInt() == 1)
                    val auxCount = p.getShort(if (need) 2 else 6).toInt() and 65535; require(auxCount in 1..1024)
                    val owner = if (need) soname(stringAt(p.getInt(4).toLong() and 0xffffffffL)) else ""
                    val aux = p.getInt(if (need) 8 else 12).toLong() and 0xffffffffL
                    val next = p.getInt(if (need) 12 else 16).toLong() and 0xffffffffL
                    require(aux >= if (need) 16 else 20)
                    var av = Math.addExact(va, aux); val auxSeen = hashSetOf<Long>()
                    repeat(auxCount) { ai ->
                        require(auxSeen.add(av)); val a = read(map(av, if (need) 16 else 8), if (need) 16 else 8)
                        val version = stringAt(a.getInt(if (need) 8 else 0).toLong() and 0xffffffffL)
                        require(version.matches(Regex("[A-Za-z0-9_.+-]{1,180}")))
                        if (need) {
                            versionNeeds.getOrPut(owner) { linkedSetOf() }.add(version)
                            val vi=a.getShort(6).toInt() and 0x7fff;require(vi>1 && needIndices.put(vi,owner to version)==null)
                        } else {
                            definitions += version
                            if(ai==0) { val vi=p.getShort(4).toInt() and 0x7fff;require(vi>0 && definitionIndices.put(vi,version)==null) }
                        }
                        val an = a.getInt(if (need) 12 else 4).toLong() and 0xffffffffL
                        if (ai == auxCount - 1) require(an == 0L) else { require(an >= if (need) 16 else 8); av = Math.addExact(av, an) }
                    }
                    if (index == count.toInt() - 1) require(next == 0L) else { require(next >= if (need) 16 else 20); va = Math.addExact(va, next) }
                }
            }
            versions(0x6ffffffeL, 0x6fffffffL, true); versions(0x6ffffffcL, 0x6ffffffdL, false)
            val exports=mutableListOf<NativeSymbol>();val imports=mutableListOf<NativeSymbol>()
            val symbols=tag(6);val symbolWidth=tag(11)
            require((symbols==null)==(symbolWidth==null))
            if(symbols!=null) {
                require(symbolWidth==24L)
                var sysvData:ByteBuffer?=null;var sysvBuckets=0
                var gnuBuckets:ByteBuffer?=null;var gnuBloom:ByteBuffer?=null;var gnuOffset=0L;var gnuShift=0;var gnuChainVa=0L
                val sysvCount=tag(4)?.let { va ->
                    val at=read(map(va,8),8);val buckets=at.getInt(0).toLong() and 0xffffffffL;val count=at.getInt(4).toLong() and 0xffffffffL
                    require(buckets in 1..262144 && count in 1..262144)
                    sysvData=read(map(va,8+4*(buckets+count)),(8+4*(buckets+count)).toInt());sysvBuckets=buckets.toInt();count.toInt()
                }
                val gnuCount=tag(0x6ffffef5L)?.let { va ->
                    val at=read(map(va,16),16);val buckets=at.getInt(0).toLong() and 0xffffffffL;val offset=at.getInt(4).toLong() and 0xffffffffL
                    val bloom=at.getInt(8).toLong() and 0xffffffffL;val shift=at.getInt(12).toLong() and 0xffffffffL
                    require(buckets in 1..262144 && offset in 1..262144 && bloom in 1..65536 && shift<64)
                    val bucketVa=Math.addExact(va,16+8*bloom);val bucketData=read(map(bucketVa,4*buckets),(4*buckets).toInt())
                    val chainVa=Math.addExact(bucketVa,4*buckets);var maximum=offset-1;var work=0
                    gnuBuckets=bucketData;gnuBloom=read(map(va+16,8*bloom),(8*bloom).toInt());gnuOffset=offset;gnuShift=shift.toInt();gnuChainVa=chainVa
                    repeat(buckets.toInt()) { i ->
                        var index=bucketData.getInt(i*4).toLong() and 0xffffffffL
                        if(index!=0L) {
                            require(index>=offset && index<262144)
                            while(true) {
                                active();require(++work<=524288 && index<262144)
                                val chain=read(map(Math.addExact(chainVa,4*(index-offset)),4),4).getInt(0)
                                maximum=maxOf(maximum,index);if(chain and 1!=0)break;index++
                            }
                        }
                    };(maximum+1).toInt()
                }
                val count=sysvCount?:gnuCount?:error("Nim dynamic symbol table requires a bounded hash table")
                if(sysvCount!=null && gnuCount!=null) require(sysvCount==gnuCount)
                val table=read(map(symbols,24L*count),24*count)
                val versym=tag(0x6ffffff0L)?.let { read(map(it,2L*count),2*count) }
                var lookupWork=0
                fun hashAccessible(symbol:String,index:Int):Boolean {
                    // glibc chooses GNU lookup when present; a valid SYSV table cannot rescue a malformed GNU
                    // bloom/bucket/chain or make its unreachable export usable.
                    sysvData?.takeIf { gnuBuckets==null }?.let { data ->
                        var hash=0L;for(c in symbol) { hash=(hash shl 4)+c.code;val high=hash and 0xf0000000L;if(high!=0L)hash=hash xor (high ushr 24);hash=hash and high.inv() }
                        var current=data.getInt(8+4*(hash%sysvBuckets).toInt()).toLong() and 0xffffffffL;val seen=hashSetOf<Long>()
                        while(current!=0L) { active();require(++lookupWork<=4_194_304 && current<count && seen.add(current))
                            if(current==index.toLong())return true
                            current=data.getInt(8+4*sysvBuckets+4*current.toInt()).toLong() and 0xffffffffL
                        }
                    }
                    gnuBuckets?.let { buckets ->
                        var hash=5381L;for(c in symbol)hash=((hash shl 5)+hash+c.code) and 0xffffffffL
                        val bloom=requireNotNull(gnuBloom);val word=bloom.getLong(8*((hash/64)%(bloom.capacity()/8)).toInt())
                        val mask=(1L shl (hash%64).toInt()) or (1L shl ((hash ushr gnuShift)%64).toInt())
                        if(word and mask!=mask)return@let
                        var current=buckets.getInt(4*(hash%(buckets.capacity()/4)).toInt()).toLong() and 0xffffffffL
                        if(current==0L)return@let
                        require(current>=gnuOffset && current<count)
                        while(true) { active();require(++lookupWork<=4_194_304 && current<count)
                            val chain=read(map(gnuChainVa+4*(current-gnuOffset),4),4).getInt(0).toLong() and 0xffffffffL
                            if(current==index.toLong() && (chain or 1L)==(hash or 1L))return true
                            if(chain and 1L!=0L)break;current++
                        }
                    };return false
                }
                repeat(count) { index ->
                    active();val at=index*24;val str=table.getInt(at).toLong() and 0xffffffffL;val info=table.get(at+4).toInt() and 255
                    val binding=info ushr 4;val kind=info and 15;val visibility=table.get(at+5).toInt() and 255
                    val section=table.getShort(at+6).toInt() and 65535;val value=table.getLong(at+8);val size=table.getLong(at+16)
                    require(value>=0 && size>=0 && visibility in 0..3 && kind in setOf(0,1,2,3,4,5,6,10) && binding in setOf(0,1,2,10))
                    val symbol=stringAt(str);if(symbol.isEmpty()||binding==0)return@repeat
                    val vi=versym?.getShort(index*2)?.toInt()?.and(65535)?:1;val versionIndex=vi and 0x7fff
                    if(section==0) {
                        val owner=if(versionIndex>1)requireNotNull(needIndices[versionIndex]) else null
                        require(owner==null || owner.first in needed)
                        imports+=NativeSymbol(symbol,kind,owner?.second,owner?.first,true,binding==2)
                    } else if(visibility in setOf(0,3) && versionIndex!=0) {
                        require(section<0xff00 || section==0xfff1)
                        if(kind in setOf(2,10)) require(loads.any { it.flags and 1!=0 && value>=it.address && value-it.address<it.bytes })
                        val version=if(versionIndex>1)requireNotNull(definitionIndices[versionIndex]) else null
                        require(hashAccessible(symbol,index)) { "Nim exported symbol is absent from its loader hash lookup: $symbol" }
                        exports+=NativeSymbol(symbol,kind,version,null,vi and 0x8000==0,binding==2)
                    }
                }
            } else require(tag(4)==null && tag(0x6ffffef5L)==null && tag(0x6ffffff0L)==null) { "Nim orphan dynamic symbol metadata" }
            require(input.size() == length && sameState(before, snapshot(file))) { "Nim ELF changed during inspection" }
            return NativeImage(relative, role, needed, name, search != null, versionNeeds.mapValues { it.value.toSet() }, definitions, loads.map { it.offset to it.bytes },exports,imports)
        }
    }

    internal fun requireRelative(value: String) {
        require(value.length in 1..4096 && !value.startsWith('/') && '\\' !in value && ':' !in value)
        val parts = value.split('/'); require(parts.size <= 32); parts.forEach(::requireName)
    }
    private fun requireName(value: String) { require(value.isNotEmpty() && value !in setOf(".", "..") && value.length <= 255 && value.none { it.code < 32 || it.code == 127 || it == '/' || it == '\\' || it == ':' }) }
    internal fun requireDirectory(file: File) {
        var at: File? = file.absoluteFile
        while (at != null) { require(Files.isDirectory(at.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(at.toPath())) { "Nim directory missing or linked: $at" }; at = at.parentFile }
    }
    private fun snapshot(file: File): BasicFileAttributes {
        requireDirectory(requireNotNull(file.absoluteFile.parentFile))
        return Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).also { require(it.isRegularFile && !it.isSymbolicLink) }
    }
    private fun sameState(a: BasicFileAttributes, b: BasicFileAttributes) = a.fileKey() == b.fileKey() && a.size() == b.size() && a.lastModifiedTime() == b.lastModifiedTime()
    internal fun measure(file: File, maximum: Long, active: () -> Unit = {}): Pair<Long, String> {
        val before = snapshot(file); require(before.size() in 0..maximum); val hash = MessageDigest.getInstance("SHA-256"); var size = 0L
        FileChannel.open(file.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS).use { input ->
            val bytes = ByteBuffer.allocate(65536)
            while (true) { active(); bytes.clear(); val n = input.read(bytes); if (n < 0) break; require(n > 0); size = Math.addExact(size, n.toLong()); require(size <= maximum); hash.update(bytes.array(), 0, n) }
            require(input.size() == before.size() && size == before.size() && sameState(before, snapshot(file)))
        }
        return size to hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    internal fun readText(file: File, max: Int, active: () -> Unit = {}): String =
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(readBytes(file, max, active, allowEmpty = true))).toString()
    private fun readBytes(file: File, max: Int, active: () -> Unit, allowEmpty: Boolean = false): ByteArray {
        val before = snapshot(file); require(before.size() in (if (allowEmpty) 0L else 1L)..max.toLong())
        val b = ByteBuffer.allocate(before.size().toInt())
        FileChannel.open(file.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS).use { input -> while (b.hasRemaining()) { active(); require(input.read(b) > 0) }; require(input.size() == before.size()) }
        require(sameState(before, snapshot(file))); return b.array()
    }
    private fun readPrefix(file: File, length: Int): ByteArray = FileChannel.open(file.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS).use { input ->
        val b = ByteBuffer.allocate(minOf(input.size(), length.toLong()).toInt()); while (b.hasRemaining()) require(input.read(b) > 0); b.array()
    }
}
