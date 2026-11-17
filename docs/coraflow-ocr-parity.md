# CoraFlow OCR parity

JSON workflows use `CoraFlowOcr`, not the legacy Lua ML Kit `ScriptOcr` bridge.
The engine is Tesseract.js/core 7.0.0, OEM LSTM_ONLY. The offline eng,
chi_sim and vie models are byte-identical copies of the CoraFlow OCR cache.
`app/src/main/assets/coraflow-ocr/source-hashes.json` records the bundled hashes.

The bridge runs an offscreen WebView worker, with assets served only on a random
loopback port and random path. It uses no model CDN and shows no WebView panel.
It supports English-only numbers, English-first mixed Chinese/Vietnamese text,
single-line/single-block segmentation, the same numeric whitelist, Lanczos3
14-bit resize, and amber/grayscale/dark/light variants for confidence below 85.
Confidence is the Tesseract document confidence; it is never synthesized from
ML Kit line scores or boosted to pass workflow thresholds. Actual word boxes
are available for text click matching.

Validation on the connected emulator used the production bridge in a separate
test APK (`tools/ocr-parity-device`) and the real CoraFlow Electron IPC OCR handler
(`tools/verify-coraflow-ocr-replay.cjs`). All 13 pairs matched in text AND confidence:
synthetic numbers 16, 200/200, 1; Chinese recovery text; Vietnamese sell text;
actual game weight 199/200, free slots, red stack 31, bag title, Chon button;
low-contrast number, blank crop and filtered yellow equipment-power text. Blank input remained confidence 0. The bag
title crop was misread by BOTH engines: agreement does not prove correctness.

This verifies those OCR samples, not an error-free whole workflow or 24-hour run.
Template matching, welcome announcements, page-state recovery and node execution
remain separate from OCR. The screenshot's auto-battle-button failure is a
template/page-state failure and cannot be fixed by changing OCR alone.

To replay, generate cases.json and PNG crops under work/ocr-parity-fixtures,
run the desktop script with CoraFlow's Electron executable, install the separate
parity test APK, push the same fixtures into its external files directory, and
compare results.json to desktop-results.json. Preserve model and input hashes
when adding fixtures. No production game actions are sent by the parity test.
