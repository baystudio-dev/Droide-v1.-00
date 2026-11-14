#!/usr/bin/env python3
"""Inventory actual compiled extension catalogs and local installer admission.

Use a separate, successfully compiled Android source copy. Matching source files
are checked before invoking the production policies. This does not install or
execute extension commands; --require-local-routes makes uncovered families fail.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CORE = Path("app/src/main/java/com/baystudio/droide/core")
INPUTS = [
    CORE / (name + ".kt") for name in [
        "DevelopmentExtensions", "DevelopmentExtensionsManager", "DevelopmentExtensionProjection", "LanguageRegistry", "LanguageRuntimePresentation",
        "LanguageServerRegistry", "DebugAdapterRegistry", "SourceRunPreparation", "PathSecurity", "UbuntuExtensionRecipes", "ReviewedWorkstationRecipeInstaller",
        "LocalUbuntuNpmPolicy", "LocalUbuntuNpmRuntimePolicy", "LocalUbuntuTypeScriptServerPolicy", "FoundryUbuntuAptPolicy", "FoundryUbuntuLayerReleasePolicy", "LocalUbuntuRubyPolicy", "LocalUbuntuRubyInstaller", "LocalAndroidWorkstation", "LocalUbuntuNodeDistribution", "DeclarativePackageCatalog",
        "PackageSourceResolver", "PackageRuntimeCompatibility", "PackageRuntimeDependencyEngine",
        "PackageProtocolCapability", "LocalUbuntuReviewedRawPolicy", "LocalUbuntuReviewedArtifactPolicy", "AndroidLocalComponentCatalog",
        "LocalUbuntuVendorCatalog", "LocalUbuntuVendorResolver", "LocalUbuntuVendorPolicy", "LocalUbuntuVendorArchive", "LocalUbuntuJsDebugPolicy",
        "LocalUbuntuBundleAliasPolicy", "LocalUbuntuBundleAliasInstaller", "LocalCommandProjectionPolicy",
    ]
]
INPUTS += [Path("app/src/main/assets/droide-package-catalog-v1.json"),
           Path("app/src/main/assets/android-local-component-catalog.json")]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiled-source-root", type=Path, required=True)
    parser.add_argument("--classes", type=Path, required=True, help="Compiled Android main Kotlin classes from that source copy")
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--jars", type=Path, action="append", required=True, help="Provisioned matching Kotlin compiler/runtime and OkHttp/Okio jars; repeatable")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--require-local-routes", action="store_true")
    args = parser.parse_args()
    inputs = {}
    for relative in INPUTS:
        original, compiled = ROOT / relative, args.compiled_source_root / relative
        if not original.is_file() or not compiled.is_file():
            raise SystemExit(f"Missing inventory source input: {relative}")
        inputs[str(relative)] = digest(original)
        if digest(compiled) != inputs[str(relative)]:
            raise SystemExit(f"Compiled source copy is stale: {relative}; compile the current source before inventory")
    jars = sorted({p.resolve() for directory in args.jars for p in directory.glob("*.jar")})
    for dependency in ("kotlin-compiler-embeddable", "kotlin-stdlib", "serialization-core", "serialization-json", "okhttp", "okio"):
        if not any(dependency in p.name for p in jars):
            raise SystemExit(f"Missing JVM dependency: {dependency}")
    for path in [args.classes, args.android_jar]:
        if not path.exists():
            raise SystemExit(f"Missing compiled runtime input: {path}")
    classpath = os.pathsep.join(map(str, [args.classes.resolve(), args.android_jar.resolve(), *jars]))
    with tempfile.TemporaryDirectory(prefix="droide-extension-catalog-") as temporary:
        classes = Path(temporary) / "classes"
        subprocess.run(["java", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath,
                        f"-Xfriend-paths={args.classes.resolve()}", "-d", str(classes),
                        str(ROOT / "tools/catalog-regressions/ExtensionCatalogInventory.kt")], check=True)
        run = subprocess.run(["java", "-cp", str(classes) + os.pathsep + classpath,
                              "com.baystudio.droide.core.ExtensionCatalogInventoryKt",
                              str(ROOT / "app/src/main/assets/droide-package-catalog-v1.json"),
                              str(ROOT / "app/src/main/assets/android-local-component-catalog.json")],
                             text=True, capture_output=True, check=True)
    inventory = json.loads(run.stdout)
    inventory["sourceSha256"] = inputs
    inventory["jvmJarSha256"] = {p.name: digest(p) for p in jars}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(inventory, indent=2, ensure_ascii=False) + "\n")
    gaps = [family["familyId"] for family in inventory["families"] if family["classification"] == "NO_LOCAL_INSTALL_ROUTE"]
    print(json.dumps({"counts": inventory["counts"], "uncoveredLocalFamilies": gaps, "output": str(args.output)}, indent=2))
    if args.require_local_routes and gaps:
        raise SystemExit(f"Local extension coverage failed: {len(gaps)} families lack a local install route")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as failure:
        if failure.stderr:
            print(failure.stderr)
        raise SystemExit(f"Catalog inventory failed (exit {failure.returncode}); see diagnostics above") from None
