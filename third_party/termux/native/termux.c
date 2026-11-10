#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define TERMUX_UNUSED(x) x __attribute__((__unused__))
#ifdef __APPLE__
#define LACKS_PTSNAME_R
#endif

/* Bind the Java fakeable policy's constants without loading JNI in its tests. */
#if EINTR != 4 || EAGAIN != 11 || EWOULDBLOCK != EAGAIN
#error "Owned PTY policy requires the reviewed Linux errno ABI"
#endif
#if POLLIN != 1 || POLLOUT != 4 || POLLERR != 8 || POLLHUP != 16 || POLLNVAL != 32
#error "Owned PTY policy requires the reviewed Linux poll ABI"
#endif

/* Narrow seams for separately authorized native fault injection. Production
 * I/O adapters make ONE libc call. Do not introduce hidden retry macros. */
#ifndef TERMUX_OWNED_READ
#define TERMUX_OWNED_READ read
#endif
#ifndef TERMUX_OWNED_WRITE
#define TERMUX_OWNED_WRITE write
#endif
#ifndef TERMUX_OWNED_POLL
#define TERMUX_OWNED_POLL poll
#endif
#ifndef TERMUX_OWNED_WAITID
#define TERMUX_OWNED_WAITID waitid
#endif
#ifndef TERMUX_OWNED_WAITPID
#define TERMUX_OWNED_WAITPID waitpid
#endif
#ifndef TERMUX_OWNED_KILL
#define TERMUX_OWNED_KILL kill
#endif

enum { OWNER_CAPACITY = 64, IO_SCRATCH_SIZE = 4096, POLL_QUANTUM_MS = 100 };
enum { LANE_READ = 1, LANE_WRITE = 2, LANE_BOTH = 3 };
enum { STOP_DIRECT = 0, STOP_ANCHORED_GROUP = 1 };
enum {
    IO_SUCCESS = 0, IO_ERRNO = 1, IO_CANCELLED = 2, IO_LANE_CLOSED = 3,
    IO_BUSY = 4, IO_INVALID_OWNER = 5, IO_STATE_ERROR = 6
};
enum {
    WAIT_EXITED = 0, WAIT_NO_CHILD = 1, WAIT_OWNERSHIP_LOST = 2,
    WAIT_QUARANTINED = 3, WAIT_EXITED_CLEANUP_UNCONFIRMED = 4
};
enum {
    STOP_REQUESTED = 0, STOP_ALREADY_FINISHED = 1,
    STOP_GROUP_UNAVAILABLE_DIRECT_REQUESTED = 2, STOP_INVALID_OWNER = 3,
    STOP_FAILURE = 4
};
typedef enum {
    OWNER_FREE, OWNER_RESERVED, OWNER_SPAWNING, OWNER_RUNNING,
    OWNER_EXIT_OBSERVED_UNREAPED, OWNER_REAPED, OWNER_NO_CHILD,
    OWNER_OWNERSHIP_LOST, OWNER_QUARANTINED
} owner_state;

typedef struct {
    uint64_t token;
    owner_state state;
    pid_t pid;
    int master;
    unsigned lane_holds;
    unsigned active_lanes;
    unsigned cancelled_lanes;
    int lifecycle_hold;
    int control_active;
    int waiter_claimed;
    int waiter_active;
    int close_in_progress;
    int close_errno;
    int cleanup_errno;
    int signaling_enabled;
    int stop_requested;
    int stop_scope;
    jlong wait_result;
} owned_process;

/* No numeric PID/fd leaves this table as authority. A returned PID is only a
 * historical diagnostic. Sole direct-child reaping and stable SIGCHLD policy
 * are explicit assumptions: arbitrary outside reapers cannot be made safe by
 * a userspace mutex. OWNERSHIP_LOST/QUARANTINED never claim a successful reap. */
static pthread_mutex_t owners_mutex = PTHREAD_MUTEX_INITIALIZER;
static owned_process owners[OWNER_CAPACITY];
static uint64_t next_token = 1;

static jlong tagged_result(unsigned tag, int32_t payload)
{
    return (jlong) (((uint64_t) tag << 32) | (uint32_t) payload);
}

static owned_process* find_owner_locked(jlong token)
{
    if (token <= 0) return NULL;
    for (size_t i = 0; i < OWNER_CAPACITY; ++i) {
        if (owners[i].state != OWNER_FREE && owners[i].token == (uint64_t) token)
            return &owners[i];
    }
    return NULL;
}

