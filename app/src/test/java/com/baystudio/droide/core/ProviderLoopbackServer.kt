package com.baystudio.droide.core

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/** Public JDK HTTP APIs through reflection: Android's compile stubs omit jdk.httpserver. The
 * local unit tests run on a full JDK; a missing server is a failure, never a skip. */
internal class ProviderLoopbackServer private constructor(private val server: Any) {
    private val api = Class.forName("com.sun.net.httpserver.HttpServer")
    val address: InetSocketAddress get() = api.getMethod("getAddress").invoke(server) as InetSocketAddress
    var executor: Executor?
        get() = api.getMethod("getExecutor").invoke(server) as? Executor
        set(value) { api.getMethod("setExecutor", Executor::class.java).invoke(server, value) }

    fun createContext(path: String, handler: (Exchange) -> Unit) {
        val handlerApi = Class.forName("com.sun.net.httpserver.HttpHandler")
        val proxy = Proxy.newProxyInstance(handlerApi.classLoader, arrayOf(handlerApi)) { instance, method, args ->
            when (method.name) {
                "handle" -> { handler(Exchange(args!![0])); null }
                "toString" -> "Droide loopback HTTP test handler"
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.firstOrNull()
                else -> error("Unexpected HTTP handler method: ${method.name}")
            }
        }
        api.getMethod("createContext", String::class.java, handlerApi).invoke(server, path, proxy)
    }

    fun start() { api.getMethod("start").invoke(server) }
    fun stop(delaySeconds: Int) { api.getMethod("stop", Int::class.javaPrimitiveType).invoke(server, delaySeconds) }

    class Exchange(private val exchange: Any) {
        private val api = Class.forName("com.sun.net.httpserver.HttpExchange")
        val requestBody: InputStream get() = api.getMethod("getRequestBody").invoke(exchange) as InputStream
        val responseBody: OutputStream get() = api.getMethod("getResponseBody").invoke(exchange) as OutputStream
        val requestMethod: String get() = api.getMethod("getRequestMethod").invoke(exchange) as String
        val requestURI: URI get() = api.getMethod("getRequestURI").invoke(exchange) as URI
        val responseHeaders get() = Headers(requireNotNull(api.getMethod("getResponseHeaders").invoke(exchange)))
        fun sendResponseHeaders(status: Int, length: Long) {
            api.getMethod("sendResponseHeaders", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType).invoke(exchange, status, length)
        }
        fun close() { api.getMethod("close").invoke(exchange) }
    }

    class Headers(private val headers: Any) {
        fun add(name: String, value: String) {
            Class.forName("com.sun.net.httpserver.Headers").getMethod("add", String::class.java, String::class.java).invoke(headers, name, value)
        }
        fun set(name: String, value: String) {
            Class.forName("com.sun.net.httpserver.Headers").getMethod("set", String::class.java, String::class.java).invoke(headers, name, value)
        }
    }

    companion object {
        fun create(address: InetSocketAddress, backlog: Int): ProviderLoopbackServer {
            val api = Class.forName("com.sun.net.httpserver.HttpServer")
            return ProviderLoopbackServer(requireNotNull(api.getMethod("create", InetSocketAddress::class.java, Int::class.javaPrimitiveType).invoke(null, address, backlog)))
        }
    }
}
