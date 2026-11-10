package com.baystudio.droide.core
import java.nio.file.Files
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class CSharpAnalysisSelectionsTest {
 @Test fun oneSelectionRequiresExplicitModeAndMetadataOnly() {
  val root=Files.createTempDirectory("csharp-selection").toFile();try {
   val doc=File(root,"a.cs").apply{writeText("class A {}")};val solution=File(root,"a.slnx").apply{writeText("<Solution />")}
   var reads=0;val store=CSharpAnalysisSelections(root){_,_->reads++;"metadata"}
   val a=store.validate(doc,root.path,CSharpAnalysisSelections.Mode.EXPLICIT_SOLUTION,solution.path);assertNull(store.current())
   val committed=store.commit(a,null);assertTrue(store.isCurrent(committed));assertTrue(reads>0)
   solution.delete();assertFalse(store.status(doc).available);assertSame(committed,store.current())
   assertFalse(store.revalidate(committed))
   val b=store.validate(doc,root.path,CSharpAnalysisSelections.Mode.NATIVE_DIRECTORY_DISCOVERY,null);store.commit(b,committed)
   assertTrue(store.status(doc).available);store.close();assertNull(store.current())
  }finally{root.deleteRecursively()}
 }
 @Test fun projectAndSymlinkAreNotSolutions() {
  val root=Files.createTempDirectory("csharp-selection").toFile();try {
   val doc=File(root,"a.cs").apply{writeText("x")};val project=File(root,"a.csproj").apply{writeText("x")};val store=CSharpAnalysisSelections(root){_,_->"x"}
   assertTrue(runCatching{store.validate(doc,root.path,CSharpAnalysisSelections.Mode.EXPLICIT_SOLUTION,project.path)}.isFailure)
   val link=File(root,"link.sln");Files.createSymbolicLink(link.toPath(),project.toPath())
   assertTrue(runCatching{store.validate(doc,root.path,CSharpAnalysisSelections.Mode.EXPLICIT_SOLUTION,link.path)}.isFailure)
  }finally{root.deleteRecursively()}
 }
}
