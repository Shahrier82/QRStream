# Project report outline: QR Stream

Fill the bracketed parts with your own measurements. Numbers marked (measured) come from the repository's seeded benchmark and can be reproduced exactly.

## Title page
Project title, course (Advanced Programming Lab), name, ID, supervisor, date.

## 1. Abstract (150 to 200 words)
- Problem: moving a small file between two phones when no network, Bluetooth or Wi-Fi Direct is available or allowed.
- Approach: optical, one-way channel. The sender shows a looping sequence of QR codes, the receiver scans them.
- Key ideas: numbered chunks, per-frame CRC-32, XOR parity groups for loss recovery, SHA-256 for end-to-end integrity.
- Result: [e.g. a 20 KB file transfers in about 18 s per pass at 4 fps]; with K = 4 parity a single pass succeeds in 73 percent of 5-percent-loss trials against 9 percent without parity (measured).

## 2. Objectives
1. Design a binary frame format that survives an unreliable visual channel.
2. Implement loss recovery without a back channel.
3. Verify integrity end to end.
4. Build the app with Android Service, Broadcast Receiver, Content Provider, ListView and Firebase.
5. Evaluate recovery quantitatively with reproducible simulation.

## 3. Background and related work
- QR codes: capacity (version 40: 2953 bytes at level L, 2331 at M), error correction inside one code (Reed-Solomon) versus loss of whole frames.
- Erasure codes: XOR parity (RAID-5 style) and why it repairs one loss per group; fountain codes and Reed-Solomon across frames as alternatives.
- Existing apps that stream data through QR codes [cite 2 or 3].

## 4. Methodology
### 4.1 System overview
Architecture diagram (use the ASCII diagram in the README, redrawn).
### 4.2 Frame protocol
Table of fields, big-endian layout, Base64url, META JSON, why 800 bytes is the maximum chunk (1091 characters, fits QR level M).
### 4.3 Sender
Chunking, grouping, parity, loop order, META repetition every 10 frames, frame-rate scheduler, rendering with ZXing, loss simulation with a fixed seed.
### 4.4 Receiver
CameraX plus ML Kit on an analyzer thread, consecutive-duplicate suppression, Reassembler state machine (buffer before META, lock session, accept, recover, finish), SHA-256 check.
### 4.5 Android components
Service lifecycle (start, bind, foreground, battery pause), receivers, provider and contract, ListView with CursorAdapter, FileProvider, WorkManager and Firestore sync, offline-first design.
### 4.6 Experimental design
File size 20 KB, chunk 400 B, 100 seeded trials per cell, K in {none, 8, 4, 2}, loss in {0, 5, 10, 20} percent. Two metrics: single-pass success rate, mean frames until complete. Same file and same seed for every configuration in a trial.

## 5. Algorithm analysis
| Component | Time | Space | Notes |
|---|---|---|---|
| Chunker.split | O(n) | O(n) | one copy per byte |
| ParityEncoder.encodeAll | O(n) | O(n/K) | XOR each byte once |
| ParityRecovery.recover | O(K x C) | O(C) | one chunk |
| Reassembler.accept | O(1) + O(K) check | O(n) | rebuild O(K x C) at most once per group |
| Whole receive | O(n) | O(n) | plus SHA-256 |

Correctness arguments: parity identity, why two losses in one group are unrecoverable, idempotence of accept, why CRC rejects every single-bit error (tested exhaustively on one frame), probability model:
- Single pass with no parity: P(success) = (1 - p)^F for F frames.
- With a parity group of K data frames plus one parity frame, a group is complete after one pass if either no data frame is lost, (1-p)^K, or exactly one data frame is lost and the parity frame arrived, K p (1-p)^K. So P(group) = (1-p)^K (1 + K p), and a file with G groups succeeds in a single pass with probability about P(group)^G (this ignores META loss and treats the short last group as full).
- Worked check for the 20 KB benchmark file (52 chunks): K = 4 (G = 13) predicts 74.3 / 33.1 / 1.9 percent at 5 / 10 / 20 percent loss, against 73 / 27 / 1 measured; K = 2 (G = 26) predicts 82.8 / 47.8 / 5.8 against 87 / 45 / 4 measured; K = 8 (G = 7, last group shorter) predicts 59.6 / 16.8 / 0.3 against 69 / 19 / 3 measured. The model tracks the simulation; the remaining differences come from the short last group and from 100-trial sampling noise (about 4 to 5 percentage points).
- Overhead per pass = (parity + repeated META) / data frames: 12 / 25 / 38 / 65 percent for none / 8 / 4 / 2 (measured).

## 6. Results (measured, reproducible)
Table 1: single-pass success rate (%).

| Setting | 0% | 5% | 10% | 20% |
|---|---|---|---|---|
| No parity | 100.0 | 9.0 | 0.0 | 0.0 |
| K = 8 | 100.0 | 69.0 | 19.0 | 3.0 |
| K = 4 | 100.0 | 73.0 | 27.0 | 1.0 |
| K = 2 | 100.0 | 87.0 | 45.0 | 4.0 |

Table 2: mean frames shown until complete (sender loops).

| Setting | 0% | 5% | 10% | 20% |
|---|---|---|---|---|
| No parity | 58.0 | 101.0 | 122.9 | 183.4 |
| K = 8 | 64.0 | 73.4 | 96.1 | 123.7 |
| K = 4 | 71.0 | 81.3 | 102.7 | 137.5 |
| K = 2 | 85.0 | 89.8 | 108.6 | 154.0 |

Add: a bar chart of both tables, device test results from `docs/CAMERA_TEST_CHECKLISTS.md` (real transfers: file, size, K, fps, time, success), screenshots of Send, Receive (with blue cells), History and Details.

Discussion points:
- Parity helps most in a single pass; time to completion favours larger K because the loop repairs itself on later passes.
- At 0 percent loss parity is pure overhead.
- At 20 percent loss single-pass success is near zero for every K: relying on repeated passes is the real recovery mechanism.
- Threats to validity: simulated independent loss, real loss is bursty (glare, motion); one file size; ideal decoder.

## 7. Conclusion
Summarise what was built, the measured benefit of parity, and the trade-off between K and time.

## 8. Limitations
One-way channel, 200 KB limit, one repair per group, no encryption, simulated rather than measured camera loss, manual stop.

## 9. Future work
- Reed-Solomon or fountain codes (repair several losses, no waiting for a particular frame).
- Adaptive K or frame rate driven by a receiver "ack" QR shown back to the sender camera.
- Colour QR codes or larger frames for higher throughput.
- Interleaved parity groups to withstand burst loss.
- Encryption and sender authentication; resumable multi-file transfer; iOS client.

## References
QR code specification (ISO/IEC 18004), CRC-32 (IEEE 802.3), SHA-256 (FIPS 180-4), Patterson et al. RAID (1988), CameraX, ML Kit, ZXing and Firebase documentation.

## Appendices
A. Frame layout byte table. B. Source file list. C. Test list (79 JUnit tests) and how to run them. D. Firebase setup. E. Full benchmark output.
