# Security, Privacy & Sandboxing Architecture

### Security Philosophy
Traditional rooting tools (Magisk, KernelSU, APatch) grant Ring-0 / `uid=0` privileges that bypass all Linux Discretionary Access Controls (DAC) and Android SELinux rules. This introduces critical security risks: rogue apps can silently read private credentials, compromise banking tokens, or cause permanent bootloops.

### The Shizuku Privilege Model (`uid 2000`)
ShizukuPlus strictly operates within Android's `android.uid.shell` (UID 2000) security context:
- **Enforced Sandbox**: Cannot tamper with the Linux kernel, boot partitions, or cryptographic hardware keystores.
- **Google Wallet & Banking Safety**: Because system integrity remains untouched, Play Integrity (DEVICE / BASIC integrity) and Samsung Knox remain un-tripped.
- **Binder IPC Security**: Client applications cannot execute unauthorized actions; every request is verified via Binder calling UID token checks.

### Full Auditing & Transparency
- **Live Activity Log**: ShizukuPlus maintains an audit log of every Binder request, transaction type, calling application, and timestamp.
- **One-Tap Kill Switch**: Tapping **Stop** immediately halts the Shizuku server and disconnects all authorized clients.
- **Granular Permissions**: You can revoke access from any authorized app at any time without restarting the phone.
