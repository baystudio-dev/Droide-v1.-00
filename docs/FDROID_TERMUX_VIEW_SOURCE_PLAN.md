# Termux View source-build closure and validation plan

Investigation date: 2026-11-13 (Asia/Jakarta). Current observed status: **two
independent complete View source compilations succeeded with identical output;
the strict metadata gate correctly failed on three paths in two constructors**.
Android resource linking, device validation and production replacement remain
unverified. The subsequent exact-pin compatibility experiment is described
below; it does not reclassify the raw compiler result as a pass.

Subsequent implementation checkpoint: `tools/fdroid_termux_view_source_build.py`
and the independent `tools/fdroid_termux_view_metadata.py` comparator now
implement the offline Java proof described below. **265 source-proof tests
passed in the enforced local sandbox**, including 39 View builder tests, 31
annotation/bytecode metadata tests, and the existing source/sandbox contracts.
Both inspection APIs accepted all nineteen pinned reference classes as data,
with exactly five resource GETSTATIC reads. That local-test checkpoint alone
was not a compiler result; the later actual compiler evidence is recorded in
section 7 and the [compiler metadata note](FDROID_TERMUX_COMPILER_METADATA.md).

This document and its proof implementation do not change a dependency, production build, reference binary,
integrity pin, runtime capability, release APK, or signing configuration. It
records an isolated source-build proof, not an accepted F-Droid recipe or
permission to replace the stable View AAR. Compilers ran only in the reviewed
offline source-proof sandbox. No generated View target class was executed.

## 1. Source closure

The inspected View source archive contains seven Java files. A candidate build
also needs generated release `BuildConfig`, compile-time resource symbols,
Android SDK classes, a terminal emulator compiled from the reviewed source
closure, and the AndroidX `RequiresApi` annotation. None of the seven files
imports Kotlin, `com.termux.shared`, a desktop UI library, or another AndroidX UI
library. There is no Kotlin source to compile in this module.

The current View AAR has exactly nineteen class files, all Java class major
version 52. It contains no native libraries, assets, bundled dependency JARs,
Kotlin metadata, or ProGuard rules. This small closure supports a source rebuild
without deleting terminal functionality, subject to the gates below.

| Input | Identity or constraint |
| --- | --- |
| View source archive | `third_party/termux/terminal-view-v0.118.0-sources.jar` |
| Source archive SHA-256 | `93449d6faf0fa0c0e1b89595959d35f4f84bb2f04f7a3d06dc78226677c7f09d` |
| Immutable upstream | `termux/termux-app`, commit `6e2689f55295fa444be8ac8592c527c2c5ef3253`, tag `v0.118.0` |
| Java provenance manifest | `third_party/termux/UPSTREAM_PROVENANCE.json`, `artifacts.terminalViewSources.gitBlobSha1ByPath` |
| Resource provenance manifest | `third_party/termux/view-source-resources/PROVENANCE.json` |
| Source-only resource verifier | `tools/fdroid_termux_view_sources.py` |
| Emulator compiler dependency | Candidate classes rebuilt from the current eighteen-source, forty-class closure described by `tools/fdroid_termux_source_build.py`; no vendored emulator bytecode on the View compiler classpath |
| Existing pinned JDK | Temurin `17.0.20.1+1`, verified archive and complete installed tree through `tools/terminal_jdk_v2.py` and `tools/terminal-engine-v2-jdk.json` |
| JDK archive SHA-256 | `3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e` |
| Current Android input | API 36 `android.jar`, SHA-256 `d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a` |
| Current resolved annotation artifact | `androidx.annotation:annotation-jvm:1.10.0`, JAR SHA-256 `ddd072ddbb553178e205517ce777b2f05aa9e412982d9ecb4eedc74f1212f697` |
| Existing resource tool, if used | `com.android.tools.build:aapt2:8.13.2-14304508:linux`, archive SHA-256 `839609d6d776d6dd60a02aa577d97193ce3e650cf1deaabf062321e23bbd6bf6` |
| View reference AAR, inspection only | `app/libs/terminal-view-v0.118.0.aar`, SHA-256 `06b3e83cf34c40e82ba5348a88fc72657ebf35177e28f567e86b2389c34cd2d0` |
| Reference nested `classes.jar` SHA-256 | `9bceb16dc4d35f412aff579a11fcf4498bd5935dbbee85b9adb11c4ea3ec9068` |

