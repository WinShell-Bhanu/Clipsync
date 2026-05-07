# 🔒 Private Branch: smart-dialog-scanning

**Created**: February 18, 2026  
**Purpose**: Experimental feature development - Smart clipboard dialog detection  
**Visibility**: Local only (never pushed to GitHub)

---

## About This Branch

This is a **private experimental branch** for testing and developing the **Smart Dialog Scanning** feature for ClipSync.

### Feature Goal:
Instead of constantly scanning the entire UI for copy buttons, we only activate scanning when:
1. User opens a share dialog/popup
2. System detects the dialog appearance
3. We scan ONLY the dialog content for copy buttons
4. Much more efficient and battery-friendly

---

## Branch Rules

### ✅ DO:
- Experiment freely with new features
- Commit work-in-progress code
- Test aggressive changes
- Break things and learn

### ❌ DON'T:
- Push this branch to GitHub (`git push origin private-smart-dialog-scanning`)
- Merge to main branches without testing
- Share this branch publicly

---

## How to Work on This Branch

### Switch to this branch:
```bash
git checkout private-smart-dialog-scanning
```

### Make changes and commit:
```bash
git add .
git commit -m "Experimental: Dialog detection logic"
```

### Switch back to main branch:
```bash
git checkout Auto-OTP-Copy
```

### When feature is ready, merge to main:
```bash
git checkout Auto-OTP-Copy
git merge private-smart-dialog-scanning
git push origin Auto-OTP-Copy
```

### Delete private branch (optional):
```bash
git branch -d private-smart-dialog-scanning
```

---

## Current Status

**Branch**: `private-smart-dialog-scanning`  
**Base**: `Auto-OTP-Copy`  
**Files to implement**:
- [ ] Smart dialog detection in ClipboardAccessibilityService
- [ ] Dialog-only scanning logic
- [ ] Battery optimization metrics
- [ ] Testing with Reddit, Instagram, Twitter

---

## Notes

- This branch exists ONLY on your local machine
- No remote tracking configured
- Safe to experiment without affecting production code
- Can be deleted anytime without consequences

**Privacy**: 🔒 100% Private - Only visible to you

