# Prototype — incremental decompilation via hooked DLL

## Current status

**Phase 0. Nothing has been built yet.** This repo contains only this file. No source, no
build system, no Ghidra project, no analysis done. The blocking task is Open Question 1
below (obtaining an unwrapped executable); until that is resolved, no meaningful static
analysis is possible and no code should be written.

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

Paths to record here once known (not yet established):

- Game install directory: _TBD_
- Proton prefix: _TBD_
- Ghidra project location (**keep outside this repo** — it embeds the game binary): _TBD_

## Repo layout (planned — none of this exists yet)

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
- Keep the Ghidra project out of the repo for the same reason.

## Open questions — resolve before writing real code

Blockers, in order. None are answered yet:

1. **Get an unwrapped exe.** The Steam build is Steam-DRM packed; the on-disk bytes are not
   the real code, so Ghidra will show the wrapper rather than the game. Check whether a
   retail or GOG build exists in a cleaner state — an unpacked binary with intact section
   layout and imports is worth more than any other single factor. *Everything else is
   blocked on this.*
2. **Confirm which build to standardize on.** Steam vs retail may differ; any community
   documentation will be written against a specific one. Pin the choice and note the exact
   version and file hash here once decided.
3. **Identify the compiler.** Read the PE Rich header for the exact MSVC version (expect
   2005 or 2008). This informs calling conventions, name mangling, and codegen patterns.
4. **Check for RTTI and debug leftovers.** Search `.rdata` for strings beginning `.?AV` — if
   RTTI is intact, class names and vtable layouts come back nearly free, which is the
   difference between a navigable and unnavigable project at this size. Also look for a
   leftover PDB path string (reveals the original source tree layout — use it to organize
   `src/`) and for assert/log format strings, which in 2009 retail builds often survive and
   hand over original function and file names.

## Conventions

- **Addresses are the primary key for everything.** Maintain `notes/addresses.md` mapping
  original address → our name → signature → calling convention → status. Every reversed
  function gets an entry before it gets an implementation.
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
