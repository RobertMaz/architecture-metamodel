# План: усиление статического извлечения

По итогам deep research и четырёх пилотов 2026-08-19. Полный отчёт с метриками и
ссылками: https://claude.ai/code/artifact/e398a024-6de9-414e-a727-2d9b4d59a4c6 (приватный артефакт).

**Пилотный код сохранён в `workspace/_pilots/`** (gitignore): `springwolf-exp/` — сканер + `scan-jar.sh` + демо-жертва + примеры AsyncAPI; `openrewrite-exp/` — экстрактор (`extractor/src/main/java/pilot/Extractor.java`) + Kotlin-корпус с 8 ловушками + baseline-регулярки; `noir-exp/` — бинарь noir 1.3.0 + `noir-adapter.sh` + JSON-выхлопы по petclinic. Внедрение каждой полки стартует от этого кода, не с нуля.

**Ограничение, определяющее все решения:** запустить приложение можно не всегда, бинарь и сорцы доступны всегда. Ядро — статика (source +
bytecode); всё, что требует поднятого приложения, — вторая очередь, ляжет поверх как верификация, когда появятся трейсы.

**Главный вывод ресёрча:** готового «Spring-код → LikeC4» не существует; академическое поле (microservice architecture reconstruction,
сравнение 9 инструментов — arXiv 2412.08352) подтверждает: точность даёт только ансамбль источников. Наша схема «полки → реконсилятор» и
есть state of the art; на petclinic наш анализатор уже сильнее лучшего академического инструмента (Code2DFD: совпало 4 ребра из 15 наших,
все содержательные он пропустил).

## Очередь работ

### 1. Эвристики config/discovery в config-полку (дёшево, делаем первым)

Украдено у Code2DFD. Сейчас рёбра в config-server и discovery появляются только из runtime-улик (на проде их не будет); статически их видно
у всех сервисов:

- `spring.config.import: configserver:...` в yml (+ зависимость `spring-cloud-starter-config`) → ребро `сервис → config-server`;
- eureka-client в pom/gradle или `@EnableDiscoveryClient` → ребро `сервис → discovery`;
- бонус: `spring.cloud.config.server.git.uri` → внешний узел config-репозитория.

На petclinic это ≈ +10 рёбер. Confidence уровня config-полки.

### 2. Полка `springwolf` — Kafka/AMQP consumers из бинаря

Пилот подтвердил: Springwolf 2.6.0 standalone снимает AsyncAPI 3.1 с чужого fat-jar **без запуска** (BOOT-INF/classes на classpath
сканера), <1 c на сервис. Consumers — с groupId, JSON-схемой payload и резолвом `${...}`-топиков из application.yml жертвы. Один сканер
кроет и `@KafkaListener`, и `@RabbitListener` (наш стек — Kafka+Rabbit).

Реализация:

- сканер — отдельный JVM-модуль, собирается один раз (внимание: пинить `kafka-clients` с Maven Central, springwolf тянет Confluent-сборку);
- полка по образцу опциональной jqassistant: `applicable()` = есть jar + собран сканер; дергает процесс «unzip jar → скан → AsyncAPI JSON»,
  парсит в факты: канал, ребро `deliver` (канал → контейнер), groupId/payload-схема в атрибуты; confidence ~0.9 (выше bytecode-эвристик);
- нерезолвнутый плейсхолдер топика (`$_..._`) — отдельный факт «топик из внешнего конфига», в триаж;
- ASM остаётся источником `publish` и fallback'ом consumers: полка упала (Boot 2/javax-жертва, экзотический payload) — реконсилятор живёт на
  ASM-фактах.

**Скоуп честно ограничен:** producers через `KafkaTemplate.send()` Springwolf не видит (подтверждено экспериментально — только аннотационный
скан), это не блокер, а разграничение ролей.

### 3. Перевод source-полки на OpenRewrite LST (пилот пройден 2026-08-19 — берём)

