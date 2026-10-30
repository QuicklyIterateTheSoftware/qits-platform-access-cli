# qits changelog, on the command line: the commit message of a dependency-bump step, from the
# changes it applied and its body (the scene, tapes/scenes/changelog.sh); no change carries a
# changelog, so nothing is fetched. Then a missing --group, which it refuses (exit 1). There is no
# screen tape: it belongs in qits-maintenance's bump step.
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
Type "source tapes/scenes/changelog.sh"
Enter
Wait /^\$$/

Hide
Type "cat applied.tsv"
Enter
Wait /^\$$/
Type "qits changelog bump-message --group golden-masters --applied applied.tsv --body body.txt"
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits changelog bump-message --applied applied.tsv --body body.txt"
Enter
Wait /^\$$/
Type "echo $?"
Enter
Wait /^\$$/
Show
Sleep 2s
