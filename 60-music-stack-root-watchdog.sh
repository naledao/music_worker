#!/system/bin/sh
# Magisk service.d: start the root-side music stack watchdog.

LOG_FILE="/data/adb/music_stack_root_watchdog_boot.log"
WATCHDOG_SCRIPT="/codes/music_worker/music_stack_root_watchdog.sh"
BASH_BIN="/data/data/com.termux/files/usr/bin/bash"
BOOT_DELAY="${BOOT_DELAY:-10}"

mkdir -p /data/adb 2>/dev/null || true

{
  echo "[$(date '+%F %T %z')] root watchdog boot hook start"
  sleep "$BOOT_DELAY"

  if [ ! -x "$BASH_BIN" ]; then
    echo "bash not found: $BASH_BIN"
    exit 0
  fi

  if [ ! -x "$WATCHDOG_SCRIPT" ]; then
    echo "watchdog script not found: $WATCHDOG_SCRIPT"
    exit 0
  fi

  "$BASH_BIN" "$WATCHDOG_SCRIPT" start

  rc=$?
  echo "[$(date '+%F %T %z')] root watchdog hook finished, rc=$rc"
} >> "$LOG_FILE" 2>&1
