#!/bin/sh
# Installs the latest released sophi CLI on macOS or Linux, no checkout needed:
#   curl -fsSL https://raw.githubusercontent.com/shukriev/SoPhi/main/scripts/get-sophi.sh | sh
# Pin a version with SOPHI_VERSION=cli-v0.9.0. Re-run to upgrade.
# (scripts/install.sh is the other path: build from a local checkout of main.)
set -eu

repo="shukriev/SoPhi"
share_dir="$HOME/.local/share/sophi"
bin_dir="$HOME/.local/bin"

if ! command -v java >/dev/null 2>&1; then
    echo "Error: sophi needs Java 21+, and no 'java' is on your PATH." >&2
    echo "Install one (e.g. 'brew install openjdk@21', 'sudo apt install openjdk-21-jre', or https://adoptium.net) and re-run." >&2
    exit 1
fi
java_major="$(java -version 2>&1 | head -n 1 | sed -E 's/[^"]*"([0-9]+).*/\1/')"
if [ "$java_major" -lt 21 ] 2>/dev/null; then
    echo "Error: sophi needs Java 21+, found Java $java_major." >&2
    exit 1
fi

tag="${SOPHI_VERSION:-}"
if [ -z "$tag" ]; then
    # Not /releases/latest: voice-tools releases live in the same repo.
    tag="$(curl -fsSL "https://api.github.com/repos/$repo/releases?per_page=100" \
        | grep -o '"tag_name": *"cli-v[^"]*"' | head -n 1 | sed -E 's/.*"(cli-v[^"]*)"/\1/')"
fi
if [ -z "$tag" ]; then
    echo "Error: no cli-v* release found in $repo." >&2
    exit 1
fi

echo "Downloading sophi $tag..."
mkdir -p "$share_dir" "$bin_dir"
base="https://github.com/$repo/releases/download/$tag"
curl -fsSL "$base/sophi.jar" -o "$share_dir/sophi.jar.part"
expected="$(curl -fsSL "$base/sophi.jar.sha256" | cut -d ' ' -f 1)"
if command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$share_dir/sophi.jar.part" | cut -d ' ' -f 1)"
else
    actual="$(shasum -a 256 "$share_dir/sophi.jar.part" | cut -d ' ' -f 1)"
fi
if [ "$expected" != "$actual" ]; then
    rm -f "$share_dir/sophi.jar.part"
    echo "Error: checksum mismatch for sophi.jar (expected $expected, got $actual)." >&2
    exit 1
fi
mv "$share_dir/sophi.jar.part" "$share_dir/sophi.jar"

cat > "$bin_dir/sophi" <<EOF
#!/bin/sh
exec java --enable-native-access=ALL-UNNAMED -jar "$share_dir/sophi.jar" "\$@"
EOF
chmod +x "$bin_dir/sophi"

echo "Installed sophi $tag -> $bin_dir/sophi"
case ":$PATH:" in
    *":$bin_dir:"*) ;;
    *) echo "Note: $bin_dir is not on your PATH. Add it to your shell profile:  export PATH=\"$bin_dir:\$PATH\"" ;;
esac
