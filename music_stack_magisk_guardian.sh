#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

BASE_DIR_RAW="$(CDPATH= cd -- "$(dirname "$0")" && pwd -P)"
BASE_DIR="$BASE_DIR_RAW"
if [[ "$BASE_DIR_RAW" == /codes* ]] && [[ -d "/root$BASE_DIR_RAW" ]]; then
  BASE_DIR="/root$BASE_DIR_RAW"
fi
SELF_PATH="$BASE_DIR/$(basename "$0")"

PID_FILE="/data/adb/music_stack_magisk_guardian.pid"
LOCK_FILE="/data/adb/music_stack_magisk_guardian.lock"
LOG_FILE="/data/adb/music_stack_magisk_guardian.log"

BASH_BIN="${BASH_BIN:-/data/data/com.termux/files/usr/bin/bash}"
FLOCK_BIN="${FLOCK_BIN:-/data/data/com.termux/files/usr/bin/flock}"
NOHUP_BIN="${NOHUP_BIN:-/data/data/com.termux/files/usr/bin/nohup}"
PGREP_BIN="${PGREP_BIN:-/data/data/com.termux/files/usr/bin/pgrep}"
INTERVAL="${MUSIC_STACK_MAGISK_GUARDIAN_INTERVAL:-20}"

ROOT_WATCHDOG="$BASE_DIR/music_stack_root_watchdog.sh"

timestamp() {
  date "+%F %T %z"
}

log() {
  mkdir -p /data/adb
  echo "[$(timestamp)] $*" >> "$LOG_FILE"
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

is_self_process() {
  local pid="$1"
  local -a args
  local i

  [[ -r "/proc/$pid/cmdline" ]] || return 1
  mapfile -d '' -t args < "/proc/$pid/cmdline" || return 1

  for i in "${!args[@]}"; do
    if [[ "${args[$i]}" == "$SELF_PATH" ]]; then
      if (( i + 1 < ${#args[@]} )) && [[ "${args[$((i + 1))]}" == "run" ]]; then
        return 0
      fi
    fi
  done

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

find_running_pid() {
  local pid

  pid="$(read_pid "$PID_FILE" 2>/dev/null || true)"
  if pid_alive "$pid" && is_self_process "$pid"; then
    echo "$pid"
    return 0
  fi
  cleanup_pidfile "$PID_FILE" "$pid"

  while IFS= read -r pid; do
    if pid_alive "$pid" && is_self_process "$pid"; then
      mkdir -p /data/adb
      echo "$pid" > "$PID_FILE"
      echo "$pid"
      return 0
    fi
  done < <("$PGREP_BIN" -f "$SELF_PATH run" 2>/dev/null || true)

  return 1
}

require_tools() {
  [[ -x "$BASH_BIN" ]] || { echo "bash not found: $BASH_BIN"; exit 1; }
  [[ -x "$FLOCK_BIN" ]] || { echo "flock not found: $FLOCK_BIN"; exit 1; }
  [[ -x "$NOHUP_BIN" ]] || { echo "nohup not found: $NOHUP_BIN"; exit 1; }
  [[ -x "$PGREP_BIN" ]] || { echo "pgrep not found: $PGREP_BIN"; exit 1; }
  [[ -x "$ROOT_WATCHDOG" ]] || { echo "root watchdog not found: $ROOT_WATCHDOG"; exit 1; }
}

root_watchdog_running() {
  "$BASH_BIN" "$ROOT_WATCHDOG" status >/dev/null 2>&1
}

ensure_root_watchdog_started() {
  if root_watchdog_running; then
    return 0
  fi

  log "root watchdog missing, starting"

  if "$BASH_BIN" "$ROOT_WATCHDOG" start >> "$LOG_FILE" 2>&1; then
    sleep 2
    if root_watchdog_running; then
      log "root watchdog healthy"
      return 0
    fi
  fi

  log "failed to restore root watchdog"
  return 1
}

run_once() {
  ensure_root_watchdog_started || true
}

run_loop() {
  mkdir -p /data/adb
  exec 9>"$LOCK_FILE"
  if ! "$FLOCK_BIN" -n 9; then
    echo "another magisk guardian process holds lock: $LOCK_FILE"
    exit 0
  fi

  echo "$$" > "$PID_FILE"
  trap 'rm -f "$PID_FILE"' EXIT

  log "magisk guardian loop started pid=$$ interval=${INTERVAL}s"

  while true; do
    run_once
    sleep "$INTERVAL"
  done
}

start_daemon() {
  local pid

  require_tools

  if pid="$(find_running_pid)"; then
    echo "running (pid=$pid, interval=${INTERVAL}s)"
    return 0
  fi

  mkdir -p /data/adb
  "$NOHUP_BIN" "$BASH_BIN" "$SELF_PATH" run >/dev/null 2>&1 &
  sleep 1
  status_daemon
}

stop_daemon() {
  local pid

  pid="$(find_running_pid 2>/dev/null || true)"
  if [ -n "${pid:-}" ]; then
    kill "$pid" 2>/dev/null || true
    sleep 1
    if kill -0 "$pid" 2>/dev/null; then
      kill -9 "$pid" 2>/dev/null || true
    fi
    echo "stopped (pid=$pid)"
  else
    echo "not running"
  fi

  rm -f "$PID_FILE"
}

status_daemon() {
  local pid

  pid="$(find_running_pid 2>/dev/null || true)"
  if [ -n "${pid:-}" ]; then
    echo "running (pid=$pid, interval=${INTERVAL}s)"
  else
    echo "not running"
    return 1
  fi
}

usage() {
  cat <<'EOF'
Usage: music_stack_magisk_guardian.sh {start|stop|status|once|run}
  start   Start Magisk-layer guardian daemon
  stop    Stop Magisk-layer guardian daemon
  status  Show Magisk-layer guardian status
  once    Run one Magisk-layer health-check cycle
  run     Internal loop mode (daemon body)
EOF
}

cmd="${1:-status}"
case "$cmd" in
  start)
    start_daemon
    ;;
  stop)
    stop_daemon
    ;;
  status)
    status_daemon
    ;;
  once)
    require_tools
    run_once
    echo "done"
    ;;
  run)
    require_tools
    run_loop
    ;;
  *)
    usage
    exit 1
    ;;
esac
