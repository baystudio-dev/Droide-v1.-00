package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BrowserAgent {
    /** Legacy standalone reader. Native Agent calls use the permission-aware AgentWebResearch session. */
    suspend fun fetch(url: String): String {
        try {
            val response = SafeAgentWebTransport().get(url) {}
            require(response.status in 200..299) { "HTTP ${response.status}" }
            val content = AgentWebDocuments.extract(response)
            return AgentWebTools.bounded(buildJsonObject {
                put("success",true); put("url",response.finalUrl); put("title",content.title)
                put("text",content.lines.take(80).joinToString("\n")); put("total_lines",content.lines.size)
                put("source_incomplete",response.truncated || content.textTruncated || content.lines.size > 80)
                put("note","Legacy preview. Use the Agent web tool for session references, continuation, links and citations.")
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return buildJsonObject { put("success",false); put("error",e.message.orEmpty().take(1_000)) }.toString()
        }
    }
}
