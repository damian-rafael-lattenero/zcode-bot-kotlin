package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * The /plan_model_effort command's pure half: parse the channel's
 * modelSelection result (the fork's 3d07cace shapes + the additive
 * reasoningLevels per model), assemble a LOCAL draft one step at a time,
 * and render the menu. Callback vocabulary (≤64B): pl:<providerId> plan,
 * pm:<modelId> model, pe:<effort> reasoning, pac: accept the draft,
 * pcl: discard it, pd: copy it (or the selection) to the app default.
 */
object PlanMenu {

    /** The pre-reasoningLevels trio — DISPLAY fallback only, descending on purpose. */
    private val LEGACY_EFFORTS = listOf("high", "medium", "low")

    /** One selectable model with its effort vocabulary (the fork's optionSpecs levels). */
    data class Model(val id: String, val label: String, val reasoningLevels: List<String> = emptyList())

    /** One selectable plan with its models (the fork's plans[] rows). */
    data class Plan(val providerId: String, val label: String, val models: List<Model>)

    /** A COHERENT (plan, model) pair — the two fields that always travel together. */
    data class Target(val plan: String, val model: String)

    /**
     * THE DRAFT (owner, 3/11): the three choices assembled LOCALLY, one tap
     * at a time, and only ✅ Aceptar writes them. Every step re-resolves the
     * sibling fields from the catalog, so a draft can never hold a cross-plan
     * pair (the bug that broke the old one-field-per-tap flow). `atMs` is the
     * birth stamp (the caller's TTL drops drafts built on a stale catalog).
     */
    data class Draft(val plan: String, val model: String, val effort: String?, val atMs: Long)

    /**
     * The parsed answer — a pure projection (the draft steps are top-level
     * extensions so this class keeps a single responsibility).
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
         * the menu has always rendered).
         */
        fun effortVocabulary(providerId: String?, modelId: String?): List<String> =
            levels(providerId, modelId).takeIf { it.isNotEmpty() } ?: LEGACY_EFFORTS

        /**
         * The completion the fork itself applies — the catalog's HIGHEST
         * tier (its own `at(-1)`). The legacy trio is descending, so its
         * safe default is the first («high»), never the last.
         */
        fun defaultEffort(providerId: String?, modelId: String?): String =
            levels(providerId, modelId).lastOrNull() ?: LEGACY_EFFORTS.first()

        /** The effort rows of the CURRENT model (see [effortVocabulary]). */
        fun effortLevels(): List<String> = effortVocabulary(plan, model)

        /**
         * A plan tap's coherent pair: keep the current model when the target
         * plan exposes it (a plan migration that shares the model must not
         * silently move it), otherwise the plan's FIRST model — the catalog
         * order is the host's own. null = unknown/empty plan.
         */
        @Suppress("ReturnCount") // unknown plan / plan without models / the pair
        fun planTarget(providerId: String): Target? {
            val target = plans.firstOrNull { it.providerId == providerId } ?: return null
            val picked = target.models.firstOrNull { it.id == model } ?: target.models.firstOrNull()
                ?: return null
            return Target(target.providerId, picked.id)
        }

        /**
         * A model tap's coherent pair: the plan that OWNS the model, the
         * current plan first (a model id lives under many plans — the
         * chosen row must never ride the wrong provider). null = a model
         * the catalog no longer exposes (a stale keyboard, said honestly).
         */
        fun modelTarget(modelId: String): Target? {
            val current = plans.firstOrNull { it.providerId == plan }
            val owner = current?.takeIf { p -> p.models.any { it.id == modelId } }
                ?: plans.firstOrNull { p -> p.models.any { it.id == modelId } }
                ?: return null
            return Target(owner.providerId, modelId)
        }
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

