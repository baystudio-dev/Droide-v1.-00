#!/usr/bin/env python3
from __future__ import annotations
import hashlib, json, re, sys
import xml.etree.ElementTree as ET
from pathlib import Path
sys.dont_write_bytecode = True
from verify_dependency_trust import inspect as inspect_dependency_trust
from verify_jgit_android_compat import verify as verify_jgit_android_compat

ROOT = Path(__file__).resolve().parents[1]
JGIT_VERSION = "6.10.1.202505221210-r-droide-reconstruction1"
JGIT_COORDINATE = "com.baystudio.compat:jgit-android-compat:" + JGIT_VERSION
JGIT_ARTIFACT_HASHES = {
    "jgit-android-compat-" + JGIT_VERSION + ".jar": "34ddf32173ff109392e7b123aae599426cd846aee02bb28d6fff550bd141ed3b",
    "jgit-android-compat-" + JGIT_VERSION + ".pom": "01ffcf4b7352e84b98feb94d9cd2593db5bfe3564c51030aa5d73b0c135a06cb",
}
JGIT_LOCK_CONFIGURATIONS = "debugCompileClasspath,debugRuntimeClasspath,debugUnitTestCompileClasspath,debugUnitTestRuntimeClasspath,releaseAndroidTestCompileClasspath,releaseCompileClasspath,releaseRuntimeClasspath,releaseUnitTestCompileClasspath,releaseUnitTestRuntimeClasspath"

def fail(message: str) -> None:
    raise SystemExit(f"DEPENDENCY_POLICY_FAILED: {message}")

def text(path: str) -> str:
    p = ROOT / path
    if not p.is_file(): fail(f"missing {path}")
    return p.read_text(encoding="utf-8")

def direct_runtime_dependencies(app: str) -> set[str]:
    out: set[str] = set()
    for value in re.findall(r'implementation\(\s*"([^"]+)"\s*\)', app): out.add(value)
    for value in re.findall(r'implementation\(\s*platform\(\s*"([^"]+)"\s*\)\s*\)', app): out.add(value)
    for value in re.findall(r'implementation\(\s*files\(\s*"libs/([^"]+)"\s*\)\s*\)', app): out.add(f"local:{value}")
    if 'implementation(terminalEmulatorDependency)' in app:
        expected = 'files(File(requireNotNull(sourceEngine), "terminal-engine.aar")).builtBy(buildSourceTerminalEngine)'
        if expected not in app:
            fail("terminal engine must be built and verified from the pinned source recipe")
        out.add("local:terminal-engine.aar")
    return out

def inspect_jgit_compat(app: str) -> None:
    declared = direct_runtime_dependencies(app)
    if JGIT_COORDINATE not in declared or any(c.startswith("org.eclipse.jgit:org.eclipse.jgit:") for c in declared):
        fail("the exact Android JGit derivative must replace desktop JGit")
    rows = text("app/gradle.lockfile").splitlines()
    jgit_rows = [r for r in rows if r.startswith(("org.eclipse.jgit:org.eclipse.jgit:", "com.baystudio.compat:jgit-android-compat:"))]
    if jgit_rows != [JGIT_COORDINATE + "=" + JGIT_LOCK_CONFIGURATIONS]:
        fail("Android JGit lock coordinate/configurations differ or desktop JGit remains locked")
    # Verify complete upstream/source/receipt/output integrity; never accept source-only state.
    try:
        receipt = verify_jgit_android_compat(ROOT)
    except (OSError, ValueError, KeyError) as failure:
        fail("Android JGit provenance: " + str(failure))
    prefix = "third_party/maven/com/baystudio/compat/jgit-android-compat/" + JGIT_VERSION + "/"
    if receipt["artifacts"] != {prefix + name: digest for name, digest in JGIT_ARTIFACT_HASHES.items()}:
        fail("Android JGit output differs from the reviewed measured reconstruction")
    ns = {"v": "https://schema.gradle.org/dependency-verification"}
    metadata = ET.fromstring(text("gradle/verification-metadata.xml"))
    matches = [c for c in metadata.findall("v:components/v:component", ns)
               if (c.get("group"), c.get("name"), c.get("version")) ==
               ("com.baystudio.compat", "jgit-android-compat", JGIT_VERSION)]
    if len(matches) != 1:
        fail("Android JGit requires one exact verification metadata component")
    artifacts = matches[0].findall("v:artifact", ns)
    if len(artifacts) != 2 or {a.get("name") for a in artifacts} != set(JGIT_ARTIFACT_HASHES):
        fail("Android JGit verification artifact inventory differs")
    for artifact in artifacts:
        pins = artifact.findall("v:sha256", ns)
        if len(pins) != 1 or pins[0].get("value") != JGIT_ARTIFACT_HASHES[artifact.get("name")] or list(pins[0]):
            fail("Android JGit requires a single measured SHA-256 without alternate trust")
    upstream = metadata.findall("v:components/v:component[@group='org.eclipse.jgit'][@name='org.eclipse.jgit'][@version='6.10.1.202505221210-r']/v:artifact[@name='org.eclipse.jgit-6.10.1.202505221210-r.jar']/v:sha256", ns)
    if not any(p.get("value") == "8f0135ca45d00c4da8e7ba2e96d44e1ade452bf279d79ca4eb54921e8f27952c" for p in upstream):
        fail("original JGit provenance checksum must remain recorded")
    legal = ROOT / "app/src/main/assets/legal/licenses"
    if (legal / "JGit-UPSTREAM-ABOUT.html").read_bytes() != (ROOT / "third_party/jgit/UPSTREAM_ABOUT.html").read_bytes():
        fail("packaged JGit upstream notice differs from the original")
    for name, expected in {
        "JGit-EDL-1.0.txt": "10f5ea95859c008c924854e945ef92e49088ba9abb8c818b525b80d960170cf8",
        "JGit-Android-Compatibility-NOTICE.txt": "b42271565b8c88bd5533058fb0283624d1e0e92fc1f88017d85f8b3e94161fe0",
    }.items():
        if hashlib.sha256((legal / name).read_bytes()).hexdigest() != expected:
            fail("JGit packaged license/complete bundled MIT notice changed: " + name)

