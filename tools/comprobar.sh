#!/usr/bin/env bash
# Windows: check that the TV is still set up as we left it, and repair what a Fire OS update undid
# (double-click comprobar.bat). Checks the debloat list, the launcher, its Home redirect, its permissions and
# its screensaver. Changes nothing that is already right.
set -euo pipefail
source "$(dirname "$0")/win-env.sh"
LIST="${DEBLOAT:-examples/xiaomi-f2-fire-tv-AFTANNA0/debloat.txt}"
APP=com.gathin.tvhome
REDIRECT=$APP/$APP.HomeRedirectService
DREAM=$APP/$APP.PhotoDream
NOW_PLAYING=$APP/$APP.NowPlayingListener
tv() { "$PY" -I tools/tv.py "$@" | tr -d '\r'; }
ok() { echo "  [OK]       $1"; }
fixed() { echo "  [REPARADO] $1"; }
bad() { echo "  [FALTA]    $1"; }

tv_reachable
echo
echo "Apps desactivadas ($LIST):"
disabled=$(tv 'pm list packages -d')
for pkg in $(grep -vE '^\s*(#|$)' "$LIST" | sed -E 's/\s+#.*$//'); do
  if ! tv "pm path $pkg" | grep -q package:; then ok "$pkg (no está instalada)"
  elif grep -qx "package:$pkg" <<< "$disabled"; then ok "$pkg"
  else tv "pm disable-user --user 0 $pkg" >/dev/null; fixed "$pkg (se había vuelto a activar)"
  fi
done

echo
echo "Launcher:"
if ! tv "pm path $APP" | grep -q package:; then
  bad "el launcher no está instalado: ejecuta instalar.bat"
  exit 1
fi
ok "instalado"
if tv "dumpsys package $APP" | grep -q "WRITE_SECURE_SETTINGS: granted=true"; then ok "permiso para ajustes"
else tv "pm grant $APP android.permission.WRITE_SECURE_SETTINGS"; fixed "permiso para ajustes"
fi
if tv "appops get $APP GET_USAGE_STATS" | grep -q allow; then ok "permiso de estadísticas de uso (encendido)"
else tv "appops set $APP GET_USAGE_STATS allow"; fixed "permiso de estadísticas de uso (encendido)"
fi
if tv 'settings get secure enabled_notification_listeners' | grep -q "$NOW_PLAYING"; then ok "permiso para ver qué suena (Spotify)"
else tv "cmd notification allow_listener $NOW_PLAYING" >/dev/null; fixed "permiso para ver qué suena (Spotify)"
fi
if tv 'settings get secure enabled_accessibility_services' | grep -q "$REDIRECT" \
    && [ "$(tv 'settings get secure accessibility_enabled')" = 1 ]; then ok "botón Inicio abre el launcher"
else
  tv "settings put secure enabled_accessibility_services $REDIRECT"
  tv 'settings put secure accessibility_enabled 1'
  fixed "botón Inicio abre el launcher"
fi
if [ "$(tv 'settings get secure screensaver_components')" = "$DREAM" ]; then ok "salvapantallas"
else tv "settings put secure screensaver_components $DREAM"; fixed "salvapantallas"
fi
echo
dock_save
echo "Comprobación terminada."
