package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LspCSharpDiagnosticsTest {
 @Test fun emptyWithoutIdentityNeverCertifiesOrClears() {
  assertNull(LspCSharpDiagnostics.parse(Json.parseToJsonElement("""{"kind":"full","items":[]}"""),"x",null))
  val identified=LspCSharpDiagnostics.parse(Json.parseToJsonElement("""{"kind":"full","items":[],"resultId":"current"}"""),"x",null)!!
  assertTrue(identified.semanticResult)
  assertNull(LspCSharpDiagnostics.parse(Json.parseToJsonElement("""{"kind":"full","items":[]}"""),"x",identified))
  assertNull(LspCSharpDiagnostics.parse(Json.parseToJsonElement("""{"kind":"unchanged","resultId":"other"}"""),"x",identified))
 }
}
