# Implementation Plan - Fix Keyboard and Navbar Overlap in Send Form

This plan addresses two UI issues in `fragment_send_form.xml`:
1.  **Keyboard covering input**: Users cannot see or scroll through the form while the keyboard is visible.
2.  **Navigation bar overlap**: The "Lanjut" button at the bottom is obscured by the system navigation bar (3-button or gesture).

## User Review Required

> [!IMPORTANT]
> I will be applying dynamic padding/margins using **Window Insets**. This is the standard Material 3 approach for edge-to-edge apps (which this project became in the previous step). This ensures the "Lanjut" button always floats above the system navigation bar and the keyboard.

## Proposed Changes

### [Component] UI - fragment_send_form.xml

#### [MODIFY] [fragment_send_form.xml](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/res/layout/fragment_send_form.xml)
- Change `androidx.constraintlayout.widget.ConstraintLayout` to have `android:fitsSystemWindows="false"` (explicitly, as we will handle insets manually for precision).
- Ensure the `NestedScrollView` is properly constrained.

### [Component] Logic - SendFormFragment.kt

#### [MODIFY] [SendFormFragment.kt](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/java/com/suruhaja/ui/send/SendFormFragment.kt)
- Import `androidx.core.view.ViewCompat`, `androidx.core.view.WindowInsetsCompat`, and `androidx.core.view.updateLayoutParams`.
- In `onViewCreated`, apply a `WindowInsetsListener` to the root view or specific views:
    - **Top Insets**: Apply status bar insets as margin to `btn_back` and `tv_title` so they don't overlap with the clock/status icons.
    - **Bottom Insets**: Apply system navigation bar *and* IME (keyboard) insets to the `btn_next` layout. This will push the button (and the scroll view above it) up when the keyboard appears.

## Verification Plan

### Manual Verification
1.  **Bottom Button Visibility**: On a device with 3-button navigation, verify the "Lanjut" button is fully visible above the buttons.
2.  **Keyboard Interaction**: Tap on "No Telepon Pengirim" (the bottom-most field).
    - Verify the "Lanjut" button moves up above the keyboard.
    - Verify the user can scroll the form to see the active field while typing.
3.  **Status Bar**: Verify the "Detail Kiriman" title doesn't crash into the status bar icons.
