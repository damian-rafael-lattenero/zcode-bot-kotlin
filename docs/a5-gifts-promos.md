# A5 — Gifts & promos: where they live, and what /quota can honestly say

Investigation unit A5 of `command-parity-plan.md` (`:103-108`). Read-only on
the fork; the deliverable is this verdict + the design of the surface.
Written 2026-11-02.

The plan asked for two "gift boxes" the app shows: the token pill
(«reset 5 hour quota now, expires in X» / weekly) and the config sidebar
(«free 500.000K token expires in 2 days, Select!»). Both exist. Neither is
reachable from the bot's channel today, and neither can be read by the bot
over plain HTTP with the credential it already has. The gap is one additive
channel query — designed below.

## 1. Verdict

| Surface | Exists in the fork? | Data source | Reachable by the bot today? |
| --- | --- | --- | --- |
| **Reset-quota gift** (the Gift pill: «N reset available · expires in X», 5-hour and weekly) | **YES** | `zcode.z.ai` `GET /api/v1/coding-plan/reset/status` (+ `/use`, `/opportunity`, `/history/read`) | **NO** — needs ZCode account JWT **and** provider OAuth token, both in the app's encrypted credential store |
| **Claimable trial plan / promo** (the «Limited-time offer · Claim» banner; the offer the plan calls "free 500.000K token, Select!") | **YES** | `zcode.z.ai` `GET /api/v1/zcode-plan/billing/preview` (+ `POST …/claim`) for the structured offer; `GET /api/v1/marketing/touch` for the campaign copy | **NO** — needs the ZCode account JWT; the copy additionally needs a device id |
| **Static Start Plan preview card** ("Trial plan · 5 calendar days · 3M tokens daily", settings sidebar) | **YES** | `GET /api/v1/client/configs?app_version=…&platform=…` → `configs.startPlanPreview` (public, no auth) | **YES in principle** (no secret) — but it is static marketing config, not the owner's account |
| **Start Plan balances after a claim** ("free X tokens left, resets at Y") | **YES** | `GET /api/v1/zcode-plan/billing/balance?app_version=…` → `plans` + `balances` | **YES** — already inside the channel `quota` reply |

Verdict: **PARCIAL** (partial). The gift data planes exist and are fully specified in
the fork; the *channel* exposes none of them; the bot cannot substitute with
its own HTTP call because the credentials are not the ones it has. What is
reachable today is the *already-claimed* Start-Plan bucket view (`quota`
query), which is the "free tokens left" half — not the "claimable gift" half.

The short version of why direct HTTP fails:

- The bot's `/quota` direct rung authenticates with the **Coding Plan API key**
  from `bot-provider.json` (`QuotaDirect.kt:81-101`) against
  **`api.z.ai`** (`QuotaDirect.kt:24`).
- Both gift planes live on **`zcode.z.ai`** (`packages/shared/src/zcodeEndpoint.ts:3`)
  and authenticate with the **ZCode account JWT** (`Authorization: Bearer`,
  credential key `zcodejwttoken`) plus, for the reset plane, the **provider
  OAuth access token** (`X-Bigmodel-Authorization`, credential keys
  `oauth:zai:access_token` / `oauth:bigmodel:access_token`,
  `bigmodelUsageQuotaProvider.ts:75-77`, `:649-677`).

## 2. The reset-quota gift (gift A)

### 2.1 Surface

The pill itself is a gift icon + a count + a live countdown:

