# Instrumental App Handoff Summary

## Project

- Android app: `com.instrumental.attachment`
- Current version: `1.1.115` / version code `386`
- Android: min SDK 26, target SDK 35, compile SDK 37
- Build command:
  ```powershell
  .tools\gradle-9.4.1\bin\gradle.bat --no-daemon assembleDebug
  ```
- Release APK: `app/build/outputs/apk/release/app-release.apk`

## Latest Release - 1.1.115

- Added a beta `GE100 Remote` landing-page tool for the GE100 Pro Li. It supports
  the verified GE100 USB HID protocol over Android USB OTG and includes a
  Bluetooth LE transport for compatible pedal firmware.
- The remote reads and searches all 150 on-device presets, filters likely metal
  patches, changes the active preset, controls output/input/OTG levels using the
  device's real ranges, and shows the ten-slot chain with live bypass toggles.
- Destructive or storage-heavy operations are deliberately absent: no firmware,
  factory reset, delete, rename, or capture upload. NAM/MNRS/IR files remain
  external and are managed with MOOER Studio.
- Hardware setup performed separately from the APK: user capture slots contain
  Precision Drive Boost plus EVH 5150 III, Satan High Gain, Cali High Gain,
  Mesa Mark III High Gain, and Mezzabarba Trinity captures. Verified playable
  presets were saved to banks `36A`-`37B` as `5150 Tight`, `Satan Rhythm`,
  `Cali Metal`, `Mark III Rig`, and `Trinity Rig`.

## Previous Release - 1.1.114

- Full Keys now has a separate vertical Filter Sweep control beside the combined
  pitch-bend/vibrato lever. It runs a native resonant low-pass from dark to fully
  open without interrupting held notes.
- Keyboard A/B sweeps are independent in split layouts, all active layers follow
  their side, values persist, and MIDI CC74 drives the matching control.

## Earlier Release - 1.1.113

- Session has two original built-in one-shot banks: Festival SFX and Transition FX.
- Session can persistently index an external SFX folder containing up to 10,000
  WAV, OGG, MP3, M4A, AAC, or FLAC files. The browser searches filenames and
  folder paths; samples remain external and decode only when assigned to a pad.
- Imported Session pad samples use dedicated sampler slots and restore after an
  app restart. Chord-loop scheduling now has deterministic start/stop timing.

## Primary Code

- UI and interaction logic: `app/src/main/java/com/instrumental/attachment/MainActivity.java`
- Preset catalog: `app/src/main/java/com/instrumental/attachment/TonePreset.java`
- Java/native bridge: `app/src/main/java/com/instrumental/attachment/NativeAudioEngine.java`
- Real-time audio engine: `app/src/main/cpp/native_engine.cpp`
- SoundFont assets: `app/src/main/assets/`

## Source Control - READ THIS FIRST

The working folder `D:\Instrumental App` is now a tracked git working copy of
`https://github.com/kurosakijin/bandapp-mobile` on branch `main`. Before 1.1.104
it was untracked, and that caused a real incident worth not repeating.

What went wrong: the folder's source stayed current, but its `HANDOFF_SUMMARY.md`
and `RELEASE_HISTORY.md` silently drifted six releases behind (they still ended at
1.1.97 while 1.1.103 was live). A release run then bumped to a version tag that
already existed on GitHub and the publish aborted.

Working agreement now that it is tracked:

- `git pull` before starting work, and commit plus push when finishing. If the
  working tree and `origin/main` disagree, trust `origin/main`.
- `release.sh` publishes the APK and both markdown files to the GitHub release,
  but it does NOT commit or push source. Push the source yourself after shipping.
- `release.sh` derives the next version from `app/build.gradle`, so that file must
  match what is actually published or the tag will collide again. `versionCode`
  only ever increases.
- Never commit `bandapp-release.jks` or `keystore.properties`. This repository is
  public and both are now in `.gitignore`, along with local-only material such as
  `backup/`, `desktop/`, `images/`, `external soundfonts/`, and logs.
- `core.autocrlf` is set to `true` in this clone. The repository stores LF, the
  working files are CRLF. Without it, every file appears fully rewritten.

## Implemented Product Behavior

