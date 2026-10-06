# Walkthrough - Fixed Bottom Navigation Visibility

I have fixed the issue where the bottom navigation icons and text were disappearing in both Light and Dark modes.

## Changes

### 1. Updated Selection Color
In [bottom_nav_color.xml](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/res/color/bottom_nav_color.xml), I changed the color for the selected (checked) state from `@color/text_on_brand` to `@color/brand`.

- **In Light Mode**: The selected icon/text is now blue, which stands out against the off-white navbar background.
- **In Dark Mode**: The selected icon/text is now lime, which stands out against the dark grey navbar background.

## Verification
- Verified that `@color/brand` provides high contrast against the navbar background in both theme variants.
- Unselected items continue to use `@color/text_white`, which maps to dark text in Light mode and light text in Dark mode.