Tool and dependency hashes in this table were read from existing repository
contracts and Gradle verification metadata; they are not a claim that fresh
remote toolchain or registry downloads were verified during this investigation.
The source archive, reference AAR, nested JAR, and source contents were inspected
locally. Existing reference binaries are comparison data, never compiler inputs
or a source for copying classes into the candidate.

### Exact Java sources

Paths below are relative to the source archive root. SHA-256 values were
computed from the archived file bytes; the Git blob identities are recorded in
the existing upstream provenance manifest.

| Source path | SHA-256 | Git blob SHA-1 |
| --- | --- | --- |
| `com/termux/view/GestureAndScaleRecognizer.java` | `0df6451d1187ffe2d4c9aa5496cdcc04c96f75839cc990e70b40afaf0bd6e071` | `f7fc9d23700905f7e0cdffc13d9b55b63113e23c` |
| `com/termux/view/TerminalRenderer.java` | `52db93a02a856e3da3fb79d296d200f5dab2167c97101e46633ed558331f76b7` | `307e4226946862eebab505c0d7946cf953a10bcb` |
| `com/termux/view/TerminalView.java` | `04ca7b03cd225bd6c7b54fe7377b239f6b6ce3724d6c5acc5491ec8dab28d59e` | `2b56d66ea6312cc814afea11c800512b8ee79a2f` |
| `com/termux/view/TerminalViewClient.java` | `8f8e647e734e7a888104511979f00084e2e5fc0a887ebbd56c601fd4c314a795` | `d6b49b892ca1ab179d20df44a2fdfc2864dc0f09` |
| `com/termux/view/textselection/CursorController.java` | `6a7331f375881be9b24d34467900ec84a232ca07feeff69575b3b642abdc3e60` | `f0e1cc5e451cf80e6c30cbcfd784828fcfb9c86f` |
| `com/termux/view/textselection/TextSelectionCursorController.java` | `14c01f42c82ae891687139088b802442b0a8121b502e7ca21fddceeb51ad6f2a` | `253e8deae686f6691b6a8b425ea84fe67b7c8654` |
| `com/termux/view/textselection/TextSelectionHandleView.java` | `1f7258ebbd5ab29f7b8ef9ce74c5191a86a6b04cd5806bcb05cf5065843e16fe` | `4f39e723bae28fccafa0d6134b3f24f02e694861` |

## 2. Historical build versus current compatibility proof

The immutable upstream build files establish the following historical recipe:

| Item | Upstream value |
| --- | --- |
| Annotation dependency | `androidx.annotation:annotation:1.3.0` |
| Emulator dependency | `api project(":terminal-emulator")` |
| Java source and target | Java 8 |
| Compile / minimum / target SDK | 30 / 24 / 28 |
| Android Gradle Plugin | `4.2.2` |
| Release minification | Disabled |

Primary source files retrieved from the exact upstream commit:

