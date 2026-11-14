#!/usr/bin/env python3
"""Exercise the vendored JNI PTY on Linux, without building an Android APK.

Faults affect only this temporary host library, never shipped Android code.
Host checks do not certify Android's PTY, SELinux, or PRoot behavior.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = r'''
package com.termux.terminal;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

public final class JNI {
    static native int createSubprocess(String command, String cwd, String[] args,
        String[] env, int[] pid, int rows, int columns);
    static native int waitFor(int pid);
    static native void close(int fd);
    static native void setFault(int fault);
    static native String readOutput(int fd);
    static native String readUntil(int fd, String marker, int timeoutMillis);
    static native void writeInput(int fd, byte[] bytes);
    static native int foregroundProcessGroup(int fd);
    static native void setPtyWindowSize(int fd, int rows, int columns);
    static native void terminateTestChild(int fd, int pid);

    static final class InteractiveChild implements AutoCloseable {
        final int fd, pid;
        boolean reaped;
        InteractiveChild(String cwd, String[] env, String script) {
            int[] child = {0};
            String[] argv = script == null ? new String[]{"/bin/bash", "--noprofile", "--norc", "-i"} :
                new String[]{"/bin/bash", "--noprofile", "--norc", "-i", "-c", script};
            fd = createSubprocess("/bin/bash", cwd,
                argv, env, child, 24, 80);
            pid = child[0];
            if (fd < 0 || pid <= 0) throw new AssertionError("Missing interactive PTY child");
        }
        String await(String marker) {
            String output = readUntil(fd, marker, 5000);
            if (!output.contains(marker)) throw new AssertionError("Missing " + marker + ": " + output);
            return output;
        }
        void send(String value) { writeInput(fd, value.getBytes(StandardCharsets.UTF_8)); }
        void finish(int expectedExit) {
            int result = waitFor(pid);
            reaped = true;
            if (result != expectedExit) throw new AssertionError("Unexpected interactive exit: " + result);
        }
        public void close() {
            if (!reaped) terminateTestChild(fd, pid);
            JNI.close(fd);
        }
    }
    static long fdCount() throws Exception {
        try (Stream<Path> files = Files.list(Path.of("/proc/self/fd"))) { return files.count(); }
    }
    static void expectFailure(String command, String cwd, String[] args, String[] env, int[] pid) {
        try {
            createSubprocess(command, cwd, args, env, pid, 24, 80);
            throw new AssertionError("Invalid or failed startup was accepted");
        } catch (RuntimeException expected) { }
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        String test = args[1];
        String cwd = args[2];
        String[] command = {"/bin/sh", "-c", "exit 7"};
        String[] env = {"PATH=/usr/bin:/bin", "TERM=xterm-256color", "PS1=PTY_TEST_READY> "};
        // Warm Java's file stream machinery before comparing descriptors.
        fdCount();
        long before = fdCount();
        if (test.equals("fork") || test.equals("grant")) {
            setFault(test.equals("fork") ? 1 : 2);
            for (int i = 0; i < 64; i++) {
                int[] pid = {0};
                expectFailure("/bin/sh", cwd, command, env, pid);
                if (pid[0] != 0) throw new AssertionError("Failed launch published a child PID");
            }
        } else if (test.equals("allocation")) {
            setFault(3);
            for (int i = 0; i < 64; i++) expectFailure("/bin/sh", cwd, command, env, new int[1]);
        } else if (test.equals("invalid")) {
            expectFailure(null, cwd, command, env, new int[1]);
            expectFailure("/bin/sh", null, command, env, new int[1]);
            expectFailure("/bin/sh", cwd, new String[]{null}, env, new int[1]);
            expectFailure("/bin/sh", cwd, command, new String[]{null}, new int[1]);
            expectFailure("/bin/sh", cwd, command, env, null);
            expectFailure("/bin/sh", cwd, command, env, new int[0]);
        } else if (test.equals("job-control")) {
            int[] pid = {0};
            String script = "[[ -t 0 && -t 1 && -t 2 && $- == *m* ]] || exit 41; " +
                "sleep 0.02 & fg %+; printf '\\nNATIVE_PTY_JOB_CONTROL_OK\\n'";
            int fd = createSubprocess("/bin/bash", cwd,
                new String[]{"/bin/bash", "--noprofile", "--norc", "-i", "-c", script}, env, pid, 24, 80);
            String output = readOutput(fd);
            int result = waitFor(pid[0]);
            close(fd);
            if (result != 0 || !output.contains("NATIVE_PTY_JOB_CONTROL_OK") ||
                output.contains("no job control") || output.contains("cannot set terminal process group"))
                throw new AssertionError("PTY/job control failed: exit=" + result + " " + output);
        } else if (test.equals("interactive-input")) {
            // This is a terminal stdin reply, not a second shell command or a pipe.
            String script = "[[ -t 0 && -t 1 && -t 2 ]] || exit 41; " +
                "printf 'READY_FOR_AUTH_CODE\\n'; IFS= read -r code; " +
                "printf '\\nAUTH_CODE=<%s>\\n' \"$code\"; [[ $code == 'sample code 123' ]]";
            try (InteractiveChild child = new InteractiveChild(cwd, env, script)) {
                child.await("READY_FOR_AUTH_CODE");
                child.send("sample code 123\r");
                child.await("AUTH_CODE=<sample code 123>");
                child.finish(0);
            }
        } else if (test.equals("ctrl-c")) {
            // A real interactive shell reads the next command after Ctrl+C. A bash
            // -c command list intentionally discards that list when interrupted.
            try (InteractiveChild child = new InteractiveChild(cwd, env, null)) {
                child.await("PTY_TEST_READY>");
                child.send("sleep 30\r");
                long deadline = System.nanoTime() + 5_000_000_000L;
                int foreground;
                do {
                    foreground = foregroundProcessGroup(child.fd);
                    if (foreground > 0 && foreground != child.pid) break;
                    Thread.sleep(10);
                } while (System.nanoTime() < deadline);
                if (foreground <= 0 || foreground == child.pid)
                    throw new AssertionError("Foreground job never acquired controlling terminal");
                // Send the same VINTR byte as the app's Ctrl+C action, not a host kill signal.
                child.send("\u0003");
                child.await("PTY_TEST_READY>");
                child.send("printf '\\nINTERRUPTED=%s\\n' \"$?\"\r");
                child.await("INTERRUPTED=130");
                child.send("exit 0\r");
                child.finish(0);
            }
        } else if (test.equals("resize")) {
            // Python observes SIGWINCH directly: bash may defer its trap until
            // a blocking builtin returns, which is not a failure of PTY resize.
            String script = "exec python3 -u -c 'import os,signal\n" +
                "def report(label):\n s=os.get_terminal_size(0); print(f\"{label}={s.lines} {s.columns}\",flush=True)\n" +
                "def resized(*args):\n report(\"RESIZED\"); raise SystemExit(0)\n" +
                "signal.signal(signal.SIGWINCH,resized)\nreport(\"INITIAL_SIZE\")\nsignal.pause()'";
            try (InteractiveChild child = new InteractiveChild(cwd, env, script)) {
                child.await("INITIAL_SIZE=24 80");
                setPtyWindowSize(child.fd, 37, 112);
                child.await("RESIZED=37 112");
                child.finish(0);
            }
        } else if (test.equals("success")) {
            for (int i = 0; i < 64; i++) {
                int[] pid = {0};
                int fd = createSubprocess("/bin/sh", cwd, command, env, pid, 24, 80);
                if (fd < 0 || pid[0] <= 0) throw new AssertionError("No owned PTY/child");
                int result = waitFor(pid[0]);
                close(fd);
                if (result != 7) throw new AssertionError("Lost child exit status: " + result);
            }
            if (waitFor(-1) != 127 || waitFor(0) != 127)
                throw new AssertionError("Invalid wait target must fail without reaping unrelated children");
        } else throw new AssertionError("Unknown test " + test);
        long after = fdCount();
        if (after > before + 1) throw new AssertionError("Leaked PTY descriptors: " + (after - before));
        System.out.println("PASS " + test + " fdDelta=" + (after - before));
    }
}
'''

C = r'''
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <jni.h>
#include <poll.h>
#include <time.h>
static int fault = 0;
static pid_t test_fork(void) {
    if (fault == 1) { errno = EAGAIN; return -1; }
    return fork();
}
static int test_grantpt(int fd) {
    if (fault == 2) { errno = EACCES; return -1; }
    return grantpt(fd);
}
static char *test_strdup(const char *value) {
    if (fault == 3) { errno = ENOMEM; return NULL; }
    return strdup(value);
}
#define fork test_fork
#define grantpt test_grantpt
#define strdup test_strdup
#include "termux.c"
#undef fork
#undef grantpt
#undef strdup
JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setFault(
    JNIEnv *env, jclass cls, jint value) { (void)env; (void)cls; fault = value; }
JNIEXPORT jstring JNICALL Java_com_termux_terminal_JNI_readOutput(
    JNIEnv *env, jclass cls, jint fd) {
    (void)cls;
    char buffer[8192]; size_t used = 0;
    struct pollfd ready = { .fd = fd, .events = POLLIN };
    while (used < sizeof(buffer) - 1 && poll(&ready, 1, 2000) > 0) {
        ssize_t count = read(fd, buffer + used, sizeof(buffer) - 1 - used);
        if (count <= 0) break;
        used += (size_t)count;
    }
    buffer[used] = '\0';
    return (*env)->NewStringUTF(env, buffer);
}
static long long monotonic_millis(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (long long)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}
JNIEXPORT jstring JNICALL Java_com_termux_terminal_JNI_readUntil(
    JNIEnv *env, jclass cls, jint fd, jstring marker, jint timeout) {
    (void)cls;
    const char *expected = (*env)->GetStringUTFChars(env, marker, NULL);
    if (!expected) return NULL;
    char buffer[8192] = {0}; size_t used = 0;
    long long deadline = monotonic_millis() + timeout;
    struct pollfd ready = { .fd = fd, .events = POLLIN };
    while (used < sizeof(buffer) - 1 && !strstr(buffer, expected)) {
        long long remaining = deadline - monotonic_millis();
        if (remaining <= 0) break;
        int result = poll(&ready, 1, (int)remaining);
        if (result < 0 && errno == EINTR) continue;
        if (result <= 0) break;
        ssize_t count = read(fd, buffer + used, sizeof(buffer) - 1 - used);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        used += (size_t)count;
        buffer[used] = '\0';
    }
    (*env)->ReleaseStringUTFChars(env, marker, expected);
    return (*env)->NewStringUTF(env, buffer);
}
JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_writeInput(
    JNIEnv *env, jclass cls, jint fd, jbyteArray bytes) {
    (void)cls;
    jsize length = (*env)->GetArrayLength(env, bytes);
    jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (!data) return;
    jsize used = 0;
    while (used < length) {
        ssize_t count = write(fd, data + used, (size_t)(length - used));
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) {
            throw_runtime_exception(env, "Host PTY input write failed");
            break;
        }
        used += (jsize)count;
    }
    (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
}
JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_foregroundProcessGroup(
    JNIEnv *env, jclass cls, jint fd) { (void)env; (void)cls; return tcgetpgrp(fd); }
JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_terminateTestChild(
    JNIEnv *env, jclass cls, jint fd, jint pid) {
    (void)env; (void)cls;
    // Test-only cleanup while the owned child has not yet been reaped.
    pid_t foreground = tcgetpgrp(fd);
    if (foreground > 0 && foreground != getpgrp()) kill(-foreground, SIGKILL);
    if (pid > 0) {
        kill(pid, SIGKILL);
        while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {}
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=ROOT / 'third_party/termux/native/termux.c')
    parser.add_argument('--cases', nargs='+', default=['fork', 'grant', 'allocation', 'invalid', 'success', 'job-control',
                                                     'interactive-input', 'ctrl-c', 'resize'])
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    java_home = Path(os.environ['JAVA_HOME'])
    results = []
    with tempfile.TemporaryDirectory(prefix='droide-jni-lifecycle-') as tmp:
        work = Path(tmp)
        (work / 'JNI.java').write_text(JAVA)
        (work / 'harness.c').write_text(C)
        # Include a private copy so --source can select a frozen baseline.
        (work / 'termux.c').write_bytes(args.source.read_bytes())
        subprocess.run(['cc', '-D_GNU_SOURCE', '-fPIC', '-shared', '-Wall', '-Wextra',
                        '-I' + str(java_home / 'include'), '-I' + str(java_home / 'include/linux'),
                        str(work / 'harness.c'), '-o', str(work / 'libpty-test.so')], check=True)
        subprocess.run([str(java_home / 'bin/javac'), '-d', str(work), str(work / 'JNI.java')], check=True)
        for case in args.cases:
            run = subprocess.run([str(java_home / 'bin/java'), '-Xcheck:jni', '-cp', str(work),
                                  'com.termux.terminal.JNI', str(work / 'libpty-test.so'), case, str(work)],
                                 text=True, capture_output=True, timeout=30, cwd=work)
            value = {'case': case, 'exitCode': run.returncode,
                     'stdout': run.stdout[-8000:], 'stderr': run.stderr[-8000:]}
            results.append(value)
            print(case, 'PASS' if run.returncode == 0 else 'FAIL')
            if run.returncode: print((run.stdout + run.stderr)[-1500:])
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps({'source': str(args.source), 'hostOnly': True,
                                          'cases': results}, indent=2) + '\n')
    return int(any(r['exitCode'] != 0 for r in results))


if __name__ == '__main__':
    raise SystemExit(main())
