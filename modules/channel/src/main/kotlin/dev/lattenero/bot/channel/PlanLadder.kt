package dev.lattenero.bot.channel

/**
 * THE DEFAULT LADDER (the owner's 11/11 order): one priority list that
 * keeps the bot on a plan WITH tokens — «si la 1 no tiene tokens, va a la
 * 2, y si esa tampoco, va a la 3, y si esa tampoco, error - quota limit
 * reached for all… en ese orden, si la 1 no tiene tokens, va a la 2».
 *
 * THE ORDER IS THE OWNER'S, verbatim:
 *   1) Start Plan    — GLM-5.3-Flash    — Max
 *   2) Z.ai          — GLM-5.3-Flash    — Max
 *   3) OpenCode Go   — deepseek-v4.1-flash — Max
 *
 * A rung resolves against the LIVE catalog (never invented): the named
 * model must be exposed by the plan (else that plan's own first model,
 * noted), and the effort must be a tier the model publishes (else the
 * model's highest tier — the fork's own completion rule — noted; a model
 * with no tiers rides WITHOUT effort, the fork selects it by identity).
 * Quota decides: a plan PROVEN tokenless is skipped; a plan whose quota
 * could not be read is the LAST resort (the bot must always work), noted
 * honestly. Pure — the reads ride the caller.
 */
object PlanLadder {

    /** One rung: the plan to prefer, the model that must ride it, its effort tier. */
    data class Rung(val providerId: String, val modelId: String, val effort: String)

    /** The resolved rung: the catalog's own rows (never invented ids). */
    data class Pick(val plan: PlanMenu.Plan, val model: PlanMenu.Model, val effort: String?)

    /** The ladder's answer: the pick (null = EVERY rung exhausted) + the tiny notes. */
    data class Verdict(val pick: Pick?, val notes: List<String>)

    /** The owner's priority, in his exact order. */
    val DEFAULT: List<Rung> = listOf(
        Rung("account:zai-start-plan", "GLM-5.3-Flash", "max"),
        Rung("account:zai-individual-coding-plan", "GLM-5.3-Flash", "max"),
        Rung("opencode-go-chat", "deepseek-v4.1-flash", "max"),
    )

    /**
     * First PROVEN-usable rung wins; a rung whose quota is unknown is the
     * last resort (the bot must always work); none = the exhausted verdict
     * (the caller speaks «quota limit reached for all» with the notes).
     */
    fun pick(catalog: PlanMenu.Views, quota: Map<String, PlanQuota.Mark>): Verdict {
        val steps = DEFAULT.map { evaluate(it, catalog, quota) }
        val notes = steps.mapNotNull { step ->
            when (step) {
                is Step.Exhausted -> step.note
                is Step.Missing -> step.note
                is Step.Usable, is Step.Unknown -> null
            }
        }
        val usable = steps.filterIsInstance<Step.Usable>()
            .map { resolve(it.plan, it.rung) }
            .firstOrNull()
        val unknown = if (usable == null) {
            steps.filterIsInstance<Step.Unknown>()
                .map { resolve(it.plan, it.rung) }
                .firstOrNull()
        } else {
            null
        }
        return when {
            usable != null -> Verdict(usable.first, notes + usable.second)
            unknown != null -> Verdict(
                unknown.first,
                notes + unknown.second + "• quota desconocida — elegido por el orden del dueño",
            )

            else -> Verdict(null, notes)
        }
    }

    /** One rung's fate against the live catalog + its quota mark. */
    private sealed interface Step {
        /** The plan is not in the catalog — the tiny note says so. */
        data class Missing(val note: String) : Step

        /** Every window proven empty — skipped, the note says so. */
        data class Exhausted(val note: String) : Step

        /** Real windows with tokens (or an unknown window inside them) — THE pick. */
        data class Usable(val plan: PlanMenu.Plan, val rung: Rung) : Step

        /** The quota could not be read — the LAST resort, never the first. */
        data class Unknown(val plan: PlanMenu.Plan, val rung: Rung) : Step
    }

    private fun evaluate(rung: Rung, catalog: PlanMenu.Views, quota: Map<String, PlanQuota.Mark>): Step {
        val plan = catalog.plans.firstOrNull { it.providerId == rung.providerId }
            ?: return Step.Missing("• ${rung.providerId} no está en el catálogo de la app")
        val mark = quota[rung.providerId]
        return when {
            PlanQuota.exhausted(mark) -> Step.Exhausted("• ${plan.label}: ⛔ sin tokens — todas las ventanas agotadas")
            mark is PlanQuota.Mark.Seen && PlanQuota.usable(mark) -> Step.Usable(plan, rung)
            else -> Step.Unknown(plan, rung) // the quota could not be read — the LAST resort, never the first
        }
    }

    /**
     * The rung against the catalog's OWN rows: the named model or the
     * plan's first (noted), the named tier or the model's highest (noted)
     * — a plan with no models is unusable (null).
     */
    private fun resolve(plan: PlanMenu.Plan, rung: Rung): Pair<Pick, List<String>>? {
        val model = plan.models.firstOrNull { it.id == rung.modelId }
            ?: plan.models.firstOrNull()
            ?: return null
        val notes = buildList {
            if (model.id != rung.modelId) {
                add("• ${plan.label}: «${rung.modelId}» no está en el plan — usé ${model.label}")
            }
            when {
                rung.effort in model.reasoningLevels -> {}
                model.reasoningLevels.isNotEmpty() ->
                    add("• ${model.label}: sin tier «${rung.effort}» — usé ${model.reasoningLevels.last()}")

                else -> add("• ${model.label}: sin niveles de effort — va sin effort")
            }
        }
        val effort = when {
            rung.effort in model.reasoningLevels -> rung.effort
            model.reasoningLevels.isNotEmpty() -> model.reasoningLevels.last()
            else -> null
        }
        return Pick(plan, model, effort) to notes
    }
}
