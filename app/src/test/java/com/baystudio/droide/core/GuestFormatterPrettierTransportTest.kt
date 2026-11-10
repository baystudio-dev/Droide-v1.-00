package com.baystudio.droide.core
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class GuestFormatterPrettierTransportTest {
    @Test fun originalPathAndSeparateStagesAreExplicitArguments()=runBlocking {
        val root=createTempDirectory("formatter-transport").toFile()
        try {
            val target=File(root,"original.ts").apply {writeText("saved")}
            val result=GuestDocumentFormatter.format(root,target.name,"unsaved") {args ->
                assertEquals(listOf("sh","-c"),args.take(2))
                assertEquals("droide-formatter",args[3])
                assertEquals(target.absolutePath,args.last())
                val input=File(args[4]);val output=File(args[5])
                assertNotEquals(input,output);assertNotEquals(target,output)
                assertEquals("unsaved",input.readText());assertEquals("saved",target.readText())
                output.writeText("formatted")
                ExecResult(0,"",false)
            }
            assertEquals("formatted",result!!.text);assertEquals("saved",target.readText())
            assertEquals(listOf(target.name),root.list()!!.toList())
        } finally {root.deleteRecursively()}
    }
    @Test fun cancellationKeepsSavedDocumentAndCleansStages()=runBlocking {
        val root=createTempDirectory("formatter-cancel").toFile()
        try {
            val target=File(root,"original.ts").apply {writeText("saved")}
            try {
                GuestDocumentFormatter.format(root,target.name,"unsaved") {throw CancellationException("stop")}
                fail("Expected cancellation")
            } catch(_:CancellationException) {}
            assertEquals("saved",target.readText());assertEquals(listOf(target.name),root.list()!!.toList())
        } finally {root.deleteRecursively()}
    }
}
