package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Health means native transport plus selected OTP evidence, never project or embedded-service acceptance. */
internal object LocalUbuntuElpLspHealth {
    const val TIMEOUT_MS = 90_000L
    const val LABEL = "Native transport and selected OTP probe passed; project/embedded-service/eqWAlizer acceptance unverified"
    fun requireRuntime(e: ElpRuntimeEvidence) {
        val parts=e.otpVersion.split('.').map { it.toIntOrNull() ?: error("Incomplete or prerelease OTP health evidence") }
        require(parts.size>=2 && parts[0] in 27..28 && (parts[0]!=27 || parts[1]>=3) && e.otpMajor==parts[0])
        require(e.ertsVersion.matches(Regex("[0-9]+(?:\\.[0-9]+)+")) && e.architecture.contains("aarch64") &&
            e.rootDir.startsWith('/') && e.libDir.startsWith(e.rootDir+"/") && e.selectionIdentity.isNotBlank() && e.libraryIdentity.isNotBlank())
        require(e.rebarVersion==null) { "Staged ELP health cannot execute Rebar" }
    }
    suspend fun exercise(process: HostedStdioProcess, evidence: ElpRuntimeEvidence, deadline: ElpDeadline): Unit = withContext(Dispatchers.IO) {
        requireRuntime(evidence)
        suspend fun send(value: JsonObject) { currentCoroutineContext().ensureActive();deadline.check();ContentLengthProtocol.writeMessage(process.stdin,value.toString()) }
        var frames=0;var bytes=0
        suspend fun response(id: Int): JsonElement {
            while(true) {
                currentCoroutineContext().ensureActive();deadline.check()
                val raw=ContentLengthProtocol.readMessage(process.stdout) ?: error("ELP health exited before response")
                bytes=Math.addExact(bytes,raw.toByteArray().size);require(++frames<=64&&bytes<=16*1024*1024)
                val msg=LocalUbuntuPowerShellRuntimePolicy.strictObject(raw,8*1024*1024)
                require(msg["jsonrpc"]==JsonPrimitive("2.0"))
                val method=(msg["method"] as? JsonPrimitive)?.content
                if(method!=null) {
                    require(msg["error"]==null&&msg["result"]==null)
                    if(method in setOf("window/showMessage","window/logMessage")) {
                        val params=msg["params"]!!.jsonObject
                        val text=params["message"]!!.jsonPrimitive.content
                        require(params["type"]!!.jsonPrimitive.int>=3 && !text.contains("Could not find OTP",true) &&
                            !text.contains("failed",true) && !text.contains("error",true)) { "ELP reported runtime failure: ${text.take(200)}" }
                        require(msg["id"]==null)
                    } else if(method=="client/registerCapability") {
                        val registrations=msg["params"]!!.jsonObject["registrations"]!!.jsonArray
                        require(registrations.size in 1..8 && registrations.all { it.jsonObject["method"]==JsonPrimitive("workspace/didChangeWatchedFiles") })
                        send(buildJsonObject{put("jsonrpc","2.0");put("id",requireNotNull(msg["id"]));put("error",buildJsonObject{put("code",-32601);put("message","No watcher service in empty-project health")})})
                    } else error("Unexpected ELP empty-project health request/notification: $method")
                    continue
                }
                require(msg["id"]==JsonPrimitive(id) && msg["error"]==null && msg.containsKey("result")) { "Unexpected ELP health response" }
                return msg.getValue("result")
            }
        }
        suspend fun request(id:Int,method:String,params:JsonElement?=null):JsonElement {
            send(buildJsonObject{put("jsonrpc","2.0");put("id",id);put("method",method);if(params!=null)put("params",params)})
            return response(id)
        }
        send(buildJsonObject{put("jsonrpc","2.0");put("id",1);put("method","initialize");put("params",buildJsonObject{
            put("processId",JsonNull);put("rootUri",JsonNull);put("workspaceFolders",JsonArray(emptyList()));put("capabilities",buildJsonObject{put("general",buildJsonObject{put("positionEncodings",JsonArray(listOf(JsonPrimitive("utf-16"))))})})
        })})
        val init=response(1).jsonObject;val capabilities=init["capabilities"]!!.jsonObject
        require(capabilities["positionEncoding"] in setOf(null,JsonPrimitive("utf-16")))
        send(buildJsonObject{put("jsonrpc","2.0");put("method","initialized");put("params",buildJsonObject{})})
        require(request(2,"elp/ping",JsonArray(emptyList()))==JsonPrimitive("pong"))
        require(request(3,"shutdown")==JsonNull)
        send(buildJsonObject{put("jsonrpc","2.0");put("method","exit")})
        process.stdin.close()
        deadline.check();require(ContentLengthProtocol.readMessage(process.stdout)==null) { "ELP health emitted trailing stdout" }
        deadline.check();require(process.awaitExit()==0) { "ELP native health did not exit cleanly" }
    }
}
