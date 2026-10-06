# Walkthrough - Fix Keyboard and Navbar Overlap in Send Form

I have implemented dynamic Window Inset handling in the `SendFormFragment` to ensure that the form and the "Lanjut" button are never obscured by the keyboard or the system navigation bar.

## Changes Made

### Logic Improvements

#### [SendFormFragment.kt](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/java/com/suruhaja/ui/send/SendFormFragment.kt)
- **Top Insets**: Added logic to dynamically adjust the top margin of the header (Back button and Title) based on the status bar height. This prevents overlap with the device's clock and icons.
- **Bottom Insets**: Implemented a listener for `systemBars` and `ime` (keyboard) insets.
    - The "Lanjut" button now automatically moves up when the keyboard is visible, keeping it accessible.
    - When the keyboard is hidden, the button rests safely above the system navigation bar (3-button or gesture).
- **Seamless Scrolling**: Since the "Lanjut" button pushes the `NestedScrollView` up, users can now scroll through the entire form even while typing.

## Verification Results

- **Status Bar Safety**: The title "Detail Kiriman" now has appropriate clearance from the top of the screen.
- **Keyboard Resilience**: Tapping on any input field (including those at the bottom) pushes the action button up, ensuring it's never covered.
- **System Navigation**: The button is fully visible on devices with traditional back/home/recent buttons, with no clipping.
