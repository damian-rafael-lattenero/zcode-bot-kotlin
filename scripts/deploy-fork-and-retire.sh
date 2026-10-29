#!/usr/bin/env bash
# deploy-fork-and-retire.sh — el autómata one-shot del cutover: el ⌘Q de
# Damian se vuelve una palabra. Cierra ZCode, builda+instala el fork con el
# canal daemon-local (B3a, spec: ~/Projects/ZCode/docs/fork/DAEMON-CHANNEL.md),
# reabre el app, verifica (verify-deploy + smoke hello→hello_ok) y, si todo
# pasó, jubila al bot Python (scripts/retire-python.sh — B3b).
#
# Modos:
#   --dryrun  default en desarrollo: la fase 0 (sanity, read-only) corre de
#             verdad; las fases 1-6 se NARRAN sin tocar nada (ni quit, ni
#             build/swap, ni open, ni retire, ni Telegram real — los anuncios
#             caen al log como [dryrun] telegram-suppressed).
#   --real    el run de verdad.
#
# Lanzarlo DETACHADO en --real (tiene que sobrevivir a la muerte del propio
# ZCode — nohup reparenta el proceso a launchd):
#   nohup bash scripts/deploy-fork-and-retire.sh --real >/tmp/deploy-fork.log 2>&1 & disown
#
# Fases (cada una anunciada por Telegram en --real — curl sendMessage con
# token/chat_id de ~/.zcode/telegram-remote.json, el patrón exacto de
# build-install.sh, texto plano):
#   0 sanity   fork en origin/damian (git fetch + HEAD local == origin/damian,
#              si no aborta), daemon del bot cargado (launchctl), y
#              retire-python.sh presente (si no: retire=skip — el run para
#              tras la verificación avisando que falta).
#   1 quit     osascript graceful (≤30 s) → ÚLTIMO RECURSO: kill de los pids
#              (ps comm exacto — POTHOLE: pgrep/pkill NO ven este app, ver
#              zcode_running en el código) → hasta 120 s esperando cero proceso.
#   2 build    cd ~/Projects/ZCode && scripts/build-install.sh --yes (swap de
#              /Applications/ZCode.app; OJO: ese script REABRE el app y manda
#              su propio anuncio «Fork instalado» — es esperado, no duplicado).
#   3 reopen   barrido de socket/token huérfanos + open -a ZCode si hiciera
#              falta → poll ≤90 s (c/2 s) a ~/.zcode/daemon-local.sock Y
#              ~/.zcode/daemon-local.token.
#   4 verify   scripts/verify-deploy.sh del fork + smoke del canal según la
#              receta de la spec (§5): hello con el token → esperar hello_ok.
#   5 retire   bash scripts/retire-python.sh (solo si verify OK y retire=on) —
#              salida capturada en este mismo log.
#   6 final    «Kotlin bot 100% — Python retirado» o el estado real de cada fase.
#
# Smoke — decisión nc vs python3 (el requirement manda decidir y documentar):
# /usr/bin/nc de esta Mac soporta -U, así que el camino primario es la receta
# textual de la spec (printf hello | nc -U sock | head -1) más un watchdog de
# 10 s — tras el EOF de stdin nc queda vivo hasta que el server cierre; el
# head -1 hace SIGPIPE en el camino feliz, el watchdog acota el patológico.
# Sin nc en la máquina: fallback a python3 con socket AF_UNIX y settimeout(10).
#
# Doctrina de fallo (el script NUNCA deja el sistema a medias):
#   · falla ANTES del retire (fases 0-4) → Python intocado: announce con la
#     fase fallida + «Python sigue vivo, rollback no necesario».
#   · falla DESPUÉS del quit → el sistema igual queda usable: si el build
#     falló, el app viejo sigue en /Applications y este script lo REABRE
#     (best-effort); si el app abre pero el canal no, el daemon degrada loud
#     sin socket — modo headless automático, no error (spec §5, rollback).
#   · falla el propio retire (fase 5) → el fork ya está deployado y verificado;
#     el announce dice revisar el estado de Python en vez de «sigue vivo».
#
# Exit codes: 0 todo hecho (retire OK) · 2 error de uso · 3 una fase falló ·
#             4 verify OK pero retire=skip (falta scripts/retire-python.sh).
#
# Compatible bash 3.2 (la /bin/bash de macOS: sin timeout, sin mapfile).
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
FORK_DIR="${FORK_DIR:-$HOME/Projects/ZCode}"
ZCODE_DATA="${ZCODE_DATA_BASE_DIR:-$HOME/.zcode}"
SOCK="$ZCODE_DATA/daemon-local.sock"
TOKEN_FILE="$ZCODE_DATA/daemon-local.token"
TG_CFG="$HOME/.zcode/telegram-remote.json"   # patrón build-install.sh: fijo
RETIRE_SCRIPT="$REPO/scripts/retire-python.sh"
LOG="/tmp/deploy-fork-and-retire-$(date +%Y%m%d-%H%M%S).log"

