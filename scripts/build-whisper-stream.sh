#!/bin/bash
# Builds the `whisper-stream` binary that ambient listening needs in order to keep the model
# resident instead of reloading ~148 MB per clip (ADR-039's blocked half), packages it the way
# VoiceInstaller expects, and prints the manifest entries to publish.
#
# Why this is not just "cmake --build": whisper-stream links SDL2, which Homebrew installs as a
# dylib under /opt/homebrew or /usr/local. A binary left pointing at those paths runs on the
# machine that built it and nowhere else. This script bundles SDL2 beside the binary, rewrites the
# load path to @loader_path, re-signs (mandatory on Apple Silicon -- install_name_tool invalidates
# the signature and the binary then refuses to launch), and *verifies* that no absolute build-host
# path survived before packaging.
#
# Usage:
#   scripts/build-whisper-stream.sh                 # build for this machine's architecture
#   scripts/build-whisper-stream.sh --arch x64      # build for a specific one
#   scripts/build-whisper-stream.sh --arch both     # both, if both SDL2 slices are installed
#   scripts/build-whisper-stream.sh --publish       # also upload to the GitHub release
set -euo pipefail

# Must match the manifest's whisperCppRef, or whisper-stream and the shipped whisper-cli come from
# different sources -- different segmentation behaviour, silently.
WHISPER_REF="b4938"
RELEASE_TAG="voice-tools-v1"
RELEASE_REPO="shukriev/SoPhi"
MANIFEST_URL="https://github.com/$RELEASE_REPO/releases/download/$RELEASE_TAG/voice-tools-manifest.json"

arch_arg="host"
publish=false
while [ $# -gt 0 ]; do
    case "$1" in
        --arch) arch_arg="${2:-}"; shift 2 ;;
        --publish) publish=true; shift ;;
        # Prints the header comment up to the first non-comment line, so the range never needs
        # updating when the header grows.
        -h|--help) awk 'NR>1 { if (!/^#/) exit; sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
        *) echo "Error: unknown argument '$1'. Try --help." >&2; exit 1 ;;
    esac
done

if [ "$(uname -s)" != "Darwin" ]; then
    echo "Error: this builds the macOS artifacts; run it on a Mac." >&2
    exit 1
fi

case "$(uname -m)" in
    arm64)  host_arch="arm64" ;;
    x86_64) host_arch="x64" ;;
    *) echo "Error: unsupported host architecture '$(uname -m)'." >&2; exit 1 ;;
esac

case "$arch_arg" in
    host) targets=("$host_arch") ;;
    arm64|x64) targets=("$arch_arg") ;;
    both) targets=("arm64" "x64") ;;
    *) echo "Error: --arch must be arm64, x64, or both." >&2; exit 1 ;;
esac

for tool in cmake git shasum tar install_name_tool codesign otool lipo python3; do
    command -v "$tool" >/dev/null 2>&1 || { echo "Error: '$tool' not found on PATH." >&2; exit 1; }
done

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work_dir="$repo_root/.whisper-stream-build"
out_dir="$work_dir/dist"
src_dir="$work_dir/whisper.cpp"
mkdir -p "$out_dir"

# CMake's own arch names differ from the manifest's; keep the mapping in one place.
cmake_arch_for() { [ "$1" = "arm64" ] && echo "arm64" || echo "x86_64"; }

# Homebrew installs per-prefix: Apple Silicon under /opt/homebrew, Intel (or Rosetta) under
# /usr/local. Picking the prefix by target arch is what makes a cross-arch build possible at all.
sdl2_prefix_for() { [ "$1" = "arm64" ] && echo "/opt/homebrew/opt/sdl2" || echo "/usr/local/opt/sdl2"; }

