# Analyzer LLM (подпроект 5) — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Полка `llm`: fallback на точках внимания (полки видели коммуникацию, но не извлекли), осмысление (title/summary как факты), ревьюер (в отчёт), гипотезы имён для триажа. Confidence ≤ 0.7, кэш по хэшу входа, без конфига полка выключена.

**Architecture:** `registry/llm.yml` (`baseUrl`, `model`, `apiKey`/env `LLM_API_KEY`) — любой OpenAI-совместимый endpoint (корп. Qwen3.5-397B, LM Studio с 27B). `LlmClient` — интерфейс; `OpenAiClient` — тонкий java.net.http; вся логика (точки внимания, промпты, JSON-парсинг, кэш `workspace/_llm-cache/<sha256>.json`, преобразование в факты) тестируется с фейковым клиентом. Осмысление ложится в существующий реконсилятор: ENDPOINT{summary}/CONTAINER_HINT{title,description} с conf 0.6 просто заполняют пустые поля. Ревьюер — шаг Analyze после реконсиляции, выход в `reconcile-report.llmReview`. Гипотезы — endpoint сервера, кэшируются так же, UI показывает в триаже.

**Tech Stack:** java.net.http, существующий стек; e2e — фейковый OpenAI-сервер на node (тестирует настоящий HTTP-клиент).

**Spec:** `docs/superpowers/specs/2026-08-17-arch-analyzer-design.md` (полка 7)

## Global Constraints

- temp 0.1, строгий JSON-ответ (невалидный → одна повторная попытка → скип с записью в отчёт, фактов не выдумываем).
- Все LLM-факты: confidence ≤ 0.7, `source` = файл#строка точки внимания (не «llm»).
- Кэш делает повторный прогон детерминированным и бесплатным; ключ — sha256(model + промпт).
- Нет `registry/llm.yml` → полки и ревьюера нет, всё работает как раньше.

---

### Task 1: Конфиг, клиент, кэш
`LlmConfig` (чтение llm.yml + env), `interface LlmClient { fun complete(system: String, user: String): String }`, `OpenAiClient(config)` (POST /chat/completions, temp 0.1), `CachedLlm(delegate, cacheDir)` — sha256-кэш на диске. Тесты: конфиг-парсинг; кэш-хит не зовёт делегата (фейк со счётчиком). Commit.

### Task 2: LlmLane — fallback на точках внимания
Точка внимания: .java-файл, где есть импорт http-клиента (`RestTemplate|WebClient|RestClient|OkHttpClient|HttpClient`) или `KafkaTemplate`, но ни одного факта OUTGOING_CALL/PUBLISH с `source` из этого файла (вход — evidence других полок, полка получает их через `LaneContext`? — нет: LlmLane читает готовые evidence-файлы из workspace, интерфейс Lane не меняем, путь к workspace — в конструкторе). Промпт: сниппет файла + жёсткая JSON-схема `{calls:[{method,path,urlTemplate?,host?,line}],publishes:[{channel,schema?,line}]}`. Ответ → факты conf 0.65. Тесты с фейковым клиентом на фикстуре calls-app (файл с клиентом, который HttpClientRecognizer не осилил — добавить фикстуру `TrickyCaller.java` с URL из поля). Commit.

### Task 3: Осмысление — summary и title
Тот же LlmLane, второй запрос (если включён режим в llm.yml `enrich: true`): список эндпоинтов контейнера → `{summaries:[{method,path,summary}], title, description}` → факты ENDPOINT{summary}/CONTAINER_HINT{title,description} conf 0.6. Тест реконсилятора: summary заполняет пустое поле операции source-полки, title не перетирает appName (детали от приоритетной полки). Commit.

### Task 4: Ревьюер
`LlmReviewer(client)`: вход — итоговый doc + список файлов репо; выход `List<String>` подозрений («в файле X есть Y, в модели нет»). Analyze: если LLM настроен — зовёт, пишет `reconcile-report.llmReview`. Формат ReconcileReport расширяется полем `llmReview: List<String> = []`. Тест с фейком. Commit.

### Task 5: Гипотезы в триаже
`GET /api/unresolved/{stubId}/hypotheses` → LLM: сигнатура + эндпоинты + список известных контейнеров → `{hypotheses:[{name, container?, confidence}]}` (кэш). UI: кнопка «Гипотезы LLM» в карточке stub'а, показ списка, клик по container-гипотезе подставляет её в Select. Тест сервера с фейком (инъекция клиента в buildApp через параметр `llm: LlmClient?`). Commit.

### Task 6: Приёмка + docs
Фейковый OpenAI-сервер (node, каннед-ответы) → настоящий OpenAiClient ходит по HTTP: e2e тест полного цикла analyze с llm.yml, указывающим на фейк; повторный прогон — из кэша (фейк выключен, результат тот же). Без llm.yml — прогон petclinic не меняется байт-в-байт. CLAUDE.md: как настроить llm.yml на корп. Qwen или LM Studio. Commit, merge.

## Self-review (выполнен)
Спека: fallback (T2), ревьюер (T4), осмысление (T3), гипотезы имён (T5), маршрутизация моделей — конфигом (baseUrl/model меняются на корп./локальную). Детерминизм — кэш (T1, e2e в T6). Плейсхолдеров нет.
