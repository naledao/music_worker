#!/bin/bash

set -u

APP_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
SELF_SCRIPT="$APP_DIR/$(basename "$0")"
SELF_BASENAME="$(basename "$SELF_SCRIPT")"
WORKER_SCRIPT="$APP_DIR/music_local_api.py"
WORKER_BASENAME="$(basename "$WORKER_SCRIPT")"
PYDEPS_DIR="$APP_DIR/.pydeps"
PLUGIN_DIR="$APP_DIR/yt-dlp-plugins"
LOG_DIR="$APP_DIR/logs"
RUN_DIR="$APP_DIR/run"
WORKER_LOG="$LOG_DIR/music_local_api.stdout.log"
SUPERVISOR_LOG="$LOG_DIR/music_worker_supervisor.log"
WORKER_PIDFILE="$RUN_DIR/music_local_api.pid"
SUPERVISOR_PIDFILE="$RUN_DIR/music_worker_supervisor.pid"
PYTHON_BIN="${PYTHON_BIN:-$(command -v python3 || true)}"
CURL_BIN="${CURL_BIN:-$(command -v curl || true)}"
CHECK_INTERVAL="${CHECK_INTERVAL:-20}"
STARTUP_DELAY="${STARTUP_DELAY:-2}"
LOCK_FILE="$RUN_DIR/music_worker_supervisor.lock"
LOCAL_API_HOST="${MUSIC_LOCAL_API_HOST:-127.0.0.1}"
LOCAL_API_PORT="${MUSIC_LOCAL_API_PORT:-18081}"
LOCAL_API_HEALTH_URL="${MUSIC_LOCAL_API_HEALTH_URL:-http://${LOCAL_API_HOST}:${LOCAL_API_PORT}/api/health}"
HEALTH_CHECK_TIMEOUT="${MUSIC_LOCAL_API_HEALTH_CHECK_TIMEOUT:-5}"
STARTUP_HEALTH_ATTEMPTS="${MUSIC_LOCAL_API_STARTUP_HEALTH_ATTEMPTS:-6}"
STARTUP_HEALTH_INTERVAL="${MUSIC_LOCAL_API_STARTUP_HEALTH_INTERVAL:-1}"
MAX_KEEPALIVE_FAILURES="${MUSIC_WORKER_KEEPALIVE_MAX_FAILURES:-6}"
KEEPALIVE_FAILURE_FILE="$RUN_DIR/music_worker_keepalive.failed"

timestamp() {
  date "+%F %T %z"
}

log() {
  mkdir -p "$LOG_DIR" "$RUN_DIR"
  echo "[$(timestamp)] $*" >> "$SUPERVISOR_LOG"
}

read_pid() {
  local pid_file="$1"
  [ -f "$pid_file" ] || return 1
  tr -cd "0-9" < "$pid_file"
}

pid_alive() {
  local pid="${1:-}"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

current_boot_id() {
  cat /proc/sys/kernel/random/boot_id 2>/dev/null || true
}

clear_keepalive_failure_marker() {
  rm -f "$KEEPALIVE_FAILURE_FILE"
}

write_keepalive_failure_marker() {
  local failure_count="$1"

  mkdir -p "$RUN_DIR"
  {
    echo "status=max_failures"
    echo "timestamp=$(timestamp)"
    echo "boot_id=$(current_boot_id)"
    echo "failures=$failure_count"
    echo "max_failures=$MAX_KEEPALIVE_FAILURES"
    echo "health_url=$LOCAL_API_HEALTH_URL"
  } > "$KEEPALIVE_FAILURE_FILE"
}

wait_pid_stopped() {
  local pid="${1:-}"
  local attempt

  [ -n "$pid" ] || return 0
  for attempt in {1..30}; do
    if ! pid_alive "$pid"; then
      return 0
    fi
    sleep 0.1
  done
  return 1
}

process_args() {
  local pid="${1:-}"
  [ -n "$pid" ] || return 1
  ps -o args= -p "$pid" 2>/dev/null | head -n 1
}

worker_pid_matches() {
  local pid="${1:-}"
  local args

  args="$(process_args "$pid" 2>/dev/null || true)"
  case "$args" in
    *"$WORKER_SCRIPT")
      return 0
      ;;
  esac
  return 1
}

