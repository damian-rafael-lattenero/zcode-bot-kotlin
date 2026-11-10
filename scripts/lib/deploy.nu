# Shared helpers for the bot's deploy scripts.
#
# Every helper is typed and pure where it can be; the effects (launchctl,
# files) live at the edge of the script that calls them. The shebang pins
# the interpreter: ~/.local/bin/nu-nightly (0.116.2-nightly.28 at the time
# of the migration). `nu-check --debug` is the gate.

export def log [msg: string]: nothing -> nothing {
    print $"[deploy] ($msg)"
}

export def step [msg: string]: nothing -> nothing {
    print $"\n==> ($msg)"
}

# One-shot Telegram announce, independent of the app and of the bot's
# transport: token/chat come from the config the poller itself reads. The
# announcement NEVER breaks the caller (do -i + catch). THE PHASES' VOICE:
# the phone must never sit blind through a build (measured 4/11 — the owner
# read silence for minutes). NOT hooked into `step`: the recorder's and the
# comparator's deploys use step too and must not speak.
export def announce [text: string]: nothing -> nothing {
    send-telegram $text null
}

# THE LOUD VOICE (the owner's order, 10/11: «al finalizar cada script un
# aviso bien llamativo»): HTML bold so the END of a deploy (and every
# failure) cannot be skimmed past in the chat. Same never-breaks contract.
export def announce-loud [html: string]: nothing -> nothing {
    send-telegram $html "HTML"
}

def send-telegram [text: string, parse_mode: string]: nothing -> nothing {
    let cfg_path: string = ($nu.home-dir | path join ".zcode/telegram-remote.json")
    if not ($cfg_path | path exists) { return }
    try {
        let cfg: any = (open $cfg_path)
        let token: string = ($cfg.token? | default "")
        let chat: string = ($cfg.chat_id? | default "")
        if ($token | is-empty) or ($chat | is-empty) { return }
        let body: string = if $parse_mode == null {
            ({chat_id: $chat, text: $text} | to json)
        } else {
            ({chat_id: $chat, text: $text, parse_mode: $parse_mode} | to json)
        }
        do -i {
            http post -m 10sec -t application/json $"https://api.telegram.org/bot($token)/sendMessage" $body -r | ignore
        }
    } catch {|_e| }
}

export def die [msg: string]: nothing -> nothing {
    print -e $"✗ ($msg)"
    exit 1
}

export def user-id []: nothing -> string {
    (^id -u | str trim)
}

export def agent-loaded [label: string]: nothing -> bool {
    let r = (do { ^launchctl print $"gui/(user-id)/($label)" } | complete)
    $r.exit_code == 0
}

export def bootout-agent [label: string]: nothing -> nothing {
    if (agent-loaded $label) {
        let r = (do { ^launchctl bootout $"gui/(user-id)/($label)" } | complete)
        if $r.exit_code != 0 {
            print -e $"⚠ bootout ($label) failed: ($r.stderr | str trim)"
        }
    }
}

# A label can sit in launchd's persistent DISABLED list (the retired
# Python healer left com.zcode.bot-healer there). bootstrap then answers
# error 5 — "Input/output error", a lie about the cause — until the label
# is enabled. Enabling a label we own is idempotent and safe.
export def enable-agent [label: string]: nothing -> nothing {
    let r = (do { ^launchctl enable $"gui/(user-id)/($label)" } | complete)
    if $r.exit_code != 0 {
        print -e $"⚠ launchctl enable ($label) failed: ($r.stderr | str trim)"
    }
}

# Render the repo's <label>.plist (the __HOME__ placeholder) into
# ~/Library/LaunchAgents and (re)bootstrap it. bootout's teardown races
# the bootstrap (measured: error 5, "Input/output error"), so retry once
# after a beat — the same fix the bash deploy earned the hard way.
export def bootstrap-agent [repo: string, label: string]: nothing -> nothing {
    let home = $nu.home-dir
    let src = ($repo | path join "scripts" $"($label).plist")
    let dst = ($home | path join "Library/LaunchAgents" $"($label).plist")
    if not (($src | path type) == "file") {
        die $"missing plist: ($src)"
    }
    let rendered = (open --raw $src | str replace --all "__HOME__" $home)
    $rendered | save -f $dst
    bootout-agent $label
    enable-agent $label
    let first = (do { ^launchctl bootstrap $"gui/(user-id)" $dst } | complete)
    if $first.exit_code != 0 {
        sleep 2sec
        let second = (do { ^launchctl bootstrap $"gui/(user-id)" $dst } | complete)
        if $second.exit_code != 0 {
            die $"launchctl bootstrap ($label) failed: ($second.stderr | str trim)"
        }
    }
}

# Wait until a path exists (file, socket, anything non-empty from `path type`).
export def wait-for-path [path: string, timeout_ms: int, step_ms: int]: nothing -> bool {
    mut waited = 0
    mut found = false
    while ($waited < $timeout_ms) {
        if (($path | path type) != "") {
            $found = true
            break
        }
        sleep ($step_ms * 1ms)
        $waited += $step_ms
    }
    $found
}

export def tail-lines [path: string, count: int]: nothing -> list<string> {
    if (($path | path type) != "file") {
        return []
    }
    open --raw $path | lines | last $count
}
