package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.putJsonArray
import java.time.ZoneId

/**
 * THE PLAN PICKER'S PURE HALF (the owner's 11/11 split): three focused
 * commands — /plan (each plan WITH its remaining quota, so he never picks
 * a plan with no tokens), /model (the plan in force's models) and /effort
 * (the plan+model in force's tiers). Every tap WRITES its one field and
 * the fork resolves the siblings against the catalog (resolveModelPair,
 * damian) — the local draft + ✅ Aceptar died with the bug they existed
 * for (the 3/11 cross-plan refusal), replaced by one-validator-at-the-
 * source. Callback vocabulary (≤64B): pl:<providerId> plan, pm:<modelId>
 * model, pe:<effort> reasoning, pdef: the DEFAULT LADDER ([PlanLadder]),
 * pd: copy the selection in force to the app default, pac:/pcl: the
 * retired mega-menu's verbs (answered with the honest old-menu note).
 *
 * ONE RENDER PER MENU (the mirror's byte-identical law): the commands and
 * [MenuMirrorRefresher] call the SAME card builders, so a mirrored menu's
 * refresh never fights its own text.
 */
@Suppress("TooManyFunctions") // parse + three cards + their keyboards — the picker's whole pure half
object PlanMenu {

    /** The pre-reasoningLevels trio — DISPLAY fallback only, descending on purpose. */
    private val LEGACY_EFFORTS = listOf("high", "medium", "low")

    /** One selectable model with its effort vocabulary (the fork's optionSpecs levels). */
    data class Model(val id: String, val label: String, val reasoningLevels: List<String> = emptyList())

    /** One selectable plan with its models (the fork's plans[] rows). */
    data class Plan(val providerId: String, val label: String, val models: List<Model>)

    /**
     * The parsed answer — a pure projection (the card builders are top-level
     * so this class keeps a single responsibility).
     */
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

        /** The model row of a plan — null when that plan does not expose it. */
        fun model(providerId: String?, modelId: String?): Model? =
            plans.firstOrNull { it.providerId == providerId }?.models?.firstOrNull { it.id == modelId }

        /** A model's REAL effort vocabulary (empty = the model declares none). */
        fun levels(providerId: String?, modelId: String?): List<String> =
            model(providerId, modelId)?.reasoningLevels.orEmpty()

        /**
         * The rows/validation vocabulary: the catalog's real levels, or the
         * legacy trio when an older fork publishes none (the same fallback
         * the menu has always rendered). The fork is the WRITE validator —
         * this vocabulary only shapes what the menus SHOW.
         */
        fun effortVocabulary(providerId: String?, modelId: String?): List<String> =
            levels(providerId, modelId).takeIf { it.isNotEmpty() } ?: LEGACY_EFFORTS

        /**
         * The completion the fork itself applies — the catalog's HIGHEST
         * tier (its own `at(-1)`). The legacy trio is descending, so its
         * safe default is the first («high») — but the fork completes a
         * NEW selection with its own rule, so the ladder never needs this.
         */
        fun defaultEffort(providerId: String?, modelId: String?): String =
            levels(providerId, modelId).lastOrNull() ?: LEGACY_EFFORTS.first()

