# Incidente 30/10 — la guerra del token y los cadáveres del thinking

## Qué pasó (resumen para el owner)

Entre las 09:44 y las 13:40 del 30/10 el bot perdía mensajes y los carteles
de "thinking" mentían. Causa raíz: **el bot Python "jubilado" seguía vivo** —
no por launchd (eso murió en el retiro) sino por los **hooks del CLI**
(`~/.zcode/cli/config.json`): cada prompt/pregunta engendraba procesos Python
nuevos (`watch-commands` = su poller). Ese poller leía el token del config
compartido (el token NUEVO tras el swap de anoche) y peleaba `getUpdates`
contra nuestro daemon → 409 Conflict × 99 → mensajes perdidos.

## Fixes aplicados (todos commiteados y deployados)

1. **Hooks Python eliminados**: la clave `hooks` completa fuera de
   `~/.zcode/cli/config.json` (el flag `enabled:false` no era honrado por el
   CLI — medida: los procesos renacían igual). Los procesos vivos mueren con
   el reinicio de la app de las 13:41.
2. **Bot Telegram legacy de la app deshabilitado**: `~/.zcode/v2/bot-config.v3.json`
   → bot "z.ai" (provider telegram) `enabled:false`. Era un segundo poller
   potencial del mismo token. El bot `daemon-local` (nuestro canal) sigue ON.
3. **Sello de cadáveres** (commit 06bb271): cuando el hub reemplaza un turno,
   el cartel viejo se edita UNA vez con «⛓️ turno reemplazado…» en vez de
   quedar congelado diciendo "thinking" (el dueño lo vio tick-ear con la app
   CAÍDA — la mentira que desató esto). El sello de arranque (dcd2b1a) cubre
   los huérfanos de restarts.
4. **El "cerraba y no abría"**: el `open -a ZCode` moría con la sesión
   (nieto de la app). Ahora lo relanza un one-shot de launchd (hijo de
   launchd, inmune).

## Estado del resto

- Watchdog GitHub: los fixes están (f1f3421, 9d2cc95 — cron re-registrado en
  slots :02-:57); a vigilar los próximos `schedule` runs.
- /quota: responde honesto pero el proveedor de uso del fork dice
  `not_configured` (la app no tiene el servicio de usage configurado) —
  pendiente configurarlo en la app o leer el cache de Python.
- Firehose: una sola garganta (token nuevo) hasta nueva orden; el bot viejo
  (8892…) está fuera de toda la config.
