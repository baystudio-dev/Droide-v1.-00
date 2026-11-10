package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal object MediaAuthoringSchema {
    val definitions: List<JsonObject> = listOf(
        definition("document", "Read/inspect PDF and DOCX, create DOCX, replace literal DOCX text, select/reorder PDF pages, or render one PDF page to PNG. Load the documents skill first. DOCX reading works natively; other operations use the current Ubuntu terminal and on-demand Python dependencies. status checks dependencies only. Source <=64 MiB (DOCX <=32 MiB), results paged at 8000 characters. PDF scans need visual inspection/OCR. Mutations require BUILD mode, expected_sha256 from inspect/read (except creation), and a NEW destination. Originals are preserved. Read/edit/shell permissions apply; no downloads, overwrite or implicit install.", buildJsonObject {
            put("operation", enumeration("status", "inspect", "read", "create_docx", "replace_text", "pdf_pages", "render"))
            common()
            put("content", string("Plain paragraphs for create_docx; <=40000 characters", 40000))
            put("find", string("Literal single-line text to replace in DOCX", 8000))
            put("replacement", string("Replacement text; run formatting and unrelated package parts retained", 8000))
            put("expected_matches", integer(1, 10000))
            put("page", integer(1, 10000)); put("page_count", integer(1, 4))
            put("pages", buildJsonObject { put("type", "array"); put("items", integer(1, 10000)); put("minItems", 1); put("maxItems", 200) })
        }),
        definition("audio", "Inspect or edit local MP3/WAV/M4A/AAC/FLAC/OGG/OPUS files with FFmpeg in the current Ubuntu terminal. Load the audio skill first. status checks dependencies on demand, never installs. inspect returns technical metadata, not speech or auditory understanding. edit trims an interval <=600 seconds, adjusts gain/sample rate/channels, and writes a NEW WAV/MP3/FLAC/OGG project file. Supply expected_sha256 from inspect; source/output <=64 MiB. BUILD mode and read/edit/shell permissions required for edits. Speech transcription is a separate transcribe_audio tool when a compatible provider is configured.", buildJsonObject {
            put("operation", enumeration("status", "inspect", "edit")); common()
            put("format", enumeration("wav", "mp3", "flac", "ogg"))
            put("start_seconds", number(0, 864000)); put("end_seconds", number(0, 864000))
            put("gain_db", number(-30, 30)); put("sample_rate", integer(8000, 96000)); put("channels", integer(1, 2))
        }),
    )
    private fun definition(name: String, description: String, properties: JsonObject) = buildJsonObject {
        put("type", "function"); put("function", buildJsonObject {
            put("name", name); put("description", description)
            put("parameters", buildJsonObject {
                put("type", "object"); put("properties", properties); put("additionalProperties", false)
                put("required", buildJsonArray { add("operation") })
            })
        })
    }
    private fun JsonObjectBuilder.common() {
        put("path", string("Visible project file or owned conversation attachment; external CLI uses project files"))
        put("destination", string("New visible project filename matching output format"))
        put("expected_sha256", string("Source SHA-256 from the previous inspect/read; required for editing and text continuation", 64))
        put("offset", integer(0, 500000))
    }
    private fun string(description: String, max: Int = 500) = buildJsonObject { put("type", "string"); put("description", description); put("maxLength", max) }
    private fun enumeration(vararg options: String) = buildJsonObject { put("type", "string"); put("enum", JsonArray(options.map(::JsonPrimitive))) }
    private fun integer(min: Int, max: Int) = buildJsonObject { put("type", "integer"); put("minimum", min); put("maximum", max) }
    private fun number(min: Int, max: Int) = buildJsonObject { put("type", "number"); put("minimum", min); put("maximum", max) }
}
