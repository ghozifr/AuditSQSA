# Walkthrough - Fix NullPointerException in LocationPickerFragment

Fixed a fatal crash occurring in `LocationPickerFragment` when the device's last known location is null.

## Changes

### Location UI

#### [LocationPickerFragment.kt](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/java/com/suruhaja/ui/location/LocationPickerFragment.kt)

Added a safety null check for the `location` object returned by `fusedLocationClient.lastLocation`. If the location is null, a warning is logged instead of attempting to access its properties, preventing the `NullPointerException`.

```diff
     @SuppressLint("MissingPermission")
     private fun enableMyLocation() {
         googleMap?.isMyLocationEnabled = true

         // Get current location and set as pickup
         fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
-            val latLng = LatLng(location.latitude, location.longitude)
-            pickupLatLng = latLng
-            setPickupMarker(latLng)
-
-            // Get address
-            getAddress(latLng) { address ->
-                binding.tvPickupAddress.text = address
-            }
-
-            // Move camera
-            googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
+            if (location != null) {
+                val latLng = LatLng(location.latitude, location.longitude)
+                pickupLatLng = latLng
+                setPickupMarker(latLng)
+
+                // Get address
+                getAddress(latLng) { address ->
+                    binding.tvPickupAddress.text = address
+                }
+
+                // Move camera
+                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
+            } else {
+                Log.w(TAG, "Last location is null. Camera will not be moved.")
+            }
         }
     }
```

## Verification Results

### Automated Tests
- Ran `./gradlew app:assembleDebug` to ensure the project still builds correctly.
- Result: **Build finished successfully.**

### Manual Verification
- Verified that the code logic now handles null location scenarios gracefully by logging a warning instead of crashing.
