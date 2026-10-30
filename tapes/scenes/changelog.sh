# The changelog tape's scene, sourced while hidden: what qits-maintenance's bump step hands
# `qits changelog bump-message` — the changes it applied, its body, and the event that started it
# (QITS_EVENT_PAYLOAD). No change carries a changelog, so the message is the subject and the body
# and nothing is fetched: the docs store is https://registry.qits.$QITS_DOMAIN, which no
# QITS_URL_* reaches, so the fake platform could not answer it.
qits_scene=/tmp/qits-tapes/changelog
rm -rf "$qits_scene"
mkdir -p "$qits_scene"
printf 'maven\teu.wohlben.qits:qits-projects-golden-masters\nmaven\teu.wohlben.qits:qits-ci-golden-masters\n' \
  > "$qits_scene/applied.tsv"
printf -- '- maven eu.wohlben.qits:qits-projects-golden-masters 2026.1030.143017 -> 2026.1031.90000 (pom.xml)\n- maven eu.wohlben.qits:qits-ci-golden-masters 2026.1026.163551 -> 2026.1031.90100 (pom.xml)\n' \
  > "$qits_scene/body.txt"
export QITS_EVENT_PAYLOAD='{"changes":[]}'
cd "$qits_scene"
unset qits_scene
