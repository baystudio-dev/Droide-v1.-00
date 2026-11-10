package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TerminalGuestCwdPolicyTest {
 @get:Rule val temp = TemporaryFolder()
 @Test fun samePathManagedBindCanFollowNestedWorkspace() {
  val root=temp.newFolder("workspace")
  val nested=File(root,"src/sub").apply { mkdirs() }
  val prefix=listOf("/bin/proot","-r","/guest/rootfs","-b",root.canonicalPath+":"+root.canonicalPath,"-w",root.canonicalPath,"/usr/bin/env","-i")
  val spec=TerminalLaunchSpec(prefix=prefix,shell="/bin/bash")
  val changed=TerminalGuestCwdPolicy.withObservedCwd(spec,root,nested.canonicalPath)
  assertEquals(nested.canonicalPath,changed.prefix[changed.prefix.indexOf("-w")+1])
  assertEquals(root.canonicalPath,spec.prefix[spec.prefix.indexOf("-w")+1])
 }
 @Test fun externalSymlinkAndUnknownLauncherFailClosed() {
  val root=temp.newFolder("workspace")
  val outside=temp.newFolder("outside")
  val sym=File(root,"link")
  Files.createSymbolicLink(sym.toPath(),outside.toPath())
  val prefix=listOf("/bin/proot","-r","/guest/rootfs","-b",root.canonicalPath+":"+root.canonicalPath,"-w",root.canonicalPath,"/usr/bin/env","-i")
  val spec=TerminalLaunchSpec(prefix=prefix,shell="/bin/bash")
  for (path in listOf(outside.absolutePath,sym.absolutePath,"/guest/home", "../outside")) {
   assertEquals(prefix,TerminalGuestCwdPolicy.withObservedCwd(spec,root,path).prefix)
  }
  assertEquals(listOf("custom","-w",root.canonicalPath),
   TerminalGuestCwdPolicy.withObservedCwd(TerminalLaunchSpec(prefix=listOf("custom","-w",root.canonicalPath)),root,root.absolutePath+"/src").prefix)
 }
}