MODE="dryrun"   # default en desarrollo: cero acciones destructivas
RETIRE="skip"
QUIT_DONE=0
RETIRE_RESULT="pendiente"
REPORT=""
ERRNOTE=""
EXIT_CODE=0

usage() {
  cat <<'EOF'
deploy-fork-and-retire.sh — el cutover en una palabra (quit→build→reopen→verify→retire)

  bash scripts/deploy-fork-and-retire.sh [--dryrun|--real]

  --dryrun  default: fase 0 real (read-only), fases 1-6 narradas sin tocar nada
  --real    el run de verdad (detached: nohup ... >/tmp/deploy-fork.log 2>&1 & disown)
EOF
}

# El parseo va ANTES del setup de logging: --help y los errores de uso no
# generan archivo de log ni línea de trap.
while [ $# -gt 0 ]; do
  case "$1" in
    --dryrun) MODE="dryrun" ;;
    --real)   MODE="real" ;;
    -h|--help) usage; exit 0 ;;
    *) echo "flag desconocido: $1 (ver --help)" >&2; exit 2 ;;
  esac
  shift
done

exec > >(tee -a "$LOG") 2>&1
trap 'log "── exit rc=$? (log: $LOG)"' EXIT

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }
rpt() { REPORT="${REPORT:+$REPORT · }$*"; }

# Detección del app — POTHOLE medido: pgrep/pkill NO ven el main process de
# ZCode (app de LaunchServices que reescribe su argv: ARGS="ZCode", y pgrep
# matchea pbi_name que acá difiere — probado fallando con -x, -i y -f con el
# app corriendo, pid invisible para pgrep pero visible para ps). La fuente
# confiable es ps comm exacto; System Events (osascript) también ve el app
# pero es demasiado pesado para el poll de 2 s.
# shellcheck disable=SC2009  # pgrep NO ve este app (pbi_name ≠ comm, probado) — ps es la fuente correcta
zcode_running() { ps -axo comm= 2>/dev/null | grep -x "ZCode" >/dev/null; }
zcode_pids()    { ps -axo pid=,comm= | awk '$2=="ZCode"{print $1}' | tr '\n' ' '; }

announce() {
  # En --real: el patrón EXACTO de build-install.sh (token/chat_id de
  # ~/.zcode/telegram-remote.json, texto plano, un curl que nunca rompe el run).
  # En --dryrun: el mensaje cae al log como suprimido — nada sale a Telegram.
  if [ "$MODE" = "real" ]; then
    if [ -f "$TG_CFG" ]; then
      TG_TOKEN=$(/usr/bin/python3 -c "import json;print(json.load(open('$TG_CFG')).get('token',''))" 2>/dev/null || true)
      TG_CHAT=$(/usr/bin/python3 -c "import json;print(json.load(open('$TG_CFG')).get('chat_id',''))" 2>/dev/null || true)
      [ -n "${TG_TOKEN:-}" ] && [ -n "${TG_CHAT:-}" ] && curl -s -m 10 \
        "https://api.telegram.org/bot$TG_TOKEN/sendMessage" -d chat_id="$TG_CHAT" \
        -d text="$1" >/dev/null 2>&1 || true
    else
      log "WARN: falta $TG_CFG — anuncio descartado"
    fi
  else
    log "[dryrun] telegram-suppressed: $1"
  fi
}

