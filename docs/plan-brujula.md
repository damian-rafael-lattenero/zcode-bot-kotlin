# Plan Brujula — un solo sistema, cinco repos, cinco cosas

> Creado: 2026-10-30 · Estado: v1.2 — decisiones de arranque tomadas (mismo día, ver §7)
> Orden de ejecución decidido: **U0 → U1a (paralelo) → U2 → U1b → U3 → U4 → U5 → U6 → U7 → U8 → U9 → U10 → U11**
> La brújula: Fazt, «Para ser ingeniero de IA no necesitas crear tu propio ChatGPT» (27/10/2026, 2:56) — https://www.youtube.com/watch?v=dWyB3EcpNLQ
> Este plan unifica y obedece la doctrina ya existente: `docs/parity-and-engineering.md` §7 · `~/Projects/bot/docs/kap-first-migration.md` (+ ADRs 021-025) · `~/Projects/agent-brain/plan.md` (+ ADRs 001-008) · `~/Projects/kap/SPEC-5.0-concurrency.md` · `~/Projects/ZCode/FORK.md` + `docs/fork/ARCHITECTURE.md`.

## 0. La brújula (el video)

Para ser ingeniero de IA no hace falta entrenar modelos ni crear tu propio ChatGPT; hacen falta **cinco cosas construidas sobre los modelos existentes**:

1. **Un MCP de tu propio sistema** — servidor MCP (estándar abierto, cualquier lenguaje) para que cualquier asistente compatible use tus datos/herramientas.
2. **Un RAG que responda con tus documentos y cite la fuente** — indexación propia + vector DB o pgvector.
3. **Memoria para tu agente** — un programa separado donde el agente guarda conversaciones y búsquedas; todas tus herramientas se conectan a la misma memoria (estilo Mem0/Letta).
4. **Evals** — «unit tests para respuestas de IA»: preguntas reales con respuestas esperadas, corridas contra modelos caros/baratos/locales, con pass/fail y costo (estilo Promptfoo).
5. **Tu propio harness** — el programa que rodea al modelo: herramientas propias (buscar archivos, ejecutar comandos, navegar) y el loop pensar → usar herramienta → mirar resultado → repetir. «Claude Code y Codex son exactamente esto.»

La frase que lo hace brújula: **«no son cinco proyectos sueltos, es una sola app que va creciendo con cada uno»**.

### ¿Es real esto? ¿Alcanza? (verificado con búsquedas, 30/10/2026)

Sí, es el consenso 2026 — los roadmaps vigentes (roadmap.sh/ai-engineer, KodeKloud, dataskew, etc.) listan exactamente el mismo camino: fundamentos → LLM APIs → RAG → agents/MCP → evals → producción. Datos por cosa:

- **MCP**: estándar abierto de Anthropic (fines 2024) adoptado por toda la industria — ~28% de Fortune 500 en <18 meses, tema de agenda CIO. Los desafíos abiertos son de **seguridad** (CVEs, servers maliciosos): aprender MCP incluye aprender su superficie de ataque.
- **RAG**: sigue esencial pero evolucionó — el consenso 2026 es **context engineering** (la ventana es RAM, no storage): RAG híbrido + reranking, **Adaptive RAG** (clasificador que rutea por complejidad), Agentic RAG, GraphRAG. El long-context no mató a RAG: gana en costo, citación y escala.
- **Memoria**: disciplina de producción con benchmarks propios («State of AI Agent Memory 2026»); Mem0 (capa de memoria) vs Letta (runtime stateful, «Context Repositories» git-based desde feb 2026). Exactamente lo que agent-brain ya hace (extracción + decay + recall).
- **Evals**: Promptfoo/DeepEval/LangSmith/OpenAI Evals son práctica estándar — incluir **red teaming** (Promptfoo lo trae integrado). El comparador de paridad de este repo ES un eval harness de migración.
- **Harness**: camino de aprendizaje reconocido (cursos open source, «loop engineering, memory, eval»); Claude Code y Codex son las referencias. Construir el propio es la recomendación recurrente.

**Qué hay además del video** (mapa de aprendizaje complementario): context engineering como disciplina paraguas · seguridad/red teaming (prompt injection) · multi-agent orchestration · selección de modelos (LLM/SLM, locales con Ollama) · structured outputs/function calling · fine-tuning vs RAG (cuándo cada uno) · presupuestos de costo/latencia · observabilidad de agentes (OTel).

