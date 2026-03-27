#!/bin/bash

set -u

APP_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
SELF_SCRIPT="$APP_DIR/$(basename "$0")"
SELF_BASENAME="$(basename "$SELF_SCRIPT")"
WORKER_SCRIPT="$APP_DIR/music_worker_ws.py"
PYDEPS_DIR="$APP_DIR/.pydeps"
PLUGIN_DIR="$APP_DIR/yt-dlp-plugins"
LOG_DIR="$APP_DIR/logs"
RUN_DIR="$APP_DIR/run"
WORKER_LOG="$LOG_DIR/music_worker.stdout.log"
SUPERVISOR_LOG="$LOG_DIR/music_worker_supervisor.log"
WORKER_PIDFILE="$RUN_DIR/music_worker.pid"
SUPERVISOR_PIDFILE="$RUN_DIR/music_worker_supervisor.pid"
PYTHON_BIN="${PYTHON_BIN:-$(command -v python3 || true)}"
CHECK_INTERVAL="${CHECK_INTERVAL:-20}"
STARTUP_DELAY="${STARTUP_DELAY:-2}"
LOCK_FILE="$RUN_DIR/music_worker_supervisor.lock"

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
    log "worker already running pid=$pid"
    return 0
  fi

  if [ ! -f "$WORKER_SCRIPT" ]; then
    log "worker script not found: $WORKER_SCRIPT"
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
    exec "$PYTHON_BIN" -u "$WORKER_SCRIPT"
  ) >> "$WORKER_LOG" 2>&1 &

  pid=$!
  echo "$pid" > "$WORKER_PIDFILE"
  log "started worker pid=$pid python=$PYTHON_BIN"
  return 0
}

ensure_worker() {
  local pid

  if pid="$(find_worker_pid)"; then
    return 0
  fi

  start_worker || return 1
  sleep "$STARTUP_DELAY"

  if pid="$(find_worker_pid)"; then
    log "worker healthy pid=$pid"
    return 0
  fi

  log "worker failed to come up"
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

  pid="$(find_worker_pid 2>/dev/null || true)"
  if pid_alive "$pid"; then
    kill "$pid" 2>/dev/null || true
    log "stopped worker pid=$pid"
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
    echo "worker: running pid=$worker_pid"
  else
    echo "worker: stopped"
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
    ensure_worker || true
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
  start-worker)
    start_worker
    ;;
  *)
    echo "usage: $0 {start|keepalive|restart|stop|status|start-worker}" >&2
    exit 1
    ;;
esac
