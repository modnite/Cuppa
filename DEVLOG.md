# Development Log

Informal running notes on notable debugging sessions and design decisions. See CHANGELOG.md for
the user-facing summary.

These are written in my own voice, but I'm not the one writing the code. I don't have much coding
experience. Claude (Anthropic's AI) does the actual implementation and debugging. I direct it,
test everything on real hardware, and decide what Cuppa should do and how it should feel.

## 2026-09-20: A friendlier front door

Asked whether the UX could feel more modern. The Dashboard was mostly developer tooling: a libcups
query box, engine details and a line about root and Shizuku. I rebuilt it around what someone
sharing a printer wants to know. Is it on, what is it sharing and how do I connect a device. The
developer tools are still there under Advanced. Added a side rail for wide windows and cleaned up
the Jobs and Printers screens. Settings is still one long list and is next.

I could not look at any of it running. My computer's C drive has 0.2 GB free so the emulator
refuses to start, and the phone was offline. It compiles and the tests pass. That is all I can say.

## 2026-09-19: Only advertise what works

At home Windows listed my two office printers next to the two that work. They can never print
from here. Cuppa announced every saved printer all the time. Now it checks each one every 30
seconds. A network printer has to accept a connection. A USB printer has to be plugged in. One
that stops answering stays listed for five more minutes so a short blip does not make it vanish.
The Printers tab shows those as Offline. Tested at home with the Epson. The phone advertised one
of four printers and Windows found it almost instantly. The slow discovery was mostly Cuppa
announcing printers that could never answer.

## 2026-09-19: Windows and the secure connection

Windows saw Cuppa's printers once and then not again. I am on a Windows PC so I asked Windows
directly. Its own discovery service listed all four Cuppa printers every time. Discovery was
fine. Then I watched the phone log while Windows searched. One connection came in from this PC
and the server said it could not read the request. A secure connection from this PC gave the same
line. Windows opens a TLS connection first. Cuppa only spoke plain HTTP unless "Require IPPS"
was on.

Turning that on was no help. The server wrapped connections in TLS with a call that this version
of Android does not implement, so it crashed on every secure attempt. That setting had never
worked. I replaced the wrapper. The server looks at the first byte without consuming it. A secure
connection goes to Android's normal TLS layer and a plain one carries on as before. Cuppa accepts
both now. The setting only decides whether plain connections are turned away.

Windows then listed all the printers. Adding one said "That didn't work". The phone log had the
answer. Windows asked for `/printers/deskjet_2700_series` in lowercase. Cuppa's queue is named
`DeskJet_2700_series` and the lookup compared case exactly. Made it case-insensitive. The
certificate turned out not to matter for discovery, so that question can wait.

## 2026-09-19: One paper jam and then nothing worked

A sheet did not feed properly and one job died halfway through its upload. Every job after it
failed with no reply until I replugged the printer. The printer was still waiting for the rest of
the first request. The USB spec has a way to end a connection cleanly and I was not doing it.
Cuppa does now. I have not been able to test the recovery. I would need to jam the printer on
purpose.

## 2026-09-18: The DeskJet and IPP over USB

Tried my dad's HP DeskJet over USB. Every job went through with no error and nothing printed.
The USB dump explained it. The printer has three ways in. A vendor one, a plain printer one and
a third labelled IPP over USB. Cuppa was picking the wrong one and speaking a language the
printer does not understand. HP printers like this one are driverless. The right way in is the
IPP over USB interface. It carries normal web requests, so Cuppa can send the same PDF or raster
it would send to a network printer.

Building it went wrong in three boring ways. The first read failed instantly because Android
rejects reads over 16KB. Then my installs to the phone kept stalling and I blamed the phone for
waiting on a prompt when it was the adb connection name that kept dropping. Connecting by IP
address fixed it. The upload took less than a second.

Black and white printed. Color printed with colors missing. The printer may be low on ink. I am
not sure yet.

Cuppa also asks the printer how a job ended now. Raw USB writes can only say the bytes left.

Follow up the same evening. The bottom of every page was clipped. Inkjets cannot print in the last
half inch. The DeskJet says so over IPP, 12.7 mm at the bottom. Cuppa now shrinks the page to fit.
For the colors I had Cuppa read the ink levels. Both cartridges are at 20 percent with a low
warning. Cuppa sent a proper color job and the printer accepted it. I cannot prove it is the ink
but nothing on the Cuppa side looks wrong.

## 2026-09-18: Home and the Epson

Got home and tried the Epson through Cuppa from my phone. It failed. The log took one look. The
dispatcher only knew `ipp://` and `http://`. The Epson shows up on my home network as a secure
printer, so its address starts with `ipps://` and every job hit an "unsupported transport" check
and was dropped before anything was sent. At the office the Brother was plain `ipp://` so I never
saw it. Both spellings work now and secure ones connect with TLS from the first byte.

## 2026-09-18: The job that never showed up

Three copies printed and the Jobs tab had nothing for it. The job list on the server lives in
memory and starts numbering at 1 every time the app starts. The history I save to disk is keyed
by that number too. On startup I loaded the old history and marked all those numbers as already
seen. So the first new job after a restart was job 1 again and Cuppa thought it had recorded it
already. I stopped seeding the seen list from disk.

## 2026-09-18: Old Mac queues and the missing copies

Three Macs and three different results. My coworker's Mac printed but clipped the top of the
page. My first Mac failed with PostScript again. After I removed and re-added the printer on it
the print came out clean but only one copy of three.

The first two have the same cause. A Mac remembers what Cuppa told it when the printer was added.
Queues added before my fixes still carry the old margins and the old format list. Removing and
re-adding fixes them. Nothing to change in Cuppa for that.

The copies one was the Brother. It answered "ok but I ignored some attributes" and printed
once. I stopped relying on it. Cuppa already draws the raster so it now draws every copy into it.

## 2026-09-18: The Mac sent PostScript

After the resolution fix the next Mac job failed on a different error. The log said the file was
PostScript. The Brother lists raster and generic binary data as its formats and no PDF. So the
Mac guessed it was a PostScript printer and sent PostScript. Cuppa already converts PDF to raster
for the Brother when the request comes from Android. It just never told the Mac it could take
PDF. It does now.

## 2026-09-18: The Brother said document-format-error

Version 0.6.2 still printed nothing. This time Cuppa sent the job with the right label and the
Brother said OK. I asked the Brother for its own job history straight from the phone. It listed
my job with the reason `document-format-error`. So it took the file and could not read it.

The cause was one number. Cuppa's default resolution was 203 dpi, picked for the Rollo, and it
was told to every client for every printer. The Mac dutifully rendered at 203 dpi. The Brother
supports 300 and 600. Fixed by defaulting raster printers to 300.

## 2026-09-18: Mac jobs marked done that never printed

I sent 3 copies from the Mac to the Brother. Cuppa said completed. Nothing came out.

The log had the answer. The Brother replied with a document format error and Cuppa counted the
job as done anyway because the reply still contained a job number. Two bugs. One was that a job
number is not success. The other was the format. The Mac sends PWG-Raster and Cuppa only knew how
to label PDF and PostScript. Everything else went out as generic binary data and the Brother
would not take it.

I also asked whether quality and duplex settings were being used. They were not. Only the copies
count was passed on. Now quality, sides, color mode, orientation and media go through. If a
printer refuses them Cuppa retries with just the copies.

I checked it by making a PWG-Raster file in a Docker container and sending it to the phone with
CUPS's own `lp`. It went to the Brother labelled correctly and was accepted. I do not have a
real Mac here so that is the closest I can get.

## 2026-09-18: 28 copies that vanished

I sent a 28 copy PDF to one of the office Brothers from the normal Android print dialog. Nothing
printed. The Jobs tab said failed.

The log showed the job arriving and being accepted. Then the dispatcher said the spool file was
missing. The server writes each incoming document to a spool folder and nothing ever created that
folder. Every job over the network had been quietly lost since the server was first written. My
own test prints never hit it because those go straight through the app and skip the server.

Fixing it turned up two more gaps. The copies count was never read from the job so it would have
printed once. And a PDF going to a printer with no PDF support only sent page one. I wrote a
multi-page raster writer for that. I confirmed the fix on the phone by sending a three copy job to
the Rollo queue with a harmless reset command as the payload. It spooled and ran three times.

I also found the USB banner still listed audio adapters and disks. One old check let anything
with an interface through. Gone now.

The USB permission prompt on the DeX display is still unsolved. I tapped Grant from the DeX
window and looked at where the dialog landed. It was on the phone screen again. The system
raises it itself and the app has no say in which display gets it.

The macOS driver question is closed. I added the mDNS keys Apple looks at, mainly `URF=none`. On
the Mac the printers show up, add with no driver step and print. The fix for that was in the
mDNS record and not in the IPP replies where I had spent most of the day looking.

## 2026-09-18: Getting the Rollo to print and fixing macOS

Most of this day went into one printer that would not print. Every test said success. The USB
transfer finished with no error. Nothing came out.

I tried the obvious things first. TSPL and ZPL both did nothing. Then I asked the printer who it
was. Its IEEE 1284 ID said `CMD:XPP,XL`. That ruled out plain TSPL and plain ZPL as the whole
story. What actually broke the case open was the old RolloPrint project sitting on my drive. It
had a reset command (`~@`) at the front of every job and it sent data in small chunks with a short
pause between each. It also had a status query (`<ESC>!?`) I had reverse engineered from Wireshark
captures. I ported all three. The printer stalled once because a previous attempt had filled its
buffer. After a power cycle it answered "ready" and the whole job went through in two seconds. It
printed. It ran off the right edge at first. A 4 inch label is 812 dots wide but the head is 832.
Rendering at 812 and centering fixed that. Then the text ran off the edge. That one was my test
page using font sizes laid out for Letter paper.

Along the way I found that Cuppa asked for USB permission for every device I plugged in. Audio
adapters and game controllers included. The manifest filter even had a catch-all in it. It only
asks about printers now.

The macOS problem turned out to be one character. The IPP server returned URIs with spaces in
them because printer names have spaces. CUPS treats that as a bad request and drops the whole
reply. macOS then falls back to "Choose a Driver". I ran CUPS's own `ipptool` and `driverless`
from a Docker container against the phone. That gave me a real yes or no for the first time
instead of guessing from screenshots. Fixing the URIs and adding the attributes IPP Everywhere
expects made `driverless` produce a working PPD for every queue.

Two smaller things came out of the office. A second printer of the same model was hiding the
first because discovery keyed printers by name. Two Brothers only differ by a "(2)". It keys on
the mDNS UUID now. And the Available list was showing Cuppa's own shared queues back to me.

I also found out the debug build and the release build both run on the phone. They fight over
port 631. The debug one gets 8631. I spent a while testing the wrong app because of that.

## 2026-09-18: First real printer tests, and a crash from having two printers

Took Cuppa to work to test against real hardware for the first time. The Brother MFC-L2717DW
laser printer worked over the network on the first try. Black and white only, we're out of color
toner, but the PDF to PWG-Raster path handled it fine and the job spooled clean.

The Rollo USB thermal printer didn't print at all, despite Cuppa reporting a clean USB transfer
every time. Turned out the connected unit's VID/PID doesn't match either of the two Rollo IDs
already known to the driver database, so it never got recognized as a Rollo. I got it printing
later that day. That story is in the entry above.

Testing this away from home meant no wireless ADB, since the phone and my desktop weren't on the
same network. Added the phone as a peer on my home WireGuard VPN, which fixed that instantly. No
extra setup needed since the desktop was already reachable from the VPN through my home router.

Once I could reach the phone again, adding the Rollo as a second saved printer immediately crashed
the Printers tab. Root cause: Cuppa hosts every printer's IPP queue on the same shared port, so
once there are two or more, their self-advertised mDNS entries all resolve to the same host:port.
The discovered-printer ID only encoded host:port, not the actual queue path, so two different
printers collapsed into one duplicate list key and Compose crashed. Fixed by keying off the full
URI instead.

Also learned USB permission prompts show up on the phone's own screen while docked to an external
display. Runtime permission prompts like notifications do follow the app onto the DeX display. The
USB one is raised by the system itself with no way to tell it which screen to use. I have not
found a way around that yet.

## 2026-09-17: Cutting the APK in half and a cleanup pass

Went looking for clutter and half-finished features after getting the icon and screenshots
sorted. Codebase held up well. No stubs, no dead settings, nothing wired to fake data.

The one real find was the release APK shipping all four native ABIs (arm64-v8a, armeabi-v7a,
x86, x86_64) in every build. Real phones only ever need the first two. x86/x86_64 exist purely
for the emulator. Tried the obvious fix first, setting `ndk.abiFilters` on the release build
type. Built clean, changed nothing. Confirmed with a full clean rebuild that all four ABIs still
came out the other end. Per-buildType abiFilters just isn't honored for CMake-based
externalNativeBuild in this AGP version. Ended up deciding the ABI list from the invoked task
name instead. Release gets two ABIs, everything else still gets four. APK went from about 20.7MB
to 11.2MB.

Also cleared out an unused `lifecycle-service` dependency (the print service never actually
extended `LifecycleService`), 8 dead string resources nothing referenced, and a "Bundled" label
in Driver Management that was a `SuggestionChip` with an empty `onClick`, so it looked pressable
and did nothing. Replaced it with a plain label.

Left Bouncy Castle alone. It's only there for self-signed TLS cert generation, and swapping it
for Android's own AndroidKeyStore-backed cert generation would drop the SAN entries I added
specifically because some Windows IPP clients check them. Not worth risking IPPS compatibility
to save a few MB.

## 2026-09-17: Getting real printing working end to end

Spent a debugging session tracing "testing a print simply does nothing" down to five separate bugs.
Each one masked the next. Found them by adding fine-grained native tracing and testing
incrementally against a real Epson L3250 over Wi-Fi.

1. A `std::mutex` deadlock in `CupsServer`. `getDefaultPrinterName()` self-locks. It was being
   called from a code path that already held the same mutex. Froze the entire server.
2. An unbounded retry loop in `cupsSendRequest()`. A `for(;;)` with no cap on `httpPost()`
   failures. Discovered when it hammered the real printer with 1300+ connection attempts a minute.
3. TLS was never actually wired in. `HAVE_SSL` was undefined so the upgrade-on-demand code path
   was compiled out entirely. Cross-compiled OpenSSL 1.1.1 for all four Android ABIs from a
   Windows Git Bash environment with no `make` or `cmake` on PATH. The NDK bundles its own.
4. A job-id extraction bug. `ippFindAttribute(..., IPP_TAG_INTEGER)` did an exact tag match that
   failed against the real printer's response. An actually-successful print reported "spool
   failed."
5. A 15 second HTTP timeout too short for a real ~25MB uncompressed color raster page. That can
   take 15 to 17 seconds for the printer to fully ingest and respond to.

Once those were fixed the missing piece was that the Epson has no onboard PDF interpreter. It
accepted PDF print jobs at the IPP layer (200 OK) but silently produced nothing. Built a real
PWG-Raster encoder using CUPS's own raster-writing API. Converts before sending whenever the
target printer's supported formats don't include `application/pdf`. First real color print out of
the app: "IT WORKED!!!"

Follow-up fixes from real-hardware testing. Test page margins were clipping (right-edge color
ramps and the last column of text). A font-size ladder rendered multiple sizes identically because
it shrank to fit instead of truncating text. Duplicate printer entries showed up in discovery
because Cuppa's own mDNS re-advertisement of an added printer looked like a second not-yet-added
printer.

## 2026-09-17: Jobs tab, TLS, and a false lead on root privileges

Wired up persistent job history and active-job tracking. Previously the Jobs tab was a literal
hardcoded "Phase 0" stub. The first attempt had a real bug. `CupsRepository.printFile()` ran on a
coroutine scope tied to the Test Print bottom sheet's composition. Dismissing the sheet before the
~10s printer round trip finished silently cancelled the history-recording code even though the
native print had already succeeded. Fixed by moving the actual work onto the repository's own
process-lifetime scope.

Implemented real IPPS/TLS support for Cuppa's own hosted listener. Previously only the outgoing
client side had working TLS. Self-signed cert via Bouncy Castle. Opportunistic upgrade on the same
port using the same 426-then-reconnect pattern real IPP Everywhere printers use.

Chased what looked like a port-privilege issue ("port stuck on 8631 regardless of Settings"). Turned
out to have nothing to do with root or Shizuku at all. It was a race between `BootReceiver` reacting
to `MY_PACKAGE_REPLACED` and `MainActivity`'s own auto-resume both starting the server in the same
process with no guard against a duplicate start. Along the way confirmed that port 631 actually
binds fine on modern Android without root or Shizuku in the first place. Most of the existing "Root
Privileges" UI was solving a problem that doesn't exist on real devices. Stripped it down to a
low-key diagnostic line instead of a prominent call to action.

Also found and fixed six Settings toggles (subnet access restriction, HTTP Basic Auth, mDNS enable,
AirPrint compatibility, and two since-removed toggles) that were UI-only. Written to
SharedPreferences but never read anywhere else in the codebase. Wired the real ones up for real.
Removed the redundant ones. Same pattern turned up again in Thermal Settings. Darkness, print
speed, dither mode, and invert polarity were all stored but never actually applied to printed
output on either the real dispatch path or Test Print. Fixed by threading `ThermalPreferences`
through both.

## 2026-09-17: Repo migration, CI hell, and a real release pipeline

Decided the old RolloPrint repo had run its course. Nuked it. 56 tags and all the history, gone.
Turned it into Cuppa's actual home. If I'm going to keep building this thing I want it done right.
Real repo. Real CI. Real releases people can actually update to. Not me manually sideloading APKs
onto my own phone forever.

Getting there was more painful than it should've been. The GitHub Actions workflow failed four
separate times before it built anything. A third-party Android SDK setup action that just didn't
work anymore. Sdkmanager not even being on PATH once I ripped that action out. My own
`gradle.properties` had a hardcoded path to my own machine's Android Studio JDK baked into it.
That one's embarrassing. Also makes me wonder how it ever worked locally in the first place. Then
the keystore secret got silently corrupted going through the Windows clipboard of all things. Fixed
all four one at a time. Actually read the logs instead of guessing and re-running blind.

Also nearly pasted a live GitHub token straight into chat. Revoked it on the spot. Not doing that
again.

Once it actually built I went back and cleaned out the graveyard. 90 old releases and 97 old
workflow runs from the old project. All deleted. First real release, v0.5.0, came out the other end
signed and published automatically. Tested the in-app updater on my own phone and it worked first
try. Honestly wasn't expecting that.

Then immediately found out the permission toggles had been lying to me the whole time. Granted
notifications. Granted battery exemption. The UI just sat there showing the old state until I
tapped it again. Turned out Dashboard and Settings each had their own half-working copy of the
same permission-check logic. Fixed it in one place and it was still broken in the other. Ripped
both out and replaced them with one version. Both permissions get requested automatically at
launch now, so there's nothing left to hunt for. Settings is the only place left that shows status.

While I was in there I also finally asked out loud why every tab had this huge dead gap under the
title. Answer: a collapsing app bar that was never actually being used for anything it's meant for.
Swapped it for a normal-sized one everywhere. Immediate improvement. Zero downside.

Shipped that batch as v0.5.1 right after.
