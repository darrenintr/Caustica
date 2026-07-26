#!/usr/bin/env bash
# Build classic FSR2 Vulkan (libffx_fsr2_caustica.so) for Caustica.
#
# Caustica's classic FSR2 path needs the AMD FSR2 host API
# (ffxFsr2ContextCreate / ffxFsr2ContextDispatch / ffxFsr2GetInterfaceVK, ...)
# linked into a single .so. AMD's standalone FSR2 repo
# (github.com/GPUOpen-Effects/FidelityFX-FSR2) is Windows-targeted but Linux-fixable.
#
# This script:
#   1. Clones the FSR2 source tree to $FSR2_SRC (default /tmp/FSR2-src).
#   2. Patches the Windows-isms (wcscpy_s, std::wstring_convert, _countof,
#      FFX_GCC guard, platform check, DX12 backend).
#   3. Extracts 8 SPIRV blobs + descriptor metadata from the original
#      712KB AMD-shipped libffx_fsr2_caustica.so (commit 9cd6911) and
#      generates the 8 _permutations.h headers that AMD's
#      FidelityFX_SC.exe (Windows-only) normally produces.
#   4. Appends caustica_ffx_fsr2_dispatch_v2 (reactive-mask entry point)
#      to native/ffx_fsr2/caustica_fsr2_export.cpp if missing.
#   5. Configures cmake with FSR2_AUTO_COMPILE_SHADERS=OFF (we already
#      shipped the permutations) and FSR2_BUILD_AS_DLL=ON (shared lib),
#      then builds. Output: libffx_fsr2_api_x64.so → libffx_fsr2_caustica.so.
#   6. nm -D verifies caustica_ffx_fsr2_dispatch_v2 is exported.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FSR2_SRC="${FSR2_SRC:-/tmp/FSR2-src}"
BUILD="${BUILD:-/tmp/FSR2-build}"
SRC_API="$FSR2_SRC/src/ffx-fsr2-api"
OUT_LIB="${OUT_LIB:-$ROOT/src/main/resources/caustica/natives/linux-x64}"
CAUSTICA_EXPORT="$ROOT/native/ffx_fsr2/caustica_fsr2_export.cpp"

# ---------- 1. clone ----------
if [ ! -d "$SRC_API" ]; then
    echo "Cloning AMD FidelityFX-FSR2..."
    git clone --depth 1 https://github.com/GPUOpen-Effects/FidelityFX-FSR2.git "$FSR2_SRC"
fi

# ---------- 2. Linux patches ----------
PATCH_MARKER="$FSR2_SRC/.caustica_linux_patches_applied"
if [ ! -f "$PATCH_MARKER" ]; then
    echo "Applying Linux compatibility patches..."

    # 2a) Platform check: add Linux x86_64 branch.
    python3 - "$SRC_API/CMakeLists.txt" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
SENTINEL = '# Caustica Linux patch: treat Linux x86_64 as the x64 platform.'
if SENTINEL in src:
    print("  already patched:", path)