supervisor_pid_matches() {
  local pid="${1:-}"
  local args

  args="$(process_args "$pid" 2>/dev/null || true)"
  case "$args" in
    "/bin/bash $SELF_SCRIPT keepalive"|\
    "/bin/bash ./$SELF_BASENAME keepalive"|\
    "bash $SELF_SCRIPT keepalive"|\
    "bash ./$SELF_BASENAME keepalive"|\
    *"/$SELF_BASENAME keepalive"|\
    *"./$SELF_BASENAME keepalive"|\
    "$SELF_SCRIPT keepalive")
      return 0
      ;;
  esac
  return 1
}

cleanup_pidfile() {
  local pid_file="$1"
  local expected_pid="$2"
  local current_pid

  current_pid="$(read_pid "$pid_file" 2>/dev/null || true)"
  if [ "$current_pid" = "$expected_pid" ]; then
    rm -f "$pid_file"
  fi
}

require_tools() {
  command -v setsid >/dev/null 2>&1 || {
    log "setsid not found in PATH"
    return 1
  }
  command -v flock >/dev/null 2>&1 || {
    log "flock not found in PATH"
    return 1
  }
}

api_healthy() {
  if [ -n "$CURL_BIN" ]; then
    "$CURL_BIN" -fsS --max-time "$HEALTH_CHECK_TIMEOUT" -o /dev/null "$LOCAL_API_HEALTH_URL" >/dev/null 2>&1
    return $?
  fi

  [ -n "$PYTHON_BIN" ] || return 1
  "$PYTHON_BIN" -c 'import sys, urllib.request; urllib.request.urlopen(sys.argv[1], timeout=float(sys.argv[2])).read(1)' "$LOCAL_API_HEALTH_URL" "$HEALTH_CHECK_TIMEOUT" >/dev/null 2>&1
}

wait_worker_healthy() {
  local attempt=1
  local pid

  while [ "$attempt" -le "$STARTUP_HEALTH_ATTEMPTS" ]; do
    pid="$(find_worker_pid 2>/dev/null || true)"
    if pid_alive "$pid" && api_healthy; then
      log "local api healthy pid=$pid health=$LOCAL_API_HEALTH_URL"
      return 0
    fi
    attempt=$((attempt + 1))
    sleep "$STARTUP_HEALTH_INTERVAL"
  done

  return 1
}

find_worker_pid() {
  local pid

  pid="$(read_pid "$WORKER_PIDFILE" 2>/dev/null || true)"
  if pid_alive "$pid" && worker_pid_matches "$pid"; then
    echo "$pid"
    return 0
  fi
  cleanup_pidfile "$WORKER_PIDFILE" "$pid"

  pid="$(pgrep -fo -f "$WORKER_SCRIPT" 2>/dev/null || true)"
  if pid_alive "$pid" && worker_pid_matches "$pid"; then
    mkdir -p "$RUN_DIR"
    echo "$pid" > "$WORKER_PIDFILE"
    echo "$pid"
    return 0
  fi

  return 1
}

find_supervisor_pid() {
  local pid

  pid="$(read_pid "$SUPERVISOR_PIDFILE" 2>/dev/null || true)"
  if pid_alive "$pid" && supervisor_pid_matches "$pid"; then
    echo "$pid"
    return 0
  fi
  cleanup_pidfile "$SUPERVISOR_PIDFILE" "$pid"

  while IFS= read -r pid; do
    if pid_alive "$pid" && supervisor_pid_matches "$pid"; then
      mkdir -p "$RUN_DIR"
      echo "$pid" > "$SUPERVISOR_PIDFILE"
      echo "$pid"
      return 0
    fi
  done < <(pgrep -f "$SELF_BASENAME keepalive" 2>/dev/null || true)

  return 1
}