## Release 1.1.104 - Session Pad Workspace

- Added Session, a live or recorded pad workspace opened from the piano overflow
  menu. It is separate from the existing layer channel-strip Mixer.
- Chord pads on the left play the shared Chord Mode slots. A pad can latch a
  sustained chord or drive a tempo-synced looping backing pattern, selected by a
  single mode control. Long-pressing a pad edits that chord.
- Eight named drum pads on the right sit above one volume fader per pad, and
  releasing a fader auditions that pad at its new level.
- A three-state loop control records pad and chord hits for two bars, replays the
  captured pattern in sync, and clears it on the next press.
- Audio capture writes the performance to a `.wav` through the existing recorder.
- Keys are played from a MIDI keyboard only. The bar selects the keyboard sound
  and opens the external SoundFont folder import for user SF2 files.
- Session tempo is adjustable from 40 to 240 BPM and persists, as do the eight
  drum pad volumes and the chord pad mode.

## Release 1.1.103 - Tuner Input Routing

- The tuner exposes its own input-source selector and defaults to the internal
  microphone the first time it is opened.
- Tuner input selection persists independently from guitar, vocals, and looper
  routing, and switching it restarts capture immediately.
- Internal-mic capture retries with Android's Generic input preset when an OEM
  audio HAL rejects the low-latency Unprocessed preset.
- Tuner status and reference labels use high-contrast text.

## Release 1.1.102 - Audible Drum Kit Normalization

- Complete drum kits use measured per-font gains targeting a 0.75 loudest
  single hit instead of the overly quiet stacked-hit target.
- The output uses a fixed 0.86 soft ceiling, preventing red-zone peaks and hard
  clipping without automatic gain reduction or lowering later strikes.

## Release 1.1.101 - Full Kit MIDI Tom 1

- MIDI Tom 1 note 50 resolves to Full Kit's Tom 1 trigger note 48 before entering
  the native per-piece routing table.
- The resolved piece drives both audio and image animation and retains its chosen
  default or custom sound.

## Release 1.1.100 - Drum MIDI, Full Kit, and Volume Reliability

- All 46 bundled drum SoundFonts use measured per-font gain values and remain
  protected by the native 0.86 output ceiling.
- MIDI drums play while Settings and MIDI Assignment are open; learning and
  audition happen together, pedal bindings cannot consume drum strikes, redundant
  one-shot note-offs are skipped, and Drum adaptive buffering is capped.
- Full Kit reapplies inherited piece routes when a lazy selected kit finishes
  loading, including while MIDI input continues.
- MIDI hits animate the corresponding physical Full Kit image by mapped instrument
  identity, independently of the sound assigned to that piece.

## Release 1.1.99 - Funk Hi-Hat Balance

- Funk closed, pedal, and open hats receive a real 4 dB font-level lift.
- Non-hat Funk notes receive the inverse trim, retaining the previous balance
  for the rest of the kit across Pad Mode and Full Kit routing.

## Release 1.1.98 - Active Control Glow and Firefly Slide Fix

- Shared active buttons and Piano/Looper toggle pills now use a brighter cyan
  fill, animated accent border, colored elevation shadow, and subtle scale lift.
- Looper Chord, Slide, Split, and Dual use the shared active-state styling.
- Firefly Melody no longer forces portamento in Piano or Looper mode and follows
  the user's Slide setting like every other preset.

## Release 1.1.97 - Direct Cabinet IR Switching

- Regular Guitar CAB selection no longer reloads the active NAM model.
- A cabinet tap decodes the selected bundled WAV and loads it directly into the
  native double-buffered convolution stage.
- UI state and preferences change only after successful native loading; failures
  retain the previous cabinet and show an error.

## Release 1.1.96 - Landing Contrast and Stable Tuner

- All light landing-page cards and badges now use dark title/description text;
  white text remains only on the deliberately dark brand rail.
- The tuner rejects weak or poorly correlated background input, waits for three
  consistent readings before acquiring a note, and requires four readings to
  switch strings or octaves.
- A five-tick signal-loss hold and bounded cents smoothing keep the tuner steady
  without making deliberate note changes feel stuck.

