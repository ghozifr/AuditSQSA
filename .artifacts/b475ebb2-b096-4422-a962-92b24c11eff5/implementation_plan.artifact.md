# Implementation Plan - Modern Rating Dialog & Auto-Rating 5

The user wants to improve the rating UI to be "tidier" and requested that if the "Nanti Saja" (Later) option is chosen, the rating should default to 5 stars.

## Proposed Changes

### [Component] UI Resources

#### [NEW] [dialog_rating.xml](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/res/layout/dialog_rating.xml)
- Create a modern, branded layout with:
  - Rounded corners (`bg_card`).
  - Driver name display.
  - Large, styled `RatingBar`.
  - Primary "Kirim" button and a subtle "Nanti Saja" button.

#### [MODIFY] [themes.xml](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/res/values/themes.xml)
- Add a style for the dialog to ensure it has a transparent background and proper padding.

### [Component] Order Tracking

#### [MODIFY] [OrderTrackingFragment.kt](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/java/com/suruhaja/ui/tracking/OrderTrackingFragment.kt)
- Update `showRatingDialog()` to:
  - Inflate the new `dialog_rating.xml`.
  - Populate the driver's name.
  - **Logic Update**: When the "Nanti Saja" button is clicked, call `viewModel.submitRating(5)`.
  - **Logic Update**: When the "Kirim" button is clicked, call `viewModel.submitRating(selectedRating)`.
  - Maintain the 1-star minimum constraint.

## Verification Plan

### Manual Verification
- Trigger the rating dialog.
- Verify the new UI is clean and matches the app theme.
- Click "Nanti Saja" and verify in the activity history that the order is rated ★ 5.
- Select 1 star and click "Kirim", verify it is rated ★ 1.
- Try to select 0 stars, verify it snaps to 1.

> [!IMPORTANT]
> Submitting 5 stars when clicking "Later" is a specific product request from the user to ensure drivers get good ratings even from passive users.
