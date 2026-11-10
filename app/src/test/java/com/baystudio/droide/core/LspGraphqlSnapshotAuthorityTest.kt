package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
class LspGraphqlSnapshotAuthorityTest {
 private fun root():File=Files.createTempDirectory("graphql-authored-").toFile().also{File(it,".graphqlrc.json").writeText("""{"schema":"s.gql","documents":["f.gql"]}""");File(it,"s.gql").writeText("type Query { f: Int }");File(it,"f.gql").writeText("fragment F on Query { f }")}
 @Test fun emptyDirtySourceWinsAndReopenChangesGeneration(){val root=root();val path=File(root,"f.gql").path;val empty=LspGraphqlSnapshotAuthority.Overlay("",1,1);val a=LspGraphqlSnapshotAuthority.capture(root,mapOf(path to empty));assertEquals("",a.entries.getValue(path).text);val b=LspGraphqlSnapshotAuthority.capture(root,mapOf(path to LspGraphqlSnapshotAuthority.Overlay("",2,2)));assertNotEquals(a.generation,b.generation);assertFalse(a.overlaysCurrent(mapOf(path to LspGraphqlSnapshotAuthority.Overlay("",1,1))))}
 @Test fun selectedPreviouslyOpenMissingFileIsCaptured(){val root=root();File(root,"f.gql").delete();val path=File(root,"f.gql").path;val o=LspGraphqlSnapshotAuthority.Overlay("fragment D on Query { f }",3,4);assertEquals(o.text,LspGraphqlSnapshotAuthority.capture(root,mapOf(path to o)).entries.getValue(path).text)}
 @Test fun competitorBlocksAndPackageDirtyObserved(){val root=root();File(root,".graphqlrc.yaml").writeText("do not load");assertTrue(runCatching{LspGraphqlSnapshotAuthority.capture(root,emptyMap())}.isFailure);File(root,".graphqlrc.yaml").delete();val packagePath=File(root,"package.json").path;val a=LspGraphqlSnapshotAuthority.Overlay("{}",1,1);val s=LspGraphqlSnapshotAuthority.capture(root,mapOf(packagePath to a));assertFalse(s.overlaysCurrent(mapOf(packagePath to LspGraphqlSnapshotAuthority.Overlay("{}",1,2))))}
 @Test fun symlinkSpecialAndRootRemovalFail(){val root=root();File(root,"f.gql").delete();Files.createSymbolicLink(File(root,"f.gql").toPath(),File(root,"s.gql").toPath());assertTrue(runCatching{LspGraphqlSnapshotAuthority.capture(root,emptyMap())}.isFailure);root.deleteRecursively();assertTrue(runCatching{LspGraphqlSnapshotAuthority.root(root)}.isFailure)}
}