## Release 1.1.95 - Guitar Control Routing and Anti-Click Audio

- Guitar Input now runs before Gate/Comp/Wah in both NAM and bypass paths, with
  0% mute, 50% unity, 100% +6 dB, and a matching input meter.
- NAM Gain is independent drive; EQ and cabinet level are no longer flattened
  by intermediate saturation; generic Delay now runs after NAM.
- CAB bypass and final Output work with either NAM or the default Guitar path,
  and all six pedal controls persist across launches.
- Live-input streams no longer shrink their buffer after stable callbacks, which
  removed the repeating underrun grow/shrink `tak` cycle.
- Live Guitar uses a stable three-burst floor, conservative backlog trimming,
  and a linear fade over occasional short capture reads.

## Release 1.1.94 - Restored Guitar Chorus Pedals

- Restored Classic, Warm, and Shimmer chorus choices from the former Guitar
  pedal catalog inside the modern MOD signal-chain stage.
- Selecting a chorus preset enables the effect, highlights the active preset,
  applies its live Rate and Depth values, and saves the configuration.

## Release 1.1.93 - Guitar Latency, Live NAM Controls, and Stage Bypass

- Live microphone Guitar now prefers an exclusive Oboe output stream and uses
  smaller adaptive buffering with faster stale-input recovery.
- Guitar's visible Gain and four-band tone controls now directly shape the NAM
  input and post-NAM tone stack.
- Clean Chorus - JC Wide enables a real chorus configuration and matching clean
  cabinet response.
- Cabinet selection is no longer lost when another NAM/IR request is loading;
  the latest requested cabinet is applied next.
- NAM selection now lives entirely in the PEDAL stage. PEDAL and CAB each have
  persistent highlighted bypass buttons that retain all selected settings.

## Release 1.1.92 - Clean Cabinets, Chord Headroom, and Final Output

- Added six new 48 kHz mono cabinet responses derived from CC BY 4.0 Freesound
  captures by jesterdyne: four Jensen positions, Celestion C414, and ENGL V30.
- Non-metal NAM selections now bypass the metal pre-boost and delay. American
  Deluxe and JC Clean automatically use Jensen clean/jazz cabinets.
- Virtual Guitar applies smoothed active-note headroom before NAM so power
  chords stay defined instead of creating glass-like intermodulation.
- Microphone Guitar uses a lower fixed NAM input calibration for multi-string
  power-chord headroom.
- Both guitar modes now expose true final output gain after NAM and cabinet
  processing, including actual mute at 0% and makeup up to 150%.

## Release 1.1.91 - Virtual Guitar Signal Chain and Shared NAM/IR Library

- Virtual Guitar MIDI is now strictly one Keyboard A voice. Sound 2, Dual,
  Layers, Sustain, and Slide are suppressed without overwriting the user's
  saved normal Piano configuration.
- Its controls are organized as clickable MIDI, Player, Drive, NAM Pedal,
  Cabinet, Modulation, Room, and Output stages.
- Note-off now fast-releases every active Virtual Guitar SoundFont voice,
  including alternate, palm-muted, and harmonic articulation fonts.
- Virtual Guitar and microphone Guitar expose the same seven built-in NAM
  models and 23 cabinet IRs, categorized for Metal, Rock, Clean, Jazz, Chorus,
  Rhodes, and Bass use while retaining external NAM/IR folder support.
- Both guitar modes have a persisted 0-150% cabinet level control implemented
  after native IR convolution.

## Pending Release - Permanent NAM Guitar and Stage Tabs

- Regular Guitar now always loads and enables its selected NAM amp and cabinet.
  Removed the duplicate Metal NAM test/bypass panel and all legacy preset-label
  overwrite paths.
- Moved `Tight Delay` and `High Gain Metal` into the NAM Amp selector. Tight
  Delay has preset-specific post-cab makeup, a less destructive low cut, and a
  unity dry delay path. High Gain Metal replaces fuzz clipping with controlled,
  transient-preserving overdrive.
- The fixed signal-chain strip is now an animated stage-tab controller. Each
  tab opens only its real processing controls: Input, Gate, Compressor, Wah,
  NAM Amp/EQ, Cabinet IR, Chorus, Delay, Room, or app Output.
