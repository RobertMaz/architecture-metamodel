# Analyzer Ops (подпроект 7) — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Три операционных доработки: очередь-джобик для анализа (вместо потока-на-клик), редактирование источников контейнера после онбординга, резолюция assign — stub переезжает контейнером в явную (обычно чужую) систему и дообогащается.

**Architecture:** `Runs` — однопоточный executor: состояния `queued → running → done|failed` в status.json, sweep зависших при старте (последовательность заодно убирает гонку aliases.yml/`npm run gen`). `PUT /api/containers/{id}/sources` правит repos.yml (пустая строка — удалить поле). Резолюция `assign: {container: auth.sso}`: gen-model собирает такие stub'ы в «наблюдаемые контейнеры» (`model/gen/observed/<id>.gen.c4`, `#stub #inferred`, api из объединения наблюдённых эндпоинтов всех вызовов; тот же slug цели → та же резолюция → автомап и дообогащение), рёбра ведут в операции.

**Spec:** `docs/superpowers/specs/2026-08-17-arch-analyzer-design.md` (развитие разделов «Оркестрация» и «Реестр»)

## Global Constraints
- Детерминизм и «файл переписывается только при изменении» — без исключений; observed-каталог прунится как unknown/.
- Валидация assign: система-префикс заведена в systems.yml; id, уже существующий в repos.yml, — отказ (там обычная склейка).
- Русский, check зелёный, TDD.

### Task 1: Очередь прогонов
`Runs`: single-worker executor, статус `queued`, sweep `queued|running → failed («прерван перезапуском»)` при создании; `failedLanes` в status.json. UI: бейдж «в очереди», поллинг и на queued. Тесты: второй start того же id → false; sweep помечает зависших. Commit.

### Task 2: Источники после онбординга
`Onboarding.updateSources(id, patch)` + `PUT /api/containers/{id}/sources`; ContainerDto отдаёт jar/runtimeUrl/traces; карточка контейнера — блок «Источники» (5 полей, «Сохранить»). Тесты REST (404/400/200, пустая строка удаляет поле). Commit.

### Task 3: Резолюция assign
resolutions.yml: `assign: {container: <system>.<name>}`; Triage валидация; gen-model: observed-контейнеры + рёбра в операции + прунинг; unresolved очищается; тест «две цели одного host в разное время → union эндпоинтов». UI-триаж: диалог «В систему…» (Select систем + имя). Commit.

### Task 4: Приёмка + docs
e2e Playwright: очередь (два клика подряд), правка источников на карточке, assign через UI (фикстурно вернуть stub); check, идемпотентность, CLAUDE.md. Commit, merge.

## Self-review (выполнен)
Пункты пользователя 1:1: джобик (T1), источники правды после добавления (T2), явная система для чужого + автомап и дообогащение эндпоинтов (T3). Плейсхолдеров нет.
