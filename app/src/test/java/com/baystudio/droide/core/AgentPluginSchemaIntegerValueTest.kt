package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class AgentPluginSchemaIntegerValueTest {
    private val schema=Json.parseToJsonElement("""{"properties":{"count":{"type":"integer"}}}""").jsonObject
    private fun accepts(number:String) { AgentPluginJsonSchema.requireValid(schema,Json.parseToJsonElement("{\"count\":"+number+"}").jsonObject) }
    private fun rejects(number:String) { assertThrows(IllegalArgumentException::class.java) { accepts(number) } }
    @Test fun exponentIntegerAccepted() { accepts("1e3");accepts("-2E+4") }
    @Test fun integralDecimalAccepted() { accepts("1.0");accepts("-2.000");accepts("0.0") }
    @Test fun beyondLongIntegerAccepted() { accepts("9223372036854775808");accepts("-9223372036854775809") }
    @Test fun scaledDecimalIntegerAccepted() { accepts("12.30e1");accepts("1000e-3") }
    @Test fun fractionalNumbersRemainRejected() { rejects("1.5");rejects("1e-1");rejects("12.30e-1") }
    @Test fun precisionIsNotRoundedThroughDouble() { rejects("9007199254740992.1");rejects("1.0000000000000000000000001") }
    @Test fun ordinaryIntegersRemainValid() { accepts("0");accepts("-1");accepts("9223372036854775807") }
    @Test fun stringsAndBooleansRemainRejected() { rejects("\"1\"");rejects("true");rejects("null") }
    @Test fun integralNegativeScaleDoesNotNeedTrailingZeroNormalization() { accepts("100e2147483647") }
    @Test fun zeroWithExponentRemainsInteger() { accepts("0e-100");accepts("-0.000e-20") }
}
