#!/usr/bin/env bash

set -euo pipefail

exec 3<>/dev/tcp/127.0.0.1/8080
printf 'GET /actuator/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
response="$(cat <&3)"
exec 3<&-
exec 3>&-

grep -Eq '^HTTP/1\.[01] 200 ' <<<"${response}"
grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' <<<"${response}"