static int terminal_state(owner_state state)
{
    return state == OWNER_REAPED || state == OWNER_NO_CHILD ||
            state == OWNER_OWNERSHIP_LOST || state == OWNER_QUARANTINED;
}

static int no_holds_or_borrows(const owned_process* owner)
{
    return !owner->lifecycle_hold && !owner->lane_holds && !owner->active_lanes &&
            !owner->control_active && !owner->waiter_active;
}

/* Detach under the mutex, close ONCE outside it, and only then permit reuse.
 * Linux close(EINTR) must never be retried against a possibly reused integer. */
static void retire_owner(jlong token)
{
    int fd = -1;
    pthread_mutex_lock(&owners_mutex);
    owned_process* owner = find_owner_locked(token);
    if (!owner || !terminal_state(owner->state) || !no_holds_or_borrows(owner) ||
            owner->close_in_progress) {
        pthread_mutex_unlock(&owners_mutex);
        return;
    }
    if (owner->master >= 0) {
        fd = owner->master;
        owner->master = -1;
        owner->close_in_progress = 1;
    } else if (owner->state != OWNER_QUARANTINED) {
        owner->state = OWNER_FREE;
        owner->token = 0;
    }
    pthread_mutex_unlock(&owners_mutex);
    if (fd < 0) return;

    int result = close(fd);
    int saved_errno = result < 0 ? errno : 0;
    pthread_mutex_lock(&owners_mutex);
    owner = find_owner_locked(token); /* close_in_progress pins this generation. */
    if (owner) {
        owner->close_errno = saved_errno;
        owner->close_in_progress = 0;
        if (owner->state != OWNER_QUARANTINED && terminal_state(owner->state) &&
                no_holds_or_borrows(owner)) {
            owner->state = OWNER_FREE;
            owner->token = 0;
        }
    }
    pthread_mutex_unlock(&owners_mutex);
}

static void ownership_failure_locked(owned_process* owner, int lost, int error)
{
    owner->state = lost ? OWNER_OWNERSHIP_LOST : OWNER_QUARANTINED;
    owner->signaling_enabled = 0;
    owner->cancelled_lanes |= LANE_BOTH;
    owner->wait_result = tagged_result(lost ? WAIT_OWNERSHIP_LOST : WAIT_QUARANTINED,
            error > 0 ? error : EIO);
}

static void remember_cleanup_error_locked(owned_process* owner, int error)
{
    if (!owner->cleanup_errno) owner->cleanup_errno = error > 0 ? error : EIO;
}

/* Signals and final reap share this lock. Only the group anchored by this
 * unreaped direct child is eligible. Detached/other groups stay unconfirmed. */
static int signal_owner_locked(owned_process* owner, int scope)
{
    if (!owner->signaling_enabled || owner->pid <= 0 ||
            (owner->state != OWNER_RUNNING && owner->state != OWNER_EXIT_OBSERVED_UNREAPED)) {
        remember_cleanup_error_locked(owner, ECHILD);
        return STOP_FAILURE;
    }
    int group_unavailable = 0;
    if (scope == STOP_ANCHORED_GROUP) {
        pid_t group = getpgid(owner->pid);
        int group_errno = group < 0 ? errno : 0;
        pid_t session = getsid(owner->pid);
        int session_errno = session < 0 ? errno : 0;
        /* -1 is kill's broadcast selector, never an anchored-group target. */
        if (owner->pid > 1 && group == owner->pid && session == owner->pid) {
            int result = TERMUX_OWNED_KILL(-owner->pid, SIGKILL);
            int saved_errno = result < 0 ? errno : 0;
            if (result == 0) return STOP_REQUESTED;
            remember_cleanup_error_locked(owner, saved_errno);
            group_unavailable = 1;
        } else {
            remember_cleanup_error_locked(owner,
                    group_errno ? group_errno : (session_errno ? session_errno : ENOTSUP));
            group_unavailable = 1;
            /* Missing direct child contradicts ownership. Never follow an ESRCH
             * validation with a fresh numeric signal. The waiter establishes
             * reap/loss; close new authority in the meantime. */
            if (group_errno == ESRCH || session_errno == ESRCH ||
                    group_errno == ECHILD || session_errno == ECHILD) {
                owner->signaling_enabled = 0;
                owner->cancelled_lanes |= LANE_BOTH;
                return STOP_FAILURE;
            }
        }
    }
    int result = TERMUX_OWNED_KILL(owner->pid, SIGKILL);
    int saved_errno = result < 0 ? errno : 0;
    if (result < 0) {
        remember_cleanup_error_locked(owner, saved_errno);
        if (saved_errno == ESRCH || saved_errno == ECHILD) {
            owner->signaling_enabled = 0;
            owner->cancelled_lanes |= LANE_BOTH;
        }
        return STOP_FAILURE;
    }
    return group_unavailable ? STOP_GROUP_UNAVAILABLE_DIRECT_REQUESTED : STOP_REQUESTED;
}

