# Tablet deployment check

## Setup and build

On Debian 12:

```sh
sudo apt-get update
sudo apt-get install --no-install-recommends adb aapt apksigner zipalign default-jdk-headless android-framework-res libsmali-java iproute2 usbutils
bash tools/deployment-check/build.sh
```

The script generates `tmp/deployment-check/deployment-check.apk` and verifies its signature. Its local debug signing key is for testing only.

## Connect and authorize

Start the ADB server in a separate terminal if it is not already running:

```sh
adb -L tcp:5037 server nodaemon
```

Use explicit IPv4 for clients in this environment:

```sh
adb -H 127.0.0.1 connect 192.168.0.101:5555
adb -H 127.0.0.1 devices -l
```

Unlock the tablet and accept its debugging authorization prompt. The transport must report `device`, rather than `unauthorized`. Its DHCP address may change; the original connection notes identify it as a Leaderhub K-M10P.

## Verify installation and launch

After authorization, verify identity and Android API level (31 or newer required):

```sh
adb -H 127.0.0.1 -s 192.168.0.101:5555 shell getprop ro.product.model
adb -H 127.0.0.1 -s 192.168.0.101:5555 shell getprop ro.build.version.sdk
adb -H 127.0.0.1 -s 192.168.0.101:5555 install -r tmp/deployment-check/deployment-check.apk
adb -H 127.0.0.1 -s 192.168.0.101:5555 shell am start -W -n org.gossipgoblin.deploymentcheck/.MainActivity
adb -H 127.0.0.1 -s 192.168.0.101:5555 shell dumpsys activity activities
```

Check that installation reports `Success`, the activity starts successfully and is resumed, and the screen displays “Deployment check passed.” All device checks passed on 2026-11-22; see `docs/architecture.md` for the verified device and outcome.

To remove the probe when no longer needed:

```sh
adb -H 127.0.0.1 -s 192.168.0.101:5555 uninstall org.gossipgoblin.deploymentcheck
```
