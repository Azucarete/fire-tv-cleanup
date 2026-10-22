#!/usr/bin/env bash
# Windows: rebuild the launcher and reinstall it on the TV in one go (double-click instalar.bat).
# First copies your own photos, if misfondos/ exists, into the local-only debug overrides:
#   misfondos/launcher/        the home screen backgrounds: mañana, tarde, atardecer, noche (.jpg)
#   misfondos/salvapantallas/  the screensaver: any number of photos (.jpg or .png)
# Without the subfolders, the backgrounds are read from misfondos/ itself. Without screensaver photos, the
# screensaver shows the four backgrounds.
set -euo pipefail
source "$(dirname "$0")/win-env.sh"
tv_reachable
dock_save
if [ -d misfondos ]; then
  D=launcher/app/src/debug/res/drawable-nodpi
  mkdir -p "$D"
  BG_DIR=misfondos/launcher
  [ -d "$BG_DIR" ] || BG_DIR=misfondos
  for pair in mañana:morning tarde:afternoon atardecer:evening noche:night; do
    src="$BG_DIR/${pair%%:*}.jpg"
    if [ -f "$src" ]; then cp "$src" "$D/bg_${pair##*:}.jpg"; echo "Fondo: $src"; fi
  done
  # Screensaver photos become saver_mio_NN. PNGs become JPGs when tools/iconvenv has Pillow (a much smaller APK);
  # otherwise they are copied as they are.
  rm -f "$D"/saver_mio_*
  saver_items=""
  n=0
  for src in misfondos/salvapantallas/*; do
    [ -f "$src" ] || continue
    ext="${src##*.}"; ext="${ext,,}"
    case "$ext" in jpg|jpeg|png) ;; *) continue ;; esac
    n=$((n + 1))
    name=$(printf 'saver_mio_%02d' "$n")
    if [ "$ext" = png ] && [ -x tools/iconvenv/Scripts/python ]; then
      tools/iconvenv/Scripts/python -I -c \
        "import sys; from PIL import Image; Image.open(sys.argv[1]).convert('RGB').save(sys.argv[2], quality=90)" \
        "$src" "$D/$name.jpg"
    else
      [ "$ext" = jpeg ] && ext=jpg
      cp "$src" "$D/$name.$ext"
    fi
    saver_items="$saver_items        <item>@drawable/$name</item>"$'\n'
    echo "Salvapantallas: $src"
  done
  if [ -z "$saver_items" ]; then
    for bg in morning afternoon evening night; do saver_items="$saver_items        <item>@drawable/bg_$bg</item>"$'\n'; done
    echo "Salvapantallas: sin fotos en misfondos/salvapantallas, usa los fondos"
  fi
  mkdir -p launcher/app/src/debug/res/values
  cat > launcher/app/src/debug/res/values/screensaver.xml <<EOF
<?xml version="1.0" encoding="utf-8"?>
<!-- Local only, written by tools/instalar.sh from misfondos/salvapantallas (or the backgrounds, if it is empty). -->
<resources>
    <array name="screensaver_photos">
${saver_items}    </array>
</resources>
EOF
fi
echo "Compilando e instalando el launcher (un par de minutos)..."
bash launcher/build-and-install.sh
dock_restore
echo "Listo: el launcher está instalado en la tele."
