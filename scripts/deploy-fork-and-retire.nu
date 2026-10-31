#!/Users/lattenero/.local/bin/nu-nightly
# deploy-fork-and-retire.nu — el autómata one-shot del cutover, en Nushell
# TIPADO (port del deploy-fork-and-retire.sh, que queda como fallback congelado:
# el canónico es este .nu; el .sh es el fallback de emergencia — runtime
# garantizado en cualquier bash).
#
# La prueba conceptual: TODO el script queda tipado con `type` de nu 0.116 —
# los errores de tipo son de PARSER (nu::parser::type_mismatch con span exacto),
# no de runtime. Verificable rompiendo a propósito cualquier call-site.
#
# Intérprete: este script REQUIERE el nightly (0.116.2-nightly.24+) — el `nu`
# 0.116.1 de brew NO tiene el keyword `type`. Shebang absoluto por eso.
#
# Modos:
#   --dryrun  default en desarrollo: la fase 0 (sanity, read-only) corre de
#             verdad; las fases 1-6 se NARRAN sin tocar nada (ni quit, ni
#             build/swap, ni open, ni retire, ni Telegram real — los anuncios
#             caen al log como [dryrun] telegram-suppressed).
#   --real    el run de verdad.
#
# Lanzarlo DETACHADO en --real (tiene que sobrevivir a la muerte del propio
# ZCode):
#   nohup scripts/deploy-fork-and-retire.nu --real >/tmp/deploy-fork.log 2>&1 & disown
#
# Fases (cada una anunciada por Telegram en --real):
#   0 sanity   fork en origin/damian (git fetch + HEAD local == origin/damian),
#              daemon del bot cargado (launchctl), retire-python.sh presente.
#   1 quit     osascript graceful (≤30 s) → ÚLTIMO RECURSO: kill de los pids →
#              hasta 120 s esperando cero proceso.
#   2 build    cd ~/Projects/ZCode && scripts/build-install.sh --yes (swap de
#              /Applications/ZCode.app; ese script REABRE el app y manda su
#              propio anuncio «Fork instalado» — esperado, no duplicado).
#   3 reopen   barrido de socket/token huérfanos + open -a ZCode si hiciera
#              falta → poll ≤90 s (c/2 s) al socket Y al token.
#   4 verify   scripts/verify-deploy.sh + smoke hello→hello_ok del canal.
#   5 retire   scripts/retire-python.sh (solo si verify OK y retire=on).
#   6 final    «Kotlin bot 100% — Python retirado» o el estado real.
#
# El estado fluye como VALOR tipado (RunState): cada fase recibe el estado y
# devuelve record<state, outcome> — nada de globales mutables. Los externales
# se invocan como programas (git/launchctl/osascript/open/python3/scripts del
# repo); CERO bash -c con lógica embebida.
#
# Decisiones de la migración (todo medido en REPL, ver commit):
#   · Detección del app: `ps | where name == ZCode` — NATIVO y superior al
#     pothole del .sh: pgrep/pkill NO ven el main process de ZCode (app de
#     LaunchServices que reescribe su argv; pgrep matchea pbi_name ≠ comm —
#     probado fallando con -x/-i/-f). El `ps` de nu ve los 7 procesos,
#     incluido el main. El pothole muere nativo.
#   · Modo: nu no tiene enums ni tipos literal (falló `enum` y `oneof<"a","b">`
#     en REPL) → `type Mode = string` + guard en init-state con error make.
#   · Telegram: `open` del JSON tipado como TgCfg (adiós python3 -c json) +
#     `http post` nativo con body JSON y --max-time (bash usaba curl
#     form-urlencoded; la Bot API acepta ambos — mismo contrato sendMessage).
#     Envuelto en try/do -i: NUNCA rompe el run.
#   · Log: def log tipado con el MISMO formato que el .sh
#     ([YYYY-MM-DD HH:MM:SS] línea) + append a /tmp/deploy-fork-and-retire-<ts>.log.
#     std/log de nu se evaluó y rechazó: formato ISO con millis (|INF|) —
#     la paridad línea a línea manda.
#   · Externales largos (build-install/verify-deploy/retire-python): stream
#     VIVO stdout+stderr al log vía `tee`/`tee --stderr` (el análogo del
#     `exec > >(tee)` del bash) + `complete` para el exit code exacto.
#   · Smoke: nu no tiene sockets crudos → handshake python3 AF_UNIX externo
#     (extraído textual del .sh, timeout 10 s propio). El camino nc -U del
#     .sh se deja caer: un solo camino determinístico.
#   · Exit codes: 0 todo · 3 fase falló · 4 verify OK pero retire=skip. El
#     «2 uso» del bash NO es replicable: flags desconocidos los rechaza el
#     parser de nu (rc 1) antes de correr el script; --help lo genera nu.
#   · Duraciones nativas (sleep 2sec) para los waits; loops con contador en
#     segundos, como el .sh.
#
# Doctrina de fallo (NUNCA deja el sistema a medias) — idéntica al .sh:
#   · falla ANTES del retire (fases 0-4) → Python intocado: announce con la
#     fase fallida + «Python sigue vivo, rollback no necesario».
#   · falla DESPUÉS del quit → el sistema igual queda usable: si el build
#     falló, el app viejo sigue en /Applications y este script lo REABRE
#     (best-effort); si el app abre pero el canal no, el daemon degrada loud
#     sin socket — modo headless automático, no error.
#   · falla el propio retire (fase 5) → el fork ya está deployado y verificado;
#     el announce dice revisar el estado de Python.
type Mode = string
type TgCfg = record<token: string, chat_id: int>
type PhaseOutcome = record<ok: bool, note: string>
type RunState = record<mode: Mode, retire: string, quit_done: bool, retire_result: string, report: string, exit_code: int, log_path: string, repo: string, fork_dir: string, sock: string, token_file: string, tg_cfg: string, retire_script: string, drytag: string>
type PhaseResult = record<state: RunState, outcome: PhaseOutcome>

