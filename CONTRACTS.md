# Контрактный слой: API, эндпоинты, схемы сообщений

Отвечает на вопрос, ради которого всё и затевалось: **кто сломается, если я поменяю этот эндпоинт.**

Руками этот слой не пишут — он генерируется. Всё, что здесь описано, — метамодель и формат обмена с анализатором.

## Где что лежит

| | графовая часть (`.gen.c4`) | документная часть (`.json`) |
|---|---|---|
| что хранит | идентичность и **связи** | полная сигнатура |
| зачем | обход графа: потребители, радиус изменения | ответ на «а какие там поля» |
| размер | десятки строк | сколько угодно |

Схемы целиком в граф пихать не надо: **граф про рёбра, схема про документ.** В `metadata` едет сжатая выжимка — её видно в панели LikeC4 при клике на эндпоинт, и этого хватает, чтобы не лезть в код.

```
tools/api-source/shop.orders.json   выход анализатора, источник правды
        │  node tools/gen-api.mjs
        ▼
model/gen/shop.orders.gen.c4        коммитится, руками не правится
```

## Метамодель

Три новых типа, все — только внутри уже существующих:

| kind | родитель | что это |
|---|---|---|
| `api` | `service` / `worker` | опубликованный контракт контейнера |
| `operation` | `api` | эндпоинт: метод + путь |
| `message` | `channel` | схема сообщения |

**`api` висит на контейнере, а не на компоненте.** Поэтому компоненты можно не описывать вовсе (мы их и не описываем), а контракт при этом останется на месте.

**`message` живёт в канале, а не в отправителе.** Схема принадлежит каналу: у него может смениться продюсер, а контракт для подписчиков останется тем же.

### Метаданные эндпоинта

```likec4
post_api_v1_orders = operation 'POST /api/v1/orders' {
  #inferred
  description 'Создать заказ'
  metadata {
    method 'POST'
    path '/api/v1/orders'
    params 'Idempotency-Key:header:string'
    request 'CreateOrderRequest'
    responses '201 OrderResponse, 409 Problem'
    auth 'oauth2:orders.write'
    source 'src/main/java/io/acme/orders/web/OrderController.java#L42'
    confidence '0.96'
  }
}
```

`source` и `confidence` — обязательны для всего извлечённого. Без ссылки на строку кода проверить извлечение невозможно, а без confidence непонятно, чему верить.

### Идентификаторы

`id` эндпоинта = метод + путь, где `{параметр}` заменён на фиксированный `_p_`:

```
POST /api/v1/orders            -> post_api_v1_orders
GET  /api/v1/orders/{id}       -> get_api_v1_orders_p
POST /api/v1/orders/{id}/cancel -> post_api_v1_orders_p_cancel
```

Имя path-параметра в id не входит: переименование `{orderId}` → `{id}` не должно ломать модель. Генератор падает при коллизии id, а не молча склеивает два эндпоинта.

Это важно, потому что **на сгенерированные id ссылаются связи, написанные руками** (`shop.gw -[call]-> shop.orders.api.post_api_v1_orders`). Смена схемы генерации id = массовое ломание ссылок.

## Правило постепенного внедрения

Контракты появятся не у всех сервисов сразу. Линтер это разрешает:

- у контейнера **нет** `api` → звони в контейнер, как раньше;
- у контейнера **есть** `api` → звонить в контейнер запрещено, только в конкретный `operation`.

Так модель не требует «сначала опишите всё», но и не даёт откатиться назад там, где контракт уже есть. В примере: `orders` уже с контрактом, `billing` ещё нет.

## Инварианты (в `tools/check.mjs`, роняют CI)

- `api` только на `service`/`worker`, `operation` только в `api`, `message` только в `channel`;
- если контракт описан — `call` ведёт в `operation`, а не в контейнер;
- у эндпоинта есть `method` и `path`, метод из списка HTTP;
- нет дублей `method + path` внутри одного `api`;
- **нельзя звонить в эндпоинт, у которого `sunset` в прошлом** — это ошибка сборки;
- вызов `#deprecated` — предупреждение с датой выключения;
- `#inferred` с `confidence` ниже 0.8 — предупреждение «посмотреть глазами»;
- эндпоинт без известных потребителей — предупреждение (кроме `#public`, там потребители снаружи).

## Impact-анализ

```bash
npm run check
node tools/impact.mjs build/model.json shop.orders.api.post_api_v1_orders
```

```
Изменение: shop.orders.api.post_api_v1_orders  (POST /api/v1/orders)
  POST /api/v1/orders
  params:    Idempotency-Key:header:string
  request:   CreateOrderRequest
  код:       src/main/java/io/acme/orders/web/OrderController.java#L42

Затронуто: 5
  · shop.gw          API Gateway    call   team-platform
  ·· shop.mobile     Mobile App     call   team-mobile
  ·· shop.pos        POS Terminal   call   team-retail

Предупредить команды: team-platform, team-mobile, team-retail
```

**Направление зависимости выводится из типа связи, а не из стрелки.** Для `call`, `read`, `write`, `publish` зависит источник — идём против стрелки. Для `deliver` зависит получатель: он разбирает схему события — идём по стрелке. Поэтому impact по каналу находит подписчиков:

