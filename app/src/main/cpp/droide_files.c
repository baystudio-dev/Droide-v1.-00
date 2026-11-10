#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <stdint.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/file.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE (1U << 0)
#endif

static int kernel_move_no_replace(int source_dir, const char *source, int target_dir, const char *target) {
#if defined(DROIDE_FILES_TEST_ERRNO)
    (void)source_dir; (void)source; (void)target_dir; (void)target;
    return DROIDE_FILES_TEST_ERRNO;
#elif defined(DROIDE_FILES_TEST_NO_RENAMEAT2) || !defined(__NR_renameat2)
    (void)source_dir; (void)source; (void)target_dir; (void)target;
    return ENOSYS;
#else
    if (syscall(__NR_renameat2, source_dir, source, target_dir, target, RENAME_NOREPLACE) == 0) return 0;
    return errno > 0 ? errno : EIO;
#endif
}

static jlong kernel_move_no_replace_durable(int source_dir, const char *source, int target_dir, const char *target) {
    int error = kernel_move_no_replace(source_dir, source, target_dir, target);
    if (error != 0) return (jlong)(uint32_t)error;
    int sync_error = 0;
#if defined(DROIDE_FILES_TEST_SYNC_ERRNO)
    sync_error = DROIDE_FILES_TEST_SYNC_ERRNO;
#else
    if (fsync(source_dir) != 0) sync_error = errno > 0 ? errno : EIO;
    struct stat first, second;
    int same = fstat(source_dir, &first) == 0 && fstat(target_dir, &second) == 0 &&
        first.st_dev == second.st_dev && first.st_ino == second.st_ino;
    if (!same && fsync(target_dir) != 0 && sync_error == 0) sync_error = errno > 0 ? errno : EIO;
#endif
    return ((jlong)1 << 32) | (jlong)(uint32_t)sync_error;
}

/* Paths arrive as standard UTF-8 bytes, not JNI modified UTF-8 strings. */
static int copy_path(JNIEnv *env, jbyteArray bytes, char path[PATH_MAX]) {
    if (bytes == NULL) {
        return EINVAL;
    }
    const jsize length = (*env)->GetArrayLength(env, bytes);
    if (length <= 0) {
        return EINVAL;
    }
    if ((size_t)length >= PATH_MAX) {
        return ENAMETOOLONG;
    }
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *)path);
    if ((*env)->ExceptionCheck(env)) {
        /* Keep the original pending JVM exception. */
        return EIO;
    }
    if (memchr(path, '\0', (size_t)length) != NULL) {
        return EINVAL;
    }
    path[length] = '\0';
    return 0;
}

static jint rename_no_replace(JNIEnv *env, jbyteArray source_bytes,
                              jbyteArray target_bytes, int allow_directory) {
    char source[PATH_MAX];
    char target[PATH_MAX];
    int error = copy_path(env, source_bytes, source);
    if (error != 0) {
        return error;
    }
    error = copy_path(env, target_bytes, target);
    if (error != 0) {
        return error;
    }

    /* The caller owns this prepared temporary file until publication completes. */
    struct stat source_stat;
    if (fstatat(AT_FDCWD, source, &source_stat, AT_SYMLINK_NOFOLLOW) != 0) {
        return errno > 0 ? errno : EIO;
    }
    if (!S_ISREG(source_stat.st_mode) && !(allow_directory && S_ISDIR(source_stat.st_mode))) {
        return EINVAL;
    }

    return kernel_move_no_replace(AT_FDCWD, source, AT_FDCWD, target);
}

JNIEXPORT jint JNICALL
Java_com_baystudio_droide_core_NativeFilePublication_renameNoReplace(
        JNIEnv *env, jclass type, jbyteArray source_bytes, jbyteArray target_bytes) {
    (void)type;
    return rename_no_replace(env, source_bytes, target_bytes, 0);
}

/* Ordinary workspace moves must also preserve a concurrently created target. */
JNIEXPORT jint JNICALL
Java_com_baystudio_droide_core_NativeFilePublication_moveNoReplace(
        JNIEnv *env, jclass type, jbyteArray source_bytes, jbyteArray target_bytes) {
    (void)type;
    return rename_no_replace(env, source_bytes, target_bytes, 1);
}