# ─────────────────────────── utilidades tipadas ───────────────────────────
def log [st: RunState, msg: string]: nothing -> nothing {
    let line = $"[(date now | format date "%Y-%m-%d %H:%M:%S")] ($msg)"
    print $line
    ($line + "\n") | save --append $st.log_path
}

def rpt [st: RunState, msg: string]: nothing -> RunState {
    if ($st.report | is-empty) {
        $st | update report $msg
    } else {
        $st | update report $"($st.report) · ($msg)"
    }
}

def note-or-log [n: string]: nothing -> string {
    if ($n | is-empty) { "mirá el log" } else { $n }
}

# Detección del app — el pothole del .sh muere acá: ps de nu ve el main.
# Detección de procesos VIVOS del app — ZOMBIE-PROOF (medido, incidente 17:36):
# el ps builtin de nu le pone el nombre viejo a los procesos muertos-sin-enterrar
# (el defunct 20078 contestaba name==ZCode); el ps EXTERNO responde <defunct> para
# zombies y el comm exacto "ZCode" excluye a los Helpers. Solo cuenta lo vivo.
def zcode-running []: nothing -> bool {
    (^ps -axo comm= | lines | any {|c| ($c | str trim) == "ZCode"})
}

def zcode-pids []: nothing -> list<int> {
    (^ps -axo pid=,comm= | lines | parse "{pid} {comm}" | where comm == ZCode | get pid)
}

# El app corre como JOB de launchd (application.dev.zcode.app.<nums que cambian>)
# y launchd LO RESUCITA ~8 s después de matarlo (medido: SIGKILL 17:36:43 → nueva
# instancia 17:36:51). La única muerte que pega es bootout: mata Y desregistra.
def app-job-label []: nothing -> string {
    (^launchctl list | lines | where {|l| $l | str contains "application.dev.zcode"} | first | str trim | split row "\t" | last)
}

def bootout-app [st: RunState]: nothing -> nothing {
    let label = (app-job-label)
    if ($label | is-empty) {
        log $st "WARN: no encontré el job de launchd del app (¿ya booteado out?)"
        return
    }
    let uid = (^id -u | str trim)
    log $st $"launchctl bootout gui/($uid)/($label) — la muerte SIN resurrección"
    let r = (do { ^launchctl bootout $"gui/($uid)/($label)" } | complete)
    if $r.exit_code != 0 {
        log $st $"WARN: bootout exit \($r.exit_code\): \(($r.stderr | str trim))\)"
    }
}

def plist-autorun [path: string]: nothing -> bool {
    if not ($path | path exists) { return false }
    let rows = (open --raw $path | lines)
    # la LÍNEA de la clave exacta (el comentario del repo también dice
    # «RunAtLoad» — sin el tag <key> no cuenta)
    let hit = ($rows | enumerate | where {|r|
        ($r.item | str contains "<key>RunAtLoad</key>")
    } | get -o 0)
    if $hit == null { return false }
    # el valor puede vivir en la misma línea o en la siguiente
    if ($hit.item | str contains "<true/>") { return true }
    let tail = ($rows | skip ($hit.index + 1) | first 1 | str join " ")
    ($tail | str contains "<true/>")
}

def is-exec [p: string]: nothing -> bool {
    (do { ^test -x $p } | complete | get exit_code) == 0
}

