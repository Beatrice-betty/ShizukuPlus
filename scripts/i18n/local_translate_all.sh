#!/usr/bin/env bash
# Runs local_translate.py for every remaining locale. Safe to re-run.
cd "$(dirname "$0")"
for pair in "id:Indonesian" "nl:Dutch" "pl:Polish" "vi:Vietnamese" "th:Thai" "cs:Czech" "el:Greek" "fa:Persian" "fi:Finnish" \
  "fil:Filipino" "hu:Hungarian" "hy:Armenian" "ka:Georgian" "ms:Malay" "no:Norwegian Bokmal" "ro:Romanian" "sl:Slovenian" \
  "sr:Serbian" "sv:Swedish" "ta:Tamil" "af:Afrikaans"; do
  python3 local_translate.py "${pair%%:*}" "${pair#*:}" || exit $?
done
