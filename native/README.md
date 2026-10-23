# Native Frontend (P0–P6)

JNI bridge, video framebuffer, audio ring buffer, input state, SRAM/RTC
persistence, savestates, and core options for the mGBA Android app.

## Layout

```text
native/
  include/mgba_session.h   Public C API (core loader + session state machine)
  include/framebuffer.h    Triple-buffered frame pool (SPSC, lock-free)
  include/audio_ring.h     PCM ring buffer (SPSC, lock-free)
  include/input_state.h    Multi-source button state (bitmask per button)
  src/retro_core_api.c     Dynamic loader wrapper (dlopen/dlsym on POSIX,
                           LoadLibrary/GetProcAddress on Windows)
  src/emulator_session.c   Lifecycle state machine, callbacks, environment
  src/framebuffer.c        Frame pool implementation
  src/audio_ring.c         Audio ring buffer implementation
  src/input_state.c        Input state aggregator
  src/jni_bridge.c         JNI exports used by NativeEmulator.kt
  tests/                   Host unit tests and fake Libretro cores
```

The Kotlin side follows the platform-neutral / platform-specific split:

- `emulator/NativeEmulator.kt` — raw JNI declarations. **The package name is
  fixed**: the C symbols are
  `Java_com_mgba_bingbaihanji_emulator_NativeEmulator_*`.
- `core/emulation/EmulatorCore.kt` — platform-neutral emulation contract.
- `platform/android/emulation/JniEmulatorCore.kt` — JNI implementation of that
  contract with `Result`/error mapping.

## Lifetime rules

- Only one session may be initialized at a time. Libretro callbacks carry no
  userdata pointer, so the native layer keeps a single active-session pointer.
  A second `initialize()` is rejected with `MGBA_ERR_INVALID_STATE`.
- Every native call from Kotlin runs on the session's dedicated
  `mgba-emulation` thread, including `close()`. Public methods block until the
  call completes; the future run loop will submit frames to the same executor.
- `retro_get_system_av_info()` is queried only after a successful
  `retro_load_game()`. mGBA resolves its game instance in `retro_load_game()`,
  so calling it earlier would dereference a null core.
- `unload_game()` performs `retro_unload_game()` followed by `retro_deinit()`.
  mGBA frees its frontend buffers only in `retro_deinit()`, so a full
  init/deinit cycle per game keeps unload → load leak-free. The next
  `load_game()` lazily calls `retro_init()` again.
- `mgba_session_shutdown()` unloads a loaded game, calls `retro_deinit()`
  exactly once, and closes the core library only when the session opened it.
- The core's `RETRO_ENVIRONMENT_GET_LOG_INTERFACE` is served by a log callback
  that writes to logcat on Android and stderr in host tests.

## Video pipeline (P1)

The video callback copies each frame into a lock-free triple buffer
(`FramebufferPool`). The GL render thread acquires the latest frame via
`nativeAcquireFrame`, uploads it to a GLES2 texture, and draws a full-screen
quad with aspect-ratio-preserving scaling.

Thread ownership:

- **Emulation thread** writes frames (`video_refresh_cb` → `framebuffer_pool_write`).
- **GL render thread** reads frames (`acquireFrame` → `framebuffer_pool_acquire`).
- No mutexes on the hot path; slot states use C11 atomics with acquire/release
  ordering.

Frame data is compacted at write time (pitch = width × bpp) so the GL upload
needs no row-length handling. RGB565 is uploaded directly
(`GL_UNSIGNED_SHORT_5_6_5`); XRGB8888 is uploaded as `GL_RGBA` with a shader
swizzle.

Duplicate frames (`data == NULL` in the video callback) are handled naturally:
the pool keeps the last READING slot, so the renderer re-displays it.

Kotlin side: `platform/android/video/GameRenderer.kt` (GLES2) +
`platform/android/video/GameSurfaceView.kt` (GLSurfaceView). Both depend only
on the platform-neutral `core/emulation/FrameSource.kt` contract, so a desktop
renderer can consume the same frames. Lifecycle orchestration lives in
`core/session/GameSession.kt`.

## Audio pipeline (P2)

The audio callback writes interleaved stereo int16 frames into a lock-free
SPSC ring buffer (`AudioRing`, 16384 frames ≈ 250 ms at 65536 Hz). The audio
thread reads from the ring and blocking-writes to `AudioTrack`.

