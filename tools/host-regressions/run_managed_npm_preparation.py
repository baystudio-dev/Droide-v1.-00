#!/usr/bin/env python3
"""Execute exact npm preparation source slices with offline HTTP/process fixtures.

This is a host branch test, not Android/guest installation certification. Production
statements are copied verbatim; only the enclosing Android class and its external
HTTP, process, state, and Node-download collaborators are replaced. The runtime
policy executes its real JavaScript with npm's installed semver on the host.
No Gradle invocation, guest install, live HTTP, or production source write occurs.
"""
import argparse, hashlib, json, os, pathlib, subprocess, shutil

p = argparse.ArgumentParser()
p.add_argument('--root', type=pathlib.Path, required=True)
p.add_argument('--toolchain', type=pathlib.Path, required=True)
p.add_argument('--output', type=pathlib.Path, required=True)
p.add_argument('--expected-catalog-requests', type=int, default=1, choices=(1, 2))
p.add_argument('--fixture', type=pathlib.Path, default=pathlib.Path(__file__).with_name('ManagedNpmPreparationR44Test.kt'))
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=True)
core = a.root / 'app/src/main/java/com/baystudio/droide/core'
manifest = {}
def read(name):
    content = (core / name).read_text()
    manifest[name] = hashlib.sha256(content.encode()).hexdigest()
    return content
def between(content, start, end):
    if content.count(start) != 1:
        raise ValueError(f'Expected one source boundary: {start!r}')
    first = content.index(start)
    return content[first:content.index(end, first)]
source = read('ManagedExtensionInstaller.kt')
branch = between(source, '        UbuntuNpmCatalog.find(item.family.id)?.let { entry ->', '        LocalUbuntuBundleAliasPolicy.find(item.family.id)')
plan = between(source, '    private suspend fun npmPlan(', '    private suspend fun resolveNode(')
probe = between(source, '    private suspend fun hasNodeRuntime(', '    private suspend fun selectedLocalManagedNode(')
recipes = between(read('ReviewedWorkstationRecipeInstaller.kt'), 'data class WorkstationInstallRecipe(', '@Serializable\nprivate data class ReviewedRecipeLock')
catalog = read('UbuntuExtensionRecipes.kt').split('object UbuntuNpmCatalog {', 1)[1]
catalog = 'object UbuntuNpmCatalog {' + catalog
policy = read('LocalUbuntuNpmPolicy.kt')
policy = between(policy, 'internal object LocalUbuntuNpmPolicy {', '    fun isLanguageServerCommand') + between(policy, '    fun requireRegistryTarball(', '    suspend fun reusableLock(') + '\n}\n'
runtime = read('LocalUbuntuNpmRuntimePolicy.kt').split('internal object LocalUbuntuNpmRuntimePolicy {', 1)[1]
runtime = 'internal object LocalUbuntuNpmRuntimePolicy {' + runtime
# Skip unrelated record/path functions, retaining exact engine, command, compatible, script.
runtime = runtime[:runtime.index('    /** Package engine compatibility')] + runtime[runtime.index('    val script ='):]
ts = read('LocalUbuntuTypeScriptServerPolicy.kt').split('internal object LocalUbuntuTypeScriptServerPolicy {', 1)[1]
ts = 'internal object LocalUbuntuTypeScriptServerPolicy {' + ts
fixture = a.fixture.read_text()
fixture = fixture.replace('// @PRODUCTION_DECLARATIONS@', recipes + catalog + policy + runtime + ts)
fixture = fixture.replace('// @CATALOG_BRANCH@', branch)
fixture = fixture.replace('// @PRODUCTION_METHODS@', plan + probe)
program = a.output / 'ManagedNpmPreparationR44Test.kt'
program.write_text(fixture)
(a.output / 'source-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
lib = a.toolchain / 'gradle-8.13/lib'
java = a.toolchain / 'jdk-17.0.20.1+1/bin/java'
jars = [lib / name for name in ('kotlin-stdlib-2.0.21.jar', 'kotlinx-coroutines-core-jvm-1.6.4.jar', 'kotlinx-serialization-core-jvm-1.6.2.jar', 'kotlinx-serialization-json-jvm-1.6.2.jar', 'junit-4.13.2.jar', 'hamcrest-core-1.3.jar', 'annotations-24.0.1.jar')]
for path in [java] + jars:
    if not path.is_file(): raise FileNotFoundError(path)
if not shutil.which('node'): raise RuntimeError('A host Node.js with npm/semver is required')
cp = os.pathsep.join(map(str, jars))
classes = a.output / 'classes'
classes.mkdir(exist_ok=True)
compile_cmd = [str(java), '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(classes), str(program)]
compiled = subprocess.run(compile_cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
(a.output / 'compile.log').write_text(compiled.stdout)
if compiled.returncode:
    print(compiled.stdout)
    raise SystemExit(compiled.returncode)
cmd = [str(java), f'-Ddroide.npm.expectedCatalogRequests={a.expected_catalog_requests}', '-cp', str(classes) + os.pathsep + cp, 'org.junit.runner.JUnitCore', 'com.baystudio.droide.core.ManagedNpmPreparationR44Test']
result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
(a.output / 'results.txt').write_text(result.stdout)
print(result.stdout)
raise SystemExit(result.returncode)