```bash
node tools/impact.mjs build/model.json shop.orderCreated
# shop.orders   publish   team-checkout
# shop.billing  deliver   team-payments
```

Это же место, куда потом встаёт бот в PR: собрать изменённые эндпоинты из диффа `.gen.c4` и вывалить список потребителей с командами.

## Формат для анализатора

Это единственное, что нужно согласовать с будущим обходчиком Java. Он ничего не знает про LikeC4 — только выдаёт JSON, см. полный пример в `tools/api-source/shop.orders.json`.

```json
{
  "container": "shop.orders",
  "source": {
    "repo": "acme/orders",
    "commit": "a1b2c3d",
    "extractedAt": "2026-08-17",
    "extractor": "java-deep-analyze v0.3"
  },
  "api": {
    "id": "api",
    "title": "Orders API",
    "technology": "HTTP/JSON",
    "basePath": "/api/v1/orders",
    "public": true
  },
  "operations": [
    {
      "method": "POST",
      "path": "/api/v1/orders",
      "summary": "Создать заказ",
      "auth": "oauth2:orders.write",
      "params": [
        { "name": "Idempotency-Key", "in": "header", "type": "string", "required": true }
      ],
      "request": { "type": "CreateOrderRequest", "contentType": "application/json" },
      "responses": [
        { "status": 201, "type": "OrderResponse" },
        { "status": 409, "type": "Problem" }
      ],
      "deprecated": false,
      "sunset": null,
      "source": "src/.../OrderController.java#L42",
      "confidence": 0.96
    }
  ],
  "publishes": [
    {
      "channel": "shop.orderCreated",
      "schema": "OrderCreatedV1",
      "key": "orderId",
      "fields": "orderId:uuid, customerId:uuid, amount:decimal, items[]",
      "source": "src/.../OrderCreatedPublisher.java#L18",
      "confidence": 0.88
    }
  ]
}
```

Обязательные поля: `container`, `source.*`, `api.id`, для каждой операции — `method`, `path`, `source`, `confidence`.

Три вещи, которые стоит заложить в анализатор сразу, иначе переделывать больно:

1. **`container` он взять неоткуда не может** — это наш идентификатор, а не свойство репозитория. Держи маппинг «репозиторий → container» в одном месте (например, в самом репозитории, в `.arch.yml`).
2. **`confidence` на каждую операцию, а не на файл.** Контроллер со Spring-аннотациями извлекается уверенно, роутинг через самописный диспетчер — нет, и это должно быть видно поэлементно.
3. **Исходящие вызовы тоже стоит извлекать** (`calls: [{to: "billing", method, path}]`). Тогда рёбра между сервисами перестанут писаться руками — а это самая протухающая часть модели. В текущем формате этого блока нет намеренно: сначала входящие контракты, потом исходящие вызовы, иначе анализатор не взлетит.

## Формат v2 (выход arch-analyzer)

Док с блоком `containerInfo` — это v2: его обрабатывает `tools/gen-model.mjs`
(контейнер, сторы, каналы и рёбра генерируются целиком), а `gen-api.mjs` его
пропускает. Легаси-доки v1 без `containerInfo` работают по-старому.

Новые блоки поверх v1:

```json
{
  "containerInfo": { "kind": "service|worker", "title": "...", "technology": "...", "appName": "..." },
  "subscribes": [ { "channel": "topic", "group": "cg", "payload": "Dto", "source": "...", "confidence": 0.9 } ],
  "calls": [ { "method": "GET", "path": "/x", "target": { "host": "...", "feignName": "...", "role": "...", "urlTemplate": "..." }, "source": "...", "confidence": 0.8 } ],
  "stores": [ { "kind": "jdbc|redis|s3", "address": "jdbc:...", "technology": "MySQL", "access": "read|write|readwrite", "entities": "A, B", "source": "...", "confidence": 0.9 } ]
}
```

Отличия v2 от v1 в операциях: `params`/`request`/`response` — компактные строки
(`name:in:type?`), не объекты. Пустой `address` стора = «свой дефолтный
datasource»: такой стор именуется по контейнеру и НЕ склеивается с чужими —
иначе неизвестные адреса дали бы ложный shared database. `calls` без
`target.container` ждут разрешения через реестр (подпроект 3) и в рёбра пока
не генерятся. `target.role` — логическая роль цели (`config-server` |
`discovery` | `config-repo`), когда адрес неизвестен (ребро выведено из
зависимости в pom/gradle): резолвится алиасом с именем роли, иначе живёт
stub'ом `unknown.<role>` до решения в триаже.

## Чего здесь намеренно нет

- **Полных JSON-схем в графе.** Они в исходном JSON и в OpenAPI. В граф едет только то, по чему ходят.
- **Версий и diff'а схем.** Это работа контрактного тестирования и schema registry, а не диаграмм.
- **Внутренних эндпоинтов вроде `/health` и `/metrics`.** Фильтруются в анализаторе — иначе они забьют все виды.
