#!/usr/bin/env bash
# Presses remote keys on the TV, then saves a screenshot to screenshots/<name>.png (kept local, in .gitignore).
#   bash tools/captura.sh <name> [key...]
#   bash tools/captura.sh menu DPAD_UP DPAD_RIGHT MENU
# Keys are Android key names (DPAD_UP/DOWN/LEFT/RIGHT, DPAD_CENTER for OK, BACK, HOME, MENU) or codes.
set -euo pipefail
source "$(dirname "$0")/win-env.sh"
[ $# -ge 1 ] || { echo "Uso: bash tools/captura.sh <nombre> [tecla...]"; exit 1; }
name=$1; shift
tv() { "$PY" -I tools/tv.py "$1"; }
for key in "$@"; do
  tv "input keyevent $key" >/dev/null
  sleep 0.6   # let menus open and animations settle before the next key
done
sleep 1.2
mkdir -p screenshots
tv 'screencap -p /data/local/tmp/captura.png' >/dev/null
"$PY" -I tools/tv.py --pull /data/local/tmp/captura.png "screenshots/$name.png" >/dev/null
tv 'rm -f /data/local/tmp/captura.png' >/dev/null
echo "screenshots/$name.png"