- `packages/ui/src/components/coding-plan-quota-reset/CodingPlanQuotaResetOpportunity.tsx:1`
  (`GiftIcon`), `:58` (`codingPlan.quotaReset.opportunity` = "{count} reset
  available"), `:62` (`codingPlan.quotaReset.expiresIn` = "Expires in {time}"),
  `:80` (the icon in the badge).
- Rendered from the composer's coding-plan meter
  (`packages/ui/src/chat-input-toolbar/contextUsage.tsx:400` hook, `:407`
  badge) and from the settings usage panel
  (`packages/ui/src/settings/usage-stats/CodingPlanUsagePanel.tsx:343`).
- Both 5-hour and weekly gifts merge into one badge: total count, **earliest**
  expiry (`packages/ui/src/lib/codingPlanQuotaResetUi.ts:335-352`); the pill is
  visible whenever count > 0, even at 100% remaining (`:342`, and the bugfix
  comment at `codingPlanResetOpportunityBadge.ts:24-27`: a used reset rewrites
  the pool to 100% and the badge must survive it).
- Only gifts with `expireAt > now` count, sorted ascending
  (`codingPlanQuotaResetUi.ts:187-189`).

### 2.2 Service and endpoints

`IUsageStatsService` (`packages/services/src/usage-stats/usageStats.ts:22-30`):

```ts
getCodingPlanResetStatus(request: CodingPlanResetScopeRequest): Promise<CodingPlanResetStatusSnapshot>
requestCodingPlanResetOpportunity(request): Promise<CodingPlanResetOpportunityResult>
useCodingPlanReset(request): Promise<CodingPlanResetUseResult>
markCodingPlanResetHistoryRead(request): Promise<void>
```

Implementation: `packages/services/src/usage-stats/providers/bigmodelUsageQuotaProvider.ts`
(base path `:74` = `/api/v1/coding-plan/reset`, host `zcode.z.ai`).

| Op | Request | Body / response (`code: 0` envelope) |
| --- | --- | --- |
| `status` (`:523-552`) | `GET {zcode}/api/v1/coding-plan/reset/status` | `data:{available_five_hour_resets:[{expire_at}], available_week_resets:[{expire_at}], latest_five_hour_reset_history:{used_at}\|null, latest_week_reset_history:{used_at}\|null, has_unread_history:boolean}` |
| `use` (`:554-578`) | `POST …/reset/use` | body `{idempotency_key, reset_type:"FIVE_HOUR"\|"WEEK"}` → `data:{used:true}` |
| `opportunity` (`:580-632`) | `POST …/reset/opportunity` | body `{idempotency_key}` → `data:{granted:true}` · denied: `code:3301, data:{granted:false, next_try_at}` · transport: HTTP 429 |
| `history/read` (`:634-647`) | `POST …/reset/history/read` | no body, no target scope → `code:0` |

Headers (`createCodingPlanResetHeaders`, `:982-999`):

```
Authorization: Bearer <zcodejwttoken>
X-Bigmodel-Authorization: <provider OAuth access_token>   (raw, NOT Bearer)
Bigmodel-Target-Type: PERSONAL | TEAM
Bigmodel-Organization / Bigmodel-Project                  (TEAM only)
```

Zod contracts: `:86-113`. The shared snapshot type:
`packages/shared/src/coding-plan-reset.ts:17-24`.

Units: `expire_at` / `used_at` are **epoch milliseconds**. The provider passes
them through without conversion (`:538-549`) and the UI feeds them straight to
`Date.now()` arithmetic (`CodingPlanQuotaResetOpportunity.tsx:13-19`); the E2E
fixtures build them as `Date.now() + N` (`packages/desktop/wdio.conf.ts:1657-1694`).

### 2.3 The non-obvious part: `/status` shows only already-granted gifts

`/status` is a pure read of what the server already issued. Eligibility is
evaluated **only** by `/opportunity`, which is a scope-level, rate-limited,
idempotency-keyed mutating call. The app calls it after every status read
unless a use is in flight or just completed
(`packages/ui/src/hooks/useCodingPlanQuotaResetUi.ts:610-670`, comment at
`:650-652`: «/status 只查询已发放机会；必须再调 /opportunity，否则后端不会执行
资格判断»). Cadence: status every **5 min**
(`packages/ui/src/lib/codingPlanQuotaResetCoordinator.ts:5`), with 5/10-min
cooldowns for opportunity retries (`:6-9`).

Consequence for the bot: a read-only `/quota` can only ever show gifts that
were already granted — normally because the desktop app was open. It must
**not** call `/opportunity` on its own: it is a mutation with server-side
quotas, a 5-min server-side retry boundary (`next_try_at`, code 3301) and
HTTP 429 backpressure (`bigmodelUsageQuotaProvider.ts:604-612`); a second
client on its own cadence would fight the app's poller.

## 3. The claimable plan (gift B)

### 3.1 Surface

Two layers exist today:

1. **Structured offer** — `IUsageStatsService`'s sibling
   `ICodingPlanSubscriptionService`
   (`packages/services/src/coding-plan-subscription/codingPlanSubscription.ts:68-69`):

   ```ts
   getManualClaimPlanPreviews(): Promise<ManualClaimPlanPreviewSnapshot>
   claimManualPlan(request: ManualClaimPlanClaimRequest): Promise<ManualClaimPlanClaimResult>
   ```

   Types: `packages/shared/src/coding-plan-subscription.ts:132` (`…Entitlement`),
   `:144` (`…Preview`), `:152` (`…PreviewSnapshot`), `:158` (request),
   `:169` (result).

2. **Campaign copy** — the banner/popup that actually says «Limited-time offer
   … Claim» is server-delivered marketing content whose button carries the
   action `{type:"claim_zcode_plan", args:{plan_id}}`
   (`packages/shared/src/marketingTouch.ts:52`), queried from
   `GET /api/v1/marketing/touch` (`packages/services/src/marketing-touch/marketingTouchService.ts:47`)
   and actioned through `POST /api/v1/marketing/touch/action` (`:68`). The
   claim itself is fired from
   `packages/ui/src/components/marketing-touch/MarketingTouchProvider.tsx:262`.

Note what this means for the quoted strings: **«free 500.000K token / expires
in 2 days / Select!» is campaign copy**, not fork copy. The fork-localized
strings for the ticket UI (`manualClaimPlan.banner.*` in
`packages/ui/src/i18n/locales/en-US.ts:693-733`) are **unused by any component**
in this tree (grep over `packages/ui/src`, `packages/services/src`,
`packages/shared/src`), and `getManualClaimPlanPreviews()` has **no caller in
the UI** — the claim itself is driven by the marketing banner's
`claim_zcode_plan` action. The service method and the claim endpoint are live;
the standalone ticket surface is not. The bot must not try to reproduce
campaign copy.

### 3.2 Endpoints

Implementation: `packages/services/src/coding-plan-subscription/bigmodelCodingPlanSubscriptionProvider.ts`.

| Op | Request | Response |
| --- | --- | --- |
| `preview` (`:278-336`) | `GET {zcode}/api/v1/zcode-plan/billing/preview?app_version=<ZCODE_VERSION>&platform=<platform>-<arch>` , `Authorization: Bearer <zcodejwttoken>` (sent only if present) | `{code:0, data:{server_time:<unix seconds>, plans:[{plan_id, name, description, priority, entitlements:[{entitlement_id, show_name, meter, unit_type, capabilities[], grant_units, period, priority, effective_at?}]}]}}` |
| `claim` (`:338-409`) | `POST {zcode}/api/v1/zcode-plan/billing/claim`, headers `Authorization: Bearer <jwt>`, `Content-Type: application/json`, `X-ZCode-App-Version`, `X-Platform`, `+ requestVerificationClaimHeaders(proof)`; body `{plan_id}` | success `{code:0, data:{server_time, plan:{user_plan_id, plan_id, status, starts_at, ends_at, entitlements:[{entitlement_id, show_name, effective_at}]}}}` · failure `{code:1001…1005, data:{message, plan?:{ends_at}}}` |

Fixture-grade examples (the E2E contract):
`packages/desktop/test/e2e/helpers/coding-plan-upgrade-mock-server.ts:276-345`.

Facts that matter for a renderer:

- `grant_units` is a **raw token count** (fixture: `10_000_000_000`); `unit_type`
  is `"token"`; `period` is e.g. `"one_time"` or `"daily"`.
- `capabilities` lists model ids as `"model:<id>"`; `show_name` is the display
  label (e.g. `GLM-5.3-Flash`).
- `effective_at` / `server_time` are **unix seconds** (the service converts
  `server_time` to ms, `:104-109`; the shared type documents ms).
- **There is no expiry field for an unclaimed offer.** The preview carries
  `plan_id`, `name`, `description`, `priority` and the entitlements — nothing
  comparable to `expires_at`/`ends_at`. Only the *claim result* has
  `starts_at`/`ends_at`. «expires in 2 days» for an unclaimed gift is campaign
  copy or the static trial length; it cannot be derived from the API.
- `requestVerificationClaimHeaders` is a no-op returning `{}` in this build
  (`packages/shared/src/request-security-edition/verification-policy.ts:19-23`;
  the UI overlay `packages/ui/src/request-security-edition/claim.ts:4-11`
  returns `{}`). A security-edition build would enforce a proof — another
  reason for the bot not to own the claim action.

### 3.3 The static Start Plan card (third plane, public)

`GET /api/v1/client/configs?app_version=&platform=`
(`bigmodelCodingPlanSubscriptionProvider.ts:97`, `:807-838`, cached 1 h,
**no auth headers**) returns `configs.startPlanPreview`
(`{planId, name, entitlements:[{grantUnits, meter, period, showName, unitType}]}`,
`packages/shared/src/coding-plan-subscription.ts:118-130`), which feeds the
settings card ("Trial plan · 5 calendar days · 3M tokens daily",
`packages/ui/src/settings/model-provider-section/StartPlanCard.tsx`).

This is the only gift-adjacent plane the bot **could** fetch over HTTP today
with no secret. It is not account data: no eligibility, no expiry, no claim
state. Worth at most a static "the provider offers a trial plan" line — and
that line is not in scope for /quota (it would be the same for every owner).

### 3.4 The already-claimed view (reachable today)

Once claimed, the trial becomes a Start Plan and lands in the entitlement
snapshot the channel `quota` query already returns:

- `getEntitlementSnapshot` → `start-plan` path → `GET /api/v1/zcode-plan/billing/balance?app_version=…`
  (`packages/services/src/model-provider/zaiStartPlanBilling.ts:63-69`).
- Per-bucket expiry `balance.expires_at` → `quota.limits[].nextResetTime`
  (`bigmodelUsageQuotaProvider.ts:1395-1418`).
- Plan end `plan.ends_at` → `subscription.details[].expireTime`
  (`:1276-1307`).

The bot already renders those numbers as `· resets <date>`
(`modules/channel/src/main/kotlin/dev/lattenero/bot/channel/BotCommands.kt:2670-2693`).
That is the honest "free tokens left, expires" line — for a plan that is
already the owner's.

## 4. What the channel gives the bot today

- Query kinds known to the daemon channel:
  `packages/services/src/bots/daemonChannel.ts:151-166`; the union:
  `packages/shared/src/daemon-channel.ts:117-170`.
- `quota` → `getEntitlementSnapshot` only (`daemonChannel.ts:437-456`), with
  the account hint derived from the bound task's model selection
  (`accountAccessHintFor`, `:116-132`).
- Advertised capabilities (`:240-260`) list `queries:quota` — nothing about
  resets or claims.
- Bot side: `ChannelQuery` sealed type
  (`modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelFrames.kt:43-103`),
  `quota` encode at `:316`, /quota ladder at `BotCommands.kt:1720-1747`,
  renderers at `:2631-2719`.

So the gap is exactly one missing query kind. Nothing in the fork's services
has to be built: both methods already exist and are already registered as
first-class services (`packages/services/src/accessor.ts:102`,
`packages/services/src/node.ts:2774` for the subscription service,
`services.getOptional(IUsageStatsService)` for usage stats).

## 5. Proposed fork capability: `codingPlanGifts`

Additive, read-only, no new credentials, no new endpoints — it only plumbs
two existing service calls through the socket the bot already speaks.

### 5.1 Wire

```json
→ {"type":"query","id":"q7","query":{"kind":"codingPlanGifts","taskId":"<bound task, optional>"}}
← {"type":"query_result","id":"q7","result":{ ...DaemonChannelCodingPlanGiftsResult... }}
```

`taskId` is optional but recommended, exactly like `quota`: the channel
derives the same `accountAccess` hint from the task's model selection, which
is what makes the reset call resolve the real provider instead of throwing
`coding_plan_reset_account_access_required`.

### 5.2 Result type (new, in `packages/shared/src/daemon-channel.ts`)

```ts
export interface DaemonChannelCodingPlanGiftsResult {
  /** What the app's Gift pill shows. Null when the plane could not be read. */
  reset: {
    fiveHour: Array<{ expireAt: number }>; // epoch ms, raw from the server
    week: Array<{ expireAt: number }>;
    latestFiveHourUsedAt: number | null;
    latestWeekUsedAt: number | null;
    hasUnreadHistory: boolean;
  } | null;
  /** What the app's "Limited-time offer" banner offers. Null when unreadable. */
  claimable: {
    serverTime: number | null; // epoch ms (service already converts)
    plans: Array<{
      planId: string;
      name: string;
      description: string;
      priority: number;
      entitlements: Array<{
        entitlementId: string;
        showName: string;
        meter: string;
        unitType: string;
        capabilities: string[];
        grantUnits: number;
        period: string;
        effectiveAt?: number; // unix seconds, as the server sends it
      }>;
    }>;
  } | null;
  /** Per-plane degraded reason; one failed plane must not kill the other. */
  unavailable?: {
    reset?: "not_authenticated" | "not_configured" | "no_plan" | "request_failed";
    claimable?: "not_authenticated" | "request_failed";
  };
}
```

The shape is a straight projection of `CodingPlanResetStatusSnapshot`
(`coding-plan-reset.ts:17`) and `ManualClaimPlanPreviewSnapshot`
(`coding-plan-subscription.ts:152`). No secrets, no tokens, no ids beyond
what the app itself displays.

### 5.3 Implementation sketch (fork, one batch)

| File | Change |
| --- | --- |
| `packages/shared/src/daemon-channel.ts` | union member `{kind:"codingPlanGifts"; taskId?: string}` + the result interface |
| `packages/services/src/bots/daemonChannel.ts` | `"codingPlanGifts"` in `KNOWN_QUERY_KINDS` (`:151`); `"queries:codingPlanGifts"` in `capabilities` (`:240`); widen `DaemonLocalChannelDeps.usageStatsService` from `Pick<…,"getEntitlementSnapshot">` (`:67`) to also include `getCodingPlanResetStatus`; add optional `codingPlanSubscriptionService?: Pick<ICodingPlanSubscriptionService,"getManualClaimPlanPreviews">`; one `case` that `Promise.allSettled`s both calls and maps failures to `unavailable.*` |
| `packages/services/src/node.ts` | pass `services.getOptional(ICodingPlanSubscriptionService)` (already imported at `:406`, registered at `:2774`) |
| `docs/fork/DAEMON-CHANNEL.md` | a row in the §3.4 table (`:198-215`) |
| tests | one unit test with fakes: both planes ok; reset fails / claim ok; both fail → `{}`; old-server `unknown_query` covered by the shared contract test |

Error mapping (stable strings, no server `msg` passthrough):

- `coding_plan_reset_account_access_required` → `reset:"not_configured"`
- `coding_plan_reset_zcode_jwt_required` / `…_maas_jwt_required` → `reset:"not_authenticated"`
- `coding_plan_reset_api_error:*`, `ApiError` 4xx/5xx → `reset:"request_failed"`
- preview without JWT / 401/403 → `claimable:"not_authenticated"`
- anything else → `claimable:"request_failed"`

### 5.4 Explicitly out of scope: the mutating half

`useCodingPlanReset` (spend a granted reset) and `claimManualPlan` (claim the
offer) are **not** part of this capability. Reasons, in order of weight:

1. A claim changes the owner's active plan and the app then rewrites the
   model selection (`manualClaimPlanStartUsing.ts:17-20` gate) — a bot tap
   must not silently re-point the owner's model.
2. The claim may require a request-verification proof in the security-edition
   build (`verification-policy.ts:19-23` is the OSS no-op).
3. Both are idempotency-keyed and rate-limited (`3301` + HTTP 429,
   `bigmodelUsageQuotaProvider.ts:604-626`); a bot retry ladder would burn
   the server's quotas.
4. Spending a reset is irreversible: a wrong tap consumes a gift that expires
   in hours.

If the owner ever wants buttons for them, they belong in a **control** frame
(`giftUse {resetType, idempotencyKey}`, `giftClaim {planId}`) behind an
explicit confirmation row (☢️/✅-style), never in a read path — and that is a
separate design, not A5.

## 6. Proposed /quota (bot side)

Only two new line groups, appended to whichever card actually rendered
(channel card, channel Start-Plan card, or `QuotaDirect`'s card) and **never**
to the degraded `📡` line. Absent data produces no line at all.

### 6.1 The lines

```
📊 <b>Coding plan</b>
⏱ 5 hours: 31/120 credits (26%) · resets 14:00
📅 Weekly: 210/900 credits (23%) · resets 03/11 09:00
🎁 <b>2 resets</b> (5h) · earliest expires in 3h 20m 🔴
🎁 <b>1 reset</b> (week) · expires in 2d 4h
🔴 <b>Claimable: ZCode Weekend Build</b> — GLM-5.3-Flash 10,000M tokens (one-time)
```

Rules:

- **Reset line** (`reset.fiveHour` / `reset.week`): filter `expireAt > now`,
  sort ascending (same law as `codingPlanQuotaResetUi.ts:187-189`), count and
  take the earliest. `1` → `🎁 <b>1 reset</b>`; `N>1` → `🎁 <b>N resets</b>`.
  Prefix `(5h)` / `(week)` only when both types have gifts; with one type the
  word alone is unambiguous. Countdown uses the existing `fmtReset`-style
  helpers (relative: `in 3h 20m` / `in 2d 4h`), never a made-up date.
- **Claimable line**: one per plan (cap 3 plans, then `✂️ … +N more`), title
  from `name` through `PyText.esc`, then the entitlements as
  `<showName> <tokenRaw(grantUnits)> tokens (<period>)`. `period` is rendered
  raw (`one_time` → `one-time`) only if it matches a small known set;
  otherwise it is omitted — never translated into invented copy.
  `description` is **not** rendered: it is free-form server marketing copy
  (potentially long, potentially HTML) and the card must stay deterministic.
- **No invented numbers**: `grantUnits` missing/0 → omit the amount, keep the
  name. `period` unknown → omit. No `serverTime` → no "expires" wording at all
  because there is no deadline field to begin with.

### 6.2 The 🔴 alert — the rule and its price

The plan asks for a 🔴 alert when a claimable gift exists. `ui-style-guide.md`
currently gives 🔴 to the context meter (`:56`), and its *one-meaning law*
(`:11-16`) requires the table to grow before a render uses a glyph. Proposal:

| Emoji | The ONE meaning it owns |
| --- | --- |
| 🎁 | **gift balance** — an unspent quota reset the owner already owns (`/quota`) |
| 🔴 | **act now** — context meter ≥80 % (`/ctx`) · a gift line whose REAL deadline is ≤24 h (trailing mark, `/quota`) · an unclaimed offer line (leading mark, `/quota`) |

The glyph split is one job each: 🎁 states a balance, 🔴 states "act on this".
The 🔴 head on the claimable line is exactly the owner's request ("alerta
cuando haya un regalo reclamable"), and it never fires on a guess: a claimable
line exists only when the preview returned ≥1 plan. The trailing 🔴 on a reset
line fires only from a real `expireAt` inside 24 h — never from
`hasUnreadHistory`, never from a count. The one-meaning law survives because
the *slot* is part of the meaning (leading = an offer to claim, trailing = a
balance about to be lost) — the same escape hatch the guide already uses for
⚡ and 📖 (`ui-style-guide.md:12-16`).

If the owner prefers zero overload, the fallback is 🎁 on every gift line and
no 🔴 at all (the countdown text carries urgency). That is a one-line renderer
change — the guide decides, not the renderer.

### 6.3 Wiring in the bot

- `ChannelFrames.kt` (new `data class QuotaGifts(val taskId: String? = null)`
  + the `encodeQuery` branch, mirroring `:316`) — ~10 lines.
- `BotCommands.kt`:
  - `quota()` (`:1720-1734`) gains a second query after the card resolves,
    skipped when the card is `DEGRADED` / `⚠️ failed`;
  - a pure `renderGifts(json, nowMs, locale)` next to `renderQuota`
    (`:2631`) so the tests pin the copy without a socket;
  - reuse `tokenRaw` (`:2794`), `fmtNum`, `fmtReset`, `PyText.esc`.
- Ladder stays as-is: gifts never rescue a degraded quota card (a gift line
  on top of `📡 channel down` would imply the channel answered).

Suggested cache: none on the reset plane (a /quota is an owner action, and
`/status` is cheap); 60 s in-process on the claim plane so a burst of /quota
does not hammer the billing endpoint. Never call `/opportunity` from `/quota`.

## 7. Risks and the honest degradation case

| Risk | Handling |
| --- | --- |
| **Gifts only appear if someone asked** — `/status` reads granted opportunities; the server evaluates eligibility only on `/opportunity` | The bot reads, never requests. An empty gift section is the normal state after a fresh app start; it is **silent**, not "0 gifts" |
| **No expiry for unclaimed offers** — the preview has no deadline | The claimable line never states a deadline. «expires in 2 days» is not replicable and must not be invented |
| Reset status 401/403 (not logged in, stale OAuth) | `unavailable.reset:"not_authenticated"` → no reset line. Not an error card |
| Preview 401/403 or empty `plans` | `claimable` null / empty array → no claim line |
| Server 429 / `3301` on the read path (shared rate limit with the app) | The read path only hits `/status` and `/preview`; a 429 maps to `request_failed` → silence. No retry loop inside /quota |
| Old fork without the query | `unknown_query` → no gift lines. No capability probe, no warning |
| Channel down / `quota` failed | Existing `📡` / `⚠️` card, gifts not appended |
| Team plans | The reset headers need `TEAM` + org/project; the channel derives it from the same account hint as `quota` (`resolveTeamPlanContext`, `bigmodelUsageQuotaProvider.ts:1104-1112`). If the hint is wrong the call fails → silence |
| Secret hygiene | The capability returns **values**, never credentials. The bot keeps its existing law: keys never log (`QuotaDirect.kt:84-86`, `ChannelWiring.kt:125-130`) |
| Privacy | Plan `name`/`showName` are server strings → `PyText.esc` everywhere; `description` not rendered at all |
| Claim/use temptation | Not implemented. A read path must not mutate quota or the active plan |
| The bot decrypting `credentials.json` itself | Possible in theory (the store is AES-256-GCM under a key derived from `ZCODE_CREDENTIAL_SECRET` or `platform+homedir+username`, `credentialCipherProvider.ts:20-38`), and explicitly **not** the plan: it re-implements a private store, breaks the fork boundary (ADR-022: the channel is the only path), and depends on a derivation the app may change. Same for exporting the JWT through `bot-provider.json` — that would put an account token in a hook file. Both rejected |

Degradation contract, in one sentence: **no data → no line; a line is only
printed from a field the server actually returned; the card never says
"no gifts" because absence of evidence is not evidence of absence.**

## 8. What is missing where

- **Fork** (one additive batch, ~1 query kind + 1 case + 1 dep + spec row):
  `packages/shared/src/daemon-channel.ts`, `packages/services/src/bots/daemonChannel.ts`,
  `packages/services/src/node.ts`, `docs/fork/DAEMON-CHANNEL.md`, plus the
  channel test. No service, provider, endpoint or credential work — both
  calls already exist.
- **Bot** (after the fork ships): `ChannelFrames.kt` (query + encode),
  `BotCommands.kt` (`quota()` composition + `renderGifts`),
  `BotCommandsTest` cases (reset only, claim only, both, urgent 🔴, empty,
  malformed, `unknown_query`).
- **Neither**: the campaign copy («free 500.000K token… Select!») and the
  unclaimed-offer expiry. They are server-side; reproducing them would be
  fabrication.

## 9. Evidence index

Fork (all paths under `/Users/lattenero/Projects/ZCode`):

- `packages/shared/src/zcodeEndpoint.ts:3`, `:384` — `zcode.z.ai` origin and the billing URL map.
- `packages/shared/src/coding-plan-reset.ts:17` — reset snapshot type.
- `packages/shared/src/coding-plan-subscription.ts:132-185` — claim preview/claim/result types.
- `packages/shared/src/marketingTouch.ts:52` — `claim_zcode_plan` action.
- `packages/shared/src/daemon-channel.ts:117`, `:353` — query union and `query_result`.
- `packages/services/src/usage-stats/usageStats.ts:22-30` — reset service surface.
- `packages/services/src/usage-stats/providers/bigmodelUsageQuotaProvider.ts:74`, `:86-113`, `:523-647`, `:649-677`, `:982-999` — endpoints, zod contracts, auth headers, ops.
- `packages/services/src/coding-plan-subscription/codingPlanSubscription.ts:68-69`.
- `packages/services/src/coding-plan-subscription/bigmodelCodingPlanSubscriptionProvider.ts:97`, `:278-336`, `:338-409`, `:807-838` — preview/claim/client-configs.
- `packages/services/src/model-provider/zaiStartPlanBilling.ts:63-69` — balance URL.
- `packages/services/src/bots/daemonChannel.ts:67`, `:116-132`, `:151-166`, `:240-260`, `:437-456`.
- `packages/services/src/credential/credentialService.ts:33`, `:128-137`; `credential/providers/credentialCipherProvider.ts:20-38`.
- `packages/ui/src/components/coding-plan-quota-reset/CodingPlanQuotaResetOpportunity.tsx:1`, `:58`, `:62`, `:80`.
- `packages/ui/src/chat-input-toolbar/contextUsage.tsx:400-410`; `chat-input-toolbar/codingPlanResetOpportunityBadge.ts:24-31`.
- `packages/ui/src/lib/codingPlanQuotaResetUi.ts:187-189`, `:335-352`.
- `packages/ui/src/lib/codingPlanQuotaResetCoordinator.ts:5-9`.
- `packages/ui/src/hooks/useCodingPlanQuotaResetUi.ts:610-670`.
- `packages/ui/src/components/marketing-touch/MarketingTouchProvider.tsx:262`.
- `packages/ui/src/i18n/locales/en-US.ts:693-733` (claim ticket copy, now unused), `:4730-4759` (reset pill copy).
- `packages/desktop/wdio.conf.ts:1648-1701`; `packages/desktop/test/e2e/helpers/coding-plan-upgrade-mock-server.ts:276-345` — contract fixtures.
- `docs/fork/DAEMON-CHANNEL.md:188-221` — §3.4 table to extend.

Bot (paths under `/Users/lattenero/Projects/zcode-bot-kotlin`):

- `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/QuotaDirect.kt:24`, `:81-101`, `:133-155`.
- `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/BotCommands.kt:1720-1747`, `:2631-2719`, `:2794-2816`.
- `modules/channel/src/main/kotlin/dev/lattenero/bot/channel/ChannelFrames.kt:43-103`, `:316`.
- `modules/daemon/src/main/kotlin/dev/lattenero/bot/daemon/ChannelWiring.kt:100-125`.
- `docs/ui-style-guide.md:10-16`, `:54-56`, `:95`.
- `docs/command-parity-plan.md:103-108`, `:114`.
