#!/usr/bin/env bash
# Qualifies docweave-source-s3 against SeaweedFS, an S3-compatible service that enforces per-identity access.
# Starts an isolated, loopback-only container with throwaway identities (admin, reader, denied), runs the
# S3-compatible integration tests against it, and removes the container. No AWS account or network service is used.
#
# Usage: SEAWEEDFS_IMAGE=<pinned image> benchmarks/review/remote-sources/seaweedfs-qualification.sh [extra mvn args]
# Requires podman (or CONTAINER=docker) and a reactor that is already installed or buildable offline.
set -euo pipefail
image="${SEAWEEDFS_IMAGE:?Set SEAWEEDFS_IMAGE to a pinned SeaweedFS image}"
container="${CONTAINER:-podman}"
work="$(mktemp -d)"
chmod 700 "$work"
name="docweave-seaweedfs-$$"
cleanup() { "$container" rm -f "$name" >/dev/null 2>&1 || true; rm -rf "$work"; }
trap cleanup EXIT

key() { head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20; }
admin_key="$(key)"; admin_secret="$(key)$(key)"
reader_key="$(key)"; reader_secret="$(key)$(key)"
denied_key="$(key)"; denied_secret="$(key)$(key)"
cat > "$work/s3.json" <<JSON
{"identities": [
  {"name": "admin", "credentials": [{"accessKey": "$admin_key", "secretKey": "$admin_secret"}],
   "actions": ["Admin", "Read", "Write", "List", "Tagging"]},
  {"name": "reader", "credentials": [{"accessKey": "$reader_key", "secretKey": "$reader_secret"}],
   "actions": ["Read", "List"]},
  {"name": "denied", "credentials": [{"accessKey": "$denied_key", "secretKey": "$denied_secret"}],
   "actions": ["Read:docweave-no-access", "List:docweave-no-access"]}
]}
JSON
chmod 644 "$work/s3.json"
port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
"$container" run -d --name "$name" -p "127.0.0.1:${port}:8333" -v "$work/s3.json:/etc/seaweedfs/s3.json:ro,z" \
    "$image" server -dir=/data -s3 -s3.port=8333 -s3.config=/etc/seaweedfs/s3.json \
    -master.volumeSizeLimitMB=32 -volume.max=200 >/dev/null
endpoint="http://127.0.0.1:${port}"
for _ in $(seq 1 90); do
    status="$(curl -s -o /dev/null -w '%{http_code}' "$endpoint/" || true)"
    if [ "$status" != "000" ]; then break; fi
    sleep 1
done
echo "SeaweedFS ready at $endpoint (image $image)"
mvn -B -ntp -o -pl docweave-source-s3 test -Dtest=S3CompatibleServiceIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Ddocweave.test.seaweedfs.endpoint="$endpoint" \
    -Ddocweave.test.seaweedfs.versioning="${SEAWEEDFS_VERSIONING:-true}" \
    -Ddocweave.test.seaweedfs.admin.accessKey="$admin_key" -Ddocweave.test.seaweedfs.admin.secretKey="$admin_secret" \
    -Ddocweave.test.seaweedfs.reader.accessKey="$reader_key" -Ddocweave.test.seaweedfs.reader.secretKey="$reader_secret" \
    -Ddocweave.test.seaweedfs.denied.accessKey="$denied_key" -Ddocweave.test.seaweedfs.denied.secretKey="$denied_secret" \
    "$@"