static int throw_runtime_exception(JNIEnv* env, const char* message)
{
    if ((*env)->ExceptionCheck(env)) return -1;
    jclass exception = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (!exception) return -1;
    (*env)->ThrowNew(env, exception, message);
    (*env)->DeleteLocalRef(env, exception);
    return -1;
}

static void free_string_array(char** values)
{
    if (!values) return;
    for (char** value = values; *value; ++value) free(*value);
    free(values);
}

/* No native error exception here: publish NO_CHILD before constructing it.
 * Existing VM exceptions from JNI copies remain pending. */
static char* copy_string(JNIEnv* env, jstring value)
{
    if (!value) return NULL;
    const char* utf8 = (*env)->GetStringUTFChars(env, value, NULL);
    if (!utf8) return NULL;
    char* copy = strdup(utf8);
    (*env)->ReleaseStringUTFChars(env, value, utf8);
    return copy;
}

static char** copy_string_array(JNIEnv* env, jobjectArray values)
{
    jsize size = values ? (*env)->GetArrayLength(env, values) : 0;
    if ((*env)->ExceptionCheck(env)) return NULL;
    char** result = (char**) calloc((size_t) size + 1, sizeof(char*));
    if (!result) return NULL;
    for (jsize i = 0; i < size; ++i) {
        jstring value = (jstring) (*env)->GetObjectArrayElement(env, values, i);
        if ((*env)->ExceptionCheck(env) || !value) goto failed;
        result[i] = copy_string(env, value);
        (*env)->DeleteLocalRef(env, value);
        if (!result[i] || (*env)->ExceptionCheck(env)) goto failed;
    }
    return result;
failed:
    free_string_array(result);
    return NULL;
}

static jint JNICALL owned_process_api_version(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz))
{
    return 2;
}

static jlong JNICALL reserve_owned_process(JNIEnv* env, jclass TERMUX_UNUSED(clazz))
{
    jlong token = 0;
    int generations_exhausted = 0;
    pthread_mutex_lock(&owners_mutex);
    if (next_token > INT64_MAX) {
        generations_exhausted = 1;
    } else {
        for (size_t i = 0; i < OWNER_CAPACITY; ++i) {
            if (owners[i].state != OWNER_FREE) continue;
            owned_process* owner = &owners[i];
            memset(owner, 0, sizeof(*owner));
            owner->token = next_token++;
            owner->state = OWNER_RESERVED;
            owner->master = -1;
            owner->lifecycle_hold = 1;
            owner->lane_holds = LANE_BOTH;
            owner->wait_result = tagged_result(WAIT_NO_CHILD, 0);
            token = (jlong) owner->token;
            break;
        }
    }
    pthread_mutex_unlock(&owners_mutex);
    if (!token) throw_runtime_exception(env, generations_exhausted ?
            "Native PTY owner generations exhausted" :
            "Native PTY owner capacity exhausted (64 slots, including quarantined owners)");
    return token;
}

