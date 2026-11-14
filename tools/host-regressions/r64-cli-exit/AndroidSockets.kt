package android.net
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
// Explicit Android boundary doubles. Do not claim LocalServerSocket/native socket runtime coverage.
class Credentials(val uid: Int = 12345)
class LocalSocket {
 var soTimeout: Int = 0
 val peerCredentials = Credentials()
 val inputStream: InputStream = ByteArrayInputStream(byteArrayOf())
 val outputStream: OutputStream = ByteArrayOutputStream()
 fun shutdownInput() {}
 fun shutdownOutput() {}
 fun close() {}
}
class LocalServerSocket(name: String) {
 val closed = AtomicBoolean(false)
 @Volatile var failClose = nextFailClose
 private val shutdown = CountDownLatch(1)
 init {
  nextFailClose = false
  newest = this
  constructorEntered?.countDown()
  constructorRelease?.let { check(it.await(5, TimeUnit.SECONDS)) }
 }
 fun accept(): LocalSocket { check(shutdown.await(8, TimeUnit.SECONDS)); throw IOException("listener closed") }
 fun close() { if (failClose) throw IOException("injected listener close failure"); closed.set(true); shutdown.countDown() }
 companion object {
  @Volatile var newest: LocalServerSocket? = null
  @Volatile var nextFailClose = false
  @Volatile var constructorEntered: CountDownLatch? = null
  @Volatile var constructorRelease: CountDownLatch? = null
 }
}
