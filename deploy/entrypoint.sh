#!/usr/bin/env bash
# Start the engram server on localhost, then run sshd in the foreground. Only
# the SSH port is exposed. Clients reach the REST API through engram-proxy, which
# sshd runs as a forced command per authorized_keys line.
set -euo pipefail

# Host keys (idempotent).
ssh-keygen -A

# sshd is strict about ownership and permissions of authorized_keys.
if [ -d /home/engram/.ssh ]; then
  chown engram:engram /home/engram/.ssh || true
  chmod 700 /home/engram/.ssh || true
fi
if [ -f /home/engram/.ssh/authorized_keys ]; then
  chmod 600 /home/engram/.ssh/authorized_keys || true
fi

# The server runs as engram and writes LMDB under /data. A bind mount arrives
# with the owner it had on the host, so take ownership here, while this entrypoint
# is still root. This removes any host-side uid requirement for the mounts.
chown -R engram:engram /data /backups || true

# The server writes LMDB under /data (owned by engram). Run it as engram.
# su resets PATH and drops the JDK bin, so call java by its absolute path.
su engram -s /bin/bash -c "${JAVA_HOME}/bin/java ${JAVA_OPTS} -jar /app/engram.jar" &

# sshd in the foreground is PID 1's child; -e logs to stderr.
exec /usr/sbin/sshd -D -e