else:
    old = (
        'if(CMAKE_GENERATOR_PLATFORM STREQUAL "x64" OR CMAKE_EXE_LINKER_FLAGS STREQUAL "/machine:x64")\n'
        '    set(FSR2_PLATFORM_NAME x64)\n'
        'elseif(CMAKE_GENERATOR_PLATFORM STREQUAL "Win32" OR CMAKE_EXE_LINKER_FLAGS STREQUAL "/machine:X86")\n'
        '    set(FSR2_PLATFORM_NAME x86)\n'
        'else()\n'
        '    message(FATAL_ERROR "Unsupported target platform - only supporting x64 and Win32 currently")\n'
        'endif()'
    )
    new = (
        SENTINEL + '\n'
        'if(CMAKE_SYSTEM_NAME STREQUAL "Linux" AND CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64")\n'
        '    set(FSR2_PLATFORM_NAME x64)\n'
        'elseif(CMAKE_GENERATOR_PLATFORM STREQUAL "x64" OR CMAKE_EXE_LINKER_FLAGS STREQUAL "/machine:x64")\n'
        '    set(FSR2_PLATFORM_NAME x64)\n'
        'elseif(CMAKE_GENERATOR_PLATFORM STREQUAL "Win32" OR CMAKE_EXE_LINKER_FLAGS STREQUAL "/machine:X86")\n'
        '    set(FSR2_PLATFORM_NAME x86)\n'
        'else()\n'
        '    message(FATAL_ERROR "Unsupported target platform - only supporting x64 and Win32 currently")\n'
        'endif()'
    )
    if old not in src:
        print("ERROR: platform check not found in expected form", file=sys.stderr)
        sys.exit(1)
    src = src.replace(old, new, 1)
    old_dx12 = 'if(FFX_FSR2_API_DX12)\n    message("Will build FSR2 library: DX12 backend")\n    add_subdirectory(dx12)\nendif()'
    new_dx12 = (
        'if(FFX_FSR2_API_DX12 AND NOT CMAKE_SYSTEM_NAME STREQUAL "Linux")\n'
        '    message("Will build FSR2 library: DX12 backend")\n'
        '    add_subdirectory(dx12)\n'
        'endif()'
    )
    if old_dx12 in src:
        src = src.replace(old_dx12, new_dx12, 1)
    open(path, 'w').write(src)
    print("  patched", path)
PY

    # 2c) wcscpy_s → Linux-safe char[] copies. AMD's pattern
    #     `wcscpy_s(dst, converter.from_bytes(x).c_str())` has nested parens
    #     that fool naive regex; use a hand-rolled parens-balanced scanner.
    for src_file in "$SRC_API/ffx_fsr2.cpp" "$SRC_API/vk/ffx_fsr2_vk.cpp"; do
        python3 - "$src_file" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
out = []
i = 0; N = len(src); n_replaced = 0
while i < N:
    j = src.find("wcscpy_s(", i)
    if j < 0:
        out.append(src[i:])
        break
    out.append(src[i:j])
    k = j + len("wcscpy_s("); depth = 1
    while k < N and depth > 0:
        if src[k] == '(': depth += 1
        elif src[k] == ')': depth -= 1
        k += 1
    semi = src.find(';', k)
    if semi < 0:
        out.append(src[j:])
        break
    args_str = src[j + len("wcscpy_s("):k - 1]
    depth = 0; split = -1
    for idx in range(len(args_str)):
        if args_str[idx] == '(': depth += 1
        elif args_str[idx] == ')': depth -= 1
        elif args_str[idx] == ',' and depth == 0:
            split = idx; break
    if split < 0:
        out.append(src[j:semi + 1]); i = semi + 1; continue
    dst = args_str[:split].strip()
    src_expr = args_str[split + 1:].strip()
    if '.c_str()' in src_expr:
        replacement = (
            '{ const wchar_t* __wsrc = (' + src_expr + '); '
            'size_t __n = __wsrc ? std::min<size_t>(wcslen(__wsrc), 63) : 0; '
            'for (size_t __i = 0; __i < __n; ++__i) (' + dst + ')[__i] = __wsrc[__i]; '
            '(' + dst + ')[__n] = L\'\\0\'; }'
        )
    elif src_expr.rstrip().endswith('.name'):
        replacement = (
            '{ const wchar_t* __wsrc = (' + src_expr + '); '
            'size_t __n = 0; while (__n < 63 && __wsrc[__n]) ++__n; '
            'for (size_t __i = 0; __i < __n; ++__i) (' + dst + ')[__i] = __wsrc[__i]; '
            '(' + dst + ')[__n] = L\'\\0\'; }'
        )
    else:
        replacement = (
            '{ const char* __csrc = (' + src_expr + '); '
            'size_t __n = __csrc ? std::min<size_t>(strlen(__csrc), 63) : 0; '
            'for (size_t __i = 0; __i < __n; ++__i) (' + dst + ')[__i] = (wchar_t)(unsigned char)__csrc[__i]; '
            '(' + dst + ')[__n] = L\'\\0\'; }'
        )
    out.append(replacement); i = semi + 1; n_replaced += 1
