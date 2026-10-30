# qits maintenance, on the screen: `qits tui`, down to maintenance > automations, --project,
# --repository and --request picked from the lists the platform fills in, run with Ctrl+R, and
# quit. The output box shows the table the CLI tape shows.
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
Down 8
Wait+Screen / maintenance +▸/
Show
Sleep 1s

Hide
Enter
Wait+Screen /┌ maintenance ─/
Show
Sleep 1s

Hide
Type "/automations"
Enter
Wait+Screen /┌ maintenance ▸ automations ─/
Show
Sleep 1s

Hide
Type "/--project "
Wait+Screen /--project +▸/
Show
Sleep 1s

Hide
Enter
Wait+Screen /contract-00000001/
Show
Sleep 1s

Hide
Enter
Wait+Screen /--project\s+contract-00000001/
Show
Sleep 1s

Hide
Escape
Wait+Screen /\/ search/
Show
Sleep 1s

Hide
Type "/--repository "
Wait+Screen /--repository +▸/
Show
Sleep 1s

Hide
Enter
Wait+Screen /contract-service/
Show
Sleep 1s

Hide
Type "contract-service"
Enter
Wait+Screen /--repository\s+contract-service/
Show
Sleep 1s

Hide
Escape
Wait+Screen /\/ search/
Show
Sleep 1s

Hide
Type "/--request "
Wait+Screen /--request +▸/
Show
Sleep 1s

Hide
Enter
Wait+Screen /contract-service-rr-1/
Show
Sleep 1s

Hide
Type "rr-1"
Enter
Wait+Screen /--request\s+contract-service-rr-1/
Show
Sleep 1s

Hide
Escape
Wait+Screen /\/ search/
Show
Sleep 1s

Hide
Ctrl+R
Wait+Screen /exit 0/
Show
Sleep 2s

Hide
Type "q"
Wait /^\$$/
Show
Sleep 500ms
