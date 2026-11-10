package com.baystudio.droide.ui
import com.baystudio.droide.core.*
import org.junit.Assert.*
import org.junit.Test
class ElpRuntimeHealthApprovalUiTest {
    private fun request():ElpRuntimeHealthApprovalRequest {
        fun tool(role:String)=ElpSelectedToolEntry(role,ElpFileIdentity("/usr/bin/$role","/usr/lib/erlang/bin/$role",16,"a".repeat(64),"dev=1;ino=2",emptyList()),"/usr/lib/erlang")
        return ElpRuntimeHealthApprovalRequest(ElpToolSelection(tool("erl"),tool("escript")))
    }
    @Test fun disclosureRequiresSeparateExactSelectedToolApproval(){val r=request();assertTrue(r.title.contains("selected Erlang"));assertTrue(r.detail.contains("/usr/bin/erl"));assertTrue(r.detail.contains("/usr/bin/escript"));assertTrue(r.detail.contains("provenance are unverified"));assertTrue(r.detail.contains("zero-document"));assertTrue(r.detail.contains("no Rebar or project analysis"));assertTrue(r.detail.contains("including 10 seconds"))}
    @Test fun declineAndDismissCannotBeAcceptedLater(){val r=request();r.decline();assertThrows(IllegalStateException::class.java){ElpStageHealthPermit.acceptedFromExtensions(r)}}
    @Test fun acceptanceIsOneAttemptAndDoesNotClaimExecution(){val r=request();val p=ElpStageHealthPermit.acceptedFromExtensions(r);assertThrows(IllegalStateException::class.java){p.requireClaimed()};assertThrows(IllegalStateException::class.java){ElpStageHealthPermit.acceptedFromExtensions(r)};p.invalidate()}
}
