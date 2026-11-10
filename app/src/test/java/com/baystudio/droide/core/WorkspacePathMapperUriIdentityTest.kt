package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class WorkspacePathMapperUriIdentityTest {
    @Test fun rejectsForeignAndLocalhostAuthoritiesBeforeRemotePathMapping() = withMapper { mapper, _ ->
        listOf("other-host", "localhost", "user@host", "localhost:22", "[::1]").forEach { authority ->
            assertNull(authority, mapper.uriToLocal("file://$authority/workspace/main.py"))
        }
    }

    @Test fun rejectsQueryAndFragmentIncludingEmptyDelimiters() = withMapper { mapper, root ->
        val localUri = root.resolve("main.py").toURI().toString()
        listOf("?query", "#fragment", "?", "#").forEach { suffix ->
            assertNull(suffix, mapper.uriToLocal("file:///workspace/main.py$suffix"))
            assertNull(suffix, mapper.uriToLocal(localUri + suffix))
        }
    }

    @Test fun keepsLiteralEncodedFilenameCharactersAndBothOwnedUriForms() = withMapper { mapper, root ->
        val file = root.resolve("space ü # ? %.py").canonicalFile
        val remoteUri = mapper.remoteUriFor(file)
        assertEquals(file, mapper.uriToLocal(remoteUri))
        assertEquals(file, mapper.uriToLocal(mapper.localUriFor(file)))
        assertEquals(file.toURI().toString(), mapper.normalizeUriToLocal(remoteUri))
        assertEquals(file.toURI().toString(), mapper.toLocalProtocolString(remoteUri))
        assertEquals(remoteUri, mapper.toRemoteProtocolString(file.toURI().toString()))
        assertEquals(root.canonicalFile, mapper.uriToLocal("file:///workspace"))
    }

    @Test fun rejectsOpaqueRelativeOutsideAndEncodedTraversalUris() = withMapper { mapper, _ ->
        listOf("file:main.py", "file:workspace/main.py", "main.py", "https://host/workspace/main.py",
            "file:///outside/main.py", "file:///workspace/%2e%2e/secret.py", "file:///workspace/%00.py")
            .forEach { assertNull(it, mapper.uriToLocal(it)) }
    }

    private fun withMapper(action: (WorkspacePathMapper, java.io.File) -> Unit) {
        val root = Files.createTempDirectory("droide-uri-identity-").toFile()
        try { action(WorkspacePathMapper(root, "/workspace"), root) }
        finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
