# Vehicle Signing Keystore

This directory contains `debug.ks`, the RSA 2048-bit signing key used to sign `drive_assist.apk`.

### Why this key is committed to the repository:
On the vehicle's Android system, `pm install -r` strictly requires that package updates are signed with the identical certificate as the currently installed package. If an update is signed with a different key, Android rejects the installation with:
```
INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.geely.drivemem signatures do not match previously installed version
```
Committing this public development keystore ensures that:
1. Automated CI builds (GitHub Actions) sign release APKs with the exact certificate expected by the vehicle.
2. Local developer builds produce APKs that can be installed directly over existing installations without uninstallation or data loss.
3. No external keystore configuration or secret management is required to build update-compatible binaries.

### Keystore Parameters:
- **File**: `keystore/debug.ks`
- **Alias**: `d`
- **Store Password**: `android`
- **Key Password**: `android`
- **Distinguished Name (DName)**: `CN=DriveAssist, OU=Car, O=DriveAssist, L=City, S=State, C=US`
- **Algorithm**: RSA 2048-bit, SHA-256 with RSA
