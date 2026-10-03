# pilot-web

Operator's remote: `index.html` + `pilot.css` + `pilot.js`, no build step, no libraries (stage 5 spec §7).

Delivery: the `syncPilotWeb` Gradle task in `android-app/app` copies this folder (without this README) into the APK's
assets under `pilot/`. There is no checked-in copy; edit the files here and rebuild the app.

Open `http://<phone-ip>:8080/` from a laptop on the same Wi-Fi (the address is in the app's status bar and notification).
The first tab to connect drives; other tabs watch, and anyone can STOP.

| Input | Action |
|---|---|
| Shift (hold) or "Hold to drive" | deadman: drive frames are sent only while it is held |
| W / S | forward / back |
| A / D | left / right (strafe) |
| Q / E | turn counter-clockwise / clockwise |
| Space or STOP | STOP ×3 on the robot |
| Left / right stick (mouse or touch) | move / turn |
| Speed limit | scales every axis; 30 % on every page load |

The deadman is released on Shift up, window blur, hidden tab, socket loss, a role change, any STOP (from any tab or
the robot's own screen) and the robot leaving READY; a release also forgets held keys and sticks. After that, driving
needs a new press. The server enforces the same: after a STOP the page didn't send, it ignores a still-held deadman
until it is released. Keys pressed with Cmd, Ctrl or Alt are ignored.

While the phone shows the Test screen's Raw tab, remote driving is blocked (bench mode), and pressing Home there stops
the robot.
