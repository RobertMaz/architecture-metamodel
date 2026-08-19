# Приватный ландшафт: корень данных отдельно от движка (п. 10 плана)

Движок (`analyzer/`, `tools/`, `ui/`) и данные (`model/`, `registry/`,
`tools/api-source/`, `workspace/`, `CODEOWNERS`, `model/verified.json`)
разделяются параметром **корня данных**:

- CLI/сервер анализатора: `--arch-root <path>` или env `ARCH_DATA_ROOT`;
- node-инструменты (`gen`, `check`, `owners`, `verify`, …): env `ARCH_DATA_ROOT`;
- движковые ассеты (собранные сканеры springwolf/lst, адаптеры noir/jqassistant)
  ищутся от корня движка: cwd процесса или env `ARCH_ENGINE_ROOT`.

По умолчанию оба корня — текущий репозиторий: всё работает как раньше.

## Layout приватного data-репо

Тот же, что здесь, минус движок:

```
my-landscape/
  CODEOWNERS
  model/            # 00-spec.c4, 10-*.c4, 20-views.c4, systems/, gen/, verified.json
  registry/         # systems.yml, repos.yml, aliases.yml, resolutions.yml, clientlibs.yml, llm.yml
  tools/api-source/ # выход анализатора (коммитится)
  workspace/        # evidence-файлы (gitignore)
  package.json      # CI-инварианты едут вместе с данными (см. ниже)
```

Скопировать на старте: `model/00-spec.c4` (метамодель), `.gitignore` (workspace/, build/).

## package.json data-репо

Движок — соседняя папка (или git submodule / npm file:-зависимость):

```json
{
  "scripts": {
    "gen": "ARCH_DATA_ROOT=. node ../architecture/tools/gen-api.mjs && ARCH_DATA_ROOT=. node ../architecture/tools/gen-model.mjs",
    "check": "npm run gen && likec4 validate model && likec4 export json model -o build/model.json && ARCH_DATA_ROOT=. node ../architecture/tools/check.mjs build/model.json"
  },
  "devDependencies": { "likec4": "^1.x" }
}
```

CI data-репо гоняет `npm run check` — инварианты метамодели живут с данными,
движок обновляется независимо.

## Одной командой

`./up.sh /path/to/my-landscape` из репо движка делает всё сам: пересобирает
анализатор и сканеры, при первом запуске инициализирует корень данных
(каталоги, 00-spec, 30-unknown, CODEOWNERS, git init), поднимает сервер + UI +
LikeC4 на свободных портах (занятые пропускаются, ссылки печатает и связывает).
Ctrl+C гасит весь стек. Логи — `<данные>/workspace/_run/`.

## Анализ (по частям)

```bash
# из репо движка (сканеры собраны здесь):
mvn -q -f analyzer/pom.xml compile exec:java -Dexec.args="analyze --all --arch-root /path/to/my-landscape"
# или сервер для UI:
ARCH_DATA_ROOT=/path/to/my-landscape mvn -q -f analyzer/pom.xml compile exec:java -Dexec.mainClass=arch.analyzer.server.ServerKt
```

Сервер после анализа сам вызывает `npm run gen`: в data-репо, если там есть
package.json, иначе — в движке с `ARCH_DATA_ROOT`, указывающим на данные.
