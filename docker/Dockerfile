# HOW THE RELEASED BINARY IS BUILT: `qits`, a fully static musl native image that runs on any Linux,
# glibc or musl (alpine) alike. This repository ships no image. The last stage is a `FROM scratch`
# holding the one file, and a build is an `--output type=local` of it.
#
# The shape is qits-artifacts-cli's, which is qits-ci-daemon's: one build stage on the musl
# toolchain, two `FROM scratch` export stages (sbom, then binary, which stays the default target),
# the `ldd` check on the same RUN as the compile, and the CycloneDX document from the same mvnw call,
# so a release after a green fold is cache hits. Those two files carry the long reasons.
#
# THE TOOLCHAIN IS A STAGE OF THIS FILE, NOT A REGISTRY TAG. The first release request named
# `graalvmce-musl-builder:jdk-25` in the platform registry, as qits-artifacts-cli does, and failed
# with "not found": only qits-ci-daemon's pipelines push that tag, and the bootstrap builds the image
# into the host's image store, not into the registry. So after a cold boot the tag is missing until
# qits-ci-daemon next folds or releases. Built here, the toolchain needs no other repository's run.
# Nothing is pushed: BuildKit uses the stage directly, and no second pipeline writes that tag.
#
# The `tarballs` and `toolchain` stages are qits-ci-daemon's docker/Dockerfile.musl-builder, line
# for line; that file says why GraalVM CE and not Mandrel, why a musl-gcc shim, and why ADD. Keep
# them identical: the platform builder then shares their cache with qits-ci-daemon's builds. A
# toolchain bump there is a bump here.
#
# THE TARBALL URLS ARE THE RECIPES'. The defaults name registry.dev.localhost, an edge vhost that
# the platform builder cannot reach for an ADD, so both recipes pass the in-network Maven root, as
# qits-ci-daemon's do. The bootstrap seeds both tarballs into the store on every boot.
#
# A hand build off the platform passes the upstream URLs and base:
#
#     docker build --target binary --output type=local,dest=out -f docker/Dockerfile \
#       --build-arg TOOLCHAIN_BASE=quay.io/quarkus/ubi10-quarkus-graalvmce-builder-image:jdk-25 \
#       --build-arg MUSL_URL=https://more.musl.cc/11.2.1/x86_64-linux-musl/x86_64-linux-musl-native.tgz \
#       --build-arg ZLIB_URL=https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.gz .
#
# or skips the toolchain stages with a toolchain image built from qits-ci-daemon's file:
#
#     docker build --build-arg BUILDER_IMAGE=qits/graalvmce-musl-builder:jdk-25 ...
#
# The host build (`./mvnw package -Dnative`) stays glibc-linked: it needs no musl toolchain, because
# the static flags are added here only.
ARG MUSL_URL=http://registry.dev.localhost:8080/artifacts/maven/maven/eu/wohlben/qits/toolchain/x86_64-linux-musl-native/11.2.1/x86_64-linux-musl-native-11.2.1.tgz
ARG ZLIB_URL=http://registry.dev.localhost:8080/artifacts/maven/maven/eu/wohlben/qits/toolchain/zlib/1.3.1/zlib-1.3.1.tar.gz
# The platform builder maps mirror.dev.localhost:8080 to qits-platform-mirror (qits-containers).
ARG TOOLCHAIN_BASE=mirror.dev.localhost:8080/quay/quarkus/ubi10-quarkus-graalvmce-builder-image:jdk-25
# A stage name by default. An image name instead skips the two toolchain stages.
ARG BUILDER_IMAGE=toolchain

FROM scratch AS tarballs
# An ARG before the first FROM is global; a stage that reads one names it again.
ARG MUSL_URL
ARG ZLIB_URL
ADD $MUSL_URL /musl.tgz
ADD $ZLIB_URL /zlib.tar.gz

FROM ${TOOLCHAIN_BASE} AS toolchain
USER root

