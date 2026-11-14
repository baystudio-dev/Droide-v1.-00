"""Bounded owned-group supervision; no detached-process closure claim."""
import os
import signal
import subprocess
import time
class SupervisorInterrupted(Exception): pass
def require(condition, message):
    if not condition: raise ValueError(message)

def stop_owned_group(proc, grace_seconds):
    """Leader must remain unreaped, pinning its PID against group-id reuse."""
    require(proc.returncode is None, 'owned leader already reaped; refusing group signal')
    actions = []
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(proc.pid, sig)
            actions.append(signal.Signals(sig).name)
        except ProcessLookupError:
            actions.append(signal.Signals(sig).name + '_GROUP_ABSENT')
        if sig == signal.SIGTERM:
            # Deliberately do not poll()/wait(): a zombie leader pins the PGID.
            time.sleep(grace_seconds)
    proc.wait(timeout=10)
    return {'signals': actions, 'leaderReaped': True, 'scope': 'OWNED_NEW_SESSION_PROCESS_GROUP_ONLY'}


def supervise(argv, cwd, env, stdout_fd, stderr_fd, deadline_seconds=600,
              grace_seconds=10, on_started=None):
    require(deadline_seconds > 0 and grace_seconds >= 0, 'invalid supervision deadline')
    require(hasattr(os, 'waitid') and hasattr(os, 'WNOWAIT'), 'non-reaping wait observation required')
    started = time.monotonic()
    proc = None
    interrupt_signal = None
    result = {'argv': list(argv), 'cwd': str(cwd), 'environment': dict(env),
              'deadlineSeconds': deadline_seconds, 'startedAt': time.time(), 'cleanup': None}

    def interrupted(signum, frame):
        # Do not raise during Popen construction: retain the created PID first.
        nonlocal interrupt_signal
        interrupt_signal = signum

    previous = {sig: signal.getsignal(sig) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        for sig in previous:
            signal.signal(sig, interrupted)
        proc = subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                stdout=stdout_fd, stderr=stderr_fd,
                                start_new_session=True, close_fds=True)
        result.update(pid=proc.pid, pgid=proc.pid)
        if on_started:
            on_started(dict(result))
        while True:
            if interrupt_signal is not None:
                raise SupervisorInterrupted('signal ' + signal.Signals(interrupt_signal).name)
            if time.monotonic() - started >= deadline_seconds:
                result['outcome'] = 'TIMEOUT'
                break
            observed = os.waitid(os.P_PID, proc.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
            if observed is not None:
                # Preserve the exit status without reaping. The zombie leader
                # pins its PGID until even successful/nonzero groups are drained.
                result['observedExit'] = {'code': observed.si_code, 'status': observed.si_status}
                result['returncode'] = (observed.si_status if observed.si_code == os.CLD_EXITED
                                        else -observed.si_status)
                result['outcome'] = 'SUCCESS' if result['returncode'] == 0 else 'NONZERO_EXIT'
                break
            time.sleep(min(0.05, max(0, deadline_seconds - (time.monotonic() - started))))
    except (KeyboardInterrupt, SupervisorInterrupted) as error:
        result.update(outcome='INTERRUPTED', error=str(error))
    except Exception as error:
        result.update(outcome='SUPERVISOR_ERROR', error=type(error).__name__ + ': ' + str(error))
    finally:
        # A second Ctrl-C/termination cannot skip owned-group cleanup/postflight.
        for sig in previous:
            signal.signal(sig, signal.SIG_IGN)
        if proc is not None and proc.returncode is None:
            try:
                result['cleanup'] = stop_owned_group(proc, grace_seconds)
                if 'observedExit' in result:
                    require(proc.returncode == result['returncode'], 'observed/reaped leader exit status differs')
            except Exception as error:
                result.update(outcome='CLEANUP_FAILED', cleanupError=type(error).__name__ + ': ' + str(error))
            result['returncode'] = proc.returncode
        for sig, handler in previous.items():
            signal.signal(sig, handler)
        result['elapsedSeconds'] = time.monotonic() - started
        result['finishedAt'] = time.time()
    return result

