# Tailscale Android - pars5555 Fork

This is a custom fork of the [Tailscale Android client](https://github.com/tailscale/tailscale-android) with additional features for external app integration and automation.

## Fork Features

### 1. ContentProvider API (`TailscaleProvider`)

A public ContentProvider that allows external apps to query VPN status and control the VPN.

**Authority:** `com.tailscale.ipn.provider`

#### Query Endpoints

| URI | Description | Returns |
|-----|-------------|---------|
| `content://com.tailscale.ipn.provider/exit_node` | Get current exit node | Cursor with `id`, `name`, `allow_lan_access` |
| `content://com.tailscale.ipn.provider/disallowed_apps` | Get bypassed apps | Cursor with `package_name` |

**Example - Query exit node:**
```powershell
adb shell content query --uri content://com.tailscale.ipn.provider/exit_node
```

**Example - Query disallowed apps:**
```powershell
adb shell content query --uri content://com.tailscale.ipn.provider/disallowed_apps
```

#### Call Methods (Actions)

| Method | Argument | Extras | Description |
|--------|----------|--------|-------------|
| `connect_vpn` | - | - | Connect to VPN |
| `disconnect_vpn` | - | - | Disconnect from VPN |
| `use_exit_node` | Exit node name/ID | `allow_lan_access` (boolean, optional) | Set exit node |
| `disallow_app` | Package name | - | Add app to VPN bypass list |
| `allow_app` | Package name | - | Remove app from VPN bypass list |

All `call()` methods return a Bundle with:
- `success` (boolean): Whether the operation succeeded
- `error` (string, optional): Error message if failed

**Example - Connect VPN:**
```powershell
adb shell content call --uri content://com.tailscale.ipn.provider --method connect_vpn
```

**Example - Set exit node:**
```powershell
adb shell content call --uri content://com.tailscale.ipn.provider --method use_exit_node --arg "us-east-1"
```

**Example - Set exit node with LAN access:**
```powershell
adb shell content call --uri content://com.tailscale.ipn.provider --method use_exit_node --arg "us-east-1" --extra allow_lan_access:b:true
```

**Example - Disallow app from VPN:**
```powershell
adb shell content call --uri content://com.tailscale.ipn.provider --method disallow_app --arg "com.example.app"
```

**Example - Allow app back to VPN:**
```powershell
adb shell content call --uri content://com.tailscale.ipn.provider --method allow_app --arg "com.example.app"
```

### 2. Broadcast Receiver Intents

The `IPNReceiver` also supports these broadcast intents:

| Intent Action | Extras | Description |
|---------------|--------|-------------|
| `com.tailscale.ipn.CONNECT_VPN` | - | Connect to VPN |
| `com.tailscale.ipn.DISCONNECT_VPN` | - | Disconnect from VPN |
| `com.tailscale.ipn.USE_EXIT_NODE` | `exitNode` (string), `allowLanAccess` (boolean, optional) | Set exit node |
| `com.tailscale.ipn.DISALLOW_APP` | `packageName` (string) | Add app to bypass list |
| `com.tailscale.ipn.ALLOW_APP` | `packageName` (string) | Remove app from bypass list |
| `com.tailscale.ipn.GET_EXIT_NODE` | - | Log current exit node (check logcat tag `TailscaleExitNode`) |
| `com.tailscale.ipn.GET_DISALLOWED_APPS` | - | Log disallowed apps (check logcat tag `TailscaleDisallowedApps`) |

**Example - Use exit node via broadcast:**
```powershell
adb shell am broadcast -n com.tailscale.ipn/.IPNReceiver -a com.tailscale.ipn.USE_EXIT_NODE --es exitNode "us-east-1"
```

### 3. Disallow App Feature

Apps added to the "disallow" list will bypass the VPN tunnel - their traffic goes through the normal internet connection instead of Tailscale. Useful for:
- Banking apps that block VPN connections
- Streaming apps with geo-restrictions
- Apps that need local network access

### 4. Custom Version Suffix

The fork automatically appends `-pars5555` to the version reported to the Tailscale admin panel (e.g., `1.92.0-pars5555`).

---

## Development Environment Setup (Windows + WSL)

### Prerequisites

- Windows 10/11 with WSL2 enabled
- Android Studio (for SDK and running the app)
- ADB configured

### Step 1: Install WSL and Ubuntu

```powershell
wsl --install -d Ubuntu
```

### Step 2: Install Go in WSL

```bash
# In WSL terminal
cd ~
wget https://go.dev/dl/go1.25.5.linux-amd64.tar.gz
sudo tar -C /usr/local -xzf go1.25.5.linux-amd64.tar.gz
echo 'export PATH=$PATH:/usr/local/go/bin:$HOME/go/bin' >> ~/.bashrc
source ~/.bashrc
```

### Step 3: Install Android SDK and NDK in WSL

```bash
# Create SDK directory
mkdir -p ~/android-sdk
cd ~/android-sdk

# Download command-line tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip
mkdir -p cmdline-tools/latest
mv cmdline-tools/* cmdline-tools/latest/ 2>/dev/null || true

# Set environment variables
echo 'export ANDROID_HOME=~/android-sdk' >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools' >> ~/.bashrc
source ~/.bashrc

# Accept licenses and install components
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-26" "platforms;android-34"

# Install NDK r23c (required for gomobile)
cd ~/android-sdk
mkdir -p ndk
cd ndk
wget https://dl.google.com/android/repository/android-ndk-r23c-linux.zip
unzip android-ndk-r23c-linux.zip
mv android-ndk-r23c 23.1.7779620
```

### Step 4: Install Go Mobile Tools

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init
```

### Step 5: Clone the Repository

```powershell
# In PowerShell
cd C:\Users\YourUsername\AndroidStudioProjects
git clone https://github.com/pars5555/tailscale-android.git
cd tailscale-android
```

### Step 6: Configure the Build Script

Edit `build_libtailscale.sh` and update paths if needed:

```bash
# Update these paths to match your WSL setup:
export GOROOT=/home/YOUR_WSL_USERNAME/go/pkg/mod/golang.org/toolchain@v0.0.1-go1.25.5.linux-amd64
# Or if you installed Go to /usr/local:
# export GOROOT=/usr/local/go

export GOPATH=$HOME/go
export ANDROID_HOME=~/android-sdk
export ANDROID_NDK_HOME=~/android-sdk/ndk/23.1.7779620
```

Also update the project path at the top of the script:
```bash
cd /mnt/c/Users/YOUR_USERNAME/AndroidStudioProjects/tailscale-android
```

---

## Building the Project

### Step 1: Build libtailscale.aar (Go native library)

```powershell
# From PowerShell, run the WSL build script
wsl -e bash -c "sed -i 's/\r$//' /mnt/c/Users/pars/AndroidStudioProjects/tailscale-android/build_libtailscale.sh && bash /mnt/c/Users/pars/AndroidStudioProjects/tailscale-android/build_libtailscale.sh"
```

This will:
1. Read version info from `tailscale.version`
2. Generate ldflags with your custom version suffix
3. Build `android/libs/libtailscale.aar`

### Step 2: Build the Android APK

```powershell
cd android
.\gradlew clean assembleDebug
```

### Step 3: Install on Device

```powershell
adb install -r android\build\outputs\apk\debug\android-debug.apk
```

---

## Versioning Workflow

### How Versions Work

| Location | Source | Example |
|----------|--------|---------|
| App Settings | `tailscale.version` → `VERSION` | `1.92.0` |
| Tailscale Admin Panel | `tailscale.version` → `VERSION_LONG` + fork suffix | `1.92.0-pars5555` |

### Customizing the Fork Suffix

Edit `version-ldflags.sh` and change line 4:

```bash
FORK_SUFFIX="-pars5555"  # Change to your preferred suffix
```

### The `-dev` Suffix

The `version-ldflags.sh` script automatically strips the `-dev` suffix from `VERSION_LONG` before adding your custom suffix. So `1.92.0-dev` becomes `1.92.0-pars5555`.

---

## Updating from Upstream

### One-Time Setup (already done)

```powershell
git remote add upstream https://github.com/tailscale/tailscale-android.git
```

### Sync with Upstream

```powershell
# 1. Fetch latest from original Tailscale repo
git fetch upstream

# 2. Merge their changes into your main branch
git merge upstream/main

# 3. Resolve any conflicts if prompted, then:
git add .
git commit -m "Merge upstream changes"

# 4. Optionally regenerate version file (if upstream updated it)
wsl -e bash -c "cd /mnt/c/Users/pars/AndroidStudioProjects/tailscale-android && ./tool/go run tailscale.com/cmd/mkversion > tailscale.version"

# 5. Rebuild libtailscale with your version suffix
wsl -e bash /mnt/c/Users/pars/AndroidStudioProjects/tailscale-android/build_libtailscale.sh

# 6. Rebuild the Android app
cd android
.\gradlew clean assembleDebug

# 7. Push to your fork
cd ..
git push origin main
```

After updating, your admin panel will show the new upstream version with your suffix (e.g., `1.95.0-pars5555`).

---

## File Structure (Fork Changes)

```
tailscale-android/
├── FORK_README.md                    # This file
├── build_libtailscale.sh             # WSL build script for libtailscale.aar
├── version-ldflags.sh                # Modified to add custom version suffix
├── tailscale.version                 # Version properties file
└── android/src/main/java/com/tailscale/ipn/
    ├── TailscaleProvider.kt          # NEW: ContentProvider for external access
    ├── DisallowAppWorker.kt          # NEW: Worker for managing disallowed apps
    ├── IPNReceiver.java              # MODIFIED: Added new intent handlers
    └── AndroidManifest.xml           # MODIFIED: Registered new components
```

---

## Troubleshooting

### "gomobile: command not found"

Make sure `$GOPATH/bin` is in your PATH:
```bash
export PATH=$PATH:$HOME/go/bin
```

### "No compiler for arm was found in the NDK"

You need the Linux NDK, not the Windows one. Install it in WSL:
```bash
cd ~/android-sdk/ndk
wget https://dl.google.com/android/repository/android-ndk-r23c-linux.zip
unzip android-ndk-r23c-linux.zip
mv android-ndk-r23c 23.1.7779620
```

### Line ending issues (`$'\r': command not found`)

Fix CRLF line endings in bash scripts:
```bash
sed -i 's/\r$//' build_libtailscale.sh
sed -i 's/\r$//' version-ldflags.sh
```

### Version shows "ERR-BuildInfo" in admin panel

The `version-ldflags.sh` script has incorrect variable names. Make sure it uses:
- `${VERSION_LONG}` (not `${VERSION_SHORT}`)
- `${SHORT_VERSION}` (not `${VERSION_SHORT}`)
- `${COMMIT}` (not `${VERSION_GIT_HASH}`)

---

## License

BSD-3-Clause (same as Tailscale)
