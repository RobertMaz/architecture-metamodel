# Analyzer Registry (подпроект 3) — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Разрешение исходящих вызовов: алиасы, автосклейка по эндпоинтам, stub'ы в `model/gen/unknown/`, журнал `registry/unresolved.json`, ручные решения в `resolutions.yml`, экран триажа в UI.

**Architecture:** Разрешение — чистая функция в `tools/gen-model.mjs` над (все v2-доки, aliases.yml, resolutions.yml): вызов → операция/апи реального контейнера, либо stub-контейнер в системе `unknown` + запись в `unresolved.json` (генерируется, с кандидатами по скорингу эндпоинтов). Алиасы пополняет анализатор при прогоне (appName → id). Ручное решение (склейка/external) — append в `resolutions.yml` через REST, дальше обычная регенерация. `check.mjs`: R7 не применяется к целям с `#stub`.

**Tech Stack:** тот же (Kotlin/Maven, Node, React).

**Spec:** `docs/superpowers/specs/2026-08-17-arch-analyzer-design.md` (раздел «Реестр разрешения»)

## Global Constraints

- Детерминизм: unresolved.json и gen/unknown/* — генерируемые артефакты (без таймстемпов, всё отсортировано); входы человека — aliases.yml (частично) и resolutions.yml (append-only по духу).
- Id stub'а стабилен: `unknown.<slug(feignName|host|urlTemplate)>` — на него могут успеть сослаться, менять схему нельзя.
- Разрешённый вызов обязан вести в operation (точное совпадение method+normPath), иначе в элемент api цели — C2 запрещает контейнер.
- Русский язык, `npm run check` зелёный после каждой задачи.

---

### Task 1: Автопополнение aliases.yml при анализе

**Files:** `analyzer/src/main/kotlin/arch/analyzer/core/Aliases.kt`, правка `Analyze.kt`, test `AliasesTest.kt`

- `registry/aliases.yml`: `aliases: { customers-service: petclinic.customers, ... }` (ключи отсортированы, шапка-комментарий).
- После reconcile: upsert `appName -> containerId` и `<короткое имя контейнера> -> containerId`; существующие ключи с другим значением НЕ перетираются (конфликт → в отчёт прогона).
- Тесты: пустой файл создаётся; ручная запись не перетирается; повторный прогон не меняет байты. Commit.

### Task 2: Разрешение вызовов через алиасы в gen-model

**Files:** `tools/gen-model.mjs`, `tools/gen-model.test.mjs`

- Загрузка aliases.yml и всех доков. Индекс операций: containerId → (method+normPath → opId) и id элемента api.
- resolveTarget(call): `target.container` → как есть; `feignName` в алиасах; `host` в алиасах; первый label host (`billing.svc.cluster.local` → `billing`) в алиасах.
- Разрешено: ребро `caller -[call]-> <target>.api.<opId>` при точном совпадении method+normPath, иначе `-> <target>.api`; метка ребра — `'METHOD /path'`.
- Тесты: вызов с host=имя алиаса → ребро в операцию; без совпадения операции → в api. Commit.

### Task 3: Stub'ы и unresolved.json

**Files:** `tools/gen-model.mjs`, тесты; `CODEOWNERS` (+`/model/gen/unknown/`), `model/30-unknown.c4` (руками, один раз: `unknown = orgSystem 'Непознанное'`)

- Неразрешённый вызов → stub: id `unknown.<slug(feignName ?? host ?? urlTemplate)>`; в `model/gen/unknown/<slug>.gen.c4`: контейнер service `#stub #inferred` + api + наблюдённые операции (method+path из вызовов, `#stub #inferred`); ребро от вызывающего в операцию stub'а.
- `registry/unresolved.json` (генерируется): `[{stubId, signature{hosts,feignNames,urlTemplates}, observedEndpoints, callers, candidates[{container, score, matched}]}]`; кандидат — контейнер, у которого совпадают эндпоинты (score = совпавшие/наблюдённые), 1.0 и единственный → **авторазрешение** вместо stub'а.
- Вызовы без method+path и без host/feign (мусорные) — только в unresolved (без stub-узла), поле `note: 'нет сигнатуры'`.
- Тесты: unmatched вызов рождает stub-файл + запись; кандидат со score 1.0 единственный → авторазрешение, stub'а нет; повторный прогон стабилен. Commit.

### Task 4: Послабление R7 в check.mjs

**Files:** `tools/check.mjs`

- В R7: если `tagsOf(r.dst)` или теги целевой системы содержат `stub` — пропуск (мы ещё не знаем, кто это; после склейки правило включается само).
- Проверка: `npm run check` на petclinic с stub'ами — 0 ошибок. Commit.

### Task 5: resolutions.yml — ручные решения

**Files:** `tools/gen-model.mjs`, тесты; формат `registry/resolutions.yml`:

```yaml
resolutions:
  unknown.legacy_billing:
    container: billing.core        # склейка с реальным контейнером
  unknown.stripe_com:
    external:
      id: stripe
      title: Stripe
      contract: 'MSA-2026-001'
```

- container-решение: вызовы этого stub'а разрешаются в контейнер (ребро в op/api), stub и запись unresolved исчезают.
- external-решение: генерится `externalSystem` `#stub` (в `model/gen/unknown/_externals.gen.c4`, metadata.contract), рёбра ведут в него (R7 обходится через #stub, T2 не нарушаем — без потрохов: вызовы ведут в саму систему).
- Тесты на оба пути + идемпотентность. Commit.

### Task 6: REST триажа

**Files:** `analyzer/.../server/Api.kt`, `.../server/Triage.kt`, test `TriageTest.kt`

- `GET /api/unresolved` → содержимое registry/unresolved.json (`[]` если нет).
- `POST /api/unresolved/{stubId}/resolve` `{container}` или `{external:{id,title,contract?}}` → валидация (container существует в repos.yml; id external — slug), запись в resolutions.yml (сортировка, шапка), затем `npm run gen`; 404 на неизвестный stubId, 409 на уже решённый.
- Тесты через testApplication (временный root с готовым unresolved.json; gen-скип без package.json). Commit.

### Task 7: UI — экран триажа

**Files:** `ui/src/pages/Triage.tsx`, `ui/src/lib/api.ts` (+типы), `ui/src/Layout.tsx` (навигация), роут `/triage`

- Список stub'ов: сигнатура, наблюдённые эндпоинты, кто зовёт (со ссылками на карточки), кандидаты со score и кнопкой «Склеить», выбор произвольного контейнера (Select из инвентаря), кнопка «Это external» (диалог: id, title, contract).
- Бейдж количества на навигации. После решения — invalidate запросов, тост. `npm run build` зелёный. Commit.

### Task 8: Приёмка на petclinic + docs

- Прогнать анализ всех 4 сервисов (той же датой 2026-08-17, чтобы дифф был содержательным): вызов gateway→customers-service должен склеиться автоматически через алиас (ребро в операцию `get_owners_p`), вызов visits (`{_}pets/visits`) — попасть в unresolved/stub.
- Через REST решить visits-вызов склейкой с petclinic.visits → ребро появилось, stub исчез.
- `npm run check` — 0 ошибок; повторная генерация — пустой дифф; тесты все зелёные.
- docs: CLAUDE.md (aliases/resolutions/unresolved, триаж), CONTRACTS.md не трогаем. Commit, merge в master.

## Self-review (выполнен)

Покрытие спеки: aliases (T1), склейка по алиасам (T2), stub+unresolved+кандидаты+автосклейка (T3), послабление #public (T4), append-only ручные решения + external (T5), REST (T6), UI-триаж (T7), приёмка «шаг за шагом карта склеивается» (T8). Id stub'ов зафиксированы. Плейсхолдеров нет.
