# Release History

## 1.1.105 (version code 376) - 2026-08-04
- Added a Session card to the landing page features row so the pad workspace is
  reachable without first opening an instrument.
- Opening Session from the landing page selects Piano, starts the engine, and
  returns to the landing page on exit; the piano overflow entry still returns to
  the piano screen.

## 1.1.104 (version code 375) - 2026-08-04
- Added Session, a live or recorded pad workspace opened from the piano overflow
  menu, separate from the existing layer channel-strip Mixer.
- Chord pads drive the shared Chord Mode slots and can either latch a sustained
  chord or run a tempo-synced looping backing pattern; long-press edits a chord.
- Eight named drum pads sit above one volume fader each, and releasing a fader
  auditions that pad at its new level.
- A three-state loop control records two bars of pad and chord hits, replays the
  pattern in sync, then clears it.
- Added audio capture to `.wav`, a 40-240 BPM session tempo, MIDI-only keys with
  a keyboard sound selector, and external SF2 folder import.

## 1.1.103 (version code 374) - 2026-07-29

- Added a visible input-source selector directly to the tuner screen.
- Defaulted the tuner to the internal microphone on first use while retaining
  independent tuner input preferences for internal, headset, and USB-C sources.
- Added a compatible microphone-preset fallback for Android devices that reject
  unprocessed internal-mic capture.
- Increased tuner label and status contrast for readability.

## 1.1.102 (version code 373) - 2026-07-29

- Fixed silent and near-silent drum kits caused by over-attenuating complete
  fonts against an unrealistic stacked-hit measurement.
- Rebalanced every bundled complete kit around a measured 0.75 loudest single-hit
  target while retaining the separately measured sample-library gains.
- Replaced the final hard clamp with a fixed, stateless 0.86 soft ceiling so
  simultaneous hits stay below red without automatic gain pumping.

## 1.1.101 (version code 372) - 2026-07-29

- Fixed MIDI Tom 1 being silent in Full Kit by resolving its conventional MIDI
  note 50 to Full Kit's stable Tom 1 routing trigger, note 48.
- Full Kit MIDI audio and strike animation now use the same resolved physical
  piece while preserving that piece's default or customized sound source.

## 1.1.100 (version code 371) - 2026-07-29

- Measured and normalized all 46 bundled drum SoundFonts against consistent
  complete-kit and per-sample peak targets while retaining the 0.86 output ceiling.
- Kept MIDI drums audible during Settings and MIDI Assignment, prevented pedal
  bindings from stealing drum notes, removed redundant one-shot note-off events,
  and capped adaptive Drum buffering to reduce latency and dropped fast strikes.
- Fixed Full Kit default-piece routing after lazy kit loads and while MIDI remains
  active, so changing the selected kit immediately changes inherited sounds.
- Added MIDI-driven Full Kit strike animation: the mapped physical instrument
  image punches and flashes regardless of its customized sound source.

## 1.1.99 (version code 370) - 2026-07-29

- Raised the Funk kit's closed, pedal, and open hi-hats by 4 dB so all three
  articulations remain audible beside the rest of the kit.
- Applied an inverse per-note trim to every non-hat Funk voice, preserving the
  existing kick, snare, tom, and cymbal balance in Pad and Full Kit modes.

## 1.1.98 (version code 369) - 2026-07-29

- Added a bright cyan fill, animated border, colored glow, elevation, and subtle
  scale lift to active shared buttons and Piano/Looper toggle pills.
- Updated Looper Chord, Slide, Split, and Dual controls to clearly show their
  active state using the same shared treatment.
- Removed Firefly Melody's forced portamento in Piano and Looper modes; Firefly
  now slides only when the user explicitly enables Slide.

## 1.1.97 (version code 368) - 2026-07-29

- Fixed Guitar Cabinet IR selection changing its label while the previous
  convolution remained active.
- Cabinet choices now decode and load the selected WAV directly without
  redundantly reloading the NAM neural model.
- The CAB button shows loading state and only updates/persists the cabinet after
  the native engine confirms that the new IR was accepted.

## 1.1.96 (version code 367) - 2026-07-29

- Replaced white text on every opaque light landing-page card, feature button,
  Tuner card, status pill, and BETA badge with high-contrast dark text.
- Stabilized the chromatic tuner with stronger tuner-specific signal/confidence
  thresholds, three-reading acquisition, four-reading note-change hysteresis,
  brief signal-loss hold, and bounded cents-domain smoothing.

## 1.1.95 (version code 366) - 2026-07-29

- Rewired every Guitar rack level so Input, NAM Gain, EQ, cabinet level, Delay,
  Room, and final Output affect the correct DSP stage with audible ranges.
