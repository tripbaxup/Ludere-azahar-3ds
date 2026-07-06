# Notes: azahar (3DS) branch of Ludere

This is a branch of the Ludere N64 project repointed at the azahar libretro core (a Citra fork) instead of parallel_n64.

## What was changed
- `app/src/main/res/values/config.xml` — core id/name set to `azahar`, orientation set to portrait (top+bottom 3DS screens render stacked into one frame), removed the N64 C-button flags (unused by azahar).
- `app/build.gradle` — ABI splits trimmed to `arm64-v8a` only, matching the core binary you provided. The `prepareCore` task's auto-download loop is also trimmed to `arm64-v8a` so it won't waste a build trying (and failing) to fetch cores for x86/armeabi-v7a from the libretro buildbot, which doesn't host azahar.
- `app/src/main/jniLibs/arm64-v8a/libcore.so` — your provided `azahar_libretro.so`, renamed to `libcore.so` and dropped straight into the ABI folder. Since this folder is now non-empty, `prepareCore` will skip the download step entirely and use this file as-is.

## What you still need to do
1. Drop your 3DS ROM (decrypted `.3ds`/`.cci`/`.cxi`) into `app/src/main/res/raw/rom` (no extension), same as the original project's workflow.
2. Build: `./gradlew assembleRelease`. Only an `arm64-v8a` (and universal) APK will be produced.
3. Test on a real arm64 device — 3DS emulation is heavy; expect this to need a considerably more powerful phone than N64 emulation does.
4. Verify `minSdk 21` is actually sufficient for azahar. It very likely is not — Citra/azahar's Android builds have historically targeted newer OpenGL ES / NDK baselines. If it crashes on launch, try raising `minSdk` in `app/build.gradle` (e.g. to 26+) and re-testing.
5. Touch/stylus input for the bottom screen: LibretroDroid's `GLRetroView` passes touch events on the render surface through to the core as pointer input automatically, so bottom-screen touch should work out of the box. Test this specifically, since it's the primary input method for many 3DS games/menus.
6. The on-screen gamepad currently exposes A/B/X/Y, L, R, Start, Select, and the Circle Pad (as the analog stick) — the standard Old 3DS button set. If you want ZL/ZR or the New 3DS C-Stick on the touch overlay, those need new `ButtonConfig`/`SecondaryDialConfig` entries added to `GamePadConfig.kt`.
7. Cosmetic-only leftover: `N64InputHandler` and the in-game "toggle analog/d-pad" menu option are unchanged. They still function correctly for 3DS (Circle Pad + D-Pad are sent simultaneously by default), but the class/menu naming is a leftover from the N64 template — harmless, rename if you want it cleaner.
