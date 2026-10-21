# Shared setup for the Windows helpers (instalar.bat, comprobar.bat), sourced from Git Bash.
# The TV's IP comes from TV_HOST, else tv-ip.txt in the repo root (asked for and saved the first time).
cd "$(dirname "${BASH_SOURCE[0]}")/.."
export MSYS_NO_PATHCONV=1   # keep TV paths such as /data/local/tmp as they are, not as Windows paths
if [ -z "${TV_HOST:-}" ] && [ -f tv-ip.txt ]; then TV_HOST=$(tr -d ' \r\n' < tv-ip.txt); fi
if [ -z "${TV_HOST:-}" ]; then
  read -rp "IP de la tele (Ajustes > Mi Fire TV > Acerca de > Red): " TV_HOST
  echo "$TV_HOST" > tv-ip.txt
fi
export TV_HOST
JDK=$(ls -d "/c/Program Files/Microsoft/jdk-17"* 2>/dev/null | head -1)
[ -n "$JDK" ] && export JAVA_HOME="$JDK"
PY=tools/venv/Scripts/python
if [ ! -x "$PY" ]; then
  echo "Falta el entorno de Python. Créalo con:"
  echo "  python -m venv tools/venv && tools/venv/Scripts/pip install -r tools/requirements.txt"
  exit 1
fi

# The dock order the launcher saves on the TV once it is edited there (hold OK / Menu on an app). Kept in
# mi-dock.txt so it survives a factory reset or a fresh install; without edits there is nothing to keep.
DOCK_FILE=mi-dock.txt
dock_on_tv() {
  "$PY" -I tools/tv.py 'run-as com.gathin.tvhome cat shared_prefs/tvhome.xml 2>/dev/null' \
    | tr -d '\r' | sed -n 's:.*<string name="dock">\(.*\)</string>.*:\1:p'
}
dock_save() {
  local dock
  dock=$(dock_on_tv)
  if [ -n "$dock" ]; then echo "$dock" > "$DOCK_FILE"; echo "Dock guardado en $DOCK_FILE: $dock"; fi
}
# Puts mi-dock.txt back when the TV has no dock of its own, then brings the launcher up so it re-reads it.
# No force-stop: Android would switch off the launcher's Home redirect along with it.
dock_restore() {
  [ -f "$DOCK_FILE" ] || return 0
  [ -z "$(dock_on_tv)" ] || return 0
  local dock b64
  dock=$(tr -d ' \r\n' < "$DOCK_FILE")
  # Sent as base64: the XML's quotes would clash with the shell quoting on the TV.
  b64=$(printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <string name=\"dock\">%s</string>\n</map>\n" \
    "$dock" | base64 -w0)
  printf '%s\n' \
    "run-as com.gathin.tvhome sh -c 'mkdir -p shared_prefs && echo $b64 | base64 -d > shared_prefs/tvhome.xml'" \
    "am start -n com.gathin.tvhome/.MainActivity" | "$PY" -I tools/tv.py --batch >/dev/null
  echo "Dock restaurado desde $DOCK_FILE: $dock"
}

# Fails with a hint when the TV cannot be reached (off, other network, or ADB debugging off after a restart).
tv_reachable() {
  echo "Conectando con la tele en $TV_HOST... (si pregunta \"¿Permitir depuración USB?\", acepta)"
  if ! "$PY" -I tools/tv.py 'getprop ro.product.model' >/dev/null 2>&1; then
    echo "No se puede conectar con la tele en $TV_HOST."
    echo "Comprueba que está encendida, en la misma Wi-Fi, y con Depuración ADB activada"
    echo "(Ajustes > Mi Fire TV > Opciones para desarrolladores). Se desactiva al reiniciar la tele."
    exit 1
  fi
}
