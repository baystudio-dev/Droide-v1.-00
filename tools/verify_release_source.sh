#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

fail() { echo "RELEASE_SOURCE_FAILED: $*" >&2; exit 1; }

[[ -x ./gradlew ]] || fail "gradlew is not executable"
[[ -f gradle/wrapper/gradle-wrapper.jar ]] || fail "missing gradle-wrapper.jar"
[[ -f LICENSE ]] || fail "missing root LICENSE"
[[ -f LICENSE_SCOPE.md ]] || fail "missing LICENSE_SCOPE.md"
[[ -f app/src/main/assets/legal/DROIDE_LICENSE.md ]] || fail "missing packaged Droide license"
python3 tools/verify_first_party_license.py
grep -qi 'Third-party .*remain under their respective licenses\|Third-party .*remain under their own licenses' LICENSE_SCOPE.md || fail "license scope boundary missing"

if find . -path './.git' -prune -o -type f \
  \( -name '*.apk' -o -name '*.aab' -o -name '*.jks' -o -name '*.keystore' -o -name '*.p12' -o -name '.env' -o -name 'auth.json' \) \
  -print -quit | grep -q .; then fail "generated binary or credential material present"; fi

if find . -path './.git' -prune -o -type d \
  \( -name build -o -name .gradle -o -name .kotlin -o -name .cxx -o -name .externalNativeBuild -o -name .idea -o -name __pycache__ -o -name .pytest_cache \) \
  -print -quit | grep -q .; then fail "build/cache directory present"; fi

# Check first-party release text without treating upstream copyrights, hashes or
# CPython wheel tags as internal development history.
python3 -B tools/verify_source_hygiene.py

if grep -RInE --exclude-dir=.git --exclude='*.md' --exclude='*.svg' \
  '(sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|ghp_[0-9A-Za-z]{20,})' . \
  >/tmp/droide-release-secret-hit 2>/dev/null; then
  cat /tmp/droide-release-secret-hit >&2; fail "credential signature detected"; fi

python3 tools/verify_dependency_policy.py
python3 tools/verify_artifact_transfer_manifest.py
python3 tools/verify_resolved_runtime_licenses.py
python3 tools/verify_runtime_licenses.py
python3 -B tools/generate_legal_browser_catalog.py --check
python3 tools/verify_android_toolchain_catalogs.py
python3 tools/verify_third_party_integrity.py
python3 tools/verify_termux_provenance.py
python3 tools/verify_bundled_runtime.py
python3 tools/verify_droide_cli_client.py
python3 tools/generate_provider_logos.py --check
[[ ! -e app/src/main/res/drawable-nodpi/provider_vllm.png ]] || fail "unlicensed vLLM media-kit logo must not be bundled"
grep -q '"vllm" -> R.drawable.provider_generic' app/src/main/java/com/baystudio/droide/ui/ProviderBrand.kt || fail "vLLM must use neutral provider glyph"
echo RELEASE_SOURCE_VERIFICATION_OK