# Runner de scripts largos: stdout Y stderr streameados en vivo al log (el
# tee del bash), exit code exacto vía complete. Si el script ni siquiera
# lanza (bash daría 127), se loguea y se devuelve 127.
def run-script [st: RunState, dir: string, script: string, ...args: string]: nothing -> int {
    let r: record<exit_code: int> = try {
        do {
            cd $dir
            ^$script ...$args
            | tee { lines | each {|l| log $st $l } }
            | tee --stderr { lines | each {|l| log $st $l } }
            | complete
        }
    } catch {|e|
        log $st $"⚠ no pude lanzar ($script) en ($dir): ($e.msg)"
        {exit_code: 127}
    }
    $r.exit_code
}

def announce [st: RunState, msg: string]: nothing -> nothing {
    if $st.mode == "real" {
        if not ($st.tg_cfg | path exists) {
            log $st $"WARN: falta ($st.tg_cfg) — anuncio descartado"
            return
        }
        try {
            let cfg: TgCfg = (open $st.tg_cfg)
            if ($cfg.token | is-empty) or ($cfg.chat_id | is-empty) { return }
            let body = ({chat_id: $cfg.chat_id, text: $msg} | to json)
            do -i { http post -m 10sec -t application/json $"https://api.telegram.org/bot($cfg.token)/sendMessage" $body -r | ignore }
        } catch {|_err| }   # el anuncio NUNCA rompe el run (doctrina del .sh)
    } else {
        log $st $"[dryrun] telegram-suppressed: ($msg)"
    }
}

# El handler de fallo: announce según doctrina + reopen best-effort + estado
# + exit_code 3 en el estado (main hace el exit único y loguea la línea final).
def fail [st: RunState, fase: string, detalle: string]: nothing -> RunState {
    log $st $"✗ FAIL [($fase)] ($detalle)"
    if $fase == "fase 5 (retire)" {
        announce $st $"❌ Deploy auto FALLÓ en ($fase) — ($detalle). El fork ya está deployado y verificado; revisá el estado de Python \(el retire falló a mitad\)."
    } else {
        announce $st $"❌ Deploy auto FALLÓ en ($fase) — ($detalle). Python sigue vivo, rollback no necesario."
    }
    let st = if ($st.mode == "real") and $st.quit_done {
        if (zcode-running) {
            log $st "el app ya está corriendo — nada que reabrir"
            $st
        } else {
            log $st "best-effort: reabro el app (el de /Applications, el que sea) para no dejar a Damian sin ZCode"
            try { ^open -a ZCode } catch {|_e| }   # best-effort: || true del .sh
            $st
        }
    } else {
        $st
    }
    let st = (rpt $st $"($fase) FAIL")
    announce $st $"estado: ($st.report)"
    $st | update exit_code 3
}

