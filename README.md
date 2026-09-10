# Prototype — incremental decompilation via hooked DLL

A learning project: incrementally reverse engineer *Prototype* (Radical Entertainment /
Activision, 2009, PC) by reimplementing its functions one at a time in C++ and injecting
them into the running game.

The point is **learning reverse engineering and low-level programming**, not shipping a
finished port. Progress being visible and continuous matters more than progress being fast.

## Current status

**Phase 0 — tooling done, no reimplemented code yet.**

- The game code lives in `prototypeenginef.dll`, which is **not** DRM-wrapped (see
  [Findings](#findings-about-the-binary)). It has been imported into a Ghidra project and
  auto-analyzed (default analyzers, no PDB available): 103,091 functions.
- Ghidra is scriptable from Claude Code through the Ghidra MCP, and Ghidra work is versioned
  in git as text under `annotations/`.
- The unmodified game runs under Proton (see [Paths and machine notes](#paths-and-machine-notes)).

Where the work stands in detail — findings, the current plan, open questions — is in
[STATE.md](STATE.md).

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

## Getting started on a new machine

Prerequisites: Linux, [mise](https://mise.jdx.dev),
[Ghidra 12.1.2](https://github.com/NationalSecurityAgency/ghidra/releases) unzipped anywhere,
your own Steam copy of Prototype, `curl` and `unzip`.

```
git clone git@github.com:pablopenna/prototype-decomp.git && cd prototype-decomp
mise trust                 # mise ignores a repo's mise.toml until it is trusted
cp template.env .env       # then set PROTOTYPE_GAME_DIR and GHIDRA_INSTALL_DIR
mise install               # JDK 21 + uv, as pinned in mise.toml
mise run setup             # plugin, Ghidra project, import + analysis, annotations
mise run ghidra            # launch the Ghidra GUI on the project
```

`mise run setup` is idempotent (finished steps are skipped). It:

1. checks `.env` and that Ghidra is exactly 12.1.2 (the MCP plugin is built for that version);
2. checks `prototypeenginef.dll` against the pinned sha256 and **stops on a mismatch** —
   annotations are keyed by address, so a different game build would misapply them silently;
3. downloads the GhidraMCP plugin, verifies its sha256, installs it into
   `~/.config/ghidra/ghidra_12.1.2_PUBLIC/Extensions/`;
4. creates the Ghidra project in `ghidra/`, imports and auto-analyzes the DLL (this takes a
   while), records the comment baseline, and applies `annotations/`.

Then, once per machine, in the Ghidra GUI (these live in Ghidra's per-user config, so they
can't be scripted from here):

1. **Open the program.** In the project window, double-click `prototypeenginef.dll`. If
   asked whether to analyze it, answer **No** — it is already analyzed.
2. **Enable the MCP plugin.** In CodeBrowser: File → Configure → Configure All Plugins, tick
   **GhidraMCP**.
3. **Start the MCP server.** Tools → GhidraMCP → Start MCP Server.
4. **Add the repo's script directory.** Window → Script Manager, then click the
   **Manage Script Directories** button in its toolbar (a small list icon, top right) to
   open the Bundle Manager. Click the green **+**, select the folder
   `<repo>/ghidra_scripts` itself (don't go inside it), confirm, and make sure its **Enabled**
   checkbox is ticked. Back in the Script Manager, a **Prototype** category now lists
   `ExportAnnotations.java` and `ApplyAnnotations.java`.

## Working in Ghidra: annotations and git

The Ghidra database (`ghidra/`) embeds the game binary, so it is never committed. What is
committed is `annotations/`: text files holding everything *we* added — function names and
signatures, labels, typed globals, comments, and the data types under the `/prototype`
category. `ghidra_scripts/ExportAnnotations.java` writes them and `ApplyAnnotations.java`
replays them onto a fresh import (file formats are documented at the top of the export
script). A fresh machine therefore gets all past work back from git alone.

- **Put every recovered type under the `/prototype` category** (subcategories are fine).
  Types elsewhere are treated as Ghidra's own and are not exported.
- **Export before committing.** In the GUI: Script Manager → Prototype →
  `ExportAnnotations.java` → green play button; the result prints to the Console at the
  bottom of CodeBrowser. Or ask Claude to run it over the MCP. With Ghidra closed:
  `mise run export`.
- **Apply after pulling** changes made elsewhere: `mise run apply` (Ghidra closed). Apply
  overwrites entries at the same address, so export first if you have un-exported work.
- The headless tasks (`setup`, `export`, `apply`) refuse to run while the GUI has the project
  open — Ghidra locks it (`ghidra/Prototype.lock`).
- Comments are exported only if they differ from `ghidra/<program>.comment-baseline.jsonl`,
  a snapshot of the ~100k comments auto-analysis produced, taken by `setup` right after
  analysis. If that file is lost, exports also include the analysis comments.
- **Not captured yet: local variable names/types inside function bodies** (parameters are,
  via the signature). Renamed locals don't survive a re-import.

## Environment and toolchain

Development is on **Linux**; the game runs under **Proton/Wine**. This shapes the toolchain:

- Target is a **32-bit x86 Windows DLL** (the game is x86-32). Everything must be built for
  `i686`, including any third-party code. A 64-bit build will load-fail with no useful error.
- Cross-compile with `i686-w64-mingw32-g++` (MinGW-w64) or clang targeting
  `i686-pc-windows-gnu`. MSVC is not required and would be awkward here.
- **Injection: use a proxy DLL, not an injector process.** Name the DLL after something the
  game already loads, forward the real exports, and do the hooking from `DllMain`. External
  injectors are unreliable under Wine; proxy DLLs work because the loader does the work for
  us. Note: the engine DLL imports `WINMM.dll` (also d3d9, DSOUND, XINPUT1_3) but **not**
  `dinput8.dll`, so check what is actually loaded before picking the proxy name.
- Hooking library: **MinHook** — small, builds cleanly under MinGW, handles x86 trampolines.
- Static analysis: **Ghidra 12.1.2** (its x86 decompiler is the most mature option
  available). It needs JDK 21, which mise provides; `mise run ghidra` launches it with the
  right JDK.
- **Ghidra MCP** — lets Claude Code query and annotate the program open in the Ghidra GUI:
  [bethington/ghidra-mcp](https://github.com/bethington/ghidra-mcp) v6.0.0, in two halves
  whose versions must match:
  - the Ghidra plugin, `GhidraMCP-6.0.0.zip`, built for exactly Ghidra 12.1.2 (Ghidra
    rejects extensions whose `version=` differs); installed by `mise run setup`; serves on
    `127.0.0.1:8089` once started from the Tools menu;
  - the bridge Claude Code talks to, defined in `.mcp.json` and run from the pinned release
    wheel via `mise exec uv@0.11.7 -- uvx` (nothing to install).

  Upgrading means bumping both together: the zip URL and sha256 in `tools/setup.sh`, and
  the wheel URL in `.mcp.json`. Running Ghidra scripts over the MCP needs
  `GHIDRA_MCP_ALLOW_SCRIPTS=1` in Ghidra's environment, which `mise run ghidra` sets.

## Paths and machine notes

Machine-specific paths live in `.env` (gitignored; copy `template.env`):
`PROTOTYPE_GAME_DIR` (the game install) and `GHIDRA_INSTALL_DIR`.

- **Game:** Steam build, app id 10150. Code lives in `prototypef.exe` (2.4 MB) and
  `prototypeenginef.dll` (20 MB).
- **Proton prefix:** `$PROTOTYPE_GAME_DIR/../../compatdata/10150/pfx`. With Flatpak Steam,
  the Steam root is `~/.var/app/com.valvesoftware.Steam/.local/share/Steam`.
- **Save games:** `<prefix>/drive_c/users/steamuser/Documents/Prototype/slot-*.bin` (i.e.
  `C:\users\steamuser\Documents\Prototype` inside Wine). Not synced by Steam Cloud. Never
  commit save files.
- **Behavioral baseline:** the unmodified game runs under **Proton Hotfix 11.0-100**
  (verified 2026-09-10, launched from Steam). If the Proton version changes, re-check the
  vanilla game before blaming a hooked function.
- **Ghidra project:** `ghidra/Prototype.gpr` + `ghidra/Prototype.rep/`, gitignored — the
  `.rep/` database embeds a full copy of the imported game binary.

## Repo layout

```
annotations/    Ghidra work as text — see "Working in Ghidra" above
ghidra/         the Ghidra project (gitignored: it embeds the game binary)
ghidra_scripts/ Ghidra scripts (annotation export/apply); the MCP plugin finds them by name
tools/          setup.sh and the headless helpers behind the mise tasks
mise.toml       pinned tools (JDK 21, uv) and tasks: setup, ghidra, export, apply
.mcp.json       Ghidra MCP bridge for Claude Code
template.env    copy to .env (gitignored) for machine-specific paths
CLAUDE.md       instructions for AI agents working in this repo
```

Planned, not created yet:

```
src/            reimplemented functions; mirror the original source tree (see the PDB
                path under Findings)
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
2. Tell Wine to prefer our DLL over the system one — as a Steam launch option, e.g.
   `WINEDLLOVERRIDES="winmm=n,b" %command%` (adjust to the chosen proxy name).
3. **Launch the game from the terminal, not from a desktop menu entry.** On the reference
   machine (hybrid AMD + NVIDIA laptop) menu-launched apps get `PrefersNonDefaultGPU`
   applied and render black; terminal launches are unaffected.
4. Read the log file. **Log to a file, not stdout/console** — console output under
   Proton is unreliable and easy to lose. A file in the game directory is the dependable
   channel, and it is the primary debugging tool for this project.

## Findings about the binary

Originally the blocking open questions. Findings recorded 2026-09-10 from `objdump` /
`strings` on the Steam build:

1. **An unwrapped binary — resolved, no retail/GOG copy needed.** Only `prototypef.exe` is
   Steam-DRM wrapped (it has the SteamStub `.bind` section), and it is a launcher with
   ~7 KB of code. The game code is in `prototypeenginef.dll` (14 MB `.text`), which has no
   `.bind` section, a normal section layout, a clean import table (d3d9, DSOUND, WINMM,
   XINPUT1_3, binkw32, MSVCR80, …) and readable strings. Analyze the DLL; ignore the exe.
2. **Pinned build.** Steam build id `19788432` (the only build owned):
   - `prototypeenginef.dll` sha256 `d331be205bdde99897952ee88261ab7e63c5c65e36a4660e2a367886a3537071`
   - `prototypef.exe` sha256 `0cd6834f72b4fd4a762a8ce7c705bfccc94dec58e404f0c0c09299ec42eb7de6`

   If a Steam update changes these hashes, every recorded address is suspect.
3. **Compiler — mostly answered.** Linker version 8.0 and a `MSVCR80.dll` import →
   **Visual Studio 2005 (VC8)**. This informs calling conventions, name mangling and codegen
   patterns. The PE Rich header would give the exact build number; not read yet.
4. **RTTI and debug leftovers — RTTI is intact.** 10,607 `.?AV` type descriptors, with
   namespaces (`proto::`, `engine::`, `content::`, …), so class names and vtable layouts
   come back nearly free. Leftover PDB path:
   `c:\workspace\outrun\proto\prototype\playable\win32\prototypeenginef.pdb` (the PDB itself
   is not shipped). Still to do: survey assert/log format strings, which in retail builds
   of this era often preserve original function and file names.

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

## Hard constraints

- **No game binaries, assets, or extracted data in this repo. Ever.** Source, headers, build
  scripts, and analysis notes only. The build takes the user's own copy as input. This is
  what keeps the project distributable.
- Do not commit anything derived from a dumped binary that would substitute for owning the
  game (extracted archives, shader blobs, data tables ripped wholesale).
- Keep the Ghidra project out of git for the same reason. It lives in `ghidra/`, which is
  gitignored along with `*.exe` / `*.dll` — never force-add anything from there.

## Anti-goals

Things that will feel tempting and are out of scope:

- Do not fix game bugs, improve performance, or add features. Reproduce original behavior
  exactly — a "fixed" function is an unverifiable function, because the oracle is
  behavioral equivalence.
- Do not refactor across function boundaries. One original function maps to one
  reimplementation, however ugly the original is.
- Do not chase breadth. Depth in one subsystem teaches more than shallow coverage, and a
  hooked function that isn't verified running is not progress.
