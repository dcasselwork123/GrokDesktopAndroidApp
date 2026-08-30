#include <errno.h>
#include <unistd.h>

/*
 * Become session/process-group leader so Os.kill(-pid) reaches Node and
 * non-detached grok children. EPERM means we already are the leader.
 */
int main(int argc, char **argv) {
    if (argc < 2) {
        return 127;
    }
    if (setsid() == -1 && errno != EPERM) {
        /* still exec; wrapper pid must become Node */
    }
    execv(argv[1], argv + 1);
    return 127;
}
