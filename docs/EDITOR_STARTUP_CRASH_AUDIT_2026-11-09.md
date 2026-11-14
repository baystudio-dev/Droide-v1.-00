# Editor startup crash audit

The reported timing is when the last project/file starts appearing. No device crash
trace or connected Android device was available. This change closes reproduced
integration defects; it does not establish the cause of that particular device exit.

## Findings and changes

- Sora's accessibility node/event callbacks and scroll metrics read its layout outside
  `onDraw`. Droide previously guarded drawing, resize and posted work, but inherited
  these platform entry points without guards. Fault injection against the actual
  Sora 0.24.6 runtime reproduced escaping exceptions, including after the surface had
  already failed. These callbacks now use the existing surface failure boundary,
  report once, and return an inert accessibility node/event or zero scroll metrics
  while the failed/released view awaits removal. Healthy accessibility behavior and
  fatal VM error propagation remain intact.
- The initial 4 KiB binary probe missed NULs later in an otherwise valid UTF-8 file.
  `readEditorText` now checks the complete bounded decoded text on the IO dispatcher.
  A typed `BinaryFileException` selects the existing binary presentation; UI routing
  no longer parses the exception's message. An unsuccessful reload keeps the old
  document and disk baseline.
- Restored drafts bypass disk reads. `DroideCodeEditor.setText` checks for NUL before
  replacing Sora's Content, including during initialization. A rejected recovery
  draft stays owned by `EditorDocument` and remains in recovery checkpoints. The
  existing error/retry presentation is reused; no bytes are sanitized or deleted.
- One accessibility input fixture assumed a single Main-looper drain completed
  Sora's asynchronous layout. It now waits, with a deadline, for real editability
  before asserting focus/IME behavior. Production input timing was not changed.

Startup recovery already defers editor reopening after Android reports an abnormal
exit and retains deferred dirty buffers. That policy, terminal startup, UI design,
layout, notifications and agent-save behavior were not changed.

## References

Sora source was downloaded at tag `0.24.6`, commit
`87055c459e346cac6c619b3350f0bfba076228cc`, matching the Gradle dependencies:

- [CodeEditor platform callbacks](https://github.com/Rosemoe/sora-editor/blob/87055c459e346cac6c619b3350f0bfba076228cc/editor/src/main/java/io/github/rosemoe/sora/widget/CodeEditor.java)
- [Binary/NUL report #863](https://github.com/Rosemoe/sora-editor/issues/863): relevant
  input class, but not a confirmed trace for Droide. The tests prove rejection and
  preservation, not reproduction of a native process abort.
- [Native Paint crash #874](https://github.com/Rosemoe/sora-editor/issues/874): a drag
  selection SIGABRT on an uncertain later revision. A Java exception boundary cannot
  catch SIGABRT. This report alone does not identify Droide's startup failure.
- [Layout NPE #904](https://github.com/Rosemoe/sora-editor/issues/904): the reported
  `hasSideHintIcons` path differs from pinned 0.24.6. No speculative upgrade or fork
  of Sora was introduced.

VS Code was compared at commit `e646868227c3e3a9e3f24277c8c7220be42c0be7`:
[textFileEditorModel.ts](https://github.com/microsoft/vscode/blob/e646868227c3e3a9e3f24277c8c7220be42c0be7/src/vs/workbench/services/textfile/common/textFileEditorModel.ts)
uses `acceptTextOnly` when resolving text, and
[textFileEditor.ts](https://github.com/microsoft/vscode/blob/e646868227c3e3a9e3f24277c8c7220be42c0be7/src/vs/workbench/contrib/files/browser/editors/textFileEditor.ts)
routes the typed `FILE_IS_BINARY` failure to binary handling. That separation informs
Droide's typed admission failure; Sora still needs its own Android lifecycle guards.

## Verification and limits

Eight exact current production units compiled in the targeted host harness. Nine
test classes passed: 61 distinct tests, 67 executions including the six platform
boundary tests on both Android API 29 and 35 under Robolectric. Existing native
graphics, input, lifecycle, binary decoding and recovery regressions were included.
Negative controls reproduced four escaping platform exceptions, acceptance of a
NUL-bearing native replacement, and two late-NUL file-load/reload failures before
the fixes. The recovery UI test verifies that initialization rejection does not
replace the retained draft with the empty native buffer.

The harness uses exact selected sources plus filtered retained dependency classes;
this is not a full application/Gradle/APK build, an OEM test, or proof against native
Sora aborts. If the device still closes, capture logcat/Android's crash trace around
launch (especially `AndroidRuntime`, `DroideEditor`, and native tombstones) to
distinguish a Java exception, native abort, ANR or memory kill before another fix.
