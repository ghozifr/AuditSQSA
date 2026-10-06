# Fix Unresolved Reference 'debug' in Release Build

The build error `Unresolved reference 'debug'` occurs because `DebugAppCheckProviderFactory` is included via `debugImplementation`, making it unavailable during release builds. However, `SuruhajaApp.kt` (in the `main` source set) attempts to import and use it directly.

## Proposed Changes

### [Component Name]

#### [MODIFY] [SuruhajaApp.kt](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/java/com/suruhaja/SuruhajaApp.kt)

- **Remove** the direct import of `com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory`.
- **Update** `onCreate()` to load the debug provider via reflection only when `BuildConfig.DEBUG` is true. This ensures the compiler does not try to resolve the missing class during release builds while maintaining the debug functionality for developers.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:compileReleaseKotlin` to verify the release build now passes.
- Run `./gradlew :app:compileDebugKotlin` to verify the debug build still works as expected.

### Manual Verification
- Verify that the app still initializes correctly in a debug environment (via logs if available).
