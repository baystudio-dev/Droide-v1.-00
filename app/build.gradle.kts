import java.net.URI
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Explicit host verification only. Normal application builds keep their native inputs.
val hostJvmOnly = when (val value = providers.gradleProperty("DROIDE_HOST_JVM_TESTS_ONLY").orNull) {
    null, "false" -> false
    "true" -> true
    else -> throw GradleException("DROIDE_HOST_JVM_TESTS_ONLY must be true or false, got $value")
}
if (hostJvmOnly) {
    require(gradle.startParameter.projectProperties["DROIDE_HOST_JVM_TESTS_ONLY"] == "true") {
        "Host JVM test mode requires explicit -PDROIDE_HOST_JVM_TESTS_ONLY=true"
    }
    layout.buildDirectory.set(layout.projectDirectory.dir("build-host-jvm-tests"))
    apply(from = rootProject.file("tools/host-jvm-only.gradle"))
}

// PRIVATE, DEVICE-UNVALIDATED packaging is never enabled by a file or environment default.
val privateDebugDeviceUnvalidated = when (val value = providers.gradleProperty("DROIDE_PRIVATE_DEBUG_DEVICE_UNVALIDATED").orNull) {
    null, "false" -> false
    "true" -> true
    else -> throw GradleException("DROIDE_PRIVATE_DEBUG_DEVICE_UNVALIDATED must be true or false, got $value")
}
if (privateDebugDeviceUnvalidated) {
    require(gradle.startParameter.projectProperties["DROIDE_PRIVATE_DEBUG_DEVICE_UNVALIDATED"] == "true") {
        "Private debug requires explicit -PDROIDE_PRIVATE_DEBUG_DEVICE_UNVALIDATED=true"
    }
    require(!hostJvmOnly) { "Private debug cannot be combined with host JVM mode" }
    layout.buildDirectory.set(layout.projectDirectory.dir("build-private-debug-device-unvalidated"))
    apply(from = rootProject.file("tools/private-debug-device-unvalidated.gradle"))
}

// R46 Java-only derivative is a separate opt-in; historical admission remains intact.
val privatePtyDerivative = when (val value = providers.gradleProperty("DROIDE_PRIVATE_PTY_DERIVATIVE").orNull) {
    null, "false" -> false
    "true" -> true
    else -> throw GradleException("DROIDE_PRIVATE_PTY_DERIVATIVE must be true or false, got $value")
}
if (privatePtyDerivative) {
    require(gradle.startParameter.projectProperties["DROIDE_PRIVATE_PTY_DERIVATIVE"] == "true") {
        "PTY derivative requires explicit -PDROIDE_PRIVATE_PTY_DERIVATIVE=true"
    }
    require(!hostJvmOnly && !privateDebugDeviceUnvalidated) { "PTY derivative cannot be combined with another private or host mode" }
    layout.buildDirectory.set(layout.projectDirectory.dir("build-private-pty-derivative"))
    apply(from = rootProject.file("tools/private-pty-derivative.gradle"))
}
// R63 is a distinct false-default lane; R46 and historical admission remain intact.
val privateR63ResizeDerivative = when (val value = providers.gradleProperty("DROIDE_PRIVATE_R63_RESIZE_DERIVATIVE").orNull) {
    null, "false" -> false
    "true" -> true
    else -> throw GradleException("DROIDE_PRIVATE_R63_RESIZE_DERIVATIVE must be true or false, got $value")
}
if (privateR63ResizeDerivative) {
    require(gradle.startParameter.projectProperties["DROIDE_PRIVATE_R63_RESIZE_DERIVATIVE"] == "true") {
        "R63 resize requires explicit -PDROIDE_PRIVATE_R63_RESIZE_DERIVATIVE=true"
    }
    require(!hostJvmOnly && !privateDebugDeviceUnvalidated && !privatePtyDerivative) {
        "R63 resize cannot be combined with another private or host mode"
    }
    layout.buildDirectory.set(layout.projectDirectory.dir("build-private-r63-resize-derivative"))
    apply(from = rootProject.file("tools/private-r63-resize-derivative.gradle"))
}
val privatePackaging = privateDebugDeviceUnvalidated || privatePtyDerivative || privateR63ResizeDerivative

// Current artifacts are a distinct, false-default lane; candidates are NOT admitted APKs.
val currentDebugVerified = when (val value = providers.gradleProperty("DROIDE_CURRENT_DEBUG_VERIFIED").orNull) {
    null, "false" -> false
    "true" -> true
    else -> throw GradleException("DROIDE_CURRENT_DEBUG_VERIFIED must be true or false, got $value")
}
val currentCompositeV2 = when (val value = providers.gradleProperty("DROIDE_CURRENT_COMPOSITE_V2").orNull) {
    null, "false" -> false
    "true" -> true
    else -> throw GradleException("DROIDE_CURRENT_COMPOSITE_V2 must be true or false, got $value")
}
if (currentCompositeV2) {
    require(currentDebugVerified && gradle.startParameter.projectProperties["DROIDE_CURRENT_COMPOSITE_V2"] == "true") {
        "Composite V2 requires explicit CLI current debug mode"
    }
}
val currentAppRuntimeContracts = when (val value = providers.gradleProperty("DROIDE_CURRENT_APP_RUNTIME_CONTRACTS").orNull) {
    null, "false" -> false
    "true" -> true
    else -> throw GradleException("DROIDE_CURRENT_APP_RUNTIME_CONTRACTS must be true or false, got $value")
}
if (currentAppRuntimeContracts) {
    require(currentDebugVerified && gradle.startParameter.projectProperties["DROIDE_CURRENT_APP_RUNTIME_CONTRACTS"] == "true") {
        "Current app contracts require explicit CLI current debug and contracts modes"
    }
}
val currentComposite = if (currentDebugVerified) {
    require(gradle.startParameter.projectProperties["DROIDE_CURRENT_DEBUG_VERIFIED"] == "true")
    require(!hostJvmOnly && !privatePackaging) { "Current debug cannot be combined with another mode" }
    layout.buildDirectory.set(layout.projectDirectory.dir("build-current-debug"))
    apply(from = rootProject.file(if (currentAppRuntimeContracts)
        (if (currentCompositeV2) "tools/current-app-runtime-contracts-v2.gradle" else "tools/current-app-runtime-contracts.gradle") else "tools/current-debug-candidate.gradle"))
    File(requireNotNull(gradle.startParameter.projectProperties["DROIDE_CURRENT_COMPOSITE"]) {
        "Current debug requires explicit DROIDE_CURRENT_COMPOSITE"
    }).also { require(it.isAbsolute) { "Composite path must be absolute" } }
} else null
val prebuiltPackaging = privatePackaging || currentDebugVerified

