#!/usr/bin/python3.12
"""Fixed CMake-only stdlib owner. Ordinary owned groups, not a security sandbox.

The unreaped direct session leader anchors every destructive group signal. A
receipt is source-correlated evidence, not protection from a hostile same UID.
No numeric destructive signal is sent after the leader has been reaped.
"""
import errno
import hashlib
import json
import os
import resource
import selectors
import signal
import stat
import subprocess
import sys
import time

MAX_RECORD = 16384
POLICIES = {
    'editor': dict(wall=1800, cpu=(120, 130), fsize=67108864, nofile=256,
                   stdout=67108864, stderr=1048576),
    'health': dict(wall=45, cpu=(25, 30), fsize=16777216, nofile=128,
                   stdout=4194304, stderr=65536),
}

def _read(path, limit):
    with open(path, 'rb', buffering=0) as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError('bounded read overflow')
    return data

def _stat(pid):
    raw = _read('/proc/%d/stat' % pid, 4096)
    close = raw.rfind(b')')
    if close < 2 or not raw[:raw.find(b' ')].isdigit():
        raise ValueError('invalid proc stat')
    fields = raw[close + 2:].split()
    if len(fields) < 20:
        raise ValueError('short proc stat')
    return dict(pid=int(raw[:raw.find(b' ')]), state=fields[0].decode('ascii'),
                ppid=int(fields[1]), pgid=int(fields[2]), sid=int(fields[3]),
                start=int(fields[19]))

def _namespace():
    return os.readlink('/proc/self/ns/pid')

def _boot_id():
    return _read('/proc/sys/kernel/random/boot_id', 128).decode('ascii').strip()

def _limits(policy):
    p = POLICIES[policy]
    resource.setrlimit(resource.RLIMIT_AS, (805306368, 805306368))
    resource.setrlimit(resource.RLIMIT_CPU, p['cpu'])
    resource.setrlimit(resource.RLIMIT_FSIZE, (p['fsize'], p['fsize']))
    resource.setrlimit(resource.RLIMIT_NOFILE, (p['nofile'], p['nofile']))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    os.umask(0o077)

class OwnedChild:
    def __init__(self, process, policy):
        self.process = process
        self.pid = process.pid
        birth = _stat(self.pid)
        if birth['pgid'] != self.pid or birth['sid'] != self.pid:
            raise RuntimeError('child is not the owned session leader')
        self.start_ticks = birth['start']
        self.namespace = _namespace()
        self.boot_id = _boot_id()
        self.policy = policy
        self.forced = False
        self.reaped = False
        self.held = False
        self.receipt = None

class OwnedLaunchError(RuntimeError):
    """A child was created but complete ownership evidence could not be obtained.

    Callers must retain private residue and report failure. This receipt never
    authorizes build release or private cleanup.
    """
    def __init__(self, receipt, cause):
        super().__init__('owned child birth observation failed: ' + str(cause)[:512])
        self.receipt = receipt


def _failed_launch_cleanup(process, cause):
    receipt = dict(status='UNKNOWN', directChildReaped=False, groupEmpty=False,
                   forcedTermination=False, leaderPid=process.pid, leaderStartTicks=0,
                   namespace='', bootId='', preReapScans=0, minimumSeparationMs=0,
                   postReapGroupObservation='UNKNOWN', exitCode=None)
    try:
        # waitid validates direct-child custody without reaping. Birth/group
        # observations failed, so ONLY this direct child may be killed; no
        # numeric group signal is authorized and no group release is inferred.
        os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
        os.kill(process.pid, signal.SIGKILL)
        receipt['forcedTermination'] = True
        deadline = time.monotonic() + 1
        while time.monotonic() < deadline:
            pid, status = os.waitpid(process.pid, os.WNOHANG)
            if pid == process.pid:
                process.returncode = os.waitstatus_to_exitcode(status)
                receipt['directChildReaped'] = True
                receipt['exitCode'] = process.returncode
                break
            time.sleep(.01)
    except (OSError, ChildProcessError):
        pass
    for stream in (process.stdin, process.stdout, process.stderr):
        try: stream.close()
        except OSError: pass
    raise OwnedLaunchError(receipt, cause) from cause


