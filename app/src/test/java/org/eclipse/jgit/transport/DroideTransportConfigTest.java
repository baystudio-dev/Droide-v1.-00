package org.eclipse.jgit.transport;

import static org.junit.Assert.*;
import java.io.IOException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.junit.Test;

public class DroideTransportConfigTest {
    @Test public void bridgeUsesActualRepositoryConfig() throws Exception {
        try (InMemoryRepository repo = new InMemoryRepository(new DfsRepositoryDescription("bridge-fixture"))) {
            repo.getConfig().setBoolean("http", "https://github.com/fixture", "sslVerify", false);
            try (Transport transport = Transport.open(repo, new URIish("https://github.com/fixture/repo.git"))) {
                assertSame(repo.getConfig(), DroideTransportConfig.get(transport));
            }
        }
    }

    @Test public void userConfigFailureDoesNotBecomeDefaultTrustedTls() throws Exception {
        SystemReader previous = SystemReader.getInstance();
        SystemReader failing = new SystemReader() {
            @Override public StoredConfig getUserConfig() throws IOException { throw new IOException("synthetic config failure"); }
            @Override public String getHostname() { return previous.getHostname(); }
            @Override public String getenv(String key) { return previous.getenv(key); }
            @Override public String getProperty(String key) { return previous.getProperty(key); }
            @Override public FileBasedConfig openUserConfig(Config parent, FS fs) { return previous.openUserConfig(parent, fs); }
            @Override public FileBasedConfig openSystemConfig(Config parent, FS fs) { return previous.openSystemConfig(parent, fs); }
            @Override public FileBasedConfig openJGitConfig(Config parent, FS fs) { return previous.openJGitConfig(parent, fs); }
            @Override public long getCurrentTime() { return previous.getCurrentTime(); }
            @Override public int getTimezone(long when) { return previous.getTimezone(when); }
        };
        try {
            SystemReader.setInstance(failing);
            Transport localLess = new Transport(new URIish("https://github.com/fixture/repo.git")) {
                @Override public FetchConnection openFetch() { throw new AssertionError("No network allowed"); }
                @Override public PushConnection openPush() { throw new AssertionError("No network allowed"); }
                @Override public void close() { }
            };
            try (Transport transport = localLess) {
                assertThrows(IOException.class, () -> DroideTransportConfig.get(transport));
            }
        } finally { SystemReader.setInstance(previous); }
    }
}