- Added persisted NAM input level and a final app guitar output level independent
  of Android media volume. The Amp tab exposes only Gain/EQ/Presence.
- Compressor and Wah now process before NAM. Wah remains manually controllable
  and responds to USB-C or Bluetooth MIDI expression CC11.

## Pending Release - NAM Chug Dynamics and UI Contrast

- Added fixed `+2.3 dB` makeup gain to the regular Guitar NAM path without
  automatic gain adjustment or volume pumping.
- Removed duplicate pre-NAM limiting that flattened dense palm-muted chords.
  NAM transients now retain bounded internal headroom through cabinet
  convolution, followed by a transparent output soft knee near the ceiling.
- Button labels now derive black or near-white text from the actual fill
  luminance. Light guitar controls, NAM/IR pickers, piano sound pills, menus,
  recording controls, and looper chips use dark text; dark fills retain light
  text.

## Pending Release - NAM Rig Output Balance

- Fixed both built-in NAM rigs being far too quiet. Cabinet IR normalization
  now uses convolution energy instead of the absolute sum of all taps; the old
  method reduced the bundled Mesa IR to about 4.8% and the Lead 800 IR to about
  3.1% before the model output trim.
- Restored unity NAM output gain and connected the regular guitar rack's
  Volume control to the NAM path. A static soft limiter protects peaks without
  automatic gain reduction or volume pumping.
- Replaced the regular Guitar `Pedal` picker with a searchable `NAM Amp`
  picker containing seven MIT-licensed bundled models: 5153 high gain,
  British stack, boutique lead, AC chime, American deluxe, jazz clean, and
  Ampeg grind.
- Added a searchable cabinet IR picker with 23 choices. Twenty-one are the
  48 kHz Jester Dyne Brutal and Emerald packs (CC0/public domain or explicitly
  commercial-use permitted); the existing Mesa and Celestion demo IRs are
  covered by the bundled MIT license.
- NAM/IR selection is persisted and loaded asynchronously. Gate and pre-drive
  remain before NAM; Chorus, Delay, Room, and final Volume now process after
  NAM and cabinet convolution.

## Pending Release - Regular Guitar Modular Rig

- Replaced the regular Electric Guitar control surface with a scrollable,
  fixed-order rack: Gate, Compressor, Wah, Amp/EQ, Cabinet, Chorus, Delay,
  Room, and Output.
- Added bypassable native compressor, chorus, delay, and room processing to
  the live microphone guitar path. Every visible rack control changes the
  real-time DSP and persists across launches.
- Preserved the existing pedal tone selector, six live amp/EQ/output controls,
  manual wah, cabinet voicings, input meter, and low-latency Oboe route.
- Built and verified the signed release APK locally at version `1.1.86`
  without publishing it to GitHub or Obtainium.
- Added a built-in `Metal NAM Test` rig for the regular microphone guitar:
  a compatible legacy WaveNet 5153 high-gain NAM capture followed by a Mesa
  cabinet IR. The NAM replaces the built-in amp instead of double-amping it.
- The bundled test rig loads asynchronously, reports Loading/Ready/Error in
  the rack, persists its bypass state, and disables itself outside regular
  Electric Guitar mode. Its source, MIT license, and attribution are included
  in `app/src/main/assets/guitar_rig/`.
- The bundled NAM expects a 48 kHz audio route. A mismatched route is rejected
  explicitly instead of producing corrupted or incorrectly pitched output.
- Matched the built-in metal rig to the user's Guitar Rig reference chain:
  Screamer-style boost -> compatible 5153 NAM -> Mesa 4x12 IR -> Delay
  Man-style echo. Drive, Tone, Level, Delay time, Repeats, and Delay Mix are
  adjustable and persisted.
- The Screamer stage tightens low frequencies and boosts the DI before NAM;
  the delay is applied after NAM and cabinet convolution. This preserves the
  intended pedal/amp/cab/time-effect ordering.
- Added a second selectable built-in rig matching the user's `HiGain Fuzz`
  reference: Red Fuzz-style asymmetric saturation -> compatible British
  high-gain NAM -> Lead 800/Celestion cabinet IR. Delay is forcibly bypassed
  in this preset.