# ────────────────────────────── fase 0: sanity ──────────────────────────────
def phase0-sanity [st: RunState]: nothing -> PhaseResult {
    log $st "── fase 0/6: sanity"
    if (($st.fork_dir + "/.git") | path type) != "dir" {
        let note = $"($st.fork_dir) no es un repo git"
        log $st $"✗ ($note)"
        return {state: $st, outcome: {ok: false, note: $note}}
    }
    if not (is-exec ($st.fork_dir + "/scripts/build-install.sh")) {
        let note = "falta build-install.sh ejecutable en el fork"
        log $st $"✗ ($note)"
        return {state: $st, outcome: {ok: false, note: $note}}
    }
    if not (is-exec ($st.fork_dir + "/scripts/verify-deploy.sh")) {
        let note = "falta verify-deploy.sh ejecutable en el fork"
        log $st $"✗ ($note)"
        return {state: $st, outcome: {ok: false, note: $note}}
    }

    log $st "git fetch origin (fork)..."
    let fetch = (do { ^git -C $st.fork_dir fetch origin --quiet } | complete)
    if $fetch.exit_code == 0 {
        log $st "fetch OK"
    } else if $st.mode == "real" {
        let note = "git fetch falló (¿red?)"
        log $st $"✗ ($note)"
        return {state: $st, outcome: {ok: false, note: $note}}
    } else {
        log $st "WARN (dryrun lo tolera): git fetch falló — comparo contra un origin/damian posiblemente viejo"
    }

    let brr = (do { ^git -C $st.fork_dir rev-parse --abbrev-ref HEAD } | complete)
    if $brr.exit_code != 0 {
        return {state: $st, outcome: {ok: false, note: ""}}   # note-or-log → "mirá el log", como el .sh
    }
    let locr = (do { ^git -C $st.fork_dir rev-parse HEAD } | complete)
    if $locr.exit_code != 0 {
        return {state: $st, outcome: {ok: false, note: ""}}
    }
    let remr = (do { ^git -C $st.fork_dir rev-parse origin/damian } | complete)
    if $remr.exit_code != 0 {
        let note = "no existe origin/damian"
        log $st $"✗ ($note)"
        return {state: $st, outcome: {ok: false, note: $note}}
    }
    let br = ($brr.stdout | str trim)
    let loc = ($locr.stdout | str trim)
    let rem = ($remr.stdout | str trim)
    log $st $"fork: branch=($br) HEAD=($loc | str substring 0..8) origin/damian=($rem | str substring 0..8)"
    if $br != "damian" {
        let note = $"el fork está en '($br)', no en damian"
        log $st $"✗ ($note)"
        return {state: $st, outcome: {ok: false, note: $note}}
    }
    if $loc != $rem {
        let note = "HEAD local != origin/damian — pushear/actualizar antes del deploy"
        log $st $"✗ ($note)"
        return {state: $st, outcome: {ok: false, note: $note}}
    }
    let porc = (do { ^git -C $st.fork_dir status --porcelain } | complete | get stdout)
    if not ($porc | is-empty) {
        log $st "WARN: working tree del fork sucio — build-install compila el TREE actual, no solo el commit"
    }

    # launchctl: el .sh evitaba grep -q por una carrera de SIGPIPE con
    # pipefail; en nu `lines | where` consume todo el input por diseño — la
    # carrera no existe.
    let lc = (do { ^launchctl list } | complete)
    if ($lc.stdout | lines | where {|l| $l | str contains "com.zcode.bot-daemon"} | length) > 0 {
        log $st "daemon del bot (com.zcode.bot-daemon): cargado en launchctl ✓"
    } else {
        let note = "com.zcode.bot-daemon NO está cargado — retirar a Python dejaría Telegram sin bot"
        log $st $"✗ ($note)"
        return {state: $st, outcome: {ok: false, note: $note}}
    }

    let st = if (is-exec $st.retire_script) {
        log $st "retire-python.sh presente y ejecutable → retire=on"
        $st | update retire "on"
    } else {
        log $st $"WARN: falta ($st.retire_script) \(¿B3b?\) → retire=skip: el run se DETIENE tras la verificación avisando"
        $st | update retire "skip"
    }
    if not ($st.tg_cfg | path exists) {
        log $st $"WARN: falta ($st.tg_cfg) — los anuncios de Telegram se descartan"
    }

    # El propio job launchd del cutover converge al repo (30/10: RunAtLoad
    # era true y CADA boot disparaba el --real solo — el dueño lo vivió).
    # La copia cargada se re-arma desde el repo SI su RunAtLoad difiere:
    # el deploy queda disparable por kickstart, jamás por un bootstrap.
    let plist_repo = ($st.repo + "/scripts/com.zcode.deploy-fork.plist")
    let plist_loaded = ($env.HOME + "/Library/LaunchAgents/com.zcode.deploy-fork.plist")
    # LA CONVERGENCIA ES UN EFECTO SECUNDARIO: jamás puede matar un deploy
    # (30/10: un cell-path sobre el XML del plist reventó el sanity entero —
    # medido y corregido; ahora todo el bloque va en try y lee texto crudo).
    let convergence = (try {
        if ($env.HOME | is-empty) or not ($plist_repo | path exists) {
            "WARN: no pude verificar el plist del cutover (repo o HOME ausentes)"
        } else {
            # el plist es XML: el RunAtLoad se lee por LÍNEAS (un cell path
            # estructural sobre el XML parseado revienta — medido; y el
            # str contains de esta nu no tiene -r)
            let repo_autorun = (plist-autorun $plist_repo)
            let loaded_autorun = (plist-autorun $plist_loaded)
            if $loaded_autorun != $repo_autorun {
                let note = $"plist del cutover diverge (cargado RunAtLoad=($loaded_autorun), repo=($repo_autorun)) — re-armando desde el repo"
                let dst_dir = ($plist_loaded | path dirname)
                mkdir $dst_dir
                cp $plist_repo $plist_loaded
                let uid = (^id -u | str trim)
                do { ^launchctl bootout $"gui/($uid)/com.zcode.deploy-fork" } | complete
                sleep 1sec
                let bs = (do { ^launchctl bootstrap $"gui/($uid)" $plist_loaded } | complete)
                if $bs.exit_code == 0 {
                    $"plist del cutover re-armado desde el repo (RunAtLoad=($repo_autorun) — kickstart-only) ✓"
                } else {
                    $"WARN: bootstrap del plist falló ($bs.stderr | str trim) — el deploy continúa; revisar a mano"
                }
            } else {
                "plist del cutover: copia cargada == repo ✓"
            }
        }
    } catch {|err|
        $"WARN: la convergencia del plist falló y NO detiene el deploy: ($err | to json -r)"
    })
    {state: $st, outcome: {ok: true, note: ""}}
}

