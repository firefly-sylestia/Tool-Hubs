# Changelog

All notable changes to Tool-Hubs are recorded here, newest first.

Network Switcher is the actively developed module. Some earlier release bumps were made without a
version-named commit, so the entries below `0.7.1` are reconstructed from commit history and grouped
conservatively rather than split into invented version numbers.

Each entry corresponds to the `versionName` in
[`tools/network-switcher/build.gradle.kts`](tools/network-switcher/build.gradle.kts), or — for the
desktop launcher — the `version` in [`desktop/mc-launcher/package.json`](desktop/mc-launcher/package.json).
See [`CONTRIBUTING.md`](CONTRIBUTING.md) for how versions are chosen.

## [MC Launcher 0.6.0]

The launcher could find a Java runtime but not provide one, so on a machine with no JDK — the usual case
on Windows, and the case a real launch was refused on — it stopped and asked the user to go and install
one. The official launcher does not do that: it downloads the runtime the version asks for into that game
directory. This does the same, from Mojang's own index.

### Added
- **Java is downloaded in-app.** *Download Mojang's Java* fetches the runtime the version json names
(`javaVersion.component`) into `<instance>/runtime/<component>/<platform>/<component>`, where the
version's own launcher would put it. The manifest's raw copies are used rather than the LZMA ones — an
LZMA decoder would be a dependency for a third of the bytes, and every file is verified against the same
sha1 either way — executables are marked executable, and the runtime is then found automatically by the
resolver, having already proved it runs.
- **Launching does it by itself.** When a launch finds no runtime on the machine, it downloads Mojang's
for that instance as part of the launch task, with progress in bytes. The instruction to go and install
a JDK is gone.
- **Settings answers the Java question in one place:** what this instance needs and why Java lives in
the game directory, what is already installed there, what is on the machine, and one button to download
Mojang's build. *Find Java on this machine* now says so explicitly when it finds nothing, instead of
appearing to do nothing.

### Fixed
- **"Install a JDK 25 or newer" on a machine that had Java 25.** The component table guessed
`java-runtime-epsilon` was Java 21; Mojang now ships 25 under that name. A major read from a path is only
a guess, so any runtime that exists on disk is now asked its version directly, which is what a component
name cannot tell you. Found by `npm run netcheck`, which downloads the real runtime and runs it.
- The missing-Java message was a paragraph listing six folders. It now says what the instance needs and
what to do about it.

## [MC Launcher 0.5.0]

Every instance has a game directory, so every instance has its own `options.txt` and can want its own
JVM arguments. Both were previously out of reach: the only memory and window settings lived in the
launcher's global settings, and the game's own options could only be changed from inside the game.

### Added
- **Per-instance game options.** The instance page shows the settings people actually change —
fullscreen, graphics, FOV, render and simulation distance, maximum FPS, GUI scale, pause-on-lost-focus,
VSync — read from that instance's `options.txt`. Any other key can be set by name, and the whole file
is listed, with a Remove per line that hands a key back to the game's own default. The file is patched
rather than rewritten, so every line the launcher does not know about survives exactly where it was,
which is the only safe way to edit a file the game itself rewrites.
- **Per-instance JVM arguments.** Extra arguments for this instance, added after the version json's own
arguments and before the memory limit, so the memory setting still wins. Arguments are typed one per
line and split the way a shell would, so `-Dsome.option="hello world"` stays one argument.

### Changed
- The launch plan takes the instance's own arguments into account, and an older state file is brought
forward with no arguments rather than undefined ones.

## [MC Launcher 0.4.0]

Adding a mod is what a launcher is for, and until now it meant downloading a jar in a browser and
moving it into a folder by hand. Modrinth is a public API — no key, no account — so the launcher can do
it properly: pick from what fits the instance, check every file against the publisher's hash, and bring
the required dependencies with it.