static jint JNICALL create_owned_subprocess(JNIEnv* env, jclass TERMUX_UNUSED(clazz),
        jlong token, jstring cmd, jstring cwd, jobjectArray args, jobjectArray env_vars,
        jint rows, jint columns)
{
    pthread_mutex_lock(&owners_mutex);
    owned_process* owner = find_owner_locked(token);
    if (!owner || owner->state != OWNER_RESERVED || !owner->lifecycle_hold || owner->waiter_claimed) {
        pthread_mutex_unlock(&owners_mutex);
        return throw_runtime_exception(env, "Invalid or already handed-off native PTY owner");
    }
    owner->state = OWNER_SPAWNING; /* Pins this generation throughout setup/fork. */
    pthread_mutex_unlock(&owners_mutex);

    int master = -1;
    int local_close_errno = 0;
    pid_t pid = -1;
    char* command = NULL;
    char* directory = NULL;
    char** argv = NULL;
    char** envp = NULL;
    const char* failure = "Cannot copy PTY command, arguments, or environment";
    if (!cmd || !cwd || rows < 1 || rows > 65535 || columns < 1 || columns > 65535) {
        failure = "Invalid PTY command, directory, or size";
        goto failed;
    }
    command = copy_string(env, cmd);
    if (!command || (*env)->ExceptionCheck(env)) goto failed;
    directory = copy_string(env, cwd);
    if (!directory || (*env)->ExceptionCheck(env)) goto failed;
    argv = copy_string_array(env, args);
    if (!argv) goto failed;
    envp = copy_string_array(env, env_vars);
    if (!envp) goto failed;

    struct sigaction child_action;
    if (sigaction(SIGCHLD, NULL, &child_action) != 0 ||
            child_action.sa_handler == SIG_IGN || (child_action.sa_flags & SA_NOCLDWAIT)) {
        failure = "Native PTY requires owned SIGCHLD reaping (no SIG_IGN/SA_NOCLDWAIT)";
        goto failed;
    }
    master = open("/dev/ptmx", O_RDWR | O_CLOEXEC | O_NONBLOCK);
    if (master < 0) { failure = "Cannot open nonblocking /dev/ptmx"; goto failed; }
#ifdef LACKS_PTSNAME_R
    char* devname;
#else
    char devname[64];
#endif
    if (grantpt(master) || unlockpt(master) ||
#ifdef LACKS_PTSNAME_R
            (devname = ptsname(master)) == NULL
#else
            ptsname_r(master, devname, sizeof(devname))
#endif
       ) { failure = "Cannot grant/unlock/name PTY slave"; goto failed; }

    struct termios attributes;
    if (tcgetattr(master, &attributes) != 0) {
        failure = "Cannot read PTY attributes"; goto failed;
    }
    attributes.c_iflag |= IUTF8;
    attributes.c_iflag &= ~(IXON | IXOFF);
    if (tcsetattr(master, TCSANOW, &attributes) != 0) {
        failure = "Cannot configure PTY attributes"; goto failed;
    }
    struct winsize size = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) columns };
    if (ioctl(master, TIOCSWINSZ, &size) != 0) {
        failure = "Cannot set initial PTY size"; goto failed;
    }
    pid = fork();
    if (pid < 0) { failure = "PTY fork failed"; goto failed; }
    if (pid == 0) {
        /* Preserved launch semantics. opendir/clearenv/putenv/asprintf/execvp
         * below are NOT claimed async-signal-safe after a multithreaded fork. */
        sigset_t signals_to_unblock;
        if (sigfillset(&signals_to_unblock) != 0 ||
                sigprocmask(SIG_UNBLOCK, &signals_to_unblock, NULL) != 0) _exit(127);
        close(master);
        if (setsid() < 0) _exit(127);
        int slave = open(devname, O_RDWR);
        if (slave < 0) _exit(127);
        if (dup2(slave, STDIN_FILENO) < 0 || dup2(slave, STDOUT_FILENO) < 0 ||
                dup2(slave, STDERR_FILENO) < 0) _exit(127);
        if (slave > STDERR_FILENO) close(slave);
        DIR* self_dir = opendir("/proc/self/fd");
        if (self_dir != NULL) {
            int self_dir_fd = dirfd(self_dir);
            struct dirent* entry;
            while ((entry = readdir(self_dir)) != NULL) {
                int fd = atoi(entry->d_name);
                if (fd > 2 && fd != self_dir_fd) close(fd);
            }
            closedir(self_dir);
        }
        clearenv();
        if (envp) for (char** item = envp; *item; ++item) putenv(*item);
        if (chdir(directory) != 0) {
            char* error_message;
            if (asprintf(&error_message, "chdir(\"%s\")", directory) == -1) error_message = "chdir()";
            perror(error_message);
            fflush(stderr);
        }
        execvp(command, argv);
        char* error_message;
        if (asprintf(&error_message, "exec(\"%s\")", command) == -1) error_message = "exec()";
        perror(error_message);
        _exit(1);
    }

    /* No post-fork Java output-array publication, allocation, or callback. */
    pthread_mutex_lock(&owners_mutex);
    owner = find_owner_locked(token); /* SPAWNING cannot retire/reuse. */
    owner->pid = pid;
    owner->master = master;
    owner->state = OWNER_RUNNING;
    owner->signaling_enabled = 1;
    if (owner->stop_requested) (void) signal_owner_locked(owner, owner->stop_scope);
    pthread_mutex_unlock(&owners_mutex);
    free(command);
    free(directory);
    free_string_array(argv);
    free_string_array(envp);
    return (jint) pid;