fail() {
  # $1 = fase, $2 = detalle. Announce + (si ya cerramos el app) reopen
  # best-effort + reporte del estado real + exit 3.
  log "✗ FAIL [$1] $2"
  if [ "$1" = "fase 5 (retire)" ]; then
    announce "❌ Deploy auto FALLÓ en $1 — $2. El fork ya está deployado y verificado; revisá el estado de Python (el retire falló a mitad)."
  else
    announce "❌ Deploy auto FALLÓ en $1 — $2. Python sigue vivo, rollback no necesario."
  fi
  if [ "$MODE" = "real" ] && [ "$QUIT_DONE" = 1 ]; then
    if zcode_running; then
      log "el app ya está corriendo — nada que reabrir"
    else
      log "best-effort: reabro el app (el de /Applications, el que sea) para no dejar a Damian sin ZCode"
      open -a ZCode || true
    fi
  fi
  rpt "$1 FAIL"
  announce "estado: $REPORT"
  EXIT_CODE=3
  exit 3
}

# ────────────────────────────── fase 0: sanity ──────────────────────────────
phase0_sanity() {
  log "── fase 0/6: sanity"
  if [ ! -d "$FORK_DIR/.git" ]; then ERRNOTE="$FORK_DIR no es un repo git"; log "✗ $ERRNOTE"; return 1; fi
  if [ ! -x "$FORK_DIR/scripts/build-install.sh" ]; then ERRNOTE="falta build-install.sh ejecutable en el fork"; log "✗ $ERRNOTE"; return 1; fi
  if [ ! -x "$FORK_DIR/scripts/verify-deploy.sh" ]; then ERRNOTE="falta verify-deploy.sh ejecutable en el fork"; log "✗ $ERRNOTE"; return 1; fi

  log "git fetch origin (fork)..."
  if git -C "$FORK_DIR" fetch origin --quiet 2>/dev/null; then
    log "fetch OK"
  else
    if [ "$MODE" = "real" ]; then ERRNOTE="git fetch falló (¿red?)"; log "✗ $ERRNOTE"; return 1; fi
    log "WARN (dryrun lo tolera): git fetch falló — comparo contra un origin/damian posiblemente viejo"
  fi

  local br loc rem
  br="$(git -C "$FORK_DIR" rev-parse --abbrev-ref HEAD)" || return 1
  loc="$(git -C "$FORK_DIR" rev-parse HEAD)" || return 1
  rem="$(git -C "$FORK_DIR" rev-parse origin/damian)" || { ERRNOTE="no existe origin/damian"; log "✗ $ERRNOTE"; return 1; }
  log "fork: branch=$br HEAD=${loc:0:9} origin/damian=${rem:0:9}"
  if [ "$br" != "damian" ]; then ERRNOTE="el fork está en '$br', no en damian"; log "✗ $ERRNOTE"; return 1; fi
  if [ "$loc" != "$rem" ]; then ERRNOTE="HEAD local != origin/damian — pushear/actualizar antes del deploy"; log "✗ $ERRNOTE"; return 1; fi
  if [ -n "$(git -C "$FORK_DIR" status --porcelain 2>/dev/null)" ]; then
    log "WARN: working tree del fork sucio — build-install compila el TREE actual, no solo el commit"
  fi

  # OJO grep sin -q (a propósito): -q sale en el primer match, launchctl se
  # come un SIGPIPE y `set -o pipefail` envenena el pipeline aunque haya
  # matcheado (medido: ~50% de carrera). Sin -q grep lee TODO el input y el
  # status del pipeline es el suyo, determinístico.
  if launchctl list 2>/dev/null | grep "com\.zcode\.bot-daemon" >/dev/null; then
    log "daemon del bot (com.zcode.bot-daemon): cargado en launchctl ✓"
  else
    ERRNOTE="com.zcode.bot-daemon NO está cargado — retirar a Python dejaría Telegram sin bot"
    log "✗ $ERRNOTE"; return 1
  fi

  if [ -x "$RETIRE_SCRIPT" ]; then
    RETIRE=on
    log "retire-python.sh presente y ejecutable → retire=on"
  else
    RETIRE=skip
    log "WARN: falta $RETIRE_SCRIPT (¿B3b?) → retire=skip: el run se DETIENE tras la verificación avisando"
  fi

  if [ ! -f "$TG_CFG" ]; then log "WARN: falta $TG_CFG — los anuncios de Telegram se descartan"; fi
  return 0
}

