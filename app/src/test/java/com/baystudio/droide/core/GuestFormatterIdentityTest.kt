package com.baystudio.droide.core
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class GuestFormatterIdentityTest {
    private val prettierBin=System.getenv("DROIDE_TEST_PRETTIER_BIN")
    private fun check(relative:String, override:Boolean=true, invalid:Boolean=false)=runBlocking {
        org.junit.Assume.assumeTrue("Set DROIDE_TEST_PRETTIER_BIN for native formatter integration tests", prettierBin != null)
        val root=createTempDirectory("droide-formatter-identity").toFile()
        try {
            File(root,".prettierrc").writeText(if(override)
                """{"semi":true,"overrides":[{"files":"$relative","options":{"semi":false}}]}"""
                else """{"semi":true}""")
            val file=File(root,relative);file.parentFile.mkdirs()
            val saved="const saved = 2;"+10.toChar()
            file.writeText(saved)
            val result=runCatching {
                GuestDocumentFormatter.format(root,relative,if(invalid) "const =" else "const value=1") { args ->
                    val executableArgs=if(args.first()=="prettier") listOf(File(prettierBin!!,"prettier").absolutePath)+args.drop(1) else args
                    val builder=ProcessBuilder(executableArgs).directory(root).redirectErrorStream(true)
                    builder.environment()["PATH"]=prettierBin+":"+System.getenv("PATH")
                    val process=builder.start()
                    val output=process.inputStream.bufferedReader().readText()
                    ExecResult(process.waitFor(),output,false)
                }
            }
            assertEquals(saved,file.readText())
            assertFalse(file.parentFile.listFiles()!!.any {it.name.startsWith(".droide-format")})
            if(invalid) assertTrue(result.isFailure)
            else assertEquals("const value = 1"+(if(override) "" else ";")+10.toChar(),result.getOrThrow()!!.text)
        } finally {root.deleteRecursively()}
    }
    @Test fun basenameSpecificOverrideUsesDocumentIdentity()=check("special.js")
    @Test fun relativePathOverrideUsesDocumentIdentity()=check("src/special.js")
    @Test fun quotedSpacedPathIsDataNotShellSyntax()=check("src/quo'ted space.js")
    @Test fun ordinaryConfigRemainsSupported()=check("ordinary.js",override=false)
    @Test fun formatterFailurePreservesOriginalAndCleansStage()=check("broken.js",invalid=true)
}
