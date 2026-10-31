# qits update, on the screen: `qits tui`, down to update, run with Ctrl+R, and quit. The screen
# runs with the workspace token tapes/prepare.sh sets, where qits does not update itself, so the
# box shows that refusal (exit 2); the CLI tape shows an update on a workstation.
#
# Against the fake platform tapes/prepare.sh starts (FakePlatformMain). Every key is pressed while
# hidden and the screen is shown once it has painted what the key does, so every frame of the text
# golden is a finished screen and the golden is the same on every play.
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
Type "qits tui"
Enter
Wait+Screen /⌃R run · q quit/
Show
Sleep 1s

Hide
Type "/update "
Down 2
Wait+Screen /(?s)\/update  ┐.*│ . update +▸/
Show
Sleep 1s

Hide
Enter
Wait+Screen /┌ update ─/
Show
Sleep 1s

Hide
Ctrl+R
Wait+Screen /exit 2/
Show
Sleep 2s

Hide
Type "q"
Wait /^\$$/
Show
Sleep 500ms