failed:
    if (master >= 0 && close(master) < 0) local_close_errno = errno;
    free(command);
    free(directory);
    free_string_array(argv);
    free_string_array(envp);
    pthread_mutex_lock(&owners_mutex);
    owner = find_owner_locked(token);
    owner->state = OWNER_NO_CHILD;
    owner->signaling_enabled = 0;
    owner->cancelled_lanes |= LANE_BOTH;
    owner->close_errno = local_close_errno;
    owner->wait_result = tagged_result(WAIT_NO_CHILD, 0);
    pthread_mutex_unlock(&owners_mutex);
    retire_owner(token);
    /* Native ownership is settled even if constructing the exception fails. */
    return throw_runtime_exception(env, failure);
}

static jint JNICALL request_owned_stop(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz),
        jlong token, jint scope)
{
    if (scope != STOP_DIRECT && scope != STOP_ANCHORED_GROUP) return STOP_FAILURE;
    int result;
    pthread_mutex_lock(&owners_mutex);
    owned_process* owner = find_owner_locked(token);
    if (!owner) {
        result = STOP_INVALID_OWNER;
    } else {
        /* Admission linearizes here; an already borrowed write may still run. */
        owner->cancelled_lanes |= LANE_WRITE;
        owner->stop_requested = 1;
        if (scope == STOP_ANCHORED_GROUP) owner->stop_scope = STOP_ANCHORED_GROUP;
        if (owner->state == OWNER_RESERVED || owner->state == OWNER_SPAWNING)
            result = STOP_REQUESTED;
        else if (owner->state == OWNER_REAPED || owner->state == OWNER_NO_CHILD)
            result = STOP_ALREADY_FINISHED;
        else if (owner->state == OWNER_OWNERSHIP_LOST || owner->state == OWNER_QUARANTINED)
            result = STOP_FAILURE;
        else
            result = signal_owner_locked(owner, owner->stop_scope);
    }
    pthread_mutex_unlock(&owners_mutex);
    return result;
}

/* Sleep only outside the owner mutex. Resume interrupted sleep remainders so
 * wait syscall EINTR cannot turn into an immediate hot retry. */
static void pause_owned_waiter(void)
{
    struct timespec remaining = { .tv_sec = 0, .tv_nsec = 100000000L };
    struct timespec next;
    while (nanosleep(&remaining, &next) < 0 && errno == EINTR) remaining = next;
}

