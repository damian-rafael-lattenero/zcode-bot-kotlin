package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * /mode's pure half — the conversation's COLLABORATION mode (Python's
 * `_cmd_mode`, handlers_system.py:180) served by the fork's OWN switch:
 * the `setMode` cap (A4) writes through the same path as the desktop's
 * toolbar (`switchCollaborationMode`), so the phone and the app can never
 * disagree about what is in force.
 *
 * Python only exposed plan/full-access; the fork's vocabulary is five
 * (DAEMON_CHANNEL_MODES). Exposing all five is the superior call — the
 * labels are the app's own (en-US i18n's `mode.label.glm.*`), never
 * invented here, and the two the owner already knows keep Python's emoji
 * (🗺/🔓 — the style guide's one-meaning law).
 *
 * The current mode is READ, never remembered: taskSnapshot's `meta.mode`
 * (the session file's own field) projected through the adapter's own
 * aliases — `default`/`acceptEdits` → build, `bypassPermissions`/`dontAsk`
 * → yolo ([toZCodeMode]'s mapping). A snapshot without a mode renders
 * «—» with no 🎯: an unknown is said, never guessed.
 */
object ModeMenu {

    /** One selectable mode: the wire id, the app's label, the app's one-line description. */
    data class Mode(val id: String, val label: String, val description: String)

    /** The fork's DAEMON_CHANNEL_MODES, Python's two first (the owner's vocabulary). */
    val MODES = listOf(
        Mode("plan", "🗺 Plan mode", "Plan before editing."),
        Mode("yolo", "🔓 Full access", "Run with fewer confirmations."),
        Mode("build", "🛠 Ask before changes", "Ask before file changes."),
        Mode("edit", "📝 Edit automatically", "Edit files automatically."),
        Mode("guarded", "🛡 Autonomous mode", "Ask when there's risk."),
    )

    private val BY_ID = MODES.associateBy { it.id }

    /**
     * THE DEFAULT (the owner's law, 4/11: «por defecto siempre Full
     * access»): a conversation whose snapshot has NO `meta.mode` IS
     * Full access — the card says it (🎯 on the yolo row, the label said
     * as default, never «—»), and the wiring's `task_run_started`
     * enforcement WRITES it into the fork so the app runs it too.
     */
    const val DEFAULT_MODE_ID = "yolo"

    /** The mode in force: the snapshot's, or [DEFAULT_MODE_ID] when it carries none. */
    fun effective(current: String?): String = current ?: DEFAULT_MODE_ID

    /** The typed/tapped word → the mode, case-insensitive (Python lowercases too). */
    fun byId(raw: String?): Mode? = BY_ID[raw?.trim()?.lowercase()]

    /** The unknown-mode line's vocabulary — every id the fork accepts, said out loud. */
    fun ids(): String = MODES.joinToString(" · ") { it.id }

    /**
     * The snapshot's `meta.mode` → the CHANNEL's id. The adapter's own
     * projection decides the aliases (zcodeTaskServiceAdapter's
     * toZCodeMode): the legacy automation values land on build/yolo, and
     * anything outside the five is an unknown (null), never a guess.
     */
    fun normalize(raw: String?): String? = when (val v = raw?.trim()?.lowercase()) {
        null, "" -> null
        "default", "acceptedits", "autoedit" -> "build"
        "bypasspermissions", "dontask" -> "yolo"
        else -> v.takeIf { it in BY_ID }
    }

    /** taskSnapshot → the conversation's mode; null when the answer carries none. */
    fun currentFrom(snapshotJson: String): String? = runCatching {
        val root = Json.parseToJsonElement(snapshotJson) as? JsonObject ?: return null
        normalize(root.obj("meta")?.str("mode"))
    }.getOrNull()

    /**
     * Python's card: the conversation, the mode in force, the tap promise.
     * A snapshot with NO mode says «Full access (por defecto)» and carries
     * the honest caveat (4/11): the very first turn of a brand-new
     * conversation runs the app's own default — the enforcement lands yolo
     * for the NEXT turn. Never «—»: the default is SAID, never a blank.
     */
    fun statusLine(title: String, current: String?): String {
        val eff = effective(current)
        val label = byId(eff)?.label ?: eff
        return if (current == null) {
            "🎯 «${PyText.esc(title)}»\n🎛 Modo: <b>${PyText.esc(label)}</b> (por defecto)\n" +
                "La primera vuelta de una charla nueva corre el default del app; " +
                "Full access entra desde la próxima.\nTocá para cambiar (aplica al próximo turno):"
        } else {
            "🎯 «${PyText.esc(title)}»\n🎛 Modo: <b>${PyText.esc(label)}</b>\n" +
                "Tocá para cambiar (aplica al próximo turno):"
        }
    }

    /**
     * FOUR rows, one intent each — plan NEVER appears here (4/11: plan
     * lives in /plan_mode now); the mode in force carries 🎯 (never a
     * bare ●). The vocabulary keeps all five: an old card's mo:plan tap
     * and the typed word still work, and /plan_mode writes through the
     * same switch.
     */
    fun keyboard(current: String?): JsonObject = buildJsonObject {
        val eff = effective(current)
        putJsonArray("inline_keyboard") {
            MODES.filter { it.id != "plan" }.forEach { m ->
                val mark = if (m.id == eff) "🎯" else "○"
                add(buildJsonArray { add(Keyboards.button("$mark ${m.label}", "mo:${m.id}")) })
            }
        }
    }

    /** Python's unknown-mode refusal, with the real vocabulary (and no silent downgrade). */
    fun usage(): String = "⚠️ Modos: ${ids()} — /mode &lt;modo&gt;"

    /** The applied copy — Python's own promise, in the bot's language. */
    fun applied(title: String, mode: Mode): String =
        "🎛 Modo de «${PyText.esc(title)}»: <b>${PyText.esc(mode.label)}</b> ✅\n(aplica al próximo turno)"
}
