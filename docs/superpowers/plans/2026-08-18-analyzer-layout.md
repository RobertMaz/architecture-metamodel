# Analyzer Layout (подпроект 8) — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Система анализатора = один файл со всей кухней: `model/systems/<id>/<id>.c4` (система, контейнеры, api, сторы, каналы, рёбра, views). `model/gen/` остаётся только для догадок (unknown/observed/externals + легаси v1).

**Architecture:** gen-model перестаёт писать `gen/systems/`, `gen/<container>.gen.c4`, `gen/_shared.gen.c4`; вместо этого — по файлу на систему из systems.yml: декларация системы, её контейнеры из v2-доков, её сторы/каналы (идентичность общих узлов не меняется), исходящие рёбра её контейнеров, deliver её каналов, затем `views{}`: `<id>_containers of <id>` (аналог shopContainers) + `<id>_<name>_api of <container>` для контейнеров с операциями (аналог ordersApi). Прунинг каталогов `model/systems/*`. ModelDiff и CODEOWNERS учитывают новый путь. Id элементов не меняются — меняются только файлы (это дёшево).

**Spec:** `docs/superpowers/specs/2026-08-17-arch-analyzer-design.md` (уточнение раздела «Генерация модели» по решению пользователя 2026-08-18)

## Global Constraints
- Id элементов и операций неизменны; меняется только раскладка файлов.
- Сгенерированные файлы — с шапкой «РУКАМИ НЕ ПРАВИТЬ» независимо от каталога; правки человека — overrides.c4.
- Детерминизм, writeIfChanged, прунинг; check 0 ошибок; view-id уникальны (`<system>_...`).

### Task 1: gen-model — раскладка по системам + views
Переписать рендер: model/systems/<id>/<id>.c4 (model+views), gen/ только unknown/observed/_externals; прунинг model/systems/*; тесты gen-model.test.mjs переписать под новую раскладку (+ тест на views и прунинг). Commit.

### Task 2: Переход репозитория
git rm старой раскладки (gen/systems/, gen/petclinic.*.gen.c4, gen/_shared.gen.c4), CODEOWNERS (`/model/systems/petclinic/ @acme/team-petclinic`), ModelDiff watched += model/systems, regen petclinic, `npm run check` 0 ошибок, идемпотентность. Commit.

### Task 3: Приёмка + docs
LikeC4 видит новые виды (валидация + dev-сервер глазами/HTTP), CLAUDE.md/CONTRACTS.md обновить раздел раскладки, merge.

## Self-review (выполнен)
Оба пункта пользователя: папка+один файл на систему с views (T1), «свои» контейнеры больше не в gen — gen только для догадок (T1/T2). Плейсхолдеров нет.
