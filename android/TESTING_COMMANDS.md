# Quick Testing Commands

## 🚀 Build & Install
```bash
# Build debug APK
./gradlew assembleDebug

# Install on device
./gradlew installDebug

# Or both at once
./gradlew installDebug
```

## 📱 View Logs

### Watch All Clipsync Logs (Recommended)
```bash
adb logcat -c && adb logcat -s ClipsyncScan:D ClipsyncDialog:D ClipSync_Service:D
```

### Scanning Events Only
```bash
adb logcat -s ClipsyncScan:D
```

### Dialog Detection Only
```bash
adb logcat -s ClipsyncDialog:D
```

### Filter by App (e.g., Reddit)
```bash
adb logcat -s ClipsyncDialog:D | grep "reddit"
```

### Save Logs to File
```bash
adb logcat -s ClipsyncScan:D ClipsyncDialog:D > clipsync_logs.txt
```

## 🧪 Testing Workflow

### 1. Clear logs and start fresh
```bash
adb logcat -c
```

### 2. Start monitoring
```bash
adb logcat -s ClipsyncScan:D ClipsyncDialog:D
```

### 3. Test on device:
- Open Reddit → Click Share on post
- Open Instagram → Click Share on post  
- Open Twitter → Long press → Copy
- Open Chrome → Share page

### 4. Analyze patterns in logs

## 🔍 Useful ADB Commands

### Check if accessibility service is running
```bash
adb shell dumpsys accessibility | grep -A 5 "ClipboardAccessibilityService"
```

### Force stop app
```bash
adb shell am force-stop com.bunty.clipsync
```

### Restart app
```bash
adb shell am start -n com.bunty.clipsync/.MainActivity
```

### Check app is installed
```bash
adb shell pm list packages | grep clipsync
```

### View all accessibility services
```bash
adb shell settings get secure enabled_accessibility_services
```

## 📊 Performance Monitoring

### CPU Usage
```bash
adb shell top | grep clipsync
```

### Memory Usage
```bash
adb shell dumpsys meminfo com.bunty.clipsync
```

## 🐛 Debugging

### All errors
```bash
adb logcat *:E
```

### Clipsync errors only
```bash
adb logcat -s ClipSync_Service:E AndroidRuntime:E
```

### Live filtering with colors (if supported)
```bash
adb logcat -v color -s ClipsyncScan:D ClipsyncDialog:D
```

## 💡 Tips

- Use `Ctrl+C` to stop logcat
- Add `-v time` for timestamps: `adb logcat -v time -s ClipsyncScan:D`
- Use `grep -v` to exclude patterns: `adb logcat -s ClipsyncScan:D | grep -v "VIEW_FOCUSED"`
- Clear logs between tests to reduce noise

