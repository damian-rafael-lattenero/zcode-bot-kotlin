package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * /plan_mode's pure half (4/11): plan LEFT /mode's keyboard — it lives in
 * THIS toggle now. Two states, read from the fork's truth (the snapshot's
 * `meta.mode == "plan"` — READ, never remembered), written through the
 * SAME `setMode` switch every other mode uses: ON writes `plan`, OFF
 * writes [ModeMenu.DEFAULT_MODE_ID] (the default law — back to Full
 * access; another mode is one /mode tap away).
 */
object PlanMode {

    /** The snapshot's `meta.mode` → the toggle's state (plan = ON, anything else = OFF). */
    fun isOn(currentMode: String?): Boolean = currentMode == "plan"

    /** The card: the conversation, the state line, the tap promise. */
    fun statusLine(title: String, on: Boolean): String =
        "🧭 Plan mode of «${PyText.esc(title)}»\n" +
            if (on) {
                "State: <b>✅ ON</b> (plan before editing)\nTap to change (applies to the next turn):"
            } else {
                "State: <b>⬜ OFF</b> (default: 🔓 Full access)\nTap to change (applies to the next turn):"
            }

    /**
     * The TWO buttons — `pmd:on` / `pmd:off` (the spec's `pm:` was ALREADY
     * /plan_model_effort's MODEL picker vocabulary — the toggle takes the
     * next free prefix, the labels keep the spec's law), the CURRENT one
     * carrying 🎯 (the same marking law as /mode's rows).
     */
    fun keyboard(on: Boolean): JsonObject = buildJsonObject {
        putJsonArray("inline_keyboard") {
            add(
                buildJsonArray {
                    add(Keyboards.button(if (on) "🎯 ✅ ON" else "✅ ON", "pmd:on"))
                    add(Keyboards.button(if (on) "⬜ OFF" else "🎯 ⬜ OFF", "pmd:off"))
                },
            )
        }
    }

    /** The applied copy — /mode's promise, in the toggle's 🧭 voice. */
    fun applied(title: String, on: Boolean): String =
        "🧭 Plan mode of «${PyText.esc(title)}»: " +
            if (on) {
                "<b>✅ ON</b> ✅\n(applies to the next turn)"
            } else {
                "<b>⬜ OFF</b> ✅ — back to 🔓 Full access\n(applies to the next turn)"
            }
}
