#!/usr/bin/env bash

set -Eeuo pipefail

exec 3<>/dev/tcp/127.0.0.1/9000
printf 'GET /health/ready HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
response="$(cat <&3)"

grep -Eq '^HTTP/1\.[01] 200([[:space:]]|$)' <<<"${response}"
grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' <<<"${response}"
