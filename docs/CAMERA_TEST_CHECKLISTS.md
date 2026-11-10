# Camera flow: manual / instrumented test checklists

Use two phones (A sends, B receives) with the debug build installed. Tick each box and note the device models and Android versions at the top of your report table.

Devices: A = ______________ (Android ___)   B = ______________ (Android ___)

---

## Checklist 1: Happy path, permissions and lifecycle

| # | Step | Expected result | Pass |
|---|---|---|---|
| 1.1 | Fresh install on B. Open Receive. | A system camera permission prompt appears. The panel explains why the camera is needed. | [ ] |
| 1.2 | Tap **Deny**. | Panel says the camera is needed, shows **Open app settings**. No crash. Preview stays black. | [ ] |
| 1.3 | Tap **Open app settings**, grant Camera, return with Back. | Panel disappears by itself, preview starts, "Point the camera..." message shown. | [ ] |
| 1.4 | On A: Send > Choose file (a 20 KB text or image file) > Start. | QR code fills the screen at full brightness, frame counter runs, notification "Sending: frame n/N" appears. | [ ] |
| 1.5 | Point B at A's screen from about 20 to 30 cm. | Grid fills with green cells, progress bar moves, decoded fps is about the chosen fps. | [ ] |
| 1.6 | Wait for completion. | "Received and verified" shown, statistics panel appears, a toast says the transfer finished, a "File received" notification appears. | [ ] |
| 1.7 | On B tap **View details**. | Details show the file name, size, SHA-256, status COMPLETED. **Open file** opens it in another app. **Re-verify hash** says the hash matches. | [ ] |
| 1.8 | On A press **Stop**. | Statistics panel shows frames sent, passes >= 1. History on A has a SENT / COMPLETED row. | [ ] |
| 1.9 | **Rotate B** during a transfer. | The grid and progress keep their values (state lives in the service). The camera restarts. No duplicate session. | [ ] |
| 1.10 | **Rotate A** during sending. | The QR code continues, counter continues, no restart from frame 1. | [ ] |
| 1.11 | Press Home on B in mid-transfer, wait 10 seconds, reopen from the notification. | Receiving continued or is paused, the app returns to the Receive screen with state intact. | [ ] |
| 1.12 | Press **Stop** in the notification on A. | Sending stops, notification disappears, History shows INTERRUPTED if less than one full pass was shown. | [ ] |
| 1.13 | Leave A's screen idle on Send. | The screen does not turn off. After leaving the screen, brightness returns to the system value. | [ ] |
| 1.14 | Pick a file larger than 200 KB on A. | A clear message explains the 200 KB limit. Start stays disabled. | [ ] |

---

## Checklist 2: Loss, parity recovery and failure handling

| # | Step | Expected result | Pass |
|---|---|---|---|
| 2.1 | On A turn on **Simulate frame loss**, 10 percent, K = 4, 20 KB file. Start. | Some frames show a grey cross instead of a QR code. The counter shows "n frames dropped". | [ ] |
| 2.2 | B receives. | Some chunk cells turn **blue** (recovered by parity). The transfer completes and is verified. | [ ] |
| 2.3 | After 2.2 check B's statistics. | "Recovered by parity" > 0, duplicates >= 0, overhead shown, integrity "SHA-256 verified". | [ ] |
| 2.4 | Repeat with **No parity** and 10 percent loss. | B needs more passes (more total frames) and the chunk grid never shows blue cells. | [ ] |
| 2.5 | Repeat with 40 percent loss and K = 2. | Still completes after some passes, or stays incomplete; the app never reports success without a matching hash. | [ ] |
| 2.6 | Start B first, then start A mid-loop (late join). | B locks on the next repeated META frame (within 10 frames) and completes. | [ ] |
| 2.7 | Start two senders (A and a third phone) and aim B at A only. | B ignores frames from other sessions. No mixed content. | [ ] |
| 2.8 | Cover half of A's screen for 3 seconds during a transfer. | Transfer continues afterwards; "rejected" or missing counts may rise; no crash. | [ ] |
| 2.9 | While receiving, simulate low battery (emulator: `adb shell dumpsys battery set level 4` on a debug device, or Android Studio extended controls). | Transfer pauses, "Battery low: transfer paused" notification with **Resume**. Tapping Resume continues. | [ ] |
| 2.10 | Deny the notification permission (Android 13+). | Transfer works, UI still shows progress. | [ ] |
| 2.11 | Turn off Wi-Fi and mobile data on both phones (airplane mode). | The whole transfer works. History rows stay "Not yet" synced until you are online and signed in. | [ ] |
| 2.12 | Sign in, go online. | Within a minute History rows show the cloud icon and Firestore has matching documents (metadata only). | [ ] |
| 2.13 | Long-press a History row, confirm delete. | Row and stored file disappear and the list refreshes automatically. | [ ] |
| 2.14 | Check Crashlytics after forcing a decode failure (cover the camera and point at unusual patterns). | Handled-exception entries appear for decode failures, rate limited. | [ ] |

---

## Optional instrumented checks

- `./gradlew connectedDebugAndroidTest` runs `TransferProviderInstrumentedTest` (insert, query, update, delete, MIME types, validation).
- An Espresso test for the permission denial path needs `GrantPermissionRule` for the grant case and the UiAutomator permission dialog for the deny case; checklist item 1.2 covers it manually.