check_sdl2() {
    local target="$1" prefix dylib
    prefix="$(sdl2_prefix_for "$target")"
    dylib="$prefix/lib/libSDL2-2.0.0.dylib"

    if [ ! -f "$dylib" ]; then
        echo "Error: SDL2 for $target not found at $dylib" >&2
        if [ "$target" = "$host_arch" ]; then
            echo "  Install it with:  brew install sdl2" >&2
        else
            echo "  Cross-building $target from $host_arch needs the other Homebrew prefix." >&2
            echo "  On Apple Silicon that means the Intel brew under /usr/local:" >&2
            echo "    arch -x86_64 /usr/local/bin/brew install sdl2" >&2
            echo "  If you don't have it, build $target on an Intel Mac (or a runner) instead." >&2
        fi
        return 1
    fi

    # A dylib present at the right prefix can still be the wrong slice. Check rather than discover
    # it at link time, or worse, on a user's machine.
    if ! lipo -archs "$dylib" | tr ' ' '\n' | grep -qx "$(cmake_arch_for "$target")"; then
        echo "Error: $dylib has no $(cmake_arch_for "$target") slice (found: $(lipo -archs "$dylib"))." >&2
        return 1
    fi
}

echo "Checking SDL2 for: ${targets[*]}"
for target in "${targets[@]}"; do check_sdl2 "$target"; done

if [ ! -d "$src_dir" ]; then
    echo "Cloning whisper.cpp..."
    git clone --quiet https://github.com/ggerganov/whisper.cpp "$src_dir"
fi
echo "Checking out whisper.cpp @ $WHISPER_REF..."
git -C "$src_dir" fetch --quiet --tags origin
git -C "$src_dir" checkout --quiet "$WHISPER_REF"

for target in "${targets[@]}"; do
    cmake_arch="$(cmake_arch_for "$target")"
    sdl2_prefix="$(sdl2_prefix_for "$target")"
    build_dir="$src_dir/build-$target"
    stage_dir="$work_dir/stage-$target"

    echo
    echo "=== Building whisper-stream for $target ==="
    rm -rf "$build_dir" "$stage_dir"
    mkdir -p "$stage_dir"

    cmake -S "$src_dir" -B "$build_dir" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_OSX_ARCHITECTURES="$cmake_arch" \
        -DWHISPER_SDL2=ON \
        -DCMAKE_PREFIX_PATH="$sdl2_prefix" \
        -DBUILD_SHARED_LIBS=OFF \
        >/dev/null
    cmake --build "$build_dir" --config Release --target whisper-stream -j"$(sysctl -n hw.ncpu)" >/dev/null

    binary="$(find "$build_dir" -name whisper-stream -type f -perm +111 | head -1)"
    if [ -z "$binary" ]; then
        echo "Error: whisper-stream was not produced. WHISPER_SDL2 may have been silently ignored" >&2
        echo "  (SDL2 not found by CMake), or the target was renamed at ref $WHISPER_REF." >&2
        exit 1
    fi
    cp "$binary" "$stage_dir/whisper-stream"

    # Confirm we built what we asked for. A cross-build that silently fell back to the host arch
    # would only surface as a crash on someone else's Mac.
    if ! lipo -archs "$stage_dir/whisper-stream" | tr ' ' '\n' | grep -qx "$cmake_arch"; then
        echo "Error: built binary is $(lipo -archs "$stage_dir/whisper-stream"), expected $cmake_arch." >&2
        exit 1
    fi

    # Bundle SDL2 next to the binary and point the binary at it relatively.
    sdl2_ref="$(otool -L "$stage_dir/whisper-stream" | awk '/libSDL2/ {print $1}' | head -1)"
    if [ -n "$sdl2_ref" ]; then
        cp "$sdl2_prefix/lib/libSDL2-2.0.0.dylib" "$stage_dir/"
        chmod u+w "$stage_dir/libSDL2-2.0.0.dylib"
        install_name_tool -id "@loader_path/libSDL2-2.0.0.dylib" "$stage_dir/libSDL2-2.0.0.dylib"
        install_name_tool -change "$sdl2_ref" "@loader_path/libSDL2-2.0.0.dylib" "$stage_dir/whisper-stream"
        # install_name_tool invalidates the code signature; on Apple Silicon an unsigned-but-modified
        # binary is killed at launch. Ad-hoc signing is enough for a locally-installed tool.
        codesign --force --sign - "$stage_dir/libSDL2-2.0.0.dylib"
        codesign --force --sign - "$stage_dir/whisper-stream"
    else
        echo "Note: no SDL2 dependency recorded — it linked statically, nothing to bundle."
    fi

    # The check that matters: nothing may still point into the build host's Homebrew.
    if otool -L "$stage_dir/whisper-stream" | tail -n +2 | grep -qE '^\s+(/opt/homebrew|/usr/local)'; then
        echo "Error: whisper-stream still references build-host paths, so it would fail on any" >&2
        echo "  machine without Homebrew SDL2. Remaining references:" >&2
        otool -L "$stage_dir/whisper-stream" | tail -n +2 | grep -E '^\s+(/opt/homebrew|/usr/local)' >&2
        exit 1
    fi

    tarball="$out_dir/whisper-stream-macos-$target.tar.gz"
    # Flat archive, matching whisper-cli's: VoiceInstaller extracts straight into bin/whisper and
    # then resolves the binary name directly under it.
    tar -czf "$tarball" -C "$stage_dir" .
    echo "Packaged $tarball"