static jlong JNICALL wait_for_owned_process(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz),
        jlong token)
{
    pthread_mutex_lock(&owners_mutex);
    owned_process* owner = find_owner_locked(token);
    if (!owner || !owner->lifecycle_hold || owner->waiter_claimed) {
        int reason = owner && owner->waiter_claimed ? EBUSY : EINVAL;
        pthread_mutex_unlock(&owners_mutex);
        return tagged_result(WAIT_QUARANTINED, reason); /* Rejection does not mutate the slot. */
    }
    owner->waiter_claimed = 1;
    owner->waiter_active = 1;
    if (owner->state == OWNER_RESERVED) {
        /* Java's process handoff says no spawn; subsequent create must reject. */
        owner->state = OWNER_NO_CHILD;
        owner->cancelled_lanes |= LANE_BOTH;
        owner->wait_result = tagged_result(WAIT_NO_CHILD, 0);
    }
    pthread_mutex_unlock(&owners_mutex);

    for (;;) {
        int should_pause = 0;
        pthread_mutex_lock(&owners_mutex);
        owner = find_owner_locked(token); /* waiter_active pins this generation. */
        if (owner->state == OWNER_SPAWNING) {
            should_pause = 1;
        } else if (owner->state == OWNER_RUNNING && owner->pid <= 0) {
            ownership_failure_locked(owner, 0, EPROTO);
        } else if (owner->state == OWNER_RUNNING) {
            siginfo_t observation;
            memset(&observation, 0, sizeof(observation));
            int result = TERMUX_OWNED_WAITID(P_PID, (id_t) owner->pid, &observation,
                    WEXITED | WNOHANG | WNOWAIT);
            int saved_errno = result < 0 ? errno : 0;
            if (result < 0) {
                if (saved_errno == EINTR) should_pause = 1;
                else ownership_failure_locked(owner, saved_errno == ECHILD, saved_errno);
            } else if (result != 0) {
                ownership_failure_locked(owner, 0, EPROTO);
            } else if (observation.si_pid == 0) {
                should_pause = 1;
            } else if (observation.si_pid != owner->pid ||
                    (observation.si_code != CLD_EXITED && observation.si_code != CLD_KILLED &&
                     observation.si_code != CLD_DUMPED)) {
                ownership_failure_locked(owner, 0, EPROTO);
            } else {
                owner->state = OWNER_EXIT_OBSERVED_UNREAPED;
                /* Do not release this PID before anchored-group cleanup. */
                (void) signal_owner_locked(owner, STOP_ANCHORED_GROUP);
            }
        }
        if (owner->state == OWNER_EXIT_OBSERVED_UNREAPED && !should_pause) {
            int status = 0;
            pid_t result = TERMUX_OWNED_WAITPID(owner->pid, &status, WNOHANG);
            int saved_errno = result < 0 ? errno : 0;
            if (result < 0 && saved_errno == EINTR) {
                should_pause = 1;
            } else if (result < 0) {
                ownership_failure_locked(owner, saved_errno == ECHILD, saved_errno);
            } else if (result != owner->pid || (!WIFEXITED(status) && !WIFSIGNALED(status))) {
                /* Includes impossible zero after matching WNOWAIT exit. */
                ownership_failure_locked(owner, 0, EPROTO);
            } else {
                int exit_status = WIFEXITED(status) ? WEXITSTATUS(status) : -WTERMSIG(status);
                owner->state = OWNER_REAPED;
                owner->signaling_enabled = 0;
                owner->cancelled_lanes |= LANE_WRITE;
                owner->wait_result = tagged_result(owner->cleanup_errno ?
                        WAIT_EXITED_CLEANUP_UNCONFIRMED : WAIT_EXITED, exit_status);
            }
        }
        if (terminal_state(owner->state)) {
            jlong result = owner->wait_result;
            owner->waiter_active = 0;
            pthread_mutex_unlock(&owners_mutex);
            retire_owner(token);
            return result;
        }
        pthread_mutex_unlock(&owners_mutex);
        /* Pending, SPAWNING, and either wait EINTR all take this pause. */
        pause_owned_waiter();
    }
}

static jboolean JNICALL release_owned_process(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz),
        jlong token)
{
    int released = 0;
    pthread_mutex_lock(&owners_mutex);
    owned_process* owner = find_owner_locked(token);
    if (owner && owner->lifecycle_hold) {
        if (owner->state == OWNER_RESERVED && !owner->waiter_claimed) {
            owner->state = OWNER_NO_CHILD;
            owner->cancelled_lanes |= LANE_BOTH;
            owner->wait_result = tagged_result(WAIT_NO_CHILD, 0);
        }
        owner->lifecycle_hold = 0; /* Immediately disables new control borrows. */
        released = 1;
    }
    pthread_mutex_unlock(&owners_mutex);
    retire_owner(token);
    return released ? JNI_TRUE : JNI_FALSE;
}

static int valid_buffer(JNIEnv* env, jbyteArray bytes, jint offset, jint count)
{
    if (!bytes || offset < 0 || count < 1 || count > IO_SCRATCH_SIZE) return 0;
    jsize length = (*env)->GetArrayLength(env, bytes);
    if ((*env)->ExceptionCheck(env)) return 0;
    return count <= length && offset <= length - count;
}

/* This grant is the operation-admission linearization point. A borrowed fd
 * cannot be closed/reused until end_lane_operation clears its active flag. */
static jlong begin_lane_operation(jlong token, int lane, int* fd)
{
    unsigned tag = IO_SUCCESS;
    int reason = 0;
    pthread_mutex_lock(&owners_mutex);
    owned_process* owner = find_owner_locked(token);
    if (!owner) { tag = IO_INVALID_OWNER; reason = EINVAL; }
    else if (!(owner->lane_holds & lane)) { tag = IO_LANE_CLOSED; }
    else if (owner->cancelled_lanes & lane) { tag = IO_CANCELLED; }
    else if (owner->state != OWNER_RUNNING && owner->state != OWNER_EXIT_OBSERVED_UNREAPED &&
            owner->state != OWNER_REAPED) { tag = IO_STATE_ERROR; reason = ENXIO; }
    else if (owner->master < 0 || owner->close_in_progress) { tag = IO_STATE_ERROR; reason = EBADF; }
    else if (owner->active_lanes & lane) { tag = IO_BUSY; reason = EBUSY; }
    else { owner->active_lanes |= lane; *fd = owner->master; }
    pthread_mutex_unlock(&owners_mutex);
    return tagged_result(tag, reason);
}