### Added
- **Search and install from Modrinth.** Mods, resource packs and shader packs, filtered by the selected
instance's game version and loader — a Quilt instance is offered Fabric mods as well, because Quilt runs
them. Every file is downloaded through the same verified, resumable downloader the game files use, so a
mod is checked against the sha1/sha512 Modrinth published instead of trusted because it arrived. The
version list shows each version's game versions and loaders, and the newest release that fits is offered
first, with betas labelled as betas.
- **Required dependencies come with it.** A mod that needs Fabric API or an API library is the most
common way a modded launch fails, so required dependencies are followed up to three levels deep and
installed into the same folder. A dependency with no version for this instance is named in a warning
instead of being installed for a different game version, and a project's own optional and embedded
dependencies are left alone.
- **Jars are read, so the list says what a mod really is.** Each mod's own metadata is read out of the
jar — `fabric.mod.json`, `quilt.mod.json`, `META-INF/mods.toml`, `META-INF/neoforge.mods.toml` and the
legacy `mcmod.info` — and shown as its real name, version and loader instead of the file name someone
chose. Nothing is executed and nothing is extracted; only the metadata file is read.
- **Problems are flagged before a launch fails.** A Forge or NeoForge mod in a Fabric instance, a mod
that asks for a Minecraft version this instance is not on, a second copy of the same mod id: each is
said in the folder listing, where it can be fixed. A range the launcher cannot read produces no warning
at all — a wrong warning about someone's mod is worse than none.

### Fixed
- **`/project/<id>/versions` does not exist.** Modrinth's list endpoint is `/project/<id>/version`;
the wrong one is a 404, and it was found by `npm run netcheck` calling the real API rather than by
reading the documentation. Reading a mod's metadata was checked the same way: netcheck downloads a real
Sodium build and reads "Sodium" back out of the jar.

## [MC Launcher 0.3.0]

An instance is now the unit of play, and it owns its own mods, resource packs and shader packs. Before
this there was one shared game directory and a version on each profile, which cannot express "this mod
only in this pack" at all — and importing a modpack, the thing modded play actually starts from, could
not be expressed either.

### Added
- **Instances.** One game directory each, with its own version, loader, `mods/`, `resourcepacks/` and
`shaderpacks/`. Create one, name it (the folder is derived from the name and cannot collide), select
it, and install a version into it. A profile is now only an identity, so several profiles can play the
same instance and one profile can play any instance.
- **Manage mods, resource packs and shader packs per instance.** Each folder lists what is in it and
what the game will actually load: a mod may be a `.jar` or a `.zip`, a resource pack and a shader pack
must be zips. Turning one off renames it to `<name>.disabled` — the trick every loader ignores — so the
toggle matches the files on disk instead of a list that can drift. Files that the folder would not
load are refused with the reason, rather than copied and silently ignored: a `.jar` in `resourcepacks/`
answers "did you mean to add it to mods?". Each folder can also be opened in the OS file manager.
- **Modpack import, for both formats that exist.** A Modrinth `.mrpack` is read from its
`modrinth.index.json`: the game version, the loader and every file with a URL and hashes are installed
for you, `env.client: "unsupported"` entries are excluded as the pack intends, and the `overrides/`
tree is unpacked into the instance root. A CurseForge pack is read from its `manifest.json`, and
because its files are identified only by project and file id — which needs CurseForge's keyed API —
the overrides are imported and the rest is reported honestly instead of half-installed. A pack is
inspected before anything is written (format, Minecraft version, loader, file counts), and it is
always imported into a **new** instance so it cannot disturb the ones you have.
- **Java is found, not assumed.** The launcher used to run `java` from PATH, which fails on any machine
where PATH has no java — the usual case on Windows. It now looks inside the game directory for the
runtime Mojang installed (`runtime/<component>/...`, matched against the `javaVersion.component` the
version json names), then `JAVA_HOME`, then the JDK folders for the platform and SDKMAN, and picks the
**smallest runtime that is new enough** for the version. Settings lists what was found, with a `Use`
button, and `java -version` is only spawned for candidates that cannot be identified from their path.
- **An instance is never deleted by accident.** Removing one forgets it and leaves every file where
it is, and the app says so with the path. The service can delete, but only a directory inside the
launcher's own instances folder — deleting an imported directory the user pointed us at is not ours
to do — and no part of the UI asks it to yet.

