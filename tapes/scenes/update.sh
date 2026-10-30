# The update tapes' scene, sourced while hidden: a copy of the qits under test in a directory of its
# own, which `qits update` may replace (never the copy prepare.sh put on PATH), and a workstation's
# session file beside it, because qits updates itself on a workstation only: with QITS_TOKEN the
# image decides which qits a workspace has. The session's token is no token (the fake reads none)
# and lasts until 2099, so nothing is refreshed. QITS_AUTO_UPDATE=off keeps the background check,
# which a workstation's qits starts before a command, out of the way of the one the tape runs.
qits_scene=/tmp/qits-tapes/update
rm -rf "$qits_scene"
mkdir -p "$qits_scene/config/qits"
cp "$(command -v qits)" "$qits_scene/qits"
cat > "$qits_scene/config/qits/t.json" << 'SESSION'
{"idpUrl":"https://idp.qits.example.test/idp","clientId":"qits-cli","accessToken":"qits-tapes-not-a-token","accessExpiresAt":"2099-01-01T00:00:00Z","refreshToken":"qits-tapes-not-a-refresh-token","refreshExpiresAt":"2099-01-01T00:00:00Z"}
SESSION
chmod 600 "$qits_scene/config/qits/t.json"
export QITS_AUTO_UPDATE=off
# The version this build was released as changes with every release, so the tape shows it as
# <this build>; the version the fake store names, 2100.1.0, is the same on every play.
this_build=$("$qits_scene/qits" -V)
cd "$qits_scene"
unset qits_scene
