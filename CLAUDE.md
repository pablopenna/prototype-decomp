# Prototype — incremental decompilation via hooked DLL

## Current status

**Phase 0. Nothing has been built yet.** No source, no build system, no analysis done.
Tooling is in place: a Ghidra project at `ghidra/Prototype` (gitignored), the Ghidra MCP
(`.mcp.json`), and git sync for Ghidra work via `annotations/` (see "Annotations"). Open Questions 1, 3 and 4 have been answered by inspecting the binaries (see
below): the game code lives in `prototypeenginef.dll`, which is **not** DRM-wrapped. That
DLL has been imported and auto-analyzed (headless, default analyzers, no PDB available).
Next step: survey the recovered RTTI classes and pick a first subsystem.

If you are a new session: read this whole file before proposing work. Most of what looks
like an open decision here has already been decided and is recorded under
"Why this and not the alternatives".

## What this is

A learning project: incrementally reverse engineer *Prototype* (Radical Entertainment /
Activision, 2009, PC) by reimplementing its functions one at a time in C++ and injecting
them into the running game.

The point is **learning reverse engineering and low-level programming**, not shipping a
finished port. Progress being visible and continuous matters more than progress being fast.
The owner has no prior RE experience — explanations should assume that, and should teach
the underlying concept rather than just producing working code.

## Approach

Replace the original binary's functions one at a time from an injected DLL:

1. Pick a function in Ghidra.
2. Reverse it, write a readable C++ equivalent.
3. Compile into the DLL, hook the original's address so calls land in our version.
4. Run the game. If behavior is identical, that function is done.

**We are not doing a byte-matching decomp.** The correctness oracle is *behavioral
equivalence in the running game*, not identical compiler output. This means we write clear,
idiomatic C++ rather than contorting source to match MSVC's codegen. That tradeoff is
deliberate: matching is a much harsher standard and would kill a solo project.

The long-term end state — a standalone exe with no original binary — is a north star, not a
plan. It is reached (if ever) by growing the DLL until the original's role shrinks to nothing.

## Why this and not the alternatives

Decided after evaluating the options. Recorded so we don't relitigate:

- **Static recompilation (Xbox 360 build + XenonRecomp)** — rejected as the primary path.
  Technically the shortest route to a running native exe, and has a working reference
  (*Unleashed Recompiled*), but teaches compiler engineering rather than reverse engineering,
  and requires dumping a 360 disc.
- **Recomp of the PC build** — not possible in any meaningful sense. Recomp bridges an
  architecture/OS gap; x86 Windows → x86 Windows has none. Separately, x86 is close to
  worst-case for static recompilation: variable-length instructions mean you can't decode
  reliably without already knowing control flow, and `.text` interleaves code and data.
  Every real recomp project targets a fixed-width RISC ISA for this reason.
- **Classic full decomp** — rejected. Pays out only at 100%; at 60% you have C++ that
  doesn't link and a game you've never seen boot. The hooked-DLL structure exists
  specifically to fix that property.
- **PS3 build** — rejected outright. Encrypted EBOOT, Cell SPU code, and the output would
  be a PowerPC binary, not an exe.

If the goal were simply *playing Prototype on modern hardware*, the answer would be
dgVoodoo2/DXVK plus community patches — not this. This is for the learning.

## Environment

Development is on **Linux**; the game runs under **Proton/Wine**. This shapes the toolchain:

- Target is a **32-bit x86 Windows DLL** (the game is x86-32). Everything must be built for
  `i686`, including any third-party code. A 64-bit build will load-fail with no useful error.
- Cross-compile with `i686-w64-mingw32-g++` (MinGW-w64) or clang targeting
  `i686-pc-windows-gnu`. MSVC is not required and would be awkward here.
- **Injection: use a proxy DLL, not an injector process.** Name the DLL after something the
  game already loads (`dinput8.dll`, `winmm.dll`, `version.dll`), forward the real exports,
  and do the hooking from `DllMain`. External injectors are unreliable under Wine; proxy
  DLLs work because the loader does the work for us.
- Hooking library: **MinHook** — small, builds cleanly under MinGW, handles x86 trampolines.
- Static analysis: **Ghidra** (its x86 decompiler is the most mature option available).
  Installed at `/opt/__my_files__/apps/ghidra/ghidra_12.1.2_PUBLIC` (12.1.2; needs JDK 21,
  which it picks up from mise — the system `java` is 17).
- **Ghidra MCP** (lets Claude query/annotate the open program): bethington/ghidra-mcp v6.0.0.
  Two halves, versions must match:
  - Plugin `GhidraMCP-6.0.0.zip` (built for exactly Ghidra 12.1.2 — Ghidra rejects extensions
    whose `version=` differs), installed to `~/.config/ghidra/ghidra_12.1.2_PUBLIC/Extensions/`
    by `mise run setup`.
    Enable in CodeBrowser via File → Configure → Configure All Plugins → GhidraMCP, then
    Tools → GhidraMCP → Start MCP Server. Serves HTTP on `127.0.0.1:8089`.
  - Bridge (stdio MCP ↔ that HTTP port): defined in `.mcp.json`, run via
    `mise exec uv@0.11.7 -- uvx` from the pinned release wheel. Nothing to install.
  - The GUI must be running with a program open for the tools to work. Upgrading = bump both
    the plugin zip (`tools/setup.sh`, with its sha256) and the wheel URL (`.mcp.json`) together.
  - Running Ghidra scripts over the MCP needs `GHIDRA_MCP_ALLOW_SCRIPTS=1` in Ghidra's
    environment; `mise run ghidra` sets it.

