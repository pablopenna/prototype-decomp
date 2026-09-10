#!/usr/bin/env bash
# Export or apply annotations/ without the GUI. Ghidra must not have the project open.
# Usage (via mise): mise run export | mise run apply
set -euo pipefail
. "$(dirname "$0")/common.sh"

require_env
require_project_closed
[[ -f $PROJECT_DIR/$PROJECT_NAME.gpr ]] || die "no Ghidra project yet. Run: mise run setup"

case "${1:-}" in
  export)
    headless -process "$ENGINE_DLL" -noanalysis -readOnly \
      -scriptPath "$GHIDRA_SCRIPTS" -postScript ExportAnnotations.java ;;
  apply)
    headless -process "$ENGINE_DLL" -noanalysis \
      -scriptPath "$GHIDRA_SCRIPTS" -postScript ApplyAnnotations.java ;;
  *)
    die "usage: $0 export|apply" ;;
esac
