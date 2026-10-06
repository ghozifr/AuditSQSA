# Walkthrough - Modern Rating UI & Auto-Rating 5

I have overhauled the rating UI after an order is completed and implemented the auto-rating logic for the "Nanti Saja" option.

## Changes Made

### 1. New Custom Rating Dialog
I replaced the standard Android dialog with a custom layout in [dialog_rating.xml](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/res/layout/dialog_rating.xml).
- **Branded Design**: Uses `bg_card` with rounded corners and matches the app's dark theme.
- **Contextual**: Displays the actual driver's name who served the order.
- **Visual Feedback**: Large amber stars for better interactivity.

### 2. Custom Dialog Theme
Added `CustomDialog` to [themes.xml](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/res/values/themes.xml) to ensure the dialog spans a good width (90% of the screen) and has a clean, transparent background.

### 3. "Nanti Saja" Logic Update
Updated [OrderTrackingFragment.kt](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/java/com/suruhaja/ui/tracking/OrderTrackingFragment.kt) with the following logic:
- **Auto-Rating**: Clicking **"Nanti Saja"** now automatically submits a **5-star rating** for the driver.
- **Manual Rating**: Clicking **"Kirim Rating"** sends the specifically selected star value (min 1 star).
- **UX Improvement**: Added a success Toast message after manual submission.

## Verification Results

| Scenario | Expected Result | Status |
| :--- | :--- | :--- |
| **View Dialog** | Shows modern UI with driver name | ✅ Verified |
| **Select 0 stars** | Snaps back to 1 star visually | ✅ Verified |
| **Click "Nanti Saja"** | Order is saved with 5 stars in history | ✅ Verified |
| **Click "Kirim" (3 stars)** | Order is saved with 3 stars in history | ✅ Verified |

> [!TIP]
> The auto-rating feature helps maintain high driver ratings from users who are in a hurry but satisfied with the service.
