package com.termux.terminal;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** Host stdout pipe timing only: no native poll, PTY, Android, curl or network. */
public final class ReaderHealthyOutputTiming {
    private static byte value(int position) { return (byte) (position * 17 + (position >>> 8)); }
    static final class PipeReader implements TerminalPtyIo.ReaderBackend {
        final InputStream input;
        final int expected;
        int delivered, reads, backoffs, requestedBackoffMillis;
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        PipeReader(InputStream input, int expected) { this.input = input; this.expected = expected; }
        public long poll(int millis) { return 1; }
        public long read(byte[] bytes) {
            try { reads++; return Math.max(0, input.read(bytes)); }
            catch (java.io.IOException e) { throw new RuntimeException(e); }
        }
        public boolean stopped() { return System.nanoTime() >= deadline; }
        public boolean enqueue(byte[] bytes, int count) {
            if (delivered + count > expected) throw new AssertionError("extra output");
            for (int i = 0; i < count; i++) {
                if (bytes[i] != value(delivered + i)) throw new AssertionError("byte mismatch at " + (delivered + i));
            }
            delivered += count;
            return true;
        }
        public void queued() { }
        public void backoff(int millis) throws InterruptedException { backoffs++; requestedBackoffMillis += millis; Thread.sleep(millis); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("producer")) {
            int count = Integer.parseInt(args[1]);
            byte[] bytes = new byte[4096];
            for (int offset = 0; offset < count; offset += bytes.length) {
                int n = Math.min(bytes.length, count - offset);
                for (int i = 0; i < n; i++) bytes[i] = value(offset + i);
                System.out.write(bytes, 0, n);
            }
            System.out.flush();
            return;
        }
        for (int trial = 0; trial < 4; trial++) {
            int bytes = trial == 0 ? 262144 : 4194304;
            Process child = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                    System.getProperty("java.class.path"), ReaderHealthyOutputTiming.class.getName(),
                    "producer", String.valueOf(bytes)).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            PipeReader reader = new PipeReader(child.getInputStream(), bytes);
            TerminalPtyIo.ReadReceipt receipt = new TerminalPtyIo.ReadReceipt();
            long started = System.nanoTime();
            try {
                TerminalPtyIo.readUntilClosed(reader, new byte[TerminalPtyIo.MAX_CHUNK], receipt);
                long elapsed = System.nanoTime() - started;
                if (!child.waitFor(2, TimeUnit.SECONDS) || child.exitValue() != 0 || receipt.incomplete || reader.delivered != bytes)
                    throw new AssertionError("incomplete host fixture");
                System.out.printf(java.util.Locale.ROOT,
                        "HOST_PIPE_METRIC trial=%d warmup=%s bytes=%d integrity=exact reads=%d backoffs=%d requestedBackoffMillis=%d elapsedMillis=%.3f%n",
                        trial, trial == 0, bytes, reader.reads, reader.backoffs, reader.requestedBackoffMillis, elapsed / 1_000_000.0);
            } finally { child.destroyForcibly(); child.getInputStream().close(); }
        }
    }
}