## 1. El mapa: cinco repos, un sistema

| Repo | Rol en el sistema | Estado hoy | Su hoja de ruta propia |
|---|---|---|---|
| `ZCode` (fork, rama `damian`) | El harness de escritorio (Electron) + futuro canal «daemon local» (B3a) | Limpio; 30 commits fork; Olas 1-8 cerradas y deployadas | `FORK.md`, `docs/fork/ARCHITECTURE.md` |
| `bot` (Python) | El harness original Telegram↔ZCode; fuente de verdad que se retira en B5 | Limpio; 1012 tests; semana de paridad acumulando desde 29/10 12:37 | `docs/kap-first-migration.md` + ADRs 001-025 |
| `zcode-bot-kotlin` | El reemplazo: harness sobre KAP (strangler por file contracts) | **B2a transport sin commitear** (11 archivos, sin tests) + toolchain 25 | README + `docs/parity-and-engineering.md` |
| `agent-brain` | El cerebro: bus + RAG + memoria + evals + MCP, todo Kotlin sobre kap | **Fase B sin commitear**; P0-P6 done, P7 planned | `plan.md` (F→B→A→C→D→E, G intercalado) + ADRs |
| `kap` | La stdlib del ecosistema (Maven Central 4.2.0, 1112 tests) | Limpio; v4.2.0 released hoy | `SPEC-5.0-concurrency.md` + 8 issues abiertas |

**Telegram (investigado)**: hay dos mecanismos en el fork — (a) el bot externo por archivos (sidecars `live-io-*.jsonl`, logs CLI, hooks; el que Damian usa) y (b) el feature «Bots» embebido de upstream (`telegramProvider.ts` completo y testeado, NO usado). ADR-022 ya evaluó (b) y decidió: el daemon Kotlin es dueño de Telegram; el fork gana un canal «daemon local» (unix socket) que inyecta por el mismo camino que el composer. Vigente.

## 2. Las cinco cosas → dónde viven y qué falta

| Cosa (video) | Dónde vive | Estado | El gap real | Primera acción |
|---|---|---|---|---|
| 1. MCP | `agent-brain/brain-mcp`: `rag_query`, `memory_search`, `memory_recent`, `bus_status` | Código done (P5) | **No está instalado en el ZCode de Damian** (`make plugin` pendiente) | Instalarlo y usarlo desde ZCode |
| 2. RAG | `agent-brain` P2: híbrido pgvector + RRF + dedup SQL + decay | Código done | **Sin datos vivos**: `event_bus` apagado; y **no hay indexador de repos** (el RAG que Damian quiere: preguntar a sus chats Y a los repos de la Mac) | Activar `event_bus` + deploy; luego indexador de repos |
| 3. Memoria | `agent-brain` P3 (ADR-008) + inyección en turnos (bot ADR-020) | Código done | `memory_inject` apagado; `/brain` `/memory` nunca usados en vivo | Activar knobs + probar |
| 4. Evals | `agent-brain` P6 (EvalRunner, tiers ADR-006) + comparator B1c (gate del cutover) + 21 golden vectors + replay | Corriendo (gatea B1) | EvalRunner→Resource (fase B brain); tier-5 real nunca corrido; comparador sin agendar | Cerrar fase B; agendar comparador |
| 5. Harness | Todo el sistema: fork (desktop) + bot→daemon Kotlin (teléfono) | Migración B2 en curso | B2 transport sin terminar; B3a/B3b live mode; K1/B4/B5 | Terminar B2a (tests + commit) |

**Lectura de brújula**: las cinco cosas ya están construidas (o en obra) — el plan no es crear de cero, es **encender, terminar y unificar**: el cerebro está apagado (knobs), el harness está a medio migrar (B2), y los evals ya están gateando el cutover.

## 3. Reglas fijas (ya decididas; este plan las obedece)

