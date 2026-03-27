#!/system/bin/sh
# Magisk service.d: start the mihomo supervisor from the Debian proot.

LOG_FILE="/data/adb/mihomo_keepalive_boot.log"
PROOT_DISTRO="/data/data/com.termux/files/usr/bin/proot-distro"
DISTRO_NAME="${DISTRO_NAME:-debian-nolocale}"
KEEPALIVE_CMD="cd /codes/music_worker && /bin/bash ./mihomo_supervisor.sh start"
BOOT_DELAY="${BOOT_DELAY:-15}"

is_nested_proot() {
  tracer_pid="$(grep TracerPid /proc/$$/status 2>/dev/null | awk '{print $2}')"
  [ -n "$tracer_pid" ] || return 1
  [ "$tracer_pid" != "0" ] || return 1
  tracer_name="$(cat /proc/$tracer_pid/comm 2>/dev/null || true)"
  [ "$tracer_name" = "proot" ]
}

mkdir -p /data/adb 2>/dev/null || true

{
  echo "[$(date '+%F %T %z')] mihomo boot hook start"
  sleep "$BOOT_DELAY"

  if is_nested_proot; then
    echo "nested proot detected, starting mihomo supervisor directly"
    /bin/bash -lc "$KEEPALIVE_CMD"
  else
    if [ ! -x "$PROOT_DISTRO" ]; then
      echo "proot-distro not found: $PROOT_DISTRO"
      exit 0
    fi
    "$PROOT_DISTRO" login "$DISTRO_NAME" -- /bin/bash -lc "$KEEPALIVE_CMD"
  fi

  rc=$?
  echo "[$(date '+%F %T %z')] mihomo hook finished, rc=$rc"
} >> "$LOG_FILE" 2>&1
