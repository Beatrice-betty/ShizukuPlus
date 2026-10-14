# Root Compatibility & SU Bridge

### Overview
ShizukuPlus enables root-only applications to function **without permanent root access** (no Magisk, KernelSU, or bootloader unlocking required).

### Storage Bridge vs. SU Bridge
- **Storage Bridge**: Grants privileged file read/write access to restricted app directories (like `/Android/data` on Android 14–17 / One UI 8) via Android Document Provider IPC. It does **not** simulate an `su` binary.
- **SU Bridge**: Creates a lightweight, standards-compliant `su` executable wrapper in `/data/local/tmp` that intercepts `su -c` and routes execution through the Shizuku server (`uid 2000`).

### Quick Setup for Root Apps (e.g. NeoBackup, AdAway, Swift Backup)
1. On the ShizukuPlus Home screen, tap **Root Compatibility** or **Export Shizuku files**.
2. Tap **Setup All** or copy the custom binary path:
   `PATH="/data/local/tmp:$PATH"`
3. In your target app's Settings, find **Root Access** or **Custom SU Binary Path**.
4. Paste `/data/local/tmp/su` as the executable path.
5. Grant the app permission in ShizukuPlus when prompted.

### Command Translation & Sandboxing
- **Real Execution**: System properties (`resetprop`, `getprop`, `setprop`), package management (`pm`), activity management (`am`), app ops, network policies.
- **Ghosted / Mocked Success**: Kernel module loading (`insmod`/`rmmod`), raw block partition writes, system reboot commands.
- **Google Wallet & Play Integrity Protection**: ShizukuPlus isolates the SU binary. If you disable the bridge or need to pass strict Play Integrity, tap **Clean Up Bridge** in Service Doctor to instantly remove any residual `/data/local/tmp/su` files.
