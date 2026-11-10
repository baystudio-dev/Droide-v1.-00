package com.baystudio.droide.core
import kotlinx.coroutines.CancellationException
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.events.Event
import org.snakeyaml.engine.v2.events.ScalarEvent
import org.snakeyaml.engine.v2.events.SequenceStartEvent
import org.snakeyaml.engine.v2.common.FlowStyle

// Scalar events only: no object construction or alias expansion.
internal object AgentPluginToolListYaml {
    private val settings = LoadSettings.builder().setCodePointLimit(65_536)
        .setMaxAliasesForCollections(0).setUseMarks(false).build()
    fun read(header: String): List<String>? = try {
        var depth = 0
        var expectingKey = true
        var key: String? = null
        var seen = false
        var collecting = false
        var complete = false
        var invalid = false
        val values = mutableListOf<String>()
        for (event in Parse(settings).parseString(header)) {
            when (event.eventId) {
                Event.ID.MappingStart, Event.ID.SequenceStart -> {
                    if (depth == 0) {
                        if (event.eventId != Event.ID.MappingStart) break
                    } else if (depth == 1) {
                        if (!expectingKey && key == "tools" && !seen) {
                            seen = true
                            collecting = event is SequenceStartEvent && event.flowStyle == FlowStyle.BLOCK
                            if (!collecting) invalid = true
                        }
                        expectingKey = !expectingKey
                        key = null
                    } else if (collecting) invalid = true
                    depth++
                }
                Event.ID.MappingEnd, Event.ID.SequenceEnd -> {
                    if (collecting && depth == 2) { collecting = false; complete = true }
                    depth--
                    if (depth == 0) break
                }
                Event.ID.Scalar -> {
                    val scalar = event as ScalarEvent
                    if (depth == 1) {
                        if (expectingKey) key = scalar.value.lowercase()
                        else {
                            if (key == "tools" && !seen) { seen = true; invalid = true }
                            key = null
                        }
                        expectingKey = !expectingKey
                    } else if (collecting && depth == 2) {
                        if (scalar.tag.orElse("tag:yaml.org,2002:str") != "tag:yaml.org,2002:str") invalid = true
                        else values += scalar.value
                    }
                }
                Event.ID.Alias -> {
                    if (collecting) invalid = true
                    if (depth == 1) {
                        if (!expectingKey && key == "tools" && !seen) { seen = true; invalid = true }
                        expectingKey = !expectingKey
                        key = null
                    }
                }
                else -> Unit
            }
        }
        values.takeIf { complete && !invalid }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
}
