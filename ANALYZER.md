# Arch-analyzer: как запустить и работать

Руководство для человека. Что это и почему так устроено — `docs/superpowers/specs/2026-08-17-arch-analyzer-design.md`.

## Требования

- JDK 17+ (`java -version`), Maven 3.6+ (`mvn -version`)
- Node 22+ (`node --version`)
- git; сеть до Maven Central и npm при первом запуске

## Запуск фулл-стека (три процесса)

```bash
# 0. Один раз: зависимости
npm install && (cd ui && npm install)

# 1. Сервер анализатора (Ktor, REST) — порт 8080, менять флагом --port
LLM_API_KEY=$(cat ~/.openrouter-key) \
  mvn -q -f analyzer/pom.xml compile exec:java \
  -Dexec.mainClass=arch.analyzer.server.ServerKt \
  -Dexec.args="--port 8080 --arch-root $(pwd)"

# 2. UI анализатора — http://localhost:5174 (главная точка входа)
cd ui && ANALYZER_URL=http://localhost:8080 npm run dev
#   если LikeC4 не на 5173: добавь VITE_LIKEC4_URL=http://localhost:<порт>

# 3. Карта LikeC4 — http://localhost:5173
npm run dev            # или: npx likec4 start model --port 5175
```

`LLM_API_KEY` нужен только если включена полка llm (`registry/llm.yml`); без файла/ключа всё работает, просто без LLM.

## Рабочий цикл

1. **UI → «+ Система»** — завести систему (kind: `system` — своя, `orgSystem` — другая команда, `externalSystem` — вне компании). Владелец уходит в CODEOWNERS.
2. **UI → «+ Контейнер»** — привязать репозиторий: обязателен только путь к сорцам; JAR / URL запущенной апки / файл OTel-спанов / OpenAPI-спека добавляются когда угодно (карточка → «Источники»). Выбор системы — ранний: id иерархичен (`система.имя`), перенос потом — id-aware rename.
3. **«Анализ»** — кнопка ставит прогон в очередь (`queued → running → done`); полки запускаются по наличию источников, статус переживает рестарт и обновление страницы.
4. **Карточка** — отчёт (эндпоинты/вызовы/сторы с confidence и ссылками в код), «Дифф прогона» — что изменилось в модели. **Коммитишь сам** — это точка контроля человека.
5. **«Триаж»** — нераспознанные цели: склейка с контейнером (кандидаты со скором, гипотезы LLM), «В систему…» (stub становится наблюдаемым контейнером чужой системы и дообогащается сам), «Это external».
6. Смотреть результат — LikeC4: папка видов на систему (бургер-меню → «By folders»): `Контейнеры` + `API / <сервис>`.

## Полки-источники (что подложить, чтобы стало точнее)

| Полка | Вход | Как получить вход |
|---|---|---|
| source | путь к сорцам (обязателен) | git clone |
| config | — (те же сорцы: application*.yml) | — |
| openapi | `openapi:` или автопоиск `openapi.yml`/`api-docs.json` в репо | committed-спека или `curl :8080/v3/api-docs > api-docs.json` |
| bytecode | `jar:` | Nexus или `./mvnw package -DskipTests` |
| runtime | `runtimeUrl:` живой апки | запустить с `--management.endpoints.web.exposure.include=mappings,env,health` |
| traces | `traces:` файл OTel-спанов | см. ниже |
| llm | `registry/llm.yml` + env `LLM_API_KEY` | OpenAI-совместимый endpoint (корп. Qwen: `baseUrl: https://.../v1`; LM Studio: `http://<тачка>:1234/v1`) |
| jqassistant | `analyzer/jqassistant/extract.sh` (печатает `TYPE\|attr=value\|...\|source\|confidence`) | поставить jQAssistant руками, обернуть Cypher в скрипт |

### Снять OTel-спаны

```bash
curl -sLo otel.jar https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar

java -javaagent:otel.jar \
  -Dotel.traces.exporter=logging-otlp -Dotel.metrics.exporter=none -Dotel.logs.exporter=none \
  -Dotel.service.name=my-service \
  -Dotel.instrumentation.common.peer-service-mapping=host1:port1=имя-сервиса-1,host2:port2=имя-сервиса-2 \
  -jar app.jar > app.log 2>&1

# погонять трафик, затем: (-a обязателен — grep считает лог бинарным)
grep -ao '{"resource":.*}' app.log > traces.jsonl
```

`peer-service-mapping` важен: без него вызовы через балансер/eureka придут с host=localhost/IP. Учитывай, что eureka может резолвить в LAN-IP — добавь и его в маппинг.

## Правила игры

- `model/systems/<id>/<id>.c4` — вся кухня системы одним файлом; **править руками можно**: закоммиченные правки после регенерации вытаскиваются из git diff, незакоммиченные генератор спасает в `workspace/_backup/` с предупреждением в лог сервера.
- `model/gen/` — только догадки (stub'ы/observed/externals), руками не править.
- `registry/` — входы: `systems.yml`, `repos.yml`, `aliases.yml` (автопополняется, ручные записи не перетираются), `resolutions.yml` (решения триажа), `llm.yml`; `unresolved.json` — генерируется.
- `workspace/` — gitignore: evidence полок (персистентны — источник умер, факты остались), кэш LLM, бэкапы.
- Всё детерминированно: повторный прогон — байт-в-байт; проверка — `npm run check` (0 ошибок обязательно).

## Проверка, что всё живо

```bash
mvn -q -f analyzer/pom.xml test          # тесты анализатора
npm run test:tools                        # тесты генератора
npm run check                             # полный цикл: gen -> validate -> инварианты
curl -s http://localhost:8080/api/health  # сервер
```