// Developer APKs rebuild current JNI sources and the reviewed Java terminal engine.
// Historical release/private receipts remain immutable and retain their own gates.
val sourceDebug = providers.gradleProperty("DROIDE_SOURCE_DEBUG").orNull?.let {
    it.toBooleanStrictOrNull() ?: throw GradleException("DROIDE_SOURCE_DEBUG must be true or false")
} ?: false
// An explicit release-variant build of current sources. Device/distribution
// admission remains separate; historical terminal receipts are never rewritten.
val sourceRelease = providers.gradleProperty("DROIDE_SOURCE_RELEASE").orNull?.let {
    it.toBooleanStrictOrNull() ?: throw GradleException("DROIDE_SOURCE_RELEASE must be true or false")
} ?: false
val fdroidDistribution = providers.gradleProperty("DROIDE_FDROID_BUILD").orNull?.let {
    it.toBooleanStrictOrNull() ?: throw GradleException("DROIDE_FDROID_BUILD must be true or false")
} ?: false
require(!fdroidDistribution || sourceRelease || sourceDebug || hostJvmOnly) {
    "F-Droid distribution requires a current-source build or explicit host verification"
}
fun sourceBuildPath(property: String): File = File(requireNotNull(gradle.startParameter.projectProperties[property]) {
    "Source build requires explicit $property"
}).also { require(it.isAbsolute) { "$property must be an absolute path" } }
val sourceDebugEngine = if (sourceDebug && !fdroidDistribution) sourceBuildPath("DROIDE_SOURCE_DEBUG_ENGINE_DIR") else null
val sourceDebugJdkArchive = if (sourceDebug && !fdroidDistribution) sourceBuildPath("DROIDE_SOURCE_DEBUG_JDK_ARCHIVE") else null
val sourceReleaseEngine = if (sourceRelease && !fdroidDistribution) sourceBuildPath("DROIDE_SOURCE_RELEASE_ENGINE_DIR") else null
val sourceReleaseJdkArchive = if (sourceRelease && !fdroidDistribution) sourceBuildPath("DROIDE_SOURCE_RELEASE_JDK_ARCHIVE") else null
val fdroidNativeDirectory = if (fdroidDistribution && !hostJvmOnly) sourceBuildPath("DROIDE_FDROID_NATIVE_DIR") else null
val fdroidNativeHashes: Map<String, String> = fdroidNativeDirectory?.let { directory ->
    val receipt = groovy.json.JsonSlurper().parse(File(directory, "receipt.json")) as Map<*, *>
    require(receipt["fullSourceRebuild"] == true && receipt["binaryBaseRetained"] == false)
    val hashes = receipt["nativeLibraries"] as Map<*, *>
    require(hashes.keys == setOf("libdroide_proot.so", "libproot-loader.so", "libproot-loader32.so", "libdroide_cli.so"))
    hashes.entries.associate { (name, hash) ->
        require(hash is String && Regex("[a-f0-9]{64}").matches(hash)) { "Invalid source-built native identity: $name" }
        name.toString() to hash
    }
} ?: emptyMap()
if (sourceRelease) {
    require(gradle.startParameter.projectProperties["DROIDE_SOURCE_RELEASE"] == "true")
    require(!hostJvmOnly && !prebuiltPackaging && !sourceDebug) { "Source release cannot be combined with another build mode" }
    require(gradle.startParameter.taskNames == listOf(":app:assembleSourceRelease")) {
        "Source release requires only :app:assembleSourceRelease"
    }
    require(gradle.startParameter.excludedTaskNames.isEmpty()) { "Source release forbids task exclusions" }
    require(gradle.startParameter.dependencyVerificationMode.name == "STRICT")
    require(!gradle.startParameter.isWriteDependencyLocks && gradle.startParameter.writeDependencyVerifications.isEmpty())
    layout.buildDirectory.set(layout.projectDirectory.dir(if (fdroidDistribution) "build-source-release-fdroid-candidate" else "build-source-release"))
    gradle.taskGraph.whenReady {
        // AGP also uses "Debug" in release native-symbol task names. These
        // process release binaries; they do not compile the debug variant.
        val releaseSymbolTasks = setOf("stripReleaseDebugSymbols", "mergeReleaseNativeDebugMetadata")
        val forbidden = allTasks.filter {
            // AGP disables this instrumentation dependency-check task when
            // dependency constraints are enabled; lint still models it.
            (!it.enabled && it.name != "preReleaseAndroidTestBuild") ||
                (it.name.contains("Debug", ignoreCase = true) && it.name !in releaseSymbolTasks)
        }
        require(forbidden.isEmpty()) {
            "Source release cannot skip work or execute debug tasks: ${forbidden.joinToString { it.path }}"
        }
    }
}
if (sourceDebug) {
    require(gradle.startParameter.projectProperties["DROIDE_SOURCE_DEBUG"] == "true")
    require(!hostJvmOnly && !prebuiltPackaging) { "Source debug cannot be combined with another build mode" }
    require(gradle.startParameter.taskNames == listOf(":app:assembleSourceDebug")) {
        "Source debug requires only :app:assembleSourceDebug"
    }
    require(gradle.startParameter.excludedTaskNames.isEmpty()) { "Source debug forbids task exclusions" }
    require(gradle.startParameter.dependencyVerificationMode.name == "STRICT")
    require(!gradle.startParameter.isWriteDependencyLocks && gradle.startParameter.writeDependencyVerifications.isEmpty())
    layout.buildDirectory.set(layout.projectDirectory.dir(if (fdroidDistribution) "build-source-debug-fdroid-candidate" else "build-source-debug"))
    gradle.taskGraph.whenReady {
        require(allTasks.none { !it.enabled || it.name.contains("Release", ignoreCase = true) }) {
            "Source debug cannot skip work or execute release tasks"
        }
    }
}


