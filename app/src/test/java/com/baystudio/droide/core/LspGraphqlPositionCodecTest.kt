package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test
class LspGraphqlPositionCodecTest {
 @Test fun exactLfCrlfAstralAndEof(){val s="a😀\r\nb\n";assertEquals(3,LspGraphqlPositionCodec.offset(s,0,3));assertNull(LspGraphqlPositionCodec.offset(s,0,2));assertFalse(LspGraphqlPositionCodec.boundary(s,4));assertEquals(5,LspGraphqlPositionCodec.offset(s,1,0));assertEquals(LspGraphqlPositionCodec.Position(2,0),LspGraphqlPositionCodec.position(s,s.length));assertEquals(0,LspGraphqlPositionCodec.offset("",0,0))}
 @Test fun bareCrAndMalformedScalar(){assertNull(LspGraphqlPositionCodec.cursor("a\rb",1,0));assertEquals(2,LspGraphqlPositionCodec.offset("a\rb",1,0));assertFalse(LspGraphqlPositionCodec.validText("\uD800"))}
}
