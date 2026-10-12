#!/bin/sh
set -eu

# Colima installed by the local setup keeps the Docker CLI in ~/.local/bin.
# Fall back to that location so project commands also work in non-login shells.
if ! command -v docker >/dev/null 2>&1; then
  if [ -x "${HOME}/.local/bin/docker" ]; then
    PATH="${HOME}/.local/bin:${PATH}"
    export PATH
  else
    echo "Docker CLI was not found in PATH or ${HOME}/.local/bin." >&2
    exit 127
  fi
fi

exec docker compose "$@"
