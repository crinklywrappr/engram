#!/usr/bin/env bash
# Back up the engram LMDB data directory. Run it from cron on the host. engram
# does not manage its own backups. The container writes LMDB to the host data
# directory through a bind mount, so this script tars that host path directly,
# with no container involvement. LMDB keeps a single data file that copies
# consistently under normal operation. Writes here are sparse (1-10 users), so a
# tar snapshot is safe in practice. For a strict hot copy, quiesce writes or use
# `dtlv copy`.
#
# The defaults match the deploy layout. The container mounts /srv/engram/data as
# /data, and the server writes /data/engram, so the host path is
# /srv/engram/data/engram.
#
# Install this script on the host, then add a cron line (daily at 03:30, keep
# the last 14):
#   30 3 * * *  /usr/local/bin/engram-backup >> /var/log/engram-backup.log 2>&1
set -euo pipefail

DATA_DIR="${ENGRAM_DATA_DIR:-/srv/engram/data/engram}"
DEST="${1:-/srv/engram/backups}"
KEEP="${ENGRAM_BACKUP_KEEP:-14}"
STAMP="$(date +%Y%m%d-%H%M%S)"

mkdir -p "$DEST"
tar -czf "$DEST/engram-$STAMP.tar.gz" -C "$(dirname "$DATA_DIR")" "$(basename "$DATA_DIR")"

# Keep only the most recent $KEEP archives.
ls -1t "$DEST"/engram-*.tar.gz 2>/dev/null | tail -n +"$((KEEP + 1))" | xargs -r rm -f

echo "backup written: $DEST/engram-$STAMP.tar.gz"
