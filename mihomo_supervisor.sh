#!/bin/bash

set -u

APP_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
SELF_SCRIPT="$APP_DIR/$(basename "$0")"
LOG_DIR="$APP_DIR/logs"
RUN_DIR="$APP_DIR/run"
MIHOMO_BIN="${MIHOMO_BIN:-/usr/local/bin/mihomo}"
MIHOMO_CONFIG_DIR="${MIHOMO_CONFIG_DIR:-/etc/mihomo}"
MIHOMO_CONFIG_FILE="${MIHOMO_CONFIG_FILE:-/etc/mihomo/config.yaml}"
MIHOMO_PORT="${MIHOMO_PORT:-7890}"
CONTROLLER_PORT="${CONTROLLER_PORT:-10097}"
CHECK_INTERVAL="${CHECK_INTERVAL:-20}"
STARTUP_DELAY="${STARTUP_DELAY:-2}"
CURL_BIN="${CURL_BIN:-$(command -v curl || true)}"
MIHOMO_LOG="$LOG_DIR/mihomo.stdout.log"
SUPERVISOR_LOG="$LOG_DIR/mihomo_supervisor.log"
MIHOMO_PIDFILE="$RUN_DIR/mihomo.pid"
SUPERVISOR_PIDFILE="$RUN_DIR/mihomo_supervisor.pid"
LOCK_FILE="$RUN_DIR/mihomo_supervisor.lock"
UI_HEALTH_URL="${UI_HEALTH_URL:-http://127.0.0.1:${CONTROLLER_PORT}/ui/}"

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

process_args() {
  local pid="${1:-}"
  [ -n "$pid" ] || return 1
  ps -o args= -p "$pid" 2>/dev/null | head -n 1
}

port_listening() {
  local port="$1"
  ss -ltn 2>/dev/null | grep -q ":${port} "
}

ui_healthy() {
  [ -n "$CURL_BIN" ] || return 1
  "$CURL_BIN" -fsS --max-time 8 -o /dev/null "$UI_HEALTH_URL" >/dev/null 2>&1
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

supervisor_pid_matches() {
  local pid="${1:-}"
  local args

  args="$(process_args "$pid" 2>/dev/null || true)"
  case "$args" in
    "/bin/bash $SELF_SCRIPT keepalive"|\
    "bash $SELF_SCRIPT keepalive"|\
    "$SELF_SCRIPT keepalive")
      return 0
      ;;
  esac
  return 1
}

find_mihomo_pid() {
  local pid

  pid="$(read_pid "$MIHOMO_PIDFILE" 2>/dev/null || true)"
  if pid_alive "$pid"; then
    echo "$pid"
    return 0
  fi

  pid="$(pgrep -fo -f "${MIHOMO_BIN} .*${MIHOMO_CONFIG_FILE}" 2>/dev/null || true)"
  if pid_alive "$pid"; then
    mkdir -p "$RUN_DIR"
    echo "$pid" > "$MIHOMO_PIDFILE"
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
  done < <(pgrep -f "$SELF_SCRIPT keepalive" 2>/dev/null || true)

  return 1
}

test_config() {
  if [ ! -x "$MIHOMO_BIN" ]; then
    log "mihomo binary not found: $MIHOMO_BIN"
    return 1
  fi

  if [ ! -f "$MIHOMO_CONFIG_FILE" ]; then
    log "mihomo config not found: $MIHOMO_CONFIG_FILE"
    return 1
  fi

  "$MIHOMO_BIN" -t -d "$MIHOMO_CONFIG_DIR" -f "$MIHOMO_CONFIG_FILE" >> "$SUPERVISOR_LOG" 2>&1
}

start_mihomo() {
  local pid

  if pid="$(find_mihomo_pid)"; then
    log "mihomo already running pid=$pid"
    return 0
  fi

  if ! test_config; then
    log "mihomo config test failed"
    return 1
  fi

  mkdir -p "$LOG_DIR" "$RUN_DIR"
  nohup "$MIHOMO_BIN" -d "$MIHOMO_CONFIG_DIR" -f "$MIHOMO_CONFIG_FILE" >> "$MIHOMO_LOG" 2>&1 &
  pid=$!
  echo "$pid" > "$MIHOMO_PIDFILE"
  log "started mihomo pid=$pid config=$MIHOMO_CONFIG_FILE"
  return 0
}

