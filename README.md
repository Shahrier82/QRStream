# QR Stream

Transfer small files (up to 200 KB) between two Android phones using **only the screen and the camera**. No internet, Bluetooth or Wi-Fi is involved in the transfer.

The sender splits the file into numbered chunks and shows them as a looping sequence of QR codes. The receiver scans them, tracks which chunks are missing, repairs lost chunks with XOR parity, rebuilds the file and verifies it with SHA-256.

Course project for *Advanced Programming Lab*. It demonstrates algorithmic depth (framing, CRC, XOR erasure coding, reassembly, seeded loss simulation, benchmark) and the Android components **Service, Broadcast Receiver, Content Provider, ListView and Firebase**.

> **Verification status (please read).** The pure-Java `protocol/` package and `Formatters` are compiled and tested: **79 JUnit tests pass** and the benchmark table below was produced by running them. The Android layers (UI, service, provider, Firebase, CameraX) were written without an Android SDK available and **have not been compiled or run on a device yet**. Static cross-checks (XML validity, every string/drawable/color/id/class reference, ViewBinding fields, `getString` argument counts) pass, but expect to fix a few compile errors on your first Gradle sync. See [Known limitations](#known-limitations).

---

## Contents

1. [Features](#features)
2. [Architecture](#architecture)
3. [Project layout](#project-layout)
4. [Frame protocol](#frame-protocol)
5. [Algorithms and complexity](#algorithms-and-complexity)
6. [Build and run](#build-and-run)
7. [Firebase setup](#firebase-setup)
8. [Tests](#tests)
9. [Benchmark results](#benchmark-results)
10. [Android component map](#android-component-map)
11. [Known limitations](#known-limitations)

---

## Features

- **Send**: file picker (Storage Access Framework, no storage permission), sliders for chunk size (200 to 800 bytes), parity group size K (none, or 2 to 8) and frames per second (1 to 10), QR error-correction level L or M, large QR display, frame counter, Start / Pause / Stop. Maximum screen brightness and keep-awake while on this screen.
- **Receive**: CameraX preview, ML Kit QR decoding on a background analyzer thread, chunk grid (grey missing, green received, blue recovered by parity), progress bar, live stats (decoded fps, duplicates ignored, recovered, rejected).
- **Parity recovery**: one parity frame per K data frames repairs exactly one lost chunk per group.
- **Demo mode**: "Simulate frame loss" drops X percent of frames (seeded, so every demo is identical) to show recovery on a single pair of phones.
- **Statistics panel** after each transfer: frames, passes, duplicates, recovered by parity, time, throughput, overhead, integrity.
- **Benchmark screen** (and JUnit test) that prints the comparison table of parity settings.
- **History**: ListView with filter (All / Sent / Received), tap for details, long-press to delete, empty state, auto-refresh.
- **Details**: all metadata, Open (FileProvider), Share, Re-verify hash.
- **Account**: Firebase email/password, or "Continue offline". The core transfer needs no account and no internet.
- **Cloud sync**: WorkManager uploads history metadata (never file contents) to Firestore when online.

---

## Architecture

```
                           +--------------------------------------------------+
                           |                     ui/                          |
                           |  Login  Home  Send  Receive  History  Details    |
                           |  Benchmark   ChunkGridView   TransferCursorAdapter|
                           +----+---------------+------------------+----------+
                                |  bind         |  query/delete    | register
                                |  (Binder)     |  (ContentResolver)| (dynamic)
                                v               v                  v
        camera frames    +-------------+   +--------------+   +--------------------+
        (QR text)  ----> | service/    |   | provider/    |   | receiver/          |
                         | Transfer-   |   | Transfer-    |   | TransferStatus-    |
                         | Service     |   | Provider     |   | Receiver           |
                         | (foreground)|   | (SQLite)     |   | BatteryLowReceiver |
                         +------+------+   +------^-------+   +---------^----------+
                                |  insert rows    |                     |
                                +-----------------+   PROGRESS/COMPLETED/FAILED
                                |                      (setPackage broadcasts)
                                | uses
                                v
                         +---------------------------------------------+
                         | protocol/   (pure Java, zero Android imports)|
                         |  Chunker  ParityEncoder  ParityRecovery      |
                         |  Frame  FrameCodec  Crc32Util  HashUtil      |
                         |  SendPlan  Reassembler  LossSimulator        |
                         +---------------------------------------------+

         sync/: SyncWorker (WorkManager, network constraint, backoff)
                reads unsynced rows via ContentResolver --> FirestoreRepository
                --> Firestore users/{uid}/transfers/{id}        (metadata only)
```

Rules the code follows:

- Activities contain **no protocol logic**. They bind to `TransferService` and show what it reports.
- The service owns the sender loop and the `Reassembler`; the camera stays in `ReceiveActivity` and forwards decoded text to the service.
- All database access goes through `ContentResolver`; the SQLite helper is package-private to the provider.
- No network, disk, database or camera-decode work on the main thread.

---

## Project layout

```
app/src/main/java/com/example/qrstream/
  protocol/   Frame, FrameType, FrameCodec, Base64Url, Chunker, ParityEncoder,
              ParityRecovery, Reassembler, SendPlan, FileMeta, SimpleJson,
              LossSimulator, Crc32Util, HashUtil, BenchmarkRunner, ProtocolConstants
  service/    TransferService, TransferBinder, TransferState (Parcelable), SendRequest
  receiver/   TransferStatusReceiver, BatteryLowReceiver
  provider/   TransferProvider, TransferContract, TransferDbHelper
  ui/         Login/Home/Send/Receive/History/Details/Benchmark activities,
              ChunkGridView, SquareImageView, TransferCursorAdapter, StatsPresenter
  sync/       SyncWorker, FirestoreRepository
  util/       AppPrefs, AuthHelper, CrashReporter, FilePickerHelper, FileStore,
              Formatters, PermissionHelper, QrRenderer
app/src/test/                 JUnit 4 (pure Java)
app/src/androidTest/          provider instrumented test
docs/                         report outline, demo script, camera checklists
```

---

## Frame protocol

Every QR code carries one frame: bytes, then Base64 URL-safe without padding. All integers are big-endian.

| Field | Size | Meaning |
|---|---|---|
| magic | 2 | `0x5153` ("QS") |
| version | 1 | `1` |
| type | 1 | `0` META, `1` DATA, `2` PARITY |
| sessionId | 4 | random per transfer |
| index | 2 | DATA: chunk index. PARITY: group index. META: 0 |
| total | 2 | number of DATA chunks |
| payloadLength | 2 | N |
| payload | N | see below |
| crc32 | 4 | over every preceding byte |

- **META payload**: UTF-8 JSON `{fileName, fileSize, mimeType, chunkSize, totalChunks, groupSize, sha256}` (`groupSize` 0 means no parity).
- **DATA payload**: raw chunk bytes; the last chunk may be shorter.
- **PARITY payload**: XOR of all DATA chunks of the group, shorter chunks zero-padded to `chunkSize`.

**Broadcast loop**: `META, [d0 d1 d2 d3 p0], [d4 d5 d6 d7 p1], ...`, then it wraps around. META is repeated after every 10 other frames so a late receiver can join. Defaults: chunk 400 B, K = 4, 4 fps. At most 200 KB per file.

**Receiver rules**: ignore wrong magic, bad CRC, wrong session (after META locks it), out-of-range index and wrong payload size; duplicates never change state; frames that arrive before META are buffered and replayed; after every accepted frame the affected group is checked and a single missing chunk is rebuilt from parity; on completion the file is trimmed to `fileSize` and checked against the SHA-256 in META.

---

## Algorithms and complexity

N chunks, chunk size C, group size K, file size n = N x C.

| Component | Time | Space |
|---|---|---|
| `Chunker.split` | O(n) | O(n) |
| `ParityEncoder.encodeAll` | O(n) | O(n / K) |
| `ParityRecovery.recover` (one chunk) | O(K x C) | O(C) |
| `Reassembler.accept` (per frame) | O(1) bookkeeping + O(K) group check; O(K x C) only when a chunk is rebuilt | O(n) |
| Whole receive (all recoveries + assembly + SHA-256) | O(n) | O(n) |

Each group is rebuilt at most once, so total recovery work over a transfer is O(n). Javadoc in the source repeats and justifies these bounds.

**Why XOR parity recovers exactly one chunk per group:** the parity is `p = c0 xor c1 xor ... xor c(K-1)`. If exactly one `ci` is missing, `ci = p xor (all other chunks)`. With two missing chunks there are two unknowns and one equation, so nothing can be recovered (the group waits for the next loop pass).

---

## Build and run

Requirements: Android Studio Koala or newer, JDK 17, an Android 7.0+ (API 24) phone. Two phones are needed for a real transfer (one sends, one receives); an emulator can run the Send screen.

1. Open the `QRStream` folder in Android Studio and let Gradle sync. The wrapper pins Gradle 8.9; the Android Gradle Plugin is 8.5.2.
2. **Firebase config.** The repository contains a clearly fake `app/google-services.json` so the project syncs. Replace it with your own (see [Firebase setup](#firebase-setup)). Without a real file the app still works fully offline: choose *Continue offline*.
3. Run the `app` configuration on a device (a real camera is needed for Receive; emulators can send).
4. Command line: `./gradlew assembleDebug` (needs the Android SDK; set `sdk.dir` in `local.properties`).

---

## Firebase setup

1. Go to the [Firebase console](https://console.firebase.google.com), create a project (Analytics optional).
2. **Add an Android app** with package name `com.example.qrstream`. Download `google-services.json` and put it in `app/`, replacing the placeholder.
3. **Authentication** > Sign-in method > enable **Email/Password**.
4. **Firestore Database** > Create database (production mode) > pick a region. Then **Rules**:

   ```
   rules_version = '2';
   service cloud.firestore {
     match /databases/{database}/documents {
       match /users/{uid}/transfers/{doc} {
         allow read, write: if request.auth != null && request.auth.uid == uid;
       }
     }
   }
   ```
5. **Crashlytics** > Enable. Crash and handled-exception reports (for example barcode decode failures) appear after the next app run. Release builds upload mapping files; debug builds disable that.
6. Sync data layout: `users/{uid}/transfers/{createdAt}_{direction}_{hashPrefix}` with `file_name, file_size, direction, chunk_count, recovered_count, status, sha256, created_at, synced_at`. **File contents and local paths are never uploaded.**

Do not commit a real `google-services.json` to a public repository (see `.gitignore`).

---

## Tests

### JUnit (pure Java, no device)

```
./gradlew testDebugUnitTest
./gradlew testDebugUnitTest --tests "*BenchmarkTest" -i     # prints the results table
```

Without Android Studio you can run the pure-Java tests directly (needs only a JDK and the JUnit and Hamcrest jars):

```
./scripts/run_protocol_tests.sh /path/to/junit-4.13.2.jar /path/to/hamcrest-core-1.3.jar
```

| Test class | Covers |
|---|---|
| `FrameCodecTest` | round trip, byte layout, every single-bit flip rejected, wrong magic/version/type, truncated, Base64url vectors, META JSON |
| `ChunkerTest` | 0 bytes, 1 byte, exact multiple, multiple + 1, determinism |
| `ParityRecoveryTest` | one loss per group recovered, two losses in one group *not* recovered and reported, parity frame lost, short last group, reordered parity |
| `ReassemblerTest` | out of order, duplicates, late META, wrong session, hash mismatch, malicious META, empty file, reset, loop order, META repeat, loop-length estimate |
| `LossSimulatorTest` | same seed gives same drops, different seeds differ, rate close to target |
| `EndToEndTest` | random 50 KB file, 10 percent seeded loss, K = 4, byte-identical output; maximum-size file |
| `BenchmarkTest` | prints the table and checks sanity |
| `FormattersTest` | sizes, durations, hostile file-name sanitising |

### Instrumented and manual

- `TransferProviderInstrumentedTest`: `./gradlew connectedDebugAndroidTest` (device or emulator).
- Camera flow: see [`docs/CAMERA_TEST_CHECKLISTS.md`](docs/CAMERA_TEST_CHECKLISTS.md) (two checklists).

---

## Benchmark results

20 KB random file, 400-byte chunks, 100 seeded trials per cell (trial t uses the same file and the same loss seed for every row). Reproduce with `BenchmarkTest` or the in-app Benchmark screen.

**Table 1: single-pass success rate (%)**: the receiver sees the loop exactly once.

| Setting (frames per pass overhead) | 0% loss | 5% | 10% | 20% |
|---|---|---|---|---|
| No parity (+12%) | 100.0 | 9.0 | 0.0 | 0.0 |
| K = 8 (+25%) | 100.0 | 69.0 | 19.0 | 3.0 |
| K = 4 (+38%) | 100.0 | 73.0 | 27.0 | 1.0 |
| K = 2 (+65%) | 100.0 | 87.0 | 45.0 | 4.0 |

**Table 2: mean frames shown until the receiver completes** (the sender keeps looping, as in the app). Lower is faster.

| Setting | 0% loss | 5% | 10% | 20% |
|---|---|---|---|---|
| No parity | 58.0 | 101.0 | 122.9 | 183.4 |
| K = 8 | 64.0 | 73.4 | 96.1 | 123.7 |
| K = 4 | 71.0 | 81.3 | 102.7 | 137.5 |
| K = 2 | 85.0 | 89.8 | 108.6 | 154.0 |

Reading the tables: parity makes a **single pass** far more likely to succeed, and K = 2 is best for that (87 percent against 9 percent at 5 percent loss). Because the sender loops, the practical metric is **time to completion**, and there K = 8 wins at every non-zero loss rate: smaller groups repair more but cost more frames per pass. With no loss, parity is pure overhead (58 against 64 to 85 frames). A larger K is the better default when the channel is mostly clean; the default of K = 4 is a middle ground. "Frames to complete" counts frames shown, so wall-clock time is that number divided by the frame rate (for example 71 frames at 4 fps is about 18 seconds). The table models loss only; how the real loss rate depends on frame rate and camera is something to measure on devices.

---

## Android component map

| Requirement | Where |
|---|---|
| **Service** | `TransferService`: foreground service (`dataSync`), bound through `TransferBinder`, sender loop, owns the `Reassembler`, progress notification, Stop/Resume actions |
| **Broadcast Receiver** | `TransferStatusReceiver` (dynamic, `setPackage` broadcasts, `RECEIVER_NOT_EXPORTED`), `BatteryLowReceiver` (dynamic, pauses transfer, "Resume" notification) |
| **Content Provider** | `TransferProvider` (authority `com.example.qrstream.provider`, dir and item URIs, `UriMatcher`, change notifications) and a `FileProvider` (`com.example.qrstream.fileprovider`) |
| **ListView** | `HistoryActivity` with `TransferCursorAdapter` (ViewHolder), filter chips, empty state, long-press delete, `ContentObserver` refresh |
| **Firebase** | Auth (email/password + offline), Firestore (`users/{uid}/transfers`), Crashlytics (decode failures, unexpected exceptions), `SyncWorker` |

---

## Known limitations

- **Android code not yet built or run.** See the note at the top. The pure-Java parts are tested; everything that needs the Android SDK (UI, service, provider, CameraX, Firebase) was reviewed and statically cross-checked only. First sync may need small fixes.
- **Throughput is low by design.** At 400 bytes per frame and 4 fps the payload rate is about 1.6 KB/s before overhead; a 20 KB file takes roughly 15 to 20 seconds per pass. Camera, lighting and screen glare dominate real-world reliability.
- **One-way channel.** The sender cannot know that the receiver finished; the user presses Stop. A "sent" history entry means the sender completed at least one full pass.
- **One parity chunk per group.** Two losses in the same group are not repaired until a later pass delivers one of them.
- **Memory-bound by design**: files up to 200 KB are held in memory.
- **No encryption.** Anyone who can see the screen can read the file. The CRC detects accidental corruption only, SHA-256 detects a wrong file, neither authenticates the sender.
- **Notification permission** (Android 13+) can be denied; transfers still work and the UI shows progress.
- Received files are stored in the app's private folder; sent files are not copied.
- `google-services.json` is a placeholder; cloud features need your own Firebase project.
