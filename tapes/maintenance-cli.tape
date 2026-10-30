# qits maintenance, on the command line: the release-request automations of one release request,
# as a table and as JSON, and a write the platform refuses - a cli-tapes run, the automation that
# plays these very tapes, which a qits:agent token may not start (403, exit 1). The answers are
# qits-maintenance's golden masters; the request is found in qits-projects' ones.
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
Type "qits maintenance --project contract-00000001 --repository contract-service automations --request contract-service-rr-1"
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits maintenance --project contract-00000001 --repository contract-service automations --request contract-service-rr-1 -o json"
Enter
Wait /^\$$/
Show
Sleep 2s

Hide
Type "clear"
Enter
Wait /^\$$/
Type "qits maintenance --project contract-00000001 --repository contract-service automation run --request contract-service-rr-1 --kind cli-tapes"
Enter
Wait /^\$$/
Type "echo $?"
Enter
Wait /^\$$/
Show
Sleep 2s
