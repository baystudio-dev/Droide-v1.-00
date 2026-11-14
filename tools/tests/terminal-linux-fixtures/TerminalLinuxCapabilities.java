package com.termux.terminal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Real interactive Bash over the shipped owner ABI; no replacement JNI methods or fake PTY. */
public final class TerminalLinuxCapabilities implements AutoCloseable {
    private static final String PROMPT = "DROIDE_CAPABILITY_PROMPT> ";
    private final long owner = JNI.reserveOwnedProcess();
    private final ExecutorService reaper = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "terminal-capability-reaper");
        thread.setDaemon(true);
        return thread;
    });
    private Future<Long> completion;
    private boolean spawned;
    private boolean reaped;

    private TerminalLinuxCapabilities(String cwd) throws Exception {
        try {
            check(JNI.ownedProcessApiVersion() == JNI.API_VERSION, "JNI ABI");
            int pid = JNI.createOwnedSubprocess(owner, "/bin/bash", cwd,
                new String[]{"/bin/bash", "--noprofile", "--norc", "-i"},
                new String[]{"PATH=/usr/bin:/bin", "TERM=xterm-256color", "COLORTERM=truecolor",
                    "LC_ALL=C.UTF-8", "PS1=" + PROMPT}, 24, 80);
            spawned = true;
            check(pid > 1, "child startup");
            await(PROMPT);
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    private static void check(boolean value, String detail) {
        if (!value) throw new AssertionError(detail);
    }

    private static boolean retry(long result) {
        return JNI.tag(result) == JNI.ERRNO && (JNI.value(result) == 4 || JNI.value(result) == 11);
    }

    private void send(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
        int offset = 0;
        while (offset < bytes.length && System.nanoTime() < deadline) {
            long ready = JNI.pollOwnedPtyOnce(owner, JNI.WRITE, 50);
            if (retry(ready)) continue;
            check(JNI.tag(ready) == JNI.SUCCESS, "poll write");
            long result = JNI.writeOwnedPtyOnce(owner, bytes, offset, bytes.length - offset);
            if (retry(result)) continue;
            check(JNI.tag(result) == JNI.SUCCESS && JNI.value(result) > 0, "write input");
            offset += JNI.value(result);
        }
        check(offset == bytes.length, "input deadline");
    }

    private String await(String marker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] bytes = new byte[2048];
        while (System.nanoTime() < deadline) {
            long ready = JNI.pollOwnedPtyOnce(owner, JNI.READ, 50);
            if (retry(ready)) continue;
            check(JNI.tag(ready) == JNI.SUCCESS, "poll read");
            long result = JNI.readOwnedPtyOnce(owner, bytes, 0, bytes.length);
            if (retry(result)) continue;
            check(JNI.tag(result) == JNI.SUCCESS, "read output");
            output.write(bytes, 0, JNI.value(result));
            check(output.size() <= 65536, "bounded output");
            String text = output.toString(StandardCharsets.UTF_8);
            if (text.contains(marker)) return text;
        }
        throw new AssertionError("Missing " + marker + ": " + output.toString(StandardCharsets.UTF_8));
    }

    private String command(String script) {
        send(script + "\r");
        return await(PROMPT);
    }

    private long waitForExit() throws Exception {
        if (completion == null) completion = reaper.submit(() -> JNI.waitForOwnedProcess(owner));
        long result = completion.get(10, TimeUnit.SECONDS);
        reaped = JNI.tag(result) == JNI.EXITED;
        check(reaped, "confirmed native reap");
        return result;
    }

    public void close() throws Exception {
        try {
            if (spawned && !reaped) {
                check(JNI.requestOwnedStop(owner, JNI.ANCHORED_SESSION_GROUP) == 0, "owned cleanup");
                waitForExit();
            }
        } finally {
            JNI.cancelOwnedPtyIo(owner, JNI.READ | JNI.WRITE);
            JNI.releaseOwnedPtyLane(owner, JNI.READ);
            JNI.releaseOwnedPtyLane(owner, JNI.WRITE);
            JNI.releaseOwnedProcess(owner);
            reaper.shutdownNow();
        }
    }

    public static void main(String[] args) throws Exception {
        try (TerminalLinuxCapabilities terminal = new TerminalLinuxCapabilities(args[0])) {
            check(terminal.command("[[ -t 0 && -t 1 && -t 2 && $- == *m* ]] && printf '\\nCAP_%s\\n' TTY")
                .contains("CAP_TTY"), "controlling terminal and job control");
            check(terminal.command("mkdir -p 'directory with spaces'; cd 'directory with spaces'; export CAP_STATE=kept; " +
                "set -o pipefail; printf '%s\\n' first second | tail -n 1 > result; " +
                "[[ $(cat result) == second && $CAP_STATE == kept ]] && printf '\\nCAP_%s\\n' PIPELINE")
                .contains("CAP_PIPELINE"), "persistent cwd, export, pipeline and redirection");
            check(terminal.command("[[ $CAP_STATE == kept && ${PWD##*/} == 'directory with spaces' ]] && " +
                "diff <(printf same) <(printf same) && printf '\\nCAP_%s\\n' SUBSTITUTION")
                .contains("CAP_SUBSTITUTION"), "process substitution");

            terminal.send("printf '\\nCAP_%s\\n' READING; IFS= read -r value; printf '\\nCAP_VALUE=<%s>\\n' \"$value\"\r");
            terminal.await("CAP_READING");
            terminal.send("café 世界\r");
            check(terminal.await(PROMPT).contains("CAP_VALUE=<café 世界>"), "interactive UTF-8 input");

            terminal.send("bash -c 'printf \"\\nCAP_%s\\n\" FOREGROUND; exec sleep 30'\r");
            terminal.await("CAP_FOREGROUND");
            terminal.send("\u0003");
            terminal.await(PROMPT);
            check(terminal.command("printf '\\nCAP_STATUS=%s\\n' \"$?\"").contains("CAP_STATUS=130"), "Ctrl+C");

            terminal.send("bash -c 'printf \"\\nCAP_%s\\n\" SUSPEND; exec sleep 30'\r");
            terminal.await("CAP_SUSPEND");
            terminal.send("\u001a");
            check(terminal.await(PROMPT).contains("Stopped"), "Ctrl+Z");
            check(terminal.command("bg %+ && [[ -n $(jobs -pr) ]] && { kill %+; wait %+; printf '\\nCAP_%s\\n' RESUMED; }")
                .contains("CAP_RESUMED"), "background job resumption");

            terminal.send("saved=$(stty -g); stty raw -echo; printf '\\nCAP_%s\\n' RAW; IFS= read -r -n 1 key; " +
                "stty \"$saved\"; printf '\\nCAP_KEY=<%s>\\n' \"$key\"\r");
            terminal.await("CAP_RAW");
            terminal.send("K");
            check(terminal.await(PROMPT).contains("CAP_KEY=<K>"), "raw input without newline");

            terminal.send("python3 -u -c 'import os,signal; " +
                "signal.signal(signal.SIGWINCH,lambda *a:(print(\"CAP_RESIZE=%s,%s\" % (os.get_terminal_size().lines,os.get_terminal_size().columns)),exit(0))); " +
                "print(\"CAP_SIZE=%s,%s\" % (os.get_terminal_size().lines,os.get_terminal_size().columns)); signal.pause()'\r");
            terminal.await("CAP_SIZE=24,80");
            check(JNI.resizeOwnedPty(terminal.owner, 37, 112), "resize accepted");
            check(terminal.await(PROMPT).contains("CAP_RESIZE=37,112"), "SIGWINCH and TUI geometry");
            terminal.send("\u0004");
            check(JNI.value(terminal.waitForExit()) == 0, "Ctrl+D closes shell cleanly");
        }
        System.out.println("TERMINAL_LINUX_CAPABILITIES_PASS scenarios=9");
    }
}
