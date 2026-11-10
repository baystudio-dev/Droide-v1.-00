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

// Developer APKs rebuild current JNI sources and the reviewed Java terminal engine. Historical
// release/private receipts remain immutable and retain their own gates.
val sourceDebug = providers.gradleProperty("DROIDE_SOURCE_DEBUG").orNull?.let {
    it.toBooleanStrictOrNull() ?: throw GradleException("DROIDE_SOURCE_DEBUG must be true or false")
} ?: false
// An explicit release-variant build of current sources. Device/distribution admission remains
// separate; historical terminal receipts are never rewritten.
val sourceRelease = providers.gradleProperty("DROIDE_SOURCE_RELEASE").orNull?.let {
    it.toBooleanStrictOrNull() ?: throw GradleException("DROIDE_SOURCE_RELEASE must be true or false")
} ?: false
fun sourceBuildPath(property: String): File = File(requireNotNull(gradle.startParameter.projectProperties[property]) {
    "Source build requires explicit $property"
}).also { require(it.isAbsolute) { "$property must be an absolute path" } }
val sourceDebugEngine = if (sourceDebug) sourceBuildPath("DROIDE_SOURCE_DEBUG_ENGINE_DIR") else null
val sourceDebugJdkArchive = if (sourceDebug) sourceBuildPath("DROIDE_SOURCE_DEBUG_JDK_ARCHIVE") else null
val sourceReleaseEngine = if (sourceRelease) sourceBuildPath("DROIDE_SOURCE_RELEASE_ENGINE_DIR") else null
val sourceReleaseJdkArchive = if (sourceRelease) sourceBuildPath("DROIDE_SOURCE_RELEASE_JDK_ARCHIVE") else null
if (sourceRelease) {
    require(gradle.startParameter.projectProperties["DROIDE_SOURCE_RELEASE"] == "true")
    require(!hostJvmOnly && !sourceDebug) { "Source release cannot be combined with another build mode" }
    require(gradle.startParameter.taskNames == listOf(":app:assembleSourceRelease")) {
        "Source release requires only :app:assembleSourceRelease"
    }
    require(gradle.startParameter.excludedTaskNames.isEmpty()) { "Source release forbids task exclusions" }
    require(gradle.startParameter.dependencyVerificationMode.name == "STRICT")
    require(!gradle.startParameter.isWriteDependencyLocks && gradle.startParameter.writeDependencyVerifications.isEmpty())
    layout.buildDirectory.set(layout.projectDirectory.dir("build-source-release"))
    gradle.taskGraph.whenReady {
        // AGP also uses "Debug" in release native-symbol task names. These process release binaries;
        // they do not compile the debug variant.
        val releaseSymbolTasks = setOf("stripReleaseDebugSymbols", "mergeReleaseNativeDebugMetadata")
        val forbidden = allTasks.filter {

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
    require(!hostJvmOnly) { "Source debug cannot be combined with another build mode" }
    require(gradle.startParameter.taskNames == listOf(":app:assembleSourceDebug")) {
        "Source debug requires only :app:assembleSourceDebug"
    }
    require(gradle.startParameter.excludedTaskNames.isEmpty()) { "Source debug forbids task exclusions" }
    require(gradle.startParameter.dependencyVerificationMode.name == "STRICT")
    require(!gradle.startParameter.isWriteDependencyLocks && gradle.startParameter.writeDependencyVerifications.isEmpty())
    layout.buildDirectory.set(layout.projectDirectory.dir("build-source-debug"))
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
        externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    }
    if (sourceDebug) sourceSets.getByName("debug").manifest.srcFile("../tools/source-debug-manifest.xml")
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    sourceSets.getByName("main").java.srcDir("../third_party/kadb/droide-source/src/main/kotlin")

    defaultConfig {
        applicationId = "com.baystudio.droide"
        minSdk = 29
        targetSdk = 36
        versionCode = 44
        versionName = "1.00"
        testApplicationId = "com.baystudio.droide.test"
        testInstrumentationRunner = if (frontendDeviceTests) "androidx.test.runner.AndroidJUnitRunner"
            else "com.baystudio.droide.PhysicalDeviceSmokeInstrumentation"
        testFunctionalTest = true
        buildConfigField("boolean", "EXPLORER_EXPORT_EXPERIMENTAL", explorerExportExperimental.toString())
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

            if (sourceDebug || sourceRelease) {
                keepDebugSymbols += setOf("**/libtermux.so", "**/libdroide_files.so")
            }
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libdroide_proot.so", "**/libproot-loader.so", "**/libproot-loader32.so", "**/libdroide_cli.so")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"

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
    lockAllConfigurations()
}

val terminalEmulatorDependency = if (sourceDebug || sourceRelease) {
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
} else if (hostJvmOnly) {
    // This distinct output is admitted only by the existing explicit host task-graph guard. It
    // cannot update or satisfy the release AAR receipt and contains no native binaries.
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
} else {
    tasks.matching { it.name == "preBuild" }.configureEach {
        doFirst {
            throw GradleException("Select a documented source build or host test command; see docs/BUILDING.md")
        }
    }
    files("libs/terminal-emulator-v0.118.0-java.aar")
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
    dependsOn(verifyJGitAndroidCompat)
}
if (sourceDebug) {
    val verifySourceDebugApk by tasks.registering(Exec::class) {
        group = "verification"
        description = "Verify the freshly built developer APK, JNI exports, signing and alignment"
        dependsOn("assembleDebug")
        workingDir(rootProject.projectDir)
        commandLine("python3", "-B", rootProject.file("tools/verify_source_debug_apk.py"),
            "--root", rootProject.projectDir, "--build-dir", layout.buildDirectory.get().asFile,
            "--engine", requireNotNull(sourceDebugEngine), "--sdk", android.sdkDirectory)
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
            "--engine", requireNotNull(sourceReleaseEngine), "--sdk", android.sdkDirectory,
            "--signing", if (hasReleaseSigning) "signed" else "unsigned")
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

    implementation("org.snakeyaml:snakeyaml-engine:3.0.1")
    // Mature streaming TAR/PAX encoder; exact transitive versions are dependency-locked.
    implementation("org.apache.commons:commons-compress:1.28.0")

    implementation(files("libs/xz-1.12.jar"))

    implementation(terminalEmulatorDependency)
    implementation(files("libs/terminal-view-v0.118.0.aar"))

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

    // Same pinned support artifacts formerly supplied at runtime by Kadb; source now needs them to
    // compile.
    implementation("com.baystudio.compat:spake2-boringssl-compat:1.0.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.84")
    implementation("com.baystudio.compat:kadb-mdns-android-compat:2.1.4")

    implementation("com.baystudio.compat:jgit-android-compat:6.10.1.202505221210-r-droide-reconstruction1")
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
