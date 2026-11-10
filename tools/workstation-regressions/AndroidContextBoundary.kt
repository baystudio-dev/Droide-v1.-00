package android.content

import java.io.InputStream

class Context {
    val assets = AssetBoundary()
    class AssetBoundary {
        fun open(path: String): InputStream = error("Android assets are outside host scope")
    }
}