- Switching between `Tight Delay` and `HiGain Fuzz` asynchronously loads the
  correct NAM and IR pair, disables switching while loading, persists the
  selected rig, and reports which rig is active.

## 1.1.86 Release Summary

- Fixed landing-page contrast: the brand rail now uses an opaque medium-blue
  face, and branding, section headings, READY state, instrument names,
  descriptions, tags, tuner text, and feature-card text are white.
- Kept dark text in light dialogs, browsers, and editor panels so the contrast
  correction does not make those dense work areas unreadable.

## 1.1.85 Release Summary

- Restored the original user chord board/defaults and removed the Progressions
  control; the app no longer changes chord selections to create a mood.
- Added Joyous, Funky, and Lively Piano Play Modes. They alter note order,
  timing, and dynamics for each chord attack while preserving the exact chord
  selected by the player.
- Joyous lifts from bass to an emphasized melody, Funky alternates outer and
  inner voices with syncopated accents, and Lively uses a fast bouncing
  flourish. Long-press timing remains available for all three.

## 1.1.84 Release Summary

- Lightened animated button faces from deep navy to medium sky blue while
  preserving white labels, rotating borders, and brighter selected-state hues.
- Added one-tap Chord Mode progression presets: Joyous Funk, Sunny Disco,
  Neo Soul Lift, and Lively Gospel.
- Changed the fresh-install chord board to the extended-harmony Joyous Funk
  sequence; existing saved chord boards are never overwritten automatically.
- Rebuilt Chord Mode’s six-note generation as solo-piano voicing: a dedicated
  left-hand root/slash bass plus five upper chord tones, with extensions kept
  above middle C to avoid muddy low-register tensions.
- Added automatic progression-aware voice leading. Each slot evaluates chord
  inversions and registers, retaining common tones and minimizing total upper
  voice movement while preserving chord-defining tones and extensions.

## 1.1.83 Release Summary

- Replaced Chord Mode’s binary Strum toggle with a Piano Play Mode selector:
  Block, Studio, Rolled, Reverse, Ballad, Arpeggio, and Strum.
- Studio mode adds subtle timing variation, stronger bass and melody voices,
  softer inner notes, and repeat-to-repeat velocity variation.
- Rolled, Reverse, Ballad, Arpeggio, and Strum use the existing long-press
  interval editor, retained at an adjustable 1-1000 ms.

## 1.1.82 Release Summary

- Replaced the Piano mixer’s MIDI-velocity/held-note meter approximation with
  true per-channel SoundFont signal metering inside TinySoundFont. Each bar now
  follows the rendered waveform energy, attack, sustain, release tail, layer
  volume, and the actual loudness of built-in or external SF2 layers.
- Improved animated-button contrast with opaque dark-blue faces and white text,
  including Beta badges, sound pills, menus, transports, toggles, and selected
  chord-list entries.
- Extended the combined pitch/modulation lever through every active Piano layer:
  layers 2-4 follow Keyboard A and layers 6-8 follow Keyboard B, including
  external SF2 layer slots and the configured bend range.

## 1.1.81 Release Summary

- Replaced flat app workspaces with the requested horizontal
  `#21A5D9 -> #217EC4 -> #FFFFFF` gradient while preserving the Full Kit studio
  background image.
- Restyled shared landing, menu, transport, pill, rectangular, circular, and
  engine buttons with fully opaque fills and an animated cyan/blue/purple/pink
  perimeter that rotates around each control without glass transparency.
- Added real SF2 bank/program enumeration for external SoundFonts. A selected
  file now opens a searchable preset list and loads only the chosen preset.
- Cached external SF2 metadata in memory so repeated browser and label refreshes
  do not keep querying the native engine.
- Expanded Song Presets into full Piano performance scenes, including external
  Sound 1/Sound 2 files and presets, Layer Mode, all eight layer sounds, levels,
  zap settings, and external layer presets.
- Added an always-on Performance Lock diagnostics panel with engine status,
  measured output latency, route, MIDI ports, external SF2 count, drum-map
  validation, and an audio-route check.
