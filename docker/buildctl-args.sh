# THE BUILD ARGUMENTS EVERY `buildctl build` OF docker/Dockerfile PASSES, worked out once. SOURCED,
# never executed — `. docker/buildctl-args.sh` from the repository root — by the two build steps of
# .config/qits/release.yml and by tapes/prepare.sh, so the builds they make are the same build,
# argument for argument: that is what makes the second and every later one cache hits on the native
# compile. POSIX sh (a step's shell is sh where the image has no bash) and `set -u` safe. It sets:
#
#   qits_domain   the platform's public domain. REQUIRED: qits-ci injects $QITS_DOMAIN into every
#                 step unconditionally, and a missing value fails loudly rather than falling back
#                 to one install's domain
#   maven_root    the platform's own Maven, under that domain
#   musl_url      the Dockerfile's MUSL_URL default, re-rooted at maven_root
#   zlib_url      the same for ZLIB_URL
#   tarball_auth  empty, or the `--secret` that authenticates the tarball ADD on the EDGE plane
#
# ON THE EDGE PLANE THE TARBALL `ADD` NEEDS THE RUN'S TOKEN (qits-441): there $maven_root is the
# public registry, which answers an anonymous read 401. BuildKit's HTTP source sends a session secret
# named HTTP_AUTH_TOKEN_<url host> as `Authorization: Bearer`, so the secret is keyed by exactly that
# host and passed only when the step holds $QITS_TOKEN, which qits-ci sets on the EDGE plane alone.
# On the INTERNAL plane nothing is passed and the in-network read stays anonymous, as qits-ci
# intends. docker/Dockerfile's header has the long form.
#
# THE MAVEN STAGE'S CREDENTIAL IS NOT HERE: it is unconditional, the two files qits-ci's prelude
# writes (/tmp/qits-client-id, /tmp/qits-client-secret), which every invocation names itself.
qits_domain=${QITS_DOMAIN:?QITS_DOMAIN is not set}
maven_root="https://registry.qits.$qits_domain/artifacts/maven/maven"
musl_url=$(sed -n 's/^ARG MUSL_URL=//p' docker/Dockerfile)
zlib_url=$(sed -n 's/^ARG ZLIB_URL=//p' docker/Dockerfile)
: "${musl_url:?docker/Dockerfile declares no ARG MUSL_URL}"
: "${zlib_url:?docker/Dockerfile declares no ARG ZLIB_URL}"
musl_url="$maven_root${musl_url#*/artifacts/maven/maven}"
zlib_url="$maven_root${zlib_url#*/artifacts/maven/maven}"
tarball_auth=
if [ -n "${QITS_TOKEN:-}" ]; then
  tarball_host=$(printf '%s\n' "$maven_root" | sed -E 's|^[A-Za-z]+://||; s|[/:?].*$||')
  tarball_auth="--secret id=HTTP_AUTH_TOKEN_$tarball_host,env=QITS_TOKEN"
fi
