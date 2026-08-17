# Arch-Analyzer: построитель LikeC4-модели из Java/Kotlin-кода

Дата: 2026-08-17. Статус: дизайн утверждён по секциям, ждёт финального ревью.

## Зачем

50–100 репозиториев Java/Kotlin (зоопарк: Spring Boot разных версий, Kafka и Rabbit,
заметная доля Kotlin). Нужен инструмент, который по любому доступному набору входов
(сорцы, JAR из Nexus, OpenAPI-спека, запущенное приложение) детерминированно извлекает
факты и строит/дообогащает модель в этом репозитории по строгой метамодели
(`model/00-spec.c4`), сервис за сервисом, с автосклейкой ранее неопознанных целей.

Критерий успеха: статикой (сорцы + байткод + OpenAPI) собирается >90% карты;
запуск приложения — опциональная полка для добора точности, не обязательное условие.
Приёмочный полигон: `~/IdeaProjects/spring-petclinic-microservices` — полная карта
(gateway, customers, vets, visits + рёбра) без ручных правок, повторный прогон
байт-в-байт идентичен.

## Принципы (зафиксированы)

1. **Детерминизм.** Один и тот же вход → байт-в-байт тот же выход. Все списки
   отсортированы, timestamp'ов внутри фактов нет, LLM-выход кэшируется по хэшу входа.
2. **Факты — детерминированно, LLM — на краях.** LLM: fallback на нераспознанном,
   ревьюер (в отчёт, не в факты), осмысление (имена/summary). Confidence LLM-фактов ≤ 0.7.
3. **JSON → .c4 штампует детерминированный генератор** (расширение `tools/gen-api.mjs`),
   не LLM.
4. **Итеративность и git-diff.** Повторный прогон дообогащает модель; вся дельта видна
   как обычный дифф по `tools/api-source/`, `model/gen/`, `registry/`.
5. **Последний рубеж — существующий конвейер:** `npm run gen` → `likec4 validate` →
   `tools/check.mjs`. Анализатор не имеет права выдать то, что роняет check.
6. **UI — единственный пользовательский интерфейс.** CLI как продукт не делается;
   движок доступен через Ktor REST (+ программный вход для тестов).

## Структура (всё в этой папке)

```
architecture/
  model/, tools/            как сейчас (генератор расширяется)
  analyzer/                 Gradle/Kotlin: core, lanes/*, llm, server (Ktor)
  ui/                       React 19 + Vite + Tailwind 4 + shadcn/ui
  registry/                 repos.yml, aliases.yml, unresolved.json (+ resolutions)
  workspace/                .gitignore: клоны, JAR-ы, evidence-файлы, status.json
  docs/superpowers/specs/   этот документ
```

## Архитектура: конвейер улик

Каждый источник — независимая «полка» (lane), пишущая улики в общий формат.
Полка запускается, только если её вход есть в наличии — набор источников на контейнер
произвольный (от «только сорцы» до «сорцы + бинарь + запущенная апка»).
Детерминированный реконсилятор сливает улики в итоговый `tools/api-source/<container>.json`.

```
git-клон ──► source (JavaParser + Kotlin AA) ─┐
конфиги ──► config (yml/properties, k8s) ─────┤
JAR ──────► bytecode (ASM) ───────────────────┤
JAR ──────► jqassistant (Neo4j + Cypher) ─────┼─► evidence.<lane>.json ─► реконсилятор
спека ────► openapi ──────────────────────────┤        │                      │
запущено ─► runtime (Actuator + OTel) ────────┤   (workspace/, персистентно)  ▼
код+факты ► llm (fallback) ───────────────────┘              api-source/<container>.json
                                                             + registry/* + отчёты
                                                                      │
                                              node tools/gen-api.mjs (расширенный)
                                                                      ▼
                                              model/gen/*.gen.c4 → validate → check.mjs
```

### Формат улик

`workspace/<container>/evidence.<lane>.json`:

```json
{
  "lane": "source-spring",
  "input": { "kind": "git", "commit": "a1b2c3d" },
  "facts": [
    { "type": "endpoint", "method": "POST", "path": "/api/v1/orders",
      "details": { "request": "CreateOrderRequest", "auth": "oauth2:orders.write" },
      "source": "src/main/java/.../OrderController.java#L42", "confidence": 0.96 }
  ]
}
```

