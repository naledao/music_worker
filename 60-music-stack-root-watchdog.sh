#!/system/bin/sh
# Magisk service.d: start the root-side music stack watchdog.

LOG_FILE="/data/adb/music_stack_root_watchdog_boot.log"
CHROOT_DIR="${CHROOT_DIR:-/data/local/linux}"
WATCHDOG_SCRIPT="${WATCHDOG_SCRIPT:-/root/codes/music_worker/music_stack_root_watchdog.sh}"
CHROOT_BIN="${CHROOT_BIN:-/system/bin/chroot}"
BOOT_DELAY="${BOOT_DELAY:-10}"

mkdir -p /data/adb 2>/dev/null || true

{
  echo "[$(date '+%F %T %z')] root watchdog boot hook start"
  sleep "$BOOT_DELAY"

  if [ ! -x "$CHROOT_BIN" ]; then
    echo "chroot not found: $CHROOT_BIN"
    exit 0
  fi

  if [ ! -x "$CHROOT_DIR/bin/bash" ]; then
    echo "bash not found: $CHROOT_DIR/bin/bash"
    exit 0
  fi

  if [ ! -x "$CHROOT_DIR$WATCHDOG_SCRIPT" ]; then
    echo "watchdog script not found: $CHROOT_DIR$WATCHDOG_SCRIPT"
    exit 0
  fi

  "$CHROOT_BIN" "$CHROOT_DIR" /usr/bin/env \
    PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    /bin/bash "$WATCHDOG_SCRIPT" start </dev/null

  rc=$?
  echo "[$(date '+%F %T %z')] root watchdog hook finished, rc=$rc"
} >> "$LOG_FILE" 2>&1
