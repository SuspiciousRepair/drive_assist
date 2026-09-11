# Install Drive Assist

**English** · [Português (Brasil)](QUICK-INSTALL.pt-BR.md)

The simplest installation uses one file and a USB drive. Your car must already
allow third-party apps. Keep the car parked while installing.

> [!WARNING]
> If you enabled root ADB to prepare the car, read [what that access means](ADB-ROOT-SAFETY.md).
> Keep network ADB off after installation and install APKs only from a source you trust.

1. Download `drive_assist_installer.apk` from the project release.
2. Copy it to a FAT32 USB drive.
3. Connect the drive to the car and open the built-in File Manager.
4. Tap the installer file and follow the instructions on screen.
5. Open Drive Assist when installation finishes.

The installer adds Drive Assist and its ModeHelper companion. ModeHelper lets the
app restore preferences and use supported vehicle functions. You do not need to
install the two parts separately. The temporary installer removes itself after a
successful setup. See the [installed-component inventory](ADB-ROOT-SAFETY.md#know-what-the-drive-assist-installer-adds)
for package names and verification steps.

If the file will not open, the car may not yet be prepared for third-party apps.
The [technical installation guide](INSTALL-GUIDE.md) covers unlocking, ADB,
computer-assisted installation, verification, and troubleshooting.

[See what Drive Assist does](DRIVER-GUIDE.md) · [Safety and privacy](WELCOME.md)
