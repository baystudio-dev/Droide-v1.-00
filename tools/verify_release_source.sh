#!/usr/bin/env bash
set -euo pipefail
source_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$source_root"
export PYTHONDONTWRITEBYTECODE=1
python3 -B tools/verify_repository.py
python3 -B tools/verify_source_release.py inputs --root "$source_root"
python3 -B tools/verify_android_toolchain_catalogs.py
python3 -B tools/verify_droide_cli_client.py
python3 -B tools/verify_droide_icons.py
python3 -B tools/generate_provider_logos.py --check
python3 -B tools/generate_document_file_icons.py --check
python3 -B -m unittest discover -s tools/tests -p 'test_*.py' -v
printf '%s\n' RELEASE_SOURCE_VERIFICATION_OK