start_worker() {
  local pid

  if pid="$(find_worker_pid)"; then
    log "local api already running pid=$pid"
    return 0
  fi

  if [ ! -f "$WORKER_SCRIPT" ]; then
    log "local api script not found: $WORKER_SCRIPT"
    return 1
  fi

  if [ -z "$PYTHON_BIN" ]; then
    log "python3 not found in PATH"
    return 1
  fi

  mkdir -p "$LOG_DIR" "$RUN_DIR"

  (
    cd "$APP_DIR" || exit 1
    local_pythonpath="$PYDEPS_DIR"
    if [ -d "$PLUGIN_DIR" ]; then
      local_pythonpath="$local_pythonpath:$PLUGIN_DIR"
    fi
    export PYTHONPATH="$local_pythonpath${PYTHONPATH:+:$PYTHONPATH}"
    export MUSIC_LOCAL_API_HOST="$LOCAL_API_HOST"
    export MUSIC_LOCAL_API_PORT="$LOCAL_API_PORT"
    export MUSIC_MIHOMO_CONFIG_FILE="${MUSIC_MIHOMO_CONFIG_FILE:-/data/local/mihomo/config.yaml}"
    export MUSIC_MIHOMO_CONTROLLER_URL="${MUSIC_MIHOMO_CONTROLLER_URL:-http://127.0.0.1:10097}"
    export MUSIC_MIHOMO_SELECTOR_NAME="${MUSIC_MIHOMO_SELECTOR_NAME:-PROXY}"
    export NO_PROXY="${NO_PROXY:-127.0.0.1,localhost}"
    export no_proxy="${no_proxy:-$NO_PROXY}"
    exec "$PYTHON_BIN" -u "$WORKER_SCRIPT"
  ) >> "$WORKER_LOG" 2>&1 &

  pid=$!
  echo "$pid" > "$WORKER_PIDFILE"
  log "started local api pid=$pid python=$PYTHON_BIN"
  return 0
}

ensure_worker() {
  local pid

  pid="$(find_worker_pid 2>/dev/null || true)"
  if pid_alive "$pid" && api_healthy; then
    return 0
  fi

  if pid_alive "$pid"; then
    log "local api pid=$pid failed health check url=$LOCAL_API_HEALTH_URL, restarting"
    kill "$pid" 2>/dev/null || true
    if ! wait_pid_stopped "$pid"; then
      log "local api did not stop within timeout pid=$pid"
    fi
    cleanup_pidfile "$WORKER_PIDFILE" "$pid"
  fi

  start_worker || return 1
  sleep "$STARTUP_DELAY"

  if wait_worker_healthy; then
    return 0
  fi

  pid="$(find_worker_pid 2>/dev/null || true)"
  if pid_alive "$pid"; then
    kill "$pid" 2>/dev/null || true
    cleanup_pidfile "$WORKER_PIDFILE" "$pid"
  fi

  log "local api failed to come up health=$LOCAL_API_HEALTH_URL"
  return 1
}

start_supervisor() {
  local pid

  require_tools || return 1

  if pid="$(find_supervisor_pid)"; then
    log "supervisor already running pid=$pid"
    echo "supervisor running pid=$pid"
    return 0
  fi

  mkdir -p "$LOG_DIR" "$RUN_DIR"
  clear_keepalive_failure_marker
  setsid -f /bin/bash -lc "exec \"$SELF_SCRIPT\" keepalive" >/dev/null 2>&1
  sleep 1

  pid="$(find_supervisor_pid 2>/dev/null || true)"
  if pid_alive "$pid"; then
    log "started supervisor pid=$pid"
    echo "supervisor started pid=$pid"
    return 0
  fi

  log "failed to start supervisor"
  echo "failed to start supervisor" >&2
  return 1
}

