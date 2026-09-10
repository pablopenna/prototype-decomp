#!/usr/bin/env bash
# Development setup for a fresh machine. Safe to re-run: finished steps are skipped.
# Run via: mise run setup
set -euo pipefail
. "$(dirname "$0")/common.sh"

MCP_VERSION=6.0.0
MCP_ZIP_URL=https://github.com/bethington/ghidra-mcp/releases/download/v$MCP_VERSION/GhidraMCP-$MCP_VERSION.zip
MCP_ZIP_SHA256=867731de27d5143632a010943b907a6485dd54d0e19729e2f85ee9f692c99873

step "Checking .env"
require_env
echo "ok"

step "Checking Ghidra $GHIDRA_VERSION at $GHIDRA_INSTALL_DIR"
props=$GHIDRA_INSTALL_DIR/Ghidra/application.properties
[[ -f $props ]] ||
  die "no Ghidra there. Download $GHIDRA_VERSION from https://github.com/NationalSecurityAgency/ghidra/releases and set GHIDRA_INSTALL_DIR in .env."
have=$(sed -n 's/^application.version=//p' "$props")
[[ $have == "$GHIDRA_VERSION" ]] ||
  die "found Ghidra $have, need exactly $GHIDRA_VERSION (the GhidraMCP plugin is built for that version)."
echo "ok"

step "Verifying $ENGINE_DLL is the pinned game build"
dll=$PROTOTYPE_GAME_DIR/$ENGINE_DLL
[[ -f $dll ]] || die "$dll not found. Check PROTOTYPE_GAME_DIR in .env."
sum=$(sha256sum "$dll" | cut -d' ' -f1)
[[ $sum == "$ENGINE_DLL_SHA256" ]] ||
  die "$ENGINE_DLL has sha256 $sum, expected $ENGINE_DLL_SHA256. A different game build means every address in annotations/ may be wrong."
echo "ok"

step "Installing the GhidraMCP $MCP_VERSION plugin"
ext_dir=${XDG_CONFIG_HOME:-$HOME/.config}/ghidra/ghidra_${GHIDRA_VERSION}_PUBLIC/Extensions
if [[ -f $ext_dir/GhidraMCP/lib/GhidraMCP-$MCP_VERSION.jar ]]; then
  echo "already installed in $ext_dir"
else
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL -o "$tmp/GhidraMCP.zip" "$MCP_ZIP_URL"
  echo "$MCP_ZIP_SHA256  $tmp/GhidraMCP.zip" | sha256sum --check --quiet - ||
    die "downloaded GhidraMCP zip does not match the pinned sha256"
  mkdir -p "$ext_dir"
  rm -rf "$ext_dir/GhidraMCP"
  unzip -q "$tmp/GhidraMCP.zip" -d "$ext_dir"
  echo "installed in $ext_dir (restart Ghidra if it is running)"
fi

step "Creating the Ghidra project"
if [[ -f $PROJECT_DIR/$PROJECT_NAME.gpr ]]; then
  echo "already exists at $PROJECT_DIR; skipping."
  echo "To pull annotations from git into it: mise run apply"
else
  mkdir -p "$PROJECT_DIR"
  echo "Importing and auto-analyzing $ENGINE_DLL, then applying annotations/."
  echo "Auto-analysis of 14 MB of code takes a while."
  # The baseline must be recorded after analysis and before anything human is applied:
  # it is how ExportAnnotations tells analysis-made comments from ours.
  headless -import "$dll" -scriptPath "$GHIDRA_SCRIPTS" \
    -postScript ExportAnnotations.java record-comment-baseline \
    -postScript ApplyAnnotations.java
fi

cat <<'EOF'

Setup done. One-time steps in the Ghidra GUI (start it with: mise run ghidra):
  1. Open prototypeenginef.dll in CodeBrowser (answer "No" if asked to analyze).
  2. File > Configure > Configure All Plugins: enable GhidraMCP.
  3. Tools > GhidraMCP > Start MCP Server.
  4. Window > Script Manager > Manage Script Directories: add <repo>/ghidra_scripts.
EOF
