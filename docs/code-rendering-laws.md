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
3. **PNG render** (silicon, the owner's teal palette) — colors and zero
   wrapping, but a tap-to-open viewer with pinch-zoom, no copy.

The bot offers road 3 on demand: code boxes and their outputs carry the
`📸` row (`PngShots` registry + `PngTaps` edge, palette A `#0B3B3C` +
OneHalfDark). The image flies ONLY on the tap — never a stream of photos.
NO image is wrapped (the owner, 25/11): every render grows as wide as its
longest line, and `PngRender.wrap` runs only when that render comes out past
Telegram's photo ceilings — the same last-resort law the diff drawer follows.

## 6. Caps that bound the code bodies

`CH_REPLY = 3900` classic · `RICH_CP = 12000` rich HTML house cap (API
ceiling 32768 cp / 500 blocks) · `BLOCK_CP = 3500` rich page/block ·
`PAGE_CP = 2800` tool-output page · `COMMAND_CLOSE_CP = 1000` command in a
box header · `COMMAND_CHUNK_CP = 3000` long-command message.

## 7. A diff opens SIDE BY SIDE, never as a unified stream

An edit/multiedit box's `📸` door paints the diff the way an IDE does
(`DiffShot` builds the rows, `DiffPng` draws them in AWT — no browser, no
external binary):

- two columns with the REAL line numbers, red cells on the old side, green
  on the new, and the **changed middle** — the span two lines do not share —
  on a hotter tone inside each cell;
- the bot's numbered format (`- 4 │ code`) and a standard unified diff both
  parse: the marker is the sign, the numbers are a gift;
- a plan with only additions (a fresh file) or only deletions keeps ONE
  column — an empty side would waste half the phone's width;
- a line is NEVER wrapped and never ends in dots (the owner, 25/11): the
  column takes the whole line, however long it is — the image grows sideways
  and the owner zooms. There is no fixed column cap: the width comes from the
  longest line, and the column narrows (wrapping the line) only when even a
  1× paint would pass Telegram's 10 000-px ceiling — a wrapped line beats a
  missing image;
- the column width comes from the longest line of the plan, so a short diff
  makes a narrow image instead of a 6:1 banner;
- the canvas is painted at the largest integer scale that still fits the
  pixel ceiling (up to 3×): the photo may look small on the phone, but a
  zoom finds real glyphs, and the height pads so the aspect never passes 8:1;
- 60 rows is the ceiling, and the last line says how many stayed behind;
- file headers (`--- a/x`, `+++ b/x`), hunk headers and the no-newline
  marker are not changes — the first sample drew them as a pair of paths.

Measured on the 25/11 five-shape album; enforced by `DiffShotTest` and
`DiffPngTest`.