- **FP-first binding** (§7): puro por defecto; **KAP es la stdlib** — lo que falte se implementa EN kap con leyes y version bump («extend-and-bump»); Arrow para errores; leyes antes que features.
- **Denotacional siempre** (refuerzo 30/10, binding): valores puros, funciones puras, ADTs, monadas/applicatives/functors — todo abstracto, puro y denotacional. Lo impuro/sucio/mutable solo si no queda alternativa, con justificación escrita en el borde visible. **Cero concurrencia manual**: `while(true)` y loops caseros se reemplazan por operadores kap (`repeat`, `awaiting`, `awaitUntil`, `Process`, `Schedule`); barrido permanente de estas patterns (gate anti-while(true) en CI, como agent-brain F1).
- **Feedback real permanente** (refuerzo 30/10, binding): pirámide completa unit → integración → e2e → **blackbox e2e** (apps reales levantadas, docker si hace falta, Telegram real con mensajes reales y logs) — la pirámide T1-T7 de parity-and-engineering.md §4. Nada se da por hecho sin corrida real.
- **Strangler, no big-bang**: file contracts como única frontera; golden vectors pinean contratos; el Python no se toca hasta B5.
- **Gates de verdad**: semana de paridad (`comparator --week` = 7 PARITY consecutivos) + ≥2 semanas de recorder traces → recién ahí cutover; rollback = knob atrás.
- **ADR-022 (live mode)**: el daemon Kotlin es dueño de Telegram; el fork forwadea texto libre a la conversación real por unix socket.
- **Observabilidad E1-E5** mapeada a fases: E1✓(B1) · E2 metrics@B2 · E3 traces@B3b · E4 dashboards@B4 · E5 runbook@B5.
- **Commit + push siempre** (regla 28/10); suite + detekt verdes antes de commitear; un commit narrativo por fase.

## 4. La hoja de ruta unificada

### U0 — Cerrar lo abierto (días)
- [ ] `zcode-bot-kotlin`: tests del transport (el catálogo ya trae `ktor-client-mock`), smoke CI, **commit+push B2a** (transport + PyText→contract-types + toolchain 25). *Es el working tree actual.*
- [x] `agent-brain`: fase B cerrada y pusheada (30/10, commit `642f2ee`): B1+B2+B3 completos — un solo bracket `inTransaction` sobre kap `bracketCase` (rollback también ante cancelación, autocommit restaurado NonCancellable), EvalRunner como `Resource` (el eval gate deja cero bases residuales), stdout guard de brain-mcp como `Resource`. Gates: 132 tests + bbLocal + detekt + replay goldens + eval gate 20/20 con modelo local + compose healthy. Hallazgos de paso ya agendados por plan.md (A1 Resource.zip, A4 shutdown→awaiting, C4 http blocking, E7 reloj inyectado).
- [ ] Agendar el comparador diario como **LaunchAgent** (decidido 30/10; como recorder/daemon: StartInterval diario, reportes en `~/.zcode/bot-kotlin/parity/`, plist commiteado) para que la semana de paridad acumule sola.
- [x] **Barrido while(true)/concurrencia manual** (audit hecho 30/10): 3 MUST-FIX en zcode-bot-kotlin (Outbox flock `while(true)` en el WIP, SpectatorWatch polling-loop, TgBreaker casero Mutex+vars), 1 leve en agent-brain (Traces `AtomicLong` global — espera kap `Ref`), 0 en kap (solo un example-documentación aparte). `GlobalScope`: cero en los tres repos. **SendPolicy: uso canónico de `kap.Schedule`** (no duplica el álgebra).
- [ ] Fixes del audit en zcode-bot-kotlin (en vuelo con el agente de B2a): Outbox → `Kap.retry(Schedule.spaced+withMaxDuration)` con semántica exacta del flock-timeout · gate anti-while(true) en ci.yml (patrón mejorado, no solo el literal) · SpectatorWatch body → repeat-form (comportamiento idéntico, semana de paridad intacta) · re-deploy del daemon (Main.kt cambió: selftest).
- [ ] **kap 4.3 «quick unlocks»** (candidatos del audit — con leyes + bump, ideal antes del cutover B2): `CircuitBreaker(successThreshold=2, retryAfter)` para jubilar TgBreaker (Python F79e exige 2 éxitos en half-open; kap cierra con 1 y no reporta retryAfter) · `Schedule.delays(0,1,3)` (la tabla BACKOFFS_S usa el constructor crudo) · `Ref` (lo pide brain: Traces y las métricas `@Volatile`/AtomicLong) · **módulo kap-detekt-rules** con la regla «manual wait loop» (while-true / while+delay / do-while+sleep en src/main fuera de kap) — el gate ecosystem-wide; el grep F1 de brain tiene puntos ciegos (solo el literal con un espacio, no ve polling no-literal ni do-while, módulos hardcodeados).
- Gate de salida: los dos repos limpios, CI verde, semana de paridad corriendo sola.

