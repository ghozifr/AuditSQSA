# Implementation Plan - Fix MainActivity Inflation Crash

The application crashes at startup due to an `InflateException` when inflating `FragmentContainerView` in `activity_main.xml`. This is likely caused by incompatible or "future" versions of dependencies and SDKs being used in the project.

## User Review Required

> [!IMPORTANT]
> I am downgrading `compileSdk` from `37` to `35` and normalizing dependency versions to stable ones. `37` is not a standard SDK version yet and might be the root cause of the binary XML inflation failure.

## Proposed Changes

### Build Configuration

#### [MODIFY] [libs.versions.toml](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/gradle/libs.versions.toml)
- Update `agp` to `8.5.0` (stable).
- Update `navigation` to `2.8.0` (stable).
- Update `appcompat` to `1.7.0` (better compatibility with `FragmentContainerView`).
- Add `androidx.fragment:fragment-ktx` explicitly to ensure it's available for `FragmentContainerView`.

#### [MODIFY] [build.gradle.kts](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/build.gradle.kts)
- Change `compileSdk` to `35`.
- Change `targetSdk` to `35`.
- Add `androidx.fragment.ktx` dependency.
- Fix `buildTypes` block to use standard AGP DSL (`isMinifyEnabled` instead of `optimization`).

### UI Layout

#### [MODIFY] [activity_main.xml](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/res/layout/activity_main.xml)
- If version normalization doesn't work, I will consider switching `FragmentContainerView` to the legacy `<fragment>` tag as a fallback, but primary goal is to fix it with the modern tag.

## Verification Plan

### Automated Tests
- Run `./gradlew assembleDebug` to ensure it builds.
- Run the app on an emulator/device to verify it doesn't crash on startup.

### Manual Verification
- Check if `MainActivity` launches correctly.
- Verify `BottomNavigationView` correctly navigates between fragments.