open(path, 'w').write(''.join(out))
print("  patched", path, "  wcscpy_s replacements=", n_replaced)
PY
    done

    # 2d) std::wstring_convert + std::codecvt_utf8_utf16<wchar_t> — deprecated
    #     in libstdc++16+. Replace the declaration with a tiny stub struct
    #     that provides .from_bytes(const char*) -> std::wstring.
    python3 - "$SRC_API/vk/ffx_fsr2_vk.cpp" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
needle = 'std::wstring_convert<std::codecvt_utf8_utf16<wchar_t>> converter;'
if needle in src:
    src = src.replace(needle, (
        '// Caustica Linux: std::wstring_convert is deprecated in libstdc++16.\n'
        '    // We only need converter.from_bytes(char*) → wstring, so substitute a\n'
        '    // minimal struct that does that directly via std::wstring.\n'
        '    struct __CausticaConverter {\n'
        '        std::wstring from_bytes(const char* s) const {\n'
        '            if (!s) return std::wstring();\n'
        '            size_t n = 0; while (s[n] && n < 63) ++n;\n'
        '            std::wstring r(n, L\'\\0\');\n'
        '            for (size_t i = 0; i < n; ++i) r[i] = (wchar_t)(unsigned char)s[i];\n'
        '            return r;\n'
        '        }\n'
        '    } converter;'
    ), 1)
    open(path, 'w').write(src)
    print("  patched", path)
PY

    # 2e) Header includes + _countof + wcscmp — ffx_fsr2.cpp uses wcscmp and
    #     _countof (MSVC) but doesn't include <cstddef>/<wchar.h>. Inject a
    #     Caustica Linux compat shim at the top of ffx_types.h.
    python3 - "$SRC_API/ffx_types.h" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
shim = (
    "// Caustica Linux compat shim — AMD's headers assume MSVC headers.\n"
    "#ifndef _countof\n"
    "#define _countof(a) (sizeof(a) / sizeof((a)[0]))\n"
    "#endif\n"
    "#include <cstddef>\n"
    "#include <cstring>\n"
    "#include <string>\n"
    "#include <algorithm>\n"
    "#include <wchar.h>\n"
)
if "_countof" not in src:
    src = shim + src
    open(path, 'w').write(src)
    print("  patched", path)
PY

    touch "$PATCH_MARKER"
fi

# ---------- 3. extract SPIRV + generate permutations.h ----------
SPV_DIR="/tmp/.caustica-fsr2-spvs"
mkdir -p "$SPV_DIR"

# Pull the originally-shipped 712KB SO from git history if no local backup.
SHIPSO_SO=""
if [ -f "$OUT_LIB/libffx_fsr2_caustica.so.bak-pre-format-fix" ] && \
       [ -s "$OUT_LIB/libffx_fsr2_caustica.so.bak-pre-format-fix" ]; then
    SHIPSO_SO="$OUT_LIB/libffx_fsr2_caustica.so.bak-pre-format-fix"
fi
GIT_SO="$(git -C "$ROOT" show 9cd6911:src/main/resources/caustica/natives/linux-x64/libffx_fsr2_caustica.so 2>/dev/null || true)"
if [ -z "$SHIPSO_SO" ] && [ -n "$GIT_SO" ]; then
    SHIPSO_GIT="/tmp/.caustica-shipped-fsr2.so"
    printf '%s' "$GIT_SO" > "$SHIPSO_GIT"
    SHIPSO_SO="$SHIPSO_GIT"
fi
if [ -z "$SHIPSO_SO" ] || [ ! -f "$SHIPSO_SO" ]; then
    echo "ERROR: cannot find originally-shipped 712KB libffx_fsr2_caustica.so" >&2
    exit 20
fi
echo "Extracting SPIRV + descriptor metadata from $SHIPSO_SO"

