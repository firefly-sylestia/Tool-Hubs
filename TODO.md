# Tool Hubs delivery checklist

## Desktop launcher — MC Launcher 0.13.1, one instance at a time

- [x] **The Instances page is tabs instead of four stacked cards in two columns**: a header with the
      instance, its state and every action that changes it, then Version (install a version and the
      versions it holds), Mods and packs, Worlds, and Options.
- [x] The header states the instance in the words the Play page uses (Ready to play, Needs a version,
      No instance) with its version, loader, source and folder beside it.
- [x] The Add from Modrinth dialog lists the catalogue on open rather than waiting for a search word,
      with a loading spinner and the icon of the kind being added on each row.
- [x] Version bumped to `0.13.1` in `desktop/mc-launcher/package.json`, lockfile included.

## Desktop launcher — MC Launcher 0.13.0, an instance as a modpack, and an interface that says what it is doing

- [x] **Export as a modpack.** The selected instance writes out as a self-contained `.mrpack`: its mods,
      resource packs, shader packs, `config`, `defaultconfigs`, `scripts` and `options.txt` under
      `overrides/`, with an index naming the game version and the loader an importer has to install first.
      The game files, the worlds, the backups and the logs stay behind; the pack is named and versioned
      (a date stamp when left empty) and written into a folder picked after the form, so a cancelled pick
      leaves nothing behind. An instance with nothing of its own to carry is refused by name.
- [x] **The store opens on the catalogue.** It no longer needs a search word and an instance before it
      shows anything: the open tab lists the most downloaded content, a search word and a sort narrow it,
      and a selected instance filters it to its version and loader. With no instance and a resource tab
      open it says so and offers to pick one instead of installing nothing at all.
- [x] **A running indicator that is visible from every page.** The title bar shows the playing profile
      with a pulsing dot, a Stop button and a chip for whatever install, download or launch is still
      running with its percentage; the Play page replaces the launch button with the same state, and its
      activity list says which task is working, which finished and which failed, with how long it took.
- [x] **State as an icon rather than a sentence**, and the icon font actually loaded: Vuetify was drawing
      every `v-icon` as its own name in text because `@mdi/font` was never imported. Versions, worlds,
      backups, resources, servers, Java runtimes and crash findings all carry their own state now.
- [x] **A Fabric instance exported the wrong game version**: `fabric-loader-0.16.9-1.21.4` was read as
      `0.16.9-1.21.4`, which an importer cannot install. The game version is taken as the last version
      number in the id, and the new export check is what caught it.
- [x] Em dashes taken out of the copy the interface shows.
- [x] Version bumped to `0.13.0` in `desktop/mc-launcher/package.json`, lockfile included.

## Desktop launcher — MC Launcher 0.12.0, crash help

- [x] “What went wrong” reads each instance's `crash-reports/` and `logs/latest.log` and names the cause:
      memory, the wrong Java, graphics, a mod built against another version, a failed mixin, duplicate
      mods, a missing dependency with the mod ids read out of the loader's own block, an incomplete
      installation, or a dropped connection — each with the line it was read from and what to do.
- [x] Reports listed newest first and selectable, the log as the fallback, nothing ever modified, and no
      findings at all from a log that is not a crash — including a mod that prints the word “error”.
- [x] Fixed at the cause, both found by the new checks against real report shapes: the mod list was read
      with spaces where every loader indents with a tab, and a mod id in typographic quotes kept them.
- [x] Version bumped to `0.12.0` in `desktop/mc-launcher/package.json`, lockfile included.

## Desktop launcher — MC Launcher 0.11.0, the shell XMCL's interface has

- [x] The launch pill: the play control set into its leading edge, spinning once when clicked, the
      instance actions set into its trailing edge with an icon that turns as the menu opens, and a lift
      on hover.
- [x] Motion in one stylesheet, XMCL's behaviours brought over: cards transition their border towards the
      primary colour on hover instead of jumping to an elevation, list rows tint, rows arrive staggered,
      a page fades up as it loads, and the app sits on a drifting grid with a slow light sweep.
- [x] Icons in the navigation, and `prefers-reduced-motion` turning every animation off.
- [x] Version bumped to `0.11.0` in `desktop/mc-launcher/package.json`, lockfile included.

