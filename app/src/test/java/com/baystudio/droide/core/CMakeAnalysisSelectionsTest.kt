package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import org.junit.Assert.*
import org.junit.Test

class CMakeAnalysisSelectionsTest {
    // Authored fixture boundary. Production Android open/fstat behavior is tested separately.
    private class Probe: CMakeAnalysisSelections.Inspector {
        var reads=0
        var rejectReads=false
        override fun directoryIdentity(directory:File,environmentIdentity:String):CMakeDirectoryIdentity {
            reads++;check(!rejectReads)
            var cursor:File?=directory
            while(cursor!=null){check(!Files.isSymbolicLink(cursor.toPath()));cursor=cursor.parentFile}
            val a=Files.readAttributes(directory.toPath(),"unix:dev,ino,uid,mode",NOFOLLOW_LINKS)
            check(directory.isDirectory)
            return CMakeDirectoryIdentity(environmentIdentity,(a["dev"] as Number).toLong(),(a["ino"] as Number).toLong(),(a["uid"] as Number).toLong(),(a["mode"] as Number).toInt())
        }
        override fun readCache(cache:File,maximumBytes:Int):ByteArray {
            reads++;check(!rejectReads && !Files.isSymbolicLink(cache.toPath()) && cache.isFile && cache.length()<=maximumBytes)
            return cache.readBytes()
        }
    }
    private inline fun fixture(block:(File,Probe,CMakeAnalysisSelections)->Unit) {
        val root=Files.createTempDirectory("cmake-selection-authored-").toFile().canonicalFile
        val probe=Probe();val store=CMakeAnalysisSelections(root,"workspace-1","env-1","mapping-1",WorkspacePathMapper(root,"/guest/work"),probe)
        try{block(root,probe,store)}finally{root.deleteRecursively()}
    }
    @Test fun siblingConfiguredBuildPairsInGuestNamespace()=fixture { root,_,store ->
        val source=File(root,"src").apply{mkdir()};val build=File(root,"build").apply{mkdir()}
        File(build,"CMakeCache.txt").writeText("# authored fixture\r\nCMAKE_HOME_DIRECTORY:INTERNAL=/guest//work/./src\r\n")
        val staged=store.validate(source.path,build.path)
        assertNull(store.selectionFor(File(source,"CMakeLists.txt")))
        val selection=store.commit(staged)
        assertEquals("/guest/work/src",selection.cacheSourceAssociation!!.cacheHomeGuestNormalized)
        assertEquals(source.path,selection.cacheSourceAssociation!!.sourceLocalCanonical)
        assertTrue(store.revalidate(selection))
        File(build,"CMakeCache.txt").writeText("OTHER:STRING=changed\nCMAKE_HOME_DIRECTORY:INTERNAL=/guest/work/src\n")
        assertTrue(store.revalidate(selection));assertSame(selection,store.selectionFor(File(source,"nested/x.cmake")))
    }
    @Test fun failedSaveAndCancelPreserveOldSelection()=fixture { root,_,store ->
        val source=File(root,"src").apply{mkdir()};val old=store.commit(store.validate(source.path,null))
        assertTrue(runCatching{store.validate(source.path,File(root,"absent").path)}.isFailure)
        assertSame(old,store.selectionFor(File(source,"x.cmake")));assertTrue(store.isCurrent(old))
        val staged=store.validate(source.path,null);store.discard(staged)
        assertTrue(runCatching{store.commit(staged)}.isFailure);assertTrue(store.isCurrent(old))
        val replacement=store.commit(store.validate(source.path,null))
        assertEquals(old.selectionRevision+1,replacement.selectionRevision);assertFalse(store.isCurrent(old))
        assertNull(replacement.configuredBuildLocalCanonical)
    }
    @Test fun missingSelectedSourceDoesNotWidenToAncestor()=fixture { root,probe,store ->
        val parent=store.commit(store.validate(root.path,null))
        val source=File(root,"src").apply{mkdir()};val selected=store.commit(store.validate(source.path,null))
        source.delete();assertFalse(store.revalidate(selected))
        probe.rejectReads=true
        assertSame(selected,store.selectionFor(File(source,"x.cmake")))
        val status=store.status(File(source,"x.cmake"));assertFalse(status.available);assertSame(selected,status.selection)
        assertTrue(store.isCurrent(parent))
    }
    @Test fun lookupAndStatusArePureEvenAfterDeletion()=fixture { root,probe,store ->
        val source=File(root,"src").apply{mkdir()};val selection=store.commit(store.validate(source.path,null))
        val reads=probe.reads;probe.rejectReads=true
        assertSame(selection,store.selectionFor(File(source,"x.cmake")));assertTrue(store.status(File(source,"x.cmake")).available)
        assertEquals(reads,probe.reads)
    }
    @Test fun rejectedAssociationFormsAndInvalidUtf8()=fixture { root,_,store ->
        val source=File(root,"src").apply{mkdir()};val build=File(root,"build").apply{mkdir()};val cache=File(build,"CMakeCache.txt")
        val invalid=listOf("","# CMAKE_HOME_DIRECTORY:INTERNAL=/guest/work/src\n","CMAKE_HOME_DIRECTORY:STRING=/guest/work/src\n",
            "CMAKE_HOME_DIRECTORY:INTERNAL=/guest/work/src\nCMAKE_HOME_DIRECTORY:INTERNAL=/guest/work/src\n",
            "CMAKE_HOME_DIRECTORY:INTERNAL=${source.path}\n","CMAKE_HOME_DIRECTORY:INTERNAL=/guest/work/../src\n",
            "CMAKE_HOME_DIRECTORY:INTERNAL=/guest\\work/src\n","CMAKE_HOME_DIRECTORY:INTERNAL=/guest/work/src\r",
            "CMAKE_HOME_DIRECTORY:INTERNAL=/guest/work/src\u0000\n")
        invalid.forEach{cache.writeText(it);assertTrue(it,runCatching{store.validate(source.path,build.path)}.isFailure)}
        cache.writeBytes(byteArrayOf(0xc3.toByte(),0x28));assertTrue(runCatching{store.validate(source.path,build.path)}.isFailure)
        cache.writeBytes(ByteArray(CMakeAnalysisSelections.MAX_CACHE_BYTES+1));assertTrue(runCatching{store.validate(source.path,build.path)}.isFailure)
    }
    @Test fun externalTraversalAndSymlinkSelectionsReject()=fixture { root,_,store ->
        val source=File(root,"src").apply{mkdir()}
        assertTrue(runCatching{store.validate("../",null)}.isFailure)
        assertTrue(runCatching{store.validate(root.parentFile.path,null)}.isFailure)
        val link=File(root,"alias");Files.createSymbolicLink(link.toPath(),source.toPath())
        assertTrue(runCatching{store.validate(link.path,null)}.isFailure)
        assertNull(CMakeAnalysisSelections.normalizeGuestPath("relative"))
        assertNull(CMakeAnalysisSelections.normalizeGuestPath("/a/../b"))
        assertEquals("/a/b",CMakeAnalysisSelections.normalizeGuestPath("//a/./b/"))
    }
    @Test fun capacityAndWorkspaceLifetimeRemainBounded()=fixture { root,_,store ->
        repeat(32){val f=File(root,"src$it").apply{mkdir()};store.commit(store.validate(f.path,null))}
        val extra=File(root,"extra").apply{mkdir()};assertTrue(runCatching{store.commit(store.validate(extra.path,null))}.isFailure)
        val selected=store.selectionFor(File(root,"src0/a.cmake"))!!
        store.clear();assertFalse(store.isCurrent(selected));assertNull(store.selectionFor(File(root,"src0/a.cmake")))
        assertTrue(runCatching{store.validate(root.path,null)}.isFailure)
    }
    @Test fun replacedDirectoryRetainsInertIdentity()=fixture { root,_,store ->
        val source=File(root,"src").apply{mkdir()};val selection=store.commit(store.validate(source.path,null))
        source.renameTo(File(root,"old"));source.mkdir()
        assertFalse(store.revalidate(selection));assertSame(selection,store.selectionFor(File(source,"a.cmake")))
    }
    @Test fun explicitBroaderSourceSwitchReplacesExactPriorChoiceAtomically()=fixture { root,_,store ->
        val inner=File(root,"src").apply{mkdir()};val document=File(inner,"x.cmake")
        val original=store.commit(store.validate(inner.path,null))
        val sibling=File(root,"other").apply{mkdir()};val other=store.commit(store.validate(sibling.path,null))
        val staged=store.validate(root.path,null)
        assertTrue(runCatching{store.commit(staged,original.copy())}.isFailure)
        assertSame(original,store.selectionFor(document));assertTrue(store.isCurrent(original))
        val replacement=store.commit(store.validate(root.path,null),original)
        assertSame(replacement,store.selectionFor(document));assertFalse(store.isCurrent(original))
        assertSame(other,store.selectionFor(File(sibling,"x.cmake")))
    }

}
