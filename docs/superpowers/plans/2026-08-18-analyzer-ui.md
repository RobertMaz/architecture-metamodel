# Analyzer UI (подпроект 2) — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Единственный пользовательский интерфейс анализатора: Ktor REST поверх движка + React-дашборд (инвентарь, запуск анализа, живой статус, дифф прогона, онбординг контейнеров/систем).

**Architecture:** Ktor-сервер в `analyzer/` (порт 8080, CORS для dev-Vite): читает реестры и workspace, запускает `Analyze.run` асинхронно (поток на контейнер, статус в `workspace/<id>/status.json`), после анализа зовёт `npm run gen`, дифф отдаёт через `git diff`. UI в `ui/`: Vite + React 19 + Tailwind 4 + shadcn/ui (new-york, neutral, CSS-переменные) + TanStack Query/Table + react-router + lucide; dev-прокси `/api` → 8080.

**Tech Stack:** Ktor 3.1 (netty, jackson), React 19, Vite 6, Tailwind 4, shadcn/ui, TanStack Query 5 + Table 8, react-router 7.

**Spec:** `docs/superpowers/specs/2026-08-17-arch-analyzer-design.md` (раздел «Оркестрация и UI»)

## Global Constraints

- CLI не продуктовый интерфейс; всё пользовательское — через UI/REST.
- Детерминизм артефактов не трогаем: сервер лишь вызывает существующий движок и `npm run gen`; status.json — эфемерный (workspace, гитигнор), там таймстемпы можно.
- Коммитит человек: сервер диффы показывает, но git-команд, меняющих историю, не выполняет.
- Онбординг пишет `registry/*.yml` и CODEOWNERS атомарно и отсортированно.
- Русский язык в UI-текстах и docs.

---

### Task 1: Ktor-сервер: скелет + инвентарь

**Files:** `analyzer/pom.xml` (deps ktor-server-netty/content-negotiation/jackson/cors/test-host), `analyzer/src/main/kotlin/arch/analyzer/server/Server.kt`, `.../server/Api.kt`, test `.../server/ApiTest.kt`

**Interfaces:**
- `fun buildApp(archRoot: Path): Application.() -> Unit` — модуль Ktor (тестируется testApplication).
- `GET /api/health` → `{"status":"ok"}`.
- `GET /api/systems` → `[{id,title,kind,description}]` из systems.yml.
- `GET /api/containers` → `[{id, system, repo, path, analyzed: bool, operations: n, calls: n, stores: n, unresolvedCalls: n, lanes: [..], state: idle|running|failed}]` — из repos.yml + tools/api-source/*.json + workspace/*/status.json.
- Запуск: `mvn -q -f analyzer/pom.xml compile exec:java -Dexec.mainClass=arch.analyzer.server.ServerKt` (порт 8080, `--arch-root` по умолчанию CWD).

Шаги: красный тест (testApplication: временный archRoot с реестрами из фикстур SP1) → реализация → зелёный → commit.

### Task 2: Запуск анализа + статус + дифф

**Files:** `.../server/Runs.kt` (реестр запусков в памяти + поток), `.../server/Api.kt` (роуты), `.../core/Analyze.kt` (запись status.json), test `.../server/RunsTest.kt`

**Interfaces:**
- `POST /api/containers/{id}/analyze` → `{started: true}`; отказ 409, если уже running.
- Статус пишется в `workspace/<id>/status.json`: `{state: running|done|failed, lanes, factCount, error?, finishedAt}` — сервер его же и читает (переживает рестарт).
- После успешного `Analyze.run` сервер выполняет `npm run gen` (ProcessBuilder, root архрепо) — модель дорастает без ручных шагов.
- `GET /api/containers/{id}/report` → reconcile-report.json + сводка дока.
- `GET /api/diff` → `{files: [{path, additions, deletions}], patch}` — `git diff --numstat/-p HEAD -- tools/api-source model/gen registry` (+ untracked через `git status --porcelain`).

Шаги: тест на цикл analyze→done→report (фикстурный мини-репо), тест 409, тест диффа (изменить файл в temp-репо `git init`) → реализация → commit.

### Task 3: Онбординг контейнера и системы

**Files:** `.../server/Onboarding.kt`, test `.../server/OnboardingTest.kt`

**Interfaces:**
- `POST /api/systems` `{id,title,kind,description,owner}` → дописывает systems.yml (сортировка по id) + строку CODEOWNERS `/model/gen/systems/<id>.gen.c4  <owner>`; 409 на дубль id.
- `POST /api/containers` `{id, repo, path}` → валидация: префикс id — известная система, path существует; дописывает repos.yml (сортировка); 409 на дубль.

Шаги: красный тест → реализация (перезапись yml целиком через Jackson-YAML с сортировкой, комментарии шапки сохраняются константой) → commit.

### Task 4: UI-скелет

**Files:** `ui/` (Vite React TS), `ui/vite.config.ts` (proxy `/api`→8080), Tailwind 4 + shadcn/ui init (style new-york, baseColor neutral, cssVariables), react-router (`/` дашборд, `/containers/:id` карточка), TanStack Query provider, `ui/src/lib/api.ts` (типизированные fetch-обёртки под эндпоинты Task 1–3).

Шаги: scaffold (`npm create vite`), установка зависимостей, shadcn init + компоненты (button, card, table, badge, dialog, input, select, sonner), каркас страниц-заглушек → `npm run build` зелёный → commit.

### Task 5: Дашборд

**Files:** `ui/src/pages/Dashboard.tsx`, `ui/src/components/ContainersTable.tsx`, `ui/src/components/NewContainerDialog.tsx`, `ui/src/components/NewSystemDialog.tsx`

Содержимое: таблица контейнеров (TanStack Table: id, система, статус-badge, полки, счётчики операций/вызовов/сторов, unresolved) с сортировкой; кнопка «Анализ» на строке (POST analyze, поллинг статуса Query refetchInterval 1s пока running); тосты об ошибках; диалоги онбординга системы/контейнера. Шаги: реализация → ручная проверка против живого сервера → `npm run build` → commit.

### Task 6: Карточка контейнера + дифф

**Files:** `ui/src/pages/Container.tsx`, `ui/src/components/DiffView.tsx`, `ui/src/components/ReportView.tsx`

Содержимое: сводка дока (операции, вызовы с целями, сторы, каналы) из report-эндпоинта; кнопка «Провести анализ» с живым статусом полок; блок «Дифф прогона» — файлы + патч (моноширинный, свёрнутый по файлам); ссылки на LikeC4 dev (`http://localhost:5173`). Шаги: реализация → ручная проверка → build → commit.

### Task 7: Приёмка подпроекта

Шаги: сервер на архрепо + `ui npm run dev`; e2e руками через curl/Playwright-недоступен — минимум: `curl` сценарий (health → containers → analyze petclinic.vets → дождаться done → report → diff); `npm run check` зелёный; повторный analyze → дифф пуст; docs (CLAUDE.md: как поднять сервер и UI) → commit.

## Self-review (выполнен)

Покрытие спеки: REST-оркестрация (T1–T3), async-статусы через status.json (T2), онбординг из UI-концепции (T3), стек UI как заказан (T4), дашборд/карточка/дифф (T5–T6), «сервер не коммитит» — только показ диффа (T2/T6). Триаж реестра — подпроект 3 (там же его экран). Плейсхолдеров нет; типы согласованы (эндпоинты T1–T3 ↔ api.ts T4).
