# UI Style Guide — the channel's emoji, rows and cards

The vocabulary every keyboard builder (`Keyboards.kt`, `PlanMenu.kt`, `Questions.kt`)
and command render (`BotCommands.kt`) obeys. Distilled from the code and from the
retired Python bot (`~/Projects/bot/src/zcode_bot`) — **parity is the maximum, not
the target**. The reference implementations are the `sn:` detail card
(`subagentDetailText` / `subagentDetailKeyboard`) and the pulse-gate bounces
(💀/🤔 refusals): new copy aligns to THEM.

## The one-meaning law

An emoji that means two things is a bug. Each glyph owns ONE concept (the table
below). Where a glyph serves both a stream verb and a button (⚡, 📖), the
SURFACE disambiguates: in the stream it is a process verb, on a button it is an
action. Never invent a second meaning for a listed glyph — extend the table
instead.

## Emoji for action

**Rows and lanes**

| Emoji | The ONE meaning it owns |
|---|---|
| 🟢 | conversation row (tasks lists) |
| 🔵 | subagent lane (every list, every card) |
| ▶️ | alive: open lane mark · «activos» view switch · resumed run |
| ⏹️ | stop: the `sst:` button · closed lane mark · «inactivos» view switch |
| ⏸️ | the ghost — a store row gone stale: «⏸️ hace Xm — probablemente terminó» |
| 💀 | ended / hard stop: `stop:go`, «ya terminó», the kill ladder |
| ☢️ | nuclear: the confirmation row and its go |
| 🗑 | discard ONE queued text (`qd:`) |
| ✍️ | ARM the next free text as an order/answer (`so:`, `qf:`) — never a send |
| 👁 | attach / read a stream (`sa:`, «👁 attach») |
| 👀 | F23 receipt reaction — a REACTION on the owner's message, never a button |
| 🎯 | the one in force: the active task, the current plan/model/effort |

**Verdicts**

| Emoji | The ONE meaning it owns |
|---|---|
| ✅ | confirmed / applied / the current pick in a picker |
| ❌ | deny (permission rows' pre-mark) / refused seal |
| ⚠️ | failed — always followed by the host's verbatim error |
| 🤷 | not found (unknown command, missing row, queue item gone) |
| 🤔 | hold / no signal (pulse-gate Unknown, /retry with nothing saved) |
| 📡 | channel down — the degraded host |
| 📭 | empty — no conversation / no rows / no answer / no usage |
| ⏳ | wait / parked — «vivo hace Xm», the F17 keep-queued offer |
| ⚡ | interrupt (`intr:`) — in the stream, the SHELL verb |
| 📖 | expand (`cb:`) — in the stream, the READ verb |
| 🙈 | collapse (`cb:`) |
| 📬 | parked mail: the queue card, the qgo ack, /last's answer |
| 🚀 | launch / fired: `ql:`, /run's new conversation |
| 🗜 | compact · 🪟 context window · 📈 usage · 📊 quota/status |
| 🧠 | thinking chars / the context meter header |
| 🔴 🟡 🟢 | context meter ≥80% / ≥50% / below (Python's reporting law) |
| 🎁 | a gift plane: a granted quota reset or a claimable offer (/quota's lines and its Reclamar/Usar buttons) |
| 🔴 (leading) | an UNCLAIMED offer — act now (/quota's claimable line; same glyph as the hot meter, the slot disambiguates) |
| 📣 | feed verbosity — 🔈 summary · 🔉 normal · 🔊 verbose |
| 🗺 | plan mode · 🔓 full access · 🎛 mode |
| 🛠 📝 🛡 | /mode's other three rows: build ("Ask before changes") · edit ("Edit automatically") · guarded ("Autonomous mode") — labels copied from the app's own i18n, never invented |
| 🔄 | running stamp on a list row / the restart leg of the nuclear ladder |
| ⚔️ | order delivered · 🌀 steer · 🚧 project boundary · 🪦 bound and gone |
| 📄 | /last pointer · ✂️ an honest cut («the tail was cut») |
| 🔌 ✏️ | stream verbs only: MCP · writing/editing (`ToolBox.familyOf` — frozen) |

## Row grammar

1. Primary action first; read/nav → arm → run → stop. The destructive END
   (🗑/💀/☢️) is LAST in its row — never first.
2. Pinned exceptions (Python parity): the confirmation rows — ☢️ Confirm ·
   🙅 Cancel (`nuc:go`/`nuc:no`) and the single 💀 Stop EVERYTHING. In an OFFER
   row (the F17 question) the aggressive option leads: it IS the offer.
3. One row = one intent; every button on the row serves it.
4. Label caps: rows cap the name at 34 (`TITLE_CAP`), queue previews at 24,
   question labels at 30. The DETAIL CARD is the long name's uncapped home —
   the row caps, the card never.
5. `callback_data` ≤ 64 bytes, never whitespace; fixed short datas preferred
   (the worst case is `t:<sid>` at 43).
6. A button a dead thing cannot honor is decoration: a closed lane or a ghost
   row earns no ✍️/⏹️; a dead agent's card earns NO action row at all.
7. Marks ride labels, actions ride buttons: ▶️/⏸️/💀 inside a label are
   status; stopping is its own bare ⏹️ button.

## Message format

1. Header bold, body plain, quoted data in a blockquote (the plan clip in the
   question card). Every interpolated name/title goes through `PyText.esc`.
2. Honest status lines, derived from REAL timestamps, never decorated:
   «⏳ vivo hace Xm» · «💀 terminó» · «▶️ corriendo» · «⏸️ hace Xm —
   probablemente terminó».
3. A refusal says what did NOT happen: «la orden NO voló», «NADA voló», «text
   did NOT reach a conversation», «nothing was touched». A refusal that only
   explains policy is half a refusal — name the thing that did not happen.
4. Failures carry the host's verbatim: «⚠️ X failed (code): message».
5. 📡 down ≠ 📭 none: degraded and empty are different words, never swapped.
6. Caps announce their cut: «✂️ …the tail was cut», the preview's own ….
7. Numbers only through `tokenRaw`/`fmtNum` — the number law is NOT this
   guide's to change; renders call them, never hand-format.

## Proceso / dato

The stream narrates PROCESS (🧠 thoughts, the ⚡/📖/✏️/🔌 tool verbs, CoT
blocks collapsed behind 📖); the cards hold DATA (answers, quota, usage,
meters, the sn: detail card). A card never narrates work; a stream never poses
as an answer. The /verbosity knob tunes the STREAM only — cards are always
whole.

## Sweep checklist (every new builder/render)

- [ ] every emoji it uses is in the table above (or the table grows first)
- [ ] destructive end last in its row; offer rows may lead with the offer
- [ ] label caps respected; the long name lives in the card
- [ ] callback_data ≤ 64 bytes, no whitespace
- [ ] dead targets earn no buttons
- [ ] refusals name what did NOT happen
- [ ] status lines derive from real timestamps
- [ ] numbers ride `tokenRaw`/`fmtNum`
