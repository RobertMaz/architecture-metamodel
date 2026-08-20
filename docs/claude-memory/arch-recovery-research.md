---
name: arch-recovery-research
description: Итоги deep research 2026-08-19 по реверсу архитектуры из Java/Kotlin кода — кандидаты для усиления полок arch-analyzer
metadata: 
  node_type: memory
  type: reference
  originSessionId: afadb085-2965-435a-aeb9-7290b972a813
  modified: 2026-08-19T06:31:06.405Z
---

Deep research 2026-08-19 (4 параллельных агента), отчёт-артефакт: https://claude.ai/code/artifact/e398a024-6de9-414e-a727-2d9b4d59a4c6

Ключевое для [[arch-analyzer-project]]:
- Ниша «Spring-код → LikeC4» готовыми продуктами не закрыта; академическое поле — «microservice architecture reconstruction», сравнение 9 инструментов: arXiv 2412.08352 (ансамбль источников — единственный путь к F1≈0.9, что подтверждает схему полки→реконсилятор).
- **Code2DFD** (github.com/tuhh-softsec/code2DFD, Python) — лучший запускаемый: ~35 экстракторов (resttemplate/feign/kafka/rabbitmq/gateway/eureka), донор эвристик: рекурсивный резолвер URL-констант, матчинг «имя сервиса + путь».
- Фундамент глубокого анализа: **OpenRewrite LST** (rewrite-kotlin, Apache-2.0, JVM-библиотека — фаворит), **Joern** (готовый dataflow, kotlin2cpg пилотировать), **SootUp** (call graph по jar, Spring DI склеивать самим). CodeQL лицензионно закрыт без GHAS. Spoon/JavaParser — без Kotlin, мимо.
- **Springwolf** standalone — кандидат на полку Kafka/AMQP-каналов по classpath из jar (проверить, заведётся ли без деплоя).
- Kafka-матчинг — дыра всего SOTA (везде literal string match топика): резолв через конфиги/профили/groupId/DLQ = обгон state of the art.
- LikeC4 Model Builder API (@likec4/core/builder) — типобезопасная альтернатива текстогенерации .c4.
- Runtime-слой на потом (ограничение юзера: запускать апки нельзя, бинарь+сорцы всегда доступны; трейсы появятся позже): Tempo service graph, eBPF Caretta/Coroot/Otterize Kafka Watcher — как probe для verify.mjs.
- ArchGuard/Chapi — ближайший живой аналог (Feign/RestTemplate/контроллеры), но без Kafka/WebClient; смотреть как второе мнение, не встраивать.

**БЫСТРЫЙ СТАРТ ВНЕДРЕНИЯ:** очередь работ и все вердикты — в `plan.md` в корне репо (уже утверждён юзером, не закоммичен на 2026-08-19). Весь пилотный код сохранён в `workspace/_pilots/` (gitignore, персистентно): `springwolf-exp/` (scanner/ + scan-jar.sh + демо-жертва + asyncapi*.json), `openrewrite-exp/` (extractor/Extractor.java ~330 строк + corpus с 8 Kotlin-ловушками + baseline.sh), `noir-exp/` (бинарь noir 1.3.0 + noir-adapter.sh + JSON-выхлопы по petclinic). Порядок внедрения: 1) эвристики config/discovery в config-полку (часы) → 2) полка springwolf (день) → 3) микрополка noir (полдня) → 4) OpenRewrite в source-полке (1–2 недели) → 5) Kafka-producers в ASM.

Пилоты 2026-08-19 (код сохранён в workspace/_pilots/):
- **Springwolf 2.6.0 standalone на чужом fat-jar — работает без запуска**: BOOT-INF/classes на classpath → AsyncAPI 3.1, consumers с groupId+схемой payload+резолвом топиков из application.yml жертвы, <1 c/сервис. **Producers (KafkaTemplate.send) невидимы** — только аннотационный скан. Полка «Kafka-deliver из бинаря»; publish — за ASM. Пинить kafka-clients с Central (тянет Confluent-сборку).
- **OpenRewrite LST — берём, перевод source-полки** (rewrite 8.56.1): пилот — резолв URL 11/14 против 2/14 у регулярок (константы чужих классов, конкатенации, templates, WebClient.create-base, Feign name+path), единый визитор Java+Kotlin, 390/390 типизированных вызовов; ~1.3 c/сервис; без classpath деградирует до регулярок. Продовая версия 1–2 недели; ОТДЕЛЬНЫМ процессом: kotlin-compiler-embeddable 1.9.25 конфликтует с Kotlin 2.1.20 анализатора, RSS ~900 МБ. Экстрактор-пилот: workspace/_pilots/openrewrite-exp/extractor/ (~330 строк).
- **OWASP Noir 1.3.0 — берём, микрополка REST-in**: 15/15 эндпоинтов petclinic совпали посимвольно, Kotlin Spring ок, 0.07 c/сервис, file:line в JSON; framework-эндпоинты (Eureka/actuator) не видит — за runtime-полкой. Адаптер 20 строк bash+jq под контракт `ENDPOINT|method=...|path=...|file:line|0.8`, включить `--strict`.
- **Code2DFD на petclinic — донор, не полка**: пропустил все содержательные рёбра (роуты gateway новой схемы spring.cloud.gateway.server.webflux.routes, WebClient, БД, zipkin), совпало 4/15; CLI хрупкий (битые requirements/флаг, зависание на plantuml.com без таймаута). Наш анализатор строго сильнее. Украсть эвристики: spring.config.import → ребро в config-server; eureka-client в pom/@EnableDiscoveryClient → ребро в discovery (у нас оба типа рёбер есть только у 2/7 сервисов, из runtime); spring.cloud.config.server.git.uri → внешний узел config-репо.