- Made Input and Output true mute-capable controls, synchronized CAB bypass with
  the default Guitar path, and persisted all six pedal controls.
- Removed the adaptive buffer shrink cycle that caused repeating underrun
  clicks, raised live-input stability to three bursts, and made backlog trimming
  less aggressive.
- Added a short-read fade so temporary microphone/interface capture shortages
  no longer create abrupt zero edges heard as `tak` or crunching.

## 1.1.94 (version code 365) - 2026-07-29

- Restored the former Guitar chorus pedal choices as Classic, Warm, and Shimmer
  presets inside the MOD signal-chain tab.
- Chorus preset selection now enables the live chorus processor, highlights the
  active choice, updates Rate and Depth controls, and persists the setting.

## 1.1.93 (version code 364) - 2026-07-29

- Reduced live Guitar input latency with exclusive-stream preference, smaller
  adaptive buffers, faster input-backlog recovery, and gradual buffer shrink
  after stable playback.
- Connected Guitar NAM Gain, Bass, Mid, Treble, and Presence controls to the
  native signal path and added the Clean Chorus - JC Wide preset.
- Fixed cabinet choices made during an active load being displayed but dropped;
  the newest selected IR now reloads after the in-flight request finishes.
- Moved NAM selection from the left rail into the Guitar PEDAL signal-chain tab.
- Added persistent highlighted PEDAL and CAB check buttons so NAM and cabinet
  processing can be bypassed without losing selections or control settings.

## 1.1.92 (version code 363) - 2026-07-29

- Added six genuinely distinct Jensen, Celestion, and ENGL cabinet responses
  for clean, warm clean, jazz, chorus, rock, and metal use, with CC BY 4.0
  attribution and license files.
- Fixed clean and jazz NAM models inheriting the metal boost and delay, and
  paired American Deluxe and JC Clean with appropriate Jensen cabinets.
- Added smoothed MIDI note-count headroom before Virtual Guitar NAM to prevent
  multi-note power chords from collapsing into glass-like intermodulation.
- Recalibrated microphone Guitar NAM input for multi-string chord headroom.
- Replaced ineffective pre-NAM Output behavior with true post-NAM controls:
  Virtual Guitar and microphone Guitar can now reach actual mute and scale to
  150% without changing preamp drive.

## 1.1.91 (version code 362) - 2026-07-28

- Rebuilt Virtual Guitar MIDI as a single-voice Keyboard A instrument with no
  Sound 2, Dual, Layers, Sustain, or Slide controls.
- Added a clickable Virtual Guitar signal chain for MIDI, Player, Drive, NAM
  Pedal, Cabinet, Modulation, Room, and Output.
- Fixed Virtual Guitar notes sustaining after key release by applying fast,
  per-note release to the main and articulation SoundFonts.
- Shared the seven bundled NAM models and 23 cabinet IRs between Virtual Guitar
  and microphone Guitar, with searchable Metal, Rock, Clean, Jazz, Chorus,
  Rhodes, and Bass-oriented labels.
- Renamed the NAM stage to Pedal in both guitar modes and added a persisted,
  native post-convolution cabinet level control from 0% to 150%.

## 1.1.90 (version code 361) - 2026-07-28

- Made NAM and cabinet processing permanent in regular Guitar mode and moved
  Tight Delay and High Gain Metal into the NAM Amp selector.
- Rebalanced Tight Delay and replaced the old fuzz front end with a tight metal
  overdrive that preserves pick attack.
- Converted the fixed signal chain into animated stage tabs with dedicated
  controls for Input, Gate, Compressor, Wah, Amp, Cabinet, Modulation, Delay,
  Room, and app Output.
- Routed Compressor and manual/USB-C/Bluetooth MIDI wah before NAM and separated
  app guitar output level from Android media volume.

## 1.1.89 (version code 360) - 2026-07-28

- Raised the built-in Guitar NAM path by a fixed 2.3 dB while preserving the
  manual Output control and avoiding automatic gain reduction.
- Preserved palm-muted chord and chug transients by removing duplicate NAM
  limiting and using bounded headroom through cabinet convolution.
- Added luminance-aware button text: dark ink on light controls and near-white
  text on dark controls, including guitar selectors and piano/looper pills.

## 1.1.88 (version code 359) - 2026-07-28

- Replaced the regular Guitar pedal selector with a searchable NAM Amp picker
  containing seven bundled models.
- Added 23 built-in, searchable cabinet IR choices, including 21 commercially
  permitted Jester Dyne Brutal and Emerald cabinet captures.
- Persisted NAM and cabinet selections and moved Chorus, Delay, Room, and final
  Volume after NAM and cabinet convolution.
