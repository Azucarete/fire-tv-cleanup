#!/usr/bin/env bash
# Windows: rebuild the launcher and reinstall it on the TV in one go (double-click instalar.bat).
# First copies your own backgrounds from misfondos/ (mañana, tarde, atardecer, noche .jpg), if the folder exists,
# into the local-only debug overrides.
set -euo pipefail
source "$(dirname "$0")/win-env.sh"
tv_reachable
dock_save
if [ -d misfondos ]; then
  D=launcher/app/src/debug/res/drawable-nodpi
  mkdir -p "$D"
  for pair in mañana:morning tarde:afternoon atardecer:evening noche:night; do
    src="misfondos/${pair%%:*}.jpg"
    if [ -f "$src" ]; then cp "$src" "$D/bg_${pair##*:}.jpg"; echo "Fondo: $src"; fi
  done
fi
echo "Compilando e instalando el launcher (un par de minutos)..."
bash launcher/build-and-install.sh
dock_restore
echo "Listo: el launcher está instalado en la tele."