RUN --mount=type=bind,from=tarballs,target=/tarballs \
    mkdir -p /opt/musl \
 && tar -xzf /tarballs/musl.tgz -C /opt/musl --strip-components=1 \
 && /opt/musl/bin/x86_64-linux-musl-gcc --version

# Only the target-prefixed names on PATH, so the system gcc stays the default.
RUN mkdir -p /opt/musl-shim \
 && for f in /opt/musl/bin/x86_64-linux-musl-*; do ln -s "$f" "/opt/musl-shim/$(basename "$f")"; done
ENV PATH=/opt/musl-shim:$PATH

# native-image links `-lz`, so zlib is built for the musl target into the same prefix.
RUN --mount=type=bind,from=tarballs,target=/tarballs \
    mkdir -p /tmp/zlib \
 && tar -xzf /tarballs/zlib.tar.gz -C /tmp/zlib --strip-components=1 \
 && cd /tmp/zlib \
 && CC=/opt/musl/bin/x86_64-linux-musl-gcc ./configure --static --prefix=/opt/musl \
 && make -j"$(nproc)" && make install \
 && rm -rf /tmp/zlib

FROM ${BUILDER_IMAGE} AS build
# A toolchain image built from qits-ci-daemon's file ends on user 1001, and the context arrives
# owned by root.
USER root
WORKDIR /qits-build

# .dockerignore keeps target/, .git and out/ out, so a stale host build cannot shadow this one.
COPY . .

# Maven Central through the platform mirror's in-network route when CI passes it. Empty (the
# default, and every hand build) switches off .qits-maven-settings.xml's profile: Central directly.
ARG QITS_MAVEN_CENTRAL_URL=
ENV QITS_MAVEN_CENTRAL_URL=$QITS_MAVEN_CENTRAL_URL

# - additional-build-args-APPEND: the pom's native profile sets additional-build-args (Pkce's
#   run-time initialisation), and a second value for that key would replace it, not add to it.
# - container-build=false: this is already the container Quarkus would start.
# - native-image-xmx=4g bounds the compile, as the sibling daemons do.
# - `ldd`, not `file`: GraalVM writes a static-pie executable, and `file` never says "statically
#   linked". A glibc-linked binary does not start on an alpine image, so the check fails the build.
# - makeBom on the same call: the document describes the very resolve that made the bytes.
#   cyclonedx.skipNotDeployed=false because nothing here is deployed to a Maven repository, and the
#   plugin skips such a project quietly (measured on qits-artifacts-service's first release).
RUN ./mvnw -B -ntp -s .qits-maven-settings.xml package -Dnative -DskipTests \
      -Dquarkus.native.container-build=false \
      -Dquarkus.native.additional-build-args-append=--static,--libc=musl \
      -Dquarkus.native.native-image-xmx=4g \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false \
 && ldd target/qits 2>&1 | grep -qE "statically linked|not a dynamic executable"

# `qits tui` in the binary, which is the only place JLine's provider choice can be proven: the tests
# run on a JVM, where every provider works. Started with no terminal — which is what a RUN is — it
# must say so and exit 2. The failure this catches is a binary that waits for a key nobody can
# press, so it runs under a timeout: a build that hangs here would otherwise hang forever.
RUN set -eu; \
    out=$(timeout 60 ./target/qits tui < /dev/null 2>&1 || echo "exit=$?"); \
    printf '%s\n' "$out"; \
    printf '%s' "$out" | grep -q "needs an interactive terminal"; \
    printf '%s' "$out" | grep -q "exit=2"

# The CycloneDX document, for the release step's SBOM PUT. Before `binary`, so that stays the default.
FROM scratch AS sbom
COPY --from=build /qits-build/target/sbom.json /sbom.json

# The artifact: `--output type=local,dest=out` writes out/qits.
FROM scratch AS binary
COPY --from=build /qits-build/target/qits /qits
