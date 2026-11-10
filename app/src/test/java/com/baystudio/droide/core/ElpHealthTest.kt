package com.baystudio.droide.core
import org.junit.Test
import org.junit.Assert.*
import java.io.*
import kotlinx.coroutines.runBlocking

class ElpHealthTest {
    class Peer(messages:List<String>,val code:Int=0):HostedStdioProcess {
        override val stdin=ByteArrayOutputStream()
        override val stdout=ByteArrayInputStream(ByteArrayOutputStream().also { out->messages.forEach{ContentLengthProtocol.writeMessage(out,it)} }.toByteArray())
        override val stderr=ByteArrayInputStream(byteArrayOf());override val isAlive=false;override val exitCode=code
        override suspend fun awaitExit()=code;override fun close(){}
    }
    private fun evidence(version:String="27.3.4.19")=ElpRuntimeEvidence(version,version.substringBefore('.').toInt(),"15.2.7","aarch64-unknown-linux-gnu","/usr/lib/erlang","/usr/lib/erlang/lib",null,"selection-fingerprint","libraries",false)
    private val good=listOf("""{"jsonrpc":"2.0","id":1,"result":{"capabilities":{"positionEncoding":"utf-16"}}}""","""{"jsonrpc":"2.0","id":2,"result":"pong"}""","""{"jsonrpc":"2.0","id":3,"result":null}""")
    @Test fun boundedZeroProjectLifecycle()=runBlocking {val peer=Peer(good);LocalUbuntuElpLspHealth.exercise(peer,evidence(),ElpDeadline.after(5000));val sent=peer.stdin.toString("UTF-8");assertTrue(sent.contains("\"rootUri\":null"));assertTrue(sent.contains("\"workspaceFolders\":[]"));assertTrue(sent.contains("\"processId\":null"));assertTrue(sent.contains("\"elp/ping\""));assertFalse(sent.contains("didOpen"));assertFalse(sent.contains("rebar"))}
    @Test fun initializeAndPongCannotPassMissingRuntime()=runBlocking {for(version in listOf("25.3","27.2","29.0","27.3-rc1")){val peer=Peer(good);var rejected=false;try{LocalUbuntuElpLspHealth.exercise(peer,evidence(version),ElpDeadline.after(1000))}catch(_:Exception){rejected=true};assertTrue(version,rejected);assertEquals(0,peer.stdin.size())}}
    @Test fun runtimeFailureAndBadLifecycleFail()=runBlocking {
        val error="""{"jsonrpc":"2.0","method":"window/showMessage","params":{"type":1,"message":"Could not find OTP"}}"""
        for(messages in listOf(good.take(1)+error+good.drop(1),good.dropLast(1),good.map{it.replace("pong","bad")},good+good.first())) {
            var rejected=false;try{LocalUbuntuElpLspHealth.exercise(Peer(messages),evidence(),ElpDeadline.after(1000))}catch(_:Exception){rejected=true};assertTrue(rejected)
        }
    }
}