- Corrected cabinet normalization and NAM output gain so the built-in rigs no
  longer play at abnormally low volume.

## 1.1.87 (version code 358) - 2026-07-28

- Added a modular real-time Electric Guitar rack with persistent compressor,
  wah, amp/EQ, cabinet, chorus, delay, room, gate, and output controls.
- Added the built-in `Tight Delay` metal rig: Screamer-style boost, compatible
  5153 NAM, Mesa 4x12 IR, and post-cab delay.
- Added the built-in `HiGain Fuzz` rig: Red Fuzz-style drive, compatible
  British high-gain NAM, and Lead 800/Celestion cabinet IR.
- Both built-in rigs load asynchronously, report their status, replace the
  built-in amp rather than double-amping, and require a 48 kHz audio route.
- Included source attribution and MIT license files for bundled NAM/IR assets.

## 1.1.86 (version code 357) - 2026-07-28
Fix landing-page contrast with an opaque medium-blue brand rail and white branding, headings, READY state, instrument names, descriptions, tags, tuner text, and feature-card labels. Retain dark text in light dialogs and editor panels for readability.

## 1.1.85 (version code 356) - 2026-07-28
Restore the original Chord Mode board and defaults and remove the progression replacement control. Add Joyous, Funky, and Lively strictly as Piano Play Modes that change note order, timing, accents, and dynamics without changing the player’s selected chords. Retain long-press timing adjustment and automatic piano voice leading.

## 1.1.84 (version code 355) - 2026-07-28
Lighten animated button faces while retaining white labels and rotating borders. Add Joyous Funk, Sunny Disco, Neo Soul Lift, and Lively Gospel progression presets. Rebuild six-note Piano chords with a dedicated bass, upper extensions kept out of the muddy register, and automatic inversion-based voice leading that retains common tones and minimizes movement across progression slots.

## 1.1.83 (version code 354) - 2026-07-28
Replace Chord Mode’s binary Strum toggle with Piano Play Modes: Block, Studio, Rolled, Reverse, Ballad, Arpeggio, and Strum. Add studio-style timing and velocity variation with bass and melody emphasis, while retaining the long-press 1-1000 ms interval editor for timed modes.

## 1.1.82 (version code 353) - 2026-07-28
Improve animated-control readability with opaque dark-blue faces and white labels, including selected chord entries. Extend pitch bend and modulation through all eight Piano layers, including external SF2 layers. Replace estimated mixer activity bars with true per-channel SoundFont signal metering that follows rendered waveform energy, attack, sustain, release, and layer volume.

## 1.1.81 (version code 352) - 2026-07-28
Add the cyan-to-blue-to-white app gradient and opaque animated neon button borders while preserving the Full Kit studio background. Add searchable external SF2 bank/program selection with metadata caching, complete Piano performance scenes for external sounds and eight layers, performance diagnostics and route checks, globally accessible MIDI foot-control learning, and immersive performance lock.

## 1.1.80 (version code 351) - 2026-07-27
Make swell/swirl cymbals polyphonically retriggerable from pads and MIDI: rapid hits are counted and each starts an independent five-layer swell while earlier swells continue ringing, with eight overlapping groups in a fixed real-time voice pool.

## 1.1.79 (version code 350) - 2026-07-27
Fix the Rock 3 pedal hi-hat across Pad Mode, Full Kit, custom pieces, previews, and availability checks; reduce Piano pitch-bend travel to the centered middle 50% with 25% clearance above and below.

## 1.1.78 (version code 349) - 2026-07-27
Show the active external SF2 consistently across full keyboard, Chord Mode, mixer, layers, and Piano controls; reopen sound pickers at the external selection; and add a persistent 1-1000 ms Strum interval control on long press.

## 1.1.77 (version code 348) - 2026-07-27
Fix nested WaveNet NAM support, prioritize and serialize external SoundFont loading, restore external picker position, keep Virtual Guitar on an electric-guitar fallback during reload, and set Chord Mode Strum spacing to 30 ms.

## 1.1.76 (version code 347) - 2026-07-27
Fix A2 Native SlimmableContainer NAM loading. Virtualize large external SF2 catalogs, expose external sounds in keyboard, Chord Mode, and Layer Mode, lazily load selected fonts with moving progress, preload all eight configured layer channels sequentially, and change Strum spacing to 10 ms with a simplified label.

## 1.1.75 (version code 346) - 2026-07-27
Add six-note gradient chord highlighting, optional 2 ms low-to-high strumming with safe cancellation, and a 59-quality chord catalog. Expand swell cymbals to six choices and play five separately triggered 5 ms layers with progressive 105-125% gain.

