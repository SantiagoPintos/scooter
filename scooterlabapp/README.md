# Scooter Control

Android dashboard for a Xiaomi Scooter 6 Max. It stores the chosen scooter locally, attempts an
automatic BLE connection at launch, and shows lock controls only after the authenticated MiOT
channel is ready.

Every physical action requires explicit user confirmation. The app validates the matching
authenticated MiOT response before reporting the action as confirmed.

## Setup and use

1. Open **Settings** and choose the scooter once.
2. Return to **Dashboard**; future launches reconnect automatically when Bluetooth and the local
   credential are available.
3. Use the lock control only after the dashboard reports **Connected**.

The selected Bluetooth address is held in private app preferences. The local credential lives at
`files/lab_credential.bin`, enables later connections without Xiaomi Home, is never shown in the
UI, and is excluded from Git.

## Development and reinstall notes

### Debug APK signing

Android treats the application ID and signing certificate as the identity of an installed app.
An APK can update the test phone only when it is signed by the same key as the installed version.
For the normal debug workflow, that key is the Android debug keystore in the current Windows
user profile, usually `%USERPROFILE%\.android\debug.keystore`.

- Build and install from the same Windows profile and prefer Android Studio or the repository's
  `gradlew.bat` from that profile.
- Do not delete, regenerate, copy, or replace the debug keystore while this test installation is
  still needed.
- Avoid building an APK intended for the phone from a sandbox, service account, or alternate home
  directory: it can use a different debug keystore.
- If `adb install -r` reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, do not uninstall immediately.
  The installed APK and the new APK have different certificates. Find the original signing key if
  preserving the existing app data matters.

### Local credential backup and restoration

The test credential is a 32-byte local secret. Its intentionally ignored backup is
`scooterlab/local/lab_credential.bin`. Keep that file private, out of commits, screenshots,
logs, terminal output, and shared archives. Confirm only that it exists and is 32 bytes; never
read or print its content.

An uninstall clears the app's private credential and the selected scooter preference. If a clean
install is unavoidable:

1. Confirm the ignored backup exists and is 32 bytes.
2. Uninstall and install the APK, then launch the app once so Android creates its private
   `files/` directory.
3. Copy the backup through a temporary device file, then use `run-as` with `cp` to create it as
   the application user. Do not use shell redirection (`>`), which can create an inaccessible
   file owned by the wrong user.
4. Delete the temporary device file and verify only that the private credential file is non-empty
   and 32 bytes.
5. Open **Settings**, scan, and select the scooter again. Automatic connection resumes on later
   launches.

Example, replacing `<serial>` with the selected physical device:

```powershell
adb -s <serial> push scooterlab/local/lab_credential.bin /data/local/tmp/scooterlab_lab_credential.bin
adb -s <serial> shell "run-as com.velocimetro.scooterlabapp cp /data/local/tmp/scooterlab_lab_credential.bin files/lab_credential.bin"
adb -s <serial> shell rm -f /data/local/tmp/scooterlab_lab_credential.bin
```

The application should be launched once before this copy. If the file was previously created by
the wrong user, remove that empty file first and repeat the `run-as ... cp` command. Do not work
around a signing mismatch by copying the whole private app directory.

### Development note

The persistence model targets one test phone. Any distributed release must migrate credentials to
Android Keystore and add rotation, revocation, and production-grade error handling. The optional
local backup is `scooterlab/local/lab_credential.bin`; that entire directory is Git-ignored and
must never be shared.
