# Analyzer Runtime (подпроект 6) — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Полка `runtime` — факты с наивысшим приоритетом из живого приложения: снимок Spring Actuator (роуты, конфиг, имя) и разбор OTel-спанов (реальные исходящие вызовы с отрезолвленными адресами).

**Architecture:** `repos.yml` получает `runtimeUrl:` (база запущенной апки) и `traces:` (путь к файлу спанов OTLP/JSON или JSON-lines). `RuntimeLane` = две под-части: Actuator (GET `/actuator/mappings`, `/actuator/env` — эндпоинты conf 0.97, datasource, appName) и OTel (CLIENT-спаны → OUTGOING_CALL с реальным host/path, PRODUCER/CONSUMER kafka → PUBLISH/SUBSCRIBE, db.system → STORE_ACCESS; conf 0.97). Приоритет `runtime` уже первый в реконсиляторе — рантайм-факты побеждают и подтверждают статику. Тесты: фейковый Actuator на JDK HttpServer + файл-фикстура спанов.

**Tech Stack:** java.net.http, существующий стек.

**Spec:** `docs/superpowers/specs/2026-08-17-arch-analyzer-design.md` (полка 6)

## Global Constraints

- Полка применима только при наличии `runtimeUrl` (и живом ответе `/actuator/mappings`) или `traces`; недоступный рантайм — не ошибка прогона, полка просто не применима.
- Actuator-эндпоинты `/actuator/**` и `/error` отфильтровываются (правило репо: /health в модели не живёт).
- Детерминизм: снимок актуатора кэшируется в evidence (персистентность уже есть); спаны читаются из файла — файл и есть вход.

---

### Task 1: runtimeUrl/traces в реестре, входе, онбординге, UI
`RepoEntry.runtimeUrl/traces`, `RepoInput.runtimeUrl/traces`, Onboarding + диалог UI (поля «URL запущенной апки», «Файл OTel-спанов»). Тесты Registry/Onboarding. Commit.

### Task 2: Actuator-часть
`ActuatorClient(baseUrl)`: `/actuator/mappings` (Spring Boot 3: `details.requestMappingConditions.methods/patterns`) → ENDPOINT conf 0.97 source `actuator:/mappings`; `/actuator/env` → `spring.datasource.url` → STORE_ACCESS, `spring.application.name` → CONTAINER_HINT. Фильтр `/actuator/**`, `/error`. Тест: фейковый актуатор (HttpServer) с каннед-JSON. Commit.

### Task 3: OTel-часть
`OtelSpans.parse(file)`: OTLP-JSON (`resourceSpans[].scopeSpans[].spans[]`) и JSON-lines; SpanKind CLIENT + `http.request.method`+`url.full` (или устар. `http.method`+`http.url`) → OUTGOING_CALL{method, host, path} conf 0.97 source `otel:<traceId>`; PRODUCER + `messaging.system=kafka` + `messaging.destination.name` → PUBLISH; CONSUMER → SUBSCRIBE{group из `messaging.kafka.consumer.group`}; `db.system`+`db.name` → STORE_ACCESS. Дедуп одинаковых фактов. Тест на файл-фикстуру с обоими форматами. Commit.

### Task 4: RuntimeLane + подключение
`RuntimeLane`: applicable = (runtimeUrl отвечает на `/actuator/health` за 2с) или traces-файл существует; extract объединяет обе части (что доступно). В `defaultLanes` первой не ставим — порядок полок на детерминизм не влияет, приоритет задаёт реконсилятор. Тест реконсиляции: runtime-эндпоинт побеждает source в деталях. Commit.

### Task 5: Приёмка + docs
Попытка живого снимка: `java -jar` собранного vets-service (`--spring.cloud.config.enabled=false --eureka.client.enabled=false --management.endpoints.web.exposure.include=mappings,env`), прописать runtimeUrl, прогон → эндпоинт `GET /vets` подтверждён рантаймом (conf растёт, источник actuator в metadata). Если апка не встанет (config-first и т.п.) — приёмка на фейковом актуаторе e2e-тестом, факт фиксируется в docs. CLAUDE.md: runtimeUrl/traces + как снять спаны (OTel javaagent → file exporter). check зелёный, идемпотентность. Commit, merge.

## Self-review (выполнен)
Спека: снимок Actuator (mappings/env), трейсы OTel с отрезолвленными адресами, наивысший приоритет — уже в реконсиляторе. `/actuator` и `/health` фильтруются по правилам репо. Плейсхолдеров нет.