- Exposed MIDI note/CC foot-control learning from every instrument menu.
- Retained and verified the existing looper bar quantization, overdub undo,
  waveform, WAV export, USB recovery, and adaptive underrun handling.
- Added immersive fullscreen restoration across focus/resume and system gesture
  exclusions for the live performance surface.

## 1.1.80 Release Summary

- Swell/swirl cymbals are now polyphonically retriggerable from pads and MIDI.
- Rapid hits are counted instead of collapsing into one pending trigger, and
  each hit starts a separate five-layer, 5 ms-spaced swell without restarting
  earlier swells.
- The fixed native voice pool supports eight overlapping swell trigger groups
  without allocating memory on the real-time audio thread.

## 1.1.79 Release Summary

- Fixed Rock 3's silent pedal hi-hat by routing MIDI note 44 to the kit's
  audible tight-hat articulation at note 42.
- Applied the Rock 3 correction consistently to Pad Mode, Full Kit defaults,
  customized pieces, previews, and note-availability checks.
- Reduced the combined Piano pitch/mod lever's pitch travel to the centered
  middle 50% of its height, leaving 25% unused above and below for easier bends.

## 1.1.78 Release Summary

- Fixed full keyboard, Chord Mode, mixer, layer masters, and Piano bars showing
  stale built-in preset names after an external SF2 was selected.
- External SF2 is now the selected popup row, and reopening a picker returns to
  the active file or previous browsing position instead of a built-in row.
- Chord Mode `Strum` remains a tap toggle; long-pressing it opens a persistent
  interval control adjustable from 1-1000 ms. The default remains 30 ms.

## 1.1.77 Release Summary

- Fixed A2 Native NAM packs that contain nested WaveNet models by forcing every
  NAM architecture registration unit into the final Android shared library.
- Serialized external SF2 parsing through a prioritized background queue so
  Sound 1/Sound 2 choices outrank manual layers, and automatic eight-layer
  restoration remains lowest priority.
- External SF2 catalogs return to the selected or last-viewed position instead
  of reopening at the top.
- Virtual Guitar MIDI now uses a GM electric-guitar fallback while its external
  SF2 reloads after process recreation, preventing a temporary piano sound.
- Chord Mode `Strum` spacing is now 30 ms per note.

## 1.1.76 Release Summary

- Fixed valid A2 Native `SlimmableContainer` NAM files being rejected because Android's static linker discarded the architecture registration object.
- External SF2 folders now remain metadata-only catalogs until a SoundFont is selected.
- Replaced thousands of eager external-SF2 row views with a recycled searchable catalog to keep large folders responsive.
- Exposed external SF2 selection in the normal Piano browser, full keyboard, Chord Mode, and all six additional Layer Mode channels.
- Added moving loading indicators and sequential lazy loading for Sound 1, Sound 2, and six additional layer channels.
- Renamed the chord option to `Strum` and changed its low-to-high note spacing to 10 ms.

## 1.1.75 Release Summary

- Expanded MIDI Assignment and Full Kit cymbal selection from four to six bundled swell sources.
- Swell playback now triggers five separate copies of the selected WAV at 5 ms intervals, with layer gain increasing from 105% to 125%.
- Full-screen Chord Mode now highlights all six voiced notes with a sky-to-accent gradient.
- Added persistent optional `Strum 2 ms` playback that schedules chord notes from low to high and safely cancels delayed notes on early release.
- Expanded Choose Chords to 59 chart-oriented chord qualities while preserving existing saved chord type indexes.

### Instruments

