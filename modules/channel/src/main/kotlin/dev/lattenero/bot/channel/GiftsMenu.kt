package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The coding plan's GIFTS, on the phone (A5's bot half; the fork cap
 * `queries:codingPlanGifts`): the granted quota resets (the app's 🎁 pill)
 * and the claimable offers (the banner the owner saw: «ZCODE GLOBAL BUILD ·
 * 300,000,000 tokens · Claim»). The read is pure projection; the two
 * mutations are their OWN taps behind a confirmation row (a claim changes
 * the account's plan; a reset is spent forever).
 *
 * Honesty rules inherited from A5 (docs/a5-gifts-promos.md):
 *  • no data → no line (never «0 gifts»: absence of evidence is not one);
 *  • a countdown only from a REAL `expireAt` — the unclaimed offer has NO
 *    deadline field, so the claim line never invents one;
 *  • the server's `name`/`showName` are escaped; the marketing `label` is
 *    the button's own text, never re-written;
 *  • `grantUnits` is a RAW token count through the owner's ladder
 *    ([BotCommands.tokenRaw]: 300,000,000 → 300M).
 */
@Suppress("TooManyFunctions") // the pure core: parse + render + the two confirmations + the verdict
object GiftsMenu {

    /** One granted reset: the window it refills and when it dies. */
    data class ResetGift(val type: String, val expireAt: Long)

    /**
     * One claimable plan: the billing preview's row, or a marketing banner
     * action when the preview did not list it (then [name] is null and
     * only the planId is real).
     */
    data class Offer(
        val planId: String,
        val name: String?,
        val tokens: Double?,
        val entitlement: String?,
        val period: String?,
    ) {
        val displayName: String get() = name ?: planId
    }

    /** The whole read: both planes, already filtered to what is still alive. */
    data class Gifts(val resets: List<ResetGift>, val offers: List<Offer>) {
        val isEmpty: Boolean get() = resets.isEmpty() && offers.isEmpty()
    }

    val EMPTY = Gifts(emptyList(), emptyList())

    /**
     * The fork's answer → typed gifts. A missing/broken plane is EMPTY (the
     * ladder's silence), never an exception. Resets filter `expireAt > now`
     * and sort ascending (the app's own law — the earliest one is the one
     * at risk); offers dedupe by planId, the preview's row winning over the
     * marketing action.
     */
    fun parse(json: String?, nowMs: Long): Gifts {
        val root = json?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
            ?: return EMPTY
        return Gifts(resets(root, nowMs), offers(root))
    }

    private fun resets(root: JsonObject, nowMs: Long): List<ResetGift> {
        val reset = root.obj("reset") ?: return emptyList()
        fun window(key: String, type: String): List<ResetGift> =
            ((reset[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty())
                .mapNotNull { (it.num("expireAt"))?.let { ms -> ResetGift(type, ms.toLong()) } }
                .filter { it.expireAt > nowMs }
                .sortedBy { it.expireAt }
        return window("fiveHour", FIVE_HOUR) + window("week", WEEK)
    }

    private fun offers(root: JsonObject): List<Offer> {
        val fromPreview = ((root.obj("claimable")?.get("plans") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }.orEmpty())
            .mapNotNull { plan ->
                val planId = plan.str("planId")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val entitlement = (plan["entitlements"] as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.maxByOrNull { it.num("grantUnits") ?: 0.0 }
                Offer(
                    planId = planId,
                    name = plan.str("name")?.takeIf { it.isNotBlank() },
                    tokens = entitlement?.num("grantUnits")?.takeIf { it > 0 },
                    entitlement = entitlement?.str("showName")?.takeIf { it.isNotBlank() },
                    period = entitlement?.str("period")?.let(::periodWord),
                )
            }
        val known = fromPreview.map { it.planId }.toSet()
        val fromMarketing = ((root["offers"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }.orEmpty())
            .mapNotNull { offer ->
                val planId = offer.str("planId")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                if (planId in known) null else Offer(planId, null, null, null, null)
            }
        return fromPreview + fromMarketing
    }

    // ── rendering ──

    /** The lines appended to a USABLE /quota card (never to a degraded one). */
    fun render(gifts: Gifts, nowMs: Long): List<String> {
        val lines = mutableListOf<String>()
        listOf(FIVE_HOUR to "5h", WEEK to "semanal").forEach { (type, word) ->
            val alive = gifts.resets.filter { it.type == type }
            if (alive.isEmpty()) return@forEach
            val count = alive.size
            val noun = if (count == 1) "1 reset" else "$count resets"
            lines.add("🎁 <b>$noun</b> ($word) · ${expiringWord(alive.first().expireAt, nowMs)}")
        }
        gifts.offers.forEach { offer ->
            lines.add(offerLine(offer))
        }
        return lines
    }

    private fun offerLine(offer: Offer): String {
        // the billing preview supplies the name; a marketing-only offer has
        // NONE (the banner's «ZCODE GLOBAL BUILD» lives in its image, not in
        // the API answer) — say «Oferta reclamable» + the real plan id
        // instead of pretending the id is a title
        val title = offer.name?.let { "Reclamable: ${PyText.esc(it)}" }
            ?: "Oferta reclamable (${PyText.esc(offer.planId)})"
        val body = buildString {
            append("🔴 <b>$title</b>")
            offer.tokens?.let { append(" — ${BotCommands.tokenRaw(it)} tokens") }
            offer.entitlement?.let { append(" · ${PyText.esc(it)}") }
            offer.period?.let { append(" ($it)") }
        }
        return body
    }

    /**
     * THE WORKING CARD's compact prize line (the owner, 3/11: «una pequeña
     * seccion que diga premios… mientras se recarga y te muestras los
     * segundos y los cots»): ONE line riding the always-last process card —
     * the detail (and the taps) stay in /quota. Silence when there is
     * nothing: absence is not a prize. Resets carry their REAL earliest
     * expiry; a plan offer has NO expiry field, so the line never invents
     * one.
     */
    fun workingLine(gifts: Gifts, nowMs: Long): String? {
        if (gifts.isEmpty) return null
        val parts = mutableListOf<String>()
        val five = gifts.resets.count { it.type == FIVE_HOUR }
        if (five > 0) parts += "🎁 ${if (five == 1) "1 reset 5h" else "$five resets 5h"}"
        val week = gifts.resets.count { it.type == WEEK }
        if (week > 0) parts += "🎁 ${if (week == 1) "1 reset semanal" else "$week resets semanales"}"
        gifts.offers.firstOrNull()?.let { offer ->
            val name = offer.name?.let { PyText.esc(it) } ?: "oferta"
            val tokens = offer.tokens?.let { " · ${BotCommands.tokenRaw(it)} tokens" }.orEmpty()
            parts += "🔴 $name$tokens reclamable"
        }
        val earliest = gifts.resets.minOfOrNull { it.expireAt }
        val expiry = earliest?.let { " · ${expiringWord(it, nowMs)}" }.orEmpty()
        return "🎁 <b>Premios</b> · ${parts.joinToString(" · ")}$expiry — /quota"
    }

    /**
     * The taps of a usable card: one 🎁 Reclamar per offer (the planId is
     * short by contract; a long one degrades to no button rather than a
     * broken callback) and one 🎁 Usar reset per window with gifts.
     */
    fun keyboard(gifts: Gifts): JsonObject? {
        val rows = mutableListOf<Pair<String, String>>()
        gifts.offers.forEach { offer ->
            val data = "cl:${offer.planId}"
            if (Keyboards.callbackDataValid(data)) rows.add("🎁 Reclamar" to data)
        }
        val five = gifts.resets.count { it.type == FIVE_HOUR }
        if (five > 0) rows.add("🎁 Usar reset 5h" to "gf:$FIVE_HOUR")
        val week = gifts.resets.count { it.type == WEEK }
        if (week > 0) rows.add("🎁 Usar reset semanal" to "gf:$WEEK")
        if (rows.isEmpty()) return null
        return buildJsonObject {
            putJsonArray("inline_keyboard") {
                rows.forEach { (text, data) -> add(buildJsonArray { add(Keyboards.button(text, data)) }) }
            }
        }
    }

    /** The claim's confirmation — the plan change is the warning, not a footnote. */
    fun claimConfirmation(offer: Offer): String = buildString {
        append("🔴 <b>¿Reclamar «${PyText.esc(offer.displayName)}»?</b>\n")
        offer.tokens?.let { append("${BotCommands.tokenRaw(it)} tokens") }
        offer.entitlement?.let { append(" · ${PyText.esc(it)}") }
        offer.period?.let { append(" ($it)") }
        if (offer.tokens != null || offer.entitlement != null) append("\n")
        append("Es un cambio de plan en tu cuenta — no se puede deshacer.")
    }

    /** The reset's confirmation — how many, and how soon the earliest dies. */
    fun resetConfirmation(type: String, count: Int, earliestMs: Long, nowMs: Long): String {
        val word = if (type == WEEK) "semanal" else "5 horas"
        return "🎁 <b>¿Usar un reset de $word?</b>\n" +
            "Te quedan <b>$count</b> · el más temprano ${expiringWord(earliestMs, nowMs)}\n" +
            "El reset se consume y no se puede recuperar."
    }

    /** The ✅/❌ row every confirmation carries (destructive LAST, per the guide). */
    fun confirmKeyboard(goData: String): JsonObject = buildJsonObject {
        putJsonArray("inline_keyboard") {
            add(buildJsonArray {
                add(Keyboards.button("✅ Confirmar", goData))
                add(Keyboards.button("❌ Cancelar", "gxn:"))
            })
        }
    }

    /**
     * The browser DOOR (the claim's captcha gate, 3/11): ONE url button —
     * a tap opens the owner's browser at the rewards surface. Telegram
     * cannot make a callback open a URL by itself, so this row is the
     * closest a bot can get to «confirm → abrí el link».
     */
    fun webDoorKeyboard(label: String, url: String): JsonObject = buildJsonObject {
        putJsonArray("inline_keyboard") {
            add(buildJsonArray { add(Keyboards.urlButton(label, url)) })
        }
    }

    /** The host's claim verdict — `success` is the server's, never re-derived. */
    @Suppress("ReturnCount") // one guard per verdict tier (unreadable, refused, applied)
    fun claimResult(json: String, name: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: return "⚠️ No pude leer la respuesta del reclamo"
        if (root.bool("success") != true) {
            val code = root.int("code") ?: -1
            val message = root.str("message").orEmpty().takeIf { it.isNotBlank() }
            return when {
                code == HTTP_UNAUTHORIZED -> "⚠️ La app no tiene sesión de ZCode iniciada — reclamá desde la app."
                code in TERMINAL_CLAIM_CODES ->
                    "⚠️ El server rechazó el reclamo ($code): ${PyText.esc(message ?: "la oferta ya no está disponible")}"
                else -> "⚠️ No pude reclamar ($code): ${PyText.esc(message ?: "?")}"
            }
        }
        val plan = root.obj("plan")
        val status = plan?.str("status").orEmpty().takeIf { it.isNotBlank() }
        val endsAt = plan?.long("endsAt")?.takeIf { it > 0 }
            ?.let { Instant.ofEpochMilli(it * MS_PER_SECOND).atZone(zone).format(DATE_FMT) }
        return buildString {
            append("✅ <b>Reclamado: ${PyText.esc(name)}</b>")
            status?.let { append(" · estado ${PyText.esc(it)}") }
            endsAt?.let { append(" · hasta $it") }
            append("\nCorré /quota para ver los tokens — y /plan para usar el plan nuevo.")
        }
    }

    // ── words ──

    /** The provider's period vocabulary, only where a word is unambiguous. */
    private fun periodWord(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        "one_time", "onetime" -> "único"
        "daily" -> "diario"
        "weekly" -> "semanal"
        "monthly" -> "mensual"
        else -> null
    }

    /** «expira en 3h 20m» / «expira en 2d 4h» — relative to a REAL expireAt. */
    fun expiringWord(expireAtMs: Long, nowMs: Long): String {
        val secs = (expireAtMs - nowMs) / MS_PER_SECOND
        return when {
            secs <= 0 -> "expira ya"
            secs < SECS_MIN -> "expira en ${secs}s"
            secs < SECS_HOUR -> "expira en ${secs / SECS_MIN}m"
            secs < SECS_DAY -> "expira en ${secs / SECS_HOUR}h ${(secs % SECS_HOUR) / SECS_MIN}m"
            else -> "expira en ${secs / SECS_DAY}d ${(secs % SECS_DAY) / SECS_HOUR}h"
        }
    }

    const val FIVE_HOUR = "FIVE_HOUR"
    const val WEEK = "WEEK"
    private const val MS_PER_SECOND = 1000L
    private const val SECS_MIN = 60L
    private const val SECS_HOUR = 3600L
    private const val SECS_DAY = 86_400L

    /** The claim's own verdict codes (the service's 401 = no ZCode JWT; 1001-1005 = terminal refusals). */
    private const val HTTP_UNAUTHORIZED = 401
    private val TERMINAL_CLAIM_CODES = setOf(1001, 1002, 1003, 1004, 1005)
    private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    private fun JsonObject.num(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
}