val releaseKeystorePath = providers.gradleProperty("DROIDE_KEYSTORE_PATH").orNull ?: System.getenv("DROIDE_KEYSTORE_PATH")
val releaseKeystorePassword = providers.gradleProperty("DROIDE_KEYSTORE_PASSWORD").orNull ?: System.getenv("DROIDE_KEYSTORE_PASSWORD")
val releaseKeyAlias = providers.gradleProperty("DROIDE_KEY_ALIAS").orNull ?: System.getenv("DROIDE_KEY_ALIAS")
val releaseKeyPassword = providers.gradleProperty("DROIDE_KEY_PASSWORD").orNull ?: System.getenv("DROIDE_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }
if (sourceRelease) {
    require(hasReleaseSigning || listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword).all { it.isNullOrBlank() }) {
        "Release signing is incomplete: configure all four signing values or build unsigned"
    }
}
// Keep release certification's native runner; opt into the separate device UI workflow suite explicitly.
val frontendDeviceTests = providers.gradleProperty("DROIDE_FRONTEND_DEVICE_TESTS").orNull == "true"


// Export remains explicitly disableable without changing recovery or history.
val explorerExportExperimental = providers.gradleProperty("droide.explorerExportExperimental")
    .orNull?.let { value ->
        value.toBooleanStrictOrNull()
            ?: throw GradleException("droide.explorerExportExperimental must be exactly true or false")
    } ?: true

val githubOAuthClientId = (providers.gradleProperty("DROIDE_GITHUB_OAUTH_CLIENT_ID").orNull
    ?: System.getenv("DROIDE_GITHUB_OAUTH_CLIENT_ID")).orEmpty().trim()
require(githubOAuthClientId.isEmpty() || Regex("[A-Za-z0-9._-]{8,128}").matches(githubOAuthClientId)) {
    "DROIDE_GITHUB_OAUTH_CLIENT_ID has an invalid format"
}


val githubAccountClientId = (providers.gradleProperty("DROIDE_GITHUB_ACCOUNT_CLIENT_ID").orNull
    ?: System.getenv("DROIDE_GITHUB_ACCOUNT_CLIENT_ID")).orEmpty().trim()
require(githubAccountClientId.isEmpty() || Regex("[A-Za-z0-9._-]{8,128}").matches(githubAccountClientId)) {
    "DROIDE_GITHUB_ACCOUNT_CLIENT_ID has an invalid format"
}
val githubAppSlug = (providers.gradleProperty("DROIDE_GITHUB_APP_SLUG").orNull
    ?: System.getenv("DROIDE_GITHUB_APP_SLUG")).orEmpty().trim()
require(githubAppSlug.isEmpty() || Regex("[a-z0-9](?:[a-z0-9-]{0,98}[a-z0-9])?").matches(githubAppSlug)) {
    "DROIDE_GITHUB_APP_SLUG has an invalid format"
}
val githubAccountExchangeUrl = (providers.gradleProperty("DROIDE_GITHUB_ACCOUNT_EXCHANGE_URL").orNull
    ?: System.getenv("DROIDE_GITHUB_ACCOUNT_EXCHANGE_URL")).orEmpty().trim()
require(githubAccountExchangeUrl.isEmpty() || Regex("https://[^\\s?#]+(?:/[^\\s?#]*)?").matches(githubAccountExchangeUrl)) {
    "DROIDE_GITHUB_ACCOUNT_EXCHANGE_URL must be an HTTPS URL without query or fragment"
}


val defaultGithubAccountRedirectUri = "com.baystudio.droide.oauth://github/callback"
val githubAccountRedirectUri = (providers.gradleProperty("DROIDE_GITHUB_ACCOUNT_REDIRECT_URI").orNull
    ?: System.getenv("DROIDE_GITHUB_ACCOUNT_REDIRECT_URI")).orEmpty().trim().ifEmpty { defaultGithubAccountRedirectUri }
