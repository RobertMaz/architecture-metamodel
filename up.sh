#!/usr/bin/env bash
# Весь стек arch-analyzer одной командой. Идемпотентен: при каждом запуске
# пересобирает движок (mvn инкрементален — быстро, если ничего не менялось),
# при первом — инициализирует корень данных (п. 10, docs/data-root.md).
#
#   ./up.sh [путь-к-данным]     # без аргумента: ARCH_DATA_ROOT или сам репо движка
#
# Порты: API 8080+, UI 5174+, LikeC4 5173+ — занятые пропускаются автоматически.
# Ctrl+C гасит всё. Логи — <данные>/workspace/_run/*.log
set -euo pipefail

ENGINE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_ARG="${1:-${ARCH_DATA_ROOT:-$ENGINE}}"
mkdir -p "$DATA_ARG"
DATA="$(cd "$DATA_ARG" && pwd)"

say() { printf '\033[1;36m[up]\033[0m %s\n' "$*"; }

# --- корень данных: первый запуск -> заготовка ландшафта -------------------
if [[ ! -f "$DATA/model/00-spec.c4" ]]; then
  if [[ "$DATA" == "$ENGINE" ]]; then
    echo "в $ENGINE нет model/00-spec.c4 — репозиторий движка неполон" >&2
    exit 1
  fi
  say "корень данных пуст — инициализирую $DATA"
  mkdir -p "$DATA"/{model,registry,tools/api-source,workspace}
  cp "$ENGINE/model/00-spec.c4" "$DATA/model/"
  cp "$ENGINE/model/30-unknown.c4" "$DATA/model/"
  [[ -f "$DATA/.gitignore" ]] || cp "$ENGINE/.gitignore" "$DATA/.gitignore"
  [[ -f "$DATA/CODEOWNERS" ]] || touch "$DATA/CODEOWNERS"
  git -C "$DATA" rev-parse --git-dir >/dev/null 2>&1 || git -C "$DATA" init -q
  # стартовый коммит: «дифф прогона» в UI сравнивает с git — база нужна сразу
  if ! git -C "$DATA" rev-parse --verify -q HEAD >/dev/null 2>&1; then
    git -C "$DATA" add -A && git -C "$DATA" commit -qm "init: заготовка ландшафта" || true
  fi
fi

# --- сборка движка ----------------------------------------------------------
[[ -d "$ENGINE/node_modules" ]] || (say "npm install (движок)"; cd "$ENGINE" && npm install --silent)
[[ -d "$ENGINE/ui/node_modules" ]] || (say "npm install (ui)"; cd "$ENGINE/ui" && npm install --silent)

say "JDK стека: $(java -version 2>&1 | head -1) — сервисы, собранные более новой Java, потребуют SPRINGWOLF_JAVA_HOME"
say "сборка анализатора"
mvn -q -f "$ENGINE/analyzer/pom.xml" -DskipTests compile
say "сборка сканера springwolf (полка consumers)"
mvn -q -f "$ENGINE/analyzer/springwolf-scanner/pom.xml" -DskipTests package
say "сборка lst-экстрактора (полка lst)"
mvn -q -f "$ENGINE/analyzer/lst-extractor/pom.xml" -DskipTests package

if [[ -z "${NOIR_BIN:-}" && ! -x "$ENGINE/analyzer/noir/noir" ]] && ! command -v noir >/dev/null; then
  say "ВНИМАНИЕ: бинаря noir нет (analyzer/noir/noir | NOIR_BIN | PATH) — полка noir будет неактивна"
fi

# --- LLM: локальный LM Studio, если поднят ----------------------------------
# registry/llm.yml в данных уже есть — не трогаем. Нет — пробуем LM Studio
# (дефолт http://localhost:1234/v1, override: LLM_BASE_URL/LLM_MODEL) и создаём.
if [[ ! -f "$DATA/registry/llm.yml" ]]; then
  LLM_URL="${LLM_BASE_URL:-http://localhost:1234/v1}"
  if MODELS_JSON="$(curl -sf --max-time 3 "$LLM_URL/models" 2>/dev/null)"; then
    MODEL="${LLM_MODEL:-$(jq -r '[.data[].id] | (map(select(test("qwen3\\.8"; "i"))) + map(select(test("qwen"; "i"))) + .)[0] // empty' <<<"$MODELS_JSON")}"
    if [[ -n "$MODEL" ]]; then
      mkdir -p "$DATA/registry"
      cat >"$DATA/registry/llm.yml" <<EOF
