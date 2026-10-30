#!/usr/bin/env bash
#
# Container entrypoint for local desktop installer builds; the Docker image is only the
# toolchain, this script wires it to the bind-mounted repository. See compose.yaml and
# docker/README.md at the repo root.
#
#   entrypoint            build the release installers (the task the release workflow runs)
#   entrypoint <args...>  pass args through to ./gradlew (e.g. :shared:jvmTest, --stop)
#   entrypoint shell      interactive bash inside the build environment, for debugging
#
# After a release packaging run, .deb/.rpm files are copied to /out with the same names the
# release workflow uses, so local and CI artifacts are indistinguishable.

set -euo pipefail

cd /workspace

# compose.yaml overrides the uid at run time with the host user's, and that uid has no passwd
# entry here, so the image's HOME is unwritable for it. Anything that needs HOME (gradle, the
# kotlin daemon) is pointed at GRADLE_USER_HOME explicitly by compose; /tmp is world-writable.
if ! getent passwd "$(id -u)" >/dev/null 2>&1; then
    export HOME=/tmp/home
    mkdir -p "$HOME"
fi

if [ "${1:-}" = "shell" ]; then
    shift
    exec /bin/bash "$@"
fi

# Default to exactly what .github/workflows/desktop-release.yml runs.
if [ "$#" -eq 0 ]; then
    set -- :desktopApp:packageReleaseDistributionForCurrentOS
fi

# Same parse as the workflow's plan job: the packaged version must match gradle.properties, the
# single source of truth for the version. desktopApp/build.gradle.kts reads the property itself,
# so no -Papp.version.number override is needed.
VERSION=$(grep -m1 '^app.version.number=' gradle.properties | cut -d= -f2 | tr -d '[:space:]')
if [ -z "$VERSION" ]; then
    echo "error: app.version.number is missing from gradle.properties" >&2
    exit 1
fi

# --project-cache-dir moves the project cache (configuration cache, file-watching state) out of
# the bind-mounted repo and into the arch-specific cache volume: container builds must not share
# that state with host builds. Bonus: it survives across `docker compose run` invocations.
# (If Gradle ever misbehaves with it, dropping this flag is safe - builds just get slower.)
./gradlew --project-cache-dir /cache/project "$@"

# packageReleaseDistributionForCurrentOS packages only the formats of the current OS, so in this
# Linux container that is always just .deb and .rpm - Msi/Exe/Dmg are skipped, same as on the CI
# Linux runners. Copy them to /out under the workflow's naming scheme.
case " $* " in
*"packageReleaseDistributionForCurrentOS"*)
    mkdir -p /out
    case "$(uname -m)" in
        x86_64)  LABEL=linux-x86_64 ;;
        aarch64) LABEL=linux-arm64 ;;
        *)       LABEL="linux-$(uname -m)" ;;
    esac
    find desktopApp/build/compose/binaries/main-release -type f \
        \( -name '*.deb' -o -name '*.rpm' \) -print0 |
    while IFS= read -r -d '' file; do
        target="/out/GitWriter-${VERSION}-${LABEL}.${file##*.}"
        cp "$file" "$target"
        echo "$(basename "$file") -> $(basename "$target")"
    done
    ls -lh /out
    ;;
esac
