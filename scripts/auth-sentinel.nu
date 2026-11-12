#!/Users/lattenero/.local/bin/nu-nightly
# auth-sentinel.nu — macOS password/authorization dialogs, surfaced to Telegram.
#
# The gap (the owner's ask, 2026-11-12): when macOS raises a password dialog
# (Keychain, privileged install) nobody at the keyboard sees it — the fork
# does not know it exists and whatever caused it just waits. This sentinel
# watches the two on-demand processes that host those dialogs:
#
#   - SecurityAgent          (password / authorization / Keychain prompts)
#   - UserNotificationCenter (privileged install prompts)
#
# Both spawn on demand and exit once attended (verified live 12/11: a real
# SecurityAgent episode lived 13 s, 10:21:35 -> 10:21:48). Absence is the
# honest baseline. launchd runs this every 30 s; an episode shorter than
# that is one the owner attended in person — not ours to announce.
#
# The alert says WHAT asked and WHO probably caused it, and it NEVER asks
# for the password: secrets do not ride Telegram (a key already leaked once
# through a config; the login password will not). Recurring prompts are
# killed at the root instead (sudoers NOPASSWD lines, Keychain ACLs — the
# same cure as the fork's signing identity).
#
# The "already attended" tap lands as a marker file the daemon writes
# (state/auth-dismissed) and this loop consumes — the marker is the whole
# contract: one writer (the bot), one reader (here), pid keyed, stale
# markers discarded. SendMessage stays STATELESS (the healer's law: it
# never touches getUpdates, so it cannot fight the daemon's monopoly). No
# token or chat id here: they come from ~/.zcode/telegram-remote.json, and
# the token travels through curl's config on stdin, never argv.

const REPING_AFTER_S: int = 900
const WATCHED: list<string> = ["SecurityAgent", "UserNotificationCenter"]
# processes that plausibly REQUEST an auth dialog (basename match)
const ASKERS: list<string> = ["sudo", "security", "installer", "osascript", "codesign", "hdiutil", "diskutil", "spctl"]

def expanded [p: string]: nothing -> string {
    $p | str replace --all "~" $nu.home-dir
}

def cfg_path [name: string, fallback: string]: nothing -> string {
    let raw: string = ($env | get --optional $name | default $fallback | into string)
    expanded $raw
}

def now_s []: nothing -> int {
    date now | format date "%s" | into int
}

def log_line [log: string, message: string]: nothing -> nothing {
    let stamp = (date now | format date "%Y-%m-%dT%H:%M:%S%:z")
    let parent = ($log | path dirname)
    mkdir $parent
    $"($stamp) ($message)\n" | save --append $log
}

# The dialog's host process, or the honest dead baseline.
def detect []: nothing -> record {
    let hits = (ps | where name in $WATCHED | sort-by pid)
    if ($hits | is-empty) { return {alive: false, pid: 0, name: ""} }
    let first = ($hits | first)
    {alive: true, pid: ($first.pid | into int), name: ($first.name | into string)}
}

# THE decision, pure: every input is a value, every output is a record (the
# healer's discipline — testable from --selftest without touching ps).
#   - no dialog + open episode    -> resolve (silent when already dismissed)
#   - dialog + new pid            -> alert (a fresh episode is born)
#   - dialog + same pid, fresh    -> none (quiet)
#   - dialog + same pid, waited   -> ONE reping, then quiet
#   - dialog + same pid, dismissed-> none (quiet — the owner already spoke)
def decide [episode: record, present: record, now: int]: nothing -> record {
    if not $present.alive {
        if ($episode.open? == true) {
            return {action: "resolve", quiet: ($episode.dismissed? == true), episode: {}}
        }
        return {action: "none", quiet: true, episode: $episode}
    }
    if ($episode.open? == true) and ($episode.pid? == $present.pid) {
        if ($episode.dismissed? == true) {
            return {action: "none", quiet: true, episode: $episode}
        }
        let notified: int = ($episode.notified? | default $now | into int)
        if ($episode.repinged? != true) and ($now - $notified >= $REPING_AFTER_S) {
            return {action: "reping", quiet: false, episode: ($episode | upsert repinged true)}
        }
        return {action: "none", quiet: true, episode: $episode}
    }
    let clock = (date now | format date "%H:%M")
    {action: "alert", quiet: false, episode: {
        open: true,
        pid: ($present.pid | into int),
        name: ($present.name | into string),
        started_s: $now,
        started_clock: $clock,
        notified: $now,
        repinged: false,
        dismissed: false,
    }}
}

