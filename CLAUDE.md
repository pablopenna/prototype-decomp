# Agent instructions

@README.md

The README above is the project documentation and applies in full. This file holds only
what is specific to working here as an AI agent.

## Before proposing work

- Read the whole README first. Most of what looks like an open decision is already decided
  under "Why this and not the alternatives" — don't relitigate it.
- Check "Current status" and "Findings about the binary" for where things stand, and update
  them in the README when that changes.

## Working with the owner

- The owner has no prior reverse-engineering experience. Explain the underlying concept
  (what a calling convention or vtable is, why an address is relative, …) rather than only
  producing working code. Teaching is the goal; speed is not.
- Keep changes inside the repo; install dependencies via mise (`mise.toml`). Anything that
  must live outside the repo gets automated in `tools/setup.sh` and called out explicitly.
- Commit directly on `master` (no feature branches), and only when asked. Push only when
  asked.

## Using Ghidra through the MCP

- `mcp__ghidra__*` tools only work while the Ghidra GUI runs with `prototypeenginef.dll`
  open. The owner starts it with `mise run ghidra`.
- If the tools report "No program loaded" or the wrong project, call `list_instances` and
  then `connect_instance("Prototype")` — the bridge can stay attached to an earlier Ghidra
  session.
- While the GUI is open the project is locked, so `mise run export` / `apply` / `setup`
  refuse to run. Use the MCP instead: `run_ghidra_script` with
  `script_name="ExportAnnotations.java"` (verified 2026-09-10). Pass the bare name: the
  plugin finds it in `<repo>/ghidra_scripts`. It runs a temporary copy from
  `~/ghidra_scripts` and deletes it afterwards, which is why the scripts locate the repo from
  the Ghidra project's location rather than from their own path.
- `run_ghidra_script` needs `GHIDRA_MCP_ALLOW_SCRIPTS=1`, which `mise run ghidra` sets. If
  Ghidra was started some other way, ask the owner to restart it with `mise run ghidra`.
- Every data type you create goes under the `/prototype` category, or it won't be exported.
- After changing things in Ghidra, run the export and review the `annotations/` diff before
  committing — the Ghidra database is not in git, so un-exported work is lost on re-import.
- Headless import/analysis of the 14 MB DLL takes tens of minutes: run it in the
  background, with `GHIDRA_HEADLESS_MAXMEM=8G` (set in `tools/common.sh`).

## Safety

- Before committing, check what will be staged (`git add --dry-run .`): nothing from
  `ghidra/`, no `.exe`/`.dll`, no save files, no `.env`.
- Game files are the owner's copy and are only ever read, never modified: the game
  directory, the Proton prefix, and the save games.
