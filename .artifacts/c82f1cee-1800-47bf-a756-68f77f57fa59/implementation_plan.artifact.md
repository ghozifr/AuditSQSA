# Fix Inflation Error in MainActivity

The application crashes with an `InflateException` when starting `MainActivity`. The error is specifically at line 17 of `activity_main.xml`, where `androidx.fragment.app.FragmentContainerView` is being inflated.

## Analysis

The crash occurs during the inflation of `activity_main.xml`. Line 17 of `activity_main.xml` is:
`app:layout_constraintBottom_toTopOf="@id/bottom_navigation"`

There are two primary issues identified:
1. **Invalid ID Reference**: `@id/bottom_navigation` is used before the `BottomNavigationView` is defined with `@+id/bottom_navigation`. This can cause `ConstraintLayout` to fail during inflation.
2. **FragmentContainerView & NavHostFragment Setup**: While the setup looks standard, inflation errors in `FragmentContainerView` with `android:name` often occur if there's a problem with the fragment instantiation or the navigation graph it's trying to load.

## Proposed Changes

### [app]

#### [MODIFY] [activity_main.xml](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/res/layout/activity_main.xml)
- Update `@id/bottom_navigation` to `@+id/bottom_navigation` to ensure the ID is defined when referenced.
- Ensure the horizontal constraints for `BottomNavigationView` are explicitly set to avoid potential layout issues in `ConstraintLayout`.

#### [MODIFY] [nav_graph.xml](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/res/navigation/nav_graph.xml)
- Change `android:defaultValue="0L"` to `android:defaultValue="0"` for the `amount` argument in `topupFragment`. Navigation XML parser sometimes struggles with the `L` suffix.

#### [MODIFY] [MainActivity.kt](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/java/com/suruhaja/MainActivity.kt)
- Add a safety check or use `findNavController` in a way that is more robust for `FragmentContainerView`. Actually, the current way is mostly correct, but I'll ensure the initialization order is safe.

## Verification Plan

### Automated Tests
- Build the project to ensure no compile-time errors in XML or Kotlin.
- Run the app and verify it no longer crashes on startup.

### Manual Verification
- Deploy to the device and check if `MainActivity` starts and displays `AuthFragment` (or `HomeFragment` if logged in).
- Verify bottom navigation is visible and functional.
