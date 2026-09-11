# Root ADB gives full access to your car's screen

**English** · [Português (Brasil)](ADB-ROOT-SAFETY.pt-BR.md)

> [!WARNING]
> Root ADB is an administrator shell on the vehicle head unit. Anyone who can
> reach it can run commands, install or replace apps, read stored data, use
> hardware exposed to Android, and change system files without a confirmation on
> the car screen.

This access is useful for installing and studying Drive Assist, but it is not a
normal consumer feature. Treat it like an unlocked computer that travels with
you and later connects to your home network.

## What an attacker could learn or change

Depending on the software and permissions on the head unit, access may expose:

- current and past locations, regular routes, and where the car is usually parked;
- battery, charging, speed, door, climate, and other vehicle readings;
- Wi-Fi names, network details, paired-device information, and app credentials;
- exterior camera streams and files stored by apps;
- the ability to install persistent software or keep the unit awake and drain
  the 12 V battery.

Root access to the infotainment unit does not automatically mean direct control
of steering or brakes. It still carries serious privacy, network, reliability,
and vehicle-interface risk.

## Know what the Drive Assist installer adds

A normal USB installation has three identifiable parts:

| Name | Android package | Why it is there |
| :--- | :--- | :--- |
| Drive Assist | `com.geely.drivemem` | The dashboard, settings, statistics, and optional integrations you use. |
| ModeHelper | `com.geely.modehelper` | A small privileged companion used for supported system and vehicle operations. It has much greater access than an ordinary app. |
| Drive Assist Installer | `com.geely.installer` | Installs the two packages above, then removes itself when setup succeeds. It should not remain installed. |

The optional SysProbe engineering tool (`com.geely.sysprobe`) is not required for
normal use and is not part of the normal owner installation. If you did not
deliberately install a diagnostic tool, it should not be present.

In Drive Assist, open **Settings → System** to see the installed version and the
SHA-256 fingerprint of the running APK. Owners using ADB can also list these
packages:

```bash
adb shell pm list packages | grep 'com.geely'
```

Review the release notes before every update. An update should say whether it
adds another package, requests a new permission, changes a system file, or starts
a new network connection. Do not accept “install this APK” as a complete
explanation of what will be installed.

## Safer habits

1. Install APKs only from the official project release or build them from source.
   A familiar name and icon do not prove that an APK is safe.
2. Compare the APK's SHA-256 checksum with the published release value before
   installing it.
3. Keep network ADB off when you are not actively using it. Drive Assist limits
   its ADB gate to the configured trusted Wi-Fi and closes it automatically, but
   you should still check that it is off.
4. Put the car on an isolated guest or IoT Wi-Fi network. Do not give the head
   unit open access to computers, storage devices, or other sensitive equipment.
5. Do not share screenshots that contain tokens, passwords, server addresses,
   Wi-Fi names, VINs, or precise location data.
6. Do not install modified APKs received through chats, file shares, or forums.

The [full vehicle software security guide](SECURITY-SAFETY.md) explains platform
keys, privileged permissions, APK repackaging, checksums, network isolation, and
the specific security findings for the IHU629G.
