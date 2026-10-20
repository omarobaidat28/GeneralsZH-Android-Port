# Phase 5 build triage log (feature/crossplay-determinism)

## ISSUE 1 — g_gameenginedevice: `unknown type name 'ScopedFPUGuard'` (001, 21:35 20/10/2026)

**Stage:** `[2/7] build 60 Hz`, 6 clang errors, first at W3DView.cpp:2547 and OpenALAudioManager.cpp:521+4.

**Root cause:** this repo builds Core/GameEngineDevice **twice** (base-game flavor `g_` and
Zero Hour flavor `z_`). Both of these Core source files do
`#include "GameLogic/FPUControl.h"`, which resolves through each flavor's include dirs:
the `g_` flavor resolves to `Generals/Code/GameEngine/Include/GameLogic/FPUControl.h`
— the base-engine mirror tree — while Phase 2 had only added `ScopedFPUGuard` to the
Zero Hour tree (`GeneralsMD/Code/GameEngine/Include/GameLogic/FPUControl.h`).
Hence: header found (no "file not found"), class missing. The `z_` flavor compiled fine,
which is why the build reached 2372/2275 units before failing.

**Fix (applied):** mirrored the two Phase-2 changes into the base tree, marked with
"Mirored from GeneralsMD" annotations:
- `Generals/Code/GameEngine/Include/GameLogic/FPUControl.h` — same `ScopedFPUGuard` class
  (byte-identical body; added cross-flavor note in the doc comment).
- `Generals/Code/GameEngine/Source/GameLogic/System/GameLogic.cpp` — same ARM64 FPCR block
  in `setFPMode()` (FZ/RMode/traps cleared), same `ScopedFPUGuard fpuGuard;` head of
  `GameLogic::update()`.

**Lesson (for the dev blog):** every FPU/handshake edit touching `GameLogic/FPUControl.h`
or `GameLogic/System/GameLogic.cpp` exists on **two include paths** in this repo; a Core
GameEngineDevice consumer always needs both. GeneralsX-style mirror edits must be applied
to `Generals/Code` and `GeneralsMD/Code` in the same change, or checked in a task list.

## ISSUE 2 — gradle: `AAPT2 aapt2-8.5.2-...-linux Daemon #0: Daemon startup failed` / `Syntax error: "(" unexpected` (002, 21:52 20/10/2026)

**Stage:** `[5/7] package APK`, `:app:processDebugResources` failed for every AAR transform.

**Root cause:** same host-arch problem as the NDK, one layer up. AGP 8.5.2 bundles
`aapt2-8.5.2-11315950-linux` as an **x86_64 ELF**; on this aarch64 Android/Termux host the
kernel cannot exec it and the shell reports the binary as a script ("Syntax error: ("
unexpected"). The NDK (r29) has official/community aarch64 builds; AGP's aapt2 does not.

**Fix (applied):**
1. Fetched `android-sdk-aarch64.tar.xz` from the same trusted source as our NDK
   (Lzhiyong/termux-ndk `android-sdk` release, 271MB) — native aarch64 rebuilds of the
   SDK build-tools. Selected `build-tools/37.0.0/aapt2` (checked: aarch64 ELF,
   runs natively, prints version 2.20-OS3.0.305.0.WOLCNXM).
2. Pointed AGP at it via the documented property `android.aapt2FromMavenOverride=` —
   **but note where it must live:** this environment presets
   `GRADLE_USER_HOME=/data/data/com.termux/files/home/.gradle` (Termux integration), so
   user-level properties must be written to *that* dir, not `$HOME/.gradle`. First retry
   wrote `/root/.gradle/gradle.properties` and the property was silently ignored
   ("WRAPPER NEVER CALLED" — verified with a logging wrapper shim at the override path).
   Second refinement: AGP also *validates* the override path ("Custom AAPT2 location
   does not point to an AAPT2 executable") — the file must be a plain executable named
   `aapt2` on an exec-permitted filesystem (`/tmp` shim failed; `/root/gx-tools/aapt2`
   wrapper works). Escalation ladder for this class of failure:
   wrong HOME → wrong file → invalid path → real invocation.

## ISSUE 3 — "FPCR asm missing from libmain.so" — FALSE ALARM (003, 22:07 20/10/2026)

**Observation:** `llvm-objdump -d libmain.sym.so | grep -c fpcr` = 0 although the guard class
compiled into the two failing TUs.

**Resolution:** two errors in the SEARCH, not the build:
1. LLVM's newer disassembler prints the register name **uppercase** (`mrs x8, FPCR`),
   so `grep fpcr` never matched.
2. The register is not fixed: NDK clang selected `x8` here (`d53b4408`), where the
   standalone gcc test earlier emitted `x0` (`d53b4400`). Register-agnostic masked
   scan `\x00-\x1f \x44 \x3b \xd5` (LE-opcode tail of `mrs Xn, fpcr`) against the
   final object and the stripped APK binaries found **11 mrs + 11 msr** sites —
   `setFPMode()` is inlined at every call site, so the count is per-inline-site.

**Ground truth for the record:** `libmain.so` and `libmain60.so` inside
`GeneralsXZH-android-local.apk` both carry the ARM64 FPCR enforcement (Phase 2),
and the runtime messages of the REMOVED signed-gate are absent (grep-verified), while
the `gx_pc_compat.txt` claim path strings are present (2).

## ISSUE 4 — Test-channel doctrine: APK built without `GX_UPDATE_CHANNEL=test` (004, 22:05 20/10/2026)

**Observation:** the first successful APK was packaged as a *release-channel* build
(no `assets/update_channel.txt`), i.e. its launcher would poll the production `updates`
branch — wrong for what is, by versionName and by nature (crossplay bypass branch),
a test build. `AGENTS.md`: **test builds set `GX_UPDATE_CHANNEL=test`** so OTA
readers land on `updates-test`, never on what players get.

**Root cause:** my pipeline wrapper replicated the package stage but did not carry
every env the repo's conventions require; `GX_UPDATE_CHANNEL` is consumed only in
`package-android-zh.sh` (it writes `assets/update_channel.txt`), so the mistake was
cheap to make and invisible to the build itself.

**Fix (applied):** repackaged with `GX_UPDATE_CHANNEL=test` (packaging-only, ~1 min —
the flag is packaging-layer, no native rebuild). Verified: `unzip -p <apk>
assets/update_channel.txt` -> "test", and the repo-root APK refreshed from
`app-debug.apk` (previously the pipeline's root copy predated the flag).

**Lesson:** test builds must always ride `GX_UPDATE_CHANNEL=test`; and the repo-root
APK is written by my wrappers, not the packaging script — after any repackage, refresh
`GeneralsXZH-android-local.apk` from `app/build/outputs/apk/debug/app-debug.apk`.