ensure_mihomo() {
  local pid

  pid="$(find_mihomo_pid 2>/dev/null || true)"
  if pid_alive "$pid" && port_listening "$MIHOMO_PORT" && ui_healthy; then
    return 0
  fi

  if pid_alive "$pid"; then
    log "mihomo pid=$pid failed health check port=$MIHOMO_PORT ui=$UI_HEALTH_URL, restarting"
    kill "$pid" 2>/dev/null || true
    cleanup_pidfile "$MIHOMO_PIDFILE" "$pid"
    sleep 1
  fi

  start_mihomo || return 1
  sleep "$STARTUP_DELAY"

  pid="$(find_mihomo_pid 2>/dev/null || true)"
  if pid_alive "$pid" && port_listening "$MIHOMO_PORT" && ui_healthy; then
    log "mihomo healthy pid=$pid port=$MIHOMO_PORT controller=$CONTROLLER_PORT ui=$UI_HEALTH_URL"
    return 0
  fi

  log "mihomo failed to come up on port $MIHOMO_PORT with healthy ui=$UI_HEALTH_URL"
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

  supervisor_pid="$(find_supervisor_pid 2>/dev/null || true)"
  if pid_alive "$supervisor_pid"; then
    kill "$supervisor_pid" 2>/dev/null || true
    log "stopped supervisor pid=$supervisor_pid"
  fi
  cleanup_pidfile "$SUPERVISOR_PIDFILE" "$supervisor_pid"

  pid="$(find_mihomo_pid 2>/dev/null || true)"
  if pid_alive "$pid"; then
    kill "$pid" 2>/dev/null || true
    log "stopped mihomo pid=$pid"
  fi
  cleanup_pidfile "$MIHOMO_PIDFILE" "$pid"
}

show_status() {
  local pid=""
  local supervisor_pid=""

  supervisor_pid="$(find_supervisor_pid 2>/dev/null || true)"
  if pid_alive "$supervisor_pid"; then
    echo "supervisor: running pid=$supervisor_pid"
  else
    echo "supervisor: stopped"
  fi

  pid="$(find_mihomo_pid 2>/dev/null || true)"
  if pid_alive "$pid"; then
    echo "mihomo: running pid=$pid"
  else
    echo "mihomo: stopped"
  fi

  if port_listening "$MIHOMO_PORT"; then
    echo "port-${MIHOMO_PORT}: listening"
  else
    echo "port-${MIHOMO_PORT}: stopped"
  fi

  if port_listening "$CONTROLLER_PORT"; then
    echo "port-${CONTROLLER_PORT}: listening"
  else
    echo "port-${CONTROLLER_PORT}: stopped"
  fi

  if ui_healthy; then
    echo "ui: healthy url=$UI_HEALTH_URL"
  else
    echo "ui: unhealthy url=$UI_HEALTH_URL"
  fi
}

keepalive_loop() {
  mkdir -p "$LOG_DIR" "$RUN_DIR"
  exec 9>"$LOCK_FILE"
  if ! flock -n 9; then
    log "another supervisor loop already running lock=$LOCK_FILE"
    exit 0
  fi

  trap 'log "supervisor exiting pid=$$"; cleanup_pidfile "$SUPERVISOR_PIDFILE" "$$"; exit 0' INT TERM EXIT
  echo "$$" > "$SUPERVISOR_PIDFILE"
  log "supervisor loop started pid=$$ interval=${CHECK_INTERVAL}s"

  while true; do
    ensure_mihomo || true
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
  start-mihomo)
    start_mihomo
    ;;
  *)
    echo "usage: $0 {start|keepalive|restart|stop|status|start-mihomo}" >&2
    exit 1
    ;;
esac