# Write the parser + generator to standalone scripts under /tmp so the heredoc
# boundaries in this shell script don't get fragile when we add new patches.
SPV_EXTRACT=/tmp/.caustica-fsr2-extract.py
cat > "$SPV_EXTRACT" <<'PYEOF'
import struct, sys, os
sp_so, outdir = sys.argv[1], sys.argv[2]
with open(sp_so, 'rb') as f: data = f.read()
magic = struct.pack('<I', 0x07230203)
positions = []
idx = 0
while idx < len(data) - 4:
    pos = data.find(magic, idx)
    if pos < 0: break
    positions.append(pos); idx = pos + 4
def walk_blob(data, start):
    pos = start + 20
    while pos + 4 <= len(data):
        word = struct.unpack_from('<I', data, pos)[0]
        wc = word >> 16; op = word & 0xFFFF
        # 32768 cap covers the longest SPIRV literal-string instructions
        # AMD's FSR2 emits (large OpName for debug helpers).
        if wc == 0 or wc > 32768: break
        if pos + wc * 4 > len(data): break
        pos += wc * 4
    return pos
PASSES = [
    'tcr_autogen', 'autogen_reactive', 'accumulate', 'compute_luminance_pyramid',
    'depth_clip', 'lock', 'reconstruct_previous_depth', 'rcas',
]
for i, pos in enumerate(positions):
    next_pos = positions[i+1] if i+1 < len(positions) else len(data)
    end = min(walk_blob(data, pos), next_pos)
    blob = data[pos:end]
    name = PASSES[i] if i < len(PASSES) else f'pass_{i}'
    with open(os.path.join(outdir, f'{name}.spv'), 'wb') as f: f.write(blob)
    print(f"  extracted {name}.spv ({len(blob)} bytes)")
PYEOF

SPV_GEN=/tmp/.caustica-fsr2-gen.py
cat > "$SPV_GEN" <<'PYEOF'
import struct, sys, os
spv_dir, headers_dir = sys.argv[1], sys.argv[2]
def parse_spv(path):
    with open(path, 'rb') as f: data = f.read()
    if struct.unpack_from('<I', data, 0)[0] != 0x07230203: return None
    decorations, names, vars = {}, {}, []
    pos = 20
    while pos + 4 <= len(data):
        word = struct.unpack_from('<I', data, pos)[0]
        wc = word >> 16; op = word & 0xFFFF
        if wc == 0 or wc > 32768: break
        if pos + wc * 4 > len(data): break
        body = struct.unpack_from(f'<{wc - 1}I', data, pos + 4)
        if op == 71:
            tid = body[0]; deco = body[1]
            if deco == 33: decorations.setdefault(tid, {})['set'] = body[2]
            elif deco == 34: decorations.setdefault(tid, {})['binding'] = body[2]
        elif op == 59:
            rtid, rid, sc = body[:3]
            vars.append((rid, sc, rtid))
        elif op == 5:
            tid = body[0]
            try: name = b''.join(struct.pack('<I', w) for w in body[1:]).rstrip(b'\x00').decode('utf-8', errors='replace')
            except: name = '?'
            names[tid] = name
        pos += wc * 4
    return decorations, vars, names
def classify(name):
    if name.startswith('s_') or name.startswith('sampler') or name.startswith('Sampler'): return 'sampler'
    if name.startswith('rw_') or name.startswith('w_'): return 'storage'
    if name.startswith('r_'): return 'sampled'
    if name.startswith('cb') or name.startswith('b_'): return 'uniform'
    return 'sampled'
