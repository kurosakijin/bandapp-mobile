# Instrumental Attachment

Native Android prototype for live instrument tone processing:

- Electric guitar: USB-C audio interface input with pedal-inspired overdrive, distortion, fuzz, and metal presets.
- Bass: USB-C audio interface input with common bass preamp, drive, fuzz, filter, and synth-style presets.
- Piano: USB MIDI input or USB-C audio input playing **real sampled** General MIDI instruments (polyphonic) from a bundled SoundFont.
- Input routes: guitar and bass require a USB-C audio interface; piano is MIDI or USB-C audio only. The internal microphone is intentionally not used, to avoid feedback.

The app opens on an instrument picker (Guitar / Bass / Piano). Selecting one transitions to that instrument's live screen, which shows a status dot — green while the engine is running — and an inline warning banner. If no audio port is detected, the engine refuses to start (or stops) and prompts you to plug in your audio port; if the input level pins near clipping for about a second, the engine treats it as feedback and shuts itself off.

Piano sound comes from a bundled General MIDI SoundFont (`app/src/main/assets/instrument.sf3`) rendered in real time by [TinySoundFont](https://github.com/schellingb/TinySoundFont). MIDI note-on/note-off (from a USB MIDI keyboard or on-screen key taps) drive the sampler **polyphonically**, so chords ring. USB-C audio mode estimates the played pitch and drives a single SoundFont voice (still monophonic). The "Choose Sound" picker offers ~52 named sounds across 9 categories — including a **Signature Tones** set that approximates iconic expensive-keyboard sounds (Yamaha CP80, Suitcase Rhodes, Wurlitzer 200A, DX7, Hammond B3 + Leslie, Funky Clavinet, Korg M1 house piano) for matching popular-song parts. Each preset maps to a SoundFont instrument plus a small per-preset effects layer (tone/brightness tilt, drive/overdrive, chorus, tremolo) so presets that share a sample still sound distinct. These approximate the *character* of those instruments — they are not sample-exact reproductions, which would require the original (proprietary) keyboard libraries. If the SoundFont asset is missing, the engine falls back to the built-in FM/electric/organ/bell synthesis families.

### Optional dedicated sample fonts (per family)

The engine loads the GM SoundFont plus up to three optional **dedicated** SF2/SF3 fonts and routes each piano-preset family to the right one (falling back to GM per slot if the file is absent):

| Asset | Used by presets |
| --- | --- |
| `app/src/main/assets/grand.sf2`  | Concert / Studio / Mellow / Rock Grand, Nord Pop Grand, House Piano |
| `app/src/main/assets/rhodes.sf2` | Stage Tine, Suitcase 73, FM Rhodes, Dyno, LA Ballad, DX7, Glassy E.P., Suitcase Rhodes |
| `app/src/main/assets/wurli.sf2`  | Wurlitzer 200A, Amped Reed, Tremolo Wurly |
| `app/src/main/assets/clav.sf2`   | Funky Clavinet, Clav Funk, Wah-Clav |

Bundled by default: a dedicated grand (16 MB), **Rhodes Mark I** (67 MB), **Wurlitzer 200** (24 MB) and **Clavinet D6** (51 MB), so the acoustic-grand and electric-piano families play real sampled instruments. All HQ fonts load into RAM at startup (~170 MB total) — fine on modern phones; if a low-RAM device struggles, switch to lazy per-preset loading. To swap any of them (e.g. a higher-quality grand), drop an SF2/SF3 at the path above (same filename) — auto-detected on launch, no code change. Good sources: public-domain "Clean Piano" (`archive.org/download/clean-piano/Clean%20Piano.sf2`), CC-BY Yamaha C5 / Steinway (Soundfonts4U), and the electric-piano SF2 set at sites.google.com/view/sf2-instruments. The per-preset FX layer (tone/drive/chorus/tremolo) is applied on top of whichever font is active. *(Verify each soundfont's license before redistributing the app.)*

## Credits and Licenses

- [TinySoundFont](https://github.com/schellingb/TinySoundFont) — SoundFont synthesizer (MIT License).
- [stb_vorbis](https://github.com/nothings/stb) — Ogg Vorbis decoder for SF3 samples (public domain / MIT).
- `instrument.sf3` is **FluidR3Mono_GM** by Frank Wen, distributed under the MIT License (as shipped with MuseScore).

## UI Preview

The app opens on an instrument picker:

![Instrument picker](ui-preview.png)

| Electric Guitar | Piano (key detection) | Sound picker (piano) |
| --- | --- | --- |
| ![Guitar](ui-preview-guitar.png) | ![Piano keys](ui-preview-piano.png) | ![Program picker](ui-preview-modal.png) |

Mockups rendered from the current native layout. Every instrument has an app bar (menu → instrument picker, centered title, status dot) and a pinned Start/Stop footer.

- **Guitar / bass:** live IN/OUT meters, a signal-chain strip (Mic → Gate → Drive → Amp → Cab → …), six vertical control faders, a preset bar (★ favorite · current preset · SAVE), and a two-column pedalboard filtered by **All / Favorites** tabs (favorites persist across launches).
- **Piano:** a key detector — it lights up the keys being played (from MIDI note-on/off, the nearest note when driven by USB-C audio, or your taps) and lists the held note names. The current sound is shown in a tappable **SOUND** bar that opens a searchable, categorized **Choose Sound** modal of ~44 curated, named presets.

The status dot turns green while running; an inline amber banner ("please plug your audio port") appears when no audio interface is detected.

## Build Requirements

- Android Studio with JDK 17.
- Android SDK 37.
- Android NDK and CMake 3.22.1.
- Gradle 9.4.1 or newer compatible with Android Gradle Plugin 9.2.1.

Open this folder in Android Studio and let it sync the Gradle project. The native audio dependency is pulled from Google Maven:

```gradle
implementation "com.google.oboe:oboe:1.10.0"
```

## Hardware Notes

- A wired USB-C audio interface is required for guitar and bass; the engine will not start without one.
- Avoid Bluetooth output for live playing; it adds too much latency.
- The internal microphone is not used for guitar and bass — this prevents the mic/speaker feedback loop.
- If piano USB-C audio is selected and no USB audio input is detected, the app will not start the engine.
- If piano MIDI is selected and no USB MIDI keyboard is detected, the app will not start the engine.
- The engine includes a feedback watchdog: sustained near-clipping input automatically stops it with a warning.

## Current Architecture

- `MainActivity.java`: instrument picker, per-instrument control surface, status/feedback indicator, permissions, USB attach/detach handling.
- `AudioDeviceRouter.java`: picks USB or default Android audio devices.
- `NativeAudioEngine.java`: JNI wrapper.
- `native_engine.cpp`: Oboe full-duplex input/output and DSP.

## Live Controls

The dedicated live-control panel uses six vertical faders (sliders); drag a fader up or down to update the native DSP immediately:

- Guitar: gain, bass, mid, treble, presence, and volume.
- Bass: gain, low, low-mid, high-mid, blend, and level.
- Piano: attack, tone, modulation, decay, space, and level.

The live meter animates from the input level and pitch reported by the native audio engine.

## Next DSP Milestones

- Replace the simple monophonic MIDI/audio piano synth with a polyphonic synth engine.
- Add real sound design for all 128 General MIDI programs.
- Add cabinet simulation and EQ blocks for guitar/bass presets.
- Add calibration for input gain and measured round-trip latency.
- Persist user presets.
