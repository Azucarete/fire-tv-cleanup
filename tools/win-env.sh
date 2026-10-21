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