PASSES = [
    ('tcr_autogen', 64), ('autogen_reactive', 64), ('accumulate', 64),
    ('compute_luminance_pyramid', 1),
    ('depth_clip', 64), ('lock', 64), ('reconstruct_previous_depth', 64),
    ('rcas', 64),
]
for pass_name, num_perms in PASSES:
    decos, vars, names = parse_spv(os.path.join(spv_dir, f'{pass_name}.spv'))
    if decos is None:
        print(f"  WARN: failed to parse {pass_name}", file=sys.stderr)
        continue
    with open(os.path.join(spv_dir, f'{pass_name}.spv'), 'rb') as f: spv = f.read()
    storage_names, storage_binds = [], []
    sampled_names, sampled_binds = [], []
    uniform_names, uniform_binds = [], []
    for vid, sclass, _ in vars:
        name = names.get(vid, ''); d = decos.get(vid, {})
        if not d: continue
        if sclass == 2:
            uniform_names.append(name); uniform_binds.append(d['binding']); continue
        if sclass != 0: continue
        k = classify(name)
        if k == 'storage':
            storage_names.append(name); storage_binds.append(d['binding'])
        elif k == 'sampled':
            sampled_names.append(name); sampled_binds.append(d['binding'])
        elif k == 'uniform':
            uniform_names.append(name); uniform_binds.append(d['binding'])
    struct_name = f"ffx_fsr2_{pass_name}_pass_PermutationInfo"
    blob_name = f"g_ffx_fsr2_{pass_name}_pass_Blob"
    s_names_n = f"g_ffx_fsr2_{pass_name}_pass_StorageImageResourceNames"
    s_binds_n = f"g_ffx_fsr2_{pass_name}_pass_StorageImageResourceBindings"
    sa_names_n = f"g_ffx_fsr2_{pass_name}_pass_SampledImageResourceNames"
    sa_binds_n = f"g_ffx_fsr2_{pass_name}_pass_SampledImageResourceBindings"
    u_names_n = f"g_ffx_fsr2_{pass_name}_pass_UniformBufferResourceNames"
    u_binds_n = f"g_ffx_fsr2_{pass_name}_pass_UniformBufferResourceBindings"
    perm_info_n = f"g_ffx_fsr2_{pass_name}_pass_PermutationInfo"
    indir_n = f"g_ffx_fsr2_{pass_name}_pass_IndirectionTable"
    hex_bytes = ', '.join(f'0x{b:02x}' for b in spv)
    def na(n, items):
        if not items: return f"static const char* {n}[] = {{ nullptr }};\n"
        s = f"static const char* {n}[] = {{\n"
        for x in items: s += f'    "{x}",\n'
        return s + "};\n"
    def ba(n, items):
        if not items: return f"static const uint32_t {n}[] = {{ 0 }};\n"
        return f"static const uint32_t {n}[] = {{ {', '.join(str(x) for x in items)} }};\n"
    out = (
        "// Caustica Linux - auto-generated from the originally-shipped\n"
        "// 712KB AMD FSR2 libffx_fsr2_caustica.so (commit 9cd6911).\n"
        "// SPIRV bytes + descriptor metadata re-emitted as static arrays\n"
        "// in the g_ffx_fsr2_<pass>_pass_* layout ffx_fsr2_shaders_vk.cpp expects.\n"
        "#pragma once\n"
        "#include <cstdint>\n\n"
        f"static const uint8_t {blob_name}[] = {{ {hex_bytes} }};\n"
        f"{na(s_names_n, storage_names)}"
        f"{ba(s_binds_n, storage_binds)}"
        f"{na(sa_names_n, sampled_names)}"
        f"{ba(sa_binds_n, sampled_binds)}"
        f"{na(u_names_n, uniform_names)}"
        f"{ba(u_binds_n, uniform_binds)}"
        f"struct {struct_name} {{\n"
        "    const uint8_t* blobData;\n"
        "    uint32_t blobSize;\n"
        "    uint32_t numStorageImageResources;\n"
        "    uint32_t numSampledImageResources;\n"
        "    uint32_t numUniformBufferResources;\n"
        "    const char** storageImageResourceNames;\n"
        "    const uint32_t* storageImageResourceBindings;\n"
        "    const char** sampledImageResourceNames;\n"
        "    const uint32_t* sampledImageResourceBindings;\n"
        "    const char** uniformBufferResourceNames;\n"
        "    const uint32_t* uniformBufferResourceBindings;\n"
        "};\n"
        f"struct ffx_fsr2_{pass_name}_pass_PermutationKey {{\n"
        "    uint32_t index;\n"
        "    uint32_t FFX_FSR2_OPTION_REPROJECT_USE_LANCZOS_TYPE;\n"
        "    uint32_t FFX_FSR2_OPTION_HDR_COLOR_INPUT;\n"
        "    uint32_t FFX_FSR2_OPTION_LOW_RESOLUTION_MOTION_VECTORS;\n"
        "    uint32_t FFX_FSR2_OPTION_JITTERED_MOTION_VECTORS;\n"
        "    uint32_t FFX_FSR2_OPTION_INVERTED_DEPTH;\n"
        "    uint32_t FFX_FSR2_OPTION_APPLY_SHARPENING;\n"
        "    uint32_t FFX_HALF;\n"
        "};\n"
        f"static const {struct_name} {perm_info_n}[1] = {{\n"
        "    {\n"
        f"        {blob_name},\n"
        f"        {len(spv)},\n"
        f"        {len(storage_names)}, {len(sampled_names)}, {len(uniform_names)},\n"
        f"        {s_names_n}, {s_binds_n},\n"
        f"        {sa_names_n}, {sa_binds_n},\n"
        f"        {u_names_n}, {u_binds_n},\n"
        "    },\n"
        "};\n"
        f"static const int32_t {indir_n}[{num_perms}] = {{ {', '.join(['0'] * num_perms)} }};\n"
    )
    out_path = os.path.join(headers_dir, f'ffx_fsr2_{pass_name}_pass_permutations.h')
    with open(out_path, 'w') as f: f.write(out)
    print(f"  wrote {out_path}")
