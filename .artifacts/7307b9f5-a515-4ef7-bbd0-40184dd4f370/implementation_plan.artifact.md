# Implementation Plan - Fix Unresolved Reference 'btnSendOtp' in AuthFragment

The project fails to build because `AuthFragment.kt` references View Binding IDs that do not exist in `fragment_auth.xml`. The fragment code implements an OTP-based authentication flow, while the layout seems to have been updated to a different design (Password-based login) without the corresponding IDs.

## User Review Required

> [!IMPORTANT]
> The current `fragment_auth.xml` contains a password-based login UI (Email/Phone + Password). However, `AuthFragment.kt` and `AuthViewModel.kt` are strictly OTP-based. I will update the layout to support the OTP flow while keeping the new design's aesthetic.

## Proposed Changes

### UI Layout

#### [MODIFY] [fragment_auth.xml](file:///C:/Users/andri/AndroidStudioProjects/Suruhaja/app/src/main/res/layout/fragment_auth.xml)
- Reorganize the layout to include `layout_phone_input` and `layout_otp_input`.
- Add missing IDs: `btn_send_otp`, `btn_verify_otp`, `btn_resend_otp`, `et_otp`, `progress_bar`, `tv_phone_display`, `tv_error`.
- Remove or hide the password field and tab switcher as they are not used by the current OTP implementation.
- Maintain the Hero header and dark theme styling.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:compileDebugKotlin` to verify the "Unresolved reference" error is resolved.

### Manual Verification
- Deploy the app to a device/emulator and verify the Auth screen displays correctly.
- Check if the transition from Phone Input to OTP Input works as expected (UI-wise).
