#!/usr/bin/env bash
# Микрополка noir: статическое второе мнение по входящим REST-эндпоинтам
# (OWASP Noir, один бинарь без зависимостей; Java+Kotlin Spring подтверждены пилотом).
# Контракт адаптера (как у jqassistant): TYPE|attr=value|...|source|confidence
# Использование: noir-adapter.sh <путь-к-репозиторию-контейнера>
# Бинарь: $NOIR_BIN, или noir рядом с этим скриптом, или noir из PATH.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NOIR_BIN="${NOIR_BIN:-}"
if [[ -z "$NOIR_BIN" ]]; then
  if [[ -x "$HERE/noir" ]]; then NOIR_BIN="$HERE/noir"; else NOIR_BIN="noir"; fi
fi
REPO="${1:?usage: noir-adapter.sh <repo-dir>}"
CONFIDENCE="${NOIR_CONFIDENCE:-0.8}"

# Роль полки — REST-in ИЗ КОДА (второе мнение к source/lst). Только каталоги кода:
# openapi.yml разбирает openapi-полка (noir задвоил бы её и замкнул якорение путей),
# postman-коллекции и прочее из src/test — вообще не источник эндпоинтов.
SCAN_DIRS=()
for d in "$REPO/src/main/java" "$REPO/src/main/kotlin"; do
  [[ -d "$d" ]] && SCAN_DIRS+=("$d")
done
[[ ${#SCAN_DIRS[@]} -eq 0 ]] && SCAN_DIRS=("$REPO")

# --strict: exit 2 при пропущенных файлах — честный сигнал неполного покрытия.
# Noir на экзотическом коде может упасть (SIGABRT): один упавший каталог не убивает
# полку — сканируем остальные; полка падает, только если не выжил ни один скан.
OK=0
FAILED=0
for SCAN in "${SCAN_DIRS[@]}"; do
  if OUT="$("$NOIR_BIN" scan "$SCAN" -f json --strict --no-log --no-spinner --no-color 2> >(sed 's/^/[noir-bin] /' >&2))"; then
    jq -r --arg conf "$CONFIDENCE" --arg repo "$REPO" '
        .endpoints[]
        # health/actuator сознательно не моделируем — фильтруем на входе
        | select(.url | test("^/(actuator|health)") | not)
        # ANY и прочие не-методы (dispatcher/servlet-маппинги) — мимо метамодели
        | select(.method | IN("GET","POST","PUT","DELETE","PATCH","HEAD","OPTIONS"))
        | (.details.code_paths[0].path // $repo) as $src
        # источник — путь относительно корня репозитория + строка
        | ($src | sub("^" + $repo + "/"; "")) as $rel
        | "ENDPOINT|method=\(.method)|path=\(.url)|\($rel):\(.details.code_paths[0].line // 0)|\($conf)"
      ' <<<"$OUT"
    OK=$((OK + 1))
  else
    echo "noir не осилил $SCAN (код $?) — каталог пропущен" >&2
    FAILED=$((FAILED + 1))
  fi
done
if [[ $OK -eq 0 && $FAILED -gt 0 ]]; then
  echo "noir упал на всех каталогах" >&2
  exit 1
fi
