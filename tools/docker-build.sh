#!/usr/bin/env bash
#
# tools/docker-build.sh
#
# Builds the desktop Linux installers (.deb / .rpm) in Docker, independently of GitHub CI.
# Thin wrapper around the compose services in compose.yaml: it fills in the pieces compose
# cannot know itself - the host uid/gid (so bind-mounted files stay host-owned), the SELinux
# mount flag, and the dist/ directory (docker would create a root-owned one if missing).
#
#   tools/docker-build.sh               x64 release installers -> dist/
#   tools/docker-build.sh arm64         arm64 release installers (QEMU emulated on x86 hosts: slow)
#   tools/docker-build.sh all           both architectures, sequentially - they share one repo
#                                       tree, so parallel runs would fight over build/ dirs
#   tools/docker-build.sh shell         interactive bash inside the build environment
#   tools/docker-build.sh -- TASKS...   pass-through: any gradle invocation in the same
#                                       environment, e.g. `tools/docker-build.sh -- :shared:jvmTest`
#
# arm64 on an x86_64 host needs QEMU user emulation once:
#   docker run --privileged --rm tonistiigi/binfmt --install arm64

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null || die "docker not found - install it first"
docker compose version >/dev/null 2>&1 || die "docker compose plugin not available"

# Files written into the repo and dist/ must belong to the host user, not to docker's default.
export GITWRITER_UID="$(id -u)"
export GITWRITER_GID="$(id -g)"

# On SELinux systems (Fedora) containers are denied writes into bind mounts unless the mount is
# relabeled; :Z does that for the repo and for dist/.
if [ -d /sys/fs/selinux ]; then
    export COMPOSE_MOUNT_OPTS=:Z
fi

mkdir -p "$ROOT/dist"

# Argument parsing: an optional mode (x64 | arm64 | all | shell), then an optional `--` followed
# by gradle arguments. Everything after the first unknown word is treated as pass-through.
TARGET=x64
TASKS=()
while [ "$#" -gt 0 ]; do
    case "$1" in
        x64|arm64|all) TARGET="$1"; shift ;;
        shell)         TARGET="shell"; shift ;;
        --)            shift; TASKS=("$@"); break ;;
        *)             TASKS=("$@"); break ;;
    esac
done

if [ "$TARGET" = "shell" ]; then
    log "Opening a shell in the x64 build container"
    exec docker compose -f "$ROOT/compose.yaml" run --rm build-x64 shell
fi

case "$TARGET" in
    x64)   SERVICES=(build-x64) ;;
    arm64) SERVICES=(build-arm64) ;;
    all)   SERVICES=(build-x64 build-arm64) ;;
esac

for service in "${SERVICES[@]}"; do
    if [ "$service" = "build-arm64" ] && [ "$(uname -m)" != "aarch64" ]; then
        if [ ! -e /proc/sys/fs/binfmt_misc/qemu-aarch64 ]; then
            log "arm64 runs under QEMU emulation, which needs binfmt (one-time):"
            log "  docker run --privileged --rm tonistiigi/binfmt --install arm64"
        fi
        log "Building arm64 - this is emulated and can take 10-30x longer than x64"
    fi
    log "Building in $service"
    docker compose -f "$ROOT/compose.yaml" run --rm "$service" "${TASKS[@]}"
done

log "Done. Installers are in dist/"
