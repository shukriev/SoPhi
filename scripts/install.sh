#!/bin/bash
# Builds sophi-cli from the latest source on `main` and installs it as `sophi`
# on the PATH. Safe to re-run any time to pick up new commits.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if [ -n "$(git status --short)" ]; then
    echo "Error: working tree is dirty. Commit or stash your changes before installing." >&2
    exit 1
fi

current_branch="$(git branch --show-current)"
if [ "$current_branch" != "main" ]; then
    echo "Error: expected to be on 'main', but on '$current_branch'. Checkout main first." >&2
    exit 1
fi

echo "Fetching latest changes..."
git fetch origin --quiet

if ! git pull --ff-only origin main; then
    echo "Error: local main can't fast-forward to origin/main (they've diverged)." >&2
    echo "Resolve this yourself (rebase/merge/reset as appropriate), then re-run this script." >&2
    exit 1
fi

echo "Building sophi-cli..."
mvn -pl sophi-cli -am package -DskipTests -q

jar_path="$repo_root/sophi-cli/target/sophi-cli-1.0.0-SNAPSHOT.jar"
if [ ! -f "$jar_path" ]; then
    echo "Error: expected jar not found at $jar_path" >&2
    exit 1
fi

install_dir="$HOME/.local/bin"
mkdir -p "$install_dir"
cat > "$install_dir/sophi" <<EOF
#!/bin/bash
exec java -jar "$jar_path" "\$@"
EOF
chmod +x "$install_dir/sophi"

commit="$(git rev-parse --short HEAD)"
echo "Installed sophi -> $install_dir/sophi (Sophi @ $commit)"