val githubAccountRedirect = runCatching { URI(githubAccountRedirectUri) }
    .getOrElse { e -> throw GradleException("DROIDE_GITHUB_ACCOUNT_REDIRECT_URI is not a valid URI", e) }
val githubAccountRedirectHasNoExtras = githubAccountRedirect.rawUserInfo == null &&
    githubAccountRedirect.rawQuery == null && githubAccountRedirect.rawFragment == null
val githubAccountRedirectIsNative = githubAccountRedirectUri == defaultGithubAccountRedirectUri
val githubAccountRedirectIsHttps = githubAccountRedirect.scheme.equals("https", ignoreCase = true) &&
    !githubAccountRedirect.host.isNullOrBlank() && githubAccountRedirect.port in listOf(-1, 443) &&
    !githubAccountRedirect.rawPath.isNullOrBlank() && githubAccountRedirect.rawPath.startsWith("/")
require(githubAccountRedirectHasNoExtras && (githubAccountRedirectIsNative || githubAccountRedirectIsHttps)) {
    "DROIDE_GITHUB_ACCOUNT_REDIRECT_URI must be the Droide native callback or a clean HTTPS App Link"
}
fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.baystudio.droide"
    if (!hostJvmOnly) {
        ndkVersion = "28.2.13676358"
        if (!prebuiltPackaging) {
            externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
        }
    }
    if (privatePackaging) {
        // These eight exact prebuilt bytes have two clean-root native build records.
        // They are packaged unchanged; AGP does not compile CMake in this private mode.
        sourceSets.getByName("debug").jniLibs.srcDir("../third_party/termux/native/private-debug/primary")
        sourceSets.getByName("debug").manifest.srcFile("../tools/private-debug-device-unvalidated-manifest.xml")
    }
    if (currentDebugVerified) {
        // Composite contains the complete 12-provider inventory, including unchanged auxiliaries.
        sourceSets.getByName("main").jniLibs.setSrcDirs(listOf(File(requireNotNull(currentComposite), "native")))
        sourceSets.getByName("debug").manifest.srcFile("../tools/current-debug-unadmitted-manifest.xml")
    }
    if (fdroidNativeDirectory != null) sourceSets.getByName("main").jniLibs.setSrcDirs(listOf(File(fdroidNativeDirectory, "jniLibs")))
    if (sourceDebug) sourceSets.getByName("debug").manifest.srcFile("../tools/source-debug-manifest.xml")
    compileSdk = 36
    if (fdroidDistribution) androidResources.ignoreAssetsPatterns += "ubuntu-base-24.04.5-base-arm64.tar.xz"
    buildToolsVersion = "36.0.0"
    // Explicit reviewed Android-source derivative; original Kadb archives/JAR remain provenance inputs.
    sourceSets.getByName("main").java.srcDir("../third_party/kadb/droide-source/src/main/kotlin")
    if (fdroidDistribution) {
        sourceSets.getByName("main").java.srcDirs(
            "../third_party/kadb/mdns-source/androidMain", "../third_party/kadb/mdns-source/commonMain",
            "../third_party/kadb/spake2-boringssl-compat/src/main/java")
    }

    defaultConfig {
        applicationId = "com.baystudio.droide"
        minSdk = 29
        targetSdk = 36
        versionCode = 45
        versionName = "1.01"
        testApplicationId = "com.baystudio.droide.test"
        testInstrumentationRunner = if (frontendDeviceTests) "androidx.test.runner.AndroidJUnitRunner"
            else "com.baystudio.droide.PhysicalDeviceSmokeInstrumentation"
        testFunctionalTest = true
        buildConfigField("boolean", "EXPLORER_EXPORT_EXPERIMENTAL", explorerExportExperimental.toString())
        buildConfigField("boolean", "FDROID_DISTRIBUTION", fdroidDistribution.toString())
        buildConfigField("boolean", "FDROID_NATIVE_SOURCE", (fdroidNativeDirectory != null).toString())
        for ((field, library) in mapOf("PROOT" to "libdroide_proot.so", "PROOT_LOADER" to "libproot-loader.so",
            "PROOT_LOADER32" to "libproot-loader32.so", "CLI" to "libdroide_cli.so")) {
            buildConfigField("String", "SOURCE_${field}_SHA256", buildConfigString(fdroidNativeHashes[library].orEmpty()))
        }
        buildConfigField("String", "GITHUB_OAUTH_CLIENT_ID", buildConfigString(githubOAuthClientId))
        buildConfigField("String", "GITHUB_ACCOUNT_CLIENT_ID", buildConfigString(githubAccountClientId))
        buildConfigField("String", "GITHUB_APP_SLUG", buildConfigString(githubAppSlug))
        buildConfigField("String", "GITHUB_ACCOUNT_EXCHANGE_URL", buildConfigString(githubAccountExchangeUrl))
        buildConfigField("String", "GITHUB_ACCOUNT_REDIRECT_URI", buildConfigString(githubAccountRedirectUri))
        manifestPlaceholders.putAll(mapOf<String, Any>(
            "githubAccountCallbackScheme" to (githubAccountRedirect.scheme ?: ""),
            "githubAccountCallbackHost" to (githubAccountRedirect.host ?: ""),
            "githubAccountCallbackPath" to (githubAccountRedirect.rawPath ?: ""),
            "githubAccountCallbackAutoVerify" to githubAccountRedirectIsHttps.toString(),
        ))
    }

    // release certification must exercise the release variant, not a debug surrogate.

    testBuildType = "release"

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        if (sourceDebug) getByName("debug") { versionNameSuffix = "-source-debug" }
        if (currentDebugVerified) {
            getByName("debug") {
                versionNameSuffix = "-current-debug-unadmitted"
                isDebuggable = true
                signingConfigs.getByName("debug").storeFile = File(requireNotNull(
                    gradle.startParameter.projectProperties["DROIDE_CURRENT_DEBUG_KEYSTORE"]))
                signingConfig = signingConfigs.getByName("debug")
            }
        }
        if (privatePackaging) {
            getByName("debug") {
                versionNameSuffix = "-private-device-unvalidated"
                isDebuggable = true
                signingConfig = signingConfigs.getByName("debug")
            }
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    packaging {
        jniLibs {
            
            if (prebuiltPackaging || sourceDebug || sourceRelease) {
                keepDebugSymbols += setOf("**/libtermux.so", "**/libdroide_files.so")
            }
            if (currentDebugVerified) {
                keepDebugSymbols += setOf("**/libdatastore_shared_counter.so", "**/libandroidx.graphics.path.so")
            }
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libdroide_proot.so", "**/libproot-loader.so", "**/libproot-loader32.so", "**/libdroide_cli.so")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Commons TAR runtime JARs share these paths; retain all upstream notices.
            excludes -= setOf("/META-INF/LICENSE.txt", "/META-INF/NOTICE.txt")
            merges += setOf("META-INF/LICENSE.txt", "META-INF/NOTICE.txt")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencyLocking {
    if (fdroidDistribution) lockFile.set(file("gradle-fdroid.lockfile"))
    lockAllConfigurations()
}

// The distributable AAR remains an explicit reviewed artifact. Never rebuild it implicitly.
// Restored source intentionally fails this gate until a real AAR rebuild receipt exists.
val verifyTermuxOverlay by tasks.registering(Exec::class) {
    group = "verification"
    description = "Reject stale Termux AAR/source/provenance combinations without rebuilding"
    workingDir(rootProject.projectDir)
    commandLine("python3", "-B", rootProject.file("tools/verify_termux_provenance.py"))
    if (privatePackaging) args("--private-debug-device-unvalidated")
    outputs.upToDateWhen { false }
}
// The only admitted private root. Verification runs after AGP packaging and cannot
// be omitted by selecting assembleDebug directly or by excluding tasks.
if (privateDebugDeviceUnvalidated) {
    val preparePrivateDebugDeviceUnvalidated by tasks.registering(Delete::class) {
        delete(layout.buildDirectory.file("outputs/private-debug-device-unvalidated.json"),
            layout.buildDirectory.file("outputs/Droide-private-debug-device-unvalidated.apk"))
    }
    tasks.matching { it.name == "preBuild" || it.name == "verifyTermuxOverlay" || it.name == "verifyJGitAndroidCompat" }
        .configureEach { dependsOn(preparePrivateDebugDeviceUnvalidated) }
    tasks.register<Exec>("assemblePrivateDebugDeviceUnvalidated") {
        group = "build"
        description = "Build and verify an explicitly private, device-unvalidated debug APK"
        dependsOn("assembleDebug", "lintDebug")
        workingDir(rootProject.projectDir)
        commandLine("python3", "-B", rootProject.file("tools/verify_private_debug_native.py"),
            "--root", rootProject.projectDir,
            "--apk", layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile,
            "--report", layout.buildDirectory.file("outputs/private-debug-device-unvalidated.json").get().asFile,
            "--build-tools", File(android.sdkDirectory, "build-tools/36.0.0"),
            "--java-home", System.getProperty("java.home"),
            "--debug-keystore", requireNotNull(android.signingConfigs.getByName("debug").storeFile))
        outputs.upToDateWhen { false }
    }
}
// The derivative AAR is built explicitly by the reviewed double-build tool, never implicitly.
val verifyTermuxPtyDerivative by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verify the private Java-only derivative against its frozen baseline and double-build evidence"
    dependsOn(verifyTermuxOverlay)
    workingDir(rootProject.projectDir)
    commandLine("python3", "-B", rootProject.file("tools/verify_termux_pty_derivative.py"),
        "--root", rootProject.projectDir)
    outputs.upToDateWhen { false }
}
if (privatePtyDerivative) {
    val preparePrivatePtyDerivative by tasks.registering(Delete::class) {
        delete(layout.buildDirectory.file("outputs/private-pty-derivative.json"),
            layout.buildDirectory.file("outputs/Droide-private-pty-derivative-device-unvalidated.apk"))
    }
    tasks.matching { it.name in setOf("preBuild", "verifyTermuxOverlay", "verifyTermuxPtyDerivative", "verifyJGitAndroidCompat") }
        .configureEach { dependsOn(preparePrivatePtyDerivative) }
    tasks.register<Exec>("assemblePrivatePtyDerivative") {
        group = "build"
        description = "Build and verify a private, device-unvalidated Java PTY derivative APK"
        dependsOn("assembleDebug", "lintDebug", verifyTermuxPtyDerivative)
        workingDir(rootProject.projectDir)
        commandLine("python3", "-B", rootProject.file("tools/verify_private_pty_apk.py"),
            "--root", rootProject.projectDir,
            "--apk", layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile,
            "--report", layout.buildDirectory.file("outputs/private-pty-derivative.json").get().asFile,
            "--build-tools", File(android.sdkDirectory, "build-tools/36.0.0"),
            "--android-jar", File(android.sdkDirectory, "platforms/android-36/android.jar"),
            "--r8-jar", rootProject.file(requireNotNull(gradle.startParameter.projectProperties["DROIDE_PRIVATE_PTY_R8_JAR"])),
            "--java-home", System.getProperty("java.home"),
            "--debug-keystore", requireNotNull(android.signingConfigs.getByName("debug").storeFile))
        outputs.upToDateWhen { false }
    }
}
// The derivative AAR is built explicitly by the reviewed double-build tool, never implicitly.
val verifyR63ResizeDerivative by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verify the private Java-only derivative against its frozen baseline and double-build evidence"
    dependsOn(verifyTermuxPtyDerivative)
    workingDir(rootProject.projectDir)
    commandLine("python3", "-B", rootProject.file("tools/verify_r63_resize_derivative.py"),
        "--root", rootProject.projectDir)
    outputs.upToDateWhen { false }
}
if (privateR63ResizeDerivative) {
    val preparePrivateR63ResizeDerivative by tasks.registering(Delete::class) {
        delete(layout.buildDirectory.file("outputs/private-r63-resize-derivative.json"),
            layout.buildDirectory.file("outputs/Droide-private-r63-resize-derivative-device-unvalidated.apk"))
    }
    tasks.matching { it.name in setOf("preBuild", "verifyTermuxOverlay", "verifyTermuxPtyDerivative", "verifyR63ResizeDerivative", "verifyJGitAndroidCompat") }
        .configureEach { dependsOn(preparePrivateR63ResizeDerivative) }
    tasks.register<Exec>("assemblePrivateR63ResizeDerivative") {
        group = "build"
        description = "Build and verify a private, device-unvalidated Java resize derivative APK"
        dependsOn("assembleDebug", "lintDebug", verifyR63ResizeDerivative)
        workingDir(rootProject.projectDir)
        commandLine("python3", "-B", rootProject.file("tools/verify_private_r63_resize_apk.py"),
            "--root", rootProject.projectDir,
            "--apk", layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile,
            "--report", layout.buildDirectory.file("outputs/private-r63-resize-derivative.json").get().asFile,
            "--build-tools", File(android.sdkDirectory, "build-tools/36.0.0"),
            "--android-jar", File(android.sdkDirectory, "platforms/android-36/android.jar"),
            "--r8-jar", rootProject.file(requireNotNull(gradle.startParameter.projectProperties["DROIDE_PRIVATE_R63_RESIZE_R8_JAR"])),
            "--java-home", System.getProperty("java.home"),
            "--debug-keystore", requireNotNull(android.signingConfigs.getByName("debug").storeFile))
        outputs.upToDateWhen { false }
    }
}
val verifyCurrentComposite = if (currentDebugVerified) tasks.register<Exec>("verifyCurrentComposite") {
    group = "verification"
    description = "Revalidate current composite inputs; does not admit an APK"
    workingDir(rootProject.projectDir)
    commandLine("python3", "-B", rootProject.file(if (currentCompositeV2) "tools/current_debug_inputs_v2.py" else "tools/current_debug_inputs.py"),
        "--root", rootProject.projectDir,
        "--composite", currentComposite ?: File("CURRENT_MODE_NOT_SELECTED"),
        "--context", gradle.startParameter.projectProperties["DROIDE_CURRENT_CONTEXT"] ?: "CURRENT_MODE_NOT_SELECTED")
    outputs.upToDateWhen { false }
} else null
if (currentDebugVerified) {
    tasks.register("assembleCurrentDebugCandidate") {
        group = "build"
        description = "Build UNADMITTED current debug candidate; external independent APK gate required"
        dependsOn("assembleDebug", "lintDebug", requireNotNull(verifyCurrentComposite))
    }
}
val fdroidTerminalDirectory = if (fdroidDistribution) sourceBuildPath("DROIDE_FDROID_TERMINAL_DIR") else null
val fdroidAnnotations = if (fdroidDistribution) configurations.create("fdroidTerminalCompilerClasspath") {
    isTransitive = false
} else null
val buildFdroidTerminal = if (fdroidDistribution) tasks.register<Exec>("buildFdroidTerminalSources") {
    group = "build"
    description = "Compile all terminal emulator/view sources twice and verify their JNI contract"
    workingDir(rootProject.projectDir)
    doFirst {
        commandLine("python3", "-B", rootProject.file("tools/build_terminal_sources.py"),
            "--root", rootProject.projectDir, "--java-home", System.getProperty("java.home"),
            "--android-jar", File(android.sdkDirectory, "platforms/android-36/android.jar"),
            "--annotations", requireNotNull(fdroidAnnotations).singleFile,
            "--output", requireNotNull(fdroidTerminalDirectory))
    }
    outputs.upToDateWhen { false }
} else null
val terminalEmulatorDependency = if (fdroidDistribution) {
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(requireNotNull(buildFdroidTerminal)) }
    files(File(requireNotNull(fdroidTerminalDirectory), "terminal-engine.aar")).builtBy(requireNotNull(buildFdroidTerminal))
} else if (sourceDebug || sourceRelease) {
    val sourceEngine = if (sourceRelease) sourceReleaseEngine else sourceDebugEngine
    val sourceJdkArchive = if (sourceRelease) sourceReleaseJdkArchive else sourceDebugJdkArchive
    val buildSourceTerminalEngine by tasks.registering(Exec::class) {
        group = "build"
        description = "Build or independently verify the current Java terminal engine"
        workingDir(rootProject.projectDir)
        commandLine("python3", "-B", rootProject.file("tools/terminal_engine_v2.py"),
            if (requireNotNull(sourceEngine).exists()) "verify" else "build",
            "--root", rootProject.projectDir,
            "--java-home", System.getProperty("java.home"),
            "--android-jar", File(android.sdkDirectory, "platforms/android-36/android.jar"),
            "--output", requireNotNull(sourceEngine),
            "--vendor-archive", requireNotNull(sourceJdkArchive))
        outputs.upToDateWhen { false }
    }
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildSourceTerminalEngine) }
    files(File(requireNotNull(sourceEngine), "terminal-engine.aar")).builtBy(buildSourceTerminalEngine)
} else if (currentDebugVerified) {
    tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach {
        dependsOn(requireNotNull(verifyCurrentComposite))
    }
    files(File(requireNotNull(currentComposite), "engine/terminal-engine.aar")).builtBy(requireNotNull(verifyCurrentComposite))
} else if (hostJvmOnly) {
    // This distinct output is admitted only by the existing explicit host task-graph guard.
    // It cannot update or satisfy the release AAR receipt and contains no native binaries.
    val compileTermuxHostTestJar by tasks.registering(Exec::class) {
        group = "verification"
        description = "Compile reviewed Termux Java sources to a distinct TEST-ONLY host JAR"
        workingDir(rootProject.projectDir)
        commandLine("python3", "-B", rootProject.file("tools/build_termux_host_test_jar.py"),
            "--build", "--javac", File(System.getProperty("java.home"), "bin/javac"),
            "--android-jar", File(android.sdkDirectory, "platforms/android-36/android.jar"))
        outputs.upToDateWhen { false }
    }
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(compileTermuxHostTestJar) }
    files(layout.buildDirectory.file("termux-test-only/terminal-emulator-TEST-ONLY.jar"))
        .builtBy(compileTermuxHostTestJar)
} else if (privateR63ResizeDerivative) {
    tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach {
        dependsOn(verifyR63ResizeDerivative)
    }
    files(rootProject.file("third_party/termux/derivatives/r63/build/terminal-emulator-r63-private.aar"))
        .builtBy(verifyR63ResizeDerivative)
} else if (privatePtyDerivative) {
    tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach {
        dependsOn(verifyTermuxPtyDerivative)
    }
    files(rootProject.file("third_party/termux/derivatives/r46/build/terminal-emulator-r46-private.aar"))
        .builtBy(verifyTermuxPtyDerivative)
} else {
    tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach {
        dependsOn(verifyTermuxOverlay)
    }
    files("libs/terminal-emulator-v0.118.0-droide-input.aar").builtBy(verifyTermuxOverlay)
}

