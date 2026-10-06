# Fix NullPointerException in LocationPickerFragment

The application crashes when `fusedLocationClient?.lastLocation` returns a null location, which happens in `LocationPickerFragment.enableMyLocation()`. This is a common scenario when the device's location is not yet available.

## Proposed Changes

### [Component Name] Location UI

#### [MODIFY] [LocationPickerFragment.kt](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/java/com/suruhaja/ui/location/LocationPickerFragment.kt)

Add a null check for the `location` object received from `fusedLocationClient?.lastLocation?.addOnSuccessListener`.

```kotlin
    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {
        googleMap?.isMyLocationEnabled = true

        // Get current location and set as pickup
        fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
            if (location != null) {
                val latLng = LatLng(location.latitude, location.longitude)
                pickupLatLng = latLng
                setPickupMarker(latLng)

                // Get address
                getAddress(latLng) { address ->
                    binding.tvPickupAddress.text = address
                }

                // Move camera
                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
            } else {
                Log.w(TAG, "Last location is null. Camera will not be moved.")
            }
        }
    }
```

## Verification Plan

### Manual Verification
- Deploy the app to a device/emulator.
- Grant location permissions.
- Ensure the app doesn't crash even if the location is initially unavailable.
- Check Logcat for "Last location is null" warning if the issue occurs.