# LLM-полка: локальный LM Studio (создано up.sh — поправь под себя).
# Роли: fallback на точках внимания, enrich-описания, ревью пропусков в отчёт,
# гипотезы в триаже. Всё с confidence <= 0.7, кэш в workspace/_llm-cache/.
llm:
  baseUrl: $LLM_URL
  model: $MODEL
  enrich: true
EOF
      say "llm: LM Studio на $LLM_URL, модель $MODEL — registry/llm.yml создан"
    else
      say "llm: LM Studio на $LLM_URL отвечает, но моделей не видно — полка выключена"
    fi
  else
    say "llm: LM Studio на $LLM_URL недоступен — полка llm выключена (подними LM Studio и перезапусти)"
  fi
fi

# --- свободные порты --------------------------------------------------------
port_free() { ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
PICKED=""
# pick_port <имя-переменной> <стартовый-порт>: занятые и уже выданные пропускаются
pick_port() {
  local p=$2
  while ! port_free "$p" || [[ " $PICKED " == *" $p "* ]]; do p=$((p + 1)); done
  PICKED="$PICKED $p"
  printf -v "$1" '%s' "$p"
}
pick_port API_PORT "${API_PORT:-8080}"
pick_port LIKEC4_PORT "${LIKEC4_PORT:-5173}"
pick_port UI_PORT "${UI_PORT:-5174}"

RUN="$DATA/workspace/_run"
mkdir -p "$RUN"
PIDS=()
cleanup() {
  say "останавливаю..."
  for pid in "${PIDS[@]}"; do kill "$pid" 2>/dev/null || true; done
  # mvn exec:java порождает дочерний jvm — добиваем группу
  for pid in "${PIDS[@]}"; do pkill -P "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT INT TERM

# --- сервисы -----------------------------------------------------------------
say "сервер анализатора: http://localhost:$API_PORT (данные: $DATA)"
(cd "$ENGINE" && ARCH_DATA_ROOT="$DATA" exec mvn -q -f analyzer/pom.xml compile exec:java \
  -Dexec.mainClass=arch.analyzer.server.ServerKt -Dexec.args="--port $API_PORT") \
  >"$RUN/server.log" 2>&1 &
PIDS+=($!)

say "UI: http://localhost:$UI_PORT"
(cd "$ENGINE/ui" && ANALYZER_URL="http://localhost:$API_PORT" VITE_LIKEC4_URL="http://localhost:$LIKEC4_PORT" \
  exec npx vite --port "$UI_PORT" --strictPort) \
  >"$RUN/ui.log" 2>&1 &
PIDS+=($!)

say "LikeC4: http://localhost:$LIKEC4_PORT (модель: $DATA/model)"
(cd "$ENGINE" && exec npx likec4 start "$DATA/model" --port "$LIKEC4_PORT") \
  >"$RUN/likec4.log" 2>&1 &
PIDS+=($!)

# --- готовность --------------------------------------------------------------
say "жду сервер анализатора..."
for _ in $(seq 1 90); do
  curl -sf "http://localhost:$API_PORT/api/containers" >/dev/null 2>&1 && break
  sleep 2
done
if ! curl -sf "http://localhost:$API_PORT/api/containers" >/dev/null 2>&1; then
  echo "сервер не поднялся — хвост $RUN/server.log:" >&2
  tail -20 "$RUN/server.log" >&2
  exit 1
fi

say "готово:"
say "  UI (дашборд, онбординг, триаж):  http://localhost:$UI_PORT"
say "  LikeC4 (виды ландшафта):         http://localhost:$LIKEC4_PORT"
say "  REST API:                        http://localhost:$API_PORT"
say "логи: $RUN/  ·  Ctrl+C — остановить всё"
wait