Data flow:

```text
mGBA audio_sample_batch_cb
        │ stereo int16 frames
        ▼
AudioRing (native SPSC ring buffer)
        │
        ▼
AudioThread → AudioTrack (blocking write)
```

- `audio_sample_batch` always returns the frame count the core requested;
  the ring's own overflow counter tracks dropped samples.
- Frame scheduling uses the core-reported FPS from `retro_get_system_av_info()`
  with absolute-timing (`nextFrame += frameNanos`) to avoid sleep drift.
- AV info (`fps`, `sample_rate`) is exposed via `nativeGetAvInfo`.
- Pause flushes AudioTrack and clears backlog; resume restarts playback.
- Stats: `audioQueued`, `audioUnderruns`, `audioOverflows` accessible from Kotlin.

Kotlin side: `core/audio/AudioPump.kt` (platform-neutral consumer thread) +
`platform/android/audio/AudioTrackSink.kt` (AudioTrack device). A desktop
build only needs another `AudioSink` implementation.

## Input pipeline (P3)

Multiple input sources (virtual gamepad, physical keyboard, gamepad) share a
thread-safe input state. Each button tracks a **bitmask of sources** that are
currently holding it, so one source releasing never clears a button another
source is still holding.

- `input_state_press/release(button, source)` — set/clear one source's bit.
- `input_state_release_source(source)` — release all buttons from one device
  (called on pause, focus loss, device disconnect).
- `input_state_reset()` — release everything.
- The Libretro `input_state_cb` reads this state; it never touches Compose
  or Android views.

Kotlin side:
- `ui/input/VirtualGamepad.kt` — Compose overlay with multi-touch
  hit-testing. Each pointer ID is tracked independently through
  press/move/release; it writes through the platform-neutral `InputDevice`.
- `platform/android/input/KeyMapping.kt` — maps hardware keys (DPAD, gamepad
  buttons) to GBA buttons via `GbaButton` constants from `libretro.h`.
  A remappable keyboard only needs another `KeyMapping` implementation.
- `GameSession.keyboardInput` — a `CoreInputDevice` bound to source 1.

## ROM & SRAM persistence (P4)

**ROM cache** (`AndroidRomRepository`): SAF-selected ROMs are copied into
`filesDir/rom-cache/<sha256>.gba`. The emulator loads from this stable local
path. The SHA-256 of the content is the identity key for save files.

**SRAM / RTC** (`BatterySaveRepository` + `FileSaveStorage`):
- Files: `saves/<sha256>.srm`, `saves/<sha256>.rtc`.
- Atomic writes: write to `.tmp`, `fsync`, then `rename`. A crash never
  destroys the previous valid save.
- Auto-save on `onPause` and `onDestroy`; load after `loadGame`.
- Native side: `save_sram`/`load_sram`/`save_rtc`/`load_rtc` access the
  core's `RETRO_MEMORY_SAVE_RAM` / `RETRO_MEMORY_RTC` from the emulation
  thread via direct ByteBuffers.

Kotlin side: `platform/android/storage/AndroidRomRepository.kt`,
`core/storage/BatterySaveRepository.kt`, and
`platform/android/storage/FileSaveStorage.kt`. The byte-level `SaveStorage`
interface lets a desktop build use plain files or another backend.

## Savestates & core options (P5)

**Savestates** (`SavestateRepository` + `SavestateFormat`):
- Files: `states/<sha256>.slot<N>.state` (up to 10 slots).
- Binary format: magic `MGST`, format version, timestamp, core name/version,
  ROM SHA-256, data size, then serialized core state.
- Loading validates header (magic, version, ROM hash, data length) before
  touching the core. Mismatches return specific errors (`RomMismatch`,
  `Corrupted`, etc.) instead of crashing.
- Atomic writes: tmp → fsync → rename.

**Core options** (v0 `SET_VARIABLES` path):
- Core registers option definitions via `RETRO_ENVIRONMENT_SET_VARIABLES`.
- Frontend stores key/possible-values/current-index and serves
  `GET_VARIABLE` / `GET_VARIABLE_UPDATE`.
- `setOption(index, valueIndex)` updates the value and flags the core.

Native side: `mgba_session_serialize/unserialize`, `mgba_session_option_*`.
Kotlin side: `core/storage/SavestateFormat.kt`,
`core/storage/SavestateRepository.kt`, `core/emulation/CoreOptions.kt`.

