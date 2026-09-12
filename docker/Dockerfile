# HOW THE RELEASED BINARY IS BUILT: `qits`, a fully static musl native image that runs on any Linux,
# glibc or musl (alpine) alike. This repository ships no image. The last stage is a `FROM scratch`
# holding the one file, and a build is an `--output type=local` of it.
#
# The shape is qits-artifacts-cli's, which is qits-ci-daemon's: one build stage in the musl toolchain
# image, two `FROM scratch` export stages (sbom, then binary, which stays the default target), the
# `ldd` check on the same RUN as the compile, and the CycloneDX document from the same mvnw call, so
# a release after a green fold is cache hits. Those two files carry the long reasons.
#
# THE TOOLCHAIN IMAGE IS NAMED, NOT BUILT. qits-ci-daemon owns docker/Dockerfile.musl-builder, and its
# pipelines push `graalvmce-musl-builder:jdk-25` on every fold and every release. A copy here would be
# a second spelling of one toolchain and a second pipeline pushing the same tag. The recipes pass the
# registry's address of that tag. The default below is the tag a developer builds by hand from that
# file, so a local build needs no platform:
#
#     docker build --target binary --output type=local,dest=out -f docker/Dockerfile .
#
# The host build (`./mvnw package -Dnative`) stays glibc-linked: it needs no musl toolchain, because
# the static flags are added here only.
ARG BUILDER_IMAGE=qits/graalvmce-musl-builder:jdk-25

FROM ${BUILDER_IMAGE} AS build
# The base image ends on user 1001, and the context arrives owned by root.
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

# The CycloneDX document, for the release step's SBOM PUT. Before `binary`, so that stays the default.
FROM scratch AS sbom
COPY --from=build /qits-build/target/sbom.json /sbom.json

# The artifact: `--output type=local,dest=out` writes out/qits.
FROM scratch AS binary
COPY --from=build /qits-build/target/qits /qits
