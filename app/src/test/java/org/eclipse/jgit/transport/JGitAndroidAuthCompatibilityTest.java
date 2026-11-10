package org.eclipse.jgit.transport;

import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.junit.Test;

/** Synthetic challenges only: no sockets, real credentials or external endpoints. */
public class JGitAndroidAuthCompatibilityTest {
    @Test public void mixedNegotiateBasicKeepsBasicOperational() throws Exception {
        Map<String, String> requests = new LinkedHashMap<>();
        HttpConnection conn = connection(Arrays.asList("Negotiate", "Basic realm=\"test\""), requests);
        HttpAuthMethod method = HttpAuthMethod.scanResponse(conn, null);
        assertEquals(HttpAuthMethod.Type.BASIC, method.getType());
        method.authorize("fixture-user", "fixture-secret");
        method.configureRequest(conn);
        assertTrue(requests.get("Authorization").startsWith("Basic "));
    }

    @Test public void mixedNegotiateDigestKeepsDigestAndIgnoreRules() throws Exception {
        Map<String, String> requests = new LinkedHashMap<>();
        HttpConnection conn = connection(Arrays.asList("Negotiate", "Basic realm=\"test\"",
                "Digest realm=\"test\", nonce=\"fixture\", qop=\"auth\", algorithm=MD5"), requests);
        HttpAuthMethod method = HttpAuthMethod.scanResponse(conn, null);
        assertEquals(HttpAuthMethod.Type.DIGEST, method.getType());
        method.authorize("fixture-user", "fixture-secret");
        method.configureRequest(conn);
        assertTrue(requests.get("Authorization").startsWith("Digest "));
        assertEquals(HttpAuthMethod.Type.BASIC,
                HttpAuthMethod.scanResponse(conn, Collections.singleton(HttpAuthMethod.Type.DIGEST)).getType());
    }

    @Test public void negotiateOnlyIsUnsupportedWithoutLinkageError() throws Exception {
        assertEquals(HttpAuthMethod.Type.NONE, HttpAuthMethod.scanResponse(
                connection(Collections.singletonList("Negotiate"), new LinkedHashMap<>()), null).getType());
        assertThrows(UnsupportedOperationException.class, () -> HttpAuthMethod.Type.NEGOTIATE.method(""));
    }

    private HttpConnection connection(List<String> challenges, Map<String, String> requests) throws Exception {
        Map<String, List<String>> headers = Collections.singletonMap("WWW-Authenticate", challenges);
        URL url = new URL("https://github.com/fixture/repo.git/info/refs?service=git-upload-pack");
        return (HttpConnection) Proxy.newProxyInstance(HttpConnection.class.getClassLoader(),
                new Class<?>[] { HttpConnection.class }, (proxy, method, args) -> {
                    switch (method.getName()) {
                    case "getHeaderFields": return args == null || args.length == 0 ? headers : challenges;
                    case "getURL": return url;
                    case "getRequestMethod": return "GET";
                    case "setRequestProperty": requests.put((String) args[0], (String) args[1]); return null;
                    case "toString": return "SyntheticHttpConnection";
                    default: throw new AssertionError("Unexpected synthetic connection method: " + method.getName());
                    }
                });
    }
}