// Fail closed on missing/stale provenance or output. This offline check never rebuilds JGit.
val verifyJGitAndroidCompat by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies the pinned Android JGit derivative and its source/build receipt offline"
    workingDir(rootProject.projectDir)
    commandLine("python3", "-B", rootProject.file("tools/verify_jgit_android_compat.py"),
        "--root", rootProject.projectDir)
    outputs.upToDateWhen { false }
}
tasks.matching { it.name == "preBuild" || it.name == "check" }.configureEach {
    if (!fdroidDistribution) dependsOn(verifyJGitAndroidCompat)
}
val fdroidJgitDirectory = if (fdroidDistribution) sourceBuildPath("DROIDE_FDROID_JGIT_DIR") else null
val fdroidJgitClasspath = if (fdroidDistribution) configurations.create("fdroidJgitCompilerClasspath") else null
val buildFdroidJgit = if (fdroidDistribution) tasks.register<Exec>("buildFdroidJgitSources") {
    group = "build"
    description = "Compile the complete Android Git implementation from verified source"
    workingDir(rootProject.projectDir)
    doFirst {
        commandLine("python3", "-B", rootProject.file("tools/build_jgit_sources.py"), "--root", rootProject.projectDir,
            "--java-home", System.getProperty("java.home"), "--output", requireNotNull(fdroidJgitDirectory),
            "--dependency", File(android.sdkDirectory, "platforms/android-36/android.jar"))
        requireNotNull(fdroidJgitClasspath).files.sorted().forEach { args("--dependency", it) }
    }
    outputs.upToDateWhen { false }
} else null
if (fdroidNativeDirectory != null) {
    val buildFdroidNative by tasks.registering(Exec::class) {
        group = "build"
        workingDir(rootProject.projectDir)
        commandLine("python3", "-B", rootProject.file("tools/build_linux_engine_sources.py"),
            "--root", rootProject.projectDir, "--ndk", File(android.sdkDirectory, "ndk/28.2.13676358"),
            "--output", fdroidNativeDirectory)
        outputs.upToDateWhen { false }
    }
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildFdroidNative) }
    val fdroidAssets = layout.buildDirectory.dir("generated/fdroidAssets")
    android.sourceSets.getByName(if (sourceRelease) "release" else "debug").assets.srcDir(fdroidAssets)
    val generateFdroidAssets by tasks.registering(Exec::class) {
        dependsOn(buildFdroidNative, requireNotNull(buildFdroidTerminal), requireNotNull(buildFdroidJgit))
        workingDir(rootProject.projectDir)
        commandLine("python3", "-B", rootProject.file("tools/generate_fdroid_assets.py"),
            "--root", rootProject.projectDir, "--native", fdroidNativeDirectory,
            "--output", fdroidAssets.get().asFile)
        outputs.upToDateWhen { false }
    }
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(generateFdroidAssets) }
}
if (sourceDebug) {
    val verifySourceDebugApk by tasks.registering(Exec::class) {
        group = "verification"
        description = "Verify the freshly built developer APK, JNI exports, signing and alignment"
        dependsOn("assembleDebug")
        workingDir(rootProject.projectDir)
        commandLine("python3", "-B", rootProject.file("tools/verify_source_debug_apk.py"),
            "--root", rootProject.projectDir, "--build-dir", layout.buildDirectory.get().asFile,
            "--engine", requireNotNull(if (fdroidDistribution) fdroidTerminalDirectory else sourceDebugEngine), "--sdk", android.sdkDirectory)
        if (fdroidDistribution) args("--fdroid-candidate", "--native-source", requireNotNull(fdroidNativeDirectory))
        outputs.upToDateWhen { false }
    }
    tasks.register("assembleSourceDebug") {
        group = "build"
        description = "Build current source and native libraries into a device-unvalidated debug APK"
        dependsOn(verifySourceDebugApk)
    }
}
if (sourceRelease) {
    val verifySourceReleaseInputs by tasks.registering(Exec::class) {
        group = "verification"
        workingDir(rootProject.projectDir)
        commandLine("python3", "-B", rootProject.file("tools/verify_source_release.py"), "inputs",
            "--root", rootProject.projectDir)
        if (fdroidDistribution) args("--fdroid-candidate")
        outputs.upToDateWhen { false }
    }
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifySourceReleaseInputs) }
    val verifySourceReleaseApk by tasks.registering(Exec::class) {
        group = "verification"
        description = "Verify release APK native/DEX binding, resources, signing state and alignment"
        dependsOn("assembleRelease", "lintRelease")
        workingDir(rootProject.projectDir)
        commandLine("python3", "-B", rootProject.file("tools/verify_source_release.py"), "apk",
            "--root", rootProject.projectDir, "--build-dir", layout.buildDirectory.get().asFile,
            "--engine", requireNotNull(if (fdroidDistribution) fdroidTerminalDirectory else sourceReleaseEngine), "--sdk", android.sdkDirectory,
            "--signing", if (hasReleaseSigning) "signed" else "unsigned")
        if (fdroidDistribution) args("--fdroid-candidate", "--native-source", requireNotNull(fdroidNativeDirectory))
        outputs.upToDateWhen { false }
    }
    tasks.register("assembleSourceRelease") {
        group = "build"
        description = "Build and verify current source as a release candidate; device validation is separate"
        dependsOn(verifySourceReleaseApk)
    }
}
configurations.configureEach {
    // The signed desktop JAR cannot coexist with the Android derivative or its package bridge.
    resolutionStrategy.componentSelection {
        all {
            if (candidate.group == "org.eclipse.jgit" && candidate.module == "org.eclipse.jgit") {
                reject("Desktop JGit is unsupported; use the pinned jgit-android-compat derivative")
            }
        }
    }
}

