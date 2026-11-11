#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <unistd.h>

/*
 * 极小 launcher：把网关二进制 exec 成同一个进程，并设置 parent-death signal。
 *
 * 为什么需要它：Android 的 ActivityManager 杀掉 App 进程时，ProcessBuilder 起的
 * 子进程可能被重新托孤而继续占着 3065。设置 PR_SET_PDEATHSIG 后，父进程一死，
 * 内核就给网关发 SIGTERM，端口随进程退出释放；下次启动不会撞自己的旧实例。
 *
 * 用法：libagent2api_launcher.so /path/to/libagent2api_server.so
 */
int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <server-binary> [args...]\n", argv[0]);
        return 64;
    }
    if (prctl(PR_SET_PDEATHSIG, SIGTERM) != 0) {
        fprintf(stderr, "prctl(PR_SET_PDEATHSIG) failed: errno=%d\n", errno);
    }
    if (getppid() == 1) {
        fprintf(stderr, "parent already gone\n");
        return 1;
    }
    execv(argv[1], &argv[1]);
    fprintf(stderr, "execv(%s) failed: errno=%d\n", argv[1], errno);
    return 127;
}