### Changed
- **Settings: the game directory became the instances folder.** New instances are created under it; an
imported instance keeps the path it was given.
- An older state file is brought forward instead of being ignored: the one global game directory
becomes an imported instance, taking its version from the profile, and the fields nothing reads any
more are dropped. A directory that no longer exists is not resurrected as a broken entry.
- Installing a version now updates the instance it went into, so the next step is Play rather than
hunting for the version again.
- The launcher's copy no longer claims it does not download game files, and the Instances page says
what an instance is rather than assuming one.

### Fixed
- **"Could not start java at \"java\"" on every launch.** Reported from a real launch attempt: the plan
fell back to the bare name `java`, which only works when PATH has one. Java resolution is now the
service described above, and when there is genuinely nothing to run, the failure names every place
that was searched rather than telling you to "set an absolute java path".

### Not in this pass
- No Forge or NeoForge, and no OptiFine.
- No modpack marketplace or browsing: a pack is imported from a file you already have.
- CurseForge file download, which needs their API key and terms.

## [MC Launcher 0.2.0]

The launcher can install the game now. Before this it could only run an installation that already
existed, which made it useless to anyone who did not already have one — the thing it was asked to fix.

### Added
- **Install a game version.** The Installed versions page lists every version from Mojang's manifest
(releases, snapshots or both, with search) and installs the one you pick: the version json, the client
jar, every library the per-OS rules allow, the asset index and its objects, and the native libraries.
- **Fabric and Quilt.** Pick a loader and a loader version and it is installed on top: their metadata
publishes a finished profile json that inherits the vanilla version, so it is written into
`versions/<loader>-<version>/` and its libraries are fetched. No installer is run and no JDK is needed
at install time.
- **Native libraries are unpacked** into `versions/<id>/<id>-natives`, which is where the launch plan
already looked. Older versions need that directory to exist before they can start, so installing a
version now also makes it launchable — previously that step belonged to the official launcher.
- **Add from a folder.** A game directory (one containing `versions/`) becomes the game directory in
place, because copying one means gigabytes. A single version folder is copied into the current
installation, which is how a version downloaded elsewhere gets in.
- **Progress you can see.** Installs run as tasks: percentage, files completed, and bytes done out of
bytes total, in the install card and the task list. The percentage is bytes, not files — 41 of 3000
says nothing, and one 100 MB asset looks like one 2 KB pom otherwise.

### Changed
- **Nothing half-downloaded counts as installed.** A file is fetched to `<name>.part` and only renamed
into place once its sha1 matches the hash the version json declared; an interrupted download resumes
with a range request; a server that ignores the range is detected and restarted; a failed request is
retried; and a hash mismatch keeps the bytes and fails with the expected and actual hash rather than
leaving a corrupt jar behind. A truncated archive is refused with a message that says so, instead of
being read as if it were whole.
- Files already present and hash-correct are skipped, so re-running an install completes what is
missing rather than fetching everything again. An existing version json is never overwritten.
- Installing into a profile that has no version selected selects what was just installed.
- The launch errors for a missing client jar or a missing natives directory now point at Install,
because this launcher is the thing that fills those gaps now.

### Fixed
- **A library listed twice lost its natives.** Real version jsons spell some coordinates out twice —
1.16.5 lists `org.lwjgl:lwjgl-glfw:3.2.2` once with the artifact and once with the `natives` field.
Libraries were deduplicated before the per-OS rules were evaluated, so the first listing won and the
one carrying the natives was dropped; the launch plan then found no natives to require and the game
died at runtime with an unloadable native library. Rules are now evaluated first and repeated
coordinates are merged, natives included. Found by `npm run netcheck` against the live metadata, not
by reading the code.

