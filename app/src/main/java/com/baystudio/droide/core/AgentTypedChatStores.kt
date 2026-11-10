package com.baystudio.droide.core

import java.io.File

internal data class TypedChatResources(val owned:OwnedResponseContentStore,val journal:ToolCallHistoryJournal,val registry:CanonicalChatTurnRegistry,val recovery:OwnedChatRecoveryJournal=journal.recoveryJournal(),val nativeAssistants:NativeAssistantRegistry=registry.nativeAssistantRegistry()) {
    val history:TypedChatHistory get()=TypedChatHistory(registry,nativeAssistants)
}
internal object AgentTypedChatStores {
    fun forWorkspace(workDir:File,checkActive:()->Unit={}):TypedChatResources {
        val parent=File(ChatMediaStoragePaths.baseDirectory(workDir).parentFile,"typed-chat-history")
        require(!PathSecurity.contains(workDir.canonicalFile,parent))
        ChatMediaStoragePaths.requireNoLinks(parent)
        val root=File(parent,ChatMediaStoragePaths.workspaceKey(workDir));ChatMediaStoragePaths.requireNoLinks(root)
        val owned=AgentPublicContentStores.ownedStore(workDir) { checkActive();false }
        val journal=ToolCallHistoryJournal(File(root,"calls"),owned,checkActive)
        return TypedChatResources(owned,journal,CanonicalChatTurnRegistry(File(root,"turns"),owned,journal))
    }
}
