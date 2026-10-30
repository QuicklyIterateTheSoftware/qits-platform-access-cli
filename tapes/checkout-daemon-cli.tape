# qits checkout-daemon, on the command line: a clone held at what its repository released. The
# scene (tapes/scenes/checkout-daemon.sh) is a repository on a stand-in git host - a directory
# whose path names the project and the repository, as the platform's origins do - with two
# releases and unreleased work on main, and a clone of it at main. The daemon finds the newest
# release in the event log (2026.101.90000) and checks it out, then hears of the next on the event
# stream (2026.101.101400) and checks that out too; Ctrl+C stops it. Its notes go to stderr with
# a wall-clock stamp, so the tape keeps them in a file and shows them after the stop without the
# stamp. The release events are fixtures; the commits have fixed names and dates, so their ids are
# the same on every play.
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
Type "source tapes/scenes/checkout-daemon.sh"
Enter
Wait /^\$$/

Hide
Type "git log --oneline --decorate"
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits checkout-daemon 2> /tmp/qits-tapes/checkout-daemon.log"
Enter
Wait+Screen /2026\.101\.101400/
Show
Sleep 2s

Hide
Ctrl+C
Wait /\$$/
Show
Sleep 2s

Hide
Type "cut -d' ' -f2- /tmp/qits-tapes/checkout-daemon.log"
Enter
Wait /^\$$/
Type "git log --oneline --decorate -1"
Enter
Wait /^\$$/
Show
Sleep 2s