def launch_owned(argv, cwd, env, policy='health'):
    """Single-threaded caller only; never poll/wait/reap process outside teardown."""
    if policy not in POLICIES or not argv or not os.path.isabs(argv[0]):
        raise ValueError('unreviewed child command/policy')
    # Establish required observation facilities before starting any child.
    if signal.getsignal(signal.SIGCHLD) == signal.SIG_IGN:
        raise RuntimeError('SIGCHLD auto-reaping is incompatible with owned groups')
    _namespace(); _boot_id()
    if not hasattr(os, 'WNOWAIT') or not hasattr(os, 'waitid'):
        raise RuntimeError('WNOWAIT custody unavailable')
    process = subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               start_new_session=True, close_fds=True,
                               preexec_fn=lambda: _limits(policy))
    # Popen does not reap here. Even an already terminal child retains its birth.
    try:
        return OwnedChild(process, policy)
    except (OSError, ValueError, RuntimeError) as cause:
        _failed_launch_cleanup(process, cause)

def _held_terminal(child):
    if child.reaped:
        return False
    current = _stat(child.pid)
    if (current['start'] != child.start_ticks or current['pgid'] != child.pid
            or current['sid'] != child.pid or _namespace() != child.namespace
            or _boot_id() != child.boot_id):
        raise RuntimeError('owned leader identity changed')
    result = os.waitid(os.P_PID, child.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
    child.held = result is not None and result.si_pid == child.pid
    return child.held

def _scan_no_other(child):
    """Complete bounded procfs scan. Zombies are members; vanished entries are OK."""
    start = time.monotonic()
    count = total = 0
    leader_seen = False
    with os.scandir('/proc') as entries:
        for entry in entries:
            if time.monotonic() - start > .5:
                raise RuntimeError('proc scan deadline')
            if not entry.name.isascii() or not entry.name.isdigit():
                continue
            count += 1
            if count > 4096:
                raise RuntimeError('proc scan entry bound')
            try:
                raw = _read('/proc/' + entry.name + '/stat', 4096)
                total += len(raw)
                if total > 16777216:
                    raise RuntimeError('proc scan byte bound')
                close = raw.rfind(b')')
                fields = raw[close + 2:].split()
                if close < 2 or len(fields) < 20 or raw[:raw.find(b' ')] != entry.name.encode('ascii'):
                    raise RuntimeError('ambiguous proc stat')
                pid, pgid, birth = int(entry.name), int(fields[2]), int(fields[19])
            except FileNotFoundError:
                continue
            if pgid == child.pid:
                if pid != child.pid or birth != child.start_ticks or not child.held:
                    return False
                if int(fields[3]) != child.pid or fields[0] not in (b'Z', b'X'):
                    raise RuntimeError('held leader not terminal')
                leader_seen = True
    if time.monotonic() - start > .5 or not leader_seen:
        raise RuntimeError('incomplete proc scan')
    return True

def _signal_owned(child, sig):
    if child.reaped:
        raise RuntimeError('destructive signal after reap prohibited')
    current = _stat(child.pid)
    if current['start'] != child.start_ticks or current['pgid'] != child.pid or current['sid'] != child.pid:
        raise RuntimeError('signal authority lost')
    os.killpg(child.pid, sig)
    child.forced = True

def teardown_owned(child, deadline, request_stop=True):
    """At most eight seconds. UNKNOWN keeps the caller's durable lease quarantined."""
    if child.receipt is not None:
        return child.receipt
    deadline = min(deadline, time.monotonic() + 8)
    result = dict(status='UNKNOWN', directChildReaped=False, groupEmpty=False,
                  forcedTermination=child.forced, leaderPid=child.pid,
                  leaderStartTicks=child.start_ticks, namespace=child.namespace,
                  bootId=child.boot_id, preReapScans=0, minimumSeparationMs=0,
                  postReapGroupObservation='UNKNOWN', exitCode=None)
    first_empty = None
    term_at = None
    killed = False
    natural_grace = min(deadline - 2, time.monotonic() + 1)
    try:
        if request_stop and not _held_terminal(child):
            _signal_owned(child, signal.SIGTERM)
            term_at = time.monotonic()
        while time.monotonic() < deadline:
            held = _held_terminal(child)
            if held:
                try:
                    empty = _scan_no_other(child)
                except (OSError, ValueError, RuntimeError):
                    empty = False
                if empty:
                    now = time.monotonic()
                    if first_empty is None:
                        first_empty = now
                        result['preReapScans'] = 1
                    elif now - first_empty >= .05:
                        result['preReapScans'] = 2
                        result['minimumSeparationMs'] = int((now - first_empty) * 1000)
                        # No more destructive signals are possible beyond this point.
                        _, status = os.waitpid(child.pid, 0)
                        child.reaped = True
                        child.process.returncode = os.waitstatus_to_exitcode(status)
                        result['directChildReaped'] = True
                        result['exitCode'] = child.process.returncode
                        try:
                            os.killpg(child.pid, 0)
                        except ProcessLookupError:
                            result['groupEmpty'] = True
                            result['postReapGroupObservation'] = 'ESRCH'
                            result['status'] = 'COMPLETE'
                        break
                else:
                    first_empty = None
                    result['preReapScans'] = 0
                    # A terminal leader can leave live ordinary group descendants.
                    if term_at is None:
                        _signal_owned(child, signal.SIGTERM)
                        term_at = time.monotonic()
            elif not request_stop:
                # Caller requested a natural exit; waiting is finite and never successful
                # merely because shutdown was sent. A later forced cleanup stays forced.
                pass
            now = time.monotonic()
            if not held and term_at is None and now >= natural_grace:
                _signal_owned(child, signal.SIGTERM)
                term_at = now
            if term_at is not None and not killed and now - term_at >= 1:
                _signal_owned(child, signal.SIGKILL)
                killed = True
            time.sleep(min(.025, max(0, deadline - time.monotonic())))
    except (OSError, ValueError, RuntimeError, ChildProcessError) as error:
        result['reason'] = type(error).__name__ + ': ' + str(error)[:512]
    result['forcedTermination'] = child.forced
    # The destructive phase is now irrevocably over. Reap a terminal direct child
    # even if scans were uncertain, but never turn that fact into group release.
    if not child.reaped:
        try:
            terminal = os.waitid(os.P_PID, child.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
            if terminal is not None and terminal.si_pid == child.pid:
                pid, status = os.waitpid(child.pid, os.WNOHANG)
                if pid == child.pid:
                    child.reaped = True
                    child.process.returncode = os.waitstatus_to_exitcode(status)
                    result['directChildReaped'] = True
                    result['exitCode'] = child.process.returncode
        except (OSError, ChildProcessError):
            pass
    # UNKNOWN never performs a guessed release. The durable tombstone survives.
    child.receipt = result
    return result

def private_identity(root):
    info = os.stat(root, follow_symlinks=False)
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
        raise ValueError('unsafe private root')
    return dict(dev=info.st_dev, ino=info.st_ino, uid=info.st_uid, mode=stat.S_IMODE(info.st_mode))

def private_cleanup(root, identity, deadline):
    """No-follow fd-relative bounded tree cleanup; refuses special/unowned residue."""
    deadline = min(deadline, time.monotonic() + 8)
    counter = [0, 0]
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
    parent = os.open(os.path.dirname(root), flags)
    fd = None
    def same(info, expected):
        return (info.st_dev, info.st_ino, info.st_uid, stat.S_IMODE(info.st_mode)) == (expected['dev'], expected['ino'], expected['uid'], expected['mode'])
    def walk(directory, depth):
        if depth > 32:
            raise ValueError('private cleanup depth')
        with os.scandir(directory) as entries:
            for entry in entries:
                counter[0] += 1
                if counter[0] > 4096 or time.monotonic() >= deadline:
                    raise ValueError('private cleanup bound')
                before = os.stat(entry.name, dir_fd=directory, follow_symlinks=False)
                counter[1] += before.st_size
                if counter[1] > 67108864 or before.st_uid != identity['uid']:
                    raise ValueError('private cleanup bytes/owner')
                if stat.S_ISDIR(before.st_mode):
                    nested = os.open(entry.name, flags, dir_fd=directory)
                    try:
                        if (before.st_dev, before.st_ino) != (os.fstat(nested).st_dev, os.fstat(nested).st_ino):
                            raise ValueError('private replacement')
                        walk(nested, depth + 1)
                        after = os.stat(entry.name, dir_fd=directory, follow_symlinks=False)
                        if (before.st_dev, before.st_ino) != (after.st_dev, after.st_ino):
                            raise ValueError('private replacement')
                        os.rmdir(entry.name, dir_fd=directory)
                    finally:
                        os.close(nested)
                elif stat.S_ISREG(before.st_mode) and before.st_nlink == 1:
                    after = os.stat(entry.name, dir_fd=directory, follow_symlinks=False)
                    if (before.st_dev, before.st_ino) != (after.st_dev, after.st_ino):
                        raise ValueError('private replacement')
                    os.unlink(entry.name, dir_fd=directory)
                else:
                    raise ValueError('special private residue')
    try:
        fd = os.open(os.path.basename(root), flags, dir_fd=parent)
        if not same(os.fstat(fd), identity):
            return False
        walk(fd, 0)
        if not same(os.stat(os.path.basename(root), dir_fd=parent, follow_symlinks=False), identity):
            return False
        os.rmdir(os.path.basename(root), dir_fd=parent)
        return True
    except (OSError, ValueError):
        return False
    finally:
        if fd is not None:
            os.close(fd)
        os.close(parent)

def _atomic_record(root, name, record):
    data = json.dumps(record, separators=(',', ':'), sort_keys=True).encode('ascii')
    if len(data) > MAX_RECORD:
        raise ValueError('control record bound')
    temp = os.path.join(root, name + '.tmp')
    fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data); stream.flush(); os.fsync(stream.fileno())
        os.replace(temp, os.path.join(root, name))
        directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        try: os.unlink(temp)
        except FileNotFoundError: pass

class _FrameBound:
    def __init__(self):
        self.header = bytearray()
        self.remaining = 0
    def accept(self, data):
        while data:
            if self.remaining:
                count = min(self.remaining, len(data))
                self.remaining -= count
                data = data[count:]
            else:
                self.header.append(data[0]); data = data[1:]
                if len(self.header) > 8192:
                    raise ValueError('LSP header bound')
                if self.header.endswith(b'\r\n\r\n'):
                    headers = self.header.decode('ascii').split('\r\n')[:-2]
                    if any(':' not in line or line.split(':', 1)[0].lower() not in ('content-length', 'content-type') or
                           any(ord(c) < 32 and c != '\t' for c in line) for line in headers):
                        raise ValueError('malformed LSP header')
                    if sum(line.split(':', 1)[0].lower() == 'content-type' for line in headers) > 1:
                        raise ValueError('duplicate LSP content type')
                    lengths = [line.split(':', 1)[1].strip() for line in headers
                               if ':' in line and line.split(':', 1)[0].lower() == 'content-length']
                    if len(lengths) != 1 or not lengths[0].isdigit() or not 0 < int(lengths[0]) <= 1048576:
                        raise ValueError('LSP frame bound')
                    self.remaining = int(lengths[0]); self.header.clear()

def _relay(child, root, policy):
    """Nonblocking bounded relays. No worker can outlive the supervisor receipt."""
    sel = selectors.DefaultSelector()
    streams = [sys.stdin.buffer, child.process.stdin, child.process.stdout, child.process.stderr,
               sys.stdout.buffer, sys.stderr.buffer]
    for stream in streams:
        os.set_blocking(stream.fileno(), False)
    read_to_write = {0: child.process.stdin.fileno(), child.process.stdout.fileno(): 1, child.process.stderr.fileno(): 2}
    pending = {child.process.stdin.fileno(): bytearray(), 1: bytearray(), 2: bytearray()}
    totals = {1: 0, 2: 0}
    frames = {0: _FrameBound(), child.process.stdout.fileno(): _FrameBound()}
    alive_reads = set(read_to_write)
    end = time.monotonic() + POLICIES[policy]['wall']
    while time.monotonic() < end:
        if _stop_requested(root, child.nonce):
            return True
        if _held_terminal(child) and not pending[1] and not pending[2]:
            # Drain remaining child pipes before classifying natural exit.
            if child.process.stdout.fileno() not in alive_reads and child.process.stderr.fileno() not in alive_reads:
                return False
        sel.close(); sel = selectors.DefaultSelector()
        for source in alive_reads:
            target = read_to_write[source]
            if len(pending[target]) < 1048576:
                sel.register(source, selectors.EVENT_READ, ('read', target))
        for target, buffer in pending.items():
            if buffer:
                sel.register(target, selectors.EVENT_WRITE, ('write', None))
        for key, _ in sel.select(.025):
            direction, target = key.data
            if direction == 'read':
                try: data = os.read(key.fd, min(65536, 1048576-len(pending[target])))
                except BlockingIOError: continue
                if not data:
                    if key.fd in frames and (frames[key.fd].remaining or frames[key.fd].header):
                        raise ValueError('incomplete LSP EOF')
                    alive_reads.remove(key.fd)
                    if key.fd == 0 and not pending[child.process.stdin.fileno()]:
                        return True
                    continue
                if key.fd in frames:
                    frames[key.fd].accept(data)
                if target in totals:
                    totals[target] += len(data)
                    limit = POLICIES[policy]['stdout' if target == 1 else 'stderr']
                    if totals[target] > limit:
                        return True
                pending[target].extend(data)
            else:
                try: size = os.write(key.fd, pending[key.fd])
                except BlockingIOError: continue
                except BrokenPipeError: return True
                del pending[key.fd][:size]
    sel.close()
    return True

def _regular_read(path, limit, mode=None):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        before = os.fstat(fd)
        if (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or before.st_size > limit
                or (mode is not None and (stat.S_IMODE(before.st_mode) != mode or before.st_uid != os.getuid()))):
            raise ValueError('unsafe owned regular file')
        data = bytearray()
        while len(data) <= limit:
            part = os.read(fd, min(8192, limit + 1 - len(data)))
            if not part: break
            data.extend(part)
        after = os.fstat(fd)
        named = os.stat(path, follow_symlinks=False)
        key = lambda item: (item.st_dev, item.st_ino, item.st_mode, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
        if len(data) > limit or key(before) != key(after) or key(after) != key(named):
            raise ValueError('owned regular file changed')
        return bytes(data)
    finally:
        os.close(fd)


def _strict_object(data):
    def object_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result: raise ValueError('duplicate control key')
            result[key] = value
        return result
    value = json.loads(data.decode('utf-8', 'strict'), object_pairs_hook=object_pairs,
                       parse_constant=lambda value: (_ for _ in ()).throw(ValueError('nonfinite control number')))
    if type(value) is not dict: raise ValueError('control object required')
    return value


def _stop_requested(root, nonce):
    path = os.path.join(root, 'stop')
    if not os.path.lexists(path): return False
    if _regular_read(path, 128, 0o600).decode('ascii', 'strict') != nonce:
        raise ValueError('stop nonce changed')
    return True


def _directory(path, device, inode, mode):
    if not os.path.isabs(path) or len(path) > 4096 or os.path.normpath(path) != path:
        raise ValueError('invalid guest directory path')
    current = path
    while current != '/':
        if stat.S_ISLNK(os.lstat(current).st_mode): raise ValueError('guest symlink component')
        current = os.path.dirname(current)
    info = os.stat(path, follow_symlinks=False)
    if not stat.S_ISDIR(info.st_mode) or (info.st_dev, info.st_ino, info.st_mode) != (device, inode, mode):
        raise ValueError('mapped guest directory identity changed')


def _validate_launch(root, launch, command, policy):
    text_keys = ('nonce', 'leaseId', 'environmentIdentity', 'policy', 'cwd', 'buildMode', 'buildGuest',
                 'pythonExecutable', 'bootstrapSha256', 'supervisorSha256', 'path')
    integer_keys = ('ownerGeneration', 'privateDevice', 'privateInode', 'sourceDevice', 'sourceInode',
                    'sourceMode', 'buildDevice', 'buildInode', 'buildModeBits')
    if set(launch) != set(text_keys + integer_keys): raise ValueError('unexpected launch fields')
    for key in text_keys:
        if type(launch[key]) is not str or len(launch[key]) > 4096 or any(ord(c) < 32 for c in launch[key]):
            raise ValueError('invalid launch text')
    for key in integer_keys:
        if type(launch[key]) is not int or launch[key] < 0: raise ValueError('invalid launch integer')
    if launch['policy'] != policy or policy != 'editor': raise ValueError('CLI is editor-only; health imports bounded primitives')
    expected_bootstrap = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'cmake-bootstrap.py')
    if command != [launch['pythonExecutable'], '-I', '-S', '-B', expected_bootstrap] or command[0] != sys.executable:
        raise ValueError('unreviewed editor command')
    if not (sys.flags.isolated and sys.flags.no_site and sys.flags.dont_write_bytecode):
        raise ValueError('isolated supervisor flags required')
    for path, key in ((expected_bootstrap, 'bootstrapSha256'), (__file__, 'supervisorSha256')):
        if hashlib.sha256(_regular_read(path, 1048576)).hexdigest() != launch[key]: raise ValueError('helper identity changed')
    expected_env = {'HOME':root+'/home', 'TMPDIR':root+'/tmp', 'XDG_CONFIG_HOME':root+'/xdg-config',
                    'XDG_CACHE_HOME':root+'/xdg-cache', 'XDG_DATA_HOME':root+'/xdg-data', 'XDG_STATE_HOME':root+'/xdg-state',
                    'XDG_RUNTIME_DIR':root+'/xdg-run', 'LANG':'C.UTF-8', 'LC_ALL':'C.UTF-8',
                    'PATH':launch['path'], 'DROIDE_CMAKE_POLICY':'editor'}
    if dict(os.environ) != expected_env: raise ValueError('unclean supervisor environment')
    bins = launch['path'].split(':')
    if bins[:2] != ['/usr/bin', '/bin'] or len(bins) > 34 or any(not p.startswith('/') or '/..' in p for p in bins):
        raise ValueError('unapproved editor PATH')
    _directory(launch['cwd'], launch['sourceDevice'], launch['sourceInode'], launch['sourceMode'])
    _directory(launch['buildGuest'], launch['buildDevice'], launch['buildInode'], launch['buildModeBits'])
    cache = os.path.join(launch['buildGuest'], 'CMakeCache.txt')
    if launch['buildMode'] == 'BUILTIN_ONLY_PRIVATE':
        if launch['buildGuest'] != root+'/build' or os.path.lexists(cache): raise ValueError('private cache appeared')
    elif launch['buildMode'] == 'EXISTING_CONFIGURED':
        content = _regular_read(cache, 1048576).decode('utf-8', 'strict')
        if '\x00' in content or '\r' in content.replace('\r\n', ''): raise ValueError('cache NUL/lone CR')
        values = []
        for line in content.split('\n'):
            line = line.removesuffix('\r')
            if line.startswith(('//', '#')): continue
            if line.lstrip().startswith('CMAKE_HOME_DIRECTORY'):
                if not line.startswith('CMAKE_HOME_DIRECTORY:INTERNAL='): raise ValueError('ambiguous cache source')
                values.append(line.split('=', 1)[1])
        if len(values) != 1: raise ValueError('cache source multiplicity')
        value = values[0]
        if not value.startswith('/') or '\\' in value or '..' in value.split('/') or any(ord(c)<32 for c in value):
            raise ValueError('unsafe cache source')
        if os.path.normpath(value) != launch['cwd']: raise ValueError('cache source association changed')
    else: raise ValueError('unreviewed build mode')


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--control-root', required=True)
    parser.add_argument('--nonce', required=True)
    parser.add_argument('--policy', choices=tuple(POLICIES), required=True)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    root = args.control_root
    private_identity(root)
    if not args.nonce.isalnum() or not 16 <= len(args.nonce) <= 128:
        raise ValueError('invalid owner nonce')
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    expected = _regular_read(os.path.join(root, 'launch.json'), MAX_RECORD, 0o600)
    launch = _strict_object(expected)
    _validate_launch(root, launch, command, args.policy)
    if launch['nonce'] != args.nonce or launch['policy'] != args.policy:
        raise ValueError('owner mismatch')
    private = private_identity(root)
    if private['dev'] != launch['privateDevice'] or private['ino'] != launch['privateInode']:
        raise ValueError('private guest bind identity mismatch')
    outer_pid = int(_regular_read(os.path.join(root, 'outer.pid'), 32, 0o600))
    outer_raw = _regular_read(os.path.join(root, 'outer.stat'), 4096, 0o600)
    outer_fields = outer_raw[outer_raw.rfind(b')') + 2:].split()
    if int(outer_raw[:outer_raw.find(b' ')]) != outer_pid or len(outer_fields) < 20:
        raise ValueError('invalid original outer birth')
    outer = _stat(outer_pid)
    if (outer['start'] != int(outer_fields[19]) or
        _regular_read(os.path.join(root, 'outer.boot'), 128, 0o600).decode('ascii').strip() != _boot_id() or
        _regular_read(os.path.join(root, 'outer.namespace'), 128, 0o600).decode('ascii').strip() != _namespace()):
        raise ValueError('original outer identity changed')
    child = launch_owned(command, launch['cwd'], dict(os.environ), args.policy)
    ready = None
    forced = True
    try:
        child.nonce = args.nonce
        ready = dict(nonce=args.nonce, leaseId=launch['leaseId'], ownerGeneration=launch['ownerGeneration'],
                     environmentIdentity=launch['environmentIdentity'], namespace=child.namespace,
                     bootId=child.boot_id, leaderPid=child.pid, leaderStartTicks=child.start_ticks,
                     outerPid=outer_pid, outerStartTicks=outer['start'], supervisorPid=os.getpid(),
                     supervisorStartTicks=_stat(os.getpid())['start'])
        _atomic_record(root, 'ready.json', ready)
        forced = _relay(child, root, args.policy)
    finally:
        receipt = teardown_owned(child, time.monotonic() + 8, request_stop=forced)
        # Relays are synchronous and no background transport thread is retained.
        for stream in (child.process.stdin, child.process.stdout, child.process.stderr):
            try: stream.close()
            except OSError: pass
        receipt['supervisorTransportClosed'] = True
        # A missing READY still leaves bounded group cleanup, never an invented release proof.
        if ready is not None:
            receipt.update(ready)
            _atomic_record(root, 'receipt.json', receipt)
    return 0 if receipt['status'] == 'COMPLETE' else 74

if __name__ == '__main__':
    raise SystemExit(main())