## Desktop launcher — MC Launcher 0.10.0, servers that the game can see too

- [x] A server list ping the way the game does it — handshake, status request, one JSON response —
      showing version, players, round trip, the icon and the description with colour codes stripped; a
      refusal or a timeout is reported as offline with the reason. **Ping all** at the top of the list.
      Verified against a live server by `netcheck` (mc.hypixel.net answered in 70 ms).
- [x] The game's own `servers.dat` is read and written: the launcher's list goes into it, the game's
      list comes back out, a server both know is renamed rather than duplicated, an untouched server
      keeps every field it had, and a file this launcher cannot read is reported and left exactly as it
      was rather than written over.
- [x] Found at the cause while checking the protocol: a varint of six bytes was accepted instead of
      refused, and `::1` was read as host `:` with port 1 — both are now right.
- [x] Version bumped to `0.10.0` in `desktop/mc-launcher/package.json`, lockfile included.

## Desktop launcher — MC Launcher 0.9.0, worlds and backups

- [x] Worlds are read from each instance's own `saves/` folder and described by the world's own
      `level.dat` — name, the version that last wrote it, when it was last played, game mode, hardcore —
      with an unreadable world listed with its reason instead of hidden.
- [x] Export a world to a zip (streamed, so a huge world needs no huge memory) and import from a folder,
      a `.zip` or a `.mcworld`; two worlds in one archive is refused, and a name that is taken gets a
      number rather than overwriting what is there.
- [x] A backup of `saves/` is taken before any install that changes the game underneath it — version,
      loader or modpack — before anything is written, named `before-…` so it still reads as what it is
      later; an instance with no worlds writes nothing. **Back up now** does the same by hand.
