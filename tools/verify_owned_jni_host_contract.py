#!/usr/bin/env python3
"""Run unchanged owned-token JNI C against a selected real engine JNI.class."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JNI_CLASS = 'com/termux/terminal/JNI.class'
JAVA = r'''package com.termux.terminal;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public final class OwnedJniHostContract {
    static int checks;
    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    static void expectRuntime(Runnable action) {
        try { action.run(); } catch (RuntimeException expected) { checks++; return; }
        throw new AssertionError("expected RuntimeException");
    }
    static void tag(long result, int expected, String message) {
        check(JNI.tag(result) == expected, message + ": " + result);
    }
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static void identity(String expectedJar, String expectedHash) throws Exception {
        Path actual = Paths.get(JNI.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        check(actual.equals(Paths.get(expectedJar)), "JNI loaded from selected engine jar");
        var resource = JNI.class.getResource("/com/termux/terminal/JNI.class");
        String expectedResource = "jar:" + actual.toUri().toURL().toExternalForm() + "!/com/termux/terminal/JNI.class";
        check(resource != null && resource.toExternalForm().equals(expectedResource), "JNI resource URL");
        try (var stream = resource.openStream()) {
            check(stream != null && hash(stream.readAllBytes()).equals(expectedHash), "loaded JNI bytes");
        }
        check(JNI.ownedProcessApiVersion() == 2, "actual registered API version");
    }
    static void release(long owner) {
        check(JNI.releaseOwnedPtyLane(owner, JNI.READ), "release read lane");
        check(JNI.releaseOwnedPtyLane(owner, JNI.WRITE), "release write lane");
        check(JNI.releaseOwnedProcess(owner), "release lifecycle");
        check(!JNI.releaseOwnedProcess(owner), "repeated release rejected");
        check(!JNI.releaseOwnedPtyLane(owner, JNI.READ), "stale lane rejected");
    }
    static void owners() {
        byte[] buffer = new byte[8];
        for (long invalid : new long[] {0, -1, Long.MAX_VALUE}) {
            tag(JNI.readOwnedPtyOnce(invalid, buffer, 0, 1), JNI.INVALID_OWNER, "invalid read owner");
            tag(JNI.writeOwnedPtyOnce(invalid, buffer, 0, 1), JNI.INVALID_OWNER, "invalid write owner");
            tag(JNI.pollOwnedPtyOnce(invalid, JNI.READ, 0), JNI.INVALID_OWNER, "invalid poll owner");
            check(!JNI.releaseOwnedProcess(invalid), "invalid release owner");
            check(!JNI.releaseOwnedPtyLane(invalid, JNI.WRITE), "invalid release lane");
            check(!JNI.resizeOwnedPty(invalid, 24, 80), "invalid resize owner");
            tag(JNI.waitForOwnedProcess(invalid), JNI.QUARANTINED, "invalid waiter");
            JNI.cancelOwnedPtyIo(invalid, 3);
        }
        long previous = 0;
        for (int iteration = 0; iteration < 80; iteration++) {
            long owner = JNI.reserveOwnedProcess();
            check(owner > previous, "monotonic generation"); previous = owner;
            tag(JNI.pollOwnedPtyOnce(owner, JNI.READ, 0), JNI.STATE_ERROR, "reserved not running");
            tag(JNI.waitForOwnedProcess(owner), JNI.NO_CHILD, "reserved handoff no child");
            tag(JNI.waitForOwnedProcess(owner), JNI.QUARANTINED, "second waiter rejected");
            release(owner);
        }
        long[] owners = new long[64];
        int acquired = 0;
        try {
            for (; acquired < owners.length; acquired++) owners[acquired] = JNI.reserveOwnedProcess();
            check(Arrays.stream(owners).distinct().count() == 64, "unique live owners");
            expectRuntime(() -> JNI.reserveOwnedProcess());
        } finally {
            for (int i = 0; i < acquired; i++) release(owners[i]);
        }
        long owner = JNI.reserveOwnedProcess();
        try {
            for (int[] range : new int[][] {{-1,1},{0,0},{0,-1},{0,4097},{8,1},{Integer.MAX_VALUE,1}}) {
                tag(JNI.readOwnedPtyOnce(owner, buffer, range[0], range[1]), JNI.STATE_ERROR, "read bounds");
                tag(JNI.writeOwnedPtyOnce(owner, buffer, range[0], range[1]), JNI.STATE_ERROR, "write bounds");
            }
            tag(JNI.readOwnedPtyOnce(owner, null, 0, 1), JNI.STATE_ERROR, "null read buffer");
            tag(JNI.writeOwnedPtyOnce(owner, null, 0, 1), JNI.STATE_ERROR, "null write buffer");
            for (int lane : new int[] {0,3,-1}) tag(JNI.pollOwnedPtyOnce(owner,lane,0), JNI.STATE_ERROR,"poll lane");
            for (int timeout : new int[] {-1,101}) tag(JNI.pollOwnedPtyOnce(owner,JNI.READ,timeout), JNI.STATE_ERROR,"poll timeout");
            check(!JNI.releaseOwnedPtyLane(owner,3),"combined release rejected");
            check(!JNI.resizeOwnedPty(owner,0,80),"resize bounds");
            JNI.cancelOwnedPtyIo(owner,JNI.READ);
            tag(JNI.readOwnedPtyOnce(owner,buffer,0,1),JNI.CANCELLED,"cancelled read");
        } finally { release(owner); }
    }
    static void failedStartup(String cwd) {
        long owner = JNI.reserveOwnedProcess();
        try {
            expectRuntime(() -> JNI.createOwnedSubprocess(owner,null,cwd,new String[]{"/bin/bash"},new String[0],24,80));
            tag(JNI.waitForOwnedProcess(owner),JNI.NO_CHILD,"invalid startup settled");
        } finally { release(owner); }
    }
    static void awaitText(long owner, String marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
        StringBuilder text = new StringBuilder(); byte[] data = new byte[1024];
        while (System.nanoTime() < deadline) {
            long polled = JNI.pollOwnedPtyOnce(owner,JNI.READ,100);
            if (JNI.tag(polled) == JNI.ERRNO && JNI.value(polled) == 4) continue;
            tag(polled,JNI.SUCCESS,"poll read");
            long result = JNI.readOwnedPtyOnce(owner,data,0,data.length);
            if (JNI.tag(result) == JNI.ERRNO && (JNI.value(result)==11 || JNI.value(result)==4)) continue;
            tag(result,JNI.SUCCESS,"read bytes");
            int count=JNI.value(result); check(count>=0 && count<=data.length,"read count");
            text.append(new String(data,0,count,StandardCharsets.UTF_8));
            check(text.length()<65536,"bounded output");
            if (text.indexOf(marker)>=0) return;
        }
        throw new AssertionError("missing PTY marker " + marker + ": " + text);
    }
    static final class Waiter implements AutoCloseable {
        final long owner;
        final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t=new Thread(r,"owned-wait"); t.setDaemon(true); return t;
        });
        Future<Long> future;
        Waiter(long owner) { this.owner=owner; }
        long await() throws Exception {
            if (future == null) future=executor.submit(() -> JNI.waitForOwnedProcess(owner));
            // A timeout never creates another waiter; cleanup awaits this same Future.
            return future.get(10,TimeUnit.SECONDS);
        }
        public void close() { executor.shutdownNow(); }
    }
    static void pty(String cwd, boolean stop) throws Exception {
        long owner=JNI.reserveOwnedProcess(); boolean spawned=false, waited=false;
        Waiter waiter=new Waiter(owner);
        try {
            // Only bash builtins, no grandchildren; read has its own finite deadline.
            String script="printf 'OWNED_READY\\n'; IFS= read -r -t 5 line || exit 33; printf 'OWNED_GOT:%s\\n' \"$line\"; exit 7";
            int pid=JNI.createOwnedSubprocess(owner,"/bin/bash",cwd,
                    new String[]{"/bin/bash","--noprofile","--norc","-c",script},
                    new String[]{"PATH=/usr/bin:/bin","TERM=xterm-256color","LC_ALL=C.UTF-8"},24,80);
            spawned=true; check(pid>1,"owned child created");
            awaitText(owner,"OWNED_READY");
            check(JNI.resizeOwnedPty(owner,30,100),"running PTY resize");
            tag(JNI.pollOwnedPtyOnce(owner,JNI.WRITE,100),JNI.SUCCESS,"poll write");
            byte[] payload="contract-marker\n".getBytes(StandardCharsets.UTF_8);
            if (stop) {
                check(JNI.requestOwnedStop(owner,JNI.ANCHORED_SESSION_GROUP)==0,"anchored stop requested");
            } else {
            long write=JNI.writeOwnedPtyOnce(owner,payload,0,payload.length);
            tag(write,JNI.SUCCESS,"write marker"); check(JNI.value(write)==payload.length,"complete short write");
            awaitText(owner,"OWNED_GOT:contract-marker");
            }
            long result=waiter.await(); waited=true;
            tag(result,JNI.EXITED,"clean owned reap"); check(JNI.value(result)==(stop ? -9 : 7),"actual exit status");
            check(!JNI.resizeOwnedPty(owner,24,80),"reaped resize rejected");
            tag(JNI.writeOwnedPtyOnce(owner,payload,0,1),JNI.CANCELLED,"post-reap write cancelled");
        } finally {
            try {
                if (spawned && !waited) {
                    JNI.requestOwnedStop(owner,JNI.ANCHORED_SESSION_GROUP);
                    long cleanup=waiter.await();
                    check(JNI.tag(cleanup)==JNI.EXITED,"cleanup reap incomplete: "+cleanup);
                }
            } catch (Exception | AssertionError cleanupFailure) {
                System.err.println("OWNED_CHILD_CLEANUP_UNCONFIRMED");
                throw cleanupFailure;
            } finally {
                try { release(owner); } finally { waiter.close(); }
            }
        }
    }
    public static void main(String[] args) throws Exception {
        identity(args[0],args[1]); owners(); failedStartup(args[2]); pty(args[2],false); pty(args[2],true);
        System.out.println("OWNED_JNI_HOST_PASS checks="+checks+" jni_sha256="+args[1]);
    }
}
'''


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def no_links(path: Path) -> Path:
    path = Path(os.path.abspath(path))
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError('symlinked path is not admitted: ' + str(path))
    return path


def archive_members(data: bytes) -> dict[str, bytes]:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        if len(entries) > 10000 or sum(x.file_size for x in entries) > 100_000_000:
            raise ValueError('oversized engine archive')
        names = [x.filename for x in entries]
        if len(names) != len(set(names)):
            raise ValueError('duplicate archive entry')
        for entry in entries:
            if entry.flag_bits & 1 or entry.filename.startswith('/') or '..' in Path(entry.filename).parts or '\\' in entry.filename:
                raise ValueError('unsafe engine archive entry')
        return {x.filename: archive.read(x) for x in entries if not x.is_dir()}


def validate_output(output: Path, protected: list[Path]) -> Path:
    output = no_links(output)
    if output.exists():
        raise ValueError('output must not exist')
    for path in protected:
        if output == path or output in path.parents or path in output.parents:
            raise ValueError('output must be disjoint from all input roots/files')
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--engine-aar', type=Path)
    group.add_argument('--engine-jar', type=Path)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cc', default='cc')
    args = parser.parse_args()
    if platform.system() != 'Linux':
        parser.error('Linux required')
    root = no_links(args.root).resolve(strict=True)
    java_home = no_links(args.java_home).resolve(strict=True)
    engine = no_links(args.engine_aar or args.engine_jar).resolve(strict=True)
    source = no_links(root / 'third_party/termux/native/termux.c')
    output = validate_output(args.output,[root, java_home, engine])
    compiler = shutil.which(args.cc)
    if not compiler:
        parser.error('C compiler unavailable')
    compiler = str(Path(compiler).resolve(strict=True))
    engine_bytes = engine.read_bytes()
    jar = archive_members(engine_bytes).get('classes.jar') if args.engine_aar else engine_bytes
    if jar is None:
        parser.error('engine AAR missing classes.jar')
    classes = archive_members(jar)
    if JNI_CLASS not in classes:
        parser.error('engine lacks actual JNI.class')
    if any(name == 'com/termux/terminal/OwnedJniHostContract.class' or
           name.startswith('com/termux/terminal/OwnedJniHostContract$') for name in classes):
        parser.error('engine contains fixture class')
    output.mkdir(parents=True, exist_ok=False)
    for name in ('home','tmp','fixture','classes','library','empty-sourcepath','workspace'):
        (output/name).mkdir()
    environment = {'PATH':'/usr/bin:/bin','HOME':str(output/'home'),'TMPDIR':str(output/'tmp'),
                   'LC_ALL':'C.UTF-8','LANG':'C.UTF-8','TZ':'UTC'}
    before = {'source':digest(source.read_bytes()),'engine':digest(engine_bytes)}
    receipt = {'status':'FAILED','scope':'Linux actual JNI registration and bounded owned PTY; not Android/APK/runtime certification',
               'deviceValidated':False,'apkAdmitted':False,'childCleanupStatus':'NOT_PROVEN',
               'sourceBefore':before,'commands':[],
               'harnessSha256':digest(Path(__file__).read_bytes()),'fixtureSha256':digest(JAVA.encode()),
               'engineJarSha256':digest(jar),'jniClassSha256':digest(classes[JNI_CLASS]),'started':time.time()}
    def run(name: str, command: list[str], timeout: int = 90) -> str:
        started=time.time()
        try:
            result=subprocess.run(command,cwd=output,env=environment,capture_output=True,timeout=timeout)
        except subprocess.TimeoutExpired as error:
            (output/(name+'.stdout.log')).write_bytes(error.stdout or b'')
            (output/(name+'.stderr.log')).write_bytes(error.stderr or b'')
            receipt['commands'].append({'name':name,'argv':command,'timedOut':True,'started':started})
            if name == 'java':
                receipt['childCleanupStatus'] = 'UNCONFIRMED_AFTER_JVM_TIMEOUT'
            raise
        (output/(name+'.stdout.log')).write_bytes(result.stdout)
        (output/(name+'.stderr.log')).write_bytes(result.stderr)
        receipt['commands'].append({'name':name,'argv':command,'returnCode':result.returncode,'started':started})
        if name == 'java' and b'OWNED_CHILD_CLEANUP_UNCONFIRMED' in result.stderr:
            receipt['childCleanupStatus'] = 'UNCONFIRMED_NATIVE_REAPER'
        if result.returncode:
            raise RuntimeError(name+' failed; see retained logs')
        if name=='java' and result.stderr:
            raise RuntimeError('JNI runtime emitted diagnostics; review retained stderr')
        return result.stdout.decode('utf-8')
    try:
        if not run('javac-version',[str(java_home/'bin/javac'),'-version']).strip().startswith('javac 17.'):
            raise ValueError('JDK17 required')
        receipt['toolHashes']={p:digest(Path(p).read_bytes()) for p in [compiler,str(java_home/'bin/java'),str(java_home/'bin/javac')]}
        run('cc-version',[compiler,'--version'])
        engine_copy=output/'engine.jar';engine_copy.write_bytes(jar)
        fixture=output/'fixture/OwnedJniHostContract.java';fixture.write_text(JAVA)
        run('javac',[str(java_home/'bin/javac'),'-encoding','UTF-8','-proc:none','-implicit:none',
                    '-sourcepath',str(output/'empty-sourcepath'),'-cp',str(engine_copy),'-d',str(output/'classes'),str(fixture)])
        compiled = {p.relative_to(output/'classes').as_posix() for p in (output/'classes').rglob('*') if p.is_file()}
        expected_fixture = {'com/termux/terminal/OwnedJniHostContract.class',
                            'com/termux/terminal/OwnedJniHostContract$Waiter.class'}
        if compiled != expected_fixture:
            raise ValueError('fixture compilation emitted unexpected class files')
        receipt['fixtureClasses'] = {name:digest((output/'classes'/name).read_bytes()) for name in sorted(compiled)}
        library=output/'library/libtermux.so'
        run('cc',[compiler,'-shared','-fPIC','-O2','-Wall','-Wextra','-Werror','-pthread',
                  '-I'+str(java_home/'include'),'-I'+str(java_home/'include/linux'),str(source),'-o',str(library)])
        result=run('java',[str(java_home/'bin/java'),'-Xcheck:jni','-Djava.library.path='+str(output/'library'),
                          '-cp',str(output/'classes')+os.pathsep+str(engine_copy),'com.termux.terminal.OwnedJniHostContract',
                          str(engine_copy),receipt['jniClassSha256'],str(output/'workspace')])
        match=re.fullmatch(r'OWNED_JNI_HOST_PASS checks=(\d+) jni_sha256=([a-f0-9]{64})\s*',result)
        if not match or match[2] != receipt['jniClassSha256']:
            raise ValueError('missing exact explicit host success or unexpected runtime diagnostics')
        receipt.update(status='PASS',checks=int(match[1]),librarySha256=digest(library.read_bytes()),
                       childCleanupStatus='OWNED_TEST_CHILDREN_REAPED')
    finally:
        receipt['sourceAfter']={'source':digest(source.read_bytes()),'engine':digest(engine.read_bytes())}
        if receipt['sourceAfter']!=before:
            receipt['status']='FAILED_INPUT_CHANGED'
        receipt['finished']=time.time()
        (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    if receipt['status']!='PASS':
        raise RuntimeError(receipt['status'])
    print(json.dumps({'status':'PASS','checks':receipt['checks'],'receipt':str(output/'receipt.json')}))


if __name__=='__main__':
    main()
