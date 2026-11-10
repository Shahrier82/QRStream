# 5-minute demo script

**Setup before the room fills (2 minutes of prep, not part of the 5):** two charged phones with the app installed, camera permission already granted on the receiver, a 20 KB test file (a small PDF or image) on the sender, phones in airplane mode to prove no network, a stand or a friend to hold the receiver steady, screen brightness not locked. Have Home > Demo settings ready.

| Time | Do | Say |
|---|---|---|
| 0:00 | Show both phones in airplane mode (status bar, Wi-Fi and Bluetooth off). | "These two phones have no network at all. The only connection is light: one screen and one camera." |
| 0:20 | Open the app on both. Tap **Continue offline** on the sender. | "No account needed. The account is only for syncing history to Firebase." |
| 0:35 | Sender: **Send** > choose the 20 KB file. Point at the info line (frames per pass, time per pass). | "The file is cut into 400-byte chunks. Every chunk becomes one QR code with a header, CRC and a counter. Every four chunks I also send a parity chunk." |
| 1:00 | Leave K = 4, 4 fps. Turn **Simulate frame loss** on at 10 percent. Tap **Start**. | "I'm going to make this hard: ten percent of the frames will be deliberately dropped. The grey crosses are frames the receiver never gets." |
| 1:20 | Receiver: **Receive**. Aim at the sender. Point out the notification and the foreground service. | "Decoding runs on a background thread and a foreground service owns the reassembly, so rotating the phone or switching apps does not lose progress." |
| 1:40 | Watch the grid. When the first blue cell appears, call it out. Rotate the receiver once. | "Green is a chunk that arrived. Blue is a chunk that was lost and rebuilt by XOR from the parity frame and the other three chunks. Rotation did not reset anything." |
| 2:30 | Transfer completes. Show the statistics panel. | "Recovered by parity: [n]. Duplicates ignored: [n]. The file is verified against the SHA-256 from the header. Any corruption would show here instead." |
| 3:00 | Tap **View details**, **Open file**, then **Re-verify hash**. | "The file is stored privately and shared through a FileProvider. Re-verify hashes it again from disk." |
| 3:20 | Back to Home > **History**. Show the row, the filter chips, long-press to show the delete dialog (cancel it). | "History is a ListView over my own ContentProvider. It refreshed by itself when the transfer finished." |
| 3:45 | Stop the sender. Show its History row (SENT). Optionally turn Wi-Fi on and show the cloud icon after sync. | "When there is a network and an account, a WorkManager job uploads only metadata to Firestore. Never the file." |
| 4:00 | Home menu > **Benchmark** > **Run benchmark**. Point to Table 1 and 2. | "One hundred seeded trials per cell. Without parity a single pass succeeds 9 percent of the time at 5 percent loss; with K = 4 it is 73 percent. Because the sender loops, K = 8 is actually fastest overall, and at zero loss parity is pure overhead." |
| 4:40 | Show the test run (Android Studio or terminal): 79 tests passing. | "The protocol package has no Android imports, so all of this is unit-tested on a plain JVM, including a 50 KB end-to-end transfer with 10 percent seeded loss." |
| 4:55 | Close. | "Light is slow, about 1.6 kilobytes a second, but it works anywhere there is a screen and a camera." |

## If something goes wrong
- **Receiver does not lock on:** move the phone back (20 to 30 cm), turn the sender brightness up, avoid glare, try chunk size 200 and level M error correction.
- **Transfer stalls at 98 percent:** two losses landed in one group. Say so: "XOR repairs one per group, the next pass delivers the other." That is the design, and it makes a good talking point.
- **Camera permission denied by accident:** use **Open app settings** on the permission panel; it shows the denial handling live.
- **Firebase not configured:** skip the cloud step and say it is optional by design.
