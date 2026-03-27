#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELF_PATH="$BASE_DIR/$(basename "${BASH_SOURCE[0]}")"

PID_FILE="/data/adb/music_stack_root_watchdog.pid"
LOCK_FILE="/data/adb/music_stack_root_watchdog.lock"
LOG_FILE="/data/adb/music_stack_root_watchdog.log"

BASH_BIN="${BASH_BIN:-/data/data/com.termux/files/usr/bin/bash}"
PROOT_DISTRO="${PROOT_DISTRO:-/data/data/com.termux/files/usr/bin/proot-distro}"
DISTRO_NAME="${DISTRO_NAME:-debian-nolocale}"
INTERVAL="${MUSIC_STACK_WATCHDOG_INTERVAL:-30}"
START_TIMEOUT="${MUSIC_STACK_WATCHDOG_START_TIMEOUT:-90}"

MUSIC_WORKER_SUPERVISOR="$BASE_DIR/music_worker_supervisor.sh"
MIHOMO_SUPERVISOR="$BASE_DIR/mihomo_supervisor.sh"

MUSIC_WORKER_START_CMD="cd \"$BASE_DIR\" && /bin/bash ./music_worker_supervisor.sh start"
MIHOMO_START_CMD="cd \"$BASE_DIR\" && /bin/bash ./mihomo_supervisor.sh start"

timestamp() {
  date "+%F %T %z"
}

require_tools() {
  command -v pgrep >/dev/null 2>&1 || { echo "pgrep not found"; exit 1; }
  command -v timeout >/dev/null 2>&1 || { echo "timeout not found"; exit 1; }
  command -v flock >/dev/null 2>&1 || { echo "flock not found"; exit 1; }
  [[ -x "$BASH_BIN" ]] || { echo "bash not found: $BASH_BIN"; exit 1; }
}

is_nested_proot() {
  local tracer_pid tracer_name

  tracer_pid="$(grep TracerPid /proc/$$/status 2>/dev/null | awk '{print $2}')"
  [[ -n "${tracer_pid:-}" ]] || return 1
  [[ "$tracer_pid" != "0" ]] || return 1
  tracer_name="$(cat "/proc/$tracer_pid/comm" 2>/dev/null || true)"
  [[ "$tracer_name" == "proot" ]]
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

find_running_pid() {
  local pid

  while read -r pid; do
    [[ -n "${pid:-}" ]] || continue
    if is_self_process "$pid"; then
      echo "$pid"
      return 0
    fi
  done < <(pgrep -f -- "$SELF_PATH" || true)

  return 1
}

is_running() {
  local pid

  if [[ -f "$PID_FILE" ]]; then
    pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [[ -n "${pid:-}" ]] && kill -0 "$pid" 2>/dev/null; then
      if is_self_process "$pid"; then
        return 0
      fi
    fi
  fi

  pid="$(find_running_pid || true)"
  if [[ -n "${pid:-}" ]]; then
    echo "$pid" > "$PID_FILE"
    return 0
  fi

  return 1
}

is_supervisor_process() {
  local pid="$1"
  local script_path="$2"
  local -a args
  local i

  [[ -r "/proc/$pid/cmdline" ]] || return 1
  mapfile -d '' -t args < "/proc/$pid/cmdline" || return 1

  for i in "${!args[@]}"; do
    if [[ "${args[$i]}" == "$script_path" ]]; then
      if (( i + 1 < ${#args[@]} )) && [[ "${args[$((i + 1))]}" == "keepalive" ]]; then
        return 0
      fi
    fi
  done

  return 1
}

find_supervisor_pid() {
  local script_path="$1"
  local pid

  while read -r pid; do
    [[ -n "${pid:-}" ]] || continue
    if is_supervisor_process "$pid" "$script_path"; then
      echo "$pid"
      return 0
    fi
  done < <(pgrep -f -- "$script_path" || true)

  return 1
}

run_target_cmd() {
  local cmd="$1"

  if is_nested_proot; then
    timeout "$START_TIMEOUT" /bin/bash -lc "$cmd"
    return $?
  fi

  [[ -x "$PROOT_DISTRO" ]] || {
    echo "proot-distro not found: $PROOT_DISTRO"
    return 1
  }

  timeout "$START_TIMEOUT" "$PROOT_DISTRO" login "$DISTRO_NAME" -- /bin/bash -lc "$cmd"
}

ensure_supervisor_started() {
  local name="$1"
  local script_path="$2"
  local start_cmd="$3"
  local pid
  local ts

  if pid="$(find_supervisor_pid "$script_path" 2>/dev/null || true)"; then
    [[ -n "${pid:-}" ]] && return 0
  fi

  ts="$(timestamp)"
  echo "[$ts] ${name} supervisor missing, starting" >> "$LOG_FILE"

  if run_target_cmd "$start_cmd" >> "$LOG_FILE" 2>&1; then
    sleep 2
    pid="$(find_supervisor_pid "$script_path" 2>/dev/null || true)"
    if [[ -n "${pid:-}" ]]; then
      echo "[$(timestamp)] ${name} supervisor healthy pid=$pid" >> "$LOG_FILE"
      return 0
    fi
  fi

  echo "[$(timestamp)] failed to restore ${name} supervisor" >> "$LOG_FILE"
  return 1
}

run_once() {
  ensure_supervisor_started "mihomo" "$MIHOMO_SUPERVISOR" "$MIHOMO_START_CMD" || true
  ensure_supervisor_started "music_worker" "$MUSIC_WORKER_SUPERVISOR" "$MUSIC_WORKER_START_CMD" || true
}

run_loop() {
  mkdir -p /data/adb
  exec 9>"$LOCK_FILE"
  if ! flock -n 9; then
    echo "another watchdog process holds lock: $LOCK_FILE"
    exit 0
  fi

  echo "$$" > "$PID_FILE"
  trap 'rm -f "$PID_FILE"' EXIT

  echo "[$(timestamp)] root watchdog loop started pid=$$ interval=${INTERVAL}s" >> "$LOG_FILE"

  while true; do
    run_once
    sleep "$INTERVAL"
  done
}

start_daemon() {
  require_tools

  if is_running; then
    local pid
    pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    echo "running (pid=$pid, interval=${INTERVAL}s)"
    return 0
  fi

  mkdir -p /data/adb
  setsid -f "$BASH_BIN" -lc "exec \"$SELF_PATH\" run >> \"$LOG_FILE\" 2>&1"
  sleep 1
  status_daemon
}

stop_daemon() {
  local pid

  if is_running; then
    pid="$(cat "$PID_FILE" 2>/dev/null || true)"
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

  if is_running; then
    pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    echo "running (pid=$pid, interval=${INTERVAL}s)"
  else
    echo "not running"
    return 1
  fi
}

usage() {
  cat <<'EOF'
Usage: music_stack_root_watchdog.sh {start|stop|status|once|run}
  start   Start root-side watchdog daemon
  stop    Stop root-side watchdog daemon
  status  Show root-side watchdog status
  once    Run one outer health-check cycle
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
    mkdir -p /data/adb
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
