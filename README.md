# iTantra ( CoreSense)
### Offline Speech-to-Text & Text-to-Speech Phone-to-Phone Mesh Radio

iTantra is an open-source, fully offline Android mesh radio application designed for disaster response, emergency tactical squads, and zero-connectivity field operations.

It converts voice to text on the sender's phone, sends a micro-packet (~40–80 bytes Protocol Buffers) over an offline local phone-to-phone link (Wi-Fi Direct / Bluetooth RFCOMM), and speaks it via offline TTS on the receiver's phone.


##  HARD RULES COMPLIANCE
1. **Zero Cloud Dependencies**: Manifest has **NO INTERNET PERMISSION** (`android.permission.INTERNET` is completely removed).
2. **Zero Simulated Metrics**: All WER, RTF, RAM PSS, CPU %, battery drain rate, RTT, and compression ratios are measured directly from hardware, Android APIs, and `/proc/self/stat`.
3. **No Fake Output**: If an offline model is missing or TTS voice is unavailable, iTantra reports honest errors. No hardcoded fallback transcripts or synthetic responses.
4. **Target Devices**: Designed to run comfortably on 3–4 GB RAM phones by loading only one language model at a time.
5. **Isolated DEMO Screen**: Diagnostics and loopback lab is clearly marked DEMO, isolated from production code, and off by default.
