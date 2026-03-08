# Dialog Detection & Scanning Logging Implementation

## 🎯 Overview
Comprehensive logging system to track scanning behavior and detect dialogs/popups for optimizing clipboard detection in Clipsync.

## 📊 What Was Implemented

### Phase 1: Baseline Scanning Logs
Every accessibility event is now logged with:
- **Event Type** (VIEW_CLICKED, WINDOW_STATE_CHANGED, etc.)
- **Package Name** (which app triggered the event)
- **Class Name** (UI component type)

**Log Tag:** `ClipsyncScan`

**Example Output:**
```
ClipsyncScan: 📊 Scan Event | Type: WINDOW_STATE_CHANGED | Package: com.reddit.frontpage | Class: android.widget.FrameLayout
```

### Phase 2: Dialog/Popup Detection
Detects when dialogs, popups, or share sheets appear with detailed information:

**Log Tag:** `ClipsyncDialog`

**Detection Criteria:**
- Alert Dialogs
- Bottom Sheets
- Popup Windows
- Android Share Sheets (ResolverActivity/ChooserActivity)
- System Dialogs
- Any window with "Dialog", "Popup", "Share" in class name

**Example Output:**
```
ClipsyncDialog: 🔔 ═══════════════════════════════════════
                🔍 DIALOG/POPUP DETECTED!
                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                📱 Package: com.reddit.frontpage
                🏷️  Class: android.app.Dialog
                🎭 Type: Generic Dialog
                🪟 Window Count: 2
                🔗 Has Source: true
                🌐 System Dialog: false
                📦 Root Package: com.reddit.frontpage
                ═══════════════════════════════════════
```

### Phase 3: UI Tree Preview
When a dialog is detected, logs the first 3 levels of the UI tree to help identify share buttons:

```
ClipsyncDialog: 🌳 Dialog UI Tree Preview:
ClipsyncDialog:   ├─ FrameLayout | Text: "" | Desc: ""
ClipsyncDialog:     ├─ LinearLayout (#action_buttons) | Text: "" | Desc: ""
ClipsyncDialog:       ├─ Button (#share_button) | Text: "Share" | Desc: "Share post"
```

### Phase 4: Root Window Scan Tracking
Logs when expensive full-screen scans start and their results:

**Example Output:**
```
ClipsyncScan: 🔍 STARTING Root Window Scan | Package: com.reddit.frontpage | Reason: Window State Changed
ClipsyncScan: ✅ Root Scan SUCCESS - Copy button found
```
or
```
ClipsyncScan: ❌ Root Scan COMPLETE - No copy button found
```

### Phase 5: Scan Completion
Logs when a copy trigger is successfully detected:

```
ClipsyncScan: 🎯 SCAN COMPLETE - Trigger detected: Root Window Scan | Initiating clipboard read
```

---

## 🧪 How to Use

### 1. Install the App
```bash
cd /Users/bunty/Documents/Clipsync/android
./gradlew installDebug
```

### 2. Enable Accessibility Service
- Open Android Settings
- Accessibility → Clipsync
- Enable the service

### 3. Filter Logs by Tag

**For all scanning events:**
```bash
adb logcat -s ClipsyncScan:D
```

**For dialog detection only:**
```bash
adb logcat -s ClipsyncDialog:D
```

**For both:**
```bash
adb logcat -s ClipsyncScan:D ClipsyncDialog:D
```

**Clean output (recommended):**
```bash
adb logcat -c && adb logcat -s ClipsyncScan:D ClipsyncDialog:D
```

### 4. Test Scenarios

#### Scenario A: Reddit Share Button
1. Open Reddit app
2. Scroll through posts (observe scan events)
3. Click Share button on a post
4. **Expected:** Dialog detection log appears
5. Observe the UI tree to find share button location

#### Scenario B: Instagram Share
1. Open Instagram
2. View a post
3. Click Share/Send button
4. **Expected:** System share sheet detected (ChooserActivity)

#### Scenario C: Twitter Copy
1. Open Twitter
2. Long-press on a tweet
3. Click Copy
4. **Expected:** Scan completion log with trigger type

---

## 📈 What to Look For

### Performance Patterns
- **How often does Root Window Scan trigger?**
  - Should only happen on window state changes or every 2+ seconds
- **Does it trigger unnecessarily while scrolling?**
  - Game apps should be automatically skipped

### Dialog Patterns
- **What class names do different apps use for share dialogs?**
  - Reddit: `?`
  - Instagram: `?`
  - Twitter: `?`
  - Chrome: `?`
- **Do system share sheets always show as "ResolverActivity"?**
- **Are there false positives? (dialogs detected that aren't share-related)**

### UI Tree Insights
- **Where are copy/share buttons located in the tree?**
- **What are their ViewIDs?**
- **What text/contentDescription do they have?**

---

## 🔮 Next Steps (Phase 3)

After collecting log data:

1. **Analyze common dialog patterns** across apps
2. **Identify share button locations** in UI trees
3. **Implement focused scanning** when dialogs appear:
   ```kotlin
   if (isDialog(event)) {
       // Only scan dialog window, not entire screen
       scanDialogOnly(event.source)
   }
   ```
4. **Add dialog type whitelist** (only scan share-related dialogs)
5. **Measure performance improvement** (CPU/battery usage)

---

## 🐛 Debugging Tips

**If logs don't appear:**
- Check accessibility service is enabled
- Ensure app has accessibility permission
- Try: `adb logcat | grep Clipsync`

**Too much noise:**
- Add package filters: `adb logcat -s ClipsyncDialog:D | grep "reddit"`
- Focus on specific event types in code

**App crashes:**
- Check: `adb logcat -s AndroidRuntime:E`
- Look for `ClipSync_Service` errors

---

## 📝 Log Tags Reference

| Tag | Purpose | Verbosity |
|-----|---------|-----------|
| `ClipsyncScan` | All scan events, root scans, trigger detection | High |
| `ClipsyncDialog` | Dialog detection, UI tree preview | Medium |
| `ClipSync_Service` | Service errors, Firestore operations | Low |

---

## 🎓 Key Functions Added

1. **`logDialogDetection(event)`** - Analyzes and logs dialog appearances
2. **`isDialogWindow(className)`** - Determines if a window is a dialog
3. **`logNodeTree(node, depth, maxDepth)`** - Pretty-prints UI hierarchy
4. Enhanced logging in **`onAccessibilityEvent()`** - Tracks scan lifecycle

---

**Status:** ✅ Implementation Complete - Ready for Testing
**Branch:** `private-smart-dialog-scanning`
**Next:** Collect real-world data from different apps

