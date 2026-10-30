#!/Users/lattenero/.local/bin/nu-nightly
#
# BLACKBOX E2E — the Kotlin Telegram bot's REAL surfaces, from the OUTSIDE.
#
# What a test suite cannot see: the token working, the wire reachable,
# Telegram's own answer. This script hits the Bot API's READ-ONLY surfaces
# (getMe, getWebhookInfo) and, ONLY when the owner exports a test chat id,
# one send-only hello (sendMessage) — the only write it will ever do.
# The LIVE command sends need his phone: the script prints the manual
# checklist the bot's nine commands deserve (the verdict table lives in
# the audit report; this is the routine that keeps it true).
#
# Token: $env.ZBB_TOKEN, else ~/.zcode/telegram-remote.json (READ-ONLY —
# the daemon's own config, never written here).
# Optional send test: $env.ZBB_TEST_CHAT_ID (skip silently when absent).

const API = "https://api.telegram.org"
const REMOTE_CONFIG = "~/.zcode/telegram-remote.json"

type Check = record<name: string, ok: bool, detail: string>

# The token: env first, the daemon's config (read-only) as fallback.
def resolve_token []: nothing -> string {
    if ($env | get --optional ZBB_TOKEN) != null {
        return ($env.ZBB_TOKEN | str trim)
    }
    let cfg = ($REMOTE_CONFIG | path expand)
    if ($cfg | path exists) {
        let parsed = (open --raw $cfg | from json)
        let tok = ($parsed | get --optional token)
        if $tok != null and ($tok | str trim) != "" {
            return ($tok | str trim)
        }
    }
    ""
}

# One Bot API call, parsed; null when Telegram says no (never an exception).
def tg [token: string, method: string]: nothing -> any {
    try {
        http get $"($API)/bot($token)/($method)"
    } catch {
        null
    }
}

def render [checks: list<Check>]: nothing -> nothing {
    for c in $checks {
        let mark = (if $c.ok { "PASS" } else { "FAIL" })
        print $"($mark)  ($c.name) — ($c.detail)"
    }
}

# The owner's manual half — the live sends only his phone can make true.
def checklist []: nothing -> nothing {
    print ""
    print "═══ MANUAL CHECKLIST (the phone half) ═══"
    print "/help        → the nine, exactly as the «/» menu"
    print "/tasks       → conversations by project, 🟢 rows, 🎯 on the active"
    print "/tasks n     → binds the n-th row («🎯 Active conversation: …»)"
    print "/tasks @sid  → same bind as the button tap"
    print "/tasks new   → next free text CREATES (first message lands in the draft)"
    print "/all_tasks   → loose conversations included · /all_tasks n picks"
    print "free text    → lands in the OPEN conversation (never creates one)"
    print "free text mid-turn → «⏳ The Mac is busy…» + ⚡/⏳ question; ⚡ pushes NOW"
    print "/status      → context, active title, model line, running-now list, live view"
    print "/plan        → plan/model/effort menu; each tap applies ONE field"
    print "/plan 📌     → copies the conversation's selection to the app default"
    print "/stop        → soft stop of the active turn + 💀 confirm row"
    print "/stop go     → 💀 Hard stop: every run"
    print "/nuclear     → ☢️ card + Confirm/Cancel · Cancel touches nothing"
    print "/nuclear go  → the full ladder (stopAll → kills → app bootout → reconnect)"
    print "/quota       → coding-plan credits, resets, ⚠️ at ≥75% (fork caveat: not_configured)"
    print "/subagents   → the live lanes + thought counts (or the honest «works alone»)"
    print "CoT cajita   → 🧠 cot expands, ⤴️ plegar folds"
    print "UNKNOWN cmd  → 🤷 banner, never forwarded to a conversation"
}

def main []: nothing -> nothing {
    let token = (resolve_token)
    if $token == "" {
        print "SKIP  no token — export ZBB_TOKEN (or let the script read ~/.zcode/telegram-remote.json read-only)"
        checklist
        exit 1
    }

    let me = (tg $token "getMe")
    let me_ok = ($me != null and $me.ok? == true)
    let me_detail = (if $me_ok { $"bot @($me.result.username?) id=($me.result.id?)" } else { "getMe failed — token dead or wire down?" })

    let hook = (tg $token "getWebhookInfo")
    let hook_ok = ($hook != null and $hook.ok? == true)
    let url = (if $hook_ok { $hook.result.url? } else { null })
    let hook_detail = (if $hook_ok {
        if $url == null or $url == "" {
            "no webhook set — long-polling, as the daemon speaks it"
        } else {
            $"UNEXPECTED webhook url=($url) — the daemon polls; who set this?"
        }
    } else {
        "getWebhookInfo failed"
    })

    # The ONE permitted write: a send-only hello to the owner's TEST chat.
    # Skipped entirely (no check row) when no test chat id is exported.
    let test_chat = ($env | get --optional ZBB_TEST_CHAT_ID)
    let send_check: list<Check> = (if $test_chat != null {
        let sent = (tg $token $"sendMessage?chat_id=($test_chat)&text=:blackbox-e2e%20hello%20—the%20wire%20is%20alive")
        let sent_ok = ($sent != null and $sent.ok? == true)
        let sent_detail = (if $sent_ok { $"delivered message_id=($sent.result.message_id?) to ($test_chat)" } else { "sendMessage failed — chat id wrong or bot blocked?" })
        [{name: "sendMessage (test chat)", ok: $sent_ok, detail: $sent_detail}]
    } else {
        []
    })

    let checks: list<Check> = ([
        {name: "getMe (identity)", ok: $me_ok, detail: $me_detail},
        {name: "getWebhookInfo (poll surface)", ok: $hook_ok, detail: $hook_detail},
    ] ++ $send_check)

    render $checks
    checklist
    if not (($checks | where ok == false | length) == 0) {
        exit 1
    }
}