### Not in this pass
- No Forge or NeoForge installation (their installers have to be run as a Java process and edit the
version json themselves), no OptiFine, and no CurseForge/Modrinth marketplace.
- Still no account of any kind: no Microsoft login, no session, no token. Online-mode servers will
refuse a join, as before.

### Verified
- `npm run selfcheck` — 51 checks, including the downloader's resume, checksum rejection, retries and
byte progress, zip reading and extraction, install planning, loader metadata, and importing.
- `npm run netcheck` — 8 steps against the live services: a real manifest and version json, a real
asset index, 6 real files hash-checked, a real native jar downloaded and unpacked, the 39.6 MB client
jar fetched and verified, and real Fabric and Quilt loader profiles parsed.
- `npm run test` — 6 renderer tests, one of which installs what the picker offers.

## [MC Launcher 0.1.1]

The desktop launcher now lives in this repository, under
[`desktop/mc-launcher`](desktop/mc-launcher). It is a second toolchain — Electron, Vue 3, Vuetify and
TypeScript, built with Node — so it is deliberately not a Gradle module and nothing in `settings.gradle.kts`
or the Android build refers to it. `0.1.1` is the first version of it published from here, and the first
whose installers a run can actually produce.

### Added
- The launcher itself, imported whole: the main-process service layer behind one generic RPC channel,
the preload bridge and snapshot state protocol, the launch pipeline (version `inheritsFrom` chains,
per-OS library rules, classpath and natives resolution, placeholder substitution, and a real `spawn`),
and the Play / Installed versions / Servers / Settings / Logs interface.
- [`.github/workflows/desktop.yml`](.github/workflows/desktop.yml), which verifies the app on Linux and
packages installers on Ubuntu, Windows and macOS, but only for commits that touch `desktop/` — so the
Kotlin modules never pay for a Node build, and the Windows `.exe`, `.dmg`, `.deb` and `.AppImage` are
available as run artifacts.

### Fixed
- Packaging no longer fails after the installer has been built. Three separate causes, all found by the
first CI run: a `GITHUB_TOKEN` in the packaging environment is enough for electron-builder to treat the
build as a publish, and the publisher it then resolves to `null` crashed the build while writing update
metadata — the token is gone and `publish: null` in `electron-builder.yml` states outright that this
build publishes nothing. The `.deb` target also needs a homepage and a maintainer, which the package
now carries; the `.exe` and `.dmg` had been built successfully before the crash, but the failed job threw
the artifacts away.

### Notes
- The Android modules are untouched by this: no `core/*` or `tools/*` source, token or version changed.
- The launcher is local-profiles-only by design. It has no account, session or token handling, downloads
no game files, and can only join servers configured `online-mode=false`. See
[`desktop/mc-launcher/README.md`](desktop/mc-launcher/README.md).
- Its version is its own, in `desktop/mc-launcher/package.json`; it moves independently of Chime and
Network Switcher.

## [Chime 0.2.0]

### Changed
- The whole interface is rebuilt on the shared Aurora theme and the `:core:ui` kit. Chime was still
  rendering inside a bare `MaterialTheme`, so every screen fell back to framework greys and looked
  like a different product beside Network Switcher. Screens are now one gutter, one card and one set
  of icon-led headings, the tab bar is the app's own nav with real icons instead of letters, and the
  editors are bottom sheets rather than small grey dialogs.
- Time and date are now chosen on Chime's own snapping wheels and calendar. The platform
  `TimePickerDialog` and `DatePickerDialog` ignored the theme entirely, which is what made the
  add-alarm screen look like a system utility bolted into the app.
- Alarms are list rows with their day, dismissal mode and snooze on labelled chips, a switch that
  lines up with every other row, and the next trigger spelled out underneath.

