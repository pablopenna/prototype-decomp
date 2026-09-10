# State — where we left off

Last updated 2026-09-10. Tooling is done (see README); first RE session mapped the engine's
entry points and its launch-options subsystem. **None of the findings below are recorded in
Ghidra or `notes/` yet** — that is step 1 of the plan.

All addresses are in `prototypeenginef.dll` as Ghidra shows them (image base `0x10000000`).

## Findings

### Engine layout (RTTI survey)

Class names demangled from the DLL's RTTI strings (4,180 non-template classes; 7,628
template instantiations not broken down):

| Namespace | Classes | Notes |
|---|---|---|
| `proto` | 2,283 | Game code. 1,154 in `proto::fightitems` — the combat/motion system, built from small data-driven condition/action classes. |
| `engine` | 729 | Radical's engine layer: animation, controller, camera, render, driving, world, streaming. |
| `pure3d` | 288 | Radical's rendering library. |
| `core` | 97 | Allocators, file I/O ("Drive" requests), crash handling, input (XInput *and* DirectInput). |
| `content` | 66 | Reflection/meta-types, CRC streams. |
| others | | `ravenphysics`, `audio`, `music`, `MassiveAdClient3`, `scaleformp3d`, `choreo`, `crypto` (MD5, Blowfish, TEA), `math`, `container`, … |

### `math::Vector` is not a hookable class

It has RTTI only because the `content` reflection system calls `typeid(math::Vector)`
(e.g. `1038af80` just returns `&math::Vector::RTTI_Type_Descriptor`; `10570090` builds a
`Method7<proto::StreamManager, void, math::Vector ×7>` script binding). It has no vtable
and no methods — vector math is inlined everywhere. Recovered fact: **`sizeof(math::Vector)
== 12`** (three floats). `TransformVector` (`10966150`) is Scaleform's `GMatrix2D`, not game
code.

### The exe ↔ engine interface

`prototypef.exe` imports nothing from the engine: it `LoadLibraryA`s
`prototypeenginef.dll` and `GetProcAddress`es six plain-C exports. The exe owns the window
and the message pump (`PeekMessage`/`TranslateMessage`/`DispatchMessage`); the DLL does
everything else. The other 72 exports are Scaleform.

| Address | Export | Notes |
|---|---|---|
| `1085efb0` | `EngineInitialize(Params *)` | `params[0]` is the command line, `params[1]` is used twice (window handle?). Loads options, registers the `EngineRealTimeTask` / `EngineSimulationTask` / `EngineRenderTask` tasks. |
| `1085eb20` | `EngineService` | `bool __cdecl (bool a, bool b)` — confirmed from disassembly (plain `RET`, args at `[ESP+4]`/`[ESP+8]`, no frame pointer). Called every frame. Returns false early if `108374e0()` is false; `a` → calls `10838b40(0,2,0)`; `b` → sleeps 100 ms (`10644b40(100)`) when unfocused unless `NoSleepOnLostFocus` or `windowed` is set. |
| `1085e340` | `EngineTerminate` | not read yet |
| `1085e560` | `EngineKeyboardService` | not read yet |
| `1085e590` | `EngineMouseService` | not read yet |
| `1085e6d0` | `EngineWindowFocus` | not read yet |

### Launch-options subsystem (mapped, candidate first slice)

Options come from the command line and from `args.txt` (not present in the game dir; one
option per line), into one global table. Options seen: `windowed`, `NoSleepOnLostFocus`,
`Paranoid`, `NoGameState`, `Replay`, `ReplayMode` (`record` / `rerecord` / `replay`),
`ReplayDesync`, `DesyncHunt`.

| Address | Proposed name | Behavior |
|---|---|---|
| `10848e70` | `HasOption(const char *name)` | Case-insensitive; matches `name` or `name=value`. Returns bool in `AL` (upper EAX bytes are garbage: `& 0xffffff00`). 46 callers. |
| `10848cf0` | `LoadOptionsFile(const char *path)` | Reads `path` via the engine file system, lines of up to 0x100 chars, appends each to the table. |
| `10848c70` | `ParseCommandLineOptions(char *cmdline)` | Copies the command line into the buffer and `strtok`s it on `" \t,"` (`10e708b4`). |
| `1063b6b0` | `strnicmp`-like | `int (const char *a, const char *b, int n)`, returns `tolower(*a) - tolower(*b)`. Sign-extends chars in the loop but zero-extends in the final subtraction — a quirk to reproduce. |
| `1063afc0` | `tolower`-like | Pure leaf. Tests only the **low byte** against `'A'..'Z'` but adds `0x20` to the **whole** int. |

Globals: option pointers `char *[64]` at `1130eed0`; count at `1130efd4`; string buffer
(1024 bytes) at `1130ead0`; bytes used at `1130efd0`. No bounds checks visible — reproduce,
don't fix.

### Register hazard (applies to every replacement)

`1063b6b0` keeps a character in `DL` across `CALL 1063afc0` (`1063b6cd: MOV DL,[ESI]` →
call → `1063b6d8: MOVSX ECX,DL`). The standard convention lets a callee clobber `EDX`; MSVC
relied on this particular callee not doing so. A C++ replacement may clobber `EDX` and break
every option lookup. **Convention to adopt: replacements run behind a thunk that preserves
`ECX` and `EDX`** (except functions returning 64-bit values in `EDX:EAX`). Preserving extra
registers can never break a caller.

## Plan

1. **Record the findings.** Rename/type the functions and globals above in Ghidra (types
   under `/prototype`), export annotations, create `notes/addresses.md` with an entry per
   function (address, name, signature, convention, status). Commit.
2. **Milestone 1 — pipeline.** CMake + MinGW i686 toolchain, proxy DLL, file logger,
   MinHook. Pass-through hook on `EngineService`, address from `GetProcAddress` (safe: it is
   only ever called through a function pointer, so its callers follow the standard ABI).
   Log the first N calls and their arguments. Verify in the running game.
3. **Milestone 2 — first reimplementation.** `tolower` (`1063afc0`) behind the
   register-preserving thunk, checked against the original for every byte value (plus the
   whole-int quirk) before switching it on. Then `strnicmp` (`1063b6b0`), then `HasOption`
   (`10848e70`) — a complete vertical slice of the options subsystem.

## Open questions / unverified

- **Proxy DLL name.** The exe loads the engine with `LoadLibraryA`; the engine imports
  WINMM, d3d9, DSOUND, XINPUT1_3, binkw32, MSVCR80 but not `dinput8.dll` — yet `core` has
  DirectInput classes, so dinput8 is probably loaded at runtime. Decide in milestone 1.
- **Launch options via Steam.** Does the exe pass its command line through to
  `EngineInitialize`? If so, `windowed` etc. can be set as Steam launch options instead of
  creating `args.txt` in the game dir. Not tested.
- **Replay system.** `Replay` / `ReplayMode` / `DesyncHunt` suggest a deterministic
  input record/replay feature. If it works in the retail build, it would be a much stronger
  behavioral-equivalence oracle than playing by hand. Not tested.
- **Headless annotation tasks** (`mise run export` / `apply`) have not been re-run since the
  scripts moved to `ghidra_scripts/` (they need Ghidra closed). The MCP route is verified.
- Local variable names are not captured by the annotations export (known gap, see README).
