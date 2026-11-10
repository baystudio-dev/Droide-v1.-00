package com.termux.terminal;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import org.junit.internal.TextListener;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.Failure;

/** Records the actual resize state class identity in the JVM executing its fixtures. */
public final class TerminalResizeFixtureRunner {
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Exact generated engine JAR required");
        File expected = new File(args[0]).getCanonicalFile();
        for (Class<?> type : new Class<?>[] { TerminalResizeState.class, TerminalResizeState.Receipt.class,
                TerminalResizeState.Backend.class, TerminalResizeState.Listener.class }) {
            URL source = type.getProtectionDomain().getCodeSource().getLocation();
            if (!expected.equals(new File(source.toURI()).getCanonicalFile()))
                throw new AssertionError("Resize production class shadowed: " + source);
            String path = type.getName().replace('.', '/') + ".class";
            URL resource = type.getResource("/" + path);
            if (!resource.toExternalForm().equals("jar:" + source.toExternalForm() + "!/" + path))
                throw new AssertionError("Resize resource is not from the loaded JAR");
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream input = resource.openStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
            }
            System.out.println("LOADED_RESIZE_CLASS name=" + type.getName() + " codeSource=" + source
                + " resource=" + resource + " sha256=" + hex(hash.digest()));
        }
        final int[] assumptions = {0};
        JUnitCore core = new JUnitCore(); core.addListener(new TextListener(System.out));
        core.addListener(new RunListener() {
            @Override public void testAssumptionFailure(Failure failure) { assumptions[0]++; }
        });
        Result result = core.run(TerminalResizeStateContractTest.class);
        System.out.println("ACTUAL_ENGINE_RESIZE_JUNIT tests=" + result.getRunCount() + " failures=" + result.getFailureCount()
            + " ignored=" + result.getIgnoreCount() + " assumptions=" + assumptions[0]);
        if (!result.wasSuccessful() || result.getRunCount() != 12 || result.getIgnoreCount() != 0 || assumptions[0] != 0) System.exit(1);
    }
}
