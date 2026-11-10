#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/syscall.h>

int main(int argc, char **argv) {
    if (argc != 3) return 64;
    if (!strcmp(argv[1], "errors")) {
        char path[4096];
        errno = 0;
        if (syscall(SYS_getcwd, path, 1) != -1 || errno != ERANGE) {
            printf("small getcwd: errno=%d expected=%d\n", errno, ERANGE); return 1;
        }
        errno = 0;
        if (syscall(SYS_getcwd, path, 0) != -1 || errno != EINVAL) {
            printf("zero getcwd: errno=%d expected=%d\n", errno, EINVAL); return 2;
        }
        errno = 0;
        if (fchdir(-1) != -1 || errno != EBADF) {
            printf("invalid fchdir: errno=%d expected=%d\n", errno, EBADF); return 3;
        }
        errno = 0;
        if (chdir("not-an-existing-directory") != -1 || errno != ENOENT) {
            printf("missing chdir: errno=%d expected=%d\n", errno, ENOENT); return 4;
        }
        errno = 0;
        if (syscall(-2, 0, 0, 0, 0, 0, 0) != -1 || errno != ENOSYS) {
            printf("unsupported syscall: errno=%d expected=%d\n", errno, ENOSYS); return 5;
        }
        if (!getcwd(path, sizeof(path)) || strcmp(path, argv[2])) return 6;
        puts("ERANGE/EINVAL/EBADF/ENOENT/ENOSYS preserved; cwd still usable");
        return 0;
    }
    if (!strcmp(argv[1], "fchdir")) {
        int fd = open(argv[2], O_RDONLY | O_DIRECTORY);
        if (fd < 0) { perror("open directory"); return 65; }
        errno = 0;
        int result = fchdir(fd);
        int error = errno;
        close(fd);
        printf("fchdir result=%d errno=%d (%s)\n", result, error, strerror(error));
        return result != 0;
    }
    char path[4096];
    errno = 0;
    /* glibc can walk parent directories after ENOSYS, hiding a broken
     * PRoot getcwd translation. Exercise both the syscall and libc paths. */
    char *result;
    if (!strcmp(argv[1], "getcwd")) {
        long count = syscall(SYS_getcwd, path, sizeof(path));
        if (count >= 0 && count != (long)strlen(argv[2]) + 1) {
            printf("getcwd byte count=%ld expected=%zu\n", count, strlen(argv[2]) + 1);
            return 1;
        }
        result = count < 0 ? NULL : path;
    } else if (!strcmp(argv[1], "getcwd-libc")) {
        result = getcwd(path, sizeof(path));
    } else {
        return 64;
    }
    int error = errno;
    printf("getcwd result=%s errno=%d (%s)\n", result ? path : "NULL", error, strerror(error));
    return !result || strcmp(path, argv[2]);
}
