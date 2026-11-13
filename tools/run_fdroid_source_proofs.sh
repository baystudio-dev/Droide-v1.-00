#!/usr/bin/env bash
# Run one fixed proof in an offline, read-only OS sandbox. No outputs survive.
# Provision trusted tools separately; this runner never downloads or installs.
set -euo pipefail

usage() {
  printf '%s\n' \
    'Usage: bash tools/run_fdroid_source_proofs.sh MODE [options]' \
    'Modes: tests | cli | loaders | proot | spake2 | termux | view' \
    'Options: --ndk DIR --jdk DIR --jdk-archive FILE --android-jar FILE' \
    '         --annotation-jar FILE (view only) --loader-ndk DIR (proot only)' \
    '         --sudo (CI namespace setup only) --show-command (do not execute)'
}

fail() { printf 'SOURCE_PROOF_SANDBOX_FAILED: %s\n' "$*" >&2; exit 2; }
[[ $# -ge 1 ]] || { usage >&2; exit 2; }
mode=$1
shift
case "$mode" in tests|cli|loaders|proot|spake2|termux|view) ;; *) usage >&2; fail 'unknown proof mode' ;; esac

use_sudo=false
show_command=false
jdk_path=''
archive_path=''
android_path=''
ndk_path=''
loader_ndk_path=''
annotation_path=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --sudo) [[ $use_sudo == false ]] || fail 'duplicate --sudo'; use_sudo=true; shift ;;
    --show-command) [[ $show_command == false ]] || fail 'duplicate --show-command'; show_command=true; shift ;;
    --jdk|--jdk-archive|--android-jar|--ndk|--annotation-jar|--loader-ndk)
      [[ $# -ge 2 && -n $2 && $2 != --* ]] || fail "missing value for $1"
      case "$1" in
        --jdk) [[ -z $jdk_path ]] || fail 'duplicate --jdk'; jdk_path=$2 ;;
        --jdk-archive) [[ -z $archive_path ]] || fail 'duplicate --jdk-archive'; archive_path=$2 ;;
        --android-jar) [[ -z $android_path ]] || fail 'duplicate --android-jar'; android_path=$2 ;;
        --ndk) [[ -z $ndk_path ]] || fail 'duplicate --ndk'; ndk_path=$2 ;;
        --loader-ndk) [[ -z $loader_ndk_path ]] || fail 'duplicate --loader-ndk'; loader_ndk_path=$2 ;;
        --annotation-jar) [[ -z $annotation_path ]] || fail 'duplicate --annotation-jar'; annotation_path=$2 ;;
      esac
      shift 2 ;;
    *) fail "unknown option: $1" ;;
  esac
done
[[ $mode == view || -z $annotation_path ]] || fail '--annotation-jar is permitted only for view mode'
[[ $mode == proot || -z $loader_ndk_path ]] || fail '--loader-ndk is permitted only for proot mode'

script_path=$(/usr/bin/realpath -e -- "${BASH_SOURCE[0]}")
source_root=$(/usr/bin/realpath -e -- "$(/usr/bin/dirname -- "$script_path")/..")
[[ -f $source_root/tools/fdroid_cli_source_build.py ]] || fail 'repository root not found'