PYEOF

python3 "$SPV_EXTRACT" "$SHIPSO_SO" "$SPV_DIR"
python3 "$SPV_GEN" "$SPV_DIR" "$SRC_API/vk/shaders"

# Patch the SC.exe invocation out of the cmake flow. The permutations.h
# files are already generated, so we don't need SC.exe to write them.
python3 - "$SRC_API/vk/CMakeLists.txt" <<'PY'
import sys, re
path = sys.argv[1]
src = open(path).read()
new = re.sub(
    r'add_custom_command\(\s*OUTPUT\s+\$\{PERMUTATION_HEADER\}\s*COMMAND\s+\$\{FFX_SC_EXECUTABLE\}.*?\n\s*WORKING_DIRECTORY.*?\n\s*DEPENDS.*?\n\s*(?:DEPFILE.*?\n\s*)?\)\s*\n',
    '# Caustica: SC.exe generation disabled (permutations pre-generated).\n', src, flags=re.DOTALL)
new = re.sub(
    r'add_custom_target\(shader_permutations_vk DEPENDS.*?\)',
    '# Caustica: shader_permutations_vk target disabled.\n', new, flags=re.DOTALL)
new = re.sub(
    r'list\(APPEND PERMUTATION_OUTPUTS.*?\)',
    '# Caustica: PERMUTATION_OUTPUTS list disabled.\n', new)
new = new.replace(
    'add_dependencies(${FFX_SC_DEPENDENT_TARGET} shader_permutations_vk)',
    'add_dependencies(${FFX_SC_DEPENDENT_TARGET} ${FFX_SC_DEPENDENT_TARGET})')
open(path, 'w').write(new)
print("  patched", path)
PY

# ---------- 4. refresh Caustica wrapper ----------
if [ ! -f "$CAUSTICA_EXPORT" ]; then
    echo "ERROR: missing $CAUSTICA_EXPORT" >&2
    exit 1
fi
cp -f "$CAUSTICA_EXPORT" "$SRC_API/caustica_fsr2_export.cpp"
echo "Refreshed $SRC_API/caustica_fsr2_export.cpp"