## 1.1.74 (version code 345) - 2026-07-27
Fix Full Kit default pieces so they preserve the selected Pad Mode kit's exact SoundFont program, alternate preset, genre remap, and metal drive while retaining independent per-piece customization. Restore the normal Pad Mode engine route immediately when Full Kit closes. Improve drum audio fidelity by matching Full Kit metal gain to Pad Mode, keeping clean drum and chime buses linear, and reducing the metallic comb filtering of the required three-layer 5 ms swell stack while preserving the original 16-bit/48 kHz WAV sources.

## 1.1.73 (version code 344) - 2026-07-27
Consolidate Drum MIDI Assignment to one Swell Cymbal entry with a selectable Swell 1-4 sound. Add all four swell WAVs to Full Kit Mode's Cymbal sound list, preserve each piece's selected swell variant, and improve the three-layer 5 ms stacked playback clarity by removing per-layer nonlinear clipping.

## 1.1.72 (version code 343) - 2026-07-27
Upgrade Full Kit slot export/import from position-only files to complete versioned kit snapshots. New `.kit` files preserve the selected drum kit, per-piece sound sources and notes, complete MIDI assignments, input channels, gain, pan, custom-kit state, room/cymbal levels, velocity behavior, and snare/rim setting while retaining compatibility with legacy `bandapp-kit/1` layouts.

## 1.1.71 (version code 342) - 2026-07-27
Add the beta Virtual Guitar MIDI landing instrument and dedicated MIDI-guitar workspace. Remove and hard-bypass the guitar pedal controls from regular Piano, keep Piano and Virtual Guitar preset history separate, filter the new workspace to guitar sounds, and bundle the CC0 FreePats Fender starter bank for immediate testing through the guitar amp/cab rig.

## 1.1.70 (version code 341) - 2026-07-27
Add persistent external Piano SF2 folders with searchable Sound 1/Sound 2 loading; fix the hidden Keyboard B live-control tab; and add a guitar-only MIDI pedalboard with Clean, Crunch, Lead, and Metal preamps, five cabinet voicings, drive/tone controls, and velocity-sensitive harmonic enhancement. Include a lightweight CC0 FreePats Fender clean-electric-guitar SF2 as a separate release download for pedal testing.

## 1.1.69 (version code 340) - 2026-07-26
Fix silent drum kits with loading and missing-note fallbacks; combine Piano pitch and rightward vibrato into one range-aware lever with a +/-2 semitone default; replace the Full Kit background with a true overhead wood floor and rectangular mat; add MIDI Assignment sound audition controls; animate the Dual Sound 2 browser stretch; and add read-only CPU/RAM monitoring.

## 1.1.68 (version code 339) - 2026-07-26
Complete the sky-blue bubbly redesign across Guitar, Bass, Piano, Drums, Looper controls, and Android system bars. Fix Piano Split live controls for Keyboard A/B, add configurable 6-20 full-screen chord slots, add dynamic Full Kit default-piece routing with genre remaps, bundle a permanent photorealistic studio background, and replace the combined piano expression control with independent animated Pitch and Vibrato/Modulation levers.

## 1.1.67 (version code 338) - 2026-07-26
Replace the Drum screen black pad fills with rounded light sky-blue pads and cloudy-blue disabled states while retaining accent hit feedback.

## 1.1.66 (version code 337) - 2026-07-26
Extend the sky-blue bubbly redesign to Piano and Drums, including rounded control rails, play-surface framing, and Piano stage panels.

## 1.1.65 (version code 336) - 2026-07-26
Redesign the app landing page as a sky-blue instrument workspace with a structured BandApp rail, clearer instrument header, and refined card layout.

## 1.1.64 (version code 335) - 2026-07-26
Preserve three-or-more-finger piano chords when Android cancels the touch stream for a system multi-finger gesture.

## 1.1.63 (version code 334) - 2026-07-26
Add four MIDI-assignable WAV swell cymbals. Each trigger stacks three balanced layers at 0, 5, and 10 milliseconds for a dense cymbal bloom.

## 1.1.62 (version code 333) - 2026-07-26
Add WAV drum-library choices to Custom Kit MIDI assignments with per-piece source-note routing. Add an assignable MIDI Chimes row that triggers the bundled chimes.wav one-shot.

## 1.1.61 (version code 332) - 2026-07-26
Custom Kit MIDI assignments now use the complete searchable drum-kit catalog, matching the full kit selector. Assigned pieces lazy-load their selected SoundFont.

## 1.1.60 (version code 331) - 2026-07-26
Replace the two piano expression levers with one live pitch-bend and right-side vibrato controller. Expand the on-screen bend range to plus or minus two octaves.