Семь типов фактов — ровно под метамодель:

| type            | → в модели                        | ключ идентичности                          |
|-----------------|-----------------------------------|--------------------------------------------|
| `endpoint`      | `operation` (+`api`)              | method + нормализованный path (`{x}`→`_p_`)|
| `outgoingCall`  | ребро `call`                      | method + path + host/serviceName           |
| `publish`       | `publish` + `message`             | имя топика/exchange                        |
| `subscribe`     | `deliver`                         | имя топика + group                         |
| `storeAccess`   | `read`/`write` + `store`          | нормализованный jdbc-url/host/bucket       |
| `containerHint` | kind, technology, догадка имени   | container                                  |
| `messageSchema` | детали `message`                  | топик + тип payload                        |

Обязательные поля: `type`, `source` (файл#строка, `jar!класс#метод`,
`actuator:/mappings`, `otel:trace`), `confidence`. Цель `outgoingCall` — то, что реально
удалось узнать: `{serviceName}` (Feign name) / `{urlTemplate}` / только `{path}`.
Разрешение цели — работа реестра, полка честно фиксирует незнание.

Схема id операций — существующий контракт (`{method}_{path}`, `/`→`_`, `{param}`→`_p_`),
не меняется.

### Реконсилятор

Чистая функция `(все evidence-файлы контейнера) → api-source JSON + reconcile-report.json`:

1. Группировка фактов по ключу идентичности.
2. Совпадение из нескольких полок → confidence повышается, детали — у полки с высшим
   приоритетом (`runtime > openapi > source > bytecode > config > llm`), `source` —
   объединение ссылок.
3. Противоречие деталей при одной идентичности → приоритетная полка побеждает,
   конфликт — в отчёт.
4. Одиночный факт с confidence < 0.8 → проходит с пометкой (далее warning в check.mjs).

Evidence-файлы персистентны: новая полка добавляет улики, старые не пропадают
(перезаписывается только улика той же полки при её повторном прогоне). Так работает
дообогащение: «удалось запустить сервис → перезапустил анализ → модель доросла,
дифф в git показывает ровно дельту».

## Полки

Интерфейс: `Lane { applicable(input): Boolean; extract(input): List<Fact> }`.

1. **`source`** — JavaParser с symbol-resolution + Kotlin Analysis API.
   Роуты: `@RestController`/`@*Mapping`, WebFlux functional (confidence ниже), Ktor DSL.
   Исходящие: `@FeignClient`, call-sites `RestTemplate`/`WebClient`/`RestClient`/OkHttp
   (URL-литерал → 0.9; конкатенация/плейсхолдер → шаблон с пониженным confidence).
   Kafka/Rabbit: `@KafkaListener`, `KafkaTemplate.send`, `@RabbitListener`,
   `RabbitTemplate.convertAndSend`, Spring Cloud Stream. Сторы: Spring Data
   (repository → entity → таблица; save/delete=write, find=read), `JdbcTemplate`,
   Redis/S3-клиенты. Хинты: пустые mappings + listener/`@Scheduled` → `worker`;
   `@Deprecated` на контроллере → тег.
2. **`config`** — `application*.yml/properties` по профилям: адреса
   datasource/redis/kafka/rabbit, base-URL'ы и `*.url`-свойства, биндинги Stream,
   резолв `${...}` с дефолтами; k8s/helm-манифесты, если в репо.
3. **`bytecode`** — ASM по JAR: аннотации и строковые константы; закрывает места, где
   Kotlin-сорцы врут (inline, корутины), и случаи «сорцов нет, бинарь есть».
4. **`jqassistant`** — скан JAR в Neo4j, фиксированный набор Cypher-запросов → факты.
   Второе мнение; Neo4j доступен для ручных раскопок.
5. **`openapi`** — готовые спеки (committed / снятые с `/v3/api-docs`) → endpoint-факты
   с высоким confidence.
6. **`runtime`** — снимок Actuator (`/mappings`, `/env`, `/configprops`,
   `/httpexchanges`) + трейсы OpenTelemetry javaagent (реальные исходящие HTTP/Kafka/
   Rabbit/JDBC/Redis с отрезолвленными адресами) → факты с наивысшим приоритетом.
   Вспомогательно: springdoc `/v3/api-docs`, Kafka AdminClient/schema registry;
   Arthas — ручной инструмент, вне конвейера.
