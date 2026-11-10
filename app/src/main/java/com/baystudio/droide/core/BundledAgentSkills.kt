package com.baystudio.droide.core

import java.io.File

/** Shipped reference instructions, loaded only on demand. No extraction, scan, installation or startup work. */
internal object BundledAgentSkills {
    private data class Entry(val name: String, val description: String, val instructions: String)
    private val entries = listOf(
        Entry("documents", "Read and edit PDF/DOCX safely, render PDF pages, and publish document files.", """
            Use document (external CLI: droide_document) for bounded local operations. Inspect the source first.
            A chat attachment can be read by its owned path. External CLIs use visible files in the Droide editor
            workspace; save_media_to_project copies an attachment there when requested. The CLI working copy
            may differ: synchronize first. Do not send private documents to a website merely to convert them.

            DOCX: inspect/read works natively without Python, including paged text and source_sha256. Reading
            extracts main-body paragraphs/tables, not a visual layout check; the notice lists omitted content.
            read returns next_offset; continue with that offset and the same expected_sha256. Never substitute
            text extraction for verifying page layout. Legacy .doc, encrypted files, and Google Docs URLs must
            be exported/unlocked first; never rename a binary file to pretend it is DOCX.

            PDF: inspect reports page count. read takes one-based page and page_count (1–4), then offset for
            longer text within those same pages. Extraction is not OCR; empty/misordered text is not proof the
            PDF is empty. render writes a requested page to a NEW .png (1600-pixel bound); use view_image only
            if the selected model accepts images. Otherwise state that visual verification is unavailable.

            Editing: require BUILD mode, source read and destination edit permission. All edits create a NEW
            project file, preserve the original, and require expected_sha256 from inspection. create_docx uses
            content as plain paragraphs; it cannot preserve another document's layout. replace_text changes
            literal single-line DOCX text, including text split across ordinary runs. Supply find, replacement,
            expected_matches and a new .docx destination. Fields, tracked changes, drawings, macros or signatures
            near changes may require a dedicated editor. Unrelated package parts and run formatting remain.
            pdf_pages takes an array of one-based pages in desired order to extract/reorder a new PDF. It is
            not text replacement or secure redaction; signatures are not retained. Review forms/links afterward.
            Arbitrary layout editing and PDF redaction are outside this tool. Never cover text with a white
            rectangle and claim it was removed. For unsupported operations use an explicitly installed editor
            or an appropriate MCP service with the user's authorization; report the limitation honestly.

            Setup only when needed: document status checks the active Ubuntu terminal. Python3 is needed for
            PDF and DOCX writing. Use toolchain to prepare the Python runtime if missing. In the Droide Ubuntu
            terminal install python3-pypdf, python3-docx and poppler-utils using its package manager through
            the normal shell/install permission flow (for example apt-get install python3-pypdf python3-docx
            poppler-utils). Apt metadata refresh is only needed after an actual package lookup failure. This
            is an optional runtime dependency, not an APK payload. Do not bypass package-manager locks, run
            startup scans, pin obsolete versions, or silently install LibreOffice. Python is invoked with -I;
            install into the active interpreter's system environment, not an unrelated user-site directory.

            Source/output limits: 64 MiB, DOCX 32 MiB, XML 8 MiB. Results are paged at 8000 characters. Work runs
            in a bounded owned process with cancellation and a two-minute deadline. No output is published on
            parser/runtime failure. If publication outcome is uncertain, inspect the destination before retrying.
            Send the final project path using send_media when requested, or let the user export it in Explorer.
        """),
        Entry("audio", "Inspect, trim, convert and adjust MP3/WAV audio; distinguish metadata from transcription.", """
            Use audio (external CLI: droide_audio) for local work. inspect accepts MP3, WAV, M4A, AAC, FLAC, OGG
            or OPUS and returns source_sha256, duration, stream and codec metadata. This is not listening or
            speech recognition. Never describe speech, speakers, music or sound quality from metadata alone.
            Current conversation attachments may be read by owned path; external clients require visible
            project files. For exported inputs use save_media_to_project rather than raw internal blob paths.

            For edit, provide source path, expected_sha256 from inspect, a NEW destination and matching format
            (wav/mp3/flac/ogg). Optional start_seconds/end_seconds trim a <=600-second interval, gain_db adjusts
            volume (-30..30), sample_rate is 8000..96000, and channels is 1 or 2. First audio stream only; decoded
            and re-encoded, metadata removed. Do not claim lossless MP3 processing. Positive gain may clip; ask
            for playback review. All file output is <=64 MiB, so use shorter clips/lower sample rates when needed.
            Edits require BUILD mode and read/edit/shell permissions. Existing files and originals are preserved.

            audio status checks Python3, ffprobe and ffmpeg only on request. Use toolchain for Python and the
            active Ubuntu package manager for ffmpeg (e.g. apt-get install ffmpeg), through normal permissions.
            No background scan or automatic download. Codec availability follows the installed FFmpeg build;
            do not promise every encoder. Process timeout/cancellation stops publication and keeps originals.

            For speech use transcribe_audio when available, with an explicit supported transcription model
            on the configured OpenAI API connection. This uploads audio and requires provider webfetch approval;
            do not silently upload to a different provider. It is separate from the chat model. A transcript is
            imperfect: verify names, numbers, timestamps and language; do not invent missing words or speakers.
            If the connection is unsupported, use an explicitly configured speech MCP or local speech runtime;
            metadata inspection is not a fallback transcript. This installation does not bundle speech weights.
            After successful editing/transcription, send_media can attach the output; Explorer can export it.
        """),
        Entry("image-generation", "Check image provider capability, generate images, and diagnose errors without duplicate paid requests.", """
            Use generate_image operation=status before generating. Chat model, image model, API endpoint and
            account access are separate concerns. Never pass a text/reasoning model just because it is selected
            for chat. Supply a canonical image-generation model from the same configured provider/account.
            Runtime status describes supported routes; it does not prove entitlement or service availability.
            Do not invent API credentials, silently switch providers, or copy a key to an unfamiliar host.

            Image generation requires BUILD mode, conversation edit and provider webfetch permission. One
            request produces one conversation-owned PNG/JPEG/WebP, <=8 MiB, retained seven days. Preserve it
            using save_media_to_project if a permanent project asset is wanted. Pixels are not automatically
            sent back to the chat model. Use view_image to inspect only when the model supports image inputs.

            For OpenAI image endpoints use an image model available to the account, not the chat model; current
            GPT Image models use inline bytes and quality auto/low/medium/high. DALL-E options differ and their
            availability depends on the provider. Gemini requires a generateContent model with IMAGE output;
            do not use an Imagen predict model on that endpoint. Omit OpenAI quality options for Gemini.

            HTTP 400/422: inspect image model and supported options. 401/403: verify connection/access. 404:
            verify model/endpoint. 429: check rate/quota. 500/502/503/504: provider-side or gateway failure; the
            tool cannot establish whether a paid request completed. Do not retry automatically. Preserve any
            published result and tell the user what failed. Chat-provider HTTP failures may occur before the
            image tool is called; do not report these as image-generation failures. Generic HTTP 500 text alone
            does not establish the root cause. Keep credentials and raw error bodies out of user-visible logs.

            This tool generates new images; reference-image editing is not currently exposed. Use an explicitly
            available image-editing MCP service for that workflow. Do not pretend a local pixel operation or
            text file is generated imagery. Follow the actual tool's input contract and report unsupported work.
        """),
    )
    fun catalog(): List<SkillSummary> = entries.map { SkillSummary(it.name, it.description, "builtin") }
    fun document(name: String, root: File): SkillDocument? = entries.firstOrNull { it.name == name }?.let {
        val body = it.instructions.trimIndent()
        val markdown = "---\nname: ${it.name}\ndescription: ${it.description}\n---\n\n$body\n"
        val folder = File(root, ".droide/builtin-skills/${it.name}") // Display identity only; never materialized.
        SkillDocument(Skill(it.name, it.description, body, "builtin"), markdown, File(folder, "SKILL.md"), null,
            userInvocable = true, disableModelInvocation = false, sourceRoot = root, bundleDirectory = folder,
            validateSource = {}, bundled = true)
    }
}
