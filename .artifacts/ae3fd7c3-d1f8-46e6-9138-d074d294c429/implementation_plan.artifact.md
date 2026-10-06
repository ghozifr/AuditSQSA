# Home Screen Redesign Implementation Plan

Redesign `fragment_home.xml` to match the provided modern, clean layout with rounded cards, a prominent "Suruh Ride" hero section, and a horizontal service list.

## User Review Required

> [!IMPORTANT]
> - I will use `Poppins` font where available, falling back to standard sans-serif.
> - The redesign will introduce new drawables for the search bar and cards to ensure high fidelity to the image.
> - The "Penawaran terbaik" section will use a `ViewPager2` for horizontal sliding.

## Proposed Changes

### Resources

#### [NEW] `bg_search_home.xml`
A white pill-shaped background with a thin gray border for the search bar.

#### [NEW] `bg_home_card_white.xml`
A clean white background with rounded corners and a subtle border for the "Food" and "Saldo" cards.

#### [NEW] `bg_service_item.xml`
A square rounded background for small service icons like "Clean".

### Layout

#### [MODIFY] [fragment_home.xml](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/res/layout/fragment_home.xml)
Full redesign of the layout:
1.  **Header**: Profile icon on left, greeting in the middle, and notification bell on the right.
2.  **Search Bar**: Rounded pill container with search icon.
3.  **Hero Card**: `ConstraintLayout` with teal gradient background, "Suruh Ride" branding, and scooter icon.
4.  **Mid Cards**: Two columns for "Suruh Food" and "Saldo kamu".
5.  **Service Grid**: Horizontal scrollable list starting with "Clean".
6.  **Promo Slider**: "Penawaran terbaik" title followed by a `ViewPager2`.

## Verification Plan

### Automated Tests
- Run `./gradlew assembleDebug` to verify no resource linking or compilation errors.

### Manual Verification
- Deploy to device/emulator.
- Verify UI alignment and styling against the provided image.
- Check that "Halo, Andre" and balance text are correctly populated from ViewModel.