        /** The plan the /model and /effort menus LIST against: the plan in force, else the app default's. */
        val shownPlan: String? get() = plan ?: defaultPlan
    }

    @Suppress("ReturnCount") // one guard per shape: unparseable, nothing-usable, the parsed views
    fun parse(resultJson: String): Views? {
        val root = runCatching { Json.parseToJsonElement(resultJson).jsonObject }.getOrNull() ?: return null
        fun sel(key: String): Triple<String, String, String?>? {
            val o = root[key] as? JsonObject
            val provider = o?.field("providerId")
            return if (provider == null) {
                null
            } else {
                Triple(provider, o.field("modelId") ?: "", o.field("reasoningLevel"))
            }
        }
        val cur = sel("selection")
        val def = sel("default")
        val plans = (root["plans"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { p ->
                val o = (p as? JsonObject) ?: return@mapNotNull null
                val pid = o.field("providerId") ?: return@mapNotNull null
                Plan(
                    providerId = pid,
                    label = o.field("label") ?: pid,
                    models = (o["models"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { m ->
                            val mo = m as? JsonObject ?: return@mapNotNull null
                            val id = mo.field("id") ?: return@mapNotNull null
                            Model(
                                id = id,
                                label = mo.field("label") ?: id,
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
            scope = root.field("scope") ?: "conversation",
            plan = cur?.first,
            model = cur?.second?.takeIf { it.isNotEmpty() },
            effort = cur?.third,
            defaultPlan = def?.first,
            defaultModel = def?.second?.takeIf { it.isNotEmpty() },
            defaultEffort = def?.third,
            plans = plans,
        )
    }

    // ── the three cards — ONE render per menu, the command and the mirror share them ──

    /**
     * /plan's card: the status, ONE line per plan with its quota (the
     * owner's decision data), the sibling-commands hint, and the plan
     * buttons — each with its compact quota suffix beside the name.
     */
    fun planCard(
        v: Views,
        title: String?,
        quota: Map<String, PlanQuota.Mark>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): MenuRender = MenuRender(
        text = buildString {
            append(statusLine(v, title))
            append("\n\n<b>Quota por plan</b> — «tiene tokens o no», al lado de cada nombre:")
            v.plans.forEach { p -> append("\n").append(PlanQuota.line(p.label, quota[p.providerId], nowMs, zone)) }
            append("\n\nModelo: /model · Effort: /effort")
        },
        markup = planKeyboard(v, quota),
    )

    /**
     * /model's card: the models of the plan in force (the app default's
     * when none), the hint, the model buttons. Null = no plan to list
     * against (the command speaks the honest refusal).
     */
    @Suppress("ReturnCount") // one guard per honest state (no plan, unknown plan, no models, the card)
    fun modelCard(v: Views, title: String?): MenuRender? {
        val shown = v.shownPlan ?: return null
        val plan = v.plans.firstOrNull { it.providerId == shown } ?: return null
        if (plan.models.isEmpty()) return null
        val body = buildString {
            append(statusLine(v, title))
            append("\n\nModelos de <b>${PyText.esc(v.planLabel(shown) ?: shown)}</b> — el plan vigente es el dueño:")
            append("\n\nPlan: /plan · Effort: /effort")
        }
        return MenuRender(body, modelKeyboard(plan, v.model))
    }

    /**
     * /effort's card: the tiers of the plan+model in force, the hint, the
     * effort buttons. Null = no plan, no model, or a model that publishes
     * no tiers (the fork would refuse the write — the menu says so first).
     */
    @Suppress("ReturnCount") // one guard per honest state (no plan, no model, no tiers, the card)
    fun effortCard(v: Views, title: String?): MenuRender? {
        val shownPlan = v.shownPlan ?: return null
        val shownModel = v.model ?: v.defaultModel ?: return null
        val levels = v.levels(shownPlan, shownModel)
        if (levels.isEmpty()) return null
        val body = buildString {
            append(statusLine(v, title))
            append("\n\nEffort de <b>${PyText.esc(v.modelLabel(shownModel) ?: shownModel)}</b>" +
                " (plan ${PyText.esc(v.planLabel(shownPlan) ?: shownPlan)}):")
            append("\n\nPlan: /plan · Modelo: /model")
        }
        return MenuRender(body, effortKeyboard(levels, v.effort))
    }

    /** The status line: what IS, and the app default — no draft, nothing staged. */
    fun statusLine(v: Views, title: String?): String = buildString {
        append("🎯 <b>«${PyText.esc(title ?: "conversación")}»</b>\n")
        append(currentLine(v))
        defaultLine(v)?.let { append("\n").append(it) }
    }

    private fun currentLine(v: Views): String =
        "Plan: <b>${PyText.esc(v.planLabel(v.plan) ?: v.plan ?: "—")}</b>" +
            " · Modelo: <b>${PyText.esc(v.modelLabel(v.model) ?: v.model ?: "—")}</b>" +
            " · Effort: <b>${PyText.esc(v.effort ?: "—")}</b>"

    private fun defaultLine(v: Views): String? {
        val plan = v.defaultPlan ?: return null
        return "Default de la app: ${PyText.esc(v.planLabel(plan) ?: plan)}" +
            (v.defaultModel?.let { " · ${PyText.esc(v.modelLabel(it) ?: it)}" } ?: "") +
            (v.defaultEffort?.let { " · ${PyText.esc(it)}" } ?: "")
    }

    // ── the keyboards ─────────────────────────────────────────────────────

    /** /plan's buttons: one per plan (quota beside the name), the ⭐ ladder row, the 📌 default. */
    fun planKeyboard(v: Views, quota: Map<String, PlanQuota.Mark>): JsonObject {
        val rows = v.plans.map { plan ->
            val mark = if (plan.providerId == v.plan) "🎯" else "🟢"
            listOf("$mark ${plan.label}${PlanQuota.compact(quota[plan.providerId])}" to "pl:${plan.providerId}")
        } + listOf(
            listOf("⭐ Default — el primer plan con tokens" to "pdef:"),
        ) + defaultRow(v)
        return keyboard(rows)
    }

    /** /model's buttons: the shown plan's models, 🎯 on the one in force. */
    private fun modelKeyboard(plan: Plan, current: String?): JsonObject =
        keyboard(
            plan.models.map { model ->
                val mark = if (model.id == current) "🎯" else "▪️"
                listOf("$mark ${model.label}" to "pm:${model.id}")
            },
        )

    /** /effort's buttons: the shown model's tiers, 🎯 on the one in force. */
    private fun effortKeyboard(levels: List<String>, current: String?): JsonObject =
        keyboard(
            levels.map { level ->
                val mark = if (level == current) "🎯" else "▪️"
                listOf("$mark effort $level" to "pe:$level")
            },
        )

    private fun defaultRow(v: Views): List<List<Pair<String, String>>> =
        if (v.plan == null) emptyList() else listOf(listOf("📌 dejar como default de la app" to "pd:"))

    private fun keyboard(rows: List<List<Pair<String, String>>>): JsonObject = buildJsonObject {
        putJsonArray("inline_keyboard") {
            rows.forEach { row ->
                add(buildJsonArray { row.forEach { (label, data) -> add(Keyboards.button(label, data)) } })
            }
        }
    }
}

/** A JSON string field, or null (shared by the parse only — top level keeps PlanMenu's budget). */
private fun JsonObject.field(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
