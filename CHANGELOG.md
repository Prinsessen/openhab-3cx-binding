# Changelog

All notable changes to the 3CX PBX Binding are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [1.1.0] - 2026-11-04

### Fixed
- **No more 15-minute outages after a network interruption.** A firewall restart could leave a reply whose body
  never arrived. The request timeout only covers the response headers, so two polls waited for about 15 minutes
  (until the kernel gave the dead connection up), and every other poll failed with `HTTP connect timed out` on
  the same client. The bridge stayed OFFLINE for 16 minutes, although the network was back after two.
- Every exchange now has a 15 s deadline covering connect, headers and body. When it passes, the exchange is
  cancelled and the HTTP client is replaced (at most every 10 s). The old client is shut down, which also frees
  the polling threads.
- The HTTP client is shut down when the bridge is disposed.

### Changed
- While polls keep failing, a warning every 150 polls (5 min at the default interval), instead of one at the
  4th failure and then silence.
- `alarmCall` plays `{alertType}_alert.wav` from `sipAudioPath`, so a new alert type needs only a new WAV file.
  If that file is missing, it falls back to `towing_alert.wav`.

### Added
- First JUnit test, `Pbx3cxApiClientTimeoutTest`: a TLS server that logs in and then stalls a reply after its
  headers. On 1.0.0 the call hangs past 40 s. On 1.1.0 it gives up within the deadline, and the next call is
  answered.

## [1.0.0] - 2026-03-03

First release: call monitoring, extension presence, ring groups and queues, missed and total call counters,
call history, caller ID resolution via `CallHistoryView`, live HTML dashboard, SIP alarm calls via baresip,
trunk monitoring, webhook receiver.