### Added
- A 12- or 24-hour clock choice (system, 12-hour or 24-hour) that is threaded through the clock face,
  the alarm and reminder lists, the next-up card, both notifications and the spoken time. It was
  previously hard-coded to 24-hour everywhere outside the notification text.
- The bell and the spoken time are separate switches, so the bell can strike alone, the time can be
  spoken alone, or both can happen; the previous single toggle forced them together.
- A code is now **scanned** to register it, instead of being typed. A text field asking for "the code
  to accept" produced codes nobody could reproduce at the door; scanning the sticker captures exactly
  the bytes the alarm will compare against later.
- A permissions block that reports notifications, full-screen alarms, exact alarms and the camera,
  each with the button that grants it. Android 14 keeps full-screen alerts behind a special grant, so
  reminders that never took the screen are now explained rather than silently downgraded to banners.

### Fixed
- The strike count is decided when a chime is armed and travels with the intent, so a chime that
  lands a moment early or late no longer rings the wrong number of times. A time from the "set times"
  list is a single chime rather than a count of the hour.
- The spoken time follows the clock style setting (it was always phrased the 12-hour way), and the
  bell service is told which style to speak in rather than assuming one.
- "Next up" notifications and alarm subtitles follow the chosen clock style instead of always
  formatting in 24-hour style.

## [Chime 0.1.1]

### Fixed
- The clock's "Speak the time now" button passed the timestamp where the text-to-speech call wants
  a `Context`, so the module did not compile. Found by CI on the first tag build of Chime — which is
  exactly what a tag build is for.

## [Chime 0.1.0]

First release of Chime, the alarm, reminder and chiming-clock tool. Its version lives in
[`tools/chime/build.gradle.kts`](tools/chime/build.gradle.kts) and moves independently of Network
Switcher.

### Added
- Alarms and reminders that take over the screen when they arrive, on top of the lock screen, with a
  sound that ramps from near-silence and a vibration pattern rather than starting at full volume.
- Four dismissal modes: a button, a shake streak (six shakes inside four seconds, gravity filtered
  out so carrying the phone is not a shake), a scan, or either of the last two. A saved QR or barcode
  value can be required, so only the code taped to the door dismisses that alarm. Snooze is offered in
  every mode and shortens by a minute each time it is used.
- A clock that strikes the hour on a synthesized pendulum bell — hourly, or only at chosen times —
  with quiet hours on by default, and an optional spoken time phrased in words.
- A silent countdown notification for the next alarm or reminder, driven by the platform chronometer
  so it stays accurate without the app running in the background.
- Snooze, dismiss and skip straight from the notification, and re-arming after a reboot, a clock
  change or an app update.

## [0.11.4]

### Fixed
- The watcher no longer disappears after an app update. The restart receiver treated an update exactly
  like a reboot and honoured the "start after reboot" setting, so with that setting off the ongoing
  notification vanished on every install and the watcher had to be toggled off and on again. An update
  now restores the watcher whenever it was running, and brings it back in about five seconds rather
  than waiting thirty for a radio stack that never went away.
- The page header contracts into a small **opaque** pill rather than a translucent rail. Translucent
  meant the cards read through the title; the pill is solid, inset from the screen edges, fully
  rounded, and still carries the scroll-progress filament.

## [0.11.3]

### Fixed
- The two rows of stat tiles under the Activity timeline — Events, Grouped, Failed, Recovery events
  and Hours with failures — were drawn on top of each other. `HubReveal` lays its content out in a
  `Box`, and that block handed it three children, so the rows genuinely overlapped. They now sit in
  one `Column`, and each row measures to its tallest tile so a wrapped label such as "Hours with
  failures" no longer stands taller than the tile beside it.

## [0.11.2]

### Fixed
- The Activity page's hourly bars could stand taller than the plot. Every kind of event carried a
  minimum visible height so a lone failure could not vanish, and an hour with a large total plus a
  few single failures then summed to more than the chart — the bar overflowed its own card, which is
  what read as cards overlapping. The whole stack is measured first and fitted to the plot, so a bar
  can never exceed it while a single failure stays visible.