Paths to record here once known (not yet established):

- Game install directory: `PROTOTYPE_GAME_DIR` in `.env` (gitignored, machine-specific; copy
  `template.env` to create it). Steam build (app id 10150, Flatpak Steam on this machine).
  Code lives in `prototypef.exe` (2.4 MB) and `prototypeenginef.dll` (20 MB).
- Proton prefix: `$PROTOTYPE_GAME_DIR/../../compatdata/10150/pfx`. The unmodified game runs
  under **Proton Hotfix 11.0-100** (verified 2026-09-10, launched from Steam). That run is
  the behavioral baseline; if the Proton version changes, re-check the vanilla game before
  blaming a hooked function.
- Save games: `<prefix>/drive_c/users/steamuser/Documents/Prototype/slot-*.bin` (i.e.
  `C:\users\steamuser\Documents\Prototype` inside Wine). Not synced by Steam Cloud. Never
  commit save files.
- Ghidra project location: `ghidra/Prototype.gpr` (+ `ghidra/Prototype.rep/`) inside this
  repo, **gitignored** — its `.rep/` database embeds a full copy of the imported game binary.
  Open it in the GUI (File → Open Project) so the MCP sees it.

## Setting up a new machine

Prerequisites: [mise](https://mise.jdx.dev), Ghidra 12.1.2 unzipped anywhere, your own
Steam copy of Prototype, `curl` and `unzip`.

```
git clone <repo> && cd prototype-decomp
mise trust                 # mise ignores a repo's mise.toml until it is trusted
cp template.env .env       # then set PROTOTYPE_GAME_DIR and GHIDRA_INSTALL_DIR
mise install               # JDK 21 + uv, as pinned in mise.toml
mise run setup             # plugin, Ghidra project, import + analysis, annotations
mise run ghidra            # launch the GUI on the project
```

`setup` is idempotent. It refuses to continue if `prototypeenginef.dll` doesn't match the
pinned hash: annotations are keyed by address, so a different build would misapply them
silently. It ends by printing four one-time GUI steps (open the program, enable the
GhidraMCP plugin, start its server, add `tools/ghidra` as a script directory) — those live
in Ghidra's per-user config and aren't scriptable from here.

## Annotations: how Ghidra work gets into git

The Ghidra database (`ghidra/`) embeds the game binary, so it is never committed. What is
committed is `annotations/`: text files holding everything *we* added — function names and
signatures, labels, typed globals, comments, and the data types under the `/prototype`
category. `tools/ghidra/ExportAnnotations.java` writes them and `ApplyAnnotations.java`
replays them onto a fresh import (formats are documented at the top of the export script).

- **Put every recovered type under the `/prototype` category** (subcategories are fine).
  Types elsewhere are treated as Ghidra's own and are not exported.
- **Export before committing.** In the GUI: Script Manager → `ExportAnnotations.java` (or ask
  Claude to run it over the MCP). With Ghidra closed: `mise run export`.
- **Apply after pulling** changes made elsewhere: `mise run apply` (Ghidra closed). Apply
  overwrites entries at the same address, so export first if you have un-exported work.
- Comments are exported only if they differ from `ghidra/<program>.comment-baseline.jsonl`,
  a snapshot of what auto-analysis produced, taken by `setup` right after analysis. If that
  file is lost, exports also include the analysis comments.
- **Not captured yet: local variable names/types inside function bodies** (parameters are,
  via the signature). Renamed locals don't survive a re-import.

## Repo layout

```
annotations/    Ghidra work as text — see "Annotations" above
ghidra/         the Ghidra project (gitignored: it embeds the game binary)
tools/          setup.sh, headless helpers, and Ghidra scripts (tools/ghidra/)
mise.toml       pinned tools (JDK 21, uv) and tasks: setup, ghidra, export, apply
.mcp.json       Ghidra MCP bridge for Claude Code
template.env    copy to .env (gitignored) for machine-specific paths
```

Planned, not created yet:

```
src/            reimplemented functions; mirror the original source tree once the
                PDB path string reveals it (see Open Question 4)
include/        recovered struct and class definitions
third_party/    MinHook
notes/          RE notes, one file per subsystem
notes/addresses.md   the address → name → signature map (see Conventions)
cmake/          MinGW i686 toolchain file
CMakeLists.txt
```

## Build and run (planned — commands not yet valid)

Intended build:

```
cmake -B build -DCMAKE_TOOLCHAIN_FILE=cmake/mingw32.cmake
cmake --build build
```

Intended test loop:

1. Copy the built DLL into the game directory under its proxy name.
2. Tell Wine to prefer our DLL over the system one — as a Steam launch option:
   `WINEDLLOVERRIDES="dinput8=n,b" %command%`
3. **Launch the game from the terminal, not from a desktop menu entry.** On this machine
   (hybrid AMD + NVIDIA laptop) menu-launched apps get `PrefersNonDefaultGPU` applied and
   render black; terminal launches are unaffected.
4. Read the log file. **Log to a file, not stdout/console** — console output under
   Proton is unreliable and easy to lose. A file in the game directory is the dependable
   channel, and it is the primary debugging tool for this project.

## Hard constraints

- **No game binaries, assets, or extracted data in this repo. Ever.** Source, headers, build
  scripts, and analysis notes only. The build takes the user's own copy as input. This is
  what keeps the project distributable.
- Do not commit anything derived from a dumped binary that would substitute for owning the
  game (extracted archives, shader blobs, data tables ripped wholesale).
- Keep the Ghidra project out of git for the same reason. It lives in `ghidra/`, which is
  gitignored along with `*.exe` / `*.dll` — never force-add anything from there.

## Open questions — resolve before writing real code

Blockers, in order. Findings recorded 2026-09-10 from `objdump`/`strings` on the Steam build:

1. **Get an unwrapped exe.** The Steam build is Steam-DRM packed; the on-disk bytes are not
   the real code, so Ghidra will show the wrapper rather than the game. Check whether a
   retail or GOG build exists in a cleaner state — an unpacked binary with intact section
   layout and imports is worth more than any other single factor. *Everything else is
   blocked on this.*
   **Resolved — not needed.** Only `prototypef.exe` is wrapped (it has the SteamStub `.bind`
   section), and it is a ~7 KB-of-code launcher. The game code is in
   `prototypeenginef.dll` (14 MB `.text`), which has no `.bind` section, a normal section
   layout, a clean import table (d3d9, DSOUND, WINMM, XINPUT1_3, binkw32, MSVCR80, …) and
   readable strings. Analyze the DLL; ignore the exe.
2. **Confirm which build to standardize on.** Steam vs retail may differ; any community
   documentation will be written against a specific one. Pin the choice and note the exact
   version and file hash here once decided.
   **Current build (Steam, the only one owned):** Steam build id `19788432`.
   - `prototypeenginef.dll` sha256 `d331be205bdde99897952ee88261ab7e63c5c65e36a4660e2a367886a3537071`
   - `prototypef.exe` sha256 `0cd6834f72b4fd4a762a8ce7c705bfccc94dec58e404f0c0c09299ec42eb7de6`
   If a Steam update changes these hashes, every recorded address is suspect.
3. **Identify the compiler.** Read the PE Rich header for the exact MSVC version (expect
   2005 or 2008). This informs calling conventions, name mangling, and codegen patterns.
   **Mostly answered:** linker version 8.0 and a `MSVCR80.dll` import → **Visual Studio
   2005 (VC8)**. The Rich header would give the exact build number; not yet read.
4. **Check for RTTI and debug leftovers.** Search `.rdata` for strings beginning `.?AV` — if
   RTTI is intact, class names and vtable layouts come back nearly free, which is the
   difference between a navigable and unnavigable project at this size. Also look for a
   leftover PDB path string (reveals the original source tree layout — use it to organize
   `src/`) and for assert/log format strings, which in 2009 retail builds often survive and
   hand over original function and file names.
   **Answered — RTTI is intact.** 10,607 `.?AV` type descriptors in the engine DLL, with
   namespaces (`proto::`, `engine::`, `content::`, …). PDB path:
   `c:\workspace\outrun\proto\prototype\playable\win32\prototypeenginef.pdb` (the PDB itself
   is not shipped). Assert/log format strings not yet surveyed.

## Conventions

- **Addresses are the primary key for everything.** Maintain `notes/addresses.md` mapping
  original address → our name → signature → calling convention → status. Every reversed
  function gets an entry before it gets an implementation.
- **Never hardcode absolute addresses in the DLL.** `prototypeenginef.dll` prefers base
  `0x10000000` but has a `.reloc` table, so the loader may place it elsewhere. Record
  addresses as Ghidra shows them (image base `0x10000000`); at runtime compute
  `GetModuleHandleA("prototypeenginef.dll") + (addr - 0x10000000)`.
- Name functions after what they do once understood; keep the raw `sub_XXXXXXXX` address in
  a comment so it stays greppable against Ghidra.
- Expect `__thiscall` and `__fastcall` throughout — MSVC C++ of this era. Getting the
  convention wrong is the single most common source of silent stack corruption here.
- Prefer starting with leaf functions (no outgoing calls) — self-contained and verifiable.
- When a hooked function misbehaves, suspect the calling convention and struct layout
  before suspecting the logic.

## Anti-goals

Things that will feel tempting and are out of scope:

- Do not fix game bugs, improve performance, or add features. Reproduce original behavior
  exactly — a "fixed" function is an unverifiable function, because the oracle is
  behavioral equivalence.
- Do not refactor across function boundaries. One original function maps to one
  reimplementation, however ugly the original is.
- Do not chase breadth. Depth in one subsystem teaches more than shallow coverage, and a
  hooked function that isn't verified running is not progress.