# Append the v2 dispatch (reactive-mask entry point) if it isn't there.
# This block is a self-contained snippet; embedded so the build script is
# idempotent across checkouts where someone may have already added it.
if ! grep -q "caustica_ffx_fsr2_dispatch_v2" "$CAUSTICA_EXPORT"; then
    echo "INFO: appending caustica_ffx_fsr2_dispatch_v2 to $CAUSTICA_EXPORT"
    cat >> "$CAUSTICA_EXPORT" <<'CAUSTICA_V2_BLOCK'

// ---------------------------------------------------------------------------
// v2 dispatch: adds the reactive mask (R32F, render res). The Java upscaler
// feeds the self-derived motion+depth divergence signal (see
// shaders/display/denoise_ffx/fsr2_reactive_mask.comp — ported from
// iterationRP's DepthClip_CS.glsl:82-151 motion+depth divergence).
//
// reactive_image == 0 → reactive mask disabled (behaves like v1 dispatch).
// ---------------------------------------------------------------------------
extern "C" int caustica_ffx_fsr2_dispatch_v2(
    void* ctx,
    uint64_t vk_command_buffer,
    uint64_t color_image, uint64_t color_view,
    uint64_t depth_image, uint64_t depth_view,
    uint64_t motion_image, uint64_t motion_view,
    uint64_t output_image, uint64_t output_view,
    uint64_t reactive_image, uint64_t reactive_view,
    uint32_t render_w, uint32_t render_h,
    float jitter_x, float jitter_y,
    float frame_time_delta_ms,
    float pre_exposure,
    float camera_near, float camera_far, float camera_fov_y,
    int reset)
{
    if (!ctx || !vk_command_buffer) return -1;
    auto* c = static_cast<CausticaFsr2*>(ctx);
    if (!c->created) return -2;

    FfxFsr2DispatchDescription d{};
    memset(&d, 0, sizeof(d));
    d.commandList = ffxGetCommandListVK((VkCommandBuffer)vk_command_buffer);
    d.color = makeTex(&c->ctx, color_image, color_view, render_w, render_h,
                      VK_FORMAT_R16G16B16A16_SFLOAT, FFX_RESOURCE_STATE_UNORDERED_ACCESS);
    d.depth = makeTex(&c->ctx, depth_image, depth_view, render_w, render_h,
                      VK_FORMAT_R32_SFLOAT, FFX_RESOURCE_STATE_UNORDERED_ACCESS);
    d.motionVectors = makeTex(&c->ctx, motion_image, motion_view, render_w, render_h,
                              VK_FORMAT_R16G16B16A16_SFLOAT, FFX_RESOURCE_STATE_UNORDERED_ACCESS);
    d.output = makeTex(&c->ctx, output_image, output_view, c->displayW, c->displayH,
                       VK_FORMAT_R16G16B16A16_SFLOAT, FFX_RESOURCE_STATE_UNORDERED_ACCESS);

    if (reactive_image != 0 && reactive_view != 0) {
        d.reactive = makeTex(&c->ctx, reactive_image, reactive_view, render_w, render_h,
                             VK_FORMAT_R32_SFLOAT, FFX_RESOURCE_STATE_UNORDERED_ACCESS);
    }

    d.jitterOffset.x = jitter_x;
    d.jitterOffset.y = jitter_y;
    d.motionVectorScale.x = 1.0f;
    d.motionVectorScale.y = 1.0f;
    d.renderSize = { render_w, render_h };
    d.enableSharpening = true;
    d.sharpness = 0.55f;
    if (pre_exposure > 2.0f && pre_exposure <= 3.0f) {
        d.sharpness = pre_exposure - 2.0f;
    } else if (pre_exposure < 0.0f) {
        d.enableSharpening = false;
        d.sharpness = 0.0f;
    }
    d.frameTimeDelta = frame_time_delta_ms > 0 ? frame_time_delta_ms : 16.6f;
    d.preExposure = (pre_exposure > 0.0f && pre_exposure <= 2.0f) ? pre_exposure : 1.0f;
    d.reset = reset != 0;
    d.cameraNear = camera_near > 0.0f ? camera_near : 0.05f;
    d.cameraFar = camera_far;
    float fov = camera_fov_y;
    if (!(fov > 0.15f && fov < 2.5f)) {
        fov = 1.2217305f;
    }
    d.cameraFovAngleVertical = fov;
    d.viewSpaceToMetersFactor = 1.0f;

    FfxErrorCode err = ffxFsr2ContextDispatch(&c->ctx, &d);
    return (int)err;
}
CAUSTICA_V2_BLOCK
fi
if ! grep -q "caustica_ffx_fsr2_dispatch_v2" "$CAUSTICA_EXPORT"; then
    echo "ERROR: failed to append caustica_ffx_fsr2_dispatch_v2 to $CAUSTICA_EXPORT" >&2
    exit 2
