package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.events.Event
import org.snakeyaml.engine.v2.events.ScalarEvent

// Parse scalar events only. Never construct arbitrary objects or expand aliases. The caller has
// already bounded the header by the existing skill byte limit.
internal object SkillYamlDescription {
    private val settings = LoadSettings.builder()
        .setCodePointLimit(200_000)
        .setMaxAliasesForCollections(0)
        .setUseMarks(false)
        .build()

    data class BlockSpan(val startLine: Int, val endLine: Int, val value: String)

    fun blockSpans(header: String): List<BlockSpan> = try {
        val markedSettings = LoadSettings.builder().setCodePointLimit(200_000)
            .setMaxAliasesForCollections(0).setUseMarks(true).build()
        val spans = mutableListOf<BlockSpan>()
        var depth = 0
        var expectingKey = true
        var key: String? = null
        for (event in Parse(markedSettings).parseString(header)) {
            when (event.eventId) {
                Event.ID.MappingStart, Event.ID.SequenceStart -> {
                    require(depth != 0 || event.eventId == Event.ID.MappingStart)
                    if (depth == 1) { expectingKey = !expectingKey; key = null }
                    depth++
                }
                Event.ID.MappingEnd, Event.ID.SequenceEnd -> {
                    depth--
                    if (depth == 0) break
                }
                Event.ID.Scalar -> if (depth == 1) {
                    val scalar = event as ScalarEvent
                    if (expectingKey) key = scalar.value.lowercase()
                    else {
                        if (key == "description" && (scalar.isFolded || scalar.isLiteral) &&
                            scalar.tag.orElse("tag:yaml.org,2002:str") == "tag:yaml.org,2002:str") {
                            val start = scalar.startMark.orElseThrow().line
                            val end = scalar.endMark.orElseThrow()
                            // Scalar end at column zero belongs to the following key, not this span.
                            spans.add(BlockSpan(start, end.line + if (end.column > 0) 1 else 0, scalar.value))
                        }
                        key = null
                    }
                    expectingKey = !expectingKey
                }
                Event.ID.Alias -> if (depth == 1) { expectingKey = !expectingKey; key = null }
                else -> Unit
            }
        }
        spans
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { emptyList() }

    fun blockValue(header: String): String? = try {
        var depth = 0
        var rootMapping = false
        var expectingKey = true
        var key: String? = null
        var result: String? = null
        var foundDescription = false
        for (event in Parse(settings).parseString(header)) {
            when (event.eventId) {
                Event.ID.MappingStart, Event.ID.SequenceStart -> {
                    if (depth == 0) {
                        if (event.eventId != Event.ID.MappingStart) break
                        rootMapping = true
                    } else if (depth == 1) {
                        // A collection consumes a root key or value, but is not a scalar description. Nested metadata is
                        // not selected.
                        if (!expectingKey && key == "description") foundDescription = true
                        expectingKey = !expectingKey
                        key = null
                    }
                    depth++
                }
                Event.ID.MappingEnd, Event.ID.SequenceEnd -> {
                    depth--
                    if (depth == 0) break
                }
                Event.ID.Scalar -> if (rootMapping && depth == 1) {
                    val scalar = event as ScalarEvent
                    if (expectingKey) {
                        key = scalar.value.lowercase()
                    } else {
                        if (key == "description" && !foundDescription) {
                            foundDescription = true
                            if ((scalar.isFolded || scalar.isLiteral) &&
                                scalar.tag.orElse("tag:yaml.org,2002:str") == "tag:yaml.org,2002:str") result = scalar.value
                        }
                        key = null
                    }
                    expectingKey = !expectingKey
                }
                Event.ID.Alias -> if (depth == 1) {
                    if (!expectingKey && key == "description") foundDescription = true
                    expectingKey = !expectingKey
                    key = null
                }
                else -> Unit
            }
        }
        result
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
}