`rewrite-java` + `rewrite-kotlin` 8.56.1 (Apache-2.0) — единый типизированный AST для обоих языков; один и тот же визитор работает по Java и Kotlin. Итог пилота (8 Kotlin-ловушек + реальные WebClient-вызовы petclinic, 14 вызовов): **LST полностью резолвнул 11/14, регулярки — 2/14** (плюс false positive и дубль из комментария у регулярок). Резолв: константы из чужих классов, конкатенации, string templates, склейка base у `WebClient.create(...)`, `@FeignClient(name+path)` с операциями; `@Value`-плейсхолдеры идентифицируются — доклеиваются из config-полки; динамика (DiscoveryClient) — честный плейсхолдер. Скорость: ~1.3 с на Java-сервис, Kotlin +1.3 с старт компилятора, дальше ~15 мс/файл. Без classpath деградирует (не падает) до уровня регулярок.

Продовая версия — 1–2 недели:
- подготовка classpath per-repo (`dependency:copy-dependencies` — шаг переиспользуется из bytecode-полки);
- подстановка `@Value` из config-полки; склейка WebClient-полей через инициализаторы бинов;
- маппинг в evidence с confidence (typed выше, чем untyped);
- **отдельный процесс**: rewrite-kotlin сидит на kotlin-compiler-embeddable 1.9.25, анализатор — на Kotlin 2.1.20 (конфликт stdlib в одном classpath) + RSS до ~900 МБ;
- regex-экстракторы остаются fallback'ом, где classpath не собрался.

### 4. Микрополка OWASP Noir — второе мнение для REST-in (пилот пройден 2026-08-19 — берём)

Noir 1.3.0, один бинарь ~50 МБ без зависимостей. Итог пилота на petclinic: **15/15 эндпоинтов совпали с моделью посимвольно** (метод+путь, включая литеральную `*` в `/owners/*/pets/{petId}`), 0 мусора; склейка class-level `@RequestMapping` корректная; Kotlin Spring работает (проверено на мини-контроллере, tech `kotlin_spring`); ~0.07 с на сервис; в JSON есть файл+строка — готовый source для evidence. Framework-эндпоинты (Eureka, actuator) не видит — они остаются за runtime-полкой, роли не пересекаются.

Внедрение: адаптер по контракту jqassistant (`ENDPOINT|method=...|path=...|file:line|0.8`) — прототип ~20 строк bash+jq готов в пилоте; включить `--strict` (exit 2 при пропущенных файлах). Роль в реконсиляторе: совпадение с source-полкой поднимает confidence эндпоинта.

### 5. Усиление Kafka-producer детекта в ASM

После полки springwolf: резолв имён топиков через конфиги/профили (`${topic.orders}`), нормализация суффиксов окружений, `groupId`,
распознавание DLQ/retry-топиков. Весь SOTA матчит топики literal string match — здесь мы обгоняем поле. `StreamBridge.send` добавить к
`KafkaTemplate.send`.

### 6. Kotlin-сорцы: закрыть дыру source-полки (требование: Java/Kotlin fully)

Факт из кода: SourceLane сканирует только `src/main/java` и `*.java` (`JavaProject.kt:25,38`), LLM-полка так же. Kotlin-контроллеры сейчас видны **только** через bytecode-аннотации из jar; Kotlin-producers (`kafkaTemplate.send`) не видны вообще. Закрывается п. 3 (OpenRewrite LST — единый визитор Java+Kotlin) + Noir как второе мнение (kotlin_spring подтверждён пилотом). Приёмка: добавить в тестовый ландшафт Kotlin-сервис (контроллеры + Kafka listener + producer) и держать инвариант «паритет фактов Java/Kotlin».

### 7. PlaceholderResolver: единый резолв `${...}` по всем конфигам

Факт из кода: резолва плейсхолдеров нет нигде; ConfigLane читает только `src/main/resources/application(-profile).(yml|yaml|properties)`, без `bootstrap.*`, без рекурсии (`ConfigLane.kt:29,36`). Сделать:
- собирать рекурсивно все `application*.yml/yaml/properties` + `bootstrap.*`;
- цепочка источников значения: application.yml → профили → bootstrap → helm `values*.yaml`/charts → ansible vars (если в репо или путь задан в repos.yml) → не нашли — факт «значение во внешнем конфиге», в триаж;
- резолвер один на всех потребителей: топики Kafka (п. 5), `@Value` в LST-полке (п. 3), `*.url`-свойства config-полки.

### 8. Клиентские api-библиотеки: drill down цепочки вызовов

