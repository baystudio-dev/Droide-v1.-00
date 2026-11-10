#!/usr/bin/env python3
"""Supplementary Linux JNI contract checks for the 15 descriptor/anchored APIs."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
C_SOURCE = ROOT / 'app/src/main/cpp/droide_files.c'
KOTLIN = ROOT / 'app/src/main/java/com/baystudio/droide/core/ProjectFilePublication.kt'
API = ['openDirectoryRoot', 'duplicateDescriptor', 'closeDescriptor', 'forceDirectory',
       'openOrdinaryRelative', 'openDirectoryRelative', 'createOrdinaryRelative',
       'createDirectoryRelative', 'kindRelative', 'readDescriptor', 'writeDescriptor',
       'forceFileDescriptor', 'changedTimeDescriptor', 'moveNoReplaceAnchored',
       'moveNoReplaceAnchoredDurable']
JAVA = r'''package com.baystudio.droide.core;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.baystudio.droide.core.NativeFilePublication.*;
public final class FilesystemContractHarness {
    static int checks;
    static final Set<Integer> owned = new LinkedHashSet<>();
    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    static byte[] b(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static int own(int fd) { check(fd >= 0, "descriptor admission: " + fd); owned.add(fd); return fd; }
    static void release(int fd) { check(owned.remove(fd), "descriptor owned"); check(closeDescriptor(fd)==0,"close"); }
    static int created(int root, String path) {
        long receipt = createOrdinaryRelative(root,b(path));
        check(receipt >= 0,"create packed descriptors: " + receipt);
        int parent=own((int)(receipt >>> 32)); int fd=own((int)receipt);
        check(forceDirectory(parent)==0,"created parent sync"); release(parent); return fd;
    }
    static void bytes(Path path, byte[] expected) throws Exception {
        check(Arrays.equals(Files.readAllBytes(path),expected),"preserved bytes: "+path);
    }
    static void badPaths(int root, Path path) throws Exception {
        Files.createDirectory(path.resolve("a")); // Existing ancestor must not mask malformed paths with ENOENT.
        for (byte[] bad : new byte[][] {null,new byte[0],new byte[]{'a',0,'b'},b("../escape"),
                b(path.resolveSibling("absolute-rejected").toString()),b("./x"),b("a//x"),b("a/../x"),b("a/"),b("x".repeat(4096))}) {
            check(openOrdinaryRelative(root,bad)<0,"invalid ordinary path");
            check(openDirectoryRelative(root,bad)<0,"invalid directory path");
            check(createOrdinaryRelative(root,bad)<0,"invalid create path");
            long receipt=createDirectoryRelative(root,bad);
            check((receipt >>> 32)==0 && (receipt & 0xffffffffL)!=0,"invalid mkdir receipt");
            check(kindRelative(root,bad)<0,"invalid kind path");
            check(moveNoReplaceAnchored(root,bad,root,b("untouched"))!=0,"invalid move source");
            check(moveNoReplaceAnchored(root,b("keep"),root,bad)!=0,"invalid move target");
            receipt=moveNoReplaceAnchoredDurable(root,bad,root,b("untouched"));
            check((receipt >>> 32)==0 && (receipt & 0xffffffffL)!=0,"invalid durable source");
            receipt=moveNoReplaceAnchoredDurable(root,b("keep"),root,bad);
            check((receipt >>> 32)==0 && (receipt & 0xffffffffL)!=0,"invalid durable target");
        }
        check(openDirectoryRoot(null)==-22,"null root");
        check(openDirectoryRoot(new byte[0])==-22,"empty root");
        check(openDirectoryRoot(new byte[]{'x',0})==-22,"NUL root");
        check(openDirectoryRoot(b("x".repeat(4096)))<0,"oversize root");
        check(openDirectoryRoot(b(path.resolve("keep").toString()))<0,"file not root");
        bytes(path.resolve("keep"),b("original"));
        check(!Files.exists(path.resolve("untouched")),"invalid moves did not publish");
    }
    static void descriptors(int root, Path path) throws Exception {
        check(duplicateDescriptor(-1)==-9,"dup invalid fd");
        check(closeDescriptor(-1)==9,"close invalid fd");
        check(forceDirectory(-1)==9,"sync invalid directory fd");
        check(forceFileDescriptor(-1,-1)==9,"sync invalid file fd");
        check(openOrdinaryRelative(-1,b("x"))<0,"invalid root fd");
        long[] invalid=changedTimeDescriptor(-1);
        check(invalid.length==3 && invalid[0]==9 && invalid[1]==0 && invalid[2]==0,"invalid ctime");
        int copy=own(duplicateDescriptor(root)); check(forceDirectory(copy)==0,"dup root usable");
        release(copy); check(forceDirectory(root)==0,"original root retained");
        int fd=created(root,"data ü.bin");
        check(createOrdinaryRelative(root,b("data ü.bin"))==-17,"exclusive collision");
        byte[] content=b("payload");
        check(writeDescriptor(fd,content,0,content.length)==content.length,"write");
        check(writeDescriptor(fd,content,content.length,0)==0,"zero write");
        for(int mode:new int[]{0600,0644,0755}) {
            check(forceFileDescriptor(fd,mode)==0,"file sync mode");
            int actual=((Number)Files.getAttribute(path.resolve("data ü.bin"),"unix:mode")).intValue();
            check((actual & 0777)==mode,"permission bits");
        }
        check(forceFileDescriptor(fd,-1)==0,"preserve mode sync");
        check(forceFileDescriptor(fd,0777)==22,"unsupported mode");
        check(forceDirectory(fd)==20,"file not directory");
        check(forceFileDescriptor(root,-1)==22,"directory not file");
        long[] changed=changedTimeDescriptor(fd);
        check(changed.length==3 && changed[0]==0 && changed[1]>0 && changed[2]>=0 && changed[2]<1000000000L,"ctime shape");
        release(fd);
        fd=own(openOrdinaryRelative(root,b("data ü.bin")));
        byte[] target=new byte[12]; Arrays.fill(target,(byte)99);
        check(readDescriptor(fd,target,2,7)==7,"read slice");
        check(Arrays.equals(Arrays.copyOfRange(target,2,9),content),"read bytes");
        check(target[0]==99 && target[1]==99 && target[9]==99,"outside read slice preserved");
        check(readDescriptor(fd,target,0,1)==-1,"EOF distinct from error");
        check(readDescriptor(fd,target,target.length,0)==0,"zero read at end");
        check(writeDescriptor(fd,content,0,1)==-10,"read-only write EBADF encoding");
        for(int[] range:new int[][]{{-1,1},{0,-1},{13,0},{11,2},{Integer.MAX_VALUE,1},{1,Integer.MAX_VALUE}}) {
            check(readDescriptor(fd,target,range[0],range[1])==-23,"invalid read range");
            check(writeDescriptor(fd,target,range[0],range[1])==-23,"invalid write range");
        }
        check(readDescriptor(fd,null,0,1)==-23,"null read buffer");
        check(writeDescriptor(fd,null,0,1)==-23,"null write buffer");
        check(readDescriptor(-1,target,0,1)==-10,"invalid fd read error encoding");
        check(writeDescriptor(-1,target,0,1)==-10,"invalid fd write error encoding");
        release(fd); bytes(path.resolve("data ü.bin"),content);
        fd=created(root,"large.bin"); byte[] large=new byte[70000]; Arrays.fill(large,(byte)42);
        check(writeDescriptor(fd,large,0,large.length)==65536,"bounded native write chunk"); release(fd);
        fd=own(openOrdinaryRelative(root,b("large.bin")));
        check(readDescriptor(fd,large,0,large.length)==65536,"bounded native read chunk");
        check(readDescriptor(fd,large,0,1)==-1,"large EOF"); release(fd);
        check(Files.size(path.resolve("large.bin"))==65536,"bounded write file size");
    }
    static void directories(int root, Path path, Path outside) throws Exception {
        long receipt=createDirectoryRelative(root,b("nested"));
        check(receipt==(1L<<32),"mkdir committed and synced");
        check(createDirectoryRelative(root,b("nested"))==17,"mkdir collision uncommitted");
        int nested=own(openDirectoryRelative(root,b("nested"))); release(nested);
        check(kindRelative(root,b("nested"))==2,"directory kind");
        check(kindRelative(root,b("keep"))==1,"ordinary kind");
        check(kindRelative(root,b("absent"))==-2,"absent kind");
        check(openOrdinaryRelative(root,b("nested"))<0,"directory not ordinary");
        Files.writeString(outside.resolve("sentinel"),"outside original");
        Files.createSymbolicLink(path.resolve("linkdir"),outside);
        Files.createSymbolicLink(path.resolve("linkfile"),outside.resolve("sentinel"));
        check(kindRelative(root,b("linkfile"))==3,"symlink kind no follow");
        check(openDirectoryRoot(b(path.resolve("linkdir").toString()))<0,"symlink root rejected");
        check(openDirectoryRelative(root,b("linkdir"))<0,"symlink directory rejected");
        check(openOrdinaryRelative(root,b("linkfile"))<0,"symlink file rejected");
        check(createOrdinaryRelative(root,b("linkfile"))<0,"symlink create collision");
        check(openOrdinaryRelative(root,b("linkdir/sentinel"))<0,"symlink ancestor read rejected");
        check(createOrdinaryRelative(root,b("linkdir/new"))<0,"symlink ancestor create rejected");
        long denied=createDirectoryRelative(root,b("linkdir/newdir"));
        check((denied >>> 32)==0 && (denied & 0xffffffffL)!=0,"symlink ancestor mkdir rejected");
        check(moveNoReplaceAnchored(root,b("keep"),root,b("linkdir/replaced"))!=0,"symlink ancestor move rejected");
        bytes(outside.resolve("sentinel"),b("outside original"));
        check(!Files.exists(outside.resolve("new")) && !Files.exists(outside.resolve("newdir")) && !Files.exists(outside.resolve("replaced")),"outside unchanged");
    }
    static void moves(int root, Path path, String variant) throws Exception {
        Files.writeString(path.resolve("source"),"source bytes"); Files.writeString(path.resolve("occupied"),"target bytes");
        int collision=moveNoReplaceAnchored(root,b("source"),root,b("occupied"));
        check(collision==(variant.equals("rename-error")?38:17),"anchored collision/error");
        bytes(path.resolve("source"),b("source bytes"));bytes(path.resolve("occupied"),b("target bytes"));
        int result=moveNoReplaceAnchored(root,b("source"),root,b("moved"));
        check(result==(variant.equals("rename-error")?38:0),"anchored move");
        bytes(path.resolve(variant.equals("rename-error")?"source":"moved"),b("source bytes"));
        Files.writeString(path.resolve("durable"),"durable bytes");
        long receipt=moveNoReplaceAnchoredDurable(root,b("durable"),root,b("occupied"));
        check(receipt==(variant.equals("rename-error")?38:17),"durable collision not committed");
        bytes(path.resolve("durable"),b("durable bytes")); bytes(path.resolve("occupied"),b("target bytes"));
        receipt=moveNoReplaceAnchoredDurable(root,b("durable"),root,b("nested/durable"));
        long expected=variant.equals("rename-error")?38:(1L<<32)|(variant.equals("sync-error")?5L:0L);
        check(receipt==expected,"durable commit bit/error: "+receipt);
        bytes(path.resolve(variant.equals("rename-error")?"durable":"nested/durable"),b("durable bytes"));
        check(Files.exists(path.resolve("durable"))==variant.equals("rename-error"),"durable source ownership");
        check(Files.exists(path.resolve("nested/durable"))!=variant.equals("rename-error"),"durable target ownership");
    }
    public static void main(String[] args) throws Exception {
        Path path=Files.createDirectory(Paths.get(args[0]).resolve("workspace"));
        Path outside=Files.createDirectory(Paths.get(args[0]).resolve("outside"));
        Files.writeString(path.resolve("keep"),"original");
        try {
            int root=own(openDirectoryRoot(b(path.toString())));
            badPaths(root,path); descriptors(root,path); directories(root,path,outside); moves(root,path,args[1]);
            bytes(path.resolve("keep"),b("original")); release(root);
            check(owned.isEmpty(),"all admitted descriptors closed");
            System.out.println("{\"status\":\"PASS\",\"assertions\":"+checks+"}");
        } finally { for(int fd:owned) closeDescriptor(fd); owned.clear(); }
    }
}
'''


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def declarations(text):
    block = text.split('internal object NativeFilePublication {', 1)[1].split('\n}', 1)[0]
    methods = re.findall(r'@JvmStatic external fun (\w+)\(([^\n]*)\): (\w+)', block)
    if [name for name, _, _ in methods] != ['renameNoReplace', 'moveNoReplace', *API]:
        raise ValueError('Production JNI inventory changed; review this harness')
    types = {'ByteArray': 'byte[]', 'Int': 'int', 'Long': 'long', 'LongArray': 'long[]'}
    lines = []
    for name, params, result in methods:
        args = []
        for entry in params.split(','):
            variable, kind = entry.strip().split(': ')
            args.append(types[kind] + ' ' + variable)
        lines.append('public static native ' + types[result] + ' ' + name + '(' + ', '.join(args) + ');')
    return 'package com.baystudio.droide.core; public final class NativeFilePublication {\n' + \
        'static { System.load(System.getProperty("droide.files.library")); }\n' + '\n'.join(lines) + '\n}\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--cc', default='cc')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if platform.system() != 'Linux':
        parser.error('Linux host required')
    def no_links(path):
        value = Path(os.path.abspath(path))
        if any(part.is_symlink() for part in (value, *value.parents)):
            parser.error('symlink path component is not admitted: ' + str(value))
        return value
    java_home = no_links(args.java_home).resolve(strict=True)
    compiler = shutil.which(args.cc)
    if not compiler:
        parser.error('C compiler missing')
    compiler = str(Path(compiler).resolve())
    out = no_links(args.output)
    for protected in (ROOT.resolve(), java_home):
        if out == protected or out in protected.parents or protected in out.parents:
            parser.error('output must be disjoint from source and JDK roots')
    # Existing directories, including symlinks, are never adopted or overwritten.
    if out.exists() or out.is_symlink():
        parser.error('output must not exist')
    out.mkdir(parents=True, exist_ok=False)
    home = out / 'home'; home.mkdir()
    temporary = out / 'tmp'; temporary.mkdir()
    environment = {'PATH': '/usr/bin:/bin', 'HOME': str(home), 'TMPDIR': str(temporary),
                   'LC_ALL': 'C.UTF-8', 'LANG': 'C.UTF-8', 'TZ': 'UTC', 'SOURCE_DATE_EPOCH': '0'}
    before = {str(p.relative_to(ROOT)): digest(p) for p in (C_SOURCE, KOTLIN)}
    receipt = {'scope': 'Supplementary Linux host JNI; not Android/APK/power-loss/exhaustive races',
               'source_before': before, 'status': 'FAILED', 'covered_new_api': API,
               'commands': [], 'variants': [], 'started': time.time()}
    def run(name, command):
        receipt['commands'].append(command)
        result = subprocess.run(command, env=environment, cwd=out, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
        (out / (name + '.log')).write_text(result.stdout)
        if result.returncode:
            raise RuntimeError(f'{name} failed: exit {result.returncode}; inspect log')
        return result.stdout
    try:
        version = run('javac-version', [str(java_home / 'bin/javac'), '-version']).strip()
        if not version.startswith('javac 17.'):
            raise ValueError('JDK17 required')
        receipt['tools'] = {compiler: digest(Path(compiler)),
                            str(java_home / 'bin/javac'): digest(java_home / 'bin/javac'),
                            str(java_home / 'bin/java'): digest(java_home / 'bin/java')}
        run('cc-version', [compiler, '--version'])
        source = out / 'NativeFilePublication.java'
        source.write_text(declarations(KOTLIN.read_text()))
        harness = out / 'FilesystemContractHarness.java'; harness.write_text(JAVA)
        run('javac', [str(java_home / 'bin/javac'), '-encoding', 'UTF-8', '-d', str(out), str(source), str(harness)])
        for name, flags in [('production', []), ('sync-error', ['-DDROIDE_FILES_TEST_SYNC_ERRNO=5']),
                            ('rename-error', ['-DDROIDE_FILES_TEST_ERRNO=38'])]:
            directory = out / name; directory.mkdir()
            library = directory / 'libdroide_files.so'
            run('compile-' + name, [compiler, '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-Werror',
                '-I' + str(java_home / 'include'), '-I' + str(java_home / 'include/linux'),
                *flags, str(C_SOURCE), '-o', str(library)])
            result = run('run-' + name, [str(java_home / 'bin/java'), '-Xcheck:jni',
                '-Ddroide.files.library=' + str(library), '-cp', str(out),
                'com.baystudio.droide.core.FilesystemContractHarness', str(directory), name])
            parsed = json.loads(result.strip())
            if parsed.get('status') != 'PASS':
                raise ValueError('missing explicit harness success')
            receipt['variants'].append(dict(name=name, library_sha256=digest(library), **parsed))
        receipt['status'] = 'PASS'
    finally:
        receipt['source_after'] = {str(p.relative_to(ROOT)): digest(p) for p in (C_SOURCE, KOTLIN)}
        if receipt['source_after'] != before:
            receipt['status'] = 'FAILED_SOURCE_CHANGED'
        receipt['finished'] = time.time()
        (out / 'receipt.json').write_text(json.dumps(receipt, indent=2))
    if receipt['status'] != 'PASS':
        raise RuntimeError(receipt['status'])
    print(json.dumps({'status': 'PASS', 'receipt': str(out / 'receipt.json')}))


if __name__ == '__main__':
    main()