static void end_lane_operation(jlong token, int lane, int cancel_lane)
{
    pthread_mutex_lock(&owners_mutex);
    owned_process* owner = find_owner_locked(token);
    if (owner) {
        if (cancel_lane) owner->cancelled_lanes |= (unsigned) lane;
        owner->active_lanes &= ~(unsigned) lane;
    }
    pthread_mutex_unlock(&owners_mutex);
    retire_owner(token);
}

static jlong JNICALL read_owned_pty_once(JNIEnv* env, jclass TERMUX_UNUSED(clazz), jlong token,
        jbyteArray destination, jint offset, jint count)
{
    if (!valid_buffer(env, destination, offset, count)) return tagged_result(IO_STATE_ERROR, EINVAL);
    jbyte scratch[IO_SCRATCH_SIZE];
    int fd = -1;
    jlong result = begin_lane_operation(token, LANE_READ, &fd);
    if (result != tagged_result(IO_SUCCESS, 0)) return result;
    ssize_t amount = TERMUX_OWNED_READ(fd, scratch, (size_t) count);
    int saved_errno = amount < 0 ? errno : 0;
    int cancel_lane = 0;
    if (amount > count) {
        result = tagged_result(IO_STATE_ERROR, EIO);
        cancel_lane = 1;
    } else if (amount < 0) {
        result = tagged_result(IO_ERRNO, saved_errno);
    } else {
        result = tagged_result(IO_SUCCESS, (int32_t) amount);
        if (amount > 0) {
            (*env)->SetByteArrayRegion(env, destination, offset, (jsize) amount, scratch);
            if ((*env)->ExceptionCheck(env)) {
                /* Consumed bytes cannot be replayed. Unwind even with a pending
                 * JNI exception; Java must record incomplete output. */
                result = tagged_result(IO_STATE_ERROR, EFAULT);
                cancel_lane = 1;
            }
        }
    }
    end_lane_operation(token, LANE_READ, cancel_lane);
    return result;
}

static jlong JNICALL write_owned_pty_once(JNIEnv* env, jclass TERMUX_UNUSED(clazz), jlong token,
        jbyteArray source, jint offset, jint count)
{
    if (!valid_buffer(env, source, offset, count)) return tagged_result(IO_STATE_ERROR, EINVAL);
    jbyte scratch[IO_SCRATCH_SIZE];
    (*env)->GetByteArrayRegion(env, source, offset, count, scratch);
    if ((*env)->ExceptionCheck(env)) return tagged_result(IO_STATE_ERROR, EFAULT);
    int fd = -1;
    jlong result = begin_lane_operation(token, LANE_WRITE, &fd);
    if (result != tagged_result(IO_SUCCESS, 0)) return result;
    ssize_t amount = TERMUX_OWNED_WRITE(fd, scratch, (size_t) count);
    int saved_errno = amount < 0 ? errno : 0;
    int impossible = amount > count;
    result = impossible ? tagged_result(IO_STATE_ERROR, EIO) :
            (amount < 0 ? tagged_result(IO_ERRNO, saved_errno) : tagged_result(IO_SUCCESS, (int32_t) amount));
    end_lane_operation(token, LANE_WRITE, impossible);
    return result;
}

static jlong JNICALL poll_owned_pty_once(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz),
        jlong token, jint lane, jint timeout_millis)
{
    if ((lane != LANE_READ && lane != LANE_WRITE) || timeout_millis < 0 ||
            timeout_millis > POLL_QUANTUM_MS) return tagged_result(IO_STATE_ERROR, EINVAL);
    int fd = -1;
    jlong result = begin_lane_operation(token, lane, &fd);
    if (result != tagged_result(IO_SUCCESS, 0)) return result;
    struct pollfd descriptor = { .fd = fd, .events = lane == LANE_READ ? POLLIN : POLLOUT, .revents = 0 };
    int ready = TERMUX_OWNED_POLL(&descriptor, 1, timeout_millis);
    int saved_errno = ready < 0 ? errno : 0;
    result = ready < 0 ? tagged_result(IO_ERRNO, saved_errno) :
            tagged_result(IO_SUCCESS, ready == 0 ? 0 : (unsigned short) descriptor.revents);
    end_lane_operation(token, lane, 0);
    return result;
}