static int open_child_parent(int root, char relative[PATH_MAX], char **leaf) {
    if (relative[0] == '/' || relative[0] == '\0') return -EINVAL;
    int current = fcntl(root, F_DUPFD_CLOEXEC, 0);
    if (current < 0) return -(errno > 0 ? errno : EIO);
    struct stat root_stat;
    if (fstat(current, &root_stat) != 0 || !S_ISDIR(root_stat.st_mode)) {
        close(current); return -ENOTDIR;
    }
    char *component = relative;
    while (1) {
        char *slash = strchr(component, '/');
        if (slash != NULL) *slash = '\0';
        if (component[0] == '\0' || strcmp(component, ".") == 0 || strcmp(component, "..") == 0 || strlen(component) > NAME_MAX) {
            close(current); return -EINVAL;
        }
        if (slash == NULL) { *leaf = component; return current; }
        int next = openat(current, component, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        int error = errno;
        close(current);
        if (next < 0) return -(error > 0 ? error : EIO);
        current = next;
        component = slash + 1;
    }
}

JNIEXPORT jint JNICALL
Java_com_baystudio_droide_core_NativeFilePublication_openDirectoryRoot(JNIEnv *env, jclass type, jbyteArray bytes) {
    (void)type;
    char path[PATH_MAX];
    int error = copy_path(env, bytes, path);
    if (error != 0) return -error;
    int fd = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    return fd >= 0 ? fd : -(errno > 0 ? errno : EIO);
}

JNIEXPORT jint JNICALL
Java_com_baystudio_droide_core_NativeFilePublication_duplicateDescriptor(JNIEnv *env, jclass type, jint fd) {
    (void)env; (void)type;
    int copy = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    return copy >= 0 ? copy : -(errno > 0 ? errno : EIO);
}

JNIEXPORT jint JNICALL
Java_com_baystudio_droide_core_NativeFilePublication_closeDescriptor(JNIEnv *env, jclass type, jint fd) {
    (void)env; (void)type;
    return close(fd) == 0 ? 0 : (errno > 0 ? errno : EIO);
}

JNIEXPORT jint JNICALL
Java_com_baystudio_droide_core_NativeFilePublication_forceDirectory(JNIEnv *env, jclass type, jint fd) {
    (void)env; (void)type;
    struct stat attrs;
    if (fstat(fd, &attrs) != 0) return errno > 0 ? errno : EIO;
    if (!S_ISDIR(attrs.st_mode)) return ENOTDIR;
    return fsync(fd) == 0 ? 0 : (errno > 0 ? errno : EIO);
}

JNIEXPORT jint JNICALL
Java_com_baystudio_droide_core_NativeFilePublication_openOrdinaryRelative(JNIEnv *env, jclass type, jint root, jbyteArray bytes) {
    (void)type;
    char relative[PATH_MAX]; char *leaf = NULL;
    int error = copy_path(env, bytes, relative);
    if (error != 0) return -error;
    int parent = open_child_parent(root, relative, &leaf);
    if (parent < 0) return parent;
    int fd = openat(parent, leaf, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
    error = errno;
    close(parent);
    if (fd < 0) return -(error > 0 ? error : EIO);
    struct stat attrs;
    if (fstat(fd, &attrs) != 0 || !S_ISREG(attrs.st_mode)) { close(fd); return -EINVAL; }
    return fd;
}

JNIEXPORT jint JNICALL
Java_com_baystudio_droide_core_NativeFilePublication_openDirectoryRelative(JNIEnv *env, jclass type, jint root, jbyteArray bytes) {
    (void)type;
    char relative[PATH_MAX]; char *leaf = NULL;
    int error = copy_path(env, bytes, relative);
    if (error != 0) return -error;
    int parent = open_child_parent(root, relative, &leaf);
    if (parent < 0) return parent;
    int fd = openat(parent, leaf, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    error = errno;
    close(parent);
    return fd >= 0 ? fd : -(error > 0 ? error : EIO);
}

JNIEXPORT jlong JNICALL
Java_com_baystudio_droide_core_NativeFilePublication_createOrdinaryRelative(JNIEnv *env, jclass type, jint root, jbyteArray bytes) {
    (void)type;
    char relative[PATH_MAX]; char *leaf = NULL;
    int error = copy_path(env, bytes, relative);
    if (error != 0) return -error;
    int parent = open_child_parent(root, relative, &leaf);
    if (parent < 0) return parent;
    int fd = openat(parent, leaf, O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    error = errno;
    if (fd < 0) { close(parent); return -(error > 0 ? error : EIO); }
    return ((jlong)(uint32_t)parent << 32) | (jlong)(uint32_t)fd;
}

JNIEXPORT jlong JNICALL
Java_com_baystudio_droide_core_NativeFilePublication_createDirectoryRelative(JNIEnv *env, jclass type, jint root, jbyteArray bytes) {
    (void)type;
    char relative[PATH_MAX]; char *leaf = NULL;
    int error = copy_path(env, bytes, relative);
    if (error != 0) return (jlong)(uint32_t)error;
    int parent = open_child_parent(root, relative, &leaf);
    if (parent < 0) return (jlong)(uint32_t)(-parent);
    if (mkdirat(parent, leaf, 0700) != 0) {
        error = errno;
        close(parent);
        return (jlong)(uint32_t)(error > 0 ? error : EIO);
    }
    error = fsync(parent) == 0 ? 0 : (errno > 0 ? errno : EIO);
    close(parent);
    return ((jlong)1 << 32) | (jlong)(uint32_t)error;
}

JNIEXPORT jint JNICALL
Java_com_baystudio_droide_core_NativeFilePublication_kindRelative(JNIEnv *env, jclass type, jint root, jbyteArray bytes) {
    (void)type;
    char relative[PATH_MAX]; char *leaf = NULL;
    int error = copy_path(env, bytes, relative);
    if (error != 0) return -error;
    int parent = open_child_parent(root, relative, &leaf);
    if (parent < 0) return parent;
    struct stat attrs;
    int result = fstatat(parent, leaf, &attrs, AT_SYMLINK_NOFOLLOW);
    error = errno;
    close(parent);
    if (result != 0) return -(error > 0 ? error : EIO);
    return S_ISREG(attrs.st_mode) ? 1 : S_ISDIR(attrs.st_mode) ? 2 : S_ISLNK(attrs.st_mode) ? 3 : 4;
}

JNIEXPORT jint JNICALL
Java_com_baystudio_droide_core_NativeFilePublication_readDescriptor(JNIEnv *env, jclass type, jint fd, jbyteArray bytes, jint offset, jint length) {
    (void)type;
    if (bytes == NULL || offset < 0 || length < 0) return -(EINVAL + 1);
    jsize capacity = (*env)->GetArrayLength(env, bytes);
    if (offset > capacity || length > capacity - offset) return -(EINVAL + 1);
    if (length == 0) return 0;
    unsigned char buffer[64 * 1024];
    size_t requested = (size_t)length < sizeof(buffer) ? (size_t)length : sizeof(buffer);
    ssize_t count = read(fd, buffer, requested);
    if (count < 0) return -((errno > 0 ? errno : EIO) + 1);
    if (count == 0) return -1;
    (*env)->SetByteArrayRegion(env, bytes, offset, (jsize)count, (const jbyte *)buffer);
    return (*env)->ExceptionCheck(env) ? -(EIO + 1) : (jint)count;
}

JNIEXPORT jint JNICALL
Java_com_baystudio_droide_core_NativeFilePublication_writeDescriptor(JNIEnv *env, jclass type, jint fd, jbyteArray bytes, jint offset, jint length) {
    (void)type;
    if (bytes == NULL || offset < 0 || length < 0) return -(EINVAL + 1);
    jsize capacity = (*env)->GetArrayLength(env, bytes);
    if (offset > capacity || length > capacity - offset) return -(EINVAL + 1);
    if (length == 0) return 0;
    unsigned char buffer[64 * 1024];
    size_t requested = (size_t)length < sizeof(buffer) ? (size_t)length : sizeof(buffer);
    (*env)->GetByteArrayRegion(env, bytes, offset, (jsize)requested, (jbyte *)buffer);
    if ((*env)->ExceptionCheck(env)) return -(EIO + 1);
    ssize_t count = write(fd, buffer, requested);
    return count >= 0 ? (jint)count : -((errno > 0 ? errno : EIO) + 1);
}

JNIEXPORT jint JNICALL
Java_com_baystudio_droide_core_NativeFilePublication_forceFileDescriptor(JNIEnv *env, jclass type, jint fd, jint mode) {
    (void)env; (void)type;
    if (mode != -1 && mode != 0600 && mode != 0644 && mode != 0755) return EINVAL;
    struct stat attrs;
    if (fstat(fd, &attrs) != 0) return errno > 0 ? errno : EIO;
    if (!S_ISREG(attrs.st_mode)) return EINVAL;
    if (mode != -1 && fchmod(fd, (mode_t)mode) != 0) return errno > 0 ? errno : EIO;
    return fsync(fd) == 0 ? 0 : (errno > 0 ? errno : EIO);
}

JNIEXPORT jlongArray JNICALL
Java_com_baystudio_droide_core_NativeFilePublication_changedTimeDescriptor(JNIEnv *env, jclass type, jint fd) {
    (void)type;
    struct stat attrs;
    jlong values[3] = {0, 0, 0};
    if (fstat(fd, &attrs) != 0) values[0] = errno > 0 ? errno : EIO;
    else { values[1] = (jlong)attrs.st_ctim.tv_sec; values[2] = (jlong)attrs.st_ctim.tv_nsec; }
    jlongArray result = (*env)->NewLongArray(env, 3);
    if (result != NULL) (*env)->SetLongArrayRegion(env, result, 0, 3, values);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_baystudio_droide_core_NativeFilePublication_moveNoReplaceAnchored(
        JNIEnv *env, jclass type, jint source_root, jbyteArray source_bytes, jint target_root, jbyteArray target_bytes) {
    (void)type;
    char source[PATH_MAX], target[PATH_MAX]; char *source_leaf = NULL, *target_leaf = NULL;
    int error = copy_path(env, source_bytes, source);
    if (error == 0) error = copy_path(env, target_bytes, target);
    if (error != 0) return error;
    int source_parent = open_child_parent(source_root, source, &source_leaf);
    if (source_parent < 0) return -source_parent;
    int target_parent = open_child_parent(target_root, target, &target_leaf);
    if (target_parent < 0) { close(source_parent); return -target_parent; }
    error = kernel_move_no_replace(source_parent, source_leaf, target_parent, target_leaf);
    close(target_parent); close(source_parent);
    return error;
}

JNIEXPORT jlong JNICALL
Java_com_baystudio_droide_core_NativeFilePublication_moveNoReplaceAnchoredDurable(
        JNIEnv *env, jclass type, jint source_root, jbyteArray source_bytes, jint target_root, jbyteArray target_bytes) {
    (void)type;
    char source[PATH_MAX], target[PATH_MAX]; char *source_leaf = NULL, *target_leaf = NULL;
    int error = copy_path(env, source_bytes, source);
    if (error == 0) error = copy_path(env, target_bytes, target);
    if (error != 0) return (jlong)(uint32_t)error;
    int source_parent = open_child_parent(source_root, source, &source_leaf);
    if (source_parent < 0) return (jlong)(uint32_t)(-source_parent);
    int target_parent = open_child_parent(target_root, target, &target_leaf);
    if (target_parent < 0) { close(source_parent); return (jlong)(uint32_t)(-target_parent); }
    jlong receipt = kernel_move_no_replace_durable(source_parent, source_leaf, target_parent, target_leaf);
    close(target_parent); close(source_parent);
    return receipt;
}

/* Descriptor-scoped export ownership: unrelated same-inode closes must not release it.
 * Nonblocking acquisition; never change existing POSIX metadata leases used elsewhere. */
JNIEXPORT jint JNICALL
Java_com_baystudio_droide_core_NativeFilePublication_exportDescriptorLock(JNIEnv *env, jclass type, jint fd, jboolean acquire) {
    (void)env; (void)type;
    if (fd < 0) return EBADF;
    if (acquire) {
        int flags = fcntl(fd, F_GETFD);
        if (flags < 0) return errno > 0 ? errno : EIO;
        if ((flags & FD_CLOEXEC) == 0) return EINVAL;
    }
    int operation = acquire ? (LOCK_EX | LOCK_NB) : LOCK_UN;
    /* EWOULDBLOCK/EINTR are refusal, never success or a POSIX-lock fallback.
     * Caller closes its owning descriptor on failure, so no lease leaks. */
    if (flock(fd, operation) == 0) return 0;
    return errno > 0 ? errno : EIO;
}