# ────────────────────────────── fase 1: quit ────────────────────────────────
phase1_quit() {
  if [ "$MODE" != "real" ]; then
    if zcode_running; then local viva="SÍ está corriendo (la cerraría)"; else local viva="no está corriendo (nada que cerrar)"; fi
    log "[dryrun] SKIP fase 1: osascript 'tell application \"ZCode\" to quit' → 30 s graceful → ÚLTIMO RECURSO kill \$(zcode_pids) → esperar ≤120 s sin proceso (ps comm exacto — pgrep no ve este app). Hoy ZCode $viva."
    return 0
  fi
  log "quit graceful (osascript)..."
  osascript -e 'tell application "ZCode" to quit' 2>/dev/null || log "WARN: osascript falló (¿ya estaba cerrada?)"
  local w=0
  while [ "$w" -lt 30 ] && zcode_running; do sleep 1; w=$((w+1)); done
  if zcode_running; then
    log "viva tras ${w}s graceful — ÚLTIMO RECURSO: kill $(zcode_pids)"
    zcode_pids | xargs kill 2>/dev/null || true
  else
    log "ZCode cerró graceful (${w}s)"
  fi
  while [ "$w" -lt 120 ] && zcode_running; do sleep 2; w=$((w+2)); done
  if zcode_running; then ERRNOTE="ZCode sigue viva tras ${w}s"; log "✗ $ERRNOTE"; return 1; fi
  QUIT_DONE=1
  log "cero proceso ZCode ✓ (total ${w}s)"
  return 0
}

# ──────────────────────────── fase 2: build + swap ──────────────────────────
phase2_build() {
  if [ "$MODE" != "real" ]; then
    log "[dryrun] SKIP fase 2: (cd $FORK_DIR && scripts/build-install.sh --yes) — bundle del tree actual + swap de /Applications/ZCode.app (minutos). Ese script REABRE el app y manda su propio anuncio «Fork instalado» — esperado."
    return 0
  fi
  log "build+swap: scripts/build-install.sh --yes (esto toma minutos)..."
  local rc=0
  (cd "$FORK_DIR" && scripts/build-install.sh --yes) || rc=$?
  if [ "$rc" != 0 ]; then ERRNOTE="build-install.sh exit $rc (el app viejo queda en /Applications — lo reabro)"; log "✗ $ERRNOTE"; return 1; fi
  log "build+swap OK ✓ (el propio build-install ya reabrió el app y anunció el swap)"
  return 0
}

# ───────────────────────────── fase 3: reopen ───────────────────────────────
phase3_reopen() {
  if [ "$MODE" != "real" ]; then
    local es="ausente" et="ausente"
    if [ -e "$SOCK" ]; then es="PRESENTE (huérfano pre-deploy: el run real lo barre)"; fi
    if [ -f "$TOKEN_FILE" ]; then et="PRESENTE (huérfano pre-deploy: el run real lo barre)"; fi
    log "[dryrun] SKIP fase 3: open -a ZCode (build-install ya lo abre; belt-and-suspenders) + poll ≤90 s (c/2 s) a daemon-local.sock Y daemon-local.token. Hoy: sock $es, token $et."
    return 0
  fi
  # Barrida de huérfanos: el app está muerta (fase 1), así que si estos archivos
  # viven son restos de un host muerto (spec §1) — esperarlos sería esperar
  # archivos VIEJOS. El host nuevo crea los suyos al arrancar.
  rm -f "$SOCK" "$TOKEN_FILE"
  if ! zcode_running; then
    log "el app no quedó abierta (¿cambió build-install?) — open -a ZCode"
    open -a ZCode
  fi
  log "esperando socket+token del daemon-local (≤90 s, poll 2 s)..."
  local w=0 found=0
  while [ "$w" -lt 90 ]; do
    if [ -S "$SOCK" ] && [ -f "$TOKEN_FILE" ]; then found=1; break; fi
    sleep 2; w=$((w+2))
  done
  if [ "$found" = 1 ]; then log "socket+token presentes ✓ (${w}s) — el host del fork levantó el canal"; return 0; fi
  ERRNOTE="el host no creó daemon-local.sock/token en 90 s (¿ZCODE_DAEMON_LOCAL_CHANNEL=0? ¿gate cerrada en el diálogo de Bots?)"
  log "✗ $ERRNOTE"
  return 1
}