static void JNICALL cancel_owned_pty_io(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz),
        jlong token, jint lanes)
{
    if (lanes < 1 || (lanes & ~LANE_BOTH)) return;
    pthread_mutex_lock(&owners_mutex);
    owned_process* owner = find_owner_locked(token);
    if (owner) owner->cancelled_lanes |= (unsigned) lanes;
    pthread_mutex_unlock(&owners_mutex);
}

static jboolean JNICALL release_owned_pty_lane(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz),
        jlong token, jint lane)
{
    if (lane != LANE_READ && lane != LANE_WRITE) return JNI_FALSE;
    int released = 0;
    pthread_mutex_lock(&owners_mutex);
    owned_process* owner = find_owner_locked(token);
    if (owner && (owner->lane_holds & lane)) {
        owner->cancelled_lanes |= (unsigned) lane;
        owner->lane_holds &= ~(unsigned) lane;
        released = 1;
    }
    pthread_mutex_unlock(&owners_mutex);
    retire_owner(token);
    return released ? JNI_TRUE : JNI_FALSE;
}

static jboolean JNICALL resize_owned_pty(JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz),
        jlong token, jint rows, jint columns)
{
    if (rows < 1 || rows > 65535 || columns < 1 || columns > 65535) return JNI_FALSE;
    int fd = -1;
    pthread_mutex_lock(&owners_mutex);
    owned_process* owner = find_owner_locked(token);
    if (owner && owner->lifecycle_hold && owner->state == OWNER_RUNNING &&
            owner->signaling_enabled && !owner->stop_requested && !owner->control_active &&
            owner->master >= 0 && !owner->close_in_progress) {
        owner->control_active = 1;
        fd = owner->master;
    }
    pthread_mutex_unlock(&owners_mutex);
    if (fd < 0) return JNI_FALSE;
    struct winsize size = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) columns };
    int result = ioctl(fd, TIOCSWINSZ, &size);
    pthread_mutex_lock(&owners_mutex);
    owner = find_owner_locked(token);
    if (owner) owner->control_active = 0;
    pthread_mutex_unlock(&owners_mutex);
    retire_owner(token);
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}

/* Register the COMPLETE ABI before reserve can run. Historical dynamic
 * numeric-PID/fd JNI entrypoints intentionally do not exist. */
static const JNINativeMethod owned_methods[] = {
    { "ownedProcessApiVersion", "()I", (void*) owned_process_api_version },
    { "reserveOwnedProcess", "()J", (void*) reserve_owned_process },
    { "createOwnedSubprocess", "(JLjava/lang/String;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;II)I", (void*) create_owned_subprocess },
    { "requestOwnedStop", "(JI)I", (void*) request_owned_stop },
    { "waitForOwnedProcess", "(J)J", (void*) wait_for_owned_process },
    { "releaseOwnedProcess", "(J)Z", (void*) release_owned_process },
    { "readOwnedPtyOnce", "(J[BII)J", (void*) read_owned_pty_once },
    { "writeOwnedPtyOnce", "(J[BII)J", (void*) write_owned_pty_once },
    { "pollOwnedPtyOnce", "(JII)J", (void*) poll_owned_pty_once },
    { "cancelOwnedPtyIo", "(JI)V", (void*) cancel_owned_pty_io },
    { "releaseOwnedPtyLane", "(JI)Z", (void*) release_owned_pty_lane },
    { "resizeOwnedPty", "(JII)Z", (void*) resize_owned_pty }
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* TERMUX_UNUSED(reserved))
{
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**) &env, JNI_VERSION_1_6) != JNI_OK || !env) return JNI_ERR;
    if ((*env)->ExceptionCheck(env)) return JNI_ERR;
    jclass clazz = (*env)->FindClass(env, "com/termux/terminal/JNI");
    if (!clazz || (*env)->ExceptionCheck(env)) {
        if (clazz) (*env)->DeleteLocalRef(env, clazz);
        return JNI_ERR;
    }
    jint result = (*env)->RegisterNatives(env, clazz, owned_methods,
            (jint) (sizeof(owned_methods) / sizeof(owned_methods[0])));
    int failed = result != JNI_OK || (*env)->ExceptionCheck(env);
    (*env)->DeleteLocalRef(env, clazz);
    return failed ? JNI_ERR : JNI_VERSION_1_6;
}