- Electric guitar and bass process microphone/USB audio through amp, cabinet, pedal, gate, EQ, and live controls.
- Piano is MIDI-driven. It supports USB MIDI keyboards, sound selection, sustain, reverb, Dual, Mono/Slide, MIDI settings, pitch bend, and a full-keyboard visualizer.
- Full Keys uses one combined animated Pitch/Vibrato lever per keyboard. Vertical travel follows the selected bend range; rightward travel moves the same handle and adds vibrato/modulation, while leftward travel adds no modulation. The default bend range is plus or minus two semitones and remains adjustable from 1-24.
- Drums support MIDI/pads, custom per-piece assignments, cymbal settings, room level, and GM/HQ SoundFont kits.
- MIDI Assignment exposes one `Swell Cymbal` instrument whose sound can be selected from the six bundled swell WAVs. Full Kit Mode exposes those same six variants under Add Piece > Cymbal, and each placed cymbal retains its own variant.
- Full Kit `Default` pieces preserve the selected Pad Mode kit's complete program, including alternate SoundFont preset, genre remap, and metal drive. Custom pieces remain independently routed. Closing Full Kit explicitly restores Pad Mode routing so stale per-piece routes cannot leak into the normal pads.
- Swell playback triggers the selected source WAV five times with 5 ms spacing. The first layer plays at 105% and each of four quieter repeats adds 5%, reaching 125% combined while avoiding the strong metallic comb filtering caused by equal-level copies. Clean drums and chimes remain linear until the fixed final ceiling, and Full Kit metal sources use the same gain staging as Pad Mode.
- Full Kit Mode has a permanent bundled 90-degree overhead wood-floor and rectangular drum-mat background that matches its top-down drum pieces. Per-piece sound selection includes `Default · [Selected Kit]`, which follows the active kit and its genre remapping instead of retaining a stale or silent custom sample.
- The native engine uses Oboe and attempts low-latency output paths, with a fixed final output stage to keep levels controlled rather than using aggressive automatic volume reduction.

### Piano Sound Browser and Dual

- Piano Sound 1/Sound 2 selection opens a fixed, searchable browser in the right pane instead of a dialog.
- Sustain, Reverb, Dual, Mono/Slide, and MIDI controls remain fixed above that browser.
- When Dual is enabled, the browser has independently scrollable Sound 1 and Sound 2 lists.
- Enabling Dual stretches Sound 2 smoothly from zero width while Sound 1 compresses; disabling it reverses the transition without rebuilding the browser or losing search/scroll state.
- The browser updates selected-row styling without rebuilding all rows on each tap.
- Piano library fonts load lazily; the app uses GM as a temporary fallback while a selected library font loads.
- Piano can retain access to a user-selected external folder, recursively list/search
  its `.sf2` files, and load an external SoundFont into dedicated Sound 1 or Sound 2
  slots without bundling or copying it into the APK. The folder rescans when the app
  returns to the foreground.
- Split immediately exposes Keyboard B in the live controls. Keyboard A/B share one fixed panel slot and receive independent preset, meter, and FX state updates.
- The Keyboard A/B selectors use equal weighted widths when Dual is active, fixing
  Keyboard A previously pushing Keyboard B beyond the right edge.
- Recognized GM guitar voices and imported SF2 files marked as guitar voices can use
  the Piano MIDI Guitar Pedal. Its separate stereo native chain provides Clean,
  Crunch, Lead, and Metal preamps, five cabinet responses, drive/tone controls, and
  velocity-sensitive pick/harmonic enhancement. Non-guitar voices bypass it in code.
- Release `v1.1.70` includes `FreePats Fender Clean Electric Guitar.sf2` as a
  separate CC0 download. It is not bundled in the APK; its filename is recognized
  automatically as a guitar voice by the external SF2 browser.
- Full-screen Chord Mode supports 6-20 configurable chord strips. A whole-chord press sounds six voiced notes and highlights all six note bands with sky-to-accent gradients. Optional persistent `Strum 2 ms` mode starts those notes low-to-high at 2 ms intervals, with guarded cancellation preventing delayed or stuck notes after an early release.
- The Choose Chords catalog contains 59 chart-oriented qualities, including power, add, suspended, sixth, seventh, ninth, eleventh, thirteenth, diminished, half-diminished, augmented, altered-dominant, and extended variants. The original 14 type indexes remain unchanged for compatibility with existing chord boards and saved songs.

### Interface

- The landing screen and all instrument workspaces use the shared light sky-blue bubbly interface.
- Guitar/Bass live faders, knobs, signal-chain modules, pitch controls, Piano keys, drum controls, and Android system bars no longer retain black panel surfaces.
- The Piano Controls panel includes a read-only app CPU and RAM monitor sampled once per second. It never changes audio behavior automatically.

### Looper

