# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Вся документация, комментарии и сообщения линтера в репозитории — на русском. Пиши так же.

## Что это

Архитектурная модель ландшафта как код на [LikeC4](https://likec4.dev): DSL-файлы в `model/`, инварианты метамодели в `tools/`, CI проверяет и публикует сайт. Тестов и линтера кода нет — роль тестов выполняет `npm run check`.

## Команды

```bash
npm install
npm run dev       # интерактивный просмотр, http://localhost:5173
npm run check     # полный цикл: gen -> likec4 validate -> export json -> tools/check.mjs (то же, что CI)
npm run gen       # tools/api-source/*.json -> model/gen/*.gen.c4 (gen-api: легаси v1, gen-model: v2 + системы + рёбра)
npm run test:tools   # тесты генератора (node:test)
mvn -q -f analyzer/pom.xml test   # тесты анализатора
mvn -q -f analyzer/pom.xml compile exec:java -Dexec.args="analyze <id>|--all --date YYYY-MM-DD"   # прогон анализа (dev-вход)
mvn -q -f analyzer/pom.xml compile exec:java -Dexec.mainClass=arch.analyzer.server.ServerKt       # REST-сервер, порт 8080 (--port N)
cd ui && npm install && npm run dev   # UI анализатора, http://localhost:5174 (прокси /api -> 8080)
npm run impact -- shop.orders.api.post_api_v1_orders   # кто сломается при изменении
npm run drift     # сверка build/model.json с tools/live-services.txt
npm run owners    # владельцы систем из CODEOWNERS
npm run png       # картинки в build/images
node tools/verify.mjs build/model.json <id> --by=<логин> --against=docs|owner|probe   # подтвердить реконструкцию
node tools/verify.mjs build/model.json --list
```

`impact`, `drift`, `verify` читают `build/model.json` — сначала нужен `npm run check` (он его экспортирует).

## Структура

- `analyzer/` — Kotlin/Maven: полки-источники (source, config, bytecode/ASM по `jar:` из repos.yml, runtime по `runtimeUrl:` — снимок Actuator mappings/env — и `traces:` — файл OTel-спанов (снять: `-javaagent:opentelemetry-javaagent.jar` + file/OTLP-экспорт), опционально jqassistant через адаптер `analyzer/jqassistant/extract.sh`, печатающий строки `TYPE|attr=value|...|source|confidence`) извлекают факты из репозиториев, реконсилятор сливает их в `tools/api-source/*.json` (v2); Ktor-сервер (`server/`) — REST для UI: инвентарь, запуск анализа (после — сам зовёт `npm run gen`), отчёты, дифф, онбординг. Спека: `docs/superpowers/specs/2026-08-17-arch-analyzer-design.md`.
- `ui/` — React 19 + Vite + Tailwind 4 + shadcn/ui: дашборд контейнеров, карточка с отчётом и диффом, онбординг систем/контейнеров. Единственный пользовательский интерфейс анализатора.
- `registry/systems.yml` — реестр систем (генерятся в `model/gen/systems/`); `registry/repos.yml` — привязка container-id к репозиторию. Владельцы — только в CODEOWNERS.
- `registry/llm.yml` (опционально) — включает полку llm: `llm: { baseUrl: <OpenAI-совместимый /v1>, model: ..., enrich: true }`, ключ — `apiKey` или env `LLM_API_KEY`; корпоративный Qwen или LM Studio. Роли: fallback на точках внимания, summary/description (enrich), ревью в отчёт, гипотезы в триаже. Всё с confidence ≤ 0.7, кэш в `workspace/_llm-cache/`.
- `registry/aliases.yml` — host/feign/appName → container-id, пополняется анализатором (ручные записи не перетираются); `registry/resolutions.yml` — ручные решения по stub'ам (склейка `container` / `external` / `assign: {container: auth.sso}` — stub становится наблюдаемым контейнером чужой системы в `model/gen/observed/`, его api дообогащается из вызовов при каждой регенерации), пишется через UI-триаж; `registry/unresolved.json` — журнал нераспознанных целей, **генерируется**, руками не правится. Нераспознанные цели живут stub'ами в системе `unknown` (`model/30-unknown.c4` + `model/gen/unknown/`), автосклейка — по алиасам и совпадению эндпоинтов.
- Прогоны анализа — очередь с одним воркером (`queued → running → done|failed` в `workspace/<id>/status.json`; зависшие после рестарта сервера помечаются failed). Источники контейнера (jar/runtimeUrl/traces) редактируются после онбординга: `PUT /api/containers/{id}/sources` или блок «Источники» на карточке.
- `workspace/` (gitignore) — evidence-файлы полок и отчёты реконсиляции; персистентны, дают дообогащение при появлении новых источников.
- `model/00-spec.c4` — метамодель: типы элементов, связей, теги. Одна страница, расти не должна. Kind заводится только если он меняет правила связывания (проверяемые в `check.mjs`); если различие меняет только картинку — это style, ортогональный признак — tag.
- `model/10-shop.c4` — файл на систему; владелец файла — команда в `CODEOWNERS`.
- `model/20-views.c4` — виды, по одному на реальный вопрос.
- `model/systems/<id>/<id>.c4` — **система анализатора целиком в одном файле** (контейнеры, api, сторы, каналы, связи и views); ведёт генератор (`registry/systems.yml` + `tools/api-source/*.json`). **Править руками можно** — но регенерация перезапишет: закоммиченные правки вытаскиваются обратно через git diff, незакоммиченные генератор спасает в `workspace/_backup/` с предупреждением. overrides.c4 для систем анализатора не используется. `model/gen/` — только догадки: `unknown/` (stub'ы), `observed/` (наблюдаемые контейнеры чужих систем), `_externals` + легаси v1 (`shop.orders.gen.c4`); вот их руками не править — CI-инвариант «`npm run gen` без диффа» остаётся для `model/gen/`.
- `model/verified.json` — append-only журнал подтверждений реконструкций; правится только через `tools/verify.mjs`.
- `tools/check.mjs` — все инварианты (см. ниже); ошибки роняют CI, предупреждения нет.
- `meta.md` — заметки о будущем анализаторе Java/Kotlin-кода и планах; не догма, но контекст решений.

## Метамодель (главное)

Пять типов контейнеров — `client`, `service`, `worker`, `store`, `channel` — и пять типов связей — `call`, `publish`, `deliver`, `read`, `write`. Стрелка = направление данных, всегда. Канал (топик/очередь) — это узел, а не подпись на стрелке. Postgres/Redis/S3 — все `store`, разница в `technology`.

Три круга — три kind'а системы: `system` (наша, глубина до эндпоинтов), `orgSystem` (соседняя команда, описываем только то, что реально вызываем), `externalSystem` (чёрный ящик, один узел + `metadata.contract`).

Теги: `#public`, `#pii`, `#deprecated`, `#stub` (наша догадка о чужой системе), `#inferred` (извлечено анализатором). `#stub`/`#inferred` — происхождение, а не статус: подтверждение через `verify.mjs` сбрасывает таймер, но тег не снимает.

## Ключевые инварианты (`tools/check.mjs`, ошибки)

- у каждой системы есть запись в CODEOWNERS; `metadata.owner` в модели запрещён (дублировал бы CODEOWNERS);
- у `store` ровно один писатель (детект shared database); `store` ничего не инициирует;
- `worker` не принимает `call`; `client` не может быть целью связи;
- в `channel` только `publish`, из него только `deliver`;
- границу системы пересекают только `#public`-элементы; прямой доступ в чужие хранилища запрещён;
- если у контейнера есть `api` — `call` обязан вести в конкретный `operation`, а не в контейнер (у контейнеров без `api` можно звонить в контейнер — постепенное внедрение);
- нельзя звонить в `operation` с прошедшим `sunset`;
- `externalSystem` не описывается изнутри; у `orgSystem` описано только используемое.

## Что дорого менять

**Id элементов — никогда не переименовывать**: на них ссылаются связи и виды. Title менять можно свободно. Схема генерации id операций (`{method}_{path}`, `/`→`_`, `{param}`→`_p_`) — контракт: на сгенерированные id ссылаются рукописные связи.

## Что в модели не описываем

Классы/компоненты внутри сервиса, полные схемы (только сигнатура в metadata, тело — в `tools/api-source/`), хосты/реплики/зоны (знает k8s), `/health`-эндпоинты. Факт, живущий в системе «с зубами» (CODEOWNERS, k8s, OpenAPI), в модель не дублируется.

## Документация

`CHEATSHEET.md` — как описать сервис (стартовая точка). `CONTRACTS.md` — контрактный слой и формат JSON для анализатора. `VERIFICATION.md` — механика подтверждений (hash поверхности, сроки docs 90 / owner 180 / probe 365 дней). `TIERS.md` — три круга и владение.