### U1a — El cerebro en modo lectura (en paralelo con U0/U2; NO toca el bot en vivo)
- [ ] **RAG de repos**: indexador de `~/Projects` (kap, bot, zcode-bot-kotlin, ZCode, agent-brain — docs y código) como fuente nueva de agent-brain (fuera del bus), consultable con `rag_query` desde ZCode y Telegram. La ingesta respeta los ADRs (pgvector único, dedup en SQL).
- [ ] `make plugin` + instalar brain-mcp en el ZCode de Damian → `rag_query`/`memory_search` desde el chat.
- Gate: `rag_query` respondiendo con citas sobre los repos propios, desde ZCode.

### U1b — Encender bus y memoria (inmediatamente DESPUÉS del cutover U2 — decidido 30/10)
- [ ] Activar `"event_bus": true` en `~/.zcode/telegram-remote.json` + deploy → el bus alimenta RAG y memoria con conversaciones reales.
- [ ] Activar `memory_inject` → `/brain` y `/memory` desde Telegram; memoria inyectada en los turnos.
- [ ] Verificación: una conversación real queda recordada (memoria) y consultable (RAG) al día siguiente.
- Gate: memoria citada en un turno real + `/brain` respondiendo en Telegram.

### U2 — B2: el transport (cosa 5 — el teléfono en manos del daemon; acelerado: U1b espera este cutover)
- [ ] B2b: dry-run shadow-send contra la API live; comportamiento 429 verificado.
- [ ] E2: metrics (Micrometer/Prometheus) en el daemon.
- [ ] **Cutover del transporte** cuando `comparator --week` = 7 PARITY Y recorder ≥2 semanas. Rollback = knob.
- Gate: el daemon Kotlin envía por Telegram; el send de Python silenciado.

### U3 — Live mode (B3a + B3b)
- [ ] `ZCode` fork: canal «daemon local» (unix socket): `handleInboundMessage` + stream `bot-channel-continuous` + service queries (listTasks/quota/meta).
- [ ] `zcode-bot-kotlin`: routing live|headless, `/mode live|headless`.
- [ ] E3: OTel spans (span por turno, child por tool call).
- Gate: un turno desde el teléfono viaja por la conversación real (no headless).

### U4 — kap 5.0 (K1): el dogfooding manda
- [ ] `Supervisor` (OneForOne/AllForOne) con leyes — lo necesita agent-brain (C2) y el daemon.
- [ ] `agent-brain` fase A encima: Brain como `Resource.zip`, los tres loops como `Process`, shutdown con `Kap.awaiting`.
- [ ] `KapStream` (backpressure como valores) + `Schedule` semiring + benchmarks (≤2x raw Flow).
- [ ] `agent-brain` C y D (ADTs sellados + kap-arrow; estado-como-valores).
- Gate: SPEC-5.0 completo + usado en superficie real (el contrato de dogfooding).

### U5 — Render (B4)
- [ ] Render core (~4.9k LOC: cajitas, línea parpadeante, agent live) sobre KapStream. *El riesgo mayor del plan.*
- [ ] E4: dashboards Grafana (compose de agent-brain) + chaos drills T7.

### U6 — Cierre (B5)
- [ ] Hooks híbridos delgados (socket al daemon); un solo artefacto LaunchAgent.
- [ ] **Retiro del Python**; runbook portado (E5).

### U7 — Evals propios (la cosa 4 como herramienta, no solo gate)
- [ ] El EvalRunner de agent-brain sube a producto: preguntas reales + esperadas + pass/fail + **costo y latencia** por corrida, comparación de modelos (caro/barato/local), gates en CI.
- [ ] Casos de uso propios: elegir modelo por turno del bot, regresión del renderer, calidad del RAG (respuesta con cita correcta).
- Gate: una corrida de evals comparando ≥2 modelos con pass/fail + costo, reproducible con un comando.

### U8 — MCP público (la cosa 1 abierta al mundo)
- [ ] brain-mcp pasa de plugin local a servidor MCP consumible desde cualquier cliente (auth por token, superficie mínima y documentada).
- [ ] Evaluar un segundo MCP: el sistema Telegram/agente (enviar, turnos, estado) como servidor MCP con sus permisos.
- Gate: un cliente externo (otra máquina / otro cliente MCP) usando el brain con auth.