def main() -> None:
    gradle_files = list(ROOT.glob("*.gradle.kts")) + list((ROOT / "app").glob("*.gradle.kts"))
    body = "\n".join(p.read_text(encoding="utf-8") for p in gradle_files)
    for pattern, message in {
        r"(?i)jitpack": "JitPack repository must not be enabled at build time",
        r"(?i)(?:latest\.|-SNAPSHOT|:\+\b|\[[^\]]*,[^\]]*\])": "dynamic or snapshot dependency version detected",
        r"(?i)http://": "cleartext repository URL detected",
    }.items():
        if re.search(pattern, body): fail(message)

    settings = text("settings.gradle.kts")
    for token in ["RepositoriesMode.FAIL_ON_PROJECT_REPOS", 'name = "DroideLocalCompat"', 'includeGroup("com.baystudio.compat")', 'excludeGroup("com.baystudio.compat")']:
        if token not in settings: fail(f"settings.gradle.kts missing repository hardening token: {token}")

    app = text("app/build.gradle.kts")
    for token in ["dependencyLocking {", "lockAllConfigurations()", JGIT_COORDINATE, "io.github.rosemoe:editor:0.24.6", "io.github.rosemoe:language-textmate:0.24.6"]:
        if token not in app: fail(f"app/build.gradle.kts missing required pin/control: {token}")
    inspect_jgit_compat(app)

    legal_path = ROOT / "app/src/main/assets/legal/open_source_licenses.json"
    if not legal_path.is_file(): fail("open-source license catalog missing")
    try: legal = json.loads(legal_path.read_text(encoding="utf-8"))
    except Exception as exc: fail(f"invalid open-source license catalog: {exc}")
    if legal.get("closureStatus") != "DIRECT_RUNTIME_AND_RESOLVED_RELEASE_RUNTIME_COMPLETE":
        fail("license catalog closure status is not release-complete")
    entries = legal.get("entries") or []
    if not entries: fail("open-source license catalog empty")

    mapped: dict[str, str] = {}
    for entry in entries:
        asset = ROOT / "app/src/main/assets" / entry.get("licenseTextAsset", "")
        if not asset.is_file() or asset.stat().st_size == 0: fail(f"missing license text for {entry.get('id')}: {entry.get('licenseTextAsset')}")
        for key in entry.get("dependencyKeys", []):
            if key in mapped: fail(f"dependency mapped twice: {key}")
            mapped[key] = entry.get("id", "")

    declared = direct_runtime_dependencies(app)
    missing = sorted(declared - set(mapped))
    stale = sorted(set(mapped) - declared)
    if missing: fail("direct runtime dependency has no license mapping: " + ", ".join(missing))
    if stale: fail("license catalog has stale direct dependency mapping: " + ", ".join(stale))

    if not (ROOT / "third_party/ARTIFACTS_SHA256.txt").is_file(): fail("third-party integrity manifest missing")
    workflows = sorted((ROOT / ".github/workflows").glob("*.yml"))
    if not workflows: fail("no GitHub Actions workflow found")
    for path in workflows:
        workflow = path.read_text(encoding="utf-8")
        for use in re.findall(r"\buses:\s*([^\s#]+)", workflow):
            if "@" not in use or not re.fullmatch(r"[0-9a-f]{40}", use.rsplit("@",1)[1]):
                fail(f"GitHub Action is not pinned to an immutable commit SHA in {path.name}: {use}")

    try: inspect_dependency_trust(ROOT)
    except (ValueError, OSError) as failure: fail(str(failure))

    print(f"DEPENDENCY_POLICY_OK direct_runtime={len(declared)} mapped={len(mapped)}")

if __name__ == "__main__": main()
