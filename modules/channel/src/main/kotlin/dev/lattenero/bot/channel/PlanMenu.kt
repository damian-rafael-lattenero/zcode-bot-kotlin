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
 * (the fork's 3d07cace shapes + the additive reasoningLevels per model) and
 * build the tap-to-change keyboard. Callback vocabulary (≤64B):
 * pl:<providerId> plan, pm:<modelId> model, pe:<effort> reasoning,
 * pd: copy the conversation's selection to the app default.
 */
object PlanMenu {

    /** One selectable model with its effort vocabulary (the fork's optionSpecs levels). */
    data class Model(val id: String, val label: String, val reasoningLevels: List<String> = emptyList())

    /** One selectable plan with its models (the fork's plans[] rows). */
    data class Plan(val providerId: String, val label: String, val models: List<Model>)

    /** The whole result, parsed. */
    data class Views(
        val scope: String,
        val plan: String?,
        val model: String?,
        val effort: String?,
        val defaultPlan: String?,
        val defaultModel: String?,
        val defaultEffort: String?,
        val plans: List<Plan>,
    ) {
        fun planLabel(providerId: String?): String? = plans.firstOrNull { it.providerId == providerId }?.label
        fun modelLabel(modelId: String?): String? =
            plans.flatMap { it.models }.firstOrNull { it.id == modelId }?.label

        /**
         * The effort rows to render: the CURRENT model's real vocabulary (the
         * same catalog the server validates pe: against — «max» exists for the
         * GLM plans even though it's not in the legacy three). Falls back to
         * the legacy trio only when the answer comes from an older fork
         * without the additive reasoningLevels.
         */
        fun effortLevels(): List<String> =
            plans.firstOrNull { it.providerId == plan }?.models
                ?.firstOrNull { it.id == model }?.reasoningLevels
                ?.takeIf { it.isNotEmpty() }
                ?: listOf("high", "medium", "low")
    }

    @Suppress("ReturnCount") // one guard per shape: unparseable, nothing-usable, the parsed views
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
                            val id = mo.str("id") ?: return@mapNotNull null
                            Model(
                                id = id,
                                label = mo.str("label") ?: id,
                                reasoningLevels = (mo["reasoningLevels"] as? kotlinx.serialization.json.JsonArray)
                                    ?.mapNotNull { level ->
                                        (level as? kotlinx.serialization.json.JsonPrimitive)
                                            ?.takeIf { it.isString }?.content
                                    }
                                    .orEmpty(),
                            )
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
            defaultEffort = def?.third,
            plans = plans,
        )
    }

    /** The status line: what IS, conversation and app default — the three fields always visible. */
    fun statusLine(v: Views, title: String?): String = buildString {
        append("🎯 <b>«${PyText.esc(title ?: "conversación")}»</b>\n")
        append("Plan: <b>${PyText.esc(v.planLabel(v.plan) ?: v.plan ?: "—")}</b>")
        append(" · Modelo: <b>${PyText.esc(v.modelLabel(v.model) ?: v.model ?: "—")}</b>")
        append(" · Effort: <b>${PyText.esc(v.effort ?: "—")}</b>")
        append("\n")
        if (v.defaultPlan != null) {
            append("Default de la app: ${PyText.esc(v.planLabel(v.defaultPlan) ?: v.defaultPlan)}")
            v.defaultModel?.let { append(" · ${PyText.esc(v.modelLabel(it) ?: it)}") }
            v.defaultEffort?.let { append(" · ${PyText.esc(it)}") }
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
            v.plans.firstOrNull { it.providerId == v.plan }?.models?.forEach { model ->
                val mark = if (model.id == v.model) "🎯" else "▪️"
                add(
                    buildJsonArray {
                        add(Keyboards.button("$mark ${model.label}", "pm:${model.id}"))
                    },
                )
            }
            // The effort rows speak the CURRENT model's own vocabulary — the
            // active level (p. ej. «max») siempre lleva su 🎯.
            v.effortLevels().forEach { level ->
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
