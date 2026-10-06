# Fix "Android BaseExtension not found" and AGP 9.0 Compatibility

The build error `Android BaseExtension not found` occurs because the project is using Android Gradle Plugin (AGP) 9.3.1, which removes the legacy `BaseExtension` API by default. Additionally, AGP 9.0 introduces built-in Kotlin support which is incompatible with the standard `kotlin-kapt` plugin.

## Proposed Changes

We will upgrade Hilt and migrate to AGP 9.0 compatible plugins.

### [Dependency Management]

#### [MODIFY] [libs.versions.toml](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/gradle/libs.versions.toml)
- Update `hilt` version to `2.60.1`.
- Add `legacy-kapt` plugin definition (com.android.legacy-kapt:9.3.1).
- Update `kotlin-kapt` version to `2.2.10` (though we will use legacy-kapt).

### [Build Configuration]

#### [MODIFY] [build.gradle.kts (root)](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/build.gradle.kts)
- Replace `libs.plugins.kotlin.kapt` with `libs.plugins.legacy.kapt`.

#### [MODIFY] [build.gradle.kts (app)](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/build.gradle.kts)
- Replace `libs.plugins.kotlin.kapt` with `libs.plugins.legacy.kapt`.
- Migrate `kotlinOptions` to the new `kotlin` extension or remove it if redundant.

#### [MODIFY] [gradle.properties](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/gradle.properties)
- Ensure `android.builtInKotlin` and `android.newDsl` are not explicitly disabled (or set to true).

## Verification Plan

### Automated Tests
- Run `./gradlew assembleDebug` to verify the build succeeds.

