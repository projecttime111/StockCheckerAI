#!/usr/bin/env bash
# Starts the backend with the repository's ignored root .env file.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
env_file="$repo_root/.env"

if [[ ! -f "$env_file" ]]; then
  echo "Missing $env_file. Create it from .env.neon.example first." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

cd "$repo_root/backend"
exec ./mvnw spring-boot:run "$@"