- Drum mode hides keyboard-only controls.
- Keys mode shows Split, Dual, Slide, Sound 2, and Chord controls.
- Split, Dual, and Slide are in the first visible row. Dual stays visible but is disabled until Split is enabled; disabling Split turns Dual off.
- Chord note-on/note-off tracking uses exact captured note sets to avoid stuck notes after an accidental touch overlap.
- Stylophone sounds do not auto-slide; slide is controlled by the user-facing Slide control.
- Looper keys track 32 pointer IDs, exceeding typical 10-touch hardware. Parent interception is disabled during touches. Some Android devices still reserve three-finger gestures at the OS level; when they send `ACTION_CANCEL`, captured 3+ touch chords are held for 3.5 seconds so they are not cut at once.

### Drum SoundFonts

- Existing HQ kits remain in slots 0-10.
- Ten additional user-supplied SF2 files are bundled under stable names and listed as `Expanded Drum Kits`:
  - Giant Studio Kit
  - Hard Rock Classic
  - Hard Rock V3
  - Melotti Studio
  - Real Acoustic 5
  - Roland Canvas Standard
  - Charlie Standard
  - Tama RockStar Classic
  - Tama RockStar 2
  - Ultimate CM Kit
- These additional kits occupy native slots 11-20 and load only after selection to avoid an approximately 68 MB startup-memory increase.
- Their loading worker is assigned Android background priority to avoid competing with UI/audio threads. First selection can still take time to parse a large SF2; GM remains the immediate fallback.
- Drum taps now play a temporary Standard GM sound while an HQ font loads instead of being dropped. Missing SF2 notes choose a playable articulation from the same kit, failed fonts are not marked ready, and custom Studio pieces use the complete dry kit rather than its hi-hat-only first preset.
- MIDI Assignment kit/sample rows separate preview from confirmation. Candidate SoundFonts and WAV library samples can be auditioned before selection, lazy fonts preview automatically after loading, and every assigned piece row has its own test control.

## 1.1.72 Release Summary

- Full Kit `.kit` export/import now saves and restores the selected kit,
  per-piece sound sources/notes, MIDI assignments, channels, gain, pan,
  custom-kit state, room/cymbal levels, velocity settings, and snare/rim state.
- The new `bandapp-kit/2` JSON payload remains compatible with legacy
  `bandapp-kit/1` position-only files.

## 1.1.71 Release Summary

- Added Virtual Guitar MIDI as a dedicated beta instrument on the landing page
  with its own visible in-workspace BETA badge.
- Removed the MIDI guitar rig from regular Piano and hard-bypassed its native
  processing whenever the standard Piano workspace is active.
- Added a guitar-filtered sound browser, independent saved preset, and dedicated
  amp, cabinet, drive, tone, and articulation controls.
- Bundled the 10.85 MB CC0 FreePats Fender clean-electric-guitar SoundFont as the
  starter instrument so the new workspace is immediately playable.

## 1.1.70 Release Summary

- Added persistent external Piano SF2 folder scanning and searchable Sound 1/Sound 2 loading.
- Fixed the Keyboard B live-control selector being laid out beyond the visible row.
- Added the guitar-only MIDI pedalboard with stereo preamp/cab processing and velocity harmonics.
- Attached a lightweight CC0 FreePats Fender clean-electric-guitar SF2 for testing.

## Recent Verification

- `assembleDebug` and signed `assembleRelease` passed after the external SF2,
  Keyboard B, and MIDI guitar-pedal changes.
- No Android device was connected for end-to-end MIDI, touch, or audio latency testing.
- The C++ build emits existing warnings from `tsf.h`; they did not fail the build.

## Release History

- Read `RELEASE_HISTORY.md` before continuing work. `release.sh` updates it for every release and uploads it beside the APK in the GitHub release.

## Recommended Next Checks On Device

1. Verify 3-10 finger Looper chords on the target handset and check whether the OS three-finger screenshot gesture is enabled.
2. Test first-load time and loudness for each added drum kit, then adjust only the static per-kit gains if needed.
3. Test USB MIDI piano input, Dual routing, Split routing, and sustained chord release behavior.
4. Test guitar and bass live monitoring with the actual microphone/USB hardware and headphones.
