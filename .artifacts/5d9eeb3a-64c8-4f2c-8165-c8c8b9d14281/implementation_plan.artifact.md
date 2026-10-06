# Implementation Plan - Fix NPE in LocationPickerFragment

The application crashes with a `NullPointerException` when `fusedLocationClient.lastLocation` returns `null`. This typically happens when the device location is not available or hasn't been cached yet.

## User Review Required

> [!NOTE]
> This fix adds a null check to prevent the crash. If the location is null, the map will not automatically center on the user's current location until they interact with it or a new location is obtained.

## Proposed Changes

### UI Components

#### [MODIFY] [LocationPickerFragment.kt](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/java/com/suruhaja/ui/location/LocationPickerFragment.kt)

- Add a null check for the `location` object in the `addOnSuccessListener` callback within `enableMyLocation()`.

## Verification Plan

### Automated Tests
- Build the project to verify compilation: `gradlew :app:assembleDebug`

### Manual Verification
- Deploy and run the app.
- Open the location picker.
- Verify that the app no longer crashes even if the device's last known location is unavailable.
