# Termux View compiler metadata: raw failure and compatibility proof

Recorded: 2026-11-13 (Asia/Jakarta). This note separates an observed raw
compiler result from a separate exact-pin metadata-compatibility artifact.
The dated PR11 checkpoint below records successful isolated compilation and
adaptation while retaining the raw strict failure. This is not Android
end-to-end validation, a production replacement or an accepted F-Droid recipe.

## Observed raw source-build result

[Source-proof run 43369448300](https://github.com/baystudio-dev/Droide-v1.-00/actions/runs/43369448300),
[View job 131701644763](https://github.com/baystudio-dev/Droide-v1.-00/actions/runs/43369448300/job/131701644763),
tested merge `233a52ed10fdd446bac7ddbd00880515dad91a87`, incorporating head
`72d0e47f28db4aa6fefea8a239bfb17ffb004111`. The job compiled the complete
emulator Java dependency from source and then compiled the seven original View
Java files with generated release BuildConfig and non-final resource symbols.
No precompiled Termux classes were compiler inputs.

Both independent compilations produced identical raw View outputs:

| Raw output | SHA-256, identical in runs 1 and 2 |
| --- | --- |
| Proof `classes.jar` payload | `b66ee025ea84c1314dee858bc0c711db8b959d8ce04e24b01d5f0616e1519b00` |
| Proof AAR | `e61c71cc8e94d06d944e107404fedb07990224aa5cf49a48fa27e793a03b54e0` |

The retained comparison inputs remain unchanged:

| Reference input | SHA-256 |
| --- | --- |
| `app/libs/terminal-view-v0.118.0.aar` | `06b3e83cf34c40e82ba5348a88fc72657ebf35177e28f567e86b2389c34cd2d0` |
| Its nested `classes.jar` | `9bceb16dc4d35f412aff579a11fcf4498bd5935dbbee85b9adb11c4ea3ec9068` |

The receipt reports exactly nineteen View classes, five matching actual
GETSTATIC resource reads, unchanged inputs and matching narrower declaration
ABI. The unchanged strict annotation-aware API comparator failed on **three
metadata paths in two constructors**. All nineteen complete class byte hashes
differ from the retained reference. The job therefore correctly ended with
`VIEW_SOURCE_PROOF_DIFFERENCES_DEVICE_UNVALIDATED`; this raw failure is not
reclassified as a pass by the compatibility plan.

### Exact three metadata differences

The compared constructors are:

- `com/termux/view/TerminalView$1$1.class`,
  `<init>(Lcom/termux/view/TerminalView$1;ZLandroid/view/MotionEvent;)V`.
- `com/termux/view/TerminalView$2.class`,
  `<init>(Lcom/termux/view/TerminalView;Landroid/view/View;Z)V`.

| Class | Exact comparison path | Retained reference | Raw JDK17 result |
| --- | --- | --- | --- |
| `TerminalView$1$1` | `methods.metadata.parameters` | Ordered names: `this$1`, `val$e2`, `val$mouseTrackingAtStartOfFling` | Ordered names: `this$1`, `val$mouseTrackingAtStartOfFling`, `val$e2` |
| `TerminalView$1$1` | `methods.metadata.signature` | Absent (`null`) | `()V` |
| `TerminalView$2` | `methods.metadata.parameters` | Ordered names: `this$0`, `x0`, `x1` | Ordered names: `this$0`, `targetView`, `fullEditor` |

Parameter flags also belong to the exact comparison: `this$1` and `this$0`
are `0x8010` (final and mandated); the two captured parameters of
`TerminalView$1$1` are `0x1010` (final and synthetic); the two superclass
arguments of `TerminalView$2` have flags zero. The names must not be silently
sorted, reordered or inferred from apparent types. The actual JVM constructor
descriptors remain as shown above.

## Official javac history that explains the differences

These are primary-source mechanisms consistent with the observed compiler
fingerprint. They do not identify the exact historical JDK vendor/update or
constitute a controlled historical-toolchain reproduction.

| Official change | Relevant mechanism | Recorded fix/integration |
| --- | --- | --- |
| [JDK-8143133](https://bugs.openjdk.org/browse/JDK-8143133), immutable [commit de127a784a58fc87581921ae3b0f26e8f6248c7f](https://github.com/openjdk/jdk/commit/de127a784a58fc87581921ae3b0f26e8f6248c7f) | `Lower` changes the captured-local metadata list from append to prepend while descriptor argument types were already prepended. This explains the reversed captured parameter names in the older output. | Java 9, build 99; affects Java 8 and 9. |
| [JDK-8160891](https://bugs.openjdk.org/browse/JDK-8160891), immutable [commit 3360117337c9cd98d11aba6e88509ab29d272720](https://github.com/openjdk/jdk/commit/3360117337c9cd98d11aba6e88509ab29d272720) | `ClassReader` changes parameter-name reading from the internal `save-parameter-names` option to ordinary `-parameters`. Java8 can write names without reading dependency names; the anonymous constructor then falls back to `x0,x1`, whereas the newer compiler can copy `targetView,fullEditor` from the superclass. | Java 9, build 127. |
| [JDK-8225054](https://bugs.openjdk.org/browse/JDK-8225054), immutable [commit 827e5e32264666639d36990edd5e7d0b7e7c78a9](https://github.com/openjdk/jdk/commit/827e5e32264666639d36990edd5e7d0b7e7c78a9) | The records implementation refactors default constructors. The older path marked every anonymous constructor `ANONCONSTR`; the new basic helper does not. An anonymous interface implementation such as `new Runnable()` takes the basic-helper path when no superclass constructor was resolved. `ClassWriter` suppresses Signature for `ANONCONSTR`, explaining the newer `()V` attribute. | Java 14, build 27. |

The third row is a source-code causal trace through constructor selection and
attribute writing, not a statement that the records issue itself reported
this particular Termux discrepancy. None of these findings justifies ignoring
reflection-visible metadata.

### What is and is not known about the historical build

Immutable Termux commit `6e2689f55295fa444be8ac8592c527c2c5ef3253` records
[AGP 4.2.2](https://github.com/termux/termux-app/blob/6e2689f55295fa444be8ac8592c527c2c5ef3253/build.gradle),
[compile SDK 30, minimum SDK 24 and target SDK 28](https://github.com/termux/termux-app/blob/6e2689f55295fa444be8ac8592c527c2c5ef3253/gradle.properties),
and [Java source/target 1.8, AndroidX annotation 1.3.0 and the emulator module dependency](https://github.com/termux/termux-app/blob/6e2689f55295fa444be8ac8592c527c2c5ef3253/terminal-view/build.gradle).
Its [JitPack configuration](https://github.com/termux/termux-app/blob/6e2689f55295fa444be8ac8592c527c2c5ef3253/jitpack.yml)
does not pin a JDK. The source JAR manifest supplies no compiler identity, and
a usable historical JitPack build log was not obtained during this investigation.

The metadata is strongly consistent with Java8-era javac behavior, but the
historical vendor, update, exact invocation and SDK archive bytes remain
unestablished. A GitHub debug workflow using `ubuntu-latest` is not proof of
the separate JitPack AAR build environment. SDK metadata can influence copied
parameter names, but changing the SDK is not necessary to explain the observed
`x0,x1` fallback once the javac reader change is accounted for.

## Exact-pin metadata compatibility adaptation

The implemented recipe keeps the verified JDK17/compiler dependency pins and the
original Java source unchanged. It produces a **separate, explicitly adapted
proof artifact**, not a comparator exception and not substitution of the
historical binary. Its actual isolated result is recorded below, separately
from the earlier raw failure and fixture-only checkpoints.

The contract must enforce all of the following:

1. Retain both raw compiler outputs, their hashes, the raw strict failure and
   the three exact metadata differences as independently inspectable evidence.
   Require the raw JAR and AAR hashes recorded above before adaptation; do not
   learn a new allowed hash from the candidate being tested.
2. Require the exact nineteen-class inventory and complete reviewed raw API
   records. Only the two named constructors and three listed metadata paths
   are eligible. Any new class, descriptor, flag, annotation, metadata or
   resource-access difference must stop the proof.
3. Inspect the two constructors' actual instructions as data, using descriptor
   slots rather than MethodParameters names. Compare their resolved ordered
   loads, field stores and direct superclass calls with the reference. This
   narrowly scoped check does not prove other method bodies or device behavior.
4. Change only the intended constructor metadata in a new artifact: reproduce
   the reviewed historical parameter-name records and remove the added `()V`
   Signature at the exact eligible constructor. Preserve existing constant-pool
   entries; any required new metadata strings must not retarget existing code.
5. Verify that **every complete Code attribute of every method, including
   private methods and initializers, is byte-for-byte unchanged from the raw
   rebuild**. Preserve all unrelated class structure and keep the other
   seventeen classes byte-identical to the raw rebuild. This is a preservation
   condition relative to raw JDK17 output, not a baseline body-equivalence claim.
6. Run the same strict annotation-aware API and actual resource-access
   comparators against the separately adapted output; do not waive or normalize
   the comparison. Perform the adaptation independently for both builds,
   require deterministic outputs and retain distinct raw/adapted receipts.
7. Keep production wiring, signing, dependency coordinates, reference artifacts
   and integrity pins unchanged. No adapted proof output is automatically
   admitted into the APK or published as an application release.

This is a deliberate metadata-compatibility transformation. It must not be
described as an unmodified compiler output or as evidence that the original
raw comparison passed. Unit fixtures and data-only checks, even if successful,
cannot establish successful fresh compiler/adaptation execution.

A separately pinned Java8 diagnostic lane is an alternative way to investigate
the historical fingerprint, **not a required or executed step in this plan**.
It would need classfile compatibility checks and an independently verified
toolchain; Java8 has no `--release` option. No production compiler or Android
SDK downgrade is authorized by this note.

## Remaining limits

- The observed raw rebuild still has **nineteen complete class-byte
  differences** from the retained AAR. The three metadata paths do not explain
  all byte differences, and metadata adaptation does not establish full
  byte identity or general method-body equivalence. The actual adapted receipt
  also reports nineteen complete class-byte differences from the reference.
- The no-Code-mutation condition compares adapted output with raw output.
  It must never be represented as proving all raw method bodies match the
  historical reference, even if the two inspected constructors match.
- Android resource compilation/linking, APK/Dex integration and physical ARM64
  QA remain separate gates. Selection handles, copy/paste/more, rendering,
  gestures, IME and autofill behavior must not be inferred from metadata parity.
- The existing scanner inventory remains **33 unresolved baseline findings**.
  A production source-only recipe without reference-binary requirements and
  F-Droid maintainer acceptance remain outstanding.

See [source-proof scope](FDROID_SOURCE_PROOFS.md), the
[View source closure plan](FDROID_TERMUX_VIEW_SOURCE_PLAN.md), and the
[scanner matrix](FDROID_SCANNER_MATRIX.md).

## PR11 actual compatibility result: 2026-11-13

Independently retrieved logs and the bounded `SUMMARY.json` from
[View job 131711037711](https://github.com/baystudio-dev/Droide-v1.-00/actions/runs/43372378186/job/131711037711)
confirm success in [run 43372378186](https://github.com/baystudio-dev/Droide-v1.-00/actions/runs/43372378186).
It tested merge `1a379ca95b8478bfb1c4649d436d94026fa4f36b`, incorporating
head `426c79ce38c193cc7114c579a6eb68efb40caea0`. All **7/7 source-proof
workflow jobs** succeeded. The readiness and contract jobs each ran **297
tests, OK**; these are repeated executions, not 594 unique tests.

Both raw builds retained exactly the JAR/AAR hashes above and the raw failing
comparison: `rawAllAccessibleApiMatched=false`, three changed metadata paths.
Both separately adapted builds produced these identical hashes:

| Adapted output | SHA-256 |
| --- | --- |
| JAR | `d7bcf81c7fc03c27ec28d4acced3f708a5f0d64f7e07beb679002c79f8a974ce` |
| AAR | `f2ab04a6ac29b5a3effc7dfded488a1b174124d4c60e772577b8357781aea5a7` |

The actual transformation certificate lists exactly the three reviewed changes
in two constructors, with seventeen other classes untouched. It reports
preservation of every complete Code attribute, existing constant-pool entries
and unrelated class structure **from raw to adapted output**. The adapted
strict API/annotation comparison has zero differences; all five resource
GETSTATIC accesses and both inspected constructor bindings match. Inputs are
unchanged, the comparator was not relaxed and production promotion is false.

The compact summary retains the raw failure/count but marks its detailed raw
entries as truncated. All three transformation paths remain visible. It also
marks the two constructor-instruction records as display-truncated, so its
matched gate alone is not a fully printed instruction-level audit.

The raw and adapted variants each still differ from the historical reference
in nineteen complete class hashes. General method-body equivalence, resource
linking, device validation and F-Droid acceptance remain explicitly false.
The separate [Android run 43372378207](https://github.com/baystudio-dev/Droide-v1.-00/actions/runs/43372378207)
was `in_progress` at the last recorded observation, not a completed integration
proof for this View output. The original **33 scanner findings are unresolved**.