- Stacked bars are now one capsule per bar with flat boundaries between segments. Rounding every
  segment separately made a stacked bar look like several small cards balanced on top of each other;
  the same treatment now applies to the usage timeline, and a bar outside the selected hour dims.

### Changed
- Page headers pin to the top of the screen instead of scrolling away. The header lifts into a
  frosted rail that also covers the status-bar strip, the emblem contracts, the title drops its
  aurora tint for legibility on the panel, the hairline underneath becomes a scroll-progress bar,
  and tapping the pinned rail scrolls back to the top. The settings page uses the same rail, so its
  back button stays reachable however far down the diagnostics you have scrolled.

## [0.11.1]

### Fixed
- The 0.11.0 release did not compile, which is why its CI run failed: the live graphs read theme
  colours inside their `Canvas` draw lambdas (not a composable scope), the reset-scope enum was
  nested inside the tracker's companion object where the UI could not name it, and a companion
  function was called through the same-named property. All three are corrected at the cause.
- The "why did it change?" card no longer offers an RSRP figure. `SignalStrength.cellSignalStrength`
  does not resolve against this project's compile SDK, so the card reports Android's 0–4 signal level
  on its own rather than a dBm value it could not actually read.
- Artifact uploads can no longer fail an otherwise good build, and retained APKs drop from 7 days to
  1: repeated runs had filled the account's artifact storage quota, which failed the upload step on
  every module.

## [0.11.0]

### Added
- Live graph on the Watch page: switchable Speed / Data / Latency / Signal over a 1, 5, 15 or 30
  minute window, with drag-to-inspect. Speed and traffic come from the watcher; latency and signal are
  sampled only while the screen is open, and the card says so instead of drawing a gap as if the radio
  had been quiet. Where no sample was taken the line breaks and goes dashed rather than being joined up.
- Network Health score on the Watch page. It is weighted from 5G uptime, LTE fallbacks, radio
  stability, recovery success and recovery speed, and flagged data, and it shows every factor with the
  evidence behind it. A factor with no evidence is dropped and the rest are re-weighted, so a fresh
  install is not scored as broken; below that threshold it reports Collecting rather than a number.
- Recovery analytics on the Data page: success rate, average, fastest and slowest confirmed recovery,
  current and longest failure streak, fallback count and time, longest single fallback, 5G uptime,
  longest 5G session, radio changes per hour, and a seven-day breakdown. Fastest, slowest and the
  longest failure streak are now recorded by the watcher; previously only a mean existed, which hid
  both the recovery that took a minute and the streak that never ended.
- "Why did it change?" on the Watch page. The last radio transition is explained in words with its
  evidence listed. A data-SIM move or a recovery the app triggered itself is reported as **recorded**;
  everything the modem never reports is reported as **likely** and says so, because Android exposes no
  reason for a re-selection.

### Changed
- The Activity page was rebuilt around an interactive incident timeline: 24 hourly bars stacked by
  kind, tap or drag an hour to filter the list to exactly that hour, severity colouring, and an
  incident detail that opens in place as a recorded BEFORE → EVENT → ACTION → AFTER sequence. Filters
  grew from four to six (All / Success / Failed / Network / SIM / Recovery), and tapping an event
  expands it instead of doing nothing.
- The obsolete "feature recommendations" card, which was recommending features that now exist, was
  replaced with advice that names a switch that actually exists and is based on the whole log rather
  than the current filter.
- The usage timeline was rebuilt: 1 hour / 6 hours / 24 hours / 7 days ranges, bars stacked as 4G
  under 5G, 4G-vs-5G comparison for the whole range, and a busiest-bucket figure. Tapping a bar opens
  the exact one-minute intervals it was built from, with the fake/excluded rows still listed so a
  correction can be undone. The old chart only compressed minutes into a line and left the exact
  records in a separate list with no link between them.

