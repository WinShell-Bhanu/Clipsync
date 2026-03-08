# OTP Bubble Timing Fix

## Issue
The OTP notification bubble was closing too quickly and not staying visible for the intended 5 seconds.

## Root Cause
The window's auto-close timer was set to 5.0 seconds, which was competing with the fade animation that also started at 5.0 seconds. This caused the window to potentially close before the fade animation could complete.

## Fix Applied
Updated the timing in `OTPNotificationBubble.swift`:

### New Timing Sequence:
- **0.0s** - Bubble appears with spring animation
- **4.5s** - Shimmer effect stops
- **5.0s** - Fade out animation begins (0.3s duration)
- **5.3s** - Fade animation completes (bubble visually disappears)
- **5.5s** - Window cleanup and removal from memory

### Code Changes:
1. Window auto-close timer increased from `5.0s` to `5.5s`
2. This allows the fade animation (5.0s - 5.3s) to complete smoothly
3. Window cleanup happens 0.2s after visual fade completes

## Result
The OTP bubble now:
- Stays fully visible for **5 full seconds**
- Fades out smoothly over 0.3 seconds
- Cleans up properly without memory leaks
- No more premature closing

## Files Modified
- `/mac/ClipSync/OTPNotificationBubble.swift`
  - Line 116: Timer interval changed from 5.0 to 5.5 seconds

## Testing
Rebuild the Mac app and trigger an OTP notification. The bubble should now stay visible for the full 5 seconds before fading away gracefully.