    /**
     * The status line: what IS, the app default, and — when a draft is open —
     * EXACTLY what would be applied. Nothing changes until ✅ Aceptar.
     */
    fun statusLine(v: Views, title: String?, draft: Draft? = null): String = buildString {
        append("🎯 <b>«${PyText.esc(title ?: "conversación")}»</b>\n")
        append(currentLine(v))
        defaultLine(v)?.let { append("\n").append(it) }
        if (draft != null) append("\n\n").append(draftLine(v, draft))
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

    private fun draftLine(v: Views, d: Draft): String =
        "🟡 <b>A aplicar:</b> ${PyText.esc(v.planLabel(d.plan) ?: d.plan)}" +
            " · <b>${PyText.esc(v.modelLabel(d.model) ?: d.model)}</b>" +
            (d.effort?.let { " · effort <b>${PyText.esc(it)}</b>" } ?: "") +
            "\nNada cambia hasta tocar ✅ Aceptar."

    /**
     * The tap-to-change rows: plans, the DRAFT plan's models, the draft
     * model's effort vocabulary, then ✅ Aceptar / ✖️ Descartar (only while a
     * draft exists) and the default copy. 🟡 = the draft, 🎯 = in force.
     */
    fun keyboard(v: Views, draft: Draft? = null): JsonObject {
        val rows = planRows(v, draft) + modelRows(v, draft) + effortRows(v, draft) +
            verbRows(draft) + defaultRow(v)
        return buildJsonObject {
            putJsonArray("inline_keyboard") {
                rows.forEach { row ->
                    add(buildJsonArray { row.forEach { (label, data) -> add(Keyboards.button(label, data)) } })
                }
            }
        }
    }

    private fun planRows(v: Views, draft: Draft?): List<List<Pair<String, String>>> =
        v.plans.map { plan ->
            val mark = when {
                draft != null && plan.providerId == draft.plan -> "🟡"
                plan.providerId == v.plan -> "🎯"
                else -> "🟢"
            }
            listOf("$mark ${plan.label}" to "pl:${plan.providerId}")
        }

    private fun modelRows(v: Views, draft: Draft?): List<List<Pair<String, String>>> {
        val shownPlan = draft?.plan ?: v.plan
        val plan = v.plans.firstOrNull { it.providerId == shownPlan } ?: return emptyList()
        return plan.models.map { model ->
            val mark = when {
                draft != null && model.id == draft.model -> "🟡"
                model.id == v.model && shownPlan == v.plan -> "🎯"
                else -> "▪️"
            }
            listOf("$mark ${model.label}" to "pm:${model.id}")
        }
    }

    private fun effortRows(v: Views, draft: Draft?): List<List<Pair<String, String>>> {
        val shownPlan = draft?.plan ?: v.plan
        val shownModel = draft?.model ?: v.model
        return v.effortVocabulary(shownPlan, shownModel).map { level ->
            val mark = when {
                draft?.effort == level -> "🟡"
                level == v.effort && shownPlan == v.plan && shownModel == v.model -> "🎯"
                else -> "▪️"
            }
            listOf("$mark effort $level" to "pe:$level")
        }
    }

    private fun verbRows(draft: Draft?): List<List<Pair<String, String>>> = if (draft == null) {
        emptyList()
    } else {
        listOf(listOf("✅ Aceptar" to "pac:"), listOf("✖️ Descartar" to "pcl:"))
    }

    private fun defaultRow(v: Views): List<List<Pair<String, String>>> =
        if (v.plan == null) emptyList() else listOf(listOf("📌 dejar como default de la app" to "pd:"))
}

/** A JSON string field, or null (shared by the parse only — top level keeps PlanMenu's budget). */
private fun JsonObject.field(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * The draft's steps — top-level extensions so [PlanMenu.Views] stays a
 * projection. Every step re-resolves its siblings from the SAME catalog the
 * host validates against, so a draft is a valid combination at all times.
 */

/** Step 0 — the selection in force (or the first plan), with its own effort. */
@Suppress("ReturnCount") // no plans / plan without models / the draft
fun PlanMenu.Views.startDraft(atMs: Long): PlanMenu.Draft? {
    val startPlan = plan?.let { p -> plans.firstOrNull { it.providerId == p } } ?: plans.firstOrNull()
        ?: return null
    val startModel = startPlan.models.firstOrNull { it.id == model } ?: startPlan.models.firstOrNull()
        ?: return null
    val kept = effort?.takeIf { it in effortVocabulary(startPlan.providerId, startModel.id) }
    return PlanMenu.Draft(
        plan = startPlan.providerId,
        model = startModel.id,
        effort = kept ?: defaultEffort(startPlan.providerId, startModel.id),
        atMs = atMs,
    )
}

/** Step 1 — the plan tap: land IN the target plan (its own model when the draft's is gone). */
@Suppress("ReturnCount") // unknown plan / plan without models / the draft
fun PlanMenu.Views.draftWithPlan(draft: PlanMenu.Draft, providerId: String): PlanMenu.Draft? {
    val target = plans.firstOrNull { it.providerId == providerId } ?: return null
    val picked = target.models.firstOrNull { it.id == draft.model } ?: target.models.firstOrNull()
        ?: return null
    val kept = draft.effort?.takeIf { it in effortVocabulary(target.providerId, picked.id) }
    return PlanMenu.Draft(
        target.providerId,
        picked.id,
        kept ?: defaultEffort(target.providerId, picked.id),
        draft.atMs,
    )
}

/** Step 2 — the model tap: the plan that OWNS it, effort re-completed. */
@Suppress("ReturnCount") // a model no plan owns / its own plan's draft
fun PlanMenu.Views.draftWithModel(draft: PlanMenu.Draft, modelId: String): PlanMenu.Draft? {
    val target = modelTarget(modelId) ?: return null
    val picked = model(target.plan, target.model) ?: return null
    val kept = draft.effort?.takeIf { it in effortVocabulary(target.plan, picked.id) }
    return PlanMenu.Draft(target.plan, picked.id, kept ?: defaultEffort(target.plan, picked.id), draft.atMs)
}

/** Step 3 — the effort tap: only a level the shown model publishes. */
fun PlanMenu.Views.draftWithEffort(draft: PlanMenu.Draft, level: String): PlanMenu.Draft? =
    if (level in effortVocabulary(draft.plan, draft.model)) draft.copy(effort = level) else null

/** Applicable = every field resolves in the catalog AND the effort is the model's own. */
fun PlanMenu.Views.draftValid(draft: PlanMenu.Draft): Boolean {
    model(draft.plan, draft.model) ?: return false
    return draft.effort == null || draft.effort in effortVocabulary(draft.plan, draft.model)
}