### U9 — Seguridad / red team (transversal, acá se sistematiza)
- [ ] Red teaming de las superficies nuevas: prompt injection sobre el bot (mensajes entrantes), auth del socket B3a, superficie MCP (U8), tokens en configs.
- [ ] Drills documentados con hallazgos y fixes; idealmente automatizados (estilo Promptfoo red-team) como parte de U7.
- Gate: drill completo corrido con hallazgos cerrados o mitigados.

### U10 — Panel P7 de agent-brain (post-MVP ya planificado)
- [ ] Panel ui.surfaces (estilo diff-ask): memoria + RAG + estado del bus, leyendo el `serve` HTTP.
- Gate: memoria y RAG navegables desde el panel.

### U11 — Marketing de kap (la pieza pública del ecosistema)
- [ ] Publicar `blog-draft.md` (docs/blog), issues menores (#14 matchers, #16 KDoc, #19 errores, #20 Dokka, #18 Playground).
- Gate: post publicado + issues de DX cerrados.

## 5. Cómo se ejecuta desde este chat

- Una tarea a la vez; **un subagente por repo** que ejecuta completo (tests + detekt + CI) y **commitea+pushea solo** (decidido 30/10); el main coordina y revisa los puntos delicados (cutover, migraciones, auth).
- Cada avance actualiza este archivo (changelog abajo). **Los docs nuevos que genere el plan van en inglés** (decidido 30/10); este plan queda en español.
- La semana de paridad y el recorder corren solos: no se toca el bot Python salvo deploy de config (recién en U1b).

## 6. El objetivo de fondo: aprender AI engineering construyendo

El propósito declarado (30/10/2026): **llegar a ser AI engineer construyendo el sistema propio** — el bot de Telegram, el RAG de chats y repos, los MCPs. Cada fase del plan enseña una pieza del roadmap 2026:

- U1 → RAG híbrido + memoria + MCP (3 de las 5 cosas, en vivo, sobre datos propios)
- U2/U3 → harness de producción (transporte, live mode, resiliencia con kap)
- U4 → orquestación type-safe (la pieza diferencial propia: kap)
- U2-U6 → evals y observabilidad como gates de verdad (E2-E5, comparator, golden replay)
- Transversal → seguridad de las superficies nuevas (socket auth del B3a, red teaming del MCP)

## 7. Changelog

- 2026-10-30 v1.5: U0 avanza — agent-brain fase B CERRADA (`642f2ee`, B1+B2+B3, todos los gates verdes incluido eval 20/20 con modelo local). Pendiente: agente B2a del bot (transport + tests + fixes del audit + LaunchAgent comparador).
- 2026-10-30 v1.4: U0 audit de impurezas completado — 3 MUST-FIX en el bot (Outbox/SpectatorWatch/TgBreaker), 1 leve en brain (Traces→Ref), kap limpio; SendPolicy confirmado canónico; gate F1 de brain con puntos ciegos y kap sin detekt rules (hay que crear el módulo). Fixes derivados en vuelo con el agente B2a; kap 4.3 quick unlocks agregados a U0.
- 2026-10-30 v1.3: fundamentos elevados a reglas de piedra (orden del usuario): §3 gana «Denotacional siempre» (impuro solo justificado en el borde) y «Feedback real permanente» (blackbox e2e con Telegram real); U0 gana el barrido while(true)/concurrencia manual. Arranca U0 con subagentes (transport+commit, agent-brain fase B, LaunchAgent comparador, barrido).
- 2026-10-30 v1.2: segunda ronda de decisiones — subagentes ejecutan y commitean (main revisa lo delicado) · post-B5 entra TODO como fases: nacen U7 (evals propios), U8 (MCP público), U9 (red team), U10 (P7 panel), U11 (marketing kap) · docs nuevos del plan en inglés.
- 2026-10-30 v1.1: decisiones del usuario — el plan vive en este repo (docs/) · comparador diario como LaunchAgent · **U1 se parte**: U1a (RAG de repos + brain-mcp, sin tocar el bot) arranca en paralelo con U0; U1b (event_bus + memory_inject) va inmediatamente después del cutover U2, que se acelera por eso.
- 2026-10-30 v1: nace el plan. Contexto levantado con 5 subagentes (uno por repo); transcripción del video leída completa; Telegram investigado (dos mecanismos; ADR-022 vigente); estado del arte validado con búsquedas (MCP/RAG/memoria/evals/harness, consenso 2026); agregados el objetivo de aprendizaje (§6) y el RAG de repos en U1.
