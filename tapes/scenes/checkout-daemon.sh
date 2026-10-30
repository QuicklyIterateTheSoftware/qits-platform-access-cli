# The checkout-daemon tapes' scene, sourced while hidden: a repository on a "git host" — a plain
# directory whose path ends /git/<project id>/<repository>, as the platform's origins do, so the
# daemon reads the repository off it — with the two releases the fake platform names
# (2026.101.90000, then 2026.101.101400), and a clone of it at main to hold. Fixed names and dates,
# so every commit has the same id on every play.
export GIT_AUTHOR_NAME=tapes GIT_AUTHOR_EMAIL=tapes@example.test GIT_AUTHOR_DATE=2026-01-01T09:00:00Z
export GIT_COMMITTER_NAME=tapes GIT_COMMITTER_EMAIL=tapes@example.test GIT_COMMITTER_DATE=2026-01-01T09:00:00Z
qits_scene=/tmp/qits-tapes/checkout
qits_upstream=$qits_scene/git/00000000-0000-4000-8000-000000000001/contract-service
rm -rf "$qits_scene"
git init -q -b main "$qits_upstream"
echo "contract-service" > "$qits_upstream/README.md"
git -C "$qits_upstream" add README.md
git -C "$qits_upstream" commit -qm "the first release"
git -C "$qits_upstream" tag -a 2026.101.90000 -m "release 2026.101.90000"
echo "export invoices" > "$qits_upstream/shipped.txt"
git -C "$qits_upstream" add shipped.txt
git -C "$qits_upstream" commit -qm "export invoices"
git -C "$qits_upstream" tag -a 2026.101.101400 -m "release 2026.101.101400"
echo "work in progress" > "$qits_upstream/next.txt"
git -C "$qits_upstream" add next.txt
git -C "$qits_upstream" commit -qm "work on main, not released"
git clone -q "$qits_upstream" "$qits_scene/contract-service"
cd "$qits_scene/contract-service"
unset qits_scene qits_upstream
