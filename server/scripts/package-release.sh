#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p artifacts
tar -czf artifacts/varan-hub.tar.gz package.json package-lock.json seed-site.json .env.example README.md src public scripts ops
echo 'Created artifacts/varan-hub.tar.gz (no credentials, customer data or node_modules).'
