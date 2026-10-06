# Fix Bottom Navigation Color Contrast

The bottom navigation icons and text are disappearing because they are using `@color/text_on_brand` for the selected state, which has low contrast against the navigation bar's background (`@color/surface_low`).

## User Review Required

> [!IMPORTANT]
> I will change the selected state color from `@color/text_on_brand` to `@color/brand` to ensure visibility against the navigation bar background in both Light and Dark modes.

## Proposed Changes

### [Component] Resources - Colors

#### [MODIFY] [bottom_nav_color.xml](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/res/color/bottom_nav_color.xml)
- Change the color for `state_checked="true"` from `@color/text_on_brand` to `@color/brand`.
- `@color/brand` is blue in Light mode and lime in Dark mode, providing clear contrast against the `@color/surface_low` background.

## Verification Plan

### Manual Verification
- Deploy the app and verify the bottom navigation in both Light and Dark modes.
- Ensure selected items are clearly visible (Blue in Light, Lime in Dark).
- Ensure unselected items are readable (Dark in Light, Light in Dark).
