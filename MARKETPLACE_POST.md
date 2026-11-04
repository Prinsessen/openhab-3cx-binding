| Key | Value |
|----|----|
| uid | pbx3cx |
| type | binding |
| author | Nanna Agesen |
| version range | \[5.0.0;6.0.0) |
| download | [org.openhab.binding.pbx3cx-1.1.0.jar](https://github.com/Prinsessen/openhab-3cx-binding/releases/download/v1.1.0/org.openhab.binding.pbx3cx-1.1.0.jar) |

# 3CX PBX Binding

A comprehensive openHAB binding for **3CX PBX V20** that provides real-time call monitoring, extension presence tracking, ring group/queue management, SIP alarm calls, and a live HTML dashboard — all via the 3CX xAPI (REST + JWT).

## What is new in 1.1.0

* **Recovers on its own after a network outage.** A firewall restart could leave a reply whose body never arrived. The request timeout only covers the response headers, so the bridge stayed OFFLINE for 16 minutes although the network was back after two. Every exchange now has a **15 s deadline covering connect, headers and body**. When it passes, the HTTP client is replaced and the old one shut down, which frees the polling threads.
* **A warning every 5 minutes** during a long outage (every 150 polls at the default interval), instead of one at the 4th failure and then silence.
* **Alert sounds by type:** `alarmCall` plays `{alertType}_alert.wav` from `sipAudioPath`, falling back to `towing_alert.wav`, so a new alert type needs only a new WAV file.
* **First JUnit test** (`Pbx3cxApiClientTimeoutTest`): a TLS server that stalls a reply after its headers. On 1.0.0 the call hangs past 40 s. On 1.1.0 it gives up within the deadline.

## Features

* **Real-time call monitoring** — Active calls with caller/callee resolution, direction detection, duration tracking
* **Extension presence** — Online/offline/away/DND status for every extension with auto-discovery
* **Ring groups & queues** — Member lists, strategy display, waiting caller counts
* **Missed & total call tracking** — Automatic daily counters with midnight reset and file-based persistence
* **Call history** — Recent calls and missed calls stored as JSON (configurable size), survives restarts
* **Caller ID resolution** — Queries `CallHistoryView` OData endpoint for real external caller number (3CX ActiveCalls sometimes shows DID instead of caller)
* **Live HTML dashboard** — Beautiful real-time dashboard with 2-second refresh, embeddable in sitemaps via Webview
* **SIP alarm calls** — Native baresip SIP client for outbound calls with WAV audio playback + DTMF confirmation
* **Alert types** — Towing/unplug alerts (or any type with its own WAV file) with multi-number sequential retry
* **Trunk monitoring** — SIP trunk online/offline status
* **VoiceMail detection** — Recognizes voicemail pickups (not counted as answered)
* **Webhook receiver** — Optional 3CX CRM/CFD integration webhook listener
* **Self-signed SSL** — Works with 3CX self-signed certificates
* **Self-healing connection** — A 15 s deadline on every request and a fresh HTTP client after a timeout, so a dead connection never holds the bridge offline

## Requirements

* 3CX PBX V20 with xAPI enabled
* 3CX user account with admin privileges
* Network access from openHAB to 3CX webclient port (default 5001)
* `baresip` installed for SIP/alarm call features (optional)

## Supported Things

| Thing Type | Description |
|----|----|
| `server` (Bridge) | Connection to 3CX PBX server via xAPI |
| `extension` | Individual phone extension |
| `ringgroup` | Ring group with member tracking |
| `queue` | Call queue with agent and waiting caller tracking |

## Channels

### Bridge Channels

| Channel | Type | Description |
|----|----|----|
| `callState` | String | Current call state: idle, ringing, active, outgoing |
| `callerNumber` | String | Phone number of the current/last caller |
| `callerName` | String | Display name of the current/last caller |
| `calledNumber` | String | The destination number being called |
| `callDirection` | String | Call direction: inbound, outbound, internal |
| `callDuration` | Number | Duration of the last call in seconds |
| `callAgent` | String | Extension handling the call |
| `callTimestamp` | DateTime | Timestamp of the current/last call event |
| `missedCount` | Number | Missed calls since midnight (auto-resets) |
| `totalCalls` | Number | Total calls today since midnight (auto-resets) |
| `lastMissedCaller` | String | Caller info for the last missed call |
| `lastMissedTime` | DateTime | Timestamp of the last missed call |
| `activeCalls` | Number | Number of currently active calls |
| `activeCallsJson` | String | JSON array of active calls |
| `recentCallsJson` | String | JSON array of recent completed calls |
| `recentMissedCallsJson` | String | JSON array of recent missed calls |
| `trunkStatus` | String | SIP trunk status summary |
| `systemStatus` | String | PBX connection status: ONLINE, OFFLINE |
| `recordingUrl` | String | URL to the last call recording |
| `makeCall` | String | Send command to initiate a SIP call |
| `alarmCall` | String | Trigger alarm call with audio + DTMF confirmation |
| `alarmCallResult` | String | Result: CONFIRMED, NO_ANSWER, FAILED |

### Extension Channels

| Channel | Type | Description |
|----|----|----|
| `status` | String | idle, ringing, dialing, talking, offline |
| `presence` | String | Available, Away, DND, Lunch, etc. |
| `name` | String | Display name of the extension |
| `registered` | Switch | Whether the extension is registered |

### Ring Group Channels

| Channel | Type | Description |
|----|----|----|
| `rgName` | String | Ring group display name |
| `rgStrategy` | String | Hunt, RingAll, Paging, etc. |
| `rgMembers` | String | Comma-separated list of members |
| `rgMemberCount` | Number | Number of members in the group |
| `rgRegistered` | Switch | Whether the ring group is registered |

### Queue Channels

| Channel | Type | Description |
|----|----|----|
| `qName` | String | Queue display name |
| `qStrategy` | String | RingAll, Hunt, LongestWaiting, etc. |
| `qAgents` | String | Comma-separated list of agents |
| `qAgentCount` | Number | Number of agents in the queue |
| `qWaitingCallers` | Number | Callers currently waiting |
| `qRegistered` | Switch | Whether the queue is registered |

## Quick Start

### pbx3cx.things

```java
Bridge pbx3cx:server:main "3CX PBX" [
    hostname="your-3cx-server.example.com",
    port=5001,
    username="600",
    password="your-xapi-password",
    pollInterval=2,
    presenceInterval=30,
    trunkInterval=60,
    verifySsl=false,
    recentCallsMax=20,
    recentMissedCallsMax=20
] {
    Thing extension ext200 "200 John Doe"    [ extensionNumber="200" ]
    Thing extension ext201 "201 Jane Smith"  [ extensionNumber="201" ]
    Thing ringgroup rg800 "800 Sales"        [ ringGroupNumber="800" ]
    Thing queue q900 "900 Main Queue"        [ queueNumber="900" ]
}
```

### pbx3cx.items

```java
Group gPBX "3CX Telefon" <phone>

String PBX_Call_State      "Call Status [%s]"    <phone>    (gPBX) { channel="pbx3cx:server:main:callState" }
String PBX_Caller_Number   "Caller [%s]"         <phone>    (gPBX) { channel="pbx3cx:server:main:callerNumber" }
String PBX_Caller_Name     "Caller Name [%s]"    <man_3>    (gPBX) { channel="pbx3cx:server:main:callerName" }
String PBX_Call_Direction   "Direction [%s]"       <flow>     (gPBX) { channel="pbx3cx:server:main:callDirection" }
Number PBX_Call_Duration    "Duration [%d sec]"    <time>     (gPBX) { channel="pbx3cx:server:main:callDuration" }
Number PBX_Missed_Count     "Missed Today [%d]"   <returnpipe> (gPBX) { channel="pbx3cx:server:main:missedCount" }
Number PBX_Total_Calls      "Calls Today [%d]"    <phone>    (gPBX) { channel="pbx3cx:server:main:totalCalls" }
String PBX_System_Status    "PBX Status [%s]"     <network>  (gPBX) { channel="pbx3cx:server:main:systemStatus" }
```

### Rules Examples

```java
rule "PBX Missed Call Notification"
when
    Item PBX_Missed_Count changed
then
    val caller = PBX_Last_Missed_Caller.state.toString
    logInfo("pbx", "Missed call from: {}", caller)
end

rule "Flash Light for Specific Caller"
when
    Item PBX_Caller_Number changed
then
    if (PBX_Caller_Number.state.toString.contains("12345678")) {
        logInfo("pbx", "VIP caller detected!")
        My_Light_Color.sendCommand("0,100,100")  // Flash red
    }
end

rule "Vehicle Towing Alert"
when
    Item Vehicle_GPS_Towing received update ON
then
    PBX_Alarm_Call.sendCommand("towing")  // Calls sipAlertExtensions with audio + DTMF
end
```

### Live Dashboard

Copy `3cx_dashboard.html` to `/etc/openhab/html/` and add to your sitemap:

```java
Webview url="/static/3cx_dashboard.html" height=22
```

## SIP / Alarm Calls

The binding includes a native SIP client powered by [baresip](https://github.com/baresip/baresip) for outbound calls directly from openHAB:

| Channel | Description |
|----|----|
| `makeCall` | Dial a number — rings for `makeCallDuration` seconds |
| `alarmCall` | Send alert type (`towing`, `unplug`, `generic`, or your own) — calls all `sipAlertExtensions` with `{type}_alert.wav` + DTMF `1` confirmation |
| `alarmCallResult` | `CONFIRMED` (DTMF received), `NO_ANSWER`, or `FAILED` |

Requires `sudo apt install baresip` and SIP credentials configured on the bridge. Put the WAV files in `sipAudioPath` (default `/etc/openhab/sounds/alerts`); a type without its own file plays `towing_alert.wav`.

## Resources

* **Download JAR (v1.1.0):** [org.openhab.binding.pbx3cx-1.1.0.jar](https://github.com/Prinsessen/openhab-3cx-binding/releases/download/v1.1.0/org.openhab.binding.pbx3cx-1.1.0.jar) (77.5 KB)
* **Install:** copy the JAR into the openHAB `addons` folder (`/usr/share/openhab/addons/` on apt installs, `$OPENHAB_HOME/addons/` otherwise); it is picked up without a restart. Remove an older 3CX JAR first.
* **Source Code:** [github.com/Prinsessen/openhab-3cx-binding](https://github.com/Prinsessen/openhab-3cx-binding)
* **Full Documentation:** [README.md](https://github.com/Prinsessen/openhab-3cx-binding/blob/main/README.md)
* **Changelog:** [CHANGELOG.md](https://github.com/Prinsessen/openhab-3cx-binding/blob/main/CHANGELOG.md)
* **Live Dashboard:** [3cx_dashboard.html](https://github.com/Prinsessen/openhab-3cx-binding/blob/main/3cx_dashboard.html)
* **License:** EPL-2.0
* **Maintainer:** Nanna Agesen (GitHub: Prinsessen)