### Notes
- Added the `INTERNET` permission. It is used for one thing: the live latency graph measures a single
  TCP handshake to a public resolver while the Watch screen is open. Nothing is uploaded, and the
  probe stops as soon as the screen is closed.

## [0.10.0]

### Fixed
- The no-service SIM failover could never revert. It recorded nothing about which SIM the data came
  from or that a failover was even in progress, so there was no code path back and the user stayed on
  the backup SIM until they changed it by hand. The move is now recorded and handed back as soon as
  the monitored SIM has service again.
- The LTE failover only reverted when the monitored SIM reported 5G, which it cannot do while the
  backup SIM holds the data connection — so again it never came back. Data is now handed back when
  5G returns *or* after a few minutes regardless, and a timed hand-back suppresses the next outbound
  switch so the two SIMs cannot trade the data connection every cooldown.
- Backup-SIM failover now works on a phone with two SIMs and untouched settings. Both SIMs previously
  had to be chosen explicitly, so the feature silently did nothing. The monitored SIM defaults to
  whichever carries data and the backup to the other active SIM.
- The notification no longer reads "monitoring 4G / LTE" throughout a failover. It states where the
  data connection actually is, and carries a "Move data back" action for the case the automatic path
  cannot cover.

### Added
- Wi-Fi failover: if the monitored radio stays on 4G or loses service, the Wi-Fi radio is raised so
  the phone can auto-join a saved network, and turned back off once 5G returns. It goes through
  Shizuku because apps have been barred from toggling Wi-Fi since Android 10, and the setting says
  plainly that Android gives no way to pick the network.
- Auto-correct on the counter-check card. It marks flagged spikes fake first, and only then trims the
  totals to match the device counter, reporting exactly which of the two it did. It never silently
  rewrites history, and it stays hidden while the books already balance.

## [0.9.0]

### Fixed
- "Mark interval fake" now works on every row. Interval ids were the minute bucket, so several
  records could share one id and toggling flipped a different record than the one tapped — which is
  why the button appeared dead for some rows. Ids are now unique, and the history list sorts ties by
  id so ordering is stable between reads too.
- Marking or resetting a single radio no longer disturbs the other one.

### Added
- Interactive usage graph: drag across it to scrub, with a crosshair, a highlighted point and a
  readout of the inspected bucket, plus start/end time labels.
- "Mark every spike as fake" clears all flagged intervals in one pass and reports how many changed.
- Per-field reset. The Data page now clears only what you choose — everything, the 4G figure, the 5G
  figure, or the speed peak — instead of one blanket button that threw away the good numbers too.
- Counter check card: the device's own aggregate mobile counter against the app's attribution for the
  session, split into attributed, deliberately set aside, and not seen by the watcher. This is the
  check that makes the 4G/5G split falsifiable — a negative residual would prove it wrong.
- Session reconciliation feeds the totals, so rejected bursts and unattributable bytes are accounted
  for rather than silently vanishing.

### Changed
- New module-local backdrop: orbiting light, three aurora blooms on independent periods, a grid and
  particle field that drift upward forever, a travelling sheen, a slow scanline and a vignette, all
  driven by continuous infinite transitions at different speeds so the motion never reads as one loop.
- All page headers rebuilt. The icon badge is now fully opaque with an accent border — it used to
  wash its accent over the animated backdrop, which is what made the glyph look glitchy — and the
  whole band reacts to the page's scroll position: the badge contracts, the title scales from its left
  edge, the subtitle dissolves and a gradient filament under the header fills as you scroll, with a
  breathing title colour on its own loop.
- The Data page's Reset/History row is split up, and History is a full-width action.

## [0.8.0]

### Fixed
- Impossible traffic and speed spikes are no longer shown or counted. A burst faster than the
  cellular link can physically go is treated as a counter discontinuity: its bytes are excluded, the
  speed peak is left alone, and the event is recorded instead of silently dropped.
