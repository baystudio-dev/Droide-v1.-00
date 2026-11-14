package com.termux.terminal;

import java.io.File;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import org.junit.internal.TextListener;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;

/** Test fixture only. Records actual loaded AAR identities in the same test JVM. */
public final class AarFixtureRunner {
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    private static void recordClass(Class<?> type, File expectedJar) throws Exception {
        URL source = type.getProtectionDomain().getCodeSource().getLocation();
        File actual = new File(source.toURI()).getCanonicalFile();
        if (!expectedJar.getCanonicalFile().equals(actual)) throw new AssertionError("production class shadowed: " + source);
        String name = type.getName().replace('.', '/') + ".class";
        URL resource = type.getResource("/" + name);
        if (!resource.toExternalForm().equals("jar:" + source.toExternalForm() + "!/" + name))
            throw new AssertionError("resource did not come from the loaded class AAR JAR: " + resource);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream input = resource.openStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) sha.update(buffer, 0, count);
        }
        System.out.println("LOADED_AAR_CLASS name=" + type.getName() + " codeSource=" + source.toExternalForm()
                + " resource=" + resource.toExternalForm() + " sha256=" + hex(sha.digest()));
    }
    private static void measuredPipe() throws Exception {
        final int count = 4194304;
        Process producer = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                System.getProperty("java.class.path"), ReaderHealthyOutputTiming.class.getName(),
                "producer", String.valueOf(count)).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        final ReaderHealthyOutputTiming.PipeReader pipe = new ReaderHealthyOutputTiming.PipeReader(producer.getInputStream(), count);
        final MessageDigest sha = MessageDigest.getInstance("SHA-256");
        TerminalPtyIo.ReaderBackend measured = new TerminalPtyIo.ReaderBackend() {
            public long poll(int millis) { return pipe.poll(millis); }
            public long read(byte[] bytes) { return pipe.read(bytes); }
            public boolean stopped() { return pipe.stopped(); }
            public boolean enqueue(byte[] bytes, int length) {
                boolean accepted = pipe.enqueue(bytes, length);
                if (accepted) sha.update(bytes, 0, length);
                return accepted;
            }
            public void queued() { pipe.queued(); }
            public void backoff(int millis) throws InterruptedException { pipe.backoff(millis); }
        };
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        boolean cpu = bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled();
        long startCpu = cpu ? bean.getCurrentThreadCpuTime() : -1;
        long start = System.nanoTime();
        TerminalPtyIo.ReadReceipt receipt = new TerminalPtyIo.ReadReceipt();
        try {
            TerminalPtyIo.readUntilClosed(measured, new byte[TerminalPtyIo.MAX_CHUNK], receipt);
            long elapsed = System.nanoTime() - start;
            long cpuElapsed = cpu ? bean.getCurrentThreadCpuTime() - startCpu : -1;
            if (!producer.waitFor(2, TimeUnit.SECONDS) || producer.exitValue() != 0 || receipt.incomplete || pipe.delivered != count)
                throw new AssertionError("incomplete measured host pipe fixture");
            System.out.println("MEASURED_HOST_PIPE bytes=" + pipe.delivered + " sha256=" + hex(sha.digest())
                    + " reads=" + pipe.reads + " backoffs=" + pipe.backoffs
                    + " requestedBackoffMillis=" + pipe.requestedBackoffMillis + " elapsedNanos=" + elapsed
                    + " readerThreadCpuNanos=" + cpuElapsed + " nativePtyExecuted=false");
        } finally { producer.destroyForcibly(); producer.getInputStream().close(); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("mode and exact extracted AAR JAR path required");
        File expected = new File(args[1]);
        recordClass(TerminalPtyIo.class, expected);
        recordClass(TerminalProcessOwner.class, expected);
        recordClass(ByteQueue.class, expected);
        if (args[0].equals("junit")) {
            JUnitCore core = new JUnitCore(); core.addListener(new TextListener(System.out));
            Result result = core.run(TerminalOwnedProcessTest.class, TerminalPtyReadProgressTest.class);
            System.out.println("ACTUAL_AAR_JUNIT tests=" + result.getRunCount() + " failures=" + result.getFailureCount());
            if (!result.wasSuccessful()) System.exit(1);
        } else if (args[0].equals("pipe")) {
            ReaderHealthyOutputTiming.main(new String[0]);
            measuredPipe();
        } else throw new IllegalArgumentException("unknown fixture mode");
    }
}