# ────────────────────────────── fase 1: quit ────────────────────────────────
# La escalera de muerte, reutilizable (fase 1 y el retry de fase 2 cuando el
# app reaparece durante el build — medido: el humano lo reabre para mirar).
def quit-ladder [st: RunState]: nothing -> record<ok: bool, note: string> {
    log $st "quit graceful (osascript) — en este app suele solo cerrar la ventana..."
    let osa = (do { ^osascript -e 'tell application "ZCode" to quit' } | complete)
    if $osa.exit_code != 0 {
        log $st "WARN: osascript falló (¿ya estaba cerrada?)"
    }
    mut w = 0
    while ($w < 15) and (zcode-running) { sleep 1sec; $w += 1 }
    if not (zcode-running) {
        log $st $"ZCode cerró graceful \(($w)s\)"
    } else {
        log $st $"viva tras ($w)s graceful \(puntito gris: app de fondo\) — peldaño 2: BOOTOUT"
        bootout-app $st
        while ($w < 25) and (zcode-running) { sleep 1sec; $w += 1 }
        if (zcode-running) {
            let pids = (zcode-pids)
            let pids_str = ($pids | each {|p| $p | into string } | str join " ")
            log $st $"viva tras bootout — peldaño 3: SIGTERM a ($pids_str)"
            try { kill ...$pids } catch {|_e| }
            while ($w < 35) and (zcode-running) { sleep 1sec; $w += 1 }
            if (zcode-running) {
                let pids9 = (zcode-pids)
                let pids9_str = ($pids9 | each {|p| $p | into string } | str join " ")
                log $st $"viva tras SIGTERM — peldaño 4: SIGKILL \(kill -s 9\) a ($pids9_str)"
                try { kill -s 9 ...$pids9 } catch {|_e| }
            }
        }
    }
    # Vigilancia anti-resurrección: launchd revivió el app 8 s después de un
    # SIGKILL (medido). Si reaparece, un bootout más lo apaga para siempre.
    while ($w < 50) {
        if not (zcode-running) { break }
        sleep 1sec; $w += 1
    }
    if (zcode-running) {
        log $st "resucitó (launchd) — bootout de la nueva instancia"
        bootout-app $st
        while ($w < 65) and (zcode-running) { sleep 1sec; $w += 1 }
    }
    if (zcode-running) {
        let note = $"ZCode sigue viva tras ($w)s, bootout, SIGTERM y SIGKILL"
        log $st $"✗ ($note)"
        return {ok: false, note: $note}
    }
    log $st $"cero proceso ZCode vivo ✓ \(total ($w)s\)"
    {ok: true, note: ""}
}

def phase1-quit [st: RunState]: nothing -> PhaseResult {
    if $st.mode != "real" {
        let viva = if (zcode-running) { "SÍ está corriendo (la cerraría)" } else { "no está corriendo (nada que cerrar)" }
        log $st $"[dryrun] SKIP fase 1: osascript quit \(solo cierra ventana\) → ≤15 s → bootout del job de launchd \(muerte sin resurrección\) → SIGTERM → SIGKILL → 15 s de vigilancia anti-resurrección. Hoy ZCode ($viva)."
        return {state: $st, outcome: {ok: true, note: ""}}
    }
    let r = (quit-ladder $st)
    if not $r.ok {
        return {state: $st, outcome: {ok: false, note: $r.note}}
    }
    let st = ($st | update quit_done true)
    {state: $st, outcome: {ok: true, note: ""}}
}

