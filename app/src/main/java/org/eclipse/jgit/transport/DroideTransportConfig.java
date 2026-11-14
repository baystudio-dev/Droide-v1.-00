package org.eclipse.jgit.transport;

import java.io.IOException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.util.SystemReader;

/** Direct, non-reflective access to the actual transport's inherited configuration. */
public final class DroideTransportConfig {
    private DroideTransportConfig() { }

    public static Config get(Transport transport) throws IOException, ConfigInvalidException {
        // Clone's transport owns a newly initialized repository, too. Do not guess its config.
        // Unlike HttpConfig(URIish), this path never catches load errors and substitutes defaults.
        return transport.local != null ? transport.local.getConfig()
                : SystemReader.getInstance().getUserConfig();
    }
}
