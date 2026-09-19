# Embedded on-device runtime (Termux-native opencode)

This document explains how OpenCode Mobile embeds a **fully on-device opencode
server** using the community [opencode-termux](https://github.com/Hope2333/opencode-termux)
"native mainline" build — a single **bionic ELF** (zero glibc dependencies,
Android API ≥ 28) that runs natively on aarch64 devices.

## Why not "just run Termux in the app"?

Two hard platform constraints rule out the classic Termux-style approach:

1. **`exec()` from app data is forbidden for targetSdk ≥ 29.** Android 10's
   SELinux policy (`untrusted_app` domains for API 29+) removes `execute`
   on `app_data_file`. Termux itself still ships with `targetSdk 28`
   precisely because of this — not an option for a Play-distributed app
   (current requirement: targetSdk 35+).
2. **Termux bootstrap binaries hardcode the prefix**
   `/data/data/com.termux/files/usr`. Forking Termux under our package name
   (`cc.agentlabs.opencode`) would require rebuilding the entire package set.

The opencode-termux native line sidesteps both problems: its single ELF
dynamically links only against bionic system libraries
(`libc.so`, `libm.so`, `libdl.so`, interpreter `/system/bin/linker64`), so it
can run from any executable location — including the app's
`nativeLibraryDir`.

## How the binary gets there

```
scripts/fetch-opencode-native.sh          (dev-time, run once before building)
  └─ downloads opencode_1.18.30_aarch64.deb from the opencode-termux releases
     └─ extracts usr/bin/opencode              → android/app/src/main/jniLibs/arm64-v8a/libopencode.so
        extracts usr/lib/opencode/libopencode-crhandler.so
                                               → android/app/src/main/jniLibs/arm64-v8a/libopencode-crhandler.so
```

Files under `jniLibs` are installed by the package manager into the app's
read-only, **executable** `nativeLibraryDir` — the only location (besides
`/system`) where apps targeting API 29+ may `execve()` binaries.

This requires **legacy native-lib packaging**
(`android/gradle.properties` → `expo.useLegacyPackaging=true`): without it,
`.so` files stay compressed inside the APK and are mmap'd directly, which
works for `System.loadLibrary` but not for `execve`. Cost: the 180MB ELF
compresses to ~61MB inside the APK (xz-equivalent) — arm64-v8a only.

The seccomp shim (`libopencode-crhandler.so`) is a `DT_NEEDED` dependency of
the hardened build, resolved via `DT_RUNPATH $ORIGIN/../lib/opencode`. We
resolve it instead through `LD_LIBRARY_PATH=$nativeLibraryDir`, which bionic
searches before `RUNPATH`.

## Runtime architecture

```
JS (React Native)                          Kotlin native layer
┌─────────────────────────┐                ┌──────────────────────────────────┐
│ src/lib/native-runtime  │  Promise RPC   │ OpencodeRuntimeModule            │
│ src/stores/runtime (zustand) │ ────────► │   getState/startServer/stop/…    │
│ app/runtime.tsx (UI)    │ ◄────────────  │   event emitters (log/state)     │
└───────────┬─────────────┘  DeviceEvent   │        │ broadcasts (app-local)   │
            │ HTTP+SSE (existing SDK)      │        ▼                          │
            ▼                              │ OpencodeRuntimeService (FGS)     │
   http://127.0.0.1:4096  ◄────────────────┤  ProcessBuilder                   │
   (opencode server)      │  execve + env  │   libopencode.so serve           │
                           │                │   --hostname 127.0.0.1 --port N │
                           │                │   HOME=$filesDir/opencode-home   │
                           │                │   LD_LIBRARY_PATH=nativeLibraryDir│
                           └──────────────────────────────────────────────────┘
```

Key points:

- **The app's existing client stack is reused unchanged.** The JS client
  already speaks plain HTTP+SSE to any `baseUrl`; the on-device runtime is
  simply a connection to `http://127.0.0.1:4096` (loopback only — no LAN
  exposure; `usesCleartextTraffic` was already enabled for LAN servers).
- **`HOME` is `$filesDir/opencode-home`** (writable, non-executable): Bun
  cache (`$HOME/.bun`), opencode config/auth/state
  (`$HOME/.local/share/opencode`, `$HOME/.config/opencode`) all live there.
- **Foreground service** (`dataSync` type) so background agents survive the
  UI going away; notification channel importance is LOW.
- **Cold-start note**: first launch populates Bun's compile cache under
  `$HOME/.bun` and can be slow (the opencode-termux project documents this);
  `version()` doubles as a warmup probe. Server startup is only reported as
  `RUNNING` after the process is alive; the runtime screen health-checks via
  the SDK before flipping to "connected".

## UI flow

- Connections tab: compact status card (hidden when the binary is not
  bundled — e.g. iOS, x86 emulators, or builds without the fetch step).
- `app/runtime.tsx`: state/version/port, start/stop, log tail (ring buffer,
  400 lines), and **Connect** — creates/activates the `127.0.0.1:4096`
  connection and reuses `testConnection` before navigating to sessions.

## Provider auth

The embedded server reads provider credentials from
`$HOME/.local/share/opencode/auth.json` — same file format the desktop
client maintains. v1 expects users to configure providers by placing keys
there (see roadmap); the runtime screen surfaces the exact home path.

## Known limitations (v1)

- **arm64-v8a only**; `getState().available` reports `false` on other ABIs
  and the UI hides the runtime card.
- **No bundled toolchain**: `git` and other shell tools the agent may shell
  out to are absent (`PATH` falls back to `/system/bin`). Git-dependent
  flows degrade; the in-app file browser still works over the server API.
- **Workspace lives under app-private storage**
  (`$HOME/workspace`); SAF/shared-storage integration is future work.
- **On-device verification required**: the opencode-termux project itself
  notes "CI green ≠ runnable" — the runtime screen's log tail is the primary
  debugging surface.

## Updating the runtime

```bash
scripts/fetch-opencode-native.sh            # pinned version (v1.18.30)
scripts/fetch-opencode-native.sh 1.18.31    # or any release tag
```

The script records provenance in
`android/app/src/main/jniLibs/opencode-native.txt`. Bump the pin in the
script when moving to a new release.