7. **`llm`** — три режима, confidence ≤ 0.7, кэш по хэшу входа:
   - *fallback*: только «точки внимания» — места, где полки увидели коммуникацию,
     но не смогли извлечь (импорт http-клиента без вытащенного URL, самописный
     диспетчер). Сниппет + жёсткая JSON-схема ответа, temp 0.1;
   - *ревьюер*: после реконсиляции получает факты + дерево файлов, ищет пропуски —
     выход в отчёт, не в факты;
   - *осмысление*: title/description контейнеров, summary эндпоинтов, гипотезы имён
     для реестра; summary репозитория — nice-to-have.
   Маршрутизация: корпоративный Qwen3.5-397B — дефолт; локальный Qwen3-27B — батчи и
   чувствительный код.

Всё нераспознанное, но похожее на коммуникацию (gRPC/GraphQL/NATS-зависимости в
build-файлах и т.п.) → `unrecognized-tech.json` — бэклог новых распознавателей.

## Генерация модели

Расширение принципа: всё добытое анализатором — в `model/gen/`, поголовно `#inferred`;
знание человека — в `model/overrides.c4` через `extend`; регенерация overrides не трогает.

```
model/
  10-shop.c4               существующая рукопись (остаётся как есть)
  gen/_systems.gen.c4      каркас систем из registry/systems.yml
  gen/<container>.gen.c4   контейнер #inferred (через extend системы) + api/operations
                           + ВСЕ исходящие рёбра этого контейнера
  gen/_stores.gen.c4       сторы и каналы — общие узлы
  gen/unknown/*.gen.c4     stub-заглушки нераспознанных целей
  overrides.c4             руками, не перезаписывается
```

Решения:

- **системы — тоже данные**: `registry/systems.yml`
  (`id, title, kind: system|orgSystem|externalSystem, description`), генератор штампует
  каркас в `gen/_systems.gen.c4`. Создание/выбор системы — обязательный шаг в UI при
  добавлении контейнера; владельцы остаются только в CODEOWNERS (UI дописывает строку
  при создании системы). Уже существующие рукописные системы (`10-shop.c4`) не
  переезжают — новые системы заводятся через yml;
- контейнер генерируется внутри своей системы; принадлежность решает человек **один раз
  при онбординге** в `registry/repos.yml` (id содержит систему: `shop.orders`).
  Это осознанно ранний выбор: id иерархичен и не переименовывается, перенос контейнера
  между системами дёшев только пока на его id нет рукописных ссылок/overrides;
- **сторы — общие узлы** (идентичность = нормализованный адрес): два репо пишут в один
  jdbc-url → две `write`-стрелки в один узел → существующий инвариант «один писатель»
  превращается в ландшафтный детектор shared database;
- **каналы — общие узлы**: `publish` и `deliver` из разных прогонов сходятся сами;
- рёбра генерируются от источника: цель = `operation`, если цель проанализирована;
  контейнер, если у цели нет api; stub, если не распознана. Перегенерация одного
  сервиса не трогает чужие файлы;
- формат `tools/api-source/*.json` расширяется блоками `calls`, `subscribes`, `stores`,
  `container` (kind/technology/title) — как и анонсировал CONTRACTS.md;
- послабление check.mjs: `#inferred`-контейнер без записи в CODEOWNERS — warning,
  не error (error вернётся, когда команда заберёт файл).

## Реестр разрешения (`registry/`)

- **`systems.yml`** — реестр систем (L0): `id, title, kind, description`. Источник
  для `gen/_systems.gen.c4`; владельцы — только в CODEOWNERS.
- **`repos.yml`** — инвентарь: `container-id → { repo, доступные источники, статус,
  последний проанализированный commit }`. Единственное место привязки репо к id.
- **`aliases.yml`** — `host / k8s-svc / base-url / feign-name → container-id`.
  Пополняется автоматически (`spring.application.name`, k8s svc, server.port) и руками.
  Главный механизм склейки.
- **`unresolved.json`** — журнал нераспознанных целей:

```json
{
  "stubId": "unknown.billing_svc",
  "signature": { "hosts": ["billing.svc"], "feignNames": [], "urlTemplates": [] },
  "observedEndpoints": [ { "method": "POST", "path": "/api/v1/invoices" } ],
  "callers": [ { "container": "shop.orders", "source": "src/...#L18" } ],
  "hypotheses": [ { "name": "billing", "by": "llm", "confidence": 0.6 } ],
  "status": "open"
}
```