dependencies {
    if (fdroidDistribution) add(requireNotNull(fdroidAnnotations).name, "androidx.annotation:annotation-jvm:1.10.0")
    // Already shipped transitively; use its safe event parser for skill block scalars.
    implementation("org.snakeyaml:snakeyaml-engine:3.0.1")
    // Mature streaming TAR/PAX encoder; exact transitive versions are dependency-locked.
    implementation("org.apache.commons:commons-compress:1.28.0")

    if (fdroidDistribution) implementation("org.tukaani:xz:1.12")
    else implementation(files("libs/xz-1.12.jar"))
    
    implementation(terminalEmulatorDependency)
    if (fdroidDistribution) implementation(files(File(requireNotNull(fdroidTerminalDirectory), "terminal-view.aar")).builtBy(requireNotNull(buildFdroidTerminal)))
    else implementation(files("libs/terminal-view-v0.118.0.aar"))
    

    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")


    implementation("io.github.rosemoe:editor:0.24.6")
    implementation("io.github.rosemoe:language-textmate:0.24.6")

    
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okio:okio-jvm:3.17.0")

    
    // Same pinned support artifacts formerly supplied at runtime by Kadb; source now needs them to compile.
    if (!fdroidDistribution) implementation("com.baystudio.compat:spake2-boringssl-compat:1.0.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.84")
    if (!fdroidDistribution) implementation("com.baystudio.compat:kadb-mdns-android-compat:2.1.4")


    if (fdroidDistribution) {
        implementation(files(File(requireNotNull(fdroidJgitDirectory), "jgit-android-source.jar")).builtBy(requireNotNull(buildFdroidJgit)))
        for (coordinate in listOf("com.googlecode.javaewah:JavaEWAH:1.2.3", "org.slf4j:slf4j-api:1.7.36", "commons-codec:commons-codec:1.19.0")) {
            implementation(coordinate)
            add(requireNotNull(fdroidJgitClasspath).name, coordinate)
        }
    } else implementation("com.baystudio.compat:jgit-android-compat:6.10.1.202505221210-r-droide-reconstruction1")
    implementation("androidx.documentfile:documentfile:1.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // Browser host tests call Espresso directly; Compose's runtime dependency is not a compile API.
    testImplementation("androidx.test.espresso:espresso-core:3.5.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.0")
    androidTestImplementation("androidx.test:monitor:1.7.2")
    androidTestImplementation("androidx.test.espresso:espresso-idling-resource:3.6.1")
    androidTestImplementation("com.google.code.findbugs:jsr305:3.0.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")


    implementation("org.jsoup:jsoup:1.18.1")
    
    implementation("io.coil-kt:coil-compose:2.7.0")
}
