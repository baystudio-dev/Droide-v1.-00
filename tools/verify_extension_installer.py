#!/usr/bin/env python3
"""Compile installer contracts, process supervision and the real archive projector on a JVM host."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "app/src/main/java/com/baystudio/droide/core"
TEST = ROOT / "app/src/test/java/com/baystudio/droide/core"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jars", action="append", required=True)
    parser.add_argument("--node-archive", type=Path)
    parser.add_argument("--node-checksums", type=Path)
    args = parser.parse_args()
    if bool(args.node_archive) != bool(args.node_checksums):
        parser.error("Pass both Node archive and checksums")
    jars = sorted({p.resolve() for d in args.jars for p in Path(d).glob("*.jar")})
    for name in ("kotlin-compiler", "kotlin-stdlib", "coroutines-core", "junit", "hamcrest", "okhttp", "okio"):
        if not any(name in p.name for p in jars):
            raise SystemExit(f"Missing externally provisioned JVM dependency: {name}")
    cp = os.pathsep.join(map(str, jars))
    names = ["ExtensionInstallerContractsTest", "InstallerProcessLifecycleTest", "WholeTreeNodeArchiveTest",
        "SafeWholeTreeArchiveSecurityTest", "TrustedArtifactCacheTest", "InstallerOperationCoordinatorTest", "ArtifactTransferIoTest", "InstallerPrerequisiteRestorationTest", "LocalUbuntuNpmLspHealthTest", "LinuxArm64StaticElfAdmissionTest", "LocalUbuntuNodeDistributionTest"]
    with tempfile.TemporaryDirectory(prefix="droide-installer-contracts-") as d:
        temp = Path(d)
        header = "package com.baystudio.droide.core\n"
        vendor = (CORE / "ReviewedVendorArtifactInstaller.kt").read_text().split("data class VendorArtifactInstallRecipe(", 1)[1].split("@Serializable", 1)[0]
        (temp / "VendorContract.kt").write_text(header + "data class VendorArtifactInstallRecipe(" + vendor)
        npm = (CORE / "ReviewedWorkstationRecipeInstaller.kt").read_text().split("data class WorkstationInstallRecipe(", 1)[1].split("@Serializable", 1)[0]
        (temp / "NpmContract.kt").write_text(header + "data class WorkstationInstallRecipe(" + npm)
        enums = (CORE / "DeclarativePackageCatalog.kt").read_text()
        enum_lines = "\n".join(line for line in enums.splitlines() if line.startswith("enum class DeclarativeGitHubArtifact") or line.startswith("enum class DeclarativeWholeTree"))
        terminal = (CORE / "TerminalSession.kt").read_text()
        result = next(line for line in terminal.splitlines() if line.startswith("data class ExecResult("))
        github = (CORE / "ReviewedGitHubReleaseInstaller.kt").read_text()
        format_check = github.split("        fun assetMatchesFormat(", 1)[1].split("\n    }\n}", 1)[0]
        (temp / "Boundaries.kt").write_text(header + "import java.net.URI\n" + enum_lines + "\n" + result + "\n" +
            "object GitHubReleaseInstallRecipe { fun assetMatchesFormat(" + format_check + "\n}\n" +
            "object NetworkSecurity { fun validatePublicHttpsUrl(url: String) { val u=URI(url); require(u.scheme==\"https\" && u.host!=null && u.rawUserInfo==null && u.host!=\"localhost\" && u.host!=\"127.0.0.1\") } }\n" +
            "object DeviceWorkstationStorageGuard { fun formatBytes(n: Long)=\"$n bytes\" }\n" +
            "object WorkstationGuestEnvironmentSpec { const val ID=\"alpine-3.24-aarch64\" }\n" +
            "object FoundryUbuntuGuestEnvironmentSpec { const val ID=\"ubuntu-base-24.04.5-arm64\" }\n" +
            "interface ReviewedGuestExecutionEnvironment { val environmentId: String; suspend fun execute(argv: List<String>, maxOutputBytes: Int): ExecResult }\n")
        archive = (CORE / "ReviewedGitHubWholeTreeInstaller.kt").read_text().split("internal data class SafeTreeFile(", 1)[1]
        imports = "\n".join(line for line in (CORE / "ReviewedGitHubWholeTreeInstaller.kt").read_text().splitlines() if line.startswith("import java."))
        (temp / "ArchiveProjector.kt").write_text(header + imports + "\ninternal data class SafeTreeFile(" + archive)
        (temp / "NodeArchiveHarness.kt").write_text(header + r'''
import java.io.File
import java.security.MessageDigest
fun main(args: Array<String>) {
    val archive = File(args[0]); val recipe = LocalUbuntuNodeDistribution.resolve(File(args[1]).readText())
    val digest = MessageDigest.getInstance("SHA-256")
    archive.inputStream().buffered().use { input ->
        val buffer=ByteArray(65536)
        while (true) { val n=input.read(buffer); if(n<0) break; digest.update(buffer,0,n) }
    }
    check(digest.digest().joinToString("") { "%02x".format(it) } == recipe.assetSha256)
    SafeWholeTreeArchive.project(archive, recipe.artifactFormat, emptySet(), recipe.treeMaxFiles!!,
        recipe.treeMaxUnpackedBytes!!, recipe.treeLinkPolicy, recipe.treeModePolicy).use { p ->
        val primary=p.files.single { it.relativePath==recipe.treeEntryPoint }
        LinuxArm64ElfAdmission.requireUbuntuGlibc(primary.file)
        for ((name,target) in recipe.treeAuxCommands) {
            val link=p.symlinks.single { it.relativePath==target }
            val file=p.files.single { it.relativePath==link.resolvedPath }
            check(LocalUbuntuNodeDistribution.isAuxScript(recipe.familyId,recipe.version,name,target,file.file))
        }
        println("OFFICIAL_NODE_ARM64_PROJECTION_OK files=${p.files.size} links=${p.symlinks.size} bytes=${p.totalBytes} version=${recipe.version}; ELF admission only, ARM64 code not executed")
    }
}
''')
        sources = [CORE / (n + ".kt") for n in ["UbuntuExtensionRecipes", "LocalUbuntuNodeDistribution", "InstallerProgressLog",
            "PackageRuntimeCompatibility", "LinuxArm64ElfAdmission", "PathSecurity", "LocalProcessSupervisor", "MonotonicProcessDeadline", "ProcessInputBuffer", "BoundedOutput", "TailByteBuffer", "Utf8StreamDecoder", "TrustedArtifactCache"]]
        sources += [CORE / (n + ".kt") for n in ["InstallerOperationCoordinator", "InstallerPrerequisiteRestoration", "LocalUbuntuNpmLspHealth", "ArtifactTransferIo", "OkHttpAwait"]]
        sources += [TEST / (n + ".kt") for n in names] + [TEST / "ProviderLoopbackServer.kt"] + sorted(temp.glob("*.kt"))
        classes = temp / "classes"
        subprocess.run(["java", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
            "-jvm-target", "17", "-classpath", cp, "-d", str(classes), *map(str, sources)], check=True)
        runtime = str(classes) + os.pathsep + cp
        subprocess.run(["java", "-cp", runtime, "org.junit.runner.JUnitCore", *["com.baystudio.droide.core." + n for n in names]], check=True)
        if args.node_archive:
            subprocess.run(["java", "-cp", runtime, "com.baystudio.droide.core.NodeArchiveHarnessKt",
                str(args.node_archive.resolve()), str(args.node_checksums.resolve())], check=True)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as failure:
        raise SystemExit(f"Installer verification failed (exit {failure.returncode})") from None