# ───────────────────────────── fase 4: verify ───────────────────────────────
smoke_py() {
  # Fallback sin nc: mismo handshake, timeout determinístico.
  /usr/bin/python3 - "$SOCK" "$1" <<'PYEOF'
import socket, sys
sock_path, token = sys.argv[1], sys.argv[2]
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.settimeout(10)
try:
    s.connect(sock_path)
    s.sendall(('{"type":"hello","protocolVersion":1,"token":"%s","clientId":"smoke"}\n' % token).encode())
    print(s.recv(65536).split(b"\n", 1)[0].decode("utf-8", "replace"))
except Exception as e:
    print("smoke error: %s" % e)
    sys.exit(1)
PYEOF
}

smoke_nc() {
  # La receta textual de la spec (§5) con watchdog: printf hello | nc -U | head -1.
  local token="$1" tmp bg i
  tmp="$(mktemp -t deploy_smoke)"
  ( printf '{"type":"hello","protocolVersion":1,"token":"%s","clientId":"smoke"}\n' "$token" \
      | nc -U "$SOCK" 2>/dev/null | head -1 > "$tmp" ) &
  bg=$!
  i=0
  while [ "$i" -lt 20 ] && [ ! -s "$tmp" ] && kill -0 "$bg" 2>/dev/null; do sleep 0.5; i=$((i+1)); done
  kill "$bg" 2>/dev/null || true      # nc queda vivo tras el EOF de stdin hasta
  pkill -P "$bg" 2>/dev/null || true  # que el server cierre — el watchdog acorta
  wait "$bg" 2>/dev/null || true
  head -1 "$tmp" 2>/dev/null || true
  rm -f "$tmp"
}

smoke_hello() {
  local token out tool
  token="$(cat "$TOKEN_FILE" 2>/dev/null)" || { ERRNOTE="no pude leer $TOKEN_FILE"; log "✗ $ERRNOTE"; return 1; }
  if command -v nc >/dev/null 2>&1; then
    tool="nc -U (receta de la spec + watchdog 10 s)"
    out="$(smoke_nc "$token")" || true
  else
    tool="python3 AF_UNIX (fallback: no hay nc — decisión documentada en el header)"
    out="$(smoke_py "$token")" || true
  fi
  log "smoke vía $tool"
  log "smoke ← ${out:-(sin respuesta)}"
  if printf '%s' "$out" | grep -q '"type":"hello_ok"'; then
    log "handshake hello_ok ✓ — el canal daemon-local vive"
    return 0
  fi
  ERRNOTE="el smoke no vio hello_ok (respuesta: ${out:-(nada)})"
  log "✗ $ERRNOTE"
  return 1
}

phase4_verify() {
  if [ "$MODE" != "real" ]; then
    log "[dryrun] SKIP fase 4a: (cd $FORK_DIR && scripts/verify-deploy.sh) — corre contra el app INSTALADO; solo tiene sentido tras el swap real."
    log "[dryrun] SKIP fase 4b: smoke hello→hello_ok por $SOCK con el token de $TOKEN_FILE (nc -U si existe, si no python3 AF_UNIX) — idem: canal recién tras el deploy."
    return 0
  fi
  log "verify-deploy.sh del fork..."
  local rc=0
  (cd "$FORK_DIR" && scripts/verify-deploy.sh) || rc=$?
  if [ "$rc" != 0 ]; then ERRNOTE="verify-deploy.sh FAIL (exit $rc)"; log "✗ $ERRNOTE"; return 1; fi
  smoke_hello || return 1
  return 0
}