## UI & release (P6)

- **Game list** (`ui/GameListScreen.kt`): shows cached ROMs with name, size,
  and content hash; "Open ROM..." button launches the SAF picker.
- **Settings** (`ui/SettingsScreen.kt`): displays core options registered by
  the Libretro core; radio-style value selection.
- **Error handling**: all load/init/ROM errors surface as Toast messages with
  specific failure reasons; corrupted savestates report the cause.
- **Release build**: `./gradlew assembleRelease` produces an APK with both
  native libraries (16 KiB page-aligned), no ROM/BIOS bundled.
- **License manifest**: `THIRD_PARTY_LICENSES.md` lists mGBA (MPL-2.0),
  AndroidX (Apache-2.0), and NDK libraries.

## Build

### Android

From the repository root:

```powershell
# Core + JNI frontend, arm64-v8a, Release
.\scripts\build-native.ps1

# Frontend only (core already built)
.\scripts\build-native.ps1 -SkipCore
```

Artifacts:

```text
app/src/main/jniLibs/arm64-v8a/libmgba_libretro.so
app/src/main/jniLibs/arm64-v8a/libmgba_frontend.so
```

The core target emits `mgba_libretro.so` (RetroArch naming, `PREFIX ""`);
the build script renames it to `libmgba_libretro.so` for Android packaging.

### Windows x64 (desktop)

`scripts/build-native-windows.ps1` uses MinGW-w64 GCC and produces native PE
DLLs. It requires a JDK with `include\jni.h` and
`include\win32\jni_md.h`; `JAVA_HOME` is used by default.

```powershell
# Core + JNI frontend
.\scripts\build-native-windows.ps1

# Frontend only: the core DLL must already exist
.\scripts\build-native-windows.ps1 -SkipCore

# Low-memory machines: serialize the core build
.\scripts\build-native-windows.ps1 -Jobs 1
```

Artifacts:

```text
build/native/windows-x64/dist/mgba_libretro.dll
build/native/windows-x64/dist/mgba_frontend.dll
build/native/windows-x64/dist/build-info.txt
```

The script verifies PE x86-64, all 41 JNI exports, and (when built) the 20
`retro_*` core exports. `mgba_frontend.dll` links `libgcc` statically, so it
only imports `KERNEL32.dll` and `msvcrt.dll`.

Load it in a desktop JVM with
`-Djava.library.path=build/native/windows-x64/dist` and
`System.loadLibrary("mgba_frontend")`. Pass the absolute
`mgba_libretro.dll` path to `nativeInitialize()`, because the frontend loads
the core through `LoadLibraryA()` at runtime.

## Tests

```powershell
.\scripts\test-native.ps1
```

This builds `native/` with Cygwin GCC and runs two CTest suites:

- `test_session` — state transitions, error handling, environment callback.
- `test_core_loader` — dlopen/dlsym success, missing library, missing symbol,
  and an end-to-end session against a fake core shared library.

The Android instrumentation test
(`app/src/androidTest/.../NativeEmulatorTest.kt`) loads the real core, reads
its version, checks double-initialize rejection, and verifies that a missing
ROM fails without corrupting the session. It cannot load/run a real game yet
because no legally redistributable test ROM is checked in. It requires a
connected arm64 device or emulator:

```powershell
# The repo's gradle.properties points gradle.user.home at a shared Maven
# cache; use an isolated home to avoid cache-lock errors.
.\gradlew.bat -g .\build\gradle-home :app:connectedDebugAndroidTest
```

## Known constraints

- The Libretro fork does not compile with `M_CORE_GB=OFF`; the build enables
  both GBA and GB/GBC cores.
- No ROM or BIOS is bundled. `mgba_session_load_game()` takes an absolute path
  to a ROM the user is allowed to use.
- The current frontend ignores `GET_VFS_INTERFACE`; mGBA falls back to
  `VFileOpenFD` for ROM and BIOS paths.
- Both Android shared libraries are linked with
  `-Wl,-z,max-page-size=16384` so they remain loadable on Android 15+
  16 KiB-page devices.
- The Windows core build is memory-hungry. LTO is off by default and the
  script defaults to `-Jobs 2`; use `-Jobs 1 -EnableLto:$false` if `cc1.exe`
  reports out-of-memory.
