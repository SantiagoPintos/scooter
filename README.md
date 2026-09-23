# Scooter Lab

Local Android control for a Xiaomi Scooter 6 Max. It opens an authenticated BLE session,
initializes the MiOT application channel, and can lock or unlock the scooter without needing
Xiaomi Home during everyday use.

## Modules

- `:scooterlab`: BLE protocol, authentication, MiOT cipher, framing, and unit tests.
- `:scooterlabapp`: Android dashboard for scooter setup, automatic connection, and explicit
  user-confirmed control actions.

The protocol research that supports the implementation is under [`docs/`](docs/). Reusable local
instrumentation lives in `scooterlab/tools/`, deliberately excluded from Git along with local
credentials and private captures.

## Development

1. Open the project with Android Studio and JDK 21.
2. Configure a physical Android device with BLE and grant Bluetooth permissions.
3. Provision the local test credential. Never add it to the repository.
4. Run `:scooterlabapp`.

Build the app and run the protocol tests with:

```powershell
.\gradlew.bat :scooterlab:testDebugUnitTest :scooterlabapp:assembleDebug
```

Read [Scooter Lab](scooterlab/README.md), [Scooter Control](scooterlabapp/README.md), and
[AGENTS.md](AGENTS.md) before changing the BLE flow.
