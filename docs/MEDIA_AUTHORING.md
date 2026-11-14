# Document, audio and image tools

This feature adds capabilities without changing the workbench layout. Bundled skill metadata is discovered on demand through the existing skill catalog, Settings and composer. Bodies load only when requested. No runtime, office suite, model weights or new Gradle dependency is bundled.

## Ownership and interfaces

| Surface | Tools | Execution authority |
| --- | --- | --- |
| Built-in chat | `document`, `audio` | Shared local worker and native DOCX reader; current request and workspace |
| External CLI in chat or terminal | `droide_document`, `droide_audio` | Existing IDE bridge; editor workspace, current registry owner and Droide permissions |
| Built-in chat speech | `transcribe_audio` | Explicit upload to the bound provider, separate transcription model |
| Built-in chat images | `generate_image` | Explicit image API/model on the bound provider, separate from conversational model |

External clients do not receive chat-provider credentials through the media tools. They may use their own authenticated speech/image service or MCP. The existing bridge request limit remains 4096 UTF-8 bytes; oversized authoring instructions must use the CLI's own local tools after synchronizing its working copy.

`MediaAuthoringSchema` defines the native contract; `ExternalIdeTools` projects it. `MediaAuthoringTools` implements permissions, current-owner checks, bounded source capture, version checks and publication. The trusted packaged `agent-media/worker.py` receives only structured arguments and private staged files. Its source is passed as an argv element to Python with `-I`; project files cannot replace the worker module or import a local `pypdf.py`. The currently selected terminal is pinned for the command. No shell fragments, arbitrary executable, filter expressions or user-controlled options are interpolated.

Local work holds an `ExportWriterAdmission` ticket from staging through process completion and cleanup, so export cannot capture a half-written worker output.

Only `FileRepository` publishes user-facing destinations, with require-absent semantics and digest verification immediately before publication. The source remains unchanged. File/parent observations reject changes during capture. Mutations require a SHA-256 from previous inspection, except creation. Dirty destinations are rejected. Owner retirement blocks publication. An observer failure after publication is reported as uncertain, requiring destination inspection instead of automatic replay.

Private stages use unique job directories. Completed disposable jobs are cleaned without sending every temporary input through Explorer's retained folder deletion. Cancelled/timed-out commands whose completion is unconfirmed retain their stage rather than deleting files under a possible live process. There is no background sweeper. Export/archive operations should continue to treat `.droide` as application-owned state.

## Supported operations and limits

- DOCX inspect/read: existing bounded native `DocxTextReader`, no runtime installation. Main-body text is not a layout preview; headers, footers, images and other omitted content are disclosed. UTF-16-safe text paging.
- DOCX create: plain paragraphs via `python-docx`. Literal replacement: bounded ZIP/XML processing, run-aware replacement, exact match count, unrelated package parts retained. Fields, tracked changes and complex matching paragraphs are refused. No macros, signed packages, DTD or entity expansion.
- PDF inspect/read: `pypdf`, one to four pages per request, paged output. Scanned text is not OCR. PDF page selection/reordering retains vector/text contents through `PdfWriter.append`; signatures, interactive links/form semantics need review. It is not arbitrary text editing or redaction.
- PDF render: Poppler produces a single PNG bounded to 1600 pixels. The image must then be passed to `view_image` for actual model inspection when vision is available.
- Audio inspect: FFprobe technical metadata, explicitly not speech or listening evidence. Audio edit: first stream, bounded trim, gain, resampling/channels, new WAV/MP3/FLAC/OGG via FFmpeg. Re-encoding removes metadata; available codecs follow the installed runtime.
- Remote speech: explicit supported transcription model, <=20 MiB upload, <=2 MiB JSON response and <=200000-character transcript. New text file only. No implicit fallback to another provider, retry, diarization or sound-quality claim.

Local files/output <=64 MiB, DOCX <=32 MiB, XML <=8 MiB. Text result pages <=8000 characters; PDF extraction <=500000 characters. DOCX archives have entry, expansion and node limits. Python imposes 768 MiB virtual-memory, 90-second CPU and 64 MiB output limits; the owning terminal operation has a two-minute deadline. FFprobe has a 15-second timeout and FFmpeg/Poppler 90 seconds. Python workers use regular bounded files for child output rather than unbounded pipe accumulation.

## Dependencies and upgrade ownership

Use toolchain setup for Python, then the active Ubuntu package manager for the missing packages, with existing installation/shell permissions:

| Operation | Runtime packages |
| --- | --- |
| DOCX read | None |
| DOCX create | `python3-docx` |
| DOCX literal edit | Python standard library |
| PDF text/page edits | `python3-pypdf` |
| PDF page image | `python3-pypdf`, `poppler-utils` |
| Audio metadata/edit | `ffmpeg` (includes ffprobe) |

`status` checks dependencies on demand without downloading anything. No version pin, startup scan or automatic package-index refresh is added. The installed system interpreter must see the packages because `-I` intentionally ignores user-site and project imports. For a missing runtime/active terminal, the tool reports a concrete setup path. No optional component is presented as already installed.

## Image failures and capability discovery

`generate_image operation=status` makes no network request and reports route availability separately from account access. `models` performs one authorized GET, returning bounded name-based candidate IDs. A model name is not proof of image capability or entitlement. Generation still requires an explicit image model.

Official OpenAI/Gemini routes are recognized directly. An explicitly documented gateway can opt into `api=openai_images` or `api=gemini` only when its configured wire protocol and auth scheme match. `ProviderRuntimeGuard.requireBoundProvider` verifies the active runtime snapshot and endpoint before credentials are used. No free-form generation host or cross-provider fallback is introduced. The request uses the existing provider network/DNS policy.

Compatible image APIs are asked for base64 output. Unknown image engines use their own default size rather than receiving GPT Image/DALL-E size options. Gemini camelCase and snake_case inline data are supported. Existing image size/type validation and conversation-owned storage remain. Remote image URLs are not followed. Image requests allow slow providers up to six minutes while remaining cancellable; there is no artificial display delay. Errors identify the image stage and an allowlisted/redacted request ID. Generic model HTTP errors are labeled as model-response failures; they can happen before any image tool invocation. HTTP 500 does not establish whether a paid request completed, so image/speech POSTs are never retried automatically, including OkHttp's special 503 behavior.

The user reported only a generic HTTP 500, without provider/model or a server trace. These changes fix local capability/selection/response deficiencies and improve diagnosis. They cannot establish or repair a remote provider outage. Live authenticated generation/transcription and Android device runtime installation remain separate validation steps.

## VS Code references

Reviewed downloaded VS Code commit `e646868227c3e3a9e3f24277c8c7220be42c0be7`:

- `src/vs/platform/agentHost/common/meta/vscode/agentImageGenerationMeta.ts`: requested image engine is distinct from conversation model/confirmed serving model; absent billing is unknown.
- `src/vs/platform/agentHost/node/copilot/copilotSessionLauncher.ts` and `copilotAgentSession.ts`: runtime/provider capability and session ownership, not a universal UI shortcut that bypasses provider access.
- `src/vs/platform/agentHost/node/codex/codexMapAppServerEvents.ts`: mapping runtime image-generation results to common activity presentation.
- `extensions/copilot/src/extension/tools/node/readFileTool.tsx`: text/binary handling and separate image tool. Reading a binary is not equivalent to understanding/editing Office documents or hearing audio.

Droide adapts these capability/ownership boundaries to its existing Android workspace and CLI bridge; it does not embed the desktop VS Code agent runtime or claim VS Code supplies a universal native Office/audio editor.
