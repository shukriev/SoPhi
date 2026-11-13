# Install and build

[← Back to the README](../README.md)


**Installers (no Java needed).** Download from the newest `cli-v*`
[release](https://github.com/shukriev/SoPhi/releases): `sophi-macos-arm64.pkg` (Apple Silicon), `sophi-macos-x64.pkg`
(Intel) or `sophi-windows-x64.msi`, open it, then run `sophi` in a new terminal. Until code signing is set up,
they are unsigned: on macOS approve the package once under System Settings → Privacy & Security;
on Windows choose "More info → Run anyway". To uninstall on macOS, delete `/Applications/sophi.app`
and `/usr/local/bin/sophi`; on Windows use Apps & features.

**One-liner (needs Java 21+).** No checkout required.

```bash
# macOS / Linux
curl -fsSL https://raw.githubusercontent.com/shukriev/SoPhi/main/scripts/get-sophi.sh | sh
```

```powershell
# Windows (PowerShell)
irm https://raw.githubusercontent.com/shukriev/SoPhi/main/scripts/get-sophi.ps1 | iex
```

Re-run to upgrade; set `SOPHI_VERSION=cli-v<x.y.z>` to pin a release. Maintainers publish
a release by pushing a `cli-v*` tag (`.github/workflows/cli-release.yml`).

## 🚀 Build


```bash
mvn -q -DskipTests package
```

SoPhi runs on a local model by default: start Ollama, LM Studio or llama.cpp and it finds
the server itself. Claude is opt-in (`--provider claude --model <name>` with
`ANTHROPIC_API_KEY` set); SoPhi never picks a cloud model for you.

