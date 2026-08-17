# Analyzer Bytecode (подпроект 4) — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Вторая полка фактов из байткода: ASM по JAR (аннотации Spring/Feign/Kafka из class-файлов) — закрывает «сорцов нет, бинарь есть» и места, где Kotlin-сорцы врут; jqassistant — опциональная полка при установленном CLI.

**Architecture:** `registry/repos.yml` получает опциональное поле `jar:`; `RepoInput` расширяется; `BytecodeLane` (ASM ClassReader, только аннотации с RUNTIME retention) выдаёт те же типы фактов, что source, с `source: <jar>!<Class>#<method>` и confidence 0.8 — реконсилятор сам подтверждает/дополняет. `JqassistantLane`: applicable только при наличии `analyzer/jqassistant/bin/jqassistant.sh` (ставится руками, тянуть Neo4j в сборку не хотим); парсер его CSV-выхода тестируется отдельно. Тестовый JAR собирается на лету: javac с classpath из maven-зависимостей (spring-web/kafka аннотации) + jar.

**Tech Stack:** ASM 9.7 (`org.ow2.asm:asm`), javax.tools (тестовая сборка JAR), существующий стек.

**Spec:** `docs/superpowers/specs/2026-08-17-arch-analyzer-design.md` (полки 3–4)

## Global Constraints

- Детерминизм и формат фактов — без изменений; обход классов в JAR отсортирован.
- Приоритет полок уже задан: `source > bytecode > config` — детали из сорцов побеждают, байткод подтверждает и добирает.
- jqassistant не становится обязательной зависимостью сборки.

---

### Task 1: jar в реестре и входе
`repos.yml`: `jar: <путь>` (опционально); `RepoEntry.jar`, `RepoInput.jar`; UI-диалог контейнера — поле «JAR (опционально)»; Onboarding принимает `jar`. Тесты Registry/Onboarding. Commit.

### Task 2: BytecodeLane (ASM)
Зависимость `org.ow2.asm:asm:9.7.1`. Распознаётся из class-файлов: `@RestController`+`@RequestMapping`/`@*Mapping` (метод+путь+класс-префикс) → ENDPOINT (conf 0.8); `@FeignClient` интерфейсы → OUTGOING_CALL (0.8); `@KafkaListener` → SUBSCRIBE (0.8); `@Scheduled` → CONTAINER_HINT; интерфейсы, extends Spring Data-репозиториев (по имени супертипа в сигнатуре) → STORE_ACCESS (0.8). `source` = `<jar-имя>!<FQCN>#<метод>`. Тест: фикстурные Java-файлы компилируются в JAR на лету (javax.tools + test-classpath со spring-web/spring-kafka/spring-cloud-openfeign аннотациями как test-зависимости), полка выдаёт ожидаемые факты; сравнение с source-полкой на той же фикстуре — идентичные ключи идентичности. Commit.

### Task 3: Подключение и подтверждение
`Analyze.defaultLanes()` + BytecodeLane; тест реконсиляции: source(0.95) + bytecode(0.8) по одному эндпоинту → confidence растёт, детали от source. Commit.

### Task 4: JqassistantLane (опционально при установленном CLI)
applicable = есть `analyzer/jqassistant/bin/jqassistant.sh` и jar; extract: `scan -f <jar>` + `query` фиксированных Cypher (Feign-клиенты, listener'ы) с CSV-выходом → факты (lane `jqassistant`, conf 0.75). CSV-парсер — отдельная функция с юнит-тестом; сам прогон — руками (документируется). Commit.

### Task 5: Приёмка + docs
Собрать JAR vets-service (`./mvnw -q -pl spring-petclinic-vets-service -am package -DskipTests` в петклинике), прописать `jar:` для petclinic.vets, прогон: факты подтверждены двумя полками (confidence вырос в api-source), check зелёный, повторный прогон стабилен. CLAUDE.md: полка bytecode и опциональный jqassistant. Commit, merge.

## Self-review (выполнен)
Спека: полка 3 (bytecode/ASM — полностью), полка 4 (jqassistant — опционально, зафиксированное решение: не тянуть Neo4j в сборку; ASM даёт «второе мнение» конвейеру, jqassistant остаётся инструментом ручных раскопок). Kotlin-кейс: байткод-полка работает по скомпилированному Kotlin без изменений. Плейсхолдеров нет.