fi
grep -q "caustica_ffx_fsr2_dispatch_v2" "$SRC_API/caustica_fsr2_export.cpp" \
    || cp -f "$CAUSTICA_EXPORT" "$SRC_API/caustica_fsr2_export.cpp"

# ---------- 5. configure + build ----------
mkdir -p "$BUILD"
cmake -S "$SRC_API" -B "$BUILD" \
    -G "Unix Makefiles" \
    -DCMAKE_BUILD_TYPE=Release \
    -DFFX_FSR2_API_DX12=OFF \
    -DFFX_FSR2_API_VK=ON \
    -DFSR2_BUILD_AS_DLL=ON \
    -DFSR2_AUTO_COMPILE_SHADERS=OFF \
    -DCMAKE_CXX_FLAGS="-DFFX_GCC"
cmake --build "$BUILD" -j"$(nproc)"

# AMD's CMakeLists produces two separate shared libraries
# (libffx_fsr2_api_x64.so + libffx_fsr2_api_vk_x64.so). The shipped
# 712KB libffx_fsr2_caustica.so combines both — link them together so
# the SPIRV blob arrays land in the same .so as caustica_fsr2_export.o.
CPU_OBJ_DIR="$BUILD/CMakeFiles/ffx_fsr2_api_x64.dir"
VK_OBJ_DIR="$BUILD/vk/CMakeFiles/ffx_fsr2_api_vk_x64.dir"
COMBINED_SO="$BUILD/libffx_fsr2_api_x64.so"
if ! /usr/bin/c++ -shared -fPIC -o "$COMBINED_SO" \
        "$VK_OBJ_DIR/ffx_fsr2_vk.cpp.o" \
        "$VK_OBJ_DIR/__/ffx_assert.cpp.o" \
        "$CPU_OBJ_DIR/ffx_fsr2.cpp.o" \
        "$CPU_OBJ_DIR/caustica_fsr2_export.cpp.o" \
        "$VK_OBJ_DIR/shaders/ffx_fsr2_shaders_vk.cpp.o" \
        -lvulkan 2>&1; then
    echo "ERROR: manual link of combined FSR2 SO failed" >&2
    exit 5
fi

# ---------- 6. deploy + verify ----------
if [ ! -f "$COMBINED_SO" ]; then
    echo "ERROR: $COMBINED_SO not found after manual link" >&2
    exit 3
fi
mkdir -p "$OUT_LIB"
cp -f "$COMBINED_SO" "$OUT_LIB/libffx_fsr2_caustica.so"
chmod +x "$OUT_LIB/libffx_fsr2_caustica.so"

if ! grep -q "caustica_ffx_fsr2_dispatch_v2" < <(nm -D "$OUT_LIB/libffx_fsr2_caustica.so" 2>/dev/null); then
    echo "ERROR: caustica_ffx_fsr2_dispatch_v2 not exported" >&2
    echo "       Java hasV2Dispatch() will return false and FSR2 falls back to v1." >&2
    exit 4
fi

ls -la "$OUT_LIB/libffx_fsr2_caustica.so"
echo "OK — reactive-mask v2 entry point exported with real SPIRV + descriptor metadata."
echo "Repackage jar with: bash gradlew jar"