stop_all() {
  local pid
  local supervisor_pid

  clear_keepalive_failure_marker

  supervisor_pid="$(find_supervisor_pid 2>/dev/null || true)"
  if pid_alive "$supervisor_pid"; then
    kill "$supervisor_pid" 2>/dev/null || true
    log "stopped supervisor pid=$supervisor_pid"
    if ! wait_pid_stopped "$supervisor_pid"; then
      log "supervisor did not stop within timeout pid=$supervisor_pid"
    fi
  fi
  cleanup_pidfile "$SUPERVISOR_PIDFILE" "$supervisor_pid"

  pid="$(find_worker_pid 2>/dev/null || true)"
  if pid_alive "$pid"; then
    kill "$pid" 2>/dev/null || true
    log "stopped local api pid=$pid"
    if ! wait_pid_stopped "$pid"; then
      log "local api did not stop within timeout pid=$pid"
    fi
  fi
  cleanup_pidfile "$WORKER_PIDFILE" "$pid"
}

show_status() {
  local worker_pid=""
  local supervisor_pid=""

  supervisor_pid="$(find_supervisor_pid 2>/dev/null || true)"
  worker_pid="$(find_worker_pid 2>/dev/null || true)"

  if pid_alive "$supervisor_pid"; then
    echo "supervisor: running pid=$supervisor_pid"
  else
    echo "supervisor: stopped"
  fi

  if pid_alive "$worker_pid"; then
    echo "local_api: running pid=$worker_pid"
  else
    echo "local_api: stopped"
  fi

  if [ -f "$KEEPALIVE_FAILURE_FILE" ]; then
    echo "keepalive: stopped-after-failures marker=$KEEPALIVE_FAILURE_FILE"
  else
    echo "keepalive: enabled max_failures=$MAX_KEEPALIVE_FAILURES"
  fi
}

keepalive_loop() {
  local failure_count=0

  mkdir -p "$LOG_DIR" "$RUN_DIR"
  exec 9>"$LOCK_FILE"
  if ! flock -n 9; then
    log "another supervisor loop already running lock=$LOCK_FILE"
    exit 0
  fi

  trap 'rc=$?; log "supervisor exiting pid=$$ rc=$rc"; cleanup_pidfile "$SUPERVISOR_PIDFILE" "$$"; exit "$rc"' INT TERM EXIT
  echo "$$" > "$SUPERVISOR_PIDFILE"
  clear_keepalive_failure_marker
  log "supervisor loop started pid=$$ interval=${CHECK_INTERVAL}s max_failures=${MAX_KEEPALIVE_FAILURES} health=$LOCAL_API_HEALTH_URL"

  while true; do
    if ensure_worker; then
      if [ "$failure_count" -gt 0 ]; then
        log "local api recovered after ${failure_count} failed keepalive attempt(s)"
      fi
      failure_count=0
    else
      failure_count=$((failure_count + 1))
      log "local api keepalive failed count=${failure_count}/${MAX_KEEPALIVE_FAILURES}"
      if [ "$MAX_KEEPALIVE_FAILURES" -gt 0 ] && [ "$failure_count" -ge "$MAX_KEEPALIVE_FAILURES" ]; then
        log "max keepalive failures reached count=${failure_count}, stopping supervisor"
        write_keepalive_failure_marker "$failure_count"
        exit 1
      fi
    fi
    sleep "$CHECK_INTERVAL"
  done
}

case "${1:-start}" in
  start)
    start_supervisor
    ;;
  keepalive)
    keepalive_loop
    ;;
  restart)
    stop_all
    start_supervisor
    ;;
  stop)
    stop_all
    ;;
  status)
    show_status
    ;;
  start-api|start-worker)
    start_worker
    ;;
  *)
    echo "usage: $0 {start|keepalive|restart|stop|status|start-api}" >&2
    exit 1
    ;;
esac
