# Code rendering on Telegram — the measured laws (25/11/2026)

> Why the bot shapes long shell lines instead of trusting the client to scroll.
> Every claim below was measured on the owner's phone, not assumed.

## 1. The phone wraps EVERY code block

Probe A–D (25/11, four silent messages to the owner's chat):

| Probe | Shape | Result |
|---|---|---|
| A | classic `<pre><code class="language-kotlin">` | wraps — **no horizontal scroll** |
| B | classic bare `<pre>` | wraps |
| C | classic `<pre><code class="language-nu">` | wraps, label shown, no colors |
| D | classic `<pre><code class="language-bash">` over Nushell code | wraps, Bash colors |

The 24/11 "scroll law" (classic `<pre>` scrolls, rich `pre` wraps) is
**desktop-only**. On the phone no shape scrolls — tagged or bare, classic or
rich. Any comment in this repo that says otherwise predates this probe.

## 2. What each shape actually buys

| Shape | Label | Copy (tap header) | Colors | Scroll |
|---|---|---|---|---|
| classic `<pre><code class="language-X">` | yes | yes | yes, if X has a grammar | never |
| classic bare `<pre>` | no | no | no | never |
| rich `pre` (`language` field) | no (renders plain) | no | no | never |

So the language tag buys the label, the copy affordance and the client
highlighter — **not** width. Rich `pre` is still right where the rich
structure matters (the live card's instant prose); code bodies stay classic.

## 3. The highlighter is libprisma (prism.js port)

The client bundles `TelegramMessenger/libprisma`. Grammars relevant to this
fleet: `kotlin`/`kt`/`kts`, `json`, `yaml`, `diff`, `docker`, `gradle`,
`groovy`, `toml`, `ini`, `sql`, `csv`, `http`, `log`, `makefile`,
`shell-session`, `powershell`, `bash`/`sh`/`shell`, `java`, `scala`, `c`,
`cpp`, `csharp`, `go`, `rust`, `swift`, `python`, `ruby`, `php`, `lua`,
`markdown`, `xml`/`html`, `css`.

**Nushell has no grammar** (`nu`, `nushell` are absent). A `nu` block shows
the label and copies, but stays uncolored. Aliasing it to `bash` would color
it and lie in the label; `CodeLang.SHELL = "nu"` keeps the label honest.

## 4. Width policy: shape the line, never the lane

Because the client wraps at arbitrary points, the only width answer is
server-side and semantic:

- `CommandShape.shape` breaks a long shell line at its **unquoted `|`** and
  never mid-token (`LINE_CP = 50`). Applied by every renderer that emits a
  shell command (`LiveTurn`, `TranscriptFeed`, `ToolOutputPages`, `ToolBox`,
  `TurnDigest`).
- Non-shell long lines (Kotlin, JSON, logs) still wrap where they fall —
  there is no semantic seam to break on. Pretty-printing JSON is the one
  honest lever left.

## 5. If true horizontal scroll is ever required

Only three roads, each trades something away:

1. **Telegram Desktop** — its code blocks do scroll; the phone is the limit.
2. **File attachment** (`.nu`, `.kt`, `.md`) — scrolls and copies whole,
   but leaves the inline bubble.
3. **PNG render** (carbon-style) — colors and zero wrapping, but no copy and
   pinch-zoom reading.

## 6. Caps that bound the code bodies

`CH_REPLY = 3900` classic · `RICH_CP = 12000` rich HTML house cap (API
ceiling 32768 cp / 500 blocks) · `BLOCK_CP = 3500` rich page/block ·
`PAGE_CP = 2800` tool-output page · `COMMAND_CLOSE_CP = 1000` command in a
box header · `COMMAND_CHUNK_CP = 3000` long-command message.
