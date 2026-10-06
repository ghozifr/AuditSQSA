# Implementation Plan - Fix Navigation Type Mismatch

This plan addresses the `java.lang.IllegalArgumentException` crash occurring during navigation. The crash is caused by a type mismatch for the `merchantLat` (and likely `merchantLng`) argument in the navigation graph.

## Problem Description
The application crashes with:
`java.lang.IllegalArgumentException: Wrong argument type for 'merchantLat' in argument bundle. float expected.`

- **Navigation Graph (`nav_graph.xml`)**: Defines `merchantLat` and `merchantLng` as `float`.
- **Source Code**: Passes these values using `Bundle.putDouble()` and retrieves them using `Bundle.getDouble()`.
- **Navigation Version**: `2.8.0` is being used, which supports the `double` type in XML.

## Proposed Changes

### [Component] Navigation Graph

#### [MODIFY] [nav_graph.xml](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/res/navigation/nav_graph.xml)
- Change `app:argType="float"` to `app:argType="double"` for `merchantLat` and `merchantLng` in both `merchantMenuFragment` and `foodLocationFragment` destinations.

## Verification Plan

### Automated Tests
- Run `./gradlew assembleDebug` to ensure the project still builds and Safe Args (if used) generates the correct types.

### Manual Verification
- Deploy the app to a device/emulator.
- Navigate to the "SuruhFood" section.
- Select a merchant to open the menu (triggering navigation to `merchantMenuFragment`).
- Proceed to checkout (triggering navigation to `foodLocationFragment`).
- Verify that no crash occurs and coordinates are correctly passed.
