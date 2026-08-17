# Analyzer Core (подпроект 1) — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ядро arch-analyzer: полки `source` (Java/Spring) и `config`, реконсилятор, расширенный генератор — частичная карта spring-petclinic-microservices проходит `npm run check`.

**Architecture:** Kotlin/Maven-проект `analyzer/` с полками-Lane, пишущими плоские факты в evidence-JSON; детерминированный реконсилятор сливает их в расширенный `tools/api-source/*.json` (v2: containerInfo/calls/subscribes/stores); Node-генератор `tools/gen-model.mjs` штампует из v2-доков + `registry/*.yml` контейнеры, сторы, каналы и рёбра в `model/gen/`; существующий конвейер `gen → validate → check.mjs` — последний рубеж.

**Tech Stack:** Kotlin 2.1 (JVM 17, Maven — системный Gradle 4.4.1 непригоден), JavaParser + symbol-solver, Jackson (kotlin + yaml), JUnit 5; Node 22 + пакет `yaml` (уже в deps) для генератора; тесты генератора — `node:test`.

**Spec:** `docs/superpowers/specs/2026-08-17-arch-analyzer-design.md`

## Global Constraints

- Детерминизм: одинаковый вход → байт-в-байт одинаковый выход; все списки отсортированы, JSON-ключи отсортированы, никаких timestamp'ов в фактах (`extractedAt` — только дата из `--date`, для тестов фиксируется).
- Схема id операций — контракт: `{method}_{path}`, `/`→`_`, `{param}`→`_p_`, схлоп `_+`, обрезка 80 (см. `tools/gen-api.mjs:opId`); Kotlin-порт обязан давать те же id, что JS (общие golden-примеры из CONTRACTS.md).
- Все извлечённые факты: `source` (файл#строка) и `confidence` обязательны; всё сгенерированное — `#inferred`.
- Комментарии, сообщения и docs — на русском (правило репо).
- `model/gen/` руками не правится; `npm run check` после каждой задачи, затрагивающей model/tools, обязан давать 0 ошибок.
- `workspace/` — в `.gitignore`.
- LLM в подпроекте 1 не участвует.

## Формат api-source v2 (референс для всех задач)

Существующие поля v1 (`container`, `source`, `api`, `operations`, `publishes`) не меняются. Новое:

```json
{
  "containerInfo": { "kind": "service", "title": "customers-service",
                     "technology": "Java, Spring Boot", "appName": "customers-service" },
  "subscribes": [ { "channel": "visits", "group": "pc-cg", "payload": "VisitEvent",
                    "source": "src/...#L10", "confidence": 0.85 } ],
  "calls": [ { "method": "GET", "path": "/pets/visits",
               "target": { "urlTemplate": "http://visits-service/pets/visits", "host": "visits-service" },
               "source": "src/...#L30", "confidence": 0.8 } ],
  "stores": [ { "kind": "jdbc", "address": "jdbc:hsqldb:mem:petclinic", "technology": "HSQLDB",
                "access": "readwrite", "entities": "Owner, Pet",
                "source": "src/...#L1", "confidence": 0.9 } ]
}
```

Наличие `containerInfo` = «документ v2, контейнер генерируется». Легаси-доки без него (shop.orders) обрабатываются по-старому.

---

### Task 1: Скелет Maven-проекта analyzer/

**Files:**
- Create: `analyzer/pom.xml`, `analyzer/src/main/kotlin/arch/analyzer/Main.kt`, `analyzer/src/test/kotlin/arch/analyzer/SmokeTest.kt`
- Modify: `.gitignore` (создать, если нет: `node_modules/`, `build/`, `workspace/`, `analyzer/target/`, `dist/`)

**Interfaces:**
- Produces: команда сборки/тестов `mvn -q -f analyzer/pom.xml test`; пакет `arch.analyzer`.

- [ ] **Step 1: pom.xml** — Kotlin 2.1.20, JVM 17, зависимости: `javaparser-symbol-solver-core:3.26.4`, `jackson-module-kotlin:2.18.3`, `jackson-dataformat-yaml:2.18.3`, `junit-jupiter:5.11.4`, `kotlin-test-junit5`; плагины kotlin-maven-plugin, surefire 3.5.2, exec-maven-plugin 3.5.0 (mainClass `arch.analyzer.MainKt`).
- [ ] **Step 2: SmokeTest** — `@Test fun smoke() { assertEquals(4, 2 + 2) }`; Main.kt: `fun main(args: Array<String>) { println("arch-analyzer") }`.
- [ ] **Step 3: Прогнать** `mvn -q -f analyzer/pom.xml test` → BUILD SUCCESS (первый прогон скачает мир — это ок). Проверить `mvn -version` ≥ 3.6; если старше — поставить Gradle wrapper вместо Maven и переписать задачу.
- [ ] **Step 4: Commit** `feat(analyzer): скелет Maven+Kotlin проекта`.

### Task 2: Модель фактов + канонический JSON

**Files:**
- Create: `analyzer/src/main/kotlin/arch/analyzer/core/Fact.kt`, `.../core/Json.kt`
- Test: `analyzer/src/test/kotlin/arch/analyzer/core/FactJsonTest.kt`

**Interfaces:**
- Produces:
  - `enum class FactType { ENDPOINT, OUTGOING_CALL, PUBLISH, SUBSCRIBE, STORE_ACCESS, CONTAINER_HINT, MESSAGE_SCHEMA }`
  - `data class Fact(val type: FactType, val attrs: SortedMap<String, String>, val source: String, val confidence: Double)` + `fun fact(type, source, confidence, vararg attrs: Pair<String,String>)`
  - `data class InputRef(val kind: String, val path: String, val commit: String? = null)`
  - `data class Evidence(val lane: String, val input: InputRef, val facts: List<Fact>)`
  - `object Json { fun write(value: Any): String }` — Jackson: `ORDER_MAP_ENTRIES_BY_KEYS`, `SORT_PROPERTIES_ALPHABETICALLY`, 2-space pretty printer с `\n`, финальный `\n`; `fun <T> read(text: String, type: Class<T>): T`.

- [ ] **Step 1: Тест** — сериализация Evidence с фактами в перемешанном порядке атрибутов даёт канонический текст (атрибуты и ключи отсортированы); `Json.write(x) == Json.write(read(write(x)))`.
- [ ] **Step 2:** Прогнать — FAIL (классов нет).
- [ ] **Step 3:** Реализовать; `Evidence.facts` сортируется при записи: компаратор `(type, attrs.toString(), source)` — функция `Evidence.canonical()`.
- [ ] **Step 4:** Тесты зелёные. **Step 5: Commit** `feat(analyzer): модель фактов и канонический JSON`.

### Task 3: Идентификаторы — порт opId и слаги

**Files:**
- Create: `analyzer/src/main/kotlin/arch/analyzer/core/Ids.kt`
- Test: `analyzer/src/test/kotlin/arch/analyzer/core/IdsTest.kt`

**Interfaces:**
- Produces: `fun opId(method: String, path: String): String` (в точности алгоритм `tools/gen-api.mjs:29`), `fun normPath(path: String): String` (`{x}`→`_p_` — ключ идентичности эндпоинта), `fun slug(s: String): String` (lowercase, `[^a-z0-9]+`→`_`, схлоп/обрезка краёв — для id сторов/каналов).

- [ ] **Step 1: Тест** — golden-примеры из CONTRACTS.md: `POST /api/v1/orders → post_api_v1_orders`, `GET /api/v1/orders/{id} → get_api_v1_orders_p`, `POST /api/v1/orders/{id}/cancel → post_api_v1_orders_p_cancel`; slug: `jdbc:hsqldb:mem:petclinic → jdbc_hsqldb_mem_petclinic`, `order.created → order_created`.
- [ ] **Step 2:** FAIL → **Step 3:** реализовать → **Step 4:** PASS → **Step 5: Commit** `feat(analyzer): порт схемы id операций (контракт)`.

### Task 4: Полка config

**Files:**
- Create: `analyzer/src/main/kotlin/arch/analyzer/core/Lane.kt`, `.../lanes/ConfigLane.kt`
- Test: `analyzer/src/test/kotlin/arch/analyzer/lanes/ConfigLaneTest.kt` + фикстуры в `analyzer/src/test/resources/fixtures/config-app/src/main/resources/application.yml`

**Interfaces:**
- Consumes: `Fact`, `fact(...)` из Task 2.
- Produces:
  - `data class RepoInput(val containerId: String, val repoDir: Path)` (расширится позже)
  - `interface Lane { val name: String; fun applicable(input: RepoInput): Boolean; fun extract(input: RepoInput): List<Fact> }`
  - `class ConfigLane : Lane` (`name = "config"`).

- [ ] **Step 1: Тест.** Фикстура `application.yml`:
```yaml
spring:
  application:
    name: customers-service
  datasource:
    url: jdbc:hsqldb:mem:petclinic
billing:
  url: http://billing-service/api
```
  Ожидания: `CONTAINER_HINT{appName=customers-service}`; `STORE_ACCESS{kind=jdbc, address=jdbc:hsqldb:mem:petclinic, technology=HSQLDB}`; `OUTGOING_CALL{urlTemplate=http://billing-service/api, host=billing-service, prop=billing.url}` (confidence 0.6 — свойство `*.url` это лишь намёк); `source` вида `src/main/resources/application.yml`.
- [ ] **Step 2:** FAIL → **Step 3:** Реализация: найти `src/main/resources/application.yml|.yaml|.properties` (+ `application-*.yml` — только перечислить в атрибуте `profiles`, значения не сливать); Jackson-YAML → плоская Map через рекурсивный обход (`a.b.c=v`); распознать: `spring.application.name`; `spring.datasource.url` (+ `spring.r2dbc.url`) с маппингом префикса jdbc-url → technology (`mysql→MySQL, postgresql→PostgreSQL, hsqldb→HSQLDB, h2→H2`, иначе `JDBC`); `spring.data.redis.host|spring.redis.host` → STORE_ACCESS kind=redis; `spring.kafka.bootstrap-servers` → CONTAINER_HINT{kafka=true}; `spring.rabbitmq.host` → CONTAINER_HINT{rabbit=true}; любые `*.url|*.uri|*.base-url`-свойства со значением `http(s)://…` → OUTGOING_CALL с host из URL.
- [ ] **Step 4:** PASS → **Step 5: Commit** `feat(analyzer): полка config`.

### Task 5: Полка source — роуты Spring MVC

**Files:**
- Create: `analyzer/src/main/kotlin/arch/analyzer/lanes/SourceLane.kt`, `.../lanes/source/JavaProject.kt`, `.../lanes/source/RouteRecognizer.kt`
- Test: `.../lanes/source/RouteRecognizerTest.kt` + фикстура `fixtures/mvc-app/src/main/java/demo/OwnerController.java`

**Interfaces:**
- Consumes: `Lane`, `Fact`, `normPath`.
- Produces:
  - `class JavaProject(repoDir: Path)` — обходит `src/main/java/**/*.java`, парсит JavaParser'ом с `CombinedTypeSolver(ReflectionTypeSolver, JavaParserTypeSolver(src/main/java))`, отдаёт `fun compilationUnits(): List<Pair<Path, CompilationUnit>>` (пути отсортированы), `fun rel(p: Path): String`, `fun line(node): Int`.
  - `interface SourceRecognizer { fun recognize(project: JavaProject): List<Fact> }`
  - `class SourceLane(private val recognizers: List<SourceRecognizer>) : Lane` (`name = "source"`, applicable = есть `src/main/java`).

- [ ] **Step 1: Тест.** Фикстура-контроллер:
```java
@RestController
@RequestMapping("/owners")
class OwnerController {
  @GetMapping("/{ownerId}")
  public OwnerDto findOwner(@PathVariable int ownerId) { return null; }
  @PostMapping
  public ResponseEntity<OwnerDto> create(@RequestBody OwnerDto body,
      @RequestParam(required = false) String source) { return null; }
  @Deprecated
  @GetMapping("/legacy")
  public List<OwnerDto> legacy() { return null; }
}
```
  Ожидания: три `ENDPOINT`-факта: `GET /owners/{ownerId}` (param `ownerId:path:int:required`, response `OwnerDto`), `POST /owners` (request `OwnerDto`, param `source:query:String?`), `GET /owners/legacy` (`deprecated=true`); confidence 0.95; `source` = `src/main/java/demo/OwnerController.java#L<строка метода>`.
- [ ] **Step 2:** FAIL → **Step 3:** Реализация RouteRecognizer: классы с аннотацией `RestController`; префикс из классового `@RequestMapping` (value/path, первая строка массива); методы с `@GetMapping/@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping` и `@RequestMapping(method=…)`; путь = join(prefix, value) с нормализацией слэшей; params из `@PathVariable/@RequestParam/@RequestHeader` (имя: атрибут value/name или имя параметра; тип: простое имя; required: `required=false` или `Optional<>` → `?`); request из `@RequestBody`; response: тип возврата с разворотом `ResponseEntity/Mono/Flux/Optional<T>` → атрибут `response`; `@Deprecated` → `deprecated=true`. Атрибуты params/responses пакуются строкой в стиле CONTRACTS (`name:in:type` через `, `).
- [ ] **Step 4:** PASS → **Step 5: Commit** `feat(analyzer): распознаватель роутов Spring MVC`.

### Task 6: Полка source — исходящие вызовы (Feign, RestTemplate, WebClient)

**Files:**
- Create: `.../lanes/source/FeignRecognizer.kt`, `.../lanes/source/HttpClientRecognizer.kt`
- Test: `.../lanes/source/OutgoingCallsTest.kt` + фикстуры `fixtures/calls-app/.../BillingClient.java`, `VisitsFetcher.java`

**Interfaces:**
- Consumes: `JavaProject`, `SourceRecognizer`, `Fact`.
- Produces: `FeignRecognizer`, `HttpClientRecognizer` — оба `SourceRecognizer`, факты `OUTGOING_CALL` с атрибутами `method`, `path` и/или `urlTemplate`, `host`, `feignName`.

- [ ] **Step 1: Тест.** Feign-фикстура:
```java
@FeignClient(name = "billing", url = "${billing.url}")
interface BillingClient {
  @PostMapping("/api/v1/invoices") Invoice create(@RequestBody InvoiceRq rq);
}
```
  → `OUTGOING_CALL{method=POST, path=/api/v1/invoices, feignName=billing, urlTemplate=${billing.url}}`, confidence 0.9.
  WebClient/RestTemplate-фикстура:
```java
class VisitsFetcher {
  private final WebClient.Builder wcb;
  private final RestTemplate rest;
  Object visits() {
    return wcb.build().get().uri("http://visits-service/pets/visits?petId={id}", 1).retrieve();
  }
  Object owner(int id) { return rest.getForObject("http://customers-service/owners/" + id, Object.class); }
}
```
  → `OUTGOING_CALL{method=GET, urlTemplate=http://visits-service/pets/visits, host=visits-service, path=/pets/visits}` (query отрезается) confidence 0.8; `OUTGOING_CALL{method=GET, urlTemplate=http://customers-service/owners/{…}, host=customers-service, path=/owners/{_}}` (конкатенация → сегмент-плейсхолдер `{_}`) confidence 0.7.
- [ ] **Step 2:** FAIL → **Step 3:** Реализация. Feign: интерфейсы с `@FeignClient`, mapping-методы как в Task 5. HTTP-клиенты: `MethodCallExpr` с именами `getForObject|getForEntity|postForObject|postForEntity|exchange|put|delete|patchForObject` — HTTP-метод из имени (`exchange`: из аргумента `HttpMethod.X`, иначе факт с `method=GET` и confidence 0.5); для WebClient — цепочка `.get()/.post()/...` + ближайший `.uri(...)`. URL-аргумент: строковый литерал или конкатенация (литеральные куски сохраняются, выражения → `{_}`); если начинается с `http(s)://` — выделить host и path; если path-литерал без хоста — только `path`. Резолв типа скоупа через symbol solver, при неудаче — эвристика по имени переменной/типа поля (`restTemplate|webClient|rest|client`), confidence −0.1.
- [ ] **Step 4:** PASS → **Step 5: Commit** `feat(analyzer): распознаватели исходящих вызовов`.

### Task 7: Полка source — Kafka и Spring Data

**Files:**
- Create: `.../lanes/source/KafkaRecognizer.kt`, `.../lanes/source/SpringDataRecognizer.kt`
- Test: `.../lanes/source/KafkaSpringDataTest.kt` + фикстуры `fixtures/kafka-app/...`, `fixtures/data-app/...`

**Interfaces:**
- Consumes: `JavaProject`, `SourceRecognizer`.
- Produces: факты `PUBLISH{channel, schema}`, `SUBSCRIBE{channel, group}`, `STORE_ACCESS{kind=jdbc, address=, access=readwrite, entities}`, `CONTAINER_HINT{scheduled=true}`.

- [ ] **Step 1: Тест.** Kafka-фикстура: `@KafkaListener(topics = "order.created", groupId = "billing-cg") void on(OrderCreated e)` → `SUBSCRIBE{channel=order.created, group=billing-cg, payload=OrderCreated}` 0.9; `kafkaTemplate.send("payment.succeeded", evt)` где `evt` типа `PaymentSucceeded` → `PUBLISH{channel=payment.succeeded, schema=PaymentSucceeded}` 0.85. Spring Data: `interface OwnerRepository extends JpaRepository<Owner, Integer>` → `STORE_ACCESS{kind=jdbc, address=, access=readwrite, entities=Owner}` 0.9 (address пустой — «дефолтный datasource», адрес даст config-полка); `@Scheduled` на методе → `CONTAINER_HINT{scheduled=true}`.
- [ ] **Step 2:** FAIL → **Step 3:** Реализация: топик — только строковый литерал/константа в том же классе (иначе факт с `channel=<unresolved>` не пишется, а идёт в лог полки); схема publish — резолв типа второго аргумента `send` (fallback: имя типа переменной); Spring Data: extendedTypes ∈ {JpaRepository, CrudRepository, PagingAndSortingRepository, ListCrudRepository, Repository}, entity = первый generic-аргумент; все репозитории контейнера сливаются реконсилятором в один сторо-факт (entities объединяются).
- [ ] **Step 4:** PASS → **Step 5: Commit** `feat(analyzer): распознаватели Kafka и Spring Data`.

### Task 8: Реконсилятор

**Files:**
- Create: `analyzer/src/main/kotlin/arch/analyzer/core/Reconciler.kt`, `.../core/ApiSource.kt`
- Test: `analyzer/src/test/kotlin/arch/analyzer/core/ReconcilerTest.kt`

**Interfaces:**
- Consumes: `Evidence`, `Fact`, `opId`, `normPath`, `Json`.
- Produces:
  - `data class ApiSourceDoc(...)` — структура v2 (см. референс выше; поля-объекты как Map/data class, сериализация через `Json`), `source.extractor = "arch-analyzer source+config v1"`.
  - `class Reconciler(private val lanePriority: List<String> = listOf("runtime","openapi","source","bytecode","config","llm"))`
  - `fun reconcile(containerId: String, evidences: List<Evidence>, meta: SourceMeta): Pair<ApiSourceDoc, ReconcileReport>`
  - `data class SourceMeta(val repo: String, val commit: String, val extractedAt: String)`
  - `data class ReconcileReport(val conflicts: List<String>, val lowConfidence: List<String>, val unresolvedCalls: Int)`

Правила (из спеки): группировка по ключу идентичности (`ENDPOINT`: `method + normPath(path)`; `OUTGOING_CALL`: `method + host|feignName + normPath(path)`; `PUBLISH/SUBSCRIBE`: `channel`; `STORE_ACCESS`: `kind + address`, при этом пустой `address` у jdbc-факта склеивается с единственным jdbc-фактом config-полки с адресом); детали — от полки с высшим приоритетом; подтверждение ≥2 полками: `confidence = round2(1 − Π(1−cᵢ))`; конфликт деталей → приоритетная полка + запись в report; `confidence < 0.8` → в `lowConfidence`. Kind контейнера: `worker`, если нет ENDPOINT-фактов и есть SUBSCRIBE или `scheduled=true`, иначе `service`. `api.public = true` (в SP1 ingress неизвестен, честнее считать API доступным; поле уточнит runtime-полка). Все списки в ApiSourceDoc отсортированы (operations по `method+path`, calls по ключу и т.д.).

- [ ] **Step 1: Тесты:** (а) один ENDPOINT из source → операция в doc с теми же полями; (б) STORE_ACCESS без адреса (source) + с адресом (config) → один store с адресом и `entities`, confidence повышен; (в) worker-детект: только SUBSCRIBE → `containerInfo.kind=worker`; (г) детерминизм: `reconcile(x) == reconcile(x.shuffled())` байт-в-байт через `Json.write`.
- [ ] **Step 2:** FAIL → **Step 3:** реализовать → **Step 4:** PASS → **Step 5: Commit** `feat(analyzer): реконсилятор улик`.

### Task 9: Runner — analyze end-to-end

**Files:**
- Modify: `analyzer/src/main/kotlin/arch/analyzer/Main.kt`
- Create: `.../core/Registry.kt` (чтение `registry/repos.yml`), `.../core/Analyze.kt`
- Test: `analyzer/src/test/kotlin/arch/analyzer/AnalyzeE2eTest.kt` (фикстура `fixtures/mvc-app` как целый репозиторий)

**Interfaces:**
- Consumes: все полки, Reconciler, Json.
- Produces:
  - `registry/repos.yml` формат:
```yaml
repos:
  petclinic.customers:
    repo: https://github.com/spring-petclinic/spring-petclinic-microservices
    path: /home/rmazitov/IdeaProjects/spring-petclinic-microservices/spring-petclinic-customers-service
```
  - `fun analyze(archRoot: Path, containerId: String, date: String): AnalyzeResult` — читает repos.yml, собирает `RepoInput`, гоняет применимые полки, пишет `workspace/<id>/evidence.<lane>.json`, `workspace/<id>/reconcile-report.json` и `tools/api-source/<id>.json`; commit репозитория — `git -C <path> rev-parse --short HEAD` (не git-репо → `"local"`).
  - CLI (dev-вход, не продукт): `mvn -q -f analyzer/pom.xml exec:java -Dexec.args="analyze <containerId> --date 2026-08-17"` (`--arch-root` по умолчанию — текущая директория).

- [ ] **Step 1: Тест:** временный archRoot с `registry/repos.yml`, указывающим на фикстуру → после `analyze` существуют `evidence.source.json`, `evidence.config.json`, `tools/api-source/test.app.json`; в doc — операции из фикстуры; повторный вызов не меняет байты файлов.
- [ ] **Step 2:** FAIL → **Step 3:** реализовать → **Step 4:** PASS → **Step 5: Commit** `feat(analyzer): analyze end-to-end`.

### Task 10: Генератор gen-model.mjs (v2 + системы + рёбра)

**Files:**
- Create: `tools/gen-model.mjs`, `tools/gen-model.test.mjs`
- Modify: `tools/gen-api.mjs` (пропуск v2-доков: `if (d.containerInfo) continue`), `package.json` (`"gen": "node tools/gen-api.mjs && node tools/gen-model.mjs"`, `"test:tools": "node --test tools/"`)
- Create: `registry/systems.yml`, `registry/repos.yml` (пока пустые структуры: `systems: []` / `repos: {}`)

**Interfaces:**
- Consumes: `registry/systems.yml` (`systems: [{id, title, kind, description?}]`), `registry/repos.yml`, `tools/api-source/*.json` (v1 и v2).
- Produces:
  - `model/gen/systems/<id>.gen.c4` — каркас системы: `model { <id> = <kind> '<title>' { description '...' } }`;
  - `model/gen/<container>.gen.c4` (для v2): `extend <system> { <name> = <kind> '<title>' { #inferred, technology, link repo, metadata{repo, extracted-at, app-name}, api {...operations как в gen-api...} } }` + рёбра этого контейнера: `-[write]->`/`-[read]->` в сторы (`readwrite` → оба ребра), `-[publish]->` в каналы;
  - `model/gen/_shared.gen.c4` — сторы (`<system>.<storeId>`, kind store, technology, metadata.address), каналы (`<system>.<chId>`, technology 'Kafka topic'), message-схемы из publishes v2, deliver-рёбра `<channel> -[deliver]-> <subscriber> 'group: X'`;
  - id-правила: store id = `db_<slug(имя БД из адреса)>` (адрес пуст → `db_<последний сегмент container>`), channel id = `ch_<slug(topic)>`; система стора/канала = система писателя/продюсера (при нескольких — лексикографически первая; подписчик без продюсера → система подписчика);
  - calls: в SP1 генерятся только с явным `target.container` (появится в подпроекте 3); остальные — счётчик в выводе «N вызовов ждут разрешения (подпроект 3)»;
  - до записи каждого файла — сравнение с существующим содержимым, перезапись только при отличии (чистый git-diff = чистый прогон).

- [ ] **Step 1: Тест (node:test):** фикстурный v2-док + systems.yml с `petclinic` во временной директории → golden-сравнение сгенерированных `.gen.c4` (полный текст в тесте); повторный прогон → файлы не переписаны (mtime/содержимое).
- [ ] **Step 2:** FAIL → **Step 3:** реализовать (структура по образцу gen-api.mjs: массив строк, esc, claim-коллизии) → **Step 4:** `node --test tools/` PASS **и** `npm run check` — 0 ошибок, `git diff model/gen/shop.orders.gen.c4` пуст (легаси не тронуто).
- [ ] **Step 5: Commit** `feat(tools): генератор контейнеров, сторов, каналов и рёбер из api-source v2`.

### Task 11: Пилот petclinic

**Files:**
- Modify: `registry/systems.yml`, `registry/repos.yml`, `CODEOWNERS`
- Create (генерацией, не руками): `tools/api-source/petclinic.*.json`, `model/gen/systems/petclinic.gen.c4`, `model/gen/petclinic.*.gen.c4`, `model/gen/_shared.gen.c4`

**Interfaces:**
- Consumes: analyze (Task 9), gen-model (Task 10).

- [ ] **Step 1: Реестр.** `systems.yml`: `{id: petclinic, kind: system, title: PetClinic, description: 'Демо-ландшафт Spring PetClinic (пилот анализатора)'}`. `repos.yml`: 4 контейнера — `petclinic.customers`, `petclinic.vets`, `petclinic.visits`, `petclinic.gateway` → пути к модулям `/home/rmazitov/IdeaProjects/spring-petclinic-microservices/spring-petclinic-{customers,vets,visits}-service` и `-api-gateway`, repo = `https://github.com/spring-petclinic/spring-petclinic-microservices`. CODEOWNERS: `+ /model/gen/systems/petclinic.gen.c4 @acme/team-petclinic` (и убедиться, что `/model/gen/` покрыт).
- [ ] **Step 2: Прогнать** `analyze` для всех четырёх контейнеров с `--date 2026-08-17`.
- [ ] **Step 3: Проверить руками против кода petclinic:** у customers есть `GET /owners/{ownerId}`, `POST /owners`, пути pets; у vets — `GET /vets`; у visits — `GET /pets/{petId}/visits` (или фактические); у gateway — исходящие вызовы в `unresolvedCalls` (разрешение — подпроект 3); у сервисов — jdbc-store. Расхождения = баги распознавателей → чинить через новый тест-кейс (TDD), не подгонкой ожиданий.
- [ ] **Step 4:** `npm run check` → 0 ошибок (warnings допустимы: C6 «нет потребителей», R9, реконструкции без подтверждения). `npm run dev` глазами: карта petclinic видна.
- [ ] **Step 5: Детерминизм:** повторный `analyze` всех四 + `npm run gen` → `git status` чистый.
- [ ] **Step 6: Commit** `feat: пилот petclinic — карта из анализатора проходит check`.

### Task 12: Документация подпроекта

**Files:**
- Modify: `CLAUDE.md` (раздел «Команды»: analyze/gen-model; раздел «Структура»: analyzer/, registry/, workspace/), `CONTRACTS.md` (раздел «Формат v2»: containerInfo/calls/subscribes/stores)

- [ ] **Step 1:** Дописать документацию (на русском, в стиле файлов).
- [ ] **Step 2:** `npm run check` зелёный. **Step 3: Commit** `docs: analyzer core и формат api-source v2`.

## Self-review (выполнен)

- Покрытие спеки SP1: формат фактов (T2), id-контракт (T3), полка config (T4), полка source: роуты (T5), исходящие (T6), Kafka/Spring Data (T7), реконсилятор с приоритетами/worker-детектом (T8), evidence-персистентность и analyze (T9), генератор: системы/контейнеры/сторы/каналы/рёбра/чистый diff (T10), registry-минимум + CODEOWNERS + пилот (T11), docs (T12). Kotlin-сорцы и разрешение calls — осознанно вне SP1 (спека: подпроекты 3–4); LLM/байткод/runtime — далее.
- Плейсхолдеров нет; типы между задачами сверены (Fact/Lane/RepoInput/ApiSourceDoc сквозные).
- Отклонение от спеки, зафиксированное решением: блок называется `containerInfo` (ключ `container` уже занят id в v1).
