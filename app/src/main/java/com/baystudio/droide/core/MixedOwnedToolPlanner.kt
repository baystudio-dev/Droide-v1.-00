package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Complete mixed-batch validation. This produces no execution grant and performs no tool effect. */
internal object MixedOwnedToolPlanner {
    fun prepare(result: OwnedChatResult, callSession: (String)->OwnedResponseContentStore.Session,
        json: Json, turn: Int, maximumLegacyChars: Int, advertisedNames: Set<String>,
        priorSignature: String, priorRepeat: Int, checkCurrent: ()->Unit,
        onArtifactRecovery: (String,List<OwnedResponseContentStore.Handle>)->Unit = { _,_-> }): List<PreparedToolCall> {
        require(result.finishReason=="tool_calls" && !result.hasRefusal)
        require(result.calls.isNotEmpty() && result.calls.map { it.id }.distinct().size==result.calls.size)
        var signature=priorSignature;var repeat=priorRepeat
        val batch=result.calls.mapIndexed { index, call ->
            checkCurrent()
            require(call.name.length in 1..120 && call.name in advertisedNames) { "Mixed tool batch contains a tool not advertised for this request" }
            val session=callSession(call.id)
            require(call.arguments.kind==OwnedResponseContentStore.Kind.TOOL_ARGUMENTS)
            call.arguments.verify(session)
            val prepared=if(call.arguments.utf16Units>maximumLegacyChars) {
                val input = when (call.name) {
                    "write_file", "edit_file", "write", "edit" -> PreparedToolInput.SourceFile(
                        StreamedFileArgumentExtractor.extractWire(session,call.arguments.handle,call.name,call.id,checkCurrent),
                        call.arguments,session)
                    "run_command", "bash", "exec_command" -> PreparedToolInput.SourceCommand(
                        StreamedCommandArgumentExtractor.extract(session,call.arguments.handle,call.id,call.name,checkCurrent),
                        call.arguments,session)
                    "write_stdin" -> PreparedToolInput.SourceStdin(
                        StreamedStdinArgumentExtractor.extract(session,call.arguments.handle,call.id,checkCurrent),
                        call.arguments,session)
                    "apply_patch" -> PreparedToolInput.SourcePatch(
                        StreamedPatchArgumentExtractor.extract(session,call.arguments.handle,call.id,checkCurrent),
                        call.arguments,session)
                    "artifact" -> {
                        val extracted = try {
                            StreamedArtifactArgumentExtractor.extract(session,call.arguments.handle,call.id,checkCurrent)
                        } catch (failure: Exception) {
                            val handles = when (failure) {
                                is StreamedArtifactArgumentExtractor.ExtractionFailure -> failure.recoveryHandles
                                is StreamedArtifactArgumentExtractor.ExtractionCancelled -> failure.recoveryHandles
                                else -> emptyList()
                            }
                            onArtifactRecovery(call.id,handles)
                            throw failure
                        }
                        onArtifactRecovery(call.id,listOf(extracted.content.handle))
                        PreparedToolInput.SourceArtifact(extracted,call.arguments,session)
                    }
                    else -> error("Large arguments for ${call.name} are not supported; no tool in this batch was executed")
                }
                val next=if(input is PreparedToolInput.SourceCommand) input.prepared.argumentIdentity else "${call.name}:sha256:${call.arguments.sha256}"
                PreparedToolCall(index,call.id,call.name,input,
                    "tool:$turn:$index",if(next==signature)Math.addExact(repeat,1) else 1,next)
            } else {
                val raw=StringBuilder()
                session.openPages(call.arguments.handle,checkCurrent).use { pages ->
                    while(true) { checkCurrent();val page=pages.readPage()?:break
                        require(raw.length.toLong()+page.length<=maximumLegacyChars)
                        raw.append(page)
                    }
                }
                val wire=buildJsonArray { add(buildJsonObject {
                    put("id",call.id);put("type","function");put("function",buildJsonObject {
                        put("name",call.name);put("arguments",raw.toString())
                    })
                }) }
                AgentToolTurnPlanner.prepare(wire,json,turn,maximumLegacyChars,signature,repeat).single()
                    .copy(index=index,partId="tool:$turn:$index").also {
                        require(it.argumentError==null) { "Mixed tool batch has malformed arguments; no tool was executed" }
                    }
            }
            signature=prepared.signature;repeat=prepared.repeatOrdinal;prepared
        }
        checkCurrent();return batch
    }
}
