#!/usr/bin/env bash
# Back up the engram LMDB data directory. Run it from cron on the host or inside
# the container. LMDB keeps a single data file that copies consistently under
# normal operation; writes here are sparse (1-10 users), so a tar snapshot is
# safe in practice. For a strict hot copy, quiesce writes or use `dtlv copy`.
#
# Example cron (daily at 03:30, keep the last 14):
#   30 3 * * *  /usr/local/bin/engram-backup /backups >> /var/log/engram-backup.log 2>&1
set -euo pipefail

DATA_DIR="${ENGRAM_DATA_DIR:-/data/engram}"
DEST="${1:-/backups}"
KEEP="${ENGRAM_BACKUP_KEEP:-14}"
STAMP="$(date +%Y%m%d-%H%M%S)"

mkdir -p "$DEST"
tar -czf "$DEST/engram-$STAMP.tar.gz" -C "$(dirname "$DATA_DIR")" "$(basename "$DATA_DIR")"

# Keep only the most recent $KEEP archives.
ls -1t "$DEST"/engram-*.tar.gz 2>/dev/null | tail -n +"$((KEEP + 1))" | xargs -r rm -f

echo "backup written: $DEST/engram-$STAMP.tar.gz"