Факт из кода: pom/gradle не парсятся вообще, маппинга «артефакт → контейнер» нет. Сделать:
- реестр `registry/clientlibs.yml`: `groupId:artifactId → container-id` цели (пополняется как aliases);
- парсер зависимостей `pom.xml`/`build.gradle(.kts)` → факт «использует клиента X», conf 0.6;
- **профиль клиентской либы**: её jar прогоняется теми же ASM/LST один раз (Feign-интерфейсы, RestTemplate/WebClient, URL-константы) → список операций целевого сервиса, кэш в workspace/;
- склейка: зависимость + профиль → call-рёбра в конкретные operations (conf 0.7); с появлением bytecode call-sites (п. 9) — подтверждение, какие методы либы реально зовутся.

### 9. Bytecode call-sites: снять SKIP_CODE в ASM

Факт из кода: `ClassReader` читает со `SKIP_CODE` (`BytecodeLane.kt:53`) — только аннотации, поэтому producers из байткода не видны, call-sites тоже. Сделать MethodVisitor: `invoke*` на `KafkaTemplate/RabbitTemplate/StreamBridge.send`, `RestTemplate/WebClient/RestClient` + LDC-константы рядом (простой стековый резолв аргументов). Закрывает: Kotlin-producers до перевода на LST, вызовы методов клиентских либ для п. 8, усиление п. 5.

### 10. Модель в отдельной приватной папке (sensitive-ландшафт не в этом репо)

Разделить «движок» (analyzer/, tools/, ui/) и «данные» (model/, registry/, tools/api-source/, workspace/, CODEOWNERS): корень данных — параметр (env/config, по умолчанию — текущий репо, обратная совместимость); рабочая арха — отдельный приватный git-репо с тем же layout, движок подключается как зависимость/submodule/просто соседняя папка. CI-инварианты (`npm run check`) едут вместе с данными.

### Попутные фиксы (найдены разведкой 2026-08-19)

- `technology 'Kafka topic'` захардкожен у каналов (`gen-model.mjs:463`) — брать из хинтов kafka/rabbit;
- `@RabbitListener`/`RabbitTemplate` не детектится ни одной полкой (закроется springwolf п. 2 + ASM п. 9);
- `FactType.MESSAGE_SCHEMA` объявлен, но нигде не производится и не потребляется — задействовать (схемы из springwolf) или убрать;
- топик у `kafkaTemplate.send` берётся только из строкового литерала (`KafkaRecognizer.kt:43`) — константы/`${...}` закрываются пп. 3+7.

### Резерв (триггеры зафиксированы, до триггера не трогаем)

- **Joern** — если пилот OpenRewrite упрётся в межпроцедурный dataflow (URL собирается через несколько методов): CPG с готовым
  `reachableBy`, но зрелость kotlin2cpg надо пилотировать отдельно.
- **SootUp** — когда ASM перестанет хватать для call graph (CHA/RTA из коробки; склейку Spring DI писать самим в любом случае).

### Отдельные треки (не в этой очереди)

- **LikeC4 Model Builder API** (`@likec4/core/builder`) — типобезопасная сборка модели + эмит `.c4` вместо текстогенерации. Трогаем, когда
  заболит генератор.
- **Трейсы/eBPF** — когда появится доступ: OTel-спаны (полка traces уже есть), Caretta/Coroot (L4-карта), Otterize Kafka Watcher
  (topic-level карта из логов ACL) — роль probe-верификации через `verify.mjs --against=probe`.

## Решения по проанализированным инструментам (чтобы не вспоминать)

