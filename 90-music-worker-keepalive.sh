#!/system/bin/sh
# Magisk service.d: start the local HTTP API supervisor from the Debian proot.

LOG_FILE="/data/adb/music_worker_keepalive_boot.log"
CHROOT_DIR="${CHROOT_DIR:-/data/local/linux}"
WORK_DIR="${WORK_DIR:-/root/codes/music_worker}"
CHROOT_BIN="${CHROOT_BIN:-/system/bin/chroot}"
KEEPALIVE_CMD="cd \"$WORK_DIR\" && /bin/bash ./music_worker_supervisor.sh start"
BOOT_DELAY="${BOOT_DELAY:-25}"

mkdir -p /data/adb 2>/dev/null || true

{
  echo "[$(date '+%F %T %z')] music worker boot hook start"
  sleep "$BOOT_DELAY"

  if [ ! -x "$CHROOT_BIN" ]; then
    echo "chroot not found: $CHROOT_BIN"
    exit 0
  fi

  if [ ! -x "$CHROOT_DIR/bin/bash" ]; then
    echo "bash not found: $CHROOT_DIR/bin/bash"
    exit 0
  fi

  if [ ! -x "$CHROOT_DIR$WORK_DIR/music_worker_supervisor.sh" ]; then
    echo "music worker supervisor not found: $CHROOT_DIR$WORK_DIR/music_worker_supervisor.sh"
    exit 0
  fi

  "$CHROOT_BIN" "$CHROOT_DIR" /usr/bin/env \
    PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    /bin/bash -lc "$KEEPALIVE_CMD" </dev/null

  rc=$?
  echo "[$(date '+%F %T %z')] music worker hook finished, rc=$rc"
} >> "$LOG_FILE" 2>&1
