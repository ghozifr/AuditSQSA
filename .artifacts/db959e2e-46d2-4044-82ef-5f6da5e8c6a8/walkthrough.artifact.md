# Walkthrough - Fixed Unresolved Reference 'BuildConfig'

The issue where `BuildConfig` was not found in `MainActivity.kt` has been resolved by enabling `buildConfig` generation in the app's build configuration.

## Changes Made

### Build Configuration

#### [app/build.gradle.kts](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/build.gradle.kts)
- Added `buildConfig = true` to the `buildFeatures` block.

```diff
     buildFeatures {
         viewBinding = true
+        buildConfig = true
     }
```

## Verification Results

### Automated Tests
- Ran `./gradlew :app:compileDebugKotlin`: **Success**
- Gradle Sync: **Success**
