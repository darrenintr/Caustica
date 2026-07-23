#!/usr/bin/env bash
# Reproducibly build the pinned NRD static library and Caustica Linux shim.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=nrd-version.env
source "$ROOT/scripts/nrd-version.env"
NRD_SRC="${NRD_SRC:-$ROOT/build/vendor/NRD}"
NRD_BUILD="${NRD_BUILD:-$ROOT/build/vendor/NRD-build-linux}"
SHIM_BUILD="${SHIM_BUILD:-$ROOT/build/nrd-linux}"
OUT_LIB="${OUT_LIB:-$ROOT/src/main/resources/caustica/natives/linux-x64}"

if [[ ! -d "$NRD_SRC/.git" ]]; then
  git clone --filter=blob:none --no-checkout https://github.com/NVIDIA-RTX/NRD.git "$NRD_SRC"
fi
git -C "$NRD_SRC" fetch --depth 1 origin "$NRD_COMMIT"
git -C "$NRD_SRC" checkout --detach "$NRD_COMMIT"
test "$(git -C "$NRD_SRC" rev-parse HEAD)" = "$NRD_COMMIT"

cmake -S "$NRD_SRC" -B "$NRD_BUILD" -G Ninja \
  -DNRD_STATIC_LIBRARY=ON -DNRD_NRI=OFF -DNRD_EMBEDS_SPIRV_SHADERS=ON \
  -DNRD_NORMAL_ENCODING="$NRD_NORMAL_ENCODING" \
  -DNRD_ROUGHNESS_ENCODING="$NRD_ROUGHNESS_ENCODING" \
  -DCMAKE_BUILD_TYPE=Release

# Caustica Linux workaround: NRD's CMake invokes
#   ${NRD_BUILD}/_deps/shadermake-build/build/ShaderMake
# but the user media mount (/run/media/darren/...) is noexec — even with
# chmod +x the binary can't be invoked there. Workaround: after the cmake
# configure step writes build.ninja, sed-replace the two noexec paths
# (ShaderMake + dxc-src/bin/dxc) with copies under /tmp.
SM_TMP=/tmp/ShaderMake.caustica
DXC_TMP=/tmp/dxc.caustica
if [ ! -x "$SM_TMP" ] && [ -x "$HOME/bin/ShaderMake" ]; then
    cp "$HOME/bin/ShaderMake" "$SM_TMP" && chmod +x "$SM_TMP"
fi
if [ ! -x "$DXC_TMP/bin/dxc" ] && [ -x "/tmp/dxc/bin/dxc" ]; then
    cp -r /tmp/dxc "$DXC_TMP"
fi
if [ -x "$SM_TMP" ]; then
    sed -i "s|${NRD_BUILD}/_deps/shadermake-build/build/ShaderMake|${SM_TMP}|g" \
        "$NRD_BUILD/build.ninja"
fi
if [ -x "$DXC_TMP/bin/dxc" ]; then
    sed -i "s|${NRD_BUILD}/_deps/dxc-src/bin/dxc|${DXC_TMP}/bin/dxc|g" \
        "$NRD_BUILD/build.ninja"
fi
cmake --build "$NRD_BUILD" --config Release --parallel

SM_BLOB="$(find "$NRD_BUILD" -type f -name 'libShaderMakeBlob.a' -print -quit)"
if [[ -z "$SM_BLOB" || ! -f "$NRD_SRC/_Bin/libNRD.a" ]]; then
  echo "ERROR: pinned NRD or ShaderMakeBlob static library was not produced" >&2
  exit 1
fi

cmake -S "$ROOT/native/nrd" -B "$SHIM_BUILD" -G Ninja \
  -DNRD_ROOT="$NRD_SRC" -DSHADERMAKE_BLOB_LIB="$SM_BLOB" \
  -DCAUSTICA_NRD_VERSION="$NRD_VERSION" -DCAUSTICA_NRD_COMMIT="$NRD_COMMIT" \
  -DCAUSTICA_NRD_NORMAL_ENCODING="$NRD_NORMAL_ENCODING" \
  -DCAUSTICA_NRD_ROUGHNESS_ENCODING="$NRD_ROUGHNESS_ENCODING" \
  -DCMAKE_BUILD_TYPE=Release
cmake --build "$SHIM_BUILD" --config Release --parallel

SHIM="$SHIM_BUILD/libnrd_caustica.so"
test -f "$SHIM"
# Caustica: use process substitution so `grep -q` (which exits early on match
# and would otherwise SIGPIPE `nm`, triggering pipefail under set -e) doesn't
# kill the script with a false-positive missing export.
if grep -q ' U .*FindPermutationInBlob' < <(nm -D "$SHIM" 2>/dev/null); then
  echo "ERROR: FindPermutationInBlob remains undefined" >&2
  exit 1
fi
for symbol in caustica_nrd_probe caustica_nrd_abi_version caustica_nrd_create_v2; do
  grep -q " $symbol$" < <(nm -D "$SHIM" 2>/dev/null) \
    || { echo "ERROR: missing export $symbol" >&2; exit 1; }
done

mkdir -p "$OUT_LIB"
install -m 0755 "$SHIM" "$OUT_LIB/libnrd_caustica.so"
echo "Built NRD $NRD_VERSION ($NRD_COMMIT): $OUT_LIB/libnrd_caustica.so"