# BSD ps elapsed ("[[dd-]hh:]mm:ss") to seconds — the young-process signal.
def etime_to_s [e: string]: nothing -> int {
    let parts: list<string> = ($e | split row "-")
    let days: int = (if ($parts | length) > 1 { $parts | first | into int } else { 0 })
    let hms: list<int> = ($parts | last | split row ":" | each {|x| $x | into int })
    let n: int = ($hms | length)
    let h: int = (if $n >= 3 { $hms | first } else { 0 })
    let m: int = (if $n >= 2 { $hms | get ($n - 2) } else { 0 })
    let s: int = ($hms | last | default 0)
    ($days * 86400) + ($h * 3600) + ($m * 60) + $s
}

# WHO probably asked: asker-named processes always, the three youngest
# others as context — an honest guess, labeled as one, never a verdict.
def suspects []: nothing -> string {
    let rows = (do { ^/bin/ps -axo pid,etime,comm } | complete | get stdout | lines | skip 1)
    let parsed = ($rows | each {|l|
        $l | parse -r '^\s*(?<pid>\d+)\s+(?<etime>\S+)\s+(?<comm>\S+)' | first
    } | compact)
    if ($parsed | is-empty) { return "No identifiqué al peticionario." }
    let named = ($parsed | where {|r| ($r.comm | path basename) in $ASKERS })
    let young = ($parsed | where {|r|
        ($r.comm | path basename) not-in $ASKERS and (etime_to_s $r.etime) < 120
    } | sort-by {|r| etime_to_s $r.etime } | first 3)
    let all = ($named | append $young)
    if ($all | is-empty) { return "No identifiqué al peticionario." }
    $all | each {|r|
        $"• ($r.comm | path basename) — pid ($r.pid), hace (etime_to_s $r.etime)s"
    } | str join "\n"
}

def alert_text [episode: record, who: string]: nothing -> string {
    $"🔐 La Mac pidió contraseña/autorización.

Proceso: ($episode.name) — pid ($episode.pid) — desde las ($episode.started_clock).
Probable peticionario:
($who)

Queda esperando en la pantalla de la Mac. La contraseña no viaja por Telegram — si se repite, lo matamos de raíz."
}

def reping_text [episode: record]: nothing -> string {
    $"⏳ La Mac SIGUE esperando la contraseña: ($episode.name) — pid ($episode.pid) — desde las ($episode.started_clock)."
}

def resolve_text [episode: record]: nothing -> string {
    $"🔓 Se cerró el diálogo de contraseña de la Mac — pid ($episode.pid), había nacido a las ($episode.started_clock)."
}

# Stateless sendMessage (the healer's siren): button_pid >= 0 attaches the
# «already attended» tap (auth:<pid>); -1 sends bare text.
def notify [text: string, button_pid: int]: nothing -> nothing {
    let path = (cfg_path "AUTH_SENTINEL_TELEGRAM" "~/.zcode/telegram-remote.json")
    if not ($path | path exists) { return }
    let cfg = (try { open --raw $path | from json } catch { return })
    let token = ($cfg | get --optional token | default "")
    let chat = ($cfg | get --optional chat_id | default "")
    if ($token | is-empty) or ($chat | is-empty) { return }
    let base = {chat_id: ($chat | into string), text: $text}
    let body = (if $button_pid >= 0 {
        $base | insert reply_markup {inline_keyboard: [[{text: "✅ Ya la atendí", callback_data: $"auth:($button_pid)"}]]}
    } else {
        $base
    })
    # the token goes through curl's config on stdin, never argv
    let curl_cfg = $"url = \"https://api.telegram.org/bot($token)/sendMessage\"\n"
    let res = ($curl_cfg | ^curl -fsS --max-time 8 -K - -H "Content-Type: application/json" -d ($body | to json -r) | complete)
    if $res.exit_code != 0 {
        log_line (cfg_path "AUTH_SENTINEL_LOG" "~/.zcode/bot-kotlin/logs/auth-sentinel.log") $"notify FAILED: ($res.stderr | str trim)"
    }
}

