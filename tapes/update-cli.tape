# qits update, on the command line. With a workspace token (QITS_TOKEN) it refuses (exit 2): the
# workspace's image decides which qits it has. On a workstation - the scene
# (tapes/scenes/update.sh) is a copy of this qits in a directory of its own and a session file -
# it asks the artifacts store for the newest qits (2100.1.0, its golden master), downloads it,
# checks its length, digest and `-V`, and replaces the copy, which then answers 2100.1.0. The
# "binary" the fake store serves is a two-line script that answers -V, a fixture
# (src/test/resources/tapes/released-qits.sh). The version this build was released as changes with
# every release, so the tape shows it as <this build>.
#
# Against the fake platform tapes/prepare.sh starts (FakePlatformMain). Each command is typed while
# hidden and shown once its prompt is back, so every frame of the text golden is a finished screen
# and the golden is the same on every play.
Set Shell bash
Set FontFamily "DejaVu Sans Mono"
Set FontSize 14
Set Width 930
Set Height 504
Set Padding 0
Set TypingSpeed 10ms
Set WaitTimeout 60s
Env PROMPT_COMMAND "sleep 2"
Env TZ "UTC"
Env LANG "C.UTF-8"
Env QITS_TOKEN "qits-tapes-not-a-token"

# The shell, with a fixed prompt. bash's first prompt waits for PROMPT_COMMAND's sleep, so the
# frames vhs takes while it applies the settings above are all of an empty screen.
Hide
Type "unset PROMPT_COMMAND; export PS1='$ '; clear"
Enter
Wait /^\$$/

Hide
Type "source tapes/scenes/update.sh"
Enter
Wait /^\$$/

Hide
Type "./qits update"
Enter
Wait /^\$$/
Type "echo $?"
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type "unset QITS_TOKEN; export XDG_CONFIG_HOME=/tmp/qits-tapes/update/config"
Enter
Wait /^\$$/

Hide
Type "clear"
Enter
Wait /^\$$/
Type `./qits update --check | sed "s/$this_build/<this build>/"`
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type `./qits update | sed "s/$this_build/<this build>/"`
Enter
Wait /^\$$/
Type "./qits -V"
Enter
Wait /^\$$/
Show
Sleep 2s