# ──────────────────────────── fase 2: build + swap ──────────────────────────
def phase2-build [st: RunState]: nothing -> PhaseResult {
    if $st.mode != "real" {
        log $st $"[dryrun] SKIP fase 2: \(cd ($st.fork_dir) && scripts/build-install.sh --yes\) — bundle del tree actual + swap de /Applications/ZCode.app \(minutos\). Ese script REABRE el app y manda su propio anuncio «Fork instalado» — esperado. Si el app reaparece durante el build \(medido: pasa\) → re-quit y retry del swap."
        return {state: $st, outcome: {ok: true, note: ""}}
    }
    log $st "build+swap: scripts/build-install.sh --yes (esto toma minutos)..."
    let rc = (run-script $st $st.fork_dir "./scripts/build-install.sh" -- --yes)
    if ($rc != 0) and (zcode-running) {
        log $st "el app REAPARECIÓ durante el build (alguien lo abrió para mirar 😉 — incidente 17:48) — re-quit y retry del swap \(build cacheado\)"
        let q = (quit-ladder $st)
        if not $q.ok {
            let note = $"reapareció durante el build y el re-quit falló: ($q.note)"
            log $st $"✗ ($note)"
            return {state: $st, outcome: {ok: false, note: $note}}
        }
        let rc2 = (run-script $st $st.fork_dir "./scripts/build-install.sh" -- --yes)
        if $rc2 != 0 {
            let note = $"build-install.sh exit ($rc2) en el retry \(el app viejo queda en /Applications — lo reabro\)"
            log $st $"✗ ($note)"
            return {state: $st, outcome: {ok: false, note: $note}}
        }
        log $st "build+swap OK ✓ en el retry (el propio build-install ya reabrió el app y anunció el swap)"
        return {state: $st, outcome: {ok: true, note: "retry tras reaparición"}}
    }
    if $rc != 0 {
        let note = $"build-install.sh exit ($rc) \(el app viejo queda en /Applications — lo reabro\)"
        log $st $"✗ ($note)"
        return {state: $st, outcome: {ok: false, note: $note}}
    }
    log $st "build+swap OK ✓ (el propio build-install ya reabrió el app y anunció el swap)"
    {state: $st, outcome: {ok: true, note: ""}}
}

# ───────────────────────────── fase 3: reopen ───────────────────────────────
def phase3-reopen [st: RunState]: nothing -> PhaseResult {
    if $st.mode != "real" {
        let es = if ($st.sock | path exists) { "PRESENTE (huérfano pre-deploy: el run real lo barre)" } else { "ausente" }
        let et = if ($st.token_file | path exists) { "PRESENTE (huérfano pre-deploy: el run real lo barre)" } else { "ausente" }
        log $st $"[dryrun] SKIP fase 3: open -a ZCode \(build-install ya lo abre; belt-and-suspenders\) + poll ≤90 s \(c/2 s\) a daemon-local.sock Y daemon-local.token. Hoy: sock ($es), token ($et)."
        return {state: $st, outcome: {ok: true, note: ""}}
    }
    # Barrida de huérfanos: el app está muerta (fase 1) — si estos archivos
    # viven son restos de un host muerto; el host nuevo crea los suyos.
    rm --force $st.sock $st.token_file
    if not (zcode-running) {
        log $st "el app no quedó abierta (¿cambió build-install?) — open -a ZCode"
        ^open -a ZCode
    }
    log $st "esperando socket+token del daemon-local (≤90 s, poll 2 s)..."
    mut w = 0
    mut found = false
    while $w < 90 {
        if (($st.sock | path type) == "socket") and (($st.token_file | path type) == "file") {
            $found = true
            break
        }
        sleep 2sec
        $w += 2
    }
    if $found {
        log $st $"socket+token presentes ✓ \(($w)s\) — el host del fork levantó el canal"
        return {state: $st, outcome: {ok: true, note: ""}}
    }
    let note = "el host no creó daemon-local.sock/token en 90 s (¿ZCODE_DAEMON_LOCAL_CHANNEL=0? ¿gate cerrada en el diálogo de Bots?)"
    log $st $"✗ ($note)"
    {state: $st, outcome: {ok: false, note: $note}}
}

# ───────────────────────────── fase 4: verify ───────────────────────────────
# nu no tiene sockets crudos → handshake python3 AF_UNIX externo, extraído
# textual del .sh (mismo protocolo hello→hello_ok, timeout 10 s propio).
def smoke-hello [st: RunState]: nothing -> PhaseResult {
    let token = (try { open $st.token_file | str trim } catch {|_e| "" })
    if ($token | is-empty) {
        let note = $"no pude leer ($st.token_file)"
        log $st $"✗ ($note)"
        return {state: $st, outcome: {ok: false, note: $note}}
    }
    let py = 'import socket, sys
sock_path, token = sys.argv[1], sys.argv[2]
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.settimeout(10)
try:
    s.connect(sock_path)
    s.sendall(("{\"type\":\"hello\",\"protocolVersion\":1,\"token\":\"%s\",\"clientId\":\"smoke\"}\n" % token).encode())
    print(s.recv(65536).split(b"\n", 1)[0].decode("utf-8", "replace"))
except Exception as e:
    print("smoke error: %s" % e)
    sys.exit(1)
'
    let tool = "python3 AF_UNIX (handshake de la spec §5 con timeout propio — nu no tiene sockets crudos, ver header)"
    let r = (do { ^/usr/bin/python3 -c $py $st.sock $token } | complete)
    let out = ($r.stdout | str trim)
    log $st $"smoke vía ($tool)"
    let shown = if ($out | is-empty) { "(sin respuesta)" } else { $out }
    log $st $"smoke ← ($shown)"
    if ($out | str contains '"type":"hello_ok"') {
        log $st "handshake hello_ok ✓ — el canal daemon-local vive"
        return {state: $st, outcome: {ok: true, note: ""}}
    }
    let note = $"el smoke no vio hello_ok \(respuesta: ($shown)\)"
    log $st $"✗ ($note)"
    {state: $st, outcome: {ok: false, note: $note}}
}