- [x] Two new primitives, both with no dependency: a minimal NBT reader and writer (`level.dat`, and
      the game's own `servers.dat` next), and a zip writer that streams files in.
- [x] Version bumped to `0.9.0` in `desktop/mc-launcher/package.json`, lockfile included.

### Still to do on this wave
- [ ] Import an archive by streaming it too: it is unpacked from memory today, so one over 512 MB is
      refused with a message saying to import the unzipped folder instead. Exporting and backing up
      already stream.

## Desktop launcher — MC Launcher 0.8.0, a launch that repairs what it starts

- [x] Launch runs the installer's own plan first and fetches only what is absent or the wrong size; a
      complete installation costs a walk over the tree and no requests at all.
- [x] Natives are unpacked again when they are gone, and a Java path in Settings that no longer answers
      is tested, reported and passed over instead of stopping the launch.
- [x] A version json that names no Java is matched by the Java its release needs — 1.12.2 no longer
      lands on Java 25, and a machine with no runtime gets the one that era used.
- [x] Fixed at the cause: real asset indexes list one hash under two names, so the plan held a path
      twice and two downloads raced for it (HTTP 416, or a corrupt file in another order). Each hash is
      planned once. Found by installing a real version and starting it.
- [x] `npm run netcheck --launch` covers the whole chain: a complete 1.12.2 install, three files
      deleted and fetched back, `PATH`/`JAVA_HOME` blanked so the runtime must be downloaded, and the
      game started with the real command. It reaches the game's own graphics code and stops at the
      missing display, which is as far as a headless machine can go.
- [x] Version bumped to `0.8.0` in `desktop/mc-launcher/package.json`, lockfile included.

### Still to verify on a desktop
- [ ] A launch that opens a window and reaches the main menu. Everything up to the window is covered
      above; the window itself needs a machine with a GPU and a display, and `netcheck --launch` was
      run in a container without either.

## Desktop launcher — MC Launcher 0.7.0, the Modrinth store page

- [x] A Modrinth page of its own: search, sort (relevance/downloads/followers/newest/updated), four
      tabs (mods, resource packs, shaders, modpacks), result cards with icon, stats, loaders and
      categories, and “Load more”.
- [x] A project page: published description, gallery, client/server requirements, licence, links to
      source/issues/wiki/Discord, and every version with an install button and its required-dependency
      count.
- [x] Modpacks install from the store: the `.mrpack` is downloaded and verified, then imported as a new
      instance — which is why that tab needs no instance selected.
- [x] Fixed at the cause: the licence showed as missing because Modrinth returns it as an object on some
      routes and a string on others; both are read now. Found by asking the live API.
- [x] Version bumped to `0.7.0` in `desktop/mc-launcher/package.json`, lockfile included.

### Still to verify on a desktop
- [ ] Installing a modpack from the store end to end (the store path downloads the pack and hands it to
      the importer, which is the part that has only been checked up to the download).

## Desktop launcher — MC Launcher 0.6.0, Java without a system JDK

- [x] Download Mojang's own Java runtime in-app, from the official index, into the instance's game
      directory — the same place and layout the official launcher uses. Raw copies rather than LZMA (no
      new dependency), every file sha1-verified, executables marked executable.
- [x] Launching installs the runtime by itself when the machine has none, instead of refusing with a
      list of folders to go and search.
- [x] Fixed at the cause: `java-runtime-epsilon` was assumed to be Java 21, and Mojang now ships 25
      under that name — which is why a machine with Java 25 was told to install another JDK. A major read
      from a path is now only a hint, and anything on disk is asked directly. Found by `netcheck`
      downloading and running the real runtime.
- [x] Settings answers Java in one place: what is needed, what is installed in the instance, what is on
      the machine, and one button to download Mojang's build; "Find Java" says so when it finds nothing.
- [x] Version bumped to `0.6.0` in `desktop/mc-launcher/package.json`, lockfile included.

## Desktop launcher — MC Launcher 0.5.0, per-instance game and java options

- [x] Each instance's own `options.txt` is read, shown and patched, with the settings people actually
      change offered directly and any other key settable by name. The file is patched rather than
      rewritten, so lines the launcher does not understand survive where they were.
- [x] Per-instance extra JVM arguments, added after the version json's own and before the memory limit;
      an old state file comes forward with none.
- [x] Version bumped to `0.5.0` in `desktop/mc-launcher/package.json`, lockfile included.

## Desktop launcher — MC Launcher 0.4.0, install mods from Modrinth

- [x] Search and install mods, resource packs and shader packs from Modrinth's public API, filtered by
      the selected instance's game version and loader, with every file checked against the published
      sha1/sha512 through the existing resumable downloader.
- [x] Required dependencies followed up to three levels deep and installed with the project; a
      dependency with no version for this instance is named in a warning instead of being installed for
      a different game version. Optional and embedded dependencies are left alone.
- [x] Each jar's own metadata is read — `fabric.mod.json`, `quilt.mod.json`, `META-INF/mods.toml`,
      `META-INF/neoforge.mods.toml`, legacy `mcmod.info` — so the listing shows a mod's real name,
      version and loader, and flags a wrong loader, a Minecraft version this instance is not on, and a
      second copy of the same mod id. An unreadable version range produces no warning rather than a
      wrong one.
- [x] Fixed at the cause: Modrinth's list endpoint is `/project/<id>/version`, not `/versions`; found by
      `netcheck` against the live API.
- [x] Version bumped to `0.4.0` in `desktop/mc-launcher/package.json`, lockfile included.

### Still to build on this wave (chosen, not started)

- [ ] Forge and NeoForge installation by running their installers.
- [ ] CurseForge browsing and pack files — needs an API key.
- [ ] Export an instance as a `.mrpack` (the other half of the modpack story).
- [ ] Disk efficiency: hard links for files shared between instances, and ranged multi-part downloads.
- [ ] Localization and themes.
- [ ] Auto-update from a release feed (chosen: yes).
- [ ] Code signing stays out of scope until a certificate exists; the installers remain unsigned.

## Desktop launcher — MC Launcher 0.3.0, instances, resources and modpacks

- [x] Instances: one game directory each, with its own version, loader and resource folders. Create,
      rename-free naming (folder derived from the name, collision-proof), select, remove (files kept
      unless the directory is inside the launcher's own instances folder), and a migration that turns
      the old single game directory into an instance instead of forgetting it.
- [x] Per-instance mods, resource packs and shader packs: list, add from the OS picker, turn off by
      renaming to `<name>.disabled`, remove, and open the folder. A file the folder would not load is
      refused with the reason rather than copied.
- [x] Modpack import: Modrinth `.mrpack` (index files downloaded with hashes, `env.client` exclusions
      honoured, `overrides/` unpacked), CurseForge `manifest.json` (overrides imported, the file list
      reported because it needs CurseForge's API key), inspected before anything is written, and always
      into a new instance.
- [x] Java is discovered rather than assumed: Mojang's runtime inside the instance, `JAVA_HOME`, the
      platform's JDK folders, SDKMAN, then PATH; the smallest runtime that is new enough is chosen, and
      Settings lists what was found. Fixes the reported `Could not start java at "java"`.
- [x] Version bumped to `0.3.0` in `desktop/mc-launcher/package.json`, lockfile included.

### Still to verify on a desktop
- [ ] Launch on a Windows desktop where `PATH` has no java, which is the machine the reported failure
      came from. The runtime download itself is now covered by `netcheck --launch`, which blanks `PATH`
      and `JAVA_HOME`, downloads Java 8 for a real version and starts the game with it — on Linux. What
      still needs Windows is the download, the `java.exe` spawn and native loading there.
- [ ] Import a real modpack end to end (a several-hundred-megabyte `.mrpack`), including its overrides
      landing in the instance and the loader it names being installed first.

## Desktop launcher — MC Launcher 0.2.0, install the game

- [x] Install a version from Mojang's manifest: version json, client jar, rule-filtered libraries, asset
      index and objects, natives.
- [x] Fabric and Quilt loaders from their metadata, by writing the profile json they publish and
      fetching what it lists. No installer process, no JDK needed at install time.
- [x] Native libraries unpacked into `versions/<id>/<id>-natives`, which is what makes an installed
      version launchable on the versions that need them.
- [x] Add from a folder: a game directory is used in place, a single version folder is copied in.
- [x] Downloads verify every sha1, resume from `.part` with a range request, retry failures, and never
      rename an unverified file into place. Progress is reported in bytes.
- [x] A truncated archive is refused by name instead of being read as whole — the failure the 93 MB
      half-downloaded `xmcl-0.71.0-win32-x64.zip` in the working tree has.
- [x] Fixed: a library listed twice lost its `natives`, because deduplication ran before the per-OS
      rules. Real 1.16.5 jsons are shaped exactly like that. Found by `npm run netcheck`.
- [x] Version bumped to `0.2.0` in `desktop/mc-launcher/package.json`, lockfile included.

### Still to verify on a desktop
- [ ] Install a full version and launch it: the whole chain of 3000-odd assets, native loading at
      runtime, and the java spawn path. `netcheck` proves the pieces against the real services, not the
      finished install.
- [ ] The packaged Windows `.exe`.

## Desktop launcher import — MC Launcher 0.1.1

- [x] The Electron + Vue 3 launcher sits at [`desktop/mc-launcher`](desktop/mc-launcher) with its own
      `package.json`, lockfile and Node toolchain. It is intentionally not a Gradle module and is not
      referenced by `settings.gradle.kts`; no `core/*` or `tools/*` file was touched to make room for it.
- [x] [`desktop.yml`](.github/workflows/desktop.yml) verifies the app on Linux and packages installers
      on Ubuntu, Windows and macOS, path-filtered to `desktop/` so Android runs are unaffected.
- [x] Version `0.1.1` in `desktop/mc-launcher/package.json`, the file that is the source of truth for
      this tool's version from here on.
- [x] Packaging fixed at the cause after CI's first attempt: no `GITHUB_TOKEN` in the packaging step and
      `publish: null` in `electron-builder.yml`, which is what stopped electron-builder resolving a
      publisher it could not build and crashing after the installer was written.
- [x] `homepage`, `repository` and a `.deb` maintainer in the package metadata, which the `deb` target
      requires and the `AppImage` run had hidden.
- [x] Re-verified after the move: `npm run verify` — typecheck clean, 32 launcher logic checks, 5
      renderer tests, main/preload/renderer bundles built.

### Still to verify on a desktop
- [ ] `npm run dev` and a real game launch: the java spawn path, native library loading and argument
      ordering against a real mod-loader version. The container has no display.
- [ ] The packaged Windows `.exe` — `electron-builder` cannot cross-package, so CI builds it and nobody
      has run it yet. The first successful CI package run and its download are still pending.

## Chime 0.2.0 — interface rebuild

- [x] Rebuilt every Chime screen on `ToolHubsTheme` and the `:core:ui` kit. The app was rendering
      inside a bare `MaterialTheme`, so it fell back to framework greys and platform dialogs; pages
      are now one gutter of glass cards with icon-led headings and the app's own nav bar.
- [x] Replaced `TimePickerDialog` / `DatePickerDialog` with Chime's own snapping wheel and calendar
      bottom sheets, which follow the theme and the locale's first day of the week.
- [x] Clinched clocks: a system / 12-hour / 24-hour choice threaded through the clock face, the
      lists, the next-up card, both notifications and the spoken time.
- [x] The bell and the spoken time are independent switches, so either or both can ring, and the
      schedule (off / hourly / set times) decides when.
- [x] Strike counts are decided at arm time and travel with the intent, and a "set time" is a single
      chime rather than a count of the hour.
- [x] Dismiss codes are scanned to register them instead of typed.
- [x] Permission block for notifications, full-screen alarms, exact alarms and the camera, with
      Android 14's `canUseFullScreenIntent()` checked rather than assumed.
- [x] Version bumped to `versionCode 3` / `versionName 0.2.0`.

### Still to verify on a device
The 12/24-hour strings, the wheel snapping, the full-screen grant flow and the scan-to-register
round trip need a device; CI only proves the module compiles.

## Observability pass — live graph, health, explanations, timeline redesigns

### Live graph (Watch)
- [x] New `LiveTelemetry` ring buffer (in memory, ~30 minutes at the watcher's cadence), fed by the
      usage tracker for speed and byte deltas. A gap in the buffer is drawn as a break in the line,
      never joined up, so "the watcher was off" cannot look like "the network was quiet".
- [x] Four metrics: Speed, Data, Latency, Signal. Speed buckets keep the fastest sample so a brief
      peak is not averaged away; Data buckets sum the observed byte deltas.
- [x] Four windows: 1 / 5 / 15 / 30 minutes, with drag-to-inspect and a crosshair that shares one
      bucket index with the readout above it.
- [x] Latency is a real TCP handshake to a public resolver, measured only while the screen is open,
      and labelled as a round trip rather than a ping. Signal is Android's 0–4 level, labelled as a
      coarse bucket rather than a dBm value.

### Network health and recovery analytics
- [x] Explainable health score with per-factor points, weights and evidence, graded
      Excellent / Good / Fair / Poor.
- [x] Factors without evidence are dropped and the remaining weights renormalised, so a fresh install
      reports "Collecting" instead of a fabricated score.
- [x] Recovery analytics: attempts, successes, failures, success rate, average, fastest, slowest,
      current and longest failure streak, fallback count and duration, longest single fallback, 5G
      uptime, longest 5G session, radio churn per hour, and a seven-day breakdown.
- [x] The watcher now records fastest/slowest confirmed recovery and the longest failure streak; it
      previously stored only a mean, which hid both extremes.

### "Why did it change?"
- [x] The most recent radio transition is explained in words with its evidence listed and a
      confidence label: **recorded** when the app's own log proves it (a data-SIM move, a recovery it
      triggered), **likely** when it is inference.
- [x] Supporting rows: previous and new state, exact time, signal level, recovery attempts in the
      preceding two minutes and the 12-hour fallback count.
- [x] The card states plainly that Android does not report the modem's reason for a re-selection.

### Activity page redesign
- [x] Replaced the hourly bar sparkline with an interactive incident timeline: hourly buckets stacked
      by kind, severity colouring, tap or drag to select an hour, and a clear-hour-filter action.
- [x] Six filters (All / Success / Failed / Network / SIM / Recovery) as a scrollable chip row, since
      six options do not fit a segmented control. The hour filter is applied before the type filter.
- [x] Recorded incident detail that opens in place as BEFORE → EVENT → ACTION → AFTER, showing exact
      timestamps, the group span and the next event in the log. Per-event signal/thermal snapshots are
      not shown because the watcher never stored them; inventing them would be worse than omitting them.
- [x] Page-level stats for events, groups, failures, recovery events and hours with failures.
- [x] The obsolete "feature recommendations" card, which recommended features that now exist, was
      replaced with advice that names a switch that exists and reads the whole log, not the filter.

### Usage timeline redesign
- [x] Ranges are now 1 hour / 6 hours / 24 hours / 7 days (previously 6h / 24h / 7d with a 15-minute
      hidden bucket size).
- [x] Bars are stacked as 4G under 5G, with a 4G-vs-5G comparison and busiest-bucket figure for the
      whole range.
- [x] Tapping or dragging a bar opens the exact one-minute intervals it was built from; the previous
      chart compressed minutes into a line and left the exact records in an unconnected list.
- [x] Fake intervals are excluded from the totals but still listed, so marking a row fake does not hide
      it from the user who just marked it and needs to undo it.

### Packaging
- [x] Added `INTERNET` for the latency probe only, with the reason stated in the manifest and README.
- [x] Version bumped to `versionCode 15` / `versionName 0.11.0`.

### Not done in this pass
The remaining roadmap items (Smart Modes, visual automation rules, Safe Mode, Diagnostics and
Explain-this panels, Network Test, SIM comparison, weekly report, Network Story) are untouched and
still listed in `docs/NETWORK_SWITCHER_FUTURE_TODO.md`.


## Data accuracy, warnings & SIM failover — previous pass

### Observed traffic & speed
- [x] The dashboard reads *today's* bucket instead of the lifetime total, so the 4G/5G figures reset
      at midnight and tomorrow starts from zero.
- [x] Per-day buckets (`usage_lte_bytes:<yyyy-MM-dd>`) are surfaced as a 7-day history, each row
      carrying that day's traffic split and peak speed.
- [x] Peak speed is tracked per day (`usage_peak_bps:<day>`) and a live speed sample
      (`usage_current_bps`) is recorded alongside it, so the peak no longer climbs forever.
- [x] Cross-session over-count fixed: `TrafficStats` keeps counting while the watcher is stopped, so
      restoring the previous session's counter dumped every byte used while the app was off onto the
      last radio label. Baselines are now session-local and a delta is only attributed between two
      consecutive samples of the same session.
- [x] Bytes observed while the network is unknown are still dropped rather than assigned to 4G or 5G.

### 4G data warning
- [x] New "Data guard" settings card: enable a daily LTE limit and set the threshold in GB.
- [x] The watcher posts a dedicated "Data warnings" notification once per day, the first time today's
      observed LTE traffic crosses the limit, and records a `warning` history event.
- [x] The Data page shows a live progress meter against the limit.

### RAT-driven SIM failover
- [x] New option: while the monitored SIM is on LTE, move mobile data to a backup SIM; when the
      monitored SIM reports 5G again, move it back.
- [x] A 60-second cooldown plus a persisted ACTIVE flag stop one LTE stretch from firing the switch
      on every tick. State only flips once the Shizuku command confirms, so a failed attempt simply
      retries after the cooldown.
- [x] Kept separate from the existing "no service" SIM failover.

### UI
- [x] Bottom nav refreshed with clearer icons (`NetworkCheck` / `Timeline` / `DataUsage` / `Tune`) and
      an icon-size spring on the selected tab.
- [x] The radio orb now carries five animated signal bars that shimmer and light up per generation —
      five for 5G, three for LTE — so the LTE/5G state reads at a glance.
- [x] Data page reorganised: Today card, live speed + daily peak, 4G warning meter and a 7-day history.

### Notifications
- [x] The ongoing notification no longer goes stale: `publishState()` now writes the status and pushes
      the *same* text to the tray in one call. Previously some call sites stored a status without
      notifying (so the tray stayed on "Starting background watcher…") while others notified with a
      different string than they stored (so the tray contradicted the app).
- [x] Headline is now explicit about the radio and the watcher: "5G Reconnect · on 5G / on 4G /
      no service / paused", with a sub-text for background vs manual.
- [x] Updates are guarded until `startForeground` has run, so the tray never gets a detached copy of
      the service notification during cold start.
- [x] Short-lived progress lines (an in-flight attempt, a SIM switch result) update the tray without
      overwriting the persisted status.

### Packaging
- [x] Version bumped to `versionCode 3` / `versionName 0.3.0`.
- [x] Release builds sign with the real keystore when `RELEASE_KEYSTORE_PATH` is present, and fall
      back to the throwaway debug key otherwise so fork PR builds still work. The workflow decodes
      the `KEYSTORE_BASE64` secret and exports the signing environment.

## Known platform limitations — data guard
Documented rather than left as unfinished work:
- [x] `TrafficStats` is device-wide and is not split by subscription, so the observed 4G/5G split stays
      an observation. Cross-session gaps are now excluded instead of being inflated.
- [x] The data warning needs the foreground watcher to be running; the Tune card says so.
- [x] RAT-driven SIM switching needs Shizuku and a vendor that honours
      `cmd phone set-default-data-subscription`; the result is recorded either way.

## 5G Reconnect (network-switcher) — previous pass

### Shizuku detection
- [x] `ShizukuHealthMonitor.refresh()` is now exception-safe. `Shizuku.checkSelfPermission()` throws
      when the binder dies between `pingBinder()` and the permission read; that escaped `refresh()`,
      cancelled the watcher coroutine and left monitoring dead until the service was restarted.
- [x] Health changes are pushed to observers instead of only being polled, so a recovered binder
      resets the retry budget immediately.
- [x] Every UI call site of `refresh()` is safe to run from composition.
- [x] The user-service bridge keeps the Shizuku process warm, retries one bind timeout, and reports
      "Shizuku isn't running" distinctly from a radio failure.

### Auto recovery
- [x] Reconnect attempts are gated on Shizuku being `READY`. Previously a missing tool consumed the
      retry budget and extended the back-off, so fixing Shizuku left the watcher parked for minutes —
      the "toggle it off and on again" symptom.
- [x] The circuit breaker actually opens. The old failure branch assigned `circuitOpen = false`, so
      the "paused after repeated failures" state and its auto-resume probe were unreachable.
- [x] Back-off is persisted against wall-clock time as well as `elapsedRealtime`. After a reboot
      `elapsedRealtime` restarts at zero, so a stale value used to stall recovery for ~1.4 hours.
- [x] Environment failures (binder/bind/permission) do not count as radio failures and retry quickly.
- [x] The watcher loop cannot die: every tick runs inside `runCatching`, and absorbed errors are
      recorded at most once a minute.
- [x] Paused state survives a `START_STICKY` service recreation.
- [x] Enabling automatic reconnect while Shizuku is down now arms the watcher instead of refusing.

### Auto 5G detection
- [x] Detection moved into a single `RadioWatcher`/`RadioState` pair shared by the screen and the
      watcher, so they can no longer disagree about the radio.
- [x] The 5G override flag is `@Volatile`; it was a plain field written on the main executor and read
      from a background dispatcher.
- [x] Android 11 (API 30) is handled explicitly via `PhoneStateListener`, Android 12+ via
      `TelephonyCallback`, and older releases are labelled "5G state not detectable" instead of being
      misread as a permanent LTE fallback.
- [x] Non-standalone 5G (LTE data type, NR advertised) is treated as 5G, so the watcher no longer
      re-triggers the radio in a loop on NSA networks.

### UI
- [x] Full redesign on a shared Aurora design system: animated aurora backdrop, glass cards,
      breathing radio orb with expanding halo rings, spring-pressed buttons, floating nav, staggered
      reveals and animated page transitions.
- [x] Light and dark palettes are complete Material 3 schemes, and switching appearance cross-fades
      every canvas-level role instead of hard-cutting.
- [x] A night-qualified window theme removes the white cold-start flash in dark mode, and status-bar
      icon brightness follows the in-app choice.

## Known platform limitations
Documented rather than left as unfinished work:
- [x] Exact "data saved" cannot be derived from Android traffic counters; it needs a carrier plan
      model. The product reports observed traffic by network state instead.
- [x] `cmd phone set-default-data-subscription` is vendor-dependent and can be rejected by a device
      even via Shizuku; the UI records the returned result.
- [x] Non-standalone 5G is only distinguishable on Android 11+. Below that, recovery is inert by
      design rather than guessed at.
- [x] Some OEMs restrict boot foreground-service starts and battery optimisation exemptions. The app
      can guide users but cannot guarantee exemption.

## Deliberately untouched
Data Warden was outside the scope of this request. Its theme tokens, shared header and metric
primitives are frozen at their previous values so it renders and behaves exactly as before. The
calculator, Music Analyzer and Music Visualizer modules have since been deleted from the repository
at the user's request.