# The pure matrix (decide) plus the elapsed parser — no ps, no network.
def selftest []: nothing -> nothing {
    let dead: record = {alive: false, pid: 0, name: ""}
    let live: record = {alive: true, pid: 36747, name: "SecurityAgent"}
    let open_e: record = {open: true, pid: 36747, name: "SecurityAgent", started_s: 1000, started_clock: "10:21", notified: 1000, repinged: false, dismissed: false}
    let cases: list<record> = [
        {name: "idle", got: (decide {} $dead 2000 | get action), want: "none"},
        {name: "resolve-open", got: (decide $open_e $dead 2000 | get action), want: "resolve"},
        {name: "resolve-quiet-when-dismissed", got: (decide ($open_e | upsert dismissed true) $dead 2000 | get quiet), want: true},
        {name: "alert-on-new", got: (decide {} $live 2000 | get action), want: "alert"},
        {name: "alert-episode-open", got: (decide {} $live 2000 | get episode.open), want: true},
        {name: "none-when-fresh", got: (decide $open_e $live 1100 | get action), want: "none"},
        {name: "reping-after-wait", got: (decide $open_e $live (1000 + $REPING_AFTER_S) | get action), want: "reping"},
        {name: "reping-once", got: (decide ($open_e | upsert repinged true) $live (1000 + $REPING_AFTER_S) | get action), want: "none"},
        {name: "none-when-dismissed", got: (decide ($open_e | upsert dismissed true) $live 1100 | get action), want: "none"},
        {name: "new-pid-replaces", got: (decide $open_e {alive: true, pid: 99, name: "UserNotificationCenter"} 1100 | get action), want: "alert"},
        {name: "etime-seconds", got: (etime_to_s "45"), want: 45},
        {name: "etime-mmss", got: (etime_to_s "3:28"), want: 208},
        {name: "etime-hhmmss", got: (etime_to_s "01:39:37"), want: 5977},
        {name: "etime-days", got: (etime_to_s "2-01:00:00"), want: 176400},
    ]
    let fails: list<record> = ($cases | where {|c| $c.got != $c.want })
    for f in $fails { print $"FAIL ($f.name): got ($f.got) want ($f.want)" }
    print $"selftest: ($cases | length) cases, ($fails | length) failures"
    if ($fails | is-not-empty) { exit 1 }
}

def main [--status, --dry-run, --selftest, --test-alert]: nothing -> nothing {
    if $selftest { selftest; return }
    let log = (cfg_path "AUTH_SENTINEL_LOG" "~/.zcode/bot-kotlin/logs/auth-sentinel.log")
    if $test_alert {
        notify "🔐 Prueba del sentinel — si leés esto y el botón contesta, el canal anda." 0
        log_line $log "test alert sent (callback auth:0)"
        return
    }

    let now = (now_s)
    let state = (cfg_path "AUTH_SENTINEL_STATE" "~/.zcode/bot-kotlin/state/auth-sentinel.json")
    let marker = (cfg_path "AUTH_SENTINEL_MARKER" "~/.zcode/bot-kotlin/state/auth-dismissed")
    let present = (detect)
    let episode = (if ($state | path exists) {
        try { open --raw $state | from json } catch { {} }
    } else {
        {}
    })

    # The bot's marker: READ here, consumed only in the act phase (status
    # and dry-run stay side-effect free). It names the episode it dismisses;
    # anything else is stale and dies silently.
    let marker_pid: int = (if ($marker | path exists) {
        try { open --raw $marker | str trim | into int } catch { -1 }
    } else {
        -1
    })
    let effective: record = (if ($episode.open? == true) and ($marker_pid == ($episode.pid? | default (-2))) {
        $episode | upsert dismissed true
    } else {
        $episode
    })

    let plan = (decide $effective $present $now)
    if $status {
        print ({present: $present, marker_pid: $marker_pid, plan: $plan} | to json -r)
        return
    }
    if $dry_run {
        print ($plan | to json -r)
        return
    }

    if $marker_pid >= 0 { rm -f $marker }
    let e = $plan.episode
    match $plan.action {
        "alert" => {
            notify (alert_text $e (suspects)) ($e.pid | into int)
            log_line $log $"ALERT pid:($e.pid) name:($e.name)"
        }
        "reping" => {
            notify (reping_text $e) ($e.pid | into int)
            log_line $log $"REPING pid:($e.pid)"
        }
        "resolve" => {
            if not $plan.quiet {
                notify (resolve_text $episode) -1
                log_line $log $"RESOLVED pid:($episode.pid? | default 0)"
            }
        }
        _ => {}
    }
    # persist only on change — the 30 s cadence is not a write clock
    if ($plan.episode | to json -r) != ($effective | to json -r) {
        mkdir ($state | path dirname)
        $plan.episode | to json -r | save -f $state
    }
}
