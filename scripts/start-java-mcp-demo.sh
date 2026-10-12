#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"

if command -v docker >/dev/null 2>&1; then
  DOCKER=docker
elif [ -x "${HOME}/.local/bin/docker" ]; then
  DOCKER="${HOME}/.local/bin/docker"
else
  echo "Docker CLI was not found." >&2
  exit 127
fi

# Day 7 changes no Java runtime source. Reuse the locally verified Day 6 image
# when present; a clean checkout still performs the normal Maven image build.
if "$DOCKER" image inspect autocve-java-agent-service:day6-test >/dev/null 2>&1; then
  "$DOCKER" build \
    --file java-agent-service/Dockerfile.prebuilt \
    --tag autocve-java-agent-service:day7 \
    java-agent-service
  ./scripts/docker-compose-local.sh --profile java-agent build \
    autocve-mcp-server frontend
else
  ./scripts/docker-compose-local.sh --profile java-agent build \
    java-agent-service autocve-mcp-server frontend
fi
# `sandbox` is an on-demand utility container and intentionally exits after
# preparing its image. Start only long-running demo services so `--wait` does
# not mistake that successful exit for a failed deployment.
exec ./scripts/docker-compose-local.sh --profile java-agent up -d --wait \
  db redis backend agent-worker one-click-cve-worker adminer \
  autocve-mcp-server mcp-scan-worker java-agent-service frontend
