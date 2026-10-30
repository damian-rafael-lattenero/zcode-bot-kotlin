package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The /plan command's pure half: parse the channel's modelSelection result
 * (the fork's 3d07cace shapes) and build the tap-to-change keyboard.
 * Callback vocabulary (≤64B): pl:<providerId> plan, pm:<modelId> model,
 * pe:<effort> reasoning, pd: copy the conversation's selection to the app
 * default.
 */
object PlanMenu {

    /** One selectable plan with its models (the fork's plans[] rows). */
    data class Plan(val providerId: String, val label: String, val models: List<Pair<String, String>>)

    /** The whole result, parsed. */
    data class Views(
        val scope: String,
        val plan: String?,
        val model: String?,
        val effort: String?,
        val defaultPlan: String?,
        val defaultModel: String?,
        val plans: List<Plan>,
    ) {
        fun planLabel(providerId: String?): String? = plans.firstOrNull { it.providerId == providerId }?.label
        fun modelLabel(modelId: String?): String? =
            plans.flatMap { it.models }.firstOrNull { it.first == modelId }?.second
    }

    fun parse(resultJson: String): Views? {
        val root = runCatching { Json.parseToJsonElement(resultJson).jsonObject }.getOrNull() ?: return null
        fun sel(key: String): Triple<String, String, String?>? {
            val o = root[key] as? JsonObject
            val provider = o?.str("providerId")
            return if (provider == null) {
                null
            } else {
                Triple(provider, o.str("modelId") ?: "", o.str("reasoningLevel"))
            }
        }
        val cur = sel("selection")
        val def = sel("default")
        val plans = (root["plans"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { p ->
                val o = (p as? JsonObject) ?: return@mapNotNull null
                val pid = o.str("providerId") ?: return@mapNotNull null
                Plan(
                    providerId = pid,
                    label = o.str("label") ?: pid,
                    models = (o["models"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { m ->
                            val mo = m as? JsonObject ?: return@mapNotNull null
                            mo.str("id")?.let { it to (mo.str("label") ?: it) }
                        }
                        .orEmpty(),
                )
            }
            .orEmpty()
        if (cur == null && plans.isEmpty()) return null // nothing usable — the caller speaks the degrade
        return Views(
            scope = root.str("scope") ?: "conversation",
            plan = cur?.first,
            model = cur?.second?.takeIf { it.isNotEmpty() },
            effort = cur?.third,
            defaultPlan = def?.first,
            defaultModel = def?.second?.takeIf { it.isNotEmpty() },
            plans = plans,
        )
    }

    /** The status line: what IS, conversation and app default. */
    fun statusLine(v: Views, title: String?): String = buildString {
        append("🎯 <b>«${PyText.esc(title ?: "conversación")}»</b>\n")
        append("Plan: <b>${PyText.esc(v.planLabel(v.plan) ?: v.plan ?: "—")}</b>")
        append(" · Modelo: <b>${PyText.esc(v.modelLabel(v.model) ?: v.model ?: "—")}</b>")
        v.effort?.let { append(" · Effort: <b>${PyText.esc(it)}</b>") }
        append("\n")
        if (v.defaultPlan != null) {
            append("Default de la app: ${PyText.esc(v.planLabel(v.defaultPlan) ?: v.defaultPlan)}")
            v.defaultModel?.let { append(" · ${PyText.esc(v.modelLabel(it) ?: it)}") }
        }
    }

    /** The tap-to-change rows: plans, then the current plan's models, then effort, then default. */
    fun keyboard(v: Views): JsonObject = buildJsonObject {
        putJsonArray("inline_keyboard") {
            v.plans.forEach { plan ->
                val mark = if (plan.providerId == v.plan) "🎯" else "🟢"
                add(
                    buildJsonArray {
                        add(Keyboards.button("$mark ${plan.label}", "pl:${plan.providerId}"))
                    },
                )
            }
            v.plans.firstOrNull { it.providerId == v.plan }?.models?.forEach { (id, label) ->
                val mark = if (id == v.model) "🎯" else "▪️"
                add(
                    buildJsonArray {
                        add(Keyboards.button("$mark $label", "pm:$id"))
                    },
                )
            }
            listOf("high", "medium", "low").forEach { level ->
                val mark = if (v.effort == level) "🎯" else "▪️"
                add(
                    buildJsonArray {
                        add(Keyboards.button("$mark effort $level", "pe:$level"))
                    },
                )
            }
            if (v.plan != null) {
                add(
                    buildJsonArray {
                        add(Keyboards.button("📌 dejar como default de la app", "pd:"))
                    },
                )
            }
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}