def phase4-verify [st: RunState]: nothing -> PhaseResult {
    if $st.mode != "real" {
        log $st $"[dryrun] SKIP fase 4a: \(cd ($st.fork_dir) && scripts/verify-deploy.sh\) — corre contra el app INSTALADO; solo tiene sentido tras el swap real."
        log $st $"[dryrun] SKIP fase 4b: smoke hello→hello_ok por ($st.sock) con el token de ($st.token_file) \(python3 AF_UNIX, ver header\) — idem: canal recién tras el deploy."
        return {state: $st, outcome: {ok: true, note: ""}}
    }
    log $st "verify-deploy.sh del fork..."
    let rc = (run-script $st $st.fork_dir "./scripts/verify-deploy.sh")
    if $rc != 0 {
        let note = $"verify-deploy.sh FAIL \(exit ($rc)\)"
        log $st $"✗ ($note)"
        return {state: $st, outcome: {ok: false, note: $note}}
    }
    smoke-hello $st
}

# ───────────────────────────── fase 5: retire ───────────────────────────────
def phase5-retire [st: RunState]: nothing -> PhaseResult {
    if $st.mode != "real" {
        if $st.retire == "on" {
            log $st $"[dryrun] SKIP fase 5: \(cd ($st.repo) && scripts/retire-python.sh\) — presente y ejecutable; capturaría su salida acá mismo y su exit code."
        } else {
            log $st "[dryrun] SKIP fase 5: retire=skip (falta scripts/retire-python.sh de B3b) — en --real el run se detiene ACÁ, tras la verificación, avisando por Telegram."
        }
        return {state: ($st | update retire_result "skip (dryrun)"), outcome: {ok: true, note: ""}}
    }
    if $st.retire != "on" {
        log $st $"retire=skip — me detengo tras la verificación: FALTA ($st.retire_script) \(B3b\). El fork queda deployado y verificado; Python sigue vivo."
        announce $st "⚠️ Fork deployado y verificado ✓, pero FALTA scripts/retire-python.sh — me detengo acá: Python sigue vivo."
        return {state: ($st | update retire_result "skip (falta script)"), outcome: {ok: true, note: ""}}
    }
    log $st "retire: scripts/retire-python.sh (salida capturada abajo)..."
    let rc = (run-script $st $st.repo "./scripts/retire-python.sh")
    if $rc != 0 {
        let note = $"retire-python.sh exit ($rc)"
        log $st $"✗ ($note)"
        return {state: $st, outcome: {ok: false, note: $note}}
    }
    log $st "Python retirado ✓"
    {state: ($st | update retire_result "hecho"), outcome: {ok: true, note: ""}}
}

# ───────────────────────────── fase 6: final ────────────────────────────────
def phase6-final [st: RunState]: nothing -> RunState {
    let m: record<msg: string, code: int> = match {retire_result: $st.retire_result, mode: $st.mode} {
        {retire_result: "hecho"} => {msg: $"🏁 Kotlin bot 100% — Python retirado. [($st.report)]", code: 0}
        {mode: "real"} => {msg: $"⚠️ Fork deployado y verificado; retire PENDIENTE \(falta retire-python.sh\) — Python sigue vivo. [($st.report)]", code: 4}
        _ => {msg: $"rehearsal completa — nada fue tocado. [($st.report)]", code: 0}
    }
    log $st $"── fase 6/6: ($m.msg)"
    announce $st $m.msg
    $st | update exit_code $m.code
}