Жизненный цикл:

1. Цель не разрешилась через `aliases.yml` → запись в `unresolved.json` + stub-контейнер
   в `model/gen/unknown/` (система `unknown`, `#stub`, наблюдённые эндпоинты — operations
   с `#stub`). Знаем эндпоинты, не знаем имя — и это зафиксировано.
2. Анализ нового репо → его входящие эндпоинты скорятся против всех открытых stub'ов
   (совпадение нормализованных method+path + алиасов). Один кандидат с высоким скором →
   автосклейка; иначе — в очередь ручного триажа (UI).
3. Склейка = append-only запись `stubId → container-id` (в духе `verified.json`) +
   перегенерация: рёбра вызывающих ведут в реальные operations, stub исчезает.
4. Ручное решение равноправно: «stub X = сервис Y» / «это external» — та же запись,
   регенерация уважает её вечно. External → `externalSystem` c `#stub`.
5. Послабление check.mjs: правило «границу системы пересекают только `#public`» не
   применяется к целям с `#stub`; после склейки включается автоматически.

## Оркестрация и UI

Движок — в `analyzer/` (Kotlin), поверх — Ktor-сервер с REST: инвентарь, привязка
источников к контейнеру, запуск анализа, статусы, триаж реестра, диффы. Прогон
асинхронный: полки пишут `workspace/<container>/status.json`, UI его читает.
После прогона движок вычисляет git-дифф по `tools/api-source/`, `model/gen/`,
`registry/` — UI показывает «что изменилось этим прогоном». Коммитит пользователь.

**UI — единственный интерфейс.** Стек: React 19 + Vite + Tailwind 4 + shadcn/ui
(style `new-york`, baseColor `neutral`, CSS-переменные), TanStack Table + TanStack Query,
react-router, radix-ui, lucide. Экраны:

1. дашборд: все контейнеры из `repos.yml`, статусы, покрытие полками, конфликты;
2. карточка контейнера: при создании — выбор/создание системы (пишет в `systems.yml`
   и CODEOWNERS); прикрепить источники (путь к сорцам / JAR / OpenAPI / URL запущенной
   апки) → «провести анализ» → живой статус полок → отчёт + дифф прогона;
3. триаж unresolved: очередь, кандидаты на склейку со скором, кнопки
   merge / external / отложить;
4. бэклог `unrecognized-tech`.

Просмотр архитектуры — встроенный LikeC4 (webcomponent / iframe на `npm run dev`),
свой рендер графа не пишется.

## Обработка ошибок

Упавшая полка не роняет прогон — failed в отчёте, реконсиляция по остальным уликам.
Нераспарсенный файл — пропуск с записью в отчёт, никогда молча. LLM недоступна —
полка скипается. Коллизия id операций — ошибка генератора (как в CONTRACTS.md),
не молчаливая склейка.

## Тестирование

- golden-тесты по-распознавательно: фикстурный мини-проект → ожидаемые факты;
- детерминизм: каждый e2e дважды, пустой дифф обязателен;
- приёмка: spring-petclinic-microservices → полная карта без ручных правок;
- финальный рубеж — `npm run check` (validate + инварианты).

## Порядок работ (каждый подпроект — свой цикл спека → план → реализация)

1. **Ядро**: скелет `analyzer/` + формат фактов + полки `source` (Spring MVC/Feign/Kafka)
   и `config` + реконсилятор + расширение генератора → частичная карта petclinic.
2. **Минимальный UI**: Ktor REST + дашборд, карточка контейнера, запуск анализа,
   просмотр диффа. Дальше UI растёт с каждым подпроектом.
3. **Реестр**: stubs/unknown, алиасы, автосклейка, послабления check.mjs, экран триажа.
4. **Полки `bytecode` + `jqassistant`.**
5. **Полка `llm`** (fallback / ревьюер / имена) + гипотезы в триаже.
6. **Полка `runtime`** (Actuator + OTel javaagent) + прикрепление URL запущенной апки в UI.

## Вне скоупа

Классы/компоненты внутри сервисов, полные схемы в графе, хосты/реплики/зоны,
`/health`-эндпоинты, версионирование схем (schema registry), свой рендер диаграмм,
замена трейсинга (runtime-полка использует его, а не конкурирует с ним).