# Canonicalize only operator-supplied, already-provisioned tool inputs. These
# paths are not passed through a shell or exposed as writable sandbox mounts.
external_path() {
  local supplied=$1 kind=$2 resolved
  [[ $supplied == /* && $supplied != *$'\n'* && $supplied != *$'\r'* ]] || fail 'tool input must be an absolute path'
  resolved=$(/usr/bin/realpath -e -- "$supplied") || fail 'tool input does not exist'
  [[ $resolved != / && $resolved != "$source_root" && $resolved != "$source_root/"* && $source_root != "$resolved/"* ]] || fail 'tool input must be outside repository and its ancestors'
  if [[ $kind == directory ]]; then
    [[ -d $resolved ]] || fail 'expected tool directory'
  else
    [[ -f $resolved ]] || fail 'expected regular tool input file'
  fi
  printf '%s\n' "$resolved"
}

tool_mounts=()
target=(/usr/bin/python3 -B)
case "$mode" in
  tests)
    [[ -z $jdk_path && -z $archive_path && -z $android_path && -z $ndk_path ]] || fail 'tests mode accepts no toolchain inputs'
    # Source-proof contracts need no Git metadata. The original scanner
    # inventory/metadata guards remain in Android CI's repository job.
    target+=(-m unittest discover -s tools/tests -p 'test_fdroid_*source*.py' -v)
    ;;
  cli|loaders)
    [[ -n $ndk_path && -z $jdk_path && -z $archive_path && -z $android_path ]] || fail "$mode mode requires only --ndk"
    ndk_path=$(external_path "$ndk_path" directory)
    [[ -f $ndk_path/source.properties ]] || fail 'NDK source.properties missing'
    tool_mounts+=(--ro-bind "$ndk_path" /toolchain/ndk)
    if [[ $mode == loaders ]]; then
      target+=(tools/fdroid_proot_loader_source_build.py --ndk /toolchain/ndk --output-dir /scratch/proof)
    else
      target+=(tools/fdroid_cli_source_build.py --ndk /toolchain/ndk --output-dir /scratch/proof)
    fi
    ;;
  proot)
    [[ -n $ndk_path && -n $loader_ndk_path && -z $jdk_path && -z $archive_path && -z $android_path ]] || fail 'proot mode requires only --ndk and --loader-ndk'
    ndk_path=$(external_path "$ndk_path" directory)
    loader_ndk_path=$(external_path "$loader_ndk_path" directory)
    [[ $ndk_path != "$loader_ndk_path" && $ndk_path != "$loader_ndk_path/"* && $loader_ndk_path != "$ndk_path/"* ]] || fail 'engine and loader NDK inputs must be separate non-overlapping directories'
    [[ -f $ndk_path/source.properties && -f $loader_ndk_path/source.properties ]] || fail 'NDK source.properties missing'
    tool_mounts+=(--ro-bind "$ndk_path" /toolchain/ndk --ro-bind "$loader_ndk_path" /toolchain/loader-ndk)
    target+=(tools/fdroid_proot_engine_source_build.py --ndk /toolchain/ndk
      --loader-ndk /toolchain/loader-ndk --output-dir /scratch/proof)
    ;;
  spake2|termux|view)
    [[ -n $jdk_path && -n $archive_path && -z $ndk_path ]] || fail 'Java proofs require --jdk and --jdk-archive, but not --ndk'
    jdk_path=$(external_path "$jdk_path" directory)
    archive_path=$(external_path "$archive_path" file)
    [[ -x $jdk_path/bin/javac && -x $jdk_path/bin/java ]] || fail 'JDK executable tools missing'
    tool_mounts+=(--ro-bind "$jdk_path" /toolchain/jdk --ro-bind "$archive_path" /toolchain/jdk.tar.gz)
    if [[ $mode == spake2 ]]; then
      [[ -z $android_path ]] || fail 'spake2 mode accepts no Android dependency'
      target+=(tools/fdroid_spake2_source_build.py --build --jdk /toolchain/jdk
        --jdk-archive /toolchain/jdk.tar.gz --output /scratch/proof --run-vector-test)
    else
      [[ -n $android_path ]] || fail 'terminal proofs require --android-jar'
      android_path=$(external_path "$android_path" file)
      tool_mounts+=(--ro-bind "$android_path" /toolchain/android.jar)
      if [[ $mode == view ]]; then
        [[ -n $annotation_path ]] || fail 'view mode requires --annotation-jar'
        annotation_path=$(external_path "$annotation_path" file)
        tool_mounts+=(--ro-bind "$annotation_path" /toolchain/annotation.jar)
        target+=(tools/fdroid_termux_view_source_build.py --java-home /toolchain/jdk
          --android-jar /toolchain/android.jar --vendor-archive /toolchain/jdk.tar.gz
          --annotation-jar /toolchain/annotation.jar --output /scratch/proof)
      else
        target+=(tools/fdroid_termux_source_build.py --java-home /toolchain/jdk
          --android-jar /toolchain/android.jar --vendor-archive /toolchain/jdk.tar.gz --output /scratch/proof)
      fi
    fi
    ;;
esac

# The Java heap is separately bounded by the helpers. Its VM reserves more
# address space than its live heap, hence the 6 GiB RLIMIT_AS. The scratch disk
# is a 512 MiB tmpfs with no writable host bind and vanishes on sandbox exit.
limits=(--as=6442450944 --cpu=120 --nproc=128 --nofile=256 --fsize=67108864 --core=0)
isolation=(--unshare-all --unshare-user --disable-userns)
launch=(/usr/bin/timeout --signal=TERM --kill-after=3s 300s /usr/bin/prlimit "${limits[@]}" --)
if [[ $use_sudo == true ]]; then
  # The reviewed setup helper creates empty outer user/network namespaces and
  # maps only the non-root checkout owner. Its two-way gate drops the mapping
  # parent's host privilege before the child may execute bwrap. The final
  # target cannot inherit host root, host network, or the setup capabilities.
  # No host policy, home permissions, subordinate-ID files, or mounts change.
  caller_uid=$(/usr/bin/id -u)
  caller_gid=$(/usr/bin/id -g)
  [[ $caller_uid =~ ^[0-9]+$ && $caller_gid =~ ^[0-9]+$ && $caller_uid != 0 && $caller_gid != 0 ]] || fail '--sudo requires an unprivileged caller'
  launch=(/usr/bin/sudo -n "${launch[@]}" /usr/bin/python3 -I -B -S
    "$source_root/tools/fdroid_source_namespace.py"
    --uid "$caller_uid" --gid "$caller_gid" --)
  isolation+=(--share-net)
else
  # Share ONLY this newly-created, empty outer network namespace. This avoids
  # bwrap's loopback NETLINK setup where the host forbids that operation.
  launch+=(/usr/bin/unshare --user --map-root-user --net --)
  isolation+=(--share-net)
fi
source_masks=()
if [[ -d $source_root/.git ]]; then
  source_masks+=(--size 4096 --tmpfs /src/.git --remount-ro /src/.git)
elif [[ -e $source_root/.git ]]; then
  source_masks+=(--ro-bind /dev/null /src/.git)
fi
launch+=(/usr/bin/bwrap "${isolation[@]}"
  --die-with-parent --new-session --uid 65534 --gid 65534 --cap-drop ALL --clearenv
  --ro-bind /usr /usr
  --symlink usr/bin /bin --symlink usr/sbin /sbin
  --symlink usr/lib /lib --symlink usr/lib64 /lib64
  --dir /etc --ro-bind /etc/alternatives /etc/alternatives
  --ro-bind /etc/ld.so.cache /etc/ld.so.cache
  --ro-bind "$source_root" /src "${source_masks[@]}" --dir /toolchain "${tool_mounts[@]}"
  --proc /proc --remount-ro /proc --dev /dev --remount-ro /dev
  --size 536870912 --perms 1777 --tmpfs /scratch
  --remount-ro /
  --setenv PATH /usr/bin:/bin --setenv HOME /scratch/home
  --setenv TMPDIR /scratch/tmp --setenv XDG_CACHE_HOME /scratch/cache
  --setenv LANG C.UTF-8 --setenv LC_ALL C.UTF-8 --setenv TZ UTC
  --setenv PYTHONDONTWRITEBYTECODE 1 --chdir /src)

# Receipts are diagnostic data, not promoted artifacts. Read only exact known
# paths, with a cumulative 16 KiB display budget, before tmpfs is discarded.
# Preserve the original proof exit status even when no receipt was produced.
diagnostics='import os, stat
from pathlib import Path
remaining = 16384
for name in ("SUMMARY.json", "receipt.json", "REBUILD.json", "build-manifest.json", "run-1/javac.stderr", "run-2/javac.stderr", "first/javac.log", "second/javac.log", "first/vector-test.log"):
    path = Path("/scratch/proof") / name
    if remaining <= 0 or not path.is_file() or path.is_symlink():
        continue
    with path.open("rb") as stream:
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
            continue
        size = os.fstat(stream.fileno()).st_size
        data = stream.read(remaining)
    remaining -= len(data)
    print("SOURCE_PROOF_DIAGNOSTIC " + name)
    print(data.decode("utf-8", errors="replace"))
    if size > len(data):
        print("[diagnostic truncated to the fixed display budget]")'
# The diagnostic program is our fixed, reviewed string, not an inherited var.
# No shell expansion is applied to its contents.
launch+=(--setenv PROOF_DIAGNOSTICS "$diagnostics"
  /bin/bash -c 'set -e; umask 077; /usr/bin/mkdir -m 700 -- /scratch/home /scratch/tmp /scratch/cache; /usr/bin/python3 -I -B -S /src/tools/fdroid_source_sandbox_probe.py; set +e; "$@"; result=$?; /usr/bin/python3 -B -c "$PROOF_DIAGNOSTICS"; exit "$result"' proof-runner "${target[@]}")

if [[ $show_command == true ]]; then
  printf '%q ' "${launch[@]}"
  printf '\n'
  exit 0
fi
for tool in /usr/bin/timeout /usr/bin/prlimit /usr/bin/bwrap /usr/bin/python3; do
  [[ -x $tool ]] || fail "required sandbox tool unavailable: $tool"
done
if [[ $use_sudo == true ]]; then
  [[ -x /usr/bin/sudo ]] || fail 'sudo unavailable; no fallback is permitted'
  [[ -f $source_root/tools/fdroid_source_namespace.py ]] || fail 'CI namespace helper unavailable; no fallback is permitted'
else
  [[ -x /usr/bin/unshare ]] || fail 'unshare unavailable; no fallback is permitted'
fi
printf 'SOURCE_PROOF_SANDBOX mode=%s network=isolated source=read-only outputs=discarded\n' "$mode"
exec "${launch[@]}"
