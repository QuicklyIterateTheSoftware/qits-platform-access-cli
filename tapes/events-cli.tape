# qits events, on the command line: the live stream, filtered to two event names, stopped with
# Ctrl+C; the past hour of the log as lines and as JSON (`events query`, over a window given as two
# instants, so it does not move with the clock); and a filter the command refuses before it
# connects (exit 2).
#
# The stream's notes (connected, stopped) go to stderr with a wall-clock stamp in front, which no
# two plays share, so the tape sends them to a file and shows them after the stop without the
# stamp. The stream's events are a fixture (src/test/resources/tapes/events-stream.json); the
# log's answer is qits-events' golden masters.
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
Type "clear"
Enter
Wait /^\$$/
Type "qits events --filter DeploymentActive,ReleaseRequestStateChanged 2> /tmp/qits-tapes/events.log"
Enter
Wait+Screen /000000000114/
Show
Sleep 2s

Hide
Ctrl+C
Wait /\$$/
Show
Sleep 2s

Hide
Type "cut -d' ' -f2- /tmp/qits-tapes/events.log"
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits events query --since 2026-01-01T00:00:00Z --until 2026-01-01T01:00:00Z"
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits events query --since 2026-01-01T00:00:00Z --until 2026-01-01T01:00:00Z -o json"
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits events --filter 'Build*'"
Enter
Wait /^\$$/
Type "echo $?"
Enter
Wait /^\$$/
Show
Sleep 2s
