#define _GNU_SOURCE
#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>
#include <sys/resource.h>

/* Add an outer seccomp policy like the Android app sandbox: only the
 * deliberately invalid syscall PRoot uses as its avoider is trapped.
 * This adds restrictions to a child process; it never removes a filter. */
int main(int argc, char **argv) {
    if (argc < 4) return 64;
    long avoider = strtol(argv[1], NULL, 10);
    struct rlimit limit = {0, 0};
    setrlimit(RLIMIT_CORE, &limit);
    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)(int32_t)avoider, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {sizeof(filter) / sizeof(filter[0]), filter};
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) ||
        prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program)) {
        perror("cannot add regression filter");
        return 65;
    }
    execv(argv[2], argv + 2);
    perror("exec regression command");
    return 66;
}