- [View build file](https://github.com/termux/termux-app/blob/6e2689f55295fa444be8ac8592c527c2c5ef3253/terminal-view/build.gradle), Git blob `1504f6885696cf41448f32b6f188966725a233a6`.
- [Build properties](https://github.com/termux/termux-app/blob/6e2689f55295fa444be8ac8592c527c2c5ef3253/gradle.properties), Git blob `c8f9ae0514310cf33a32ff0b2ce01ef55ea2461e`.
- [Root build file](https://github.com/termux/termux-app/blob/6e2689f55295fa444be8ac8592c527c2c5ef3253/build.gradle), Git blob `aa0981015d1c7c689b0c25ca185f013b072df17e`.

Using the existing Droide JDK, API 36 and annotation 1.10.0 is a current
source-compatibility proof, not a reconstruction of the exact historical build
environment. The historical recipe does not establish which JDK invocation
originally produced the vendored class bytes. Do not claim byte equivalence from
matching a tag, source inventory, API, or dependency coordinate alone.

`TerminalView.java` imports `androidx.annotation.RequiresApi` and uses
`api = Build.VERSION_CODES.O` on three autofill methods, corresponding to API 26.
The later CI proof separately provisioned and hash-verified the current
annotation JAR before sandbox entry. Both compilations succeeded with Android,
annotation and source-built emulator as the only compiler classpath entries.
Warnings about Kotlin annotation-metadata types did not prevent compilation;
no Kotlin compiler or stdlib was added to the proof or application.

## 3. Generated release configuration

The reference `com/termux/view/BuildConfig.class` has these public static final
constants, which a source-built release configuration must preserve:

```java
public static final boolean DEBUG = false;
public static final String LIBRARY_PACKAGE_NAME = "com.termux.view";
public static final String BUILD_TYPE = "release";
```

The reference class SHA-256 is
`5d30fa1cc5b284b49ac1ea39af6681e038bdb69966281cb758dfe5b3438f0fd6`.
Generate this class from reviewed source rather than copying the reference
bytecode. API, constant values, compiler metadata and full class bytes are
separate comparison results.

## 4. Resource closure and non-inlined symbols

Exactly these five resource symbols are present in the reference `R.txt`:

| Type | Name | Reference symbol value |
| --- | --- | --- |
| drawable | `text_select_handle_left_material` | `0x0` |
| drawable | `text_select_handle_right_material` | `0x0` |
| string | `copy_text` | `0x0` |
| string | `paste_text` | `0x0` |
| string | `text_selection_more` | `0x0` |

The two drawables in the AAR are byte-identical to the prepared source files.
The three source strings are XML-equivalent to the AAR's generated
`res/values/values.xml`. The source manifest contains only package
`com.termux.view`; the AAR additionally declares minimum SDK 24 and target SDK 28.
The existing resource verifier checks these distinctions without requiring the
reference AAR in its default source-only mode.

| Prepared file, relative to `third_party/termux/view-source-resources/` | SHA-256 |
| --- | --- |
| `AndroidManifest.xml` | `a595e81539a73bad6a0601d1b47b1cef0dd6ff236f2cc4e4158fffede55e57cb` |
| `res/drawable/text_select_handle_left_material.xml` | `3ce66a831646933bea6adc67ed8d4b8dfd3bb86b992b1ea789b0ba577add4551` |
| `res/drawable/text_select_handle_right_material.xml` | `977579c11e9c28ef74af25ab61b8f39f812006e8ac5d2a9c5bf185a2456d522f` |
| `res/values/strings.xml` | `c2880e9b490ea42e0816dc2ad1b9bd6b1e75098508afd3681433ba07b14a1bfb` |
| `LICENSE.md` | `a8992ad867b1b4ea442693acb2e1ee829a814758e79a099bd8243a04ae383304` |

**Do not generate `public static final int ... = 0` resource stubs.** That can
inline zero into View bytecode and break selection handles or copy/paste menu
text despite successful compilation. Use Android library-generated non-final
resource symbols, or explicitly reviewed non-final compile symbols in a
Java-only proof. The reference classes retain field references to
`com/termux/view/R$drawable` and `com/termux/view/R$string` for resource linking.

There are no `R.class`, `R$drawable.class`, or `R$string.class` entries in the
reference View `classes.jar`. Compile-symbol classes must stay outside the
candidate's nineteen-class output, not be copied into the final AAR. Inspect
the resulting field references to guard against accidental constant inlining.

Expected source-built AAR payload:

- `classes.jar`: the nineteen View, nested selection and BuildConfig classes
  only; no emulator, annotation, Android SDK, or compile-symbol classes.
- `R.txt`: exactly the five symbols above.
- `AndroidManifest.xml`: the reviewed package and SDK declarations, with any
  generation differences documented separately from application SDK policy.
- The two drawable resources and the three strings, without dropping or
  renaming a resource to make a comparison pass.
- `META-INF/com/android/build/gradle/aar-metadata.properties`, with
  `aarFormatVersion=1.0` and `aarMetadataVersion=1.0` if matching the reference's
  metadata contract.

Source resource files may be packaged for the consuming Android build, but
creating such a proof AAR is not evidence that resource compilation, final
linking, or APK behavior succeeds. A conventional Android library source module
is a production integration option after independent proof review. Preserve
existing license and notice delivery in the repository and application assets;
the absence of an embedded license entry in the original AAR is not permission
to remove those notices.

## 5. Reference class inventory and ABI

All paths below start with `com/termux/view/` and end with `.class`:

| Family | Required classes | Count |
| --- | --- | --- |
| Release configuration | `BuildConfig` | 1 |
| Gesture handling | `GestureAndScaleRecognizer`, `$1`, `$2`, `$3`, `$Listener` | 5 |
| Rendering | `TerminalRenderer` | 1 |
| Terminal view | `TerminalView`, `$1`, `$1$1`, `$2`, `$3`, `$TerminalCursorBlinkerRunnable` | 6 |
| View client | `TerminalViewClient` | 1 |
| Selection contract | `textselection/CursorController` | 1 |
| Selection controller | `textselection/TextSelectionCursorController`, `$1`, `$2` | 3 |
| Selection handles | `textselection/TextSelectionHandleView` | 1 |

Dollar-prefixed names in each row are nested-class suffixes of that row's
top-level class, not standalone entry names. The exact set must be compared;
matching only a count would miss a substituted or renamed class.

All non-private ABI matters, including package-private fields and methods.
Application code intentionally shares the `com.termux.view` package:

- `app/src/main/java/com/termux/view/DroideSelectionText.java` accesses
  `TerminalView.mEmulator` and the selection controller.
- `app/src/main/java/com/termux/view/DroideViewportMetrics.java` accesses
  `TerminalRenderer.mFontLineSpacing` and `mFontLineSpacingAndAscent`.

A public/protected-only API check could miss a real application regression.
Check descriptors, flags, constants, generic signatures, exceptions, nested
class identity and enclosing metadata as well as the accessible declarations.

## 6. Annotation and byte-comparison requirements

The existing emulator helper's `accessible_api()` deliberately fails on
annotation metadata it does not compare. It cannot be reused unchanged as a
successful View API comparator. Do not remove that fail-closed behavior merely
to obtain a passing check.

The reference View classes contain Android `SuppressLint` and `TargetApi`
annotations as well as AndroidX `RequiresApi`. One class has class-level
`RuntimeInvisibleAnnotations`; the following five methods have annotation
metadata:

- `TerminalView.onTouchEvent`
- `TerminalView.autofill`
- `TerminalView.getAutofillType`
- `TerminalView.getAutofillValue`
- `textselection/TextSelectionHandleView.onTouchEvent`

Implement an independently tested annotation-aware metadata comparison, including
element values resolved from each class's constant pool, or keep those cases
explicitly blocked. Raw attribute bytes containing pool indices are not a
semantic comparator across different class encodings. Do not silently discard
annotation attributes or infer equivalence from matching annotation names.

The reference also contains debug information:

| Attribute | Inspected count |
| --- | --- |
| `LineNumberTable` | 153 |
| `LocalVariableTable` | 151 |
| `StackMapTable` | 73 |
| `MethodParameters` | 119 |

`SourceFile` attributes identify the seven original sources and generated
`BuildConfig.java`. Using the emulator proof's `-g:none` option for View would
therefore guarantee full class-byte differences before considering compiler
versions. Preserve relevant debug settings for a historical reconstruction, or
record their removal explicitly for a current compatibility proof. Neither API
parity nor an explanation of debug metadata proves method-body equivalence.
Compare complete classes, API/metadata, resource entries, JAR wrapping and AAR
wrapping as distinct results, without changing any baseline hash to pass.

A later bounded inspection of the pinned reference found the 119
`MethodParameters` records above. They are distinct from local-variable debug
tables: `-g` alone does not request parameter-name metadata. The View proof
must preserve parameter names and modifier flags using an explicit
`-parameters` compilation setting and a constant-pool-normalized comparator,
or report an unresolved metadata difference. Synthetic enclosing-instance
parameters must not disappear from this check merely because they are not
written as explicit Java source parameters.

## 7. Bounded implementation and promotion gates

The implemented proof uses the verified existing JDK, API 36 and pinned
annotation-jvm 1.10.0. It first runs the complete emulator source proof in fresh
scratch, then compiles the View twice with `-g`, `-parameters`, disabled
annotation processors and an explicit closed classpath. Exactly three generated
compile-symbol classes are excluded; no extra dependency class is silently
stripped. The proof AAR additionally embeds the unchanged upstream license and
preserves the source string XML rather than copying generated reference XML.
These archive differences are explicitly reported.

Its separate annotation-aware comparator resolves constant-pool indices,
checks all non-private declarations, parameter names/flags and declaration
annotation values, and decodes actual resource field instructions. Unsupported
metadata fails closed. Strict metadata differences still fail the comparison
even when the narrower declaration-ABI diagnostic matches. Parameter names are
never sorted, inferred from descriptors or ignored by the comparator. Neither
comparator is a JVM stack/type verifier or a method-body equivalence proof.

### Actual raw compiler checkpoint and bounded follow-up

[Run 43369448300](https://github.com/baystudio-dev/Droide-v1.-00/actions/runs/43369448300)
tested merge `233a52ed10fdd446bac7ddbd00880515dad91a87`, incorporating head
`72d0e47f28db4aa6fefea8a239bfb17ffb004111`. Six source-proof jobs passed;
the View job compiled twice successfully but failed its strict comparison.
Both raw JARs have SHA-256
`b66ee025ea84c1314dee858bc0c711db8b959d8ce04e24b01d5f0616e1519b00`;
both raw AARs have SHA-256
`e61c71cc8e94d06d944e107404fedb07990224aa5cf49a48fa27e793a03b54e0`.
The nineteen-class inventory, narrower declaration ABI and five resource
GETSTATIC reads match. The three metadata differences concern ordered
MethodParameters in `TerminalView$1$1` and `TerminalView$2`, plus the added
`()V` constructor Signature in the former. All nineteen full class hashes
differ from the historical baseline; they are not assumed to be debug-only.

The follow-up implementation adds a **separate metadata compatibility step**,
not an exception to the strict comparator. It freezes the observed raw archive
and two class hashes, requires exactly those three full-metadata differences,
and checks the two constructors' resolved instructions and slot bindings.
Only the two parameter-name records and the one Signature are eligible for
adaptation. The helper preserves the old constant-pool prefix and indices,
appends only fixed missing `x0`/`x1` strings, and independently verifies that
every complete Code attribute and unrelated class structure is unchanged from
the raw source build. The other seventeen classes must remain byte-identical
to that raw build.

Raw artifacts remain untouched under `run-1` and `run-2`; independently adapted
proof artifacts use `compat-run-1` and `compat-run-2`. Their hashes and results
are reported separately. The unchanged strict comparator must pass on the
adapted output, and raw/adapted reproducibility, input integrity and Code
preservation are additional mandatory gates. Any new discrepancy stops the
experiment. Fresh end-to-end execution of this extension must be recorded
separately from fixture tests. See the
[exact metadata evidence and rationale](FDROID_TERMUX_COMPILER_METADATA.md).

At the extension's local checkpoint, **297 source-proof tests passed** in the
same enforced offline sandbox, including thirty-two new binding, adaptation
and orchestration tests. Independent static review found no concrete blocker;
this is not a successful fresh compiler/adaptation execution claim.

The fixed `view` runner mode adds only one read-only annotation JAR input and
keeps the existing network, identity, filesystem and resource limits. A compact
`SUMMARY.json` is shown before the larger receipt; neither proof AAR nor JAR is
promoted to a release. Actual compiler outcomes are distinct from the earlier
local 265-test checkpoint and any subsequent fixture-only validation.

1. Verify the exact source inventory, per-file identities, resources, notices,
   compiler tools and dependency hashes before and after each proof build.
   Provision trusted tools separately from the offline execution phase.
2. Compile the reviewed complete emulator from source into fresh scratch and use
   only that candidate as the View's terminal dependency. Generate reviewed
   BuildConfig and non-final resource symbols. Do not include existing Termux
   JAR/AAR classes on the compiler classpath.
3. Build View twice in independent fresh directories under the existing
   isolation requirements: no external network, read-only source/toolchain,
   allowlisted environment, scratch-local home/cache/temp, bounded writes and
   resource/time limits. Record full commands and input/output identities.
4. Compare independent outputs, the exact nineteen-class inventory, all
   accessible ABI, annotation values, resource references and full bytes against
   the unchanged inspection baseline. Distinguish byte differences from API
   differences and from untested runtime behavior. No generated target code
   should be executed merely because compilation succeeded.
5. Package only the reviewed View classes/resources into a proof AAR; prevent
   duplicate emulator/dependency classes and accidental inclusion of generated
   resource-symbol classes. Preserve notices and all current functionality.
6. Independently run Android resource compilation/linking and the actual
   application build before production integration. Confirm all five resources
   receive valid IDs and the source-built AAR resolves with the current emulator.
7. Before replacing a non-identical stable artifact, run physical-device checks
   for selection dragging/handles, copy/paste/more menu, rendering and row
   metrics, gestures, IME/input, autofill, PTY resize and lifecycle behavior.
8. Review a dedicated source-only production path and its strict output
   verifier. Existing reference binaries must cease being required inputs to
   that path, while independently reviewed source/API manifests retain coverage.
   F-Droid scanning, build execution, maintainer acceptance and signing/update
   channel review remain separate gates.

This plan reports successful raw Java compilation but a strict raw metadata
failure; it reports no resource link, Android device validation, replacement
APK, or F-Droid acceptance. See
[source proof scope](FDROID_SOURCE_PROOFS.md) for the broader boundaries.
