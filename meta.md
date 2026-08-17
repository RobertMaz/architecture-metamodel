# LikeC4 из Java/Kotlin ландшафта — под твою метамодель

## Принцип

Факты извлекаются детерминированно, LLM только осмысляет. Модели — нормализованный JSON, никогда не сырые исходники. Ничего локально
запускать не нужно: всё снимается с dev и из гита.

## Источник → элемент метамодели

| Элемент               | Откуда                                                                        | Признак                         |
|-----------------------|-------------------------------------------------------------------------------|---------------------------------|
| `service`             | `/actuator/mappings`                                                          | есть роуты кроме `/actuator/**` |
| `worker`              | mappings пустые + `@KafkaListener`/`@Scheduled`                               | нет публичных роутов            |
| `client`              | репо без k8s-деплоймента (KMP, Android)                                       | руками                          |
| `store`               | `/actuator/configprops` → `spring.datasource.*`, S3/Redis                     |
| `channel`             | Kafka UI + `KafkaTemplate` / `@KafkaListener`                                 |
| `api` + `operation`   | `/actuator/mappings`: метод, путь, класс                                      |
| `message`             | schema registry + DTO из payload листенера                                    |
| `call`                | `@FeignClient`, base-URL из `/actuator/env` → матч на k8s svc                 |
| `publish` / `deliver` | `KafkaTemplate.send` / `@KafkaListener`                                       |
| `read` / `write`      | jQAssistant: Spring Data → entity → таблица; `save/delete`=write, `find`=read |
| `#public`             | наличие в ingress                                                             |
| `#deprecated`         | `@Deprecated` на методе контроллера                                           |
| `#pii`                | руками                                                                        |
| deployment model      | `kubectl get deploy,svc,ingress -o yaml`                                      |

Приоритет источников: tracing (если есть) → Actuator → k8s → jQAssistant → OpenAPI → git log → люди.

## Инструменты

**Брать:** Spring Actuator, jQAssistant + Neo4j (скан JAR-ов из Nexus, не собирать самому), `code-maat` для churn, IntelliJ Ultimate
Endpoints window, LikeC4 + `@likec4/mcp`.

**Не сейчас:** ArchUnit (это для CI, когда целевая архитектура известна), Structure101/Sonargraph (закупка), CodeScene (платно).

## Пайплайн

```
JAR из Nexus ──► jQAssistant ──┐
Actuator по dev ───────────────┼──► нормализованный JSON
k8s manifests ─────────────────┤    (kind, эндпоинты, каналы,
git log (churn) ───────────────┘     сторы, repo, team, k8s)
                                            │
                              LLM: JSON → gen/*.gen.c4 (всё #inferred)
                                            │
                                  likec4 validate + check.mjs ◄─┐
                                            │──────────────────┘
                                        LikeC4 MCP
```

## Структура файлов

```
model/
  10-shop.c4          ← руками, L0
  gen/orders.gen.c4   ← генератор, перезаписывается, поголовно #inferred
  overrides.c4        ← правки человека через extend
```

Ручные правки в `gen/` запрещены (CI по diff). Знание, добытое разговорами, живёт в `overrides.c4` — регенерация его не затирает.

## Доработки спеки

- **`tag todo`** — свои неразобранные сервисы (`#stub` только про чужие). `search-element` по `#todo` = бэклог онбординга из MCP
- **`#inferred` реально проставлять** — сейчас объявлен и не используется
- **Владелец стора в metadata, не в description** — два писателя в один стор главный детектор скрытой связности, линтер должен его видеть
- **Deployment model** вместо k8s-данных в metadata — есть `read-deployment` в MCP
- **Дублировать `repo`/`team`/`k8s`/`module` в metadata** — `search-element` ищет по metadata, а не по `link`

## Стабильность id операций

`{method}_{path: / → _, {param} → p}` — это контракт, на него ссылаются связи из рукописных файлов. Зафиксировать в комментарии спеки,
покрыть тестом. Смена генератора иначе молча переименует половину операций.

## Правила check.mjs

- > 1 писателя в стор → error
- `call` в контейнер, у которого есть `api` → error
- `client` принимает входящий `call` → error
- `channel` без `publish` или без `deliver` → warn
- `externalSystem` без `#stub` → warn
- `#inferred` старше 90 дней → warn
- `orgSystem` с описанными внутренностями → error

## Kotlin: где байткод врёт

`inline` исчезает в местах вызова, корутины рвут цепочки через стейт-машины, extension-функции уезжают в `FileNameKt`. Граф вызовов по
Kotlin перепроверять рантайм-источниками.

## Модели

- **Корпоративный Qwen3.5-397B** — дефолт: синтез по графу, доменные границы, поиск циклов
- **LM Studio 0.4.18+ / `mlx-community/Qwen3.8-27B-6bit`** — батч по 50 сервисам ночью, оффлайн, Obsidian
- Для генерации DSL: thinking off, temp 0.1–0.2, один сервис на запрос (~4–8K, на Mac дорог prefill)

## Порядок первой недели

1. День 0: сообщение платформенной команде + доступы веером (git org read-all, kubectl dev, Grafana/Jaeger, Nexus, Kafka UI, CI)
2. Клон всех репо → инвентарь + churn
3. Снимок кластера → ingress = реальные точки входа
4. Actuator sweep по всем подам
5. jQAssistant по JAR-ам (ночь)
6. Нормализация в JSON
7. Генерация DSL + validate

Шаги 1–4 дают ~70% скелета за 2–3 дня.

## Масштаб: что сломается на 50 сервисах

`view index { include * }` станет кашей — наверху оставить `include element.kind = system`, контейнеры в per-system видах. `view tiers` при
таком размере полезнее как точка входа для новичка.

## Не забыть

Журнал удивлений с первого дня. Глоссарий доменных терминов («заказ» в биллинге ≠ в логистике). Постмортемы за год. Четыре вопроса каждому
тимлиду: как ходит identity, кто source of truth по каждой сущности, где общие таблицы, где монолит. Задеплоить что-нибудь в прод на первой
неделе. Карту публиковать рано и криво. Топ-5 сервисов вглубь, 50 на уровне карты, для остальных знать кого спросить.