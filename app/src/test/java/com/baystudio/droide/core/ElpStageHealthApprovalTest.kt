package com.baystudio.droide.core
import org.junit.Test
import org.junit.Assert.*
class ElpStageHealthApprovalTest {
    companion object {
        fun selection():ElpToolSelection {
            fun entry(role:String)=ElpSelectedToolEntry(role,ElpFileIdentity("/usr/bin/$role","/usr/lib/erlang/bin/$role",32,"a".repeat(64),"dev=1;ino=2",listOf(ElpPathIdentity("/usr/bin/$role","ino=2","../lib/erlang/bin/$role"))),"/usr/lib/erlang")
            return ElpToolSelection(entry("erl"),entry("escript"))
        }
        fun rejected(action:()->Unit){try{action();fail("authority bypass")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    }
    @Test fun disclosureBindsUnverifiedExactEntries(){val r=ElpRuntimeHealthApprovalRequest(selection());assertTrue(r.detail.contains("/usr/bin/erl"));assertTrue(r.detail.contains("a".repeat(64)));assertTrue(r.detail.contains("unverified"));assertTrue(r.detail.contains("90 seconds"));assertTrue(r.detail.contains("no Rebar"));assertEquals(LocalUbuntuElpPolicy.SHA256,r.artifactSha256)}
    @Test fun unclaimedDuplicateRevokedChangedSelectionReject(){val s=selection();val p=ElpStageHealthPermit.acceptedFromExtensions(ElpRuntimeHealthApprovalRequest(s));rejected{p.requireClaimed()};p.claim();p.requireClaimed();rejected{p.claim()};rejected{p.requireCurrent(s.copy(erl=s.erl.copy(file=s.erl.file.copy(sha256="b".repeat(64)))),p.attemptId)};rejected{p.requireCurrent(s,"different")};p.invalidate();rejected{p.requireCurrent()}}
    @Test fun explicitDeclineCannotBecomePermit(){val request=ElpRuntimeHealthApprovalRequest(selection());request.decline();rejected{ElpStageHealthPermit.acceptedFromExtensions(request)}}
    @Test fun requestCannotIssueTwoPermits(){val request=ElpRuntimeHealthApprovalRequest(selection());ElpStageHealthPermit.acceptedFromExtensions(request);rejected{ElpStageHealthPermit.acceptedFromExtensions(request)}}
    @Test fun rebarAndPolicySubstitutionReject(){val s=selection();rejected{ElpStageHealthPermit.acceptedFromExtensions(ElpRuntimeHealthApprovalRequest(s.copy(rebar=s.erl.copy(role="rebar"))))};rejected{ElpStageHealthPermit.acceptedFromExtensions(ElpRuntimeHealthApprovalRequest(s,artifactSha256="b".repeat(64)))}}
}