| Инструмент                                                    | Решение                                                 | Почему                                                                                                                        |
|---------------------------------------------------------------|---------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| **Springwolf** (standalone)                                   | **берём** — полка consumers                             | пилот: работает по чужому jar без запуска; producers не видит — остаются за ASM                                               |
| **Code2DFD**                                                  | **донор эвристик** (config/discovery/git.uri), не полка | пилот: пропустил все содержательные рёбра petclinic (роуты gateway новой схемы, WebClient, БД); хрупкий CLI, устаревшие regex |
| **OpenRewrite LST**                                           | **берём** — перевод source-полки (п. 3)                 | пилот: резолв URL 11/14 против 2/14 у регулярок, единый визитор Java+Kotlin, типы отсекают false positives; отдельным процессом (kotlin-embeddable 1.9.25 vs наш 2.1.20) |
| **OWASP Noir**                                                | **берём** — микрополка REST-in (п. 4)                   | пилот: 15/15 совпадений с моделью на petclinic, Kotlin ок, 0.07 с/сервис; только входящие — дополняет, не заменяет            |
| Joern                                                         | резерв                                                  | готовый dataflow, но kotlin2cpg непроверен; отдельный процесс                                                                 |
| SootUp                                                        | резерв                                                  | call graph по jar; Spring DI рвёт граф у любого алгоритма                                                                     |
| CodeQL                                                        | **нет**                                                 | лицензия: бесплатно нельзя анализировать закрытый корп. код (нужен GHAS)                                                      |
| ArchGuard / Chapi                                             | нет (референс кода)                                     | ближайший живой аналог, но без Kafka/AMQP и WebClient; выход — своя модель под свой бэкенд                                    |
| jQAssistant                                                   | остаётся как есть (опц. адаптер)                        | Spring-плагин — внутримодульные факты; REST-out/Kafka-правил нет; для Kotlin шумит синтетикой (inline/suspend/synthetic)      |
| Spring Modulith                                               | нет                                                     | модули одного приложения, кросс-сервисное не извлекает                                                                        |
| Structurizr ComponentFinder                                   | нет                                                     | компонентный уровень внутри сервиса — мы его сознательно не моделируем                                                        |
| Structurizr DSL → LikeC4 конвертер                            | не существует                                           | —                                                                                                                             |
| springdoc-openapi (maven-plugin)                              | нет                                                     | поднимает Spring-контекст = «запуск», отпадает по ограничению                                                                 |
| Spoon, JavaParser                                             | нет                                                     | нет Kotlin                                                                                                                    |
| Kotlin Analysis API / KSP / detekt                            | нет (пока)                                              | только Kotlin-сторона; standalone-API нестабилен; KSP требует встраивания в сборку каждого сервиса                            |
| tree-sitter, SCIP, Kythe, Glean                               | нет                                                     | без резолва типов — не глубже регулярок; Kotlin незрелый/закрытый                                                             |
| WALA                                                          | нет                                                     | тяжёлый, Kotlin не подтверждён                                                                                                |
| IntelliJ headless / Qodana                                    | нет                                                     | лучший резолв, но IntelliJ Platform SDK + коммерция — оверинжиниринг                                                          |
| cloudhubs (RAD, Prophet, MicroGraal), MicroDepGraph, MicroART | нет                                                     | заброшены/слабые метрики/нестабильны; идеи уже учтены                                                                         |
| LLM-тулзы (Swark, CodeBoarding, ArchAgent)                    | нет                                                     | выход — картинка/суммаризация, не факт-граф; наша LLM-роль уже определена (registry/llm.yml, confidence ≤ 0.7)                |
| CAST Imaging, vFunction                                       | нет                                                     | коммерческие чёрные ящики, экспорт под свою схему не подтверждён                                                              |
| SonarQube и коммерческие SAST (Checkmarx/Fortify)             | нет                                                     | dataflow есть внутри, наружу — только findings, граф не отдают                                                                |
| DAST (ZAP, Burp), IAST (Contrast)                             | нет                                                     | требуют запущенного приложения; DAST видит только вход                                                                        |
| cdxgen evinse / atom                                          | посмотреть после Noir                                   | сервисы+эндпоинты в CycloneDX SaaSBOM через atom (Joern-стек); Java ≥ 21, Kotlin под вопросом                                 |
| Threagile / pytm                                              | не источник                                             | потребители нашей модели: model.json → security-риски; возможный трек «модель кормит threat modeling»                         |
| IcePanel, Multiplayer, Backstage                              | нет                                                     | витрины/SaaS поверх готовых спек или телеметрии, не экстракторы                                                               |
| Confluent Stream Lineage, Conduktor                           | нет (пока)                                              | runtime + коммерция; смотреть только при готовой инфраструктуре                                                               |
| Tempo service graph, Caretta, Coroot, Beyla, Odigos, Otterize | потом                                                   | runtime/eBPF — probe-слой, когда будут трейсы/кластер                                                                         |