- Sampling windows shorter than half a second are never turned into a rate, which is what used to
  manufacture gigabit-per-second readings out of a re-scheduled sample.
- The daily and lifetime speed peaks can no longer be poisoned by a single impossible sample, and
  legacy over-ceiling values are clamped when displayed.
- A recovery cooldown is now visible everywhere. Watcher status is derived from state instead of
  written as a literal at each call site, so changing a setting or Shizuku reconnecting no longer
  wipes the cooldown message from the notification and the app.
- The cooldown countdown refreshes instead of freezing on the moment it started, and entering a
  cooldown posts a real alert notification.
- Shizuku failures no longer consume the attempt budget. Three transient bind errors inside the burst
  window used to trip the retry ceiling and park recovery on the full cooldown even though nothing
  was wrong with the network.
- Changing a setting no longer resumes a watcher the user paused. Settings are applied on the next
  resume instead, and resuming reloads them.

### Changed
- Bottom navigation redesigned: equal-width tabs with a single sliding accent pill, so a tap is one
  animation instead of a full re-measure of the bar.
- Settings is no longer a navigation tab. It is opened by a header button and pushed as its own page
  with back-out, and the tab bar now carries only the four screens you move between.
- The ongoing notification shows the live cooldown, the failed-attempt count, the Shizuku state and
  a contextual action: Pause, Resume, or Retry now while cooling down.
- Settings gained a status-notification card that warns when notifications are disabled, since an
  invisible foreground notification is easily mistaken for a watcher that is not running.
- The Data page's dead "History" shortcut now opens the History page.

## [0.7.3]

- Redesigned the usage-history graph and added interval review.
- Redesigned the Events page with a timeline graph and duplicate grouping.
- Fixed usage-history Compose colors and the usage-history empty state.
- Fixed Network Switcher release compile errors.
- Added the project README, agent rules, contributing guide and roadmap docs.

## [0.7.2]

- Added a minute-level network usage history store.
- Added interval history with manual fake/excluded interval controls and fixed fake interval
  accounting; impossible traffic counter spikes are rejected.
- Added the usage-history graph and the interval review screen.
- Moved usage-history file work off the UI thread and throttled pruning.
- Fixed the usage-history empty state.

## [0.7.1]

- Pause 5G retries while a backup SIM is carrying data.
- Added the backup SIM 5G recovery pause toggle.

## [0.7.0 and earlier]

Reconstructed from commit history — these releases were bumped without a version-named commit, so
their boundaries are approximate.

- Added call pause: automation pauses during a call, with a toggle shown independently of SIM count.
- Clamped legacy impossible speed readings.
- Used the framework API for Samsung data SIM switching; backup SIM switching now verifies its
  result and supports a Samsung fallback.
- Added experimental Samsung RF recovery controls, Samsung band selection via Shizuku, thermal
  service bridge controls and tactile thermal action buttons — later hidden behind the experimental
  set.
- Data accuracy and SIM failover pass (`0.3.0`): today's usage bucket with a 7-day history, per-day
  peak speed, session-local traffic baselines, the daily 4G data guard with a warning notification
  and progress meter, and RAT-driven SIM failover.
- Reliability pass: exception-safe Shizuku health monitoring, recovery gated on Shizuku readiness,
  a working circuit breaker, wall-clock-persisted back-off, and a watcher loop that cannot die.
- Auto 5G detection moved into a single shared `RadioWatcher`/`RadioState` pair, with explicit
  Android 11 `PhoneStateListener` handling and non-standalone 5G treated as 5G.
- Aurora design system: animated backdrop, glass cards, breathing radio orb, light and dark Material
  3 palettes, and a night-qualified window theme.
- Notification status drift fixed, daily usage accounting corrected, and release signing made
  keystore-aware with a debug-key fallback.
- Quick Settings tile, boot/package-replace restore, and the foreground watcher service.
