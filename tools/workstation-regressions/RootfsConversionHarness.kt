package com.baystudio.droide.core

import java.io.File
import org.tukaani.xz.XZInputStream

fun main(args: Array<String>) {
    val assets = File(args[0])
    val output = File(args[1])
    output.mkdirs()
    for (image in listOf(BundledRootfsExtractor.ubuntu)) {
        File(assets, image.file).inputStream().use { compressed ->
            XZInputStream(compressed, 32768).use { input ->
                File(output, image.file.removeSuffix(".xz")).outputStream().use { sink ->
                    RootfsTarCompatibility.copy(input, sink, image.hardLinks)
                }
            }
        }
        println("ROOTFS_TAR_CONVERTED ${image.file} links=${image.hardLinks.size}")
    }
}
