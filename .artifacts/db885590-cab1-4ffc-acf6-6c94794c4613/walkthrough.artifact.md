# Walkthrough - Fixed MainActivity Inflation Crash

I have fixed the `FATAL EXCEPTION: main` that occurred during `MainActivity` launch. The crash was caused by multiple issues in the project configuration and navigation graph.

## Changes Made

### 1. Version Normalization
- Downgraded `compileSdk` and `targetSdk` from `37` to `35` (Android 15).
- Reverted experimental `navigation` version `2.9.0` to stable `2.8.0`.
- Added `androidx.fragment:fragment-ktx` dependency explicitly to ensure compatibility.
- Cleaned up `build.gradle.kts` to use standard AGP DSL for build types.

### 2. Navigation Graph Fixes
- **Long/Integer Type Mismatch**: Removed `android:defaultValue="0"` from `amount` argument in `nav_graph.xml`. Stricter XML parsers in newer Navigation versions treat `0` as an integer, causing a mismatch with `app:argType="long"`.
- **Invalid Double Type**: Changed `app:argType="double"` to `app:argType="float"`. `double` is not a built-in type in Android Navigation XML.
- Updated `MerchantListFragment`, `MerchantMenuFragment`, and `FoodLocationFragment` to use `float` for coordinates (`merchantLat`, `merchantLng`) to match the navigation graph.

## Verification Results

### Automated Tests
- Gradle build: `SUCCESSFUL`
- Deployment: `SUCCESSFUL`

### Manual Verification
- Verified that the app no longer crashes on startup.
- Confirmed the Home screen is displayed correctly (see screenshot below).

![Home Screen Success](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/.artifacts/db885590-cab1-4ffc-acf6-6c94794c4613/screenshot_home.png)

> [!NOTE]
> I have copied the screenshot to the artifacts directory.
