package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class ChildRegistrationContractTest {
    @Test fun registrationPreservesUnitAndVoidContract() {
        val resources=SubagentOwnedResources{_,_->}
        assertEquals(Unit,resources.register("fixture"){})
        val methods=SubagentOwnedResources::class.java.declaredMethods.filter{it.name in setOf("register","registerTerminal")}
        assertEquals(2,methods.size)
        methods.forEach { assertEquals("${it.name} must not expose list insertion result",Void.TYPE,it.returnType) }
    }
}