# ─────────────────────────── el cutover (main loop) ─────────────────────────
def cutover [st0: RunState]: nothing -> RunState {
    let r0 = (phase0-sanity $st0)
    if not $r0.outcome.ok { return (fail $r0.state "fase 0 (sanity)" (note-or-log $r0.outcome.note)) }
    let st = (rpt $r0.state $"f0 sanity OK \(retire=($r0.state.retire)\)")
    announce $st $"✅ Sanity OK: fork en origin/damian, daemon Kotlin corriendo, retire=($st.retire). Fase 1/6: cierro ZCode."

    let r1 = (phase1-quit $st)
    if not $r1.outcome.ok { return (fail $r1.state "fase 1 (quit)" (note-or-log $r1.outcome.note)) }
    let st = (rpt $r1.state $"f1 quit OK($st.drytag)")
    announce $st "✅ ZCode cerrada. Fase 2/6: build+swap del fork (minutos — NO abras ZCode hasta el próximo aviso; si lo abrís, lo cierro y reintento solo)."

    let r2 = (phase2-build $st)
    if not $r2.outcome.ok { return (fail $r2.state "fase 2 (build+swap)" (note-or-log $r2.outcome.note)) }
    let st = (rpt $r2.state $"f2 build+swap OK($st.drytag)")
    announce $st "✅ Fork instalado. Fase 3/6: reabrir y esperar el socket daemon-local (≤90 s)."

    let r3 = (phase3-reopen $st)
    if not $r3.outcome.ok { return (fail $r3.state "fase 3 (reopen)" (note-or-log $r3.outcome.note)) }
    let st = (rpt $r3.state $"f3 reopen OK \(sock+token\)($st.drytag)")
    announce $st "✅ App reabierta, socket+token presentes. Fase 4/6: verify-deploy + smoke hello."

    let r4 = (phase4-verify $st)
    if not $r4.outcome.ok { return (fail $r4.state "fase 4 (verify)" (note-or-log $r4.outcome.note)) }
    let st = (rpt $r4.state $"f4 verify+smoke OK($st.drytag)")

    let r5 = (phase5-retire $st)
    if not $r5.outcome.ok { return (fail $r5.state "fase 5 (retire)" (note-or-log $r5.outcome.note)) }
    let st = match $r5.state.retire_result {
        "hecho" => {
            let s = (rpt $r5.state "f5 retire OK")
            announce $s "✅ Python retirado. Fase 6/6: cierre."
            $s
        }
        "skip (falta script)" => (rpt $r5.state "f5 retire SKIP (falta script)")
        _ => (rpt $r5.state "f5 retire SKIP (dryrun)")
    }
    phase6-final $st
}

def init-state [mode: Mode]: nothing -> RunState {
    # nu no tiene enums (medido: `enum`/literales fallan en parser) → guard.
    if not ($mode in [dryrun real]) {
        error make {msg: $"modo inválido: ($mode) — esperaba dryrun o real"}
    }
    let home = $nu.home-dir
    let repo = ($env.CURRENT_FILE | path dirname | path dirname)
    let ts = (date now | format date "%Y%m%d-%H%M%S")
    {
        mode: $mode,
        retire: "skip",
        quit_done: false,
        retire_result: "pendiente",
        report: "",
        exit_code: 0,
        log_path: $"/tmp/deploy-fork-and-retire-($ts).log",
        repo: $repo,
        fork_dir: ($env.FORK_DIR? | default $"($home)/Projects/ZCode"),
        sock: $"($env.ZCODE_DATA_BASE_DIR? | default $"($home)/.zcode")/daemon-local.sock",
        token_file: $"($env.ZCODE_DATA_BASE_DIR? | default $"($home)/.zcode")/daemon-local.token",
        tg_cfg: $"($home)/.zcode/telegram-remote.json",
        retire_script: $"($repo)/scripts/retire-python.sh",
        drytag: "",
    }
}

# El parseo de flags lo hace nu (main): --help auto; flag desconocido → error
# de parser (rc 1 — el «2 uso» del bash no es replicable, ver header). El
# default es dryrun: cero acciones destructivas salvo --real explícito.
def main [
    --real    # el run de verdad (quit→build→reopen→verify→retire + Telegram)
    --dryrun  # default: fase 0 real (read-only), fases 1-6 narradas sin tocar nada
]: nothing -> nothing {
    let mode: Mode = if $real { "real" } else { "dryrun" }
    let st: RunState = (init-state $mode)
    let st = if $st.mode == "dryrun" { $st | update drytag " (SKIP dryrun)" } else { $st }
    log $st $"════ deploy-fork-and-retire — modo: ($st.mode) ════"
    log $st $"fork: ($st.fork_dir) · repo bot: ($st.repo) · log: ($st.log_path)"
    if $st.mode == "dryrun" {
        log $st "DRYRUN: sanity real (read-only) + narración — NI quit, NI build/swap, NI open, NI retire, NI Telegram."
    }
    announce $st $"🤖 Deploy auto \(($st.mode)\): canal daemon-local — fase 0/6 sanity..."

    # El equivalente del trap EXIT del bash: una sola línea final, para TODOS
    # los exits (0/3/4 del autómata; 1 para lo inesperado, como el set -e).
    let code = try {
        let st2 = (cutover $st)
        log $st2 $"── exit rc=($st2.exit_code) \(log: ($st2.log_path)\)"
        $st2.exit_code
    } catch {|e|
        log $st $"── exit rc=1 \(log: ($st.log_path)\) — error inesperado: ($e.msg)"
        1
    }
    exit $code
}
