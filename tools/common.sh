# Shared settings for the scripts in tools/. Source it, don't run it.
# Meant to be run through mise (`mise run <task>`), which puts JDK 21 on PATH for Ghidra.

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -f "$REPO_ROOT/.env" ]]; then
  set -a
  . "$REPO_ROOT/.env"
  set +a
fi

GHIDRA_VERSION=12.1.2
ENGINE_DLL=prototypeenginef.dll
# Pinned game build (Steam build id 19788432) — see Open Question 2 in CLAUDE.md.
ENGINE_DLL_SHA256=d331be205bdde99897952ee88261ab7e63c5c65e36a4660e2a367886a3537071
PROJECT_DIR=$REPO_ROOT/ghidra
PROJECT_NAME=Prototype
GHIDRA_SCRIPTS=$REPO_ROOT/tools/ghidra
# Headless Ghidra defaults to a 2G heap, which is tight for a 14 MB .text.
export GHIDRA_HEADLESS_MAXMEM=${GHIDRA_HEADLESS_MAXMEM:-8G}

step() { printf '\n==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

require_env() {
  local var
  for var in PROTOTYPE_GAME_DIR GHIDRA_INSTALL_DIR; do
    [[ -n "${!var:-}" ]] || die "$var is not set. Run: cp template.env .env, then fill it in."
  done
}

require_project_closed() {
  [[ ! -e "$PROJECT_DIR/$PROJECT_NAME.lock" ]] ||
    die "the Ghidra project is open (found $PROJECT_NAME.lock). Close it in Ghidra first."
}

headless() {
  "$GHIDRA_INSTALL_DIR/support/analyzeHeadless" "$PROJECT_DIR" "$PROJECT_NAME" "$@"
}
