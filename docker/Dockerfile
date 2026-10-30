# Builds the Linux desktop installers (.deb / .rpm) of this repo inside a container, so a release
# can be produced on any machine without GitHub CI. The repository itself is NOT baked into the
# image: compose bind-mounts it at run time (see compose.yaml at the repo root). This keeps the
# build context tiny and means the image only needs the toolchain the build requires.
#
# Usage: docker compose build build-x64 build-arm64     (or just run a service - compose builds it)
# Docs:  docker/README.md
#
# Why ubuntu:22.04 and nothing newer: the launcher jpackage generates is linked against the
# builder's glibc, so installers built on a newer glibc refuse to install on older distros.
# The release workflow (.github/workflows/desktop-release.yml) builds Linux on ubuntu-22.04
# runners for exactly this reason - pinning the image to 22.04 (glibc 2.35) keeps local builds
# byte-compatible with CI.
FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8

# rpm     -> rpmbuild, needed to package .rpm (the workflow installs the same two packages)
# fakeroot-> fake root ownership while packaging .deb, so the packager needs no real root
# binutils-> objcopy: jlink runs it while creating the stripped runtime image (the CI runner
#           images ship it preinstalled; this minimal base does not)
# libgl1, libfontconfig1 -> what the Skia native library (skiko) dlopens; :shared:jvmTest renders
#           PDFs with it, and the CI runner images ship these preinstalled
# curl/gnupg/ca-certificates -> fetch the Corretto apt repository key below
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl gnupg rpm fakeroot binutils libgl1 libfontconfig1 \
 && rm -rf /var/lib/apt/lists/*

# Amazon Corretto 21, on purpose. gradle/gradle-daemon-jvm.properties pins the Gradle daemon JVM
# to vendor=AMAZON, version 21 - and that criteria wins over JAVA_HOME. Compose Desktop packages
# with the jpackage of the JVM running the Gradle daemon (see the comment in
# tools/build-macos-intel.sh), so this JDK is the packaging toolchain itself. Shipping Corretto
# here means the daemon criteria matches a local JVM; any other vendor makes Gradle silently
# download a ~200 MB JDK into the cache volume on the first build, per architecture.
RUN curl -fsSL https://apt.corretto.aws/corretto.key \
      | gpg --dearmor -o /usr/share/keyrings/corretto.gpg \
 && echo "deb [signed-by=/usr/share/keyrings/corretto.gpg] https://apt.corretto.aws stable main" \
      > /etc/apt/sources.list.d/corretto.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends java-21-amazon-corretto-jdk \
 && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto

# compose.yaml overrides the running uid/gid at run time (user:) so files written into the
# bind-mounted repo stay owned by the host user. That uid has no passwd entry inside the
# container, which entrypoint.sh deals with; the image user exists only for the default case.
RUN useradd -m -u 1000 -s /bin/bash builder

# The Gradle cache volume is mounted here at run time. chmod 0777 because the mounted volume
# inherits these permissions, and the runtime uid is whatever the host user happens to be.
# The volume is docker-managed - it is never reachable from the host - so world-writable is fine.
RUN mkdir /cache && chmod 0777 /cache

COPY entrypoint.sh /usr/local/bin/entrypoint
RUN chmod 0755 /usr/local/bin/entrypoint

ENV HOME=/home/builder
WORKDIR /workspace
ENTRYPOINT ["entrypoint"]
