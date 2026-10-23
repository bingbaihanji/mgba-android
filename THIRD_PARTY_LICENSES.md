# Third-Party Licenses

## mGBA

- **Source**: https://github.com/mgba-emu/mgba
- **Libretro core**: https://github.com/libretro/mgba
- **License**: MPL-2.0 (Mozilla Public License 2.0)
- **Usage**: Libretro core (`libmgba_libretro.so`) loaded at runtime via dlopen.
  The prebuilt library under `app/src/main/jniLibs/arm64-v8a/` is compiled from
  the Libretro fork.
- **Notes**: No ROM or BIOS files are bundled with this application.

## AndroidX / Jetpack Compose

- **License**: Apache License 2.0
- **Packages**: core-ktx, lifecycle-runtime-ktx, activity-compose, compose-bom,
  compose-ui, compose-material3, etc.

## Kotlin / KotlinX Coroutines

- **License**: Apache License 2.0

## Android NDK / Platform Libraries

- **License**: Apache License 2.0 (Android Open Source Project)
- **Libraries used**: liblog, libdl, libm, libGLESv2, OpenSL ES (via AudioTrack)

---

No ROM, BIOS, or game data files are included in this application. Users must
supply their own legally obtained ROM files.