# ───────────────────────────── fase 5: retire ───────────────────────────────
phase5_retire() {
  if [ "$MODE" != "real" ]; then
    if [ "$RETIRE" = "on" ]; then
      log "[dryrun] SKIP fase 5: (cd $REPO && bash scripts/retire-python.sh) — presente y ejecutable; capturaría su salida acá mismo y su exit code."
    else
      log "[dryrun] SKIP fase 5: retire=skip (falta scripts/retire-python.sh de B3b) — en --real el run se detiene ACÁ, tras la verificación, avisando por Telegram."
    fi
    RETIRE_RESULT="skip (dryrun)"
    return 0
  fi
  if [ "$RETIRE" != "on" ]; then
    log "retire=skip — me detengo tras la verificación: FALTA $RETIRE_SCRIPT (B3b). El fork queda deployado y verificado; Python sigue vivo."
    announce "⚠️ Fork deployado y verificado ✓, pero FALTA scripts/retire-python.sh — me detengo acá: Python sigue vivo."
    RETIRE_RESULT="skip (falta script)"
    return 4
  fi
  log "retire: bash scripts/retire-python.sh (salida capturada abajo)..."
  local rc=0
  (cd "$REPO" && bash scripts/retire-python.sh) || rc=$?
  if [ "$rc" != 0 ]; then ERRNOTE="retire-python.sh exit $rc"; log "✗ $ERRNOTE"; return 1; fi
  RETIRE_RESULT="hecho"
  log "Python retirado ✓"
  return 0
}

# ───────────────────────────── fase 6: final ────────────────────────────────
phase6_final() {
  local msg
  if [ "$RETIRE_RESULT" = "hecho" ]; then
    msg="🏁 Kotlin bot 100% — Python retirado. [$REPORT]"
    EXIT_CODE=0
  elif [ "$MODE" = "real" ]; then
    msg="⚠️ Fork deployado y verificado; retire PENDIENTE (falta retire-python.sh) — Python sigue vivo. [$REPORT]"
    EXIT_CODE=4
  else
    msg="rehearsal completa — nada fue tocado. [$REPORT]"
    EXIT_CODE=0
  fi
  log "── fase 6/6: $msg"
  announce "$msg"
}

# ──────────────────────────────── main ──────────────────────────────────────
log "════ deploy-fork-and-retire — modo: $MODE ════"
log "fork: $FORK_DIR · repo bot: $REPO · log: $LOG"
if [ "$MODE" = "dryrun" ]; then
  log "DRYRUN: sanity real (read-only) + narración — NI quit, NI build/swap, NI open, NI retire, NI Telegram."
  DRYTAG=" (SKIP dryrun)"
else
  DRYTAG=""
fi

announce "🤖 Deploy auto ($MODE): canal daemon-local — fase 0/6 sanity..."

if ! phase0_sanity; then fail "fase 0 (sanity)" "${ERRNOTE:-mirá el log}"; fi
rpt "f0 sanity OK (retire=$RETIRE)"
announce "✅ Sanity OK: fork en origin/damian, daemon Kotlin corriendo, retire=$RETIRE. Fase 1/6: cierro ZCode."

if ! phase1_quit; then fail "fase 1 (quit)" "${ERRNOTE:-mirá el log}"; fi
rpt "f1 quit OK$DRYTAG"
announce "✅ ZCode cerrada. Fase 2/6: build+swap del fork (minutos, la próxima noticia es el swap o el fallo)."

if ! phase2_build; then fail "fase 2 (build+swap)" "${ERRNOTE:-mirá el log}"; fi
rpt "f2 build+swap OK$DRYTAG"
announce "✅ Fork instalado. Fase 3/6: reabrir y esperar el socket daemon-local (≤90 s)."

if ! phase3_reopen; then fail "fase 3 (reopen)" "${ERRNOTE:-mirá el log}"; fi
rpt "f3 reopen OK (sock+token)$DRYTAG"
announce "✅ App reabierta, socket+token presentes. Fase 4/6: verify-deploy + smoke hello."

if ! phase4_verify; then fail "fase 4 (verify)" "${ERRNOTE:-mirá el log}"; fi
rpt "f4 verify+smoke OK$DRYTAG"

PR5=0
phase5_retire || PR5=$?
if [ "$PR5" = "4" ]; then
  rpt "f5 retire SKIP (falta script)"
elif [ "$PR5" != "0" ]; then
  fail "fase 5 (retire)" "${ERRNOTE:-mirá el log}"
elif [ "$RETIRE_RESULT" = "hecho" ]; then
  rpt "f5 retire OK"
  announce "✅ Python retirado. Fase 6/6: cierre."
else
  rpt "f5 retire SKIP (dryrun)"
fi

phase6_final
sleep 0.3   # dejar que el tee del log drene las últimas líneas
exit "$EXIT_CODE"
