package com.baystudio.droide.core

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.sync.Mutex

// Use same-filesystem replacement for atomic activation.
internal object LocalPackageInstallJournal {
    // Installers take this before the registry gate. Refresh/removal must use the same order.
    val transactionMutex = Mutex()
    data class Receipt(val activationId: String?)
    class Activation internal constructor(val replacedExistingFinal: Boolean)

    data class Paths(
        val stage: File,
        val final: File,
        val previous: File,
        val legacyStage: File? = null,
        val legacyPrevious: File? = null,
        val vueHealthHold: File? = null,
        val csharpHealthHold: File? = null,
        val tomlHealthHold: File? = null,
        val graphqlHealthHold: File? = null,
        val elpHealthHold: File? = null,
        val elpActivationJournal: File? = null,
        val graphqlActivationJournal: File? = null,
    )

    fun paths(root: String, familyId: String, version: String, kind: String): Paths {
        require(familyId.matches(Regex("[A-Za-z0-9._+-]{1,120}"))) { "Unsafe package family for local transaction" }
        require(version.matches(Regex("[A-Za-z0-9._+-]{1,120}"))) { "Unsafe package version for local transaction" }
        require(familyId != "." && familyId != ".." && version != "." && version != "..") {
            "Unsafe package transaction path segment"
        }
        val suffix = when (kind) {
            LocalUbuntuElpPolicy.KIND -> "local-ubuntu-elp"
            LocalUbuntuCSharpPolicy.KIND -> "local-ubuntu-csharp"
            LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW -> "local-ubuntu-raw"
            LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE -> "local-ubuntu-tree"
            LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE -> "local-ubuntu-vendor"
            LocalManagedPackageMetadata.KIND_UBUNTU_BUNDLE_ALIAS -> "local-ubuntu-alias"
            LocalManagedPackageMetadata.KIND_UBUNTU_APT -> "local-ubuntu-apt"
            LocalManagedPackageMetadata.KIND_UBUNTU_NPM -> "local-ubuntu-npm"
            LocalManagedPackageMetadata.KIND_UBUNTU_CMAKE -> "local-ubuntu-cmake"
            LocalManagedPackageMetadata.KIND_UBUNTU_RUBY -> "local-ubuntu-ruby"
            LocalManagedPackageMetadata.KIND_ANDROID_WORKSTATION -> "local-android-workstation"
            LocalManagedPackageMetadata.KIND_ANDROID_SDK_COMPONENT -> "local-android-sdk-component"
            else -> error("Unsupported local package transaction kind")
        }
        val safeFamily = familyId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeVersion = version.replace(Regex("[^A-Za-z0-9._+-]"), "_")
        val identity = MessageDigest.getInstance("SHA-256")
            .digest("$familyId@$version:$suffix".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(24)
        val name = "${safeFamily.take(48)}-${safeVersion.take(48)}-$suffix-$identity"
        val legacyName = "$safeFamily-$safeVersion-$suffix".takeIf { it.length <= 255 }
        return Paths(
            stage = File(root, "packages/.staging/$name"),
            final = File(root, "packages/$familyId/$safeVersion/arm64-v8a"),
            previous = File(root, "packages/.previous/$name"),
            legacyStage = legacyName?.let { File(root, "packages/.staging/$it") },
            legacyPrevious = legacyName?.let { File(root, "packages/.previous/$it") },
            elpActivationJournal = if (familyId == LocalUbuntuElpPolicy.FAMILY) File(root,"packages/.staging/.$name.elp-activation-pending") else null,
            elpHealthHold = if (familyId == LocalUbuntuElpPolicy.FAMILY) File(root,"packages/.staging/.$name.elp-health-pending") else null,
            graphqlActivationJournal = if (LocalUbuntuGraphqlPolicy.isFamily(familyId)) File(root,"packages/.staging/.$name.graphql-activation-pending") else null,
            graphqlHealthHold = if (LocalUbuntuGraphqlPolicy.isFamily(familyId)) File(root,"packages/.staging/.$name.graphql-health-pending") else null,
            tomlHealthHold = if (LocalUbuntuTomlPolicy.isFamily(familyId)) File(root,"packages/.staging/.$name.toml-health-pending") else null,
            csharpHealthHold = if (LocalUbuntuCSharpPolicy.isFamily(familyId)) File(root,"packages/.staging/.$name.csharp-health-pending") else null,
            vueHealthHold = if (familyId in setOf("lsp.vue","runtime.vue-helper")) File(root,"packages/.staging/.$name.vue-health-pending") else null,
        )
    }

    /** An unknown Vue process owner is not a failed disposable stage. This sidecar
     * survives activation and blocks automatic recovery/removal/start until verified
     * cleanup. Its absence is never inferred from a timeout or failed parent process. */
    class VueHealthHold internal constructor(internal val file: File, internal val nonce: String, internal val key: Any?)
    fun requireNoVueHealthHold(paths: Paths) {
        paths.vueHealthHold?.let { file ->
            requireSafeParents(file)
            check(!existsNoFollow(file)) { "Vue owned health cleanup is unconfirmed; package trees and journal were preserved for repair" }
        }
    }
    fun beginVueHealth(paths: Paths): VueHealthHold {
        requireNoVueHealthHold(paths)
        val file = requireNotNull(paths.vueHealthHold)
        requireSafeParents(file)
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val nonce = UUID.randomUUID().toString()
        java.nio.channels.FileChannel.open(file.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS).use { channel ->
            val bytes=java.nio.ByteBuffer.wrap(nonce.toByteArray(Charsets.US_ASCII))
            while(bytes.hasRemaining())check(channel.write(bytes)>0)
            channel.force(true)
        }
        val attributes=java.nio.file.Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(attributes.isRegularFile && attributes.fileKey()!=null)
        return VueHealthHold(file,nonce,attributes.fileKey())
    }
    fun finishVueHealth(hold: VueHealthHold, cleanupVerified: Boolean) {
        if(!cleanupVerified)return // Keep the durable unknown-owner custody conflict.
        requireSafeParents(hold.file)
        val before=java.nio.file.Files.readAttributes(hold.file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(before.isRegularFile && !before.isSymbolicLink && before.fileKey()==hold.key && before.size()==36L)
        val bytes=ByteArray(36)
        java.nio.file.Files.newInputStream(hold.file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
            var at=0;while(at<bytes.size){val n=input.read(bytes,at,bytes.size-at);check(n>0);at+=n};check(input.read()==-1)
        }
        check(bytes.toString(Charsets.US_ASCII)==hold.nonce)
        val after=java.nio.file.Files.readAttributes(hold.file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(after.fileKey()==before.fileKey() && after.size()==before.size() && after.lastModifiedTime()==before.lastModifiedTime())
        check(hold.file.delete()) { "Vue verified cleanup marker could not be retired" }
    }

    class CSharpHealthHold internal constructor(internal val file: File, internal val nonce: String, internal val key: Any?)
    fun requireNoCSharpHealthHold(paths: Paths) {
        paths.csharpHealthHold?.let { file ->
            requireSafeParents(file)
            check(!existsNoFollow(file)) { "C# owned health cleanup is unconfirmed; package trees and journal were preserved for repair" }
        }
    }
    fun beginCSharpHealth(paths: Paths): CSharpHealthHold {
        requireNoCSharpHealthHold(paths)
        val file = requireNotNull(paths.csharpHealthHold)
        requireSafeParents(file)
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val nonce = UUID.randomUUID().toString()
        java.nio.channels.FileChannel.open(file.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS).use { channel ->
            val bytes=java.nio.ByteBuffer.wrap(nonce.toByteArray(Charsets.US_ASCII))
            while(bytes.hasRemaining())check(channel.write(bytes)>0)
            channel.force(true)
        }
        val attributes=java.nio.file.Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(attributes.isRegularFile && attributes.fileKey()!=null)
        return CSharpHealthHold(file,nonce,attributes.fileKey())
    }
    fun finishCSharpHealth(hold: CSharpHealthHold, cleanupVerified: Boolean) {
        if(!cleanupVerified)return // Keep the durable unknown-owner custody conflict.
        requireSafeParents(hold.file)
        val before=java.nio.file.Files.readAttributes(hold.file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(before.isRegularFile && !before.isSymbolicLink && before.fileKey()==hold.key && before.size()==36L)
        val bytes=ByteArray(36)
        java.nio.file.Files.newInputStream(hold.file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
            var at=0;while(at<bytes.size){val n=input.read(bytes,at,bytes.size-at);check(n>0);at+=n};check(input.read()==-1)
        }
        check(bytes.toString(Charsets.US_ASCII)==hold.nonce)
        val after=java.nio.file.Files.readAttributes(hold.file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(after.fileKey()==before.fileKey() && after.size()==before.size() && after.lastModifiedTime()==before.lastModifiedTime())
        check(hold.file.delete()) { "C# verified cleanup marker could not be retired" }
    }

    class TomlHealthHold internal constructor(internal val file: File, internal val nonce: String, internal val key: Any?) {
        internal var ownerText:String?=null
        internal var ownerKey:Any?=null
        internal var privateRoot:String?=null
        internal val ownerFile get()=File(file.parentFile,file.name+".owner")
    }
    fun requireNoTomlHealthHold(paths: Paths) {
        paths.tomlHealthHold?.let { file ->
            requireSafeParents(file)
            check(!existsNoFollow(file)&&!existsNoFollow(File(file.parentFile,file.name+".owner"))) { "Taplo owned health cleanup is unconfirmed; package trees and journal were preserved for repair" }
        }
    }
    fun beginTomlHealth(paths: Paths): TomlHealthHold {
        requireNoElpHealthHold(paths)
        requireNoGraphqlHealthHold(paths)
        requireNoTomlHealthHold(paths)
        val file = requireNotNull(paths.tomlHealthHold)
        requireSafeParents(file)
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val nonce = UUID.randomUUID().toString()
        java.nio.channels.FileChannel.open(file.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS).use { channel ->
            val bytes=java.nio.ByteBuffer.wrap(nonce.toByteArray(Charsets.US_ASCII))
            while(bytes.hasRemaining())check(channel.write(bytes)>0)
            channel.force(true)
        }
        val attributes=java.nio.file.Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(attributes.isRegularFile && attributes.fileKey()!=null)
        java.nio.channels.FileChannel.open(file.parentFile!!.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}
        return TomlHealthHold(file,nonce,attributes.fileKey())
    }
    /** Bound before protected creation; uncertainty keeps both nonce and exact private-owner record. */
    fun bindTomlHealthOwner(hold:TomlHealthHold,privateRoot:String,token:String,directoryIdentity:String) {
        require(hold.ownerText==null&&token.matches(Regex("[0-9a-f]{32}"))&&directoryIdentity.matches(Regex("[0-9]+:[0-9]+")))
        requireSafeParents(hold.file)
        val marker=java.nio.file.Files.readAttributes(hold.file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        require(marker.isRegularFile&&!marker.isSymbolicLink&&marker.fileKey()==hold.key&&marker.size()==36L)
        require(NimRuntimeClosure.readText(hold.file,36)==hold.nonce) { "Taplo health hold was replaced before owner binding" }
        val markerAfter=java.nio.file.Files.readAttributes(hold.file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        require(markerAfter.fileKey()==marker.fileKey()&&markerAfter.size()==marker.size()&&markerAfter.lastModifiedTime()==marker.lastModifiedTime())
        val appRoot=hold.file.parentFile!!.parentFile!!.parentFile!!
        val expected=File(appRoot,"runtime-tmp/droide-toml-health-$token")
        require(expected.absolutePath==privateRoot&&expected.canonicalFile==expected.absoluteFile)
        val attrs=java.nio.file.Files.readAttributes(expected.toPath(),"unix:dev,ino,mode",java.nio.file.LinkOption.NOFOLLOW_LINKS)
        require("${attrs["dev"]}:${attrs["ino"]}"==directoryIdentity&&(attrs["mode"] as Number).toInt() and 511==448&&(attrs["mode"] as Number).toInt() and 61440==16384)
        requireSafeParents(hold.ownerFile)
        val value=listOf("taplo-health-owner-v1",hold.nonce,privateRoot,token,directoryIdentity).joinToString("\n",postfix="\n")
        java.nio.channels.FileChannel.open(hold.ownerFile.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS).use { channel->
            val bytes=java.nio.ByteBuffer.wrap(value.toByteArray(Charsets.UTF_8));while(bytes.hasRemaining())check(channel.write(bytes)>0);channel.force(true)
        }
        java.nio.channels.FileChannel.open(hold.ownerFile.parentFile!!.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}
        val fileAttrs=java.nio.file.Files.readAttributes(hold.ownerFile.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        require(fileAttrs.isRegularFile&&fileAttrs.fileKey()!=null)
        hold.ownerText=value;hold.ownerKey=fileAttrs.fileKey();hold.privateRoot=privateRoot
    }
    fun finishTomlHealth(hold: TomlHealthHold, cleanupVerified: Boolean) {
        if(!cleanupVerified)return // Keep the durable unknown-owner custody conflict.
        requireSafeParents(hold.file)
        val before=java.nio.file.Files.readAttributes(hold.file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(before.isRegularFile && !before.isSymbolicLink && before.fileKey()==hold.key && before.size()==36L)
        val bytes=ByteArray(36)
        java.nio.file.Files.newInputStream(hold.file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
            var at=0;while(at<bytes.size){val n=input.read(bytes,at,bytes.size-at);check(n>0);at+=n};check(input.read()==-1)
        }
        check(bytes.toString(Charsets.US_ASCII)==hold.nonce)
        val after=java.nio.file.Files.readAttributes(hold.file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(after.fileKey()==before.fileKey() && after.size()==before.size() && after.lastModifiedTime()==before.lastModifiedTime())
        val expectedOwner=requireNotNull(hold.ownerText) { "Taplo owner was never durably bound" }
        val ownerAttrs=java.nio.file.Files.readAttributes(hold.ownerFile.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(ownerAttrs.isRegularFile&&!ownerAttrs.isSymbolicLink&&ownerAttrs.fileKey()==hold.ownerKey&&ownerAttrs.size()==expectedOwner.toByteArray().size.toLong())
        check(NimRuntimeClosure.readText(hold.ownerFile,8192)==expectedOwner)
        check(!existsNoFollow(File(requireNotNull(hold.privateRoot)))) { "Taplo private ownership state still exists" }
        check(hold.ownerFile.delete()) { "Taplo verified owner record could not be retired" }
        check(hold.file.delete()) { "Taplo verified cleanup marker could not be retired" }
        java.nio.channels.FileChannel.open(hold.file.parentFile!!.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}
    }

    class ElpHealthHold internal constructor(internal val file: File, internal val nonce: String, internal val key: Any?, internal val activationId:String, internal val stage:String) {
        // This object is issued only by beginElpHealth in this process. Restart never reconstructs it.
        private var runtimeEntered=false
        @Synchronized internal fun enterRuntime() { check(!runtimeEntered);runtimeEntered=true }
        @Synchronized internal fun requireNeverEntered() { check(!runtimeEntered) { "ELP runtime entry is unresolved; custody retained" } }
        internal var ownerText:String?=null
        internal var ownerKey:Any?=null
        internal var privateRoot:String?=null
        internal val ownerFile get()=File(file.parentFile,file.name+".owner")
    }
    fun requireNoElpHealthHold(paths: Paths) {
        paths.elpHealthHold?.let { file ->
            requireSafeParents(file)
            check(!existsNoFollow(file)&&!existsNoFollow(File(file.parentFile,file.name+".owner"))) { "ELP owned health cleanup is unconfirmed; package trees and journal were preserved for repair" }
        }
    }
    fun beginElpHealth(paths: Paths, activationId:String): ElpHealthHold {
        require(isValidId(activationId) && matchesActivation(paths.stage,activationId))
        requireNoElpHealthHold(paths)
        val file = requireNotNull(paths.elpHealthHold)
        requireSafeParents(file)
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val nonce = UUID.randomUUID().toString()
        java.nio.channels.FileChannel.open(file.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS).use { channel ->
            val bytes=java.nio.ByteBuffer.wrap(nonce.toByteArray(Charsets.US_ASCII))
            while(bytes.hasRemaining())check(channel.write(bytes)>0)
            channel.force(true)
        }
        val attributes=java.nio.file.Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(attributes.isRegularFile && attributes.fileKey()!=null)
        java.nio.channels.FileChannel.open(file.parentFile!!.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}
        return ElpHealthHold(file,nonce,attributes.fileKey(),activationId,paths.stage.absolutePath)
    }
    /** Only a locally issued, positively never-entered hold can retire its nonce. No package tree is deleted.
     * A bound owner, changed marker, cancellation after entry, or reconstructed/restart state cannot use this. */
    internal fun finishElpHealthNeverEntered(hold:ElpHealthHold) = synchronized(hold) {
        hold.requireNeverEntered()
        require(hold.ownerText==null&&hold.ownerKey==null&&hold.privateRoot==null)
        requireSafeParents(hold.file);requireSafeParents(hold.ownerFile)
        require(!existsNoFollow(hold.ownerFile))
        require(matchesActivation(File(hold.stage),hold.activationId))
        val before=java.nio.file.Files.readAttributes(hold.file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        require(before.isRegularFile&&!before.isSymbolicLink&&before.fileKey()==hold.key&&before.size()==36L)
        require(NimRuntimeClosure.readText(hold.file,36)==hold.nonce)
        val after=java.nio.file.Files.readAttributes(hold.file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        require(after.fileKey()==before.fileKey()&&after.size()==before.size()&&after.lastModifiedTime()==before.lastModifiedTime())
        check(hold.file.delete()) { "ELP never-entered nonce could not be retired; custody retained" }
        java.nio.channels.FileChannel.open(hold.file.parentFile!!.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}
    }

    /** Bound before protected creation; uncertainty keeps both nonce and exact private-owner record. */
    fun bindElpHealthOwner(hold:ElpHealthHold,privateRoot:String,token:String,directoryIdentity:String) {
        require(hold.ownerText==null&&token.matches(Regex("[0-9a-f]{32}"))&&directoryIdentity.matches(Regex("[0-9]+:[0-9]+")))
        requireSafeParents(hold.file)
        val marker=java.nio.file.Files.readAttributes(hold.file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        require(marker.isRegularFile&&!marker.isSymbolicLink&&marker.fileKey()==hold.key&&marker.size()==36L)
        require(NimRuntimeClosure.readText(hold.file,36)==hold.nonce) { "ELP health hold was replaced before owner binding" }
        val markerAfter=java.nio.file.Files.readAttributes(hold.file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        require(markerAfter.fileKey()==marker.fileKey()&&markerAfter.size()==marker.size()&&markerAfter.lastModifiedTime()==marker.lastModifiedTime())
        val appRoot=hold.file.parentFile!!.parentFile!!.parentFile!!
        val expected=File(appRoot,"runtime-tmp/droide-elp-health-$token")
        require(expected.absolutePath==privateRoot&&expected.canonicalFile==expected.absoluteFile)
        val attrs=java.nio.file.Files.readAttributes(expected.toPath(),"unix:dev,ino,mode",java.nio.file.LinkOption.NOFOLLOW_LINKS)
        require("${attrs["dev"]}:${attrs["ino"]}"==directoryIdentity&&(attrs["mode"] as Number).toInt() and 511==448&&(attrs["mode"] as Number).toInt() and 61440==16384)
        requireSafeParents(hold.ownerFile)
        val value=listOf("elp-health-owner-v1",hold.nonce,hold.stage,hold.activationId,privateRoot,token,directoryIdentity,"448","BOUND_BEFORE_FACTORY").joinToString("\n",postfix="\n")
        java.nio.channels.FileChannel.open(hold.ownerFile.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS).use { channel->
            val bytes=java.nio.ByteBuffer.wrap(value.toByteArray(Charsets.UTF_8));while(bytes.hasRemaining())check(channel.write(bytes)>0);channel.force(true)
        }
        java.nio.channels.FileChannel.open(hold.ownerFile.parentFile!!.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}
        val fileAttrs=java.nio.file.Files.readAttributes(hold.ownerFile.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        require(fileAttrs.isRegularFile&&fileAttrs.fileKey()!=null)
        hold.ownerText=value;hold.ownerKey=fileAttrs.fileKey();hold.privateRoot=privateRoot
    }
    fun finishElpHealth(hold: ElpHealthHold, receipt:ElpTeardownReceipt) {
        if(!receipt.complete || !receipt.directChildReaped || !receipt.groupEmpty || !receipt.birthCustodyVerified || !receipt.factorySettled || !receipt.writersSettled || !receipt.readersSettled || !receipt.closeAttemptsSettled || !receipt.privateCleanupVerified)return // Keep the durable unknown-owner custody conflict.
        requireSafeParents(hold.file)
        val before=java.nio.file.Files.readAttributes(hold.file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(before.isRegularFile && !before.isSymbolicLink && before.fileKey()==hold.key && before.size()==36L)
        val bytes=ByteArray(36)
        java.nio.file.Files.newInputStream(hold.file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
            var at=0;while(at<bytes.size){val n=input.read(bytes,at,bytes.size-at);check(n>0);at+=n};check(input.read()==-1)
        }
        check(bytes.toString(Charsets.US_ASCII)==hold.nonce)
        val after=java.nio.file.Files.readAttributes(hold.file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(after.fileKey()==before.fileKey() && after.size()==before.size() && after.lastModifiedTime()==before.lastModifiedTime())
        val expectedOwner=requireNotNull(hold.ownerText) { "ELP owner was never durably bound" }
        val ownerAttrs=java.nio.file.Files.readAttributes(hold.ownerFile.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(ownerAttrs.isRegularFile&&!ownerAttrs.isSymbolicLink&&ownerAttrs.fileKey()==hold.ownerKey&&ownerAttrs.size()==expectedOwner.toByteArray().size.toLong())
        check(NimRuntimeClosure.readText(hold.ownerFile,8192)==expectedOwner)
        check(!existsNoFollow(File(requireNotNull(hold.privateRoot)))) { "ELP private ownership state still exists" }
        check(hold.ownerFile.delete()) { "ELP verified owner record could not be retired" }
        check(hold.file.delete()) { "ELP verified cleanup marker could not be retired" }
        java.nio.channels.FileChannel.open(hold.file.parentFile!!.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}
    }

    class GraphqlHealthHold internal constructor(internal val file: File, internal val nonce: String, internal val key: Any?, internal val activationId:String, internal val stage:String) {
        internal var ownerText:String?=null
        internal var ownerKey:Any?=null
        internal var privateRoot:String?=null
        internal val ownerFile get()=File(file.parentFile,file.name+".owner")
    }
    fun requireNoGraphqlHealthHold(paths: Paths) {
        paths.graphqlHealthHold?.let { file ->
            requireSafeParents(file)
            check(!existsNoFollow(file)&&!existsNoFollow(File(file.parentFile,file.name+".owner"))) { "GraphQL owned health cleanup is unconfirmed; package trees and journal were preserved for repair" }
        }
    }
    fun beginGraphqlHealth(paths: Paths, activationId:String): GraphqlHealthHold {
        require(isValidId(activationId) && matchesActivation(paths.stage,activationId))
        requireNoElpHealthHold(paths)
        requireNoGraphqlHealthHold(paths)
        val file = requireNotNull(paths.graphqlHealthHold)
        requireSafeParents(file)
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val nonce = UUID.randomUUID().toString()
        java.nio.channels.FileChannel.open(file.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS).use { channel ->
            val bytes=java.nio.ByteBuffer.wrap(nonce.toByteArray(Charsets.US_ASCII))
            while(bytes.hasRemaining())check(channel.write(bytes)>0)
            channel.force(true)
        }
        val attributes=java.nio.file.Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(attributes.isRegularFile && attributes.fileKey()!=null)
        java.nio.channels.FileChannel.open(file.parentFile!!.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}
        return GraphqlHealthHold(file,nonce,attributes.fileKey(),activationId,paths.stage.absolutePath)
    }
    /** Bound before protected creation; uncertainty keeps both nonce and exact private-owner record. */
    fun bindGraphqlHealthOwner(hold:GraphqlHealthHold,privateRoot:String,token:String,directoryIdentity:String) {
        require(hold.ownerText==null&&token.matches(Regex("[0-9a-f]{32}"))&&directoryIdentity.matches(Regex("[0-9]+:[0-9]+")))
        requireSafeParents(hold.file)
        val marker=java.nio.file.Files.readAttributes(hold.file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        require(marker.isRegularFile&&!marker.isSymbolicLink&&marker.fileKey()==hold.key&&marker.size()==36L)
        require(NimRuntimeClosure.readText(hold.file,36)==hold.nonce) { "GraphQL health hold was replaced before owner binding" }
        val markerAfter=java.nio.file.Files.readAttributes(hold.file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        require(markerAfter.fileKey()==marker.fileKey()&&markerAfter.size()==marker.size()&&markerAfter.lastModifiedTime()==marker.lastModifiedTime())
        val appRoot=hold.file.parentFile!!.parentFile!!.parentFile!!
        val expected=File(appRoot,"runtime-tmp/droide-graphql-health-$token")
        require(expected.absolutePath==privateRoot&&expected.canonicalFile==expected.absoluteFile)
        val attrs=java.nio.file.Files.readAttributes(expected.toPath(),"unix:dev,ino,mode",java.nio.file.LinkOption.NOFOLLOW_LINKS)
        require("${attrs["dev"]}:${attrs["ino"]}"==directoryIdentity&&(attrs["mode"] as Number).toInt() and 511==448&&(attrs["mode"] as Number).toInt() and 61440==16384)
        requireSafeParents(hold.ownerFile)
        val value=listOf("graphql-health-owner-v1",hold.nonce,hold.stage,hold.activationId,privateRoot,token,directoryIdentity,"448","BOUND_BEFORE_FACTORY").joinToString("\n",postfix="\n")
        java.nio.channels.FileChannel.open(hold.ownerFile.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS).use { channel->
            val bytes=java.nio.ByteBuffer.wrap(value.toByteArray(Charsets.UTF_8));while(bytes.hasRemaining())check(channel.write(bytes)>0);channel.force(true)
        }
        java.nio.channels.FileChannel.open(hold.ownerFile.parentFile!!.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}
        val fileAttrs=java.nio.file.Files.readAttributes(hold.ownerFile.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        require(fileAttrs.isRegularFile&&fileAttrs.fileKey()!=null)
        hold.ownerText=value;hold.ownerKey=fileAttrs.fileKey();hold.privateRoot=privateRoot
    }
    fun finishGraphqlHealth(hold: GraphqlHealthHold, receipt:GraphqlTeardownReceipt) {
        if(!receipt.complete || !receipt.directChildReaped || !receipt.groupEmpty || !receipt.birthCustodyVerified || !receipt.factorySettled || !receipt.writersSettled || !receipt.readersSettled || !receipt.closeAttemptsSettled || !receipt.privateCleanupVerified)return // Keep the durable unknown-owner custody conflict.
        requireSafeParents(hold.file)
        val before=java.nio.file.Files.readAttributes(hold.file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(before.isRegularFile && !before.isSymbolicLink && before.fileKey()==hold.key && before.size()==36L)
        val bytes=ByteArray(36)
        java.nio.file.Files.newInputStream(hold.file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
            var at=0;while(at<bytes.size){val n=input.read(bytes,at,bytes.size-at);check(n>0);at+=n};check(input.read()==-1)
        }
        check(bytes.toString(Charsets.US_ASCII)==hold.nonce)
        val after=java.nio.file.Files.readAttributes(hold.file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(after.fileKey()==before.fileKey() && after.size()==before.size() && after.lastModifiedTime()==before.lastModifiedTime())
        val expectedOwner=requireNotNull(hold.ownerText) { "GraphQL owner was never durably bound" }
        val ownerAttrs=java.nio.file.Files.readAttributes(hold.ownerFile.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(ownerAttrs.isRegularFile&&!ownerAttrs.isSymbolicLink&&ownerAttrs.fileKey()==hold.ownerKey&&ownerAttrs.size()==expectedOwner.toByteArray().size.toLong())
        check(NimRuntimeClosure.readText(hold.ownerFile,8192)==expectedOwner)
        check(!existsNoFollow(File(requireNotNull(hold.privateRoot)))) { "GraphQL private ownership state still exists" }
        check(hold.ownerFile.delete()) { "GraphQL verified owner record could not be retired" }
        check(hold.file.delete()) { "GraphQL verified cleanup marker could not be retired" }
        java.nio.channels.FileChannel.open(hold.file.parentFile!!.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}
    }

    /** GraphQL-only transaction custody binds candidate and previous activations before any stage data.
     * No absent journal, mismatched marker or unknown health owner authorizes deleting a tree. */
    internal data class ElpActivation(val candidate:String,val previous:String?)
    private fun readElpActivation(paths:Paths):ElpActivation? {
        val file=requireNotNull(paths.elpActivationJournal);requireSafeParents(file)
        if(!existsNoFollow(file))return null
        val raw=NimRuntimeClosure.readText(file,8192);val lines=raw.split('\n')
        require(lines.size==8&&lines[0]=="elp-activation-v1"&&lines[7].isEmpty())
        require(isValidId(lines[1])&&(lines[2]=="-"||isValidId(lines[2])))
        require(lines[3]==paths.stage.absolutePath&&lines[4]==paths.final.absolutePath&&lines[5]==paths.previous.absolutePath&&lines[6]=="PREPARED")
        return ElpActivation(lines[1],lines[2].takeUnless{it=="-"})
    }
    internal fun beginElpActivation(paths:Paths,candidate:String,previous:String?) {
        requireNoElpHealthHold(paths);require(isValidId(candidate)&&(previous==null||isValidId(previous)))
        require(matchesActivation(paths.stage,candidate)&&!existsNoFollow(paths.previous))
        require(if(previous==null)!existsNoFollow(paths.final)else existsNoFollow(paths.final)&&matchesActivation(paths.final,previous))
        val file=requireNotNull(paths.elpActivationJournal);requireSafeParents(file)
        val raw=listOf("elp-activation-v1",candidate,previous?:"-",paths.stage.absolutePath,paths.final.absolutePath,paths.previous.absolutePath,"PREPARED").joinToString("\n",postfix="\n")
        java.nio.channels.FileChannel.open(file.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS).use { channel ->
            val bytes=java.nio.ByteBuffer.wrap(raw.toByteArray(Charsets.UTF_8));while(bytes.hasRemaining())check(channel.write(bytes)>0);channel.force(true)
        };syncElpDirectories(paths)
    }
    private fun requireElpActivation(paths:Paths,candidate:String,previous:String?):ElpActivation =
        requireNotNull(readElpActivation(paths)).also{require(it==ElpActivation(candidate,previous))}
    internal fun beforeElpPromotion(paths:Paths,candidate:String,previous:String?) {
        requireNoElpHealthHold(paths);requireElpActivation(paths,candidate,previous)
        require(matchesActivation(paths.stage,candidate)&&!existsNoFollow(paths.previous))
        require(if(previous==null)!existsNoFollow(paths.final)else existsNoFollow(paths.final)&&matchesActivation(paths.final,previous))
        syncElpDirectories(paths)
    }
    internal fun syncElpDirectories(paths:Paths) {
        for(dir in listOfNotNull(paths.stage.takeIf{existsNoFollow(it)},paths.final.parentFile,paths.previous.parentFile,paths.elpActivationJournal?.parentFile).distinct()) {
            if(!existsNoFollow(dir))continue;requireSafeParents(dir);NimRuntimeClosure.requireDirectory(dir)
            java.nio.channels.FileChannel.open(dir.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}
        }
    }
    private fun retireElpActivation(paths:Paths,expected:ElpActivation) {
        require(readElpActivation(paths)==expected)
        check(requireNotNull(paths.elpActivationJournal).delete());syncElpDirectories(paths)
    }
    internal fun recoverElp(paths:Paths,receipt:Receipt?) {
        requireNoElpHealthHold(paths);requireDistinct(paths.stage,paths.final,paths.previous)
        require(receipt==null||receipt.activationId?.let(::isValidId)==true)
        for(legacy in listOfNotNull(paths.legacyStage,paths.legacyPrevious))if(legacy!=paths.stage&&legacy!=paths.previous)
            require(!existsNoFollow(legacy)){"Unknown legacy ELP transaction preserved"}
        val journal=readElpActivation(paths)
        if(journal==null) {
            require(!existsNoFollow(paths.stage)&&!existsNoFollow(paths.previous)){"ELP transaction has no exact activation custody; trees preserved"}
            require(if(receipt==null)!existsNoFollow(paths.final)else existsNoFollow(paths.final)&&matchesActivation(paths.final,requireNotNull(receipt.activationId))) { "ELP final receipt does not match; tree preserved" }
            return
        }
        val final=if(existsNoFollow(paths.final))readActivation(paths.final)else null
        val stage=if(existsNoFollow(paths.stage))readActivation(paths.stage)else null
        val previous=if(existsNoFollow(paths.previous))readActivation(paths.previous)else null
        require(!existsNoFollow(paths.stage)||stage==journal.candidate){"ELP stage activation mismatch; retained"}
        require(!existsNoFollow(paths.previous)||(journal.previous!=null&&previous==journal.previous)){"ELP previous activation mismatch; retained"}
        require(!existsNoFollow(paths.final)||final==journal.candidate||journal.previous!=null&&final==journal.previous){"ELP final activation mismatch; retained"}
        val durable=receipt?.activationId
        when(durable) {
            journal.candidate -> {
                require(final==journal.candidate&&!existsNoFollow(paths.stage)){"ELP committed activation layout ambiguous; retained"}
                if(existsNoFollow(paths.previous))check(PathSecurity.deleteTreeNoFollow(paths.previous))
            }
            journal.previous -> {
                if(final==journal.candidate) {
                    require(journal.previous==null||previous==journal.previous){"ELP rollback backup missing; final retained"}
                    check(PathSecurity.deleteTreeNoFollow(paths.final))
                }
                if(journal.previous!=null) {
                    if(existsNoFollow(paths.previous)){require(!existsNoFollow(paths.final));check(paths.previous.renameTo(paths.final))}
                    require(existsNoFollow(paths.final)&&matchesActivation(paths.final,journal.previous))
                } else require(!existsNoFollow(paths.final)&&!existsNoFollow(paths.previous))
                if(existsNoFollow(paths.stage))check(PathSecurity.deleteTreeNoFollow(paths.stage))
            }
            else -> error("ELP durable registry activation changed; all trees retained")
        }
        syncElpDirectories(paths);retireElpActivation(paths,journal)
    }

    internal data class GraphqlActivation(val candidate:String,val previous:String?)
    private fun readGraphqlActivation(paths:Paths):GraphqlActivation? {
        val file=requireNotNull(paths.graphqlActivationJournal);requireSafeParents(file)
        if(!existsNoFollow(file))return null
        val raw=NimRuntimeClosure.readText(file,8192);val lines=raw.split('\n')
        require(lines.size==8&&lines[0]=="graphql-activation-v1"&&lines[7].isEmpty())
        require(isValidId(lines[1])&&(lines[2]=="-"||isValidId(lines[2])))
        require(lines[3]==paths.stage.absolutePath&&lines[4]==paths.final.absolutePath&&lines[5]==paths.previous.absolutePath&&lines[6]=="PREPARED")
        return GraphqlActivation(lines[1],lines[2].takeUnless{it=="-"})
    }
    internal fun beginGraphqlActivation(paths:Paths,candidate:String,previous:String?) {
        requireNoElpHealthHold(paths)
        requireNoGraphqlHealthHold(paths);require(isValidId(candidate)&&(previous==null||isValidId(previous)))
        require(matchesActivation(paths.stage,candidate)&&!existsNoFollow(paths.previous))
        require(if(previous==null)!existsNoFollow(paths.final)else existsNoFollow(paths.final)&&matchesActivation(paths.final,previous))
        val file=requireNotNull(paths.graphqlActivationJournal);requireSafeParents(file)
        val raw=listOf("graphql-activation-v1",candidate,previous?:"-",paths.stage.absolutePath,paths.final.absolutePath,paths.previous.absolutePath,"PREPARED").joinToString("\n",postfix="\n")
        java.nio.channels.FileChannel.open(file.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS).use { channel ->
            val bytes=java.nio.ByteBuffer.wrap(raw.toByteArray(Charsets.UTF_8));while(bytes.hasRemaining())check(channel.write(bytes)>0);channel.force(true)
        };syncGraphqlDirectories(paths)
    }
    private fun requireGraphqlActivation(paths:Paths,candidate:String,previous:String?):GraphqlActivation =
        requireNotNull(readGraphqlActivation(paths)).also{require(it==GraphqlActivation(candidate,previous))}
    internal fun beforeGraphqlPromotion(paths:Paths,candidate:String,previous:String?) {
        requireNoElpHealthHold(paths)
        requireNoGraphqlHealthHold(paths);requireGraphqlActivation(paths,candidate,previous)
        require(matchesActivation(paths.stage,candidate)&&!existsNoFollow(paths.previous))
        require(if(previous==null)!existsNoFollow(paths.final)else existsNoFollow(paths.final)&&matchesActivation(paths.final,previous))
        syncGraphqlDirectories(paths)
    }
    internal fun syncGraphqlDirectories(paths:Paths) {
        for(dir in listOfNotNull(paths.stage.takeIf{existsNoFollow(it)},paths.final.parentFile,paths.previous.parentFile,paths.graphqlActivationJournal?.parentFile).distinct()) {
            if(!existsNoFollow(dir))continue;requireSafeParents(dir);NimRuntimeClosure.requireDirectory(dir)
            java.nio.channels.FileChannel.open(dir.toPath(),java.nio.file.StandardOpenOption.READ).use{it.force(true)}
        }
    }
    private fun retireGraphqlActivation(paths:Paths,expected:GraphqlActivation) {
        require(readGraphqlActivation(paths)==expected)
        check(requireNotNull(paths.graphqlActivationJournal).delete());syncGraphqlDirectories(paths)
    }
    internal fun recoverGraphql(paths:Paths,receipt:Receipt?) {
        requireNoElpHealthHold(paths)
        requireNoGraphqlHealthHold(paths);requireDistinct(paths.stage,paths.final,paths.previous)
        require(receipt==null||receipt.activationId?.let(::isValidId)==true)
        for(legacy in listOfNotNull(paths.legacyStage,paths.legacyPrevious))if(legacy!=paths.stage&&legacy!=paths.previous)
            require(!existsNoFollow(legacy)){"Unknown legacy GraphQL transaction preserved"}
        val journal=readGraphqlActivation(paths)
        if(journal==null) {
            require(!existsNoFollow(paths.stage)&&!existsNoFollow(paths.previous)){"GraphQL transaction has no exact activation custody; trees preserved"}
            require(if(receipt==null)!existsNoFollow(paths.final)else existsNoFollow(paths.final)&&matchesActivation(paths.final,requireNotNull(receipt.activationId))) { "GraphQL final receipt does not match; tree preserved" }
            return
        }
        val final=if(existsNoFollow(paths.final))readActivation(paths.final)else null
        val stage=if(existsNoFollow(paths.stage))readActivation(paths.stage)else null
        val previous=if(existsNoFollow(paths.previous))readActivation(paths.previous)else null
        require(!existsNoFollow(paths.stage)||stage==journal.candidate){"GraphQL stage activation mismatch; retained"}
        require(!existsNoFollow(paths.previous)||(journal.previous!=null&&previous==journal.previous)){"GraphQL previous activation mismatch; retained"}
        require(!existsNoFollow(paths.final)||final==journal.candidate||journal.previous!=null&&final==journal.previous){"GraphQL final activation mismatch; retained"}
        val durable=receipt?.activationId
        when(durable) {
            journal.candidate -> {
                require(final==journal.candidate&&!existsNoFollow(paths.stage)){"GraphQL committed activation layout ambiguous; retained"}
                if(existsNoFollow(paths.previous))check(PathSecurity.deleteTreeNoFollow(paths.previous))
            }
            journal.previous -> {
                if(final==journal.candidate) {
                    require(journal.previous==null||previous==journal.previous){"GraphQL rollback backup missing; final retained"}
                    check(PathSecurity.deleteTreeNoFollow(paths.final))
                }
                if(journal.previous!=null) {
                    if(existsNoFollow(paths.previous)){require(!existsNoFollow(paths.final));check(paths.previous.renameTo(paths.final))}
                    require(existsNoFollow(paths.final)&&matchesActivation(paths.final,journal.previous))
                } else require(!existsNoFollow(paths.final)&&!existsNoFollow(paths.previous))
                if(existsNoFollow(paths.stage))check(PathSecurity.deleteTreeNoFollow(paths.stage))
            }
            else -> error("GraphQL durable registry activation changed; all trees retained")
        }
        syncGraphqlDirectories(paths);retireGraphqlActivation(paths,journal)
    }

    fun prepare(stage: File): String {
        requireSafeParents(stage)
        check(stage.isDirectory && !PathSecurity.isSymbolicLink(stage)) { "Package staging tree is unavailable" }
        val marker = File(stage, ACTIVATION_FILE)
        check(!existsNoFollow(marker)) { "Package activation marker already exists" }
        val id = UUID.randomUUID().toString()
        FileOutputStream(marker).use { output ->
            output.write(id.toByteArray(Charsets.US_ASCII))
            output.fd.sync()
        }
        return id
    }

    fun matchesActivation(final: File, activationId: String): Boolean {
        require(isValidId(activationId)) { "Invalid package receipt activation ID" }
        return readActivation(final) == activationId
    }

    fun recover(paths: Paths, receipt: Receipt?) {
        if(paths.elpActivationJournal!=null){ recoverElp(paths,receipt);return }
        if(paths.graphqlActivationJournal!=null){ recoverGraphql(paths,receipt);return }
        requireNoElpHealthHold(paths)
        requireNoGraphqlHealthHold(paths)
        requireNoTomlHealthHold(paths)
        requireNoCSharpHealthHold(paths)
        requireNoVueHealthHold(paths)
        requireDistinct(paths.stage, paths.final, paths.previous)
        val legacyPrevious = paths.legacyPrevious
        if (legacyPrevious != null && existsNoFollow(legacyPrevious)) {
            requireRestorablePrevious(legacyPrevious)
            // Old journal names could collide across legal family/version pairs. The persisted
            // generation is the only safe evidence that this backup belongs to this receipt.
            val expectedId = receipt?.activationId
            val finalCommitted = expectedId != null && existsNoFollow(paths.final) && matchesActivation(paths.final, expectedId)
            if (!finalCommitted) {
                check(expectedId != null && readActivation(legacyPrevious) == expectedId) {
                    "Ambiguous legacy package backup was preserved; recovery requires repair"
                }
                require(isValidId(expectedId)) { "Invalid package receipt activation ID" }
                check(!existsNoFollow(paths.previous)) { "Both legacy and current package backups were preserved; recovery requires repair" }
                check(paths.previous.parentFile?.mkdirs() == true || paths.previous.parentFile?.isDirectory == true) {
                    "Cannot prepare package activation journal"
                }
                check(legacyPrevious.renameTo(paths.previous)) { "Cannot migrate verified legacy package activation journal" }
            }
        }
        // An old uncommitted stage has no ownership receipt. Preserve it rather than deleting a
        // possibly colliding package's partial payload; new transactions use their distinct stage.
        recover(paths.stage, paths.final, paths.previous, receipt)
    }

    fun recover(stage: File, final: File, previous: File, receipt: Receipt?) {
        requireDistinct(stage, final, previous)
        receipt?.activationId?.let { require(isValidId(it)) { "Invalid package receipt activation ID" } }
        check(PathSecurity.deleteTreeNoFollow(stage)) { "Cannot clean interrupted package staging tree" }

        val finalExists = existsNoFollow(final)
        val previousExists = existsNoFollow(previous)
        val finalId = if (finalExists) readActivation(final) else null
        val committedFinal = receipt?.activationId != null && receipt.activationId == finalId
        when {
            finalExists && previousExists && committedFinal -> {
                check(PathSecurity.deleteTreeNoFollow(previous)) { "Cannot clean committed package activation journal" }
            }
            finalExists && previousExists && receipt != null && receipt.activationId == null && finalId == null -> {
                // Legacy transactions have no generation marker. Preserve both trees when their
                // commit order cannot be proved; a runtime health failure is never a commit marker.
            }
            finalExists && previousExists -> {
                requireMatchingPrevious(previous, receipt)
                check(PathSecurity.deleteTreeNoFollow(final)) { "Cannot discard uncommitted package activation" }
                check(previous.renameTo(final)) { "Cannot restore package activation journal" }
            }
            !finalExists && previousExists -> {
                requireMatchingPrevious(previous, receipt)
                check(previous.renameTo(final)) { "Cannot restore interrupted package activation" }
            }
            finalExists && receipt == null -> {
                // A final tree without a matching durable receipt is an orphan from an interrupted
                // first install.  It must not silently become trusted on the next transaction.
                check(PathSecurity.deleteTreeNoFollow(final)) { "Cannot remove uncommitted package tree" }
            }
        }
    }

    fun activate(stage: File, final: File, previous: File): Activation {
        requireDistinct(stage, final, previous)
        check(stage.isDirectory && !PathSecurity.isSymbolicLink(stage)) { "Package staging tree is unavailable" }
        check(!existsNoFollow(previous)) { "Ambiguous legacy package activation: both trees were preserved; recovery is required before repair" }
        check(final.parentFile?.mkdirs() == true || final.parentFile?.isDirectory == true) {
            "Cannot prepare package destination"
        }
        check(previous.parentFile?.mkdirs() == true || previous.parentFile?.isDirectory == true) {
            "Cannot prepare package activation journal"
        }

        val replaced = existsNoFollow(final)
        if (replaced) {
            check(!PathSecurity.isSymbolicLink(final) && final.isDirectory) { "Existing package destination is unsafe" }
            check(final.renameTo(previous)) { "Cannot journal existing package tree" }
        }
        if (!stage.renameTo(final)) {
            if (replaced && existsNoFollow(previous)) {
                check(previous.renameTo(final)) { "Cannot restore package tree after activation failure" }
            }
            error("Cannot activate staged package tree")
        }
        return Activation(replaced)
    }

    fun rollback(final: File, previous: File, activation: Activation) {
        requireNonOverlapping(listOf(final, previous))
        if (activation.replacedExistingFinal) requireRestorablePrevious(previous)
        check(PathSecurity.deleteTreeNoFollow(final)) { "Cannot discard failed package activation" }
        if (activation.replacedExistingFinal) {
            check(previous.renameTo(final)) { "Cannot restore previous package tree" }
        } else {
            check(PathSecurity.deleteTreeNoFollow(previous)) { "Cannot clean empty package activation journal" }
        }
    }

    
    fun commit(previous: File): Boolean {
        requireSafeParents(previous)
        return PathSecurity.deleteTreeNoFollow(previous)
    }

    private fun existsNoFollow(file: File): Boolean = file.exists() || PathSecurity.isSymbolicLink(file)

    private const val ACTIVATION_FILE = "DROIDE_ACTIVATION_ID"
    private fun isValidId(id: String): Boolean =
        runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    private fun readActivation(tree: File): String? {
        check(tree.isDirectory && !PathSecurity.isSymbolicLink(tree)) { "Package activation tree is unsafe" }
        val marker = File(tree, ACTIVATION_FILE)
        if (!existsNoFollow(marker)) return null
        check(marker.isFile && !PathSecurity.isSymbolicLink(marker) && marker.length() == 36L) { "Package activation marker is unsafe" }
        return marker.readText(Charsets.US_ASCII).also { check(isValidId(it)) { "Package activation marker is invalid" } }
    }

    private fun requireRestorablePrevious(previous: File) {
        requireSafeParents(previous)
        check(previous.isDirectory && !PathSecurity.isSymbolicLink(previous)) {
            "Package activation journal is unsafe"
        }
    }

    private fun requireMatchingPrevious(previous: File, receipt: Receipt?) {
        requireRestorablePrevious(previous)
        receipt?.activationId?.let { expectedId ->
            check(readActivation(previous) == expectedId) {
                "Package backup does not match the durable receipt; both trees were preserved for repair"
            }
        }
    }

    private fun requireDistinct(stage: File, final: File, previous: File) {
        requireNonOverlapping(listOf(stage, final, previous))
    }

    private fun requireNonOverlapping(files: List<File>) {
        files.forEach(::requireSafeParents)
        val paths = files.map { it.canonicalFile.toPath().normalize() }
        require(paths.indices.all { first -> paths.indices.all { second ->
            first == second || (!paths[first].startsWith(paths[second]) && !paths[second].startsWith(paths[first]))
        } }) { "Package transaction paths must be distinct and must not overlap" }
    }

    internal fun requireSafeParents(file: File) {
        var parent = file.absoluteFile.parentFile
        while (parent != null) {
            check(!PathSecurity.isSymbolicLink(parent)) { "Package transaction parent is a symbolic link; all trees were preserved" }
            parent = parent.parentFile
        }
    }
}
