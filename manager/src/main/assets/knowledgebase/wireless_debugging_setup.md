# Wireless Debugging & Connection Guide

### Cable-Free Activation (Android 11 - 17)
ShizukuPlus can start directly on your device without a computer using Android's native Wireless Debugging service.

### Step-by-Step Activation
1. **Connect to Wi-Fi** or activate your phone's personal hotspot.
2. In **Settings → Developer Options**, turn on **Wireless Debugging**.
3. Open ShizukuPlus and tap **Pairing** (or use split-screen / picture-in-picture).
4. In Developer Options, tap **Pair device with pairing code**.
5. Enter the 6-digit code and matching port into ShizukuPlus.
6. Once paired, tap **Start** under Wireless Debugging.

### Automatic Reconnect & Anti-Fatigue Protection
- **Single-Prompt Guard**: ShizukuPlus prevents repeated, stacked "Allow USB debugging" dialog prompts. It holds the connection open cleanly and respects your prompt choices.
- **Dynamic Port Discovery (mDNS)**: Since Wireless Debugging picks a new random port every reboot or Wi-Fi reconnect, ShizukuPlus automatically detects the active port using local mDNS.
- **Fixed Port Fallback (TCP Mode)**: If your router or phone blocks mDNS multicast, switch to **TCP Mode (Port 5555)** in Settings to use a constant loopback port (`127.0.0.1:5555`).

### Troubleshooting
- **Port 0 / Cannot Discover**: Toggle Wireless Debugging OFF and ON in Developer Options.
- **Permission Denied (Android 16+)**: Ensure `Nearby Devices` / `Local Network` permission is granted so ShizukuPlus can detect local Wi-Fi services.
- **Local Firewall / VPN**: If using AdGuard, Tailscale, or a local VPN, add `af.shizuku.plus.api` to the bypass list so local loopback traffic (`127.0.0.1`) is not intercepted.