done

echo
echo "=== Manifest ==="
manifest_out="$out_dir/voice-tools-manifest.json"
if curl -fsSL --max-time 30 "$MANIFEST_URL" -o "$work_dir/manifest-current.json"; then
    echo "Merging into the published manifest."
else
    echo "Error: couldn't fetch the current manifest from $MANIFEST_URL." >&2
    echo "  Not writing a fresh one: it would drop the whisper-cli and piper entries that" >&2
    echo "  VoiceInstaller still needs. Download it by hand and re-run." >&2
    exit 1
fi

python3 - "$work_dir/manifest-current.json" "$manifest_out" "$out_dir" "${targets[@]}" <<'PY'
import hashlib, json, os, sys

current, out_path, dist, targets = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
manifest = json.load(open(current))

for target in targets:
    name = f"whisper-stream-macos-{target}"
    path = os.path.join(dist, f"{name}.tar.gz")
    data = open(path, "rb").read()
    manifest["artifacts"][name] = {
        "file": f"{name}.tar.gz",
        "sha256": hashlib.sha256(data).hexdigest(),
        "sizeBytes": len(data),
    }

with open(out_path, "w") as f:
    json.dump(manifest, f, indent=2)
    f.write("\n")

for target in targets:
    entry = manifest["artifacts"][f"whisper-stream-macos-{target}"]
    print(f"  whisper-stream-macos-{target}: sha256={entry['sha256']} size={entry['sizeBytes']}")
PY

echo "Wrote $manifest_out"
echo
echo "Note: --arch both is required before publishing. Adding only one architecture to the"
echo "manifest leaves VoiceInstaller hard-failing on the other one."

if [ "$publish" = true ]; then
    if ! command -v gh >/dev/null 2>&1; then
        echo "Error: --publish needs the gh CLI." >&2; exit 1
    fi
    if ! gh auth status >/dev/null 2>&1; then
        echo "Error: gh is not authenticated. Run 'gh auth login' first." >&2; exit 1
    fi
    echo
    echo "Uploading to $RELEASE_REPO release $RELEASE_TAG..."
    for target in "${targets[@]}"; do
        gh release upload "$RELEASE_TAG" "$out_dir/whisper-stream-macos-$target.tar.gz" \
            --repo "$RELEASE_REPO" --clobber
    done
    gh release upload "$RELEASE_TAG" "$manifest_out" --repo "$RELEASE_REPO" --clobber
    echo "Published."
else
    echo
    echo "Not published (no --publish). To publish by hand:"
    for target in "${targets[@]}"; do
        echo "  gh release upload $RELEASE_TAG $out_dir/whisper-stream-macos-$target.tar.gz --repo $RELEASE_REPO --clobber"
    done
    echo "  gh release upload $RELEASE_TAG $manifest_out --repo $RELEASE_REPO --clobber"
fi

echo
echo "Once published, VoiceInstaller needs a whisper-stream entry and a streaming transcriber"
echo "written against the real binary's flags and stdout framing (ADR-039)."
