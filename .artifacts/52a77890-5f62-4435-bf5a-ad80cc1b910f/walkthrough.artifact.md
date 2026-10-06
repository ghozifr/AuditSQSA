# Walkthrough - Fixing 'Unresolved reference: debug'

I have resolved the build error where `DebugAppCheckProviderFactory` could not be found during release builds.

## Changes

### Application Initialization

#### [SuruhajaApp.kt](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/java/com/suruhaja/SuruhajaApp.kt)

- I removed the direct import of `com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory`.
- I updated the `onCreate` method to use reflection to load the `DebugAppCheckProviderFactory` only when `BuildConfig.DEBUG` is true.
- If the class cannot be loaded (which is the case in release builds where it's not included as a dependency), it falls back to `PlayIntegrityAppCheckProviderFactory`.

```kotlin
        val factory: AppCheckProviderFactory = if (BuildConfig.DEBUG) {
            try {
                val clazz = Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
                clazz.getMethod("getInstance").invoke(null) as AppCheckProviderFactory
            } catch (e: Exception) {
                PlayIntegrityAppCheckProviderFactory.getInstance()
            }
        } else {
            PlayIntegrityAppCheckProviderFactory.getInstance()
        }
```

## Verification Results

### Automated Tests
- Ran `gradle_build(":app:compileReleaseKotlin")`: **SUCCESS**
- Ran `gradle_build(":app:compileDebugKotlin")`: **SUCCESS**

> [!NOTE]
> This approach keeps the code in the `main` source set while ensuring the release build does not depend on the debug-only library.
