package com.instrumental.attachment;

final class NativeAudioEngine {
    static {
        System.loadLibrary("instrumental_engine");
    }

    // Playable key range of each GM program in the bundled font (host-measured:
    // lowest/highest key that actually produces sound). Keys outside are folded
    // back in by octaves so no key on screen is ever silent.
    private static final int[] GM_LO = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 1, 0, 14, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 13, 2, 0, 0, 0, 0, 11, 0, 0, 0, 0,
        2, 0, 0, 0, 0, 0, 0, 30, 0, 29, 19, 0, 0, 19, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 58, 0, 0, 0, 1, 1, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 55, 0, 0, 0, 0, 0, 80, 0, 0,
    };
    private static final int[] GM_HI = {
        108, 108, 108, 108, 108, 108, 127, 108, 109, 127, 108, 108, 108, 127, 108, 108,
        127, 108, 108, 96, 108, 108, 96, 108, 127, 127, 96, 127, 84, 108, 108, 85,
        127, 84, 84, 84, 84, 84, 127, 96, 103, 96, 117, 81, 97, 96, 108, 127,
        100, 97, 109, 127, 96, 96, 127, 96, 108, 96, 72, 96, 96, 96, 127, 127,
        96, 87, 127, 127, 96, 85, 84, 127, 120, 127, 127, 127, 127, 127, 127, 108,
        127, 127, 109, 127, 127, 127, 127, 127, 110, 108, 127, 127, 127, 109, 127, 109,
        108, 110, 111, 110, 127, 125, 98, 110, 108, 127, 127, 127, 127, 127, 103, 127,
        108, 108, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127, 127,
    };

    private int noteLo = 0, noteHi = 127;     // piano-screen fold range
    private int keysLo = 0, keysHi = 127;     // looper-keys fold range
    // Dual sound: notes >= split play sound 2, which folds by its own range.
    private int noteSplit = -1, note2Lo = 0, note2Hi = 127;
    private int keysSplit = -1, keys2Lo = 0, keys2Hi = 127;

    // program = the GM program about to play, or -1 for HQ fonts (no folding).
    void setNoteFoldProgram(int program) {
        noteLo = program >= 0 && program < 128 ? GM_LO[program] : 0;
        noteHi = program >= 0 && program < 128 ? GM_HI[program] : 127;
    }

    void setLoopKeysFoldProgram(int program) {
        keysLo = program >= 0 && program < 128 ? GM_LO[program] : 0;
        keysHi = program >= 0 && program < 128 ? GM_HI[program] : 127;
    }

    // Deterministic per note, so the matching note-off folds identically.
    private static int fold(int note, int lo, int hi) {
        while (note < lo) note += 12;
        while (note > hi) note -= 12;
        if (note < lo) note = (lo + hi) / 2;   // range narrower than an octave
        return Math.max(0, Math.min(127, note));
    }

    // Dual-aware fold: each side folds by its own sound's range and stays on
    // its own side of the split so notes don't leak across.
    private static int foldRouted(int note, int split, int lo1, int hi1, int lo2, int hi2) {
        if (split >= 0 && note >= split) {
            int n = fold(note, lo2, hi2);
            while (n < split && n + 12 <= hi2) n += 12;
            return n;
        }
        int n = fold(note, lo1, hi1);
        if (split >= 0) {
            while (n >= split && n - 12 >= lo1) n -= 12;
        }
        return n;
    }

    // Dual sound config: split point + sound 2's GM program (-1 = dual off).
    void setKeySplitConfig(int splitKey, int program2) {
        noteSplit = program2 >= 0 ? splitKey : -1;
        note2Lo = program2 >= 0 && program2 < 128 ? GM_LO[program2] : 0;
        note2Hi = program2 >= 0 && program2 < 128 ? GM_HI[program2] : 127;
        nativeSetKeySplit(noteSplit);
    }

    void setLoopKeysSplitConfig(int splitKey, int program2) {
        keysSplit = program2 >= 0 ? splitKey : -1;
        keys2Lo = program2 >= 0 && program2 < 128 ? GM_LO[program2] : 0;
        keys2Hi = program2 >= 0 && program2 < 128 ? GM_HI[program2] : 127;
        nativeSetLoopKeySplit(keysSplit);
    }

    boolean start(InstrumentMode mode, TonePreset preset, InputRoute route, int inputDeviceId, int outputDeviceId) {
        return nativeStart(mode.nativeId, preset.nativeId, route.nativeId, inputDeviceId, outputDeviceId);
    }

    void stop() {
        nativeStop();
    }

    // Tuner: instrument id 4 (kTuner), microphone route (1). Input device -1 lets
    // Android pick the default (internal mic, or USB-C if it's the active input).
    boolean startTuner(int inputDeviceId, int outputDeviceId) {
        return nativeStart(4, 0, 1, inputDeviceId, outputDeviceId);
    }

    void setPreset(InstrumentMode mode, TonePreset preset, InputRoute route, int inputDeviceId, int outputDeviceId) {
        nativeSetPreset(mode.nativeId, preset.nativeId, route.nativeId, inputDeviceId, outputDeviceId);
    }

    boolean isRunning() {
        return nativeIsRunning();
    }

    float inputLevel() {
        return nativeInputLevel();
    }

    float outputLevel() {
        return nativeOutputLevel();
    }

    float pitchHz() {
        return nativePitchHz();
    }

    String status() {
        return nativeStatus();
    }

    // Measured output-path latency in ms (buffer + DSP + DAC), 0 until known.
    float outputLatencyMs() {
        return nativeOutputLatencyMs();
    }

    void setControls(float control1, float control2, float control3, float control4, float control5, float control6) {
        nativeSetControls(control1, control2, control3, control4, control5, control6);
    }

    void noteOn(int note, float velocity) {
        nativeNoteOn(foldRouted(note, noteSplit, noteLo, noteHi, note2Lo, note2Hi), velocity);
    }

    void noteOff(int note) {
        nativeNoteOff(foldRouted(note, noteSplit, noteLo, noteHi, note2Lo, note2Hi));
    }

    // Piano dual Sound 2: straight to the layer channel (Java-side routing,
    // used for hardware MIDI so the split always lands on the right sound).
    void note2On(int note, float velocity) {
        nativeNote2On(fold(note, note2Lo, note2Hi), velocity);
    }

    void note2Off(int note) {
        nativeNote2Off(fold(note, note2Lo, note2Hi));
    }

    boolean loadSoundFont(byte[] data) {
        return nativeLoadSoundFont(data);
    }

    void setMidiProgram(int program) {
        nativeSetMidiProgram(program);
    }

    // Panic: release every melodic voice (sound switches, screen teardown).
    void allNotesOff() {
        nativeAllNotesOff();
    }

    // Layered preset: second GM program doubling every note (-1 = off).
    void setMidiLayer(int program) {
        nativeSetMidiLayer(program);
    }

    void setLayer3(int program) { nativeSetLayer3(program); }
    void setLayer4(int program) { nativeSetLayer4(program); }
    void setLayer5(int program) { nativeSetLayer5(program); }
    void setLayer6(int program) { nativeSetLayer6(program); }
    void setLayer7(int program) { nativeSetLayer7(program); }
    void setLayer8(int program) { nativeSetLayer8(program); }
    void setLayerFontSlot(int channel, int slot) { nativeSetLayerFontSlot(channel, slot); }
    void setLayerVolume(int idx, float vol) { nativeSetLayerVolume(idx, vol); }
    void setLayerBlend(int mode, int note) { nativeSetLayerBlend(mode, note); }
    void setLayerZap(int ch, int semis) { nativeSetLayerZap(ch, semis); }
    void getLayerMeters(float[] out) { nativeGetLayerMeters(out); }

    void setLoopKeysLayer(int program) {
        nativeSetLoopKeysLayer(program);
    }

    void setDrumKit(int kit) {
        nativeSetDrumKit(kit);
    }

    // Genre note-remap: 0 none, 1 808, 2 reggae, 3 mambo, 4 beatbox.
    void setDrumRemap(int id) {
        nativeSetDrumRemap(id);
    }

    void setCustomDrum(boolean on) {
        nativeSetCustomDrum(on);
    }

    void setDrumPieceSlot(int note, int slot) {
        nativeSetDrumPieceSlot(note, slot);
    }

    void setDrumPieceSrcNote(int note, int srcNote) {
        nativeSetDrumPieceSrcNote(note, srcNote);
    }

    void previewDrum(int slot, int note) {
        nativePreviewDrum(slot, note);
    }

    void setDrumPieceGain(int note, float gain) {
        nativeSetDrumPieceGain(note, gain);
    }

    void setDrumPiecePan(int note, float pan) {
        nativeSetDrumPiecePan(note, pan);
    }

    void setSustain(boolean on) {
        nativeSetSustain(on);
    }

    void setSustainPedal(boolean down) {
        nativeSetSustainPedal(down);
    }

    void setPitchWheel(int value) {
        nativeSetPitchWheel(value);
    }
    void setPitchWheelA(int value) { nativeSetPitchWheelA(value); }
    void setPitchWheelB(int value) { nativeSetPitchWheelB(value); }
    void setVibrato(float depth) { nativeSetVibrato(depth); }
    void setVibratoA(float depth) { nativeSetVibratoA(depth); }
    void setVibratoB(float depth) { nativeSetVibratoB(depth); }

    // On-screen note bender for the looper keys (0..16383, 8192 = center).
    void setLoopKeysBend(int value) {
        nativeSetLoopKeysBend(value);
    }

    // Bender range in semitones at full throw (1..24).
    void setBendRange(int semis) {
        nativeSetBendRange(semis);
    }

    // Sound 2 = an HQ font slot (custom/sampled fonts); -1 = GM program.
    void setDualFontSlot(int slot) {
        nativeSetDualFontSlot(slot);
    }

    // Separate dual mode: these notes play Sound 2 directly (fold by its range).
    void loopKey2On(int note, float velocity) {
        nativeLoopKey2On(fold(note, keys2Lo, keys2Hi), velocity);
    }

    void loopKey2Off(int note) {
        nativeLoopKey2Off(fold(note, keys2Lo, keys2Hi));
    }

    void setReverb(boolean on) {
        nativeSetReverb(on);
    }

    void setSustainTime(float seconds) {
        nativeSetSustainTime(seconds);
    }

    void setReverbLevel(float level) {
        nativeSetReverbLevel(level);
    }

    void setDrumRoom(float level) {
        nativeSetDrumRoom(level);
    }

    // Per-group cymbal volume (0 = hi-hat, 1 = ride, 2 = crash).
    void setCymbalGain(int group, float gain) {
        nativeSetCymbalGain(group, gain);
    }

    // Guitar/bass noise gate threshold (0 = off).
    void setNoiseGate(float threshold) {
        nativeSetNoiseGate(threshold);
    }

    // Silence all ringing cymbals (cymbal choke).
    void chokeCymbals() {
        nativeChokeCymbals();
    }

    void setMidiVolume(int value) {
        nativeSetMidiVolume(value);
    }

    void setMidiExpression(int value) {
        nativeSetMidiExpression(value);
    }

    void setMetronome(boolean on, int bpm, int beatsPerBar) {
        nativeSetMetronome(on, bpm, beatsPerBar);
    }

    void resetMetronome() {
        nativeResetMetronome();
    }

    void startRecording(String path) {
        nativeStartRecording(path);
    }

    void stopRecording() {
        nativeStopRecording();
    }

    boolean isRecording() {
        return nativeIsRecording();
    }

    boolean loadMidi(byte[] data) {
        return nativeLoadMidi(data);
    }

    void midiPlay() {
        nativeMidiPlay();
    }

    void midiPause() {
        nativeMidiPause();
    }

    void midiStop() {
        nativeMidiStop();
    }

    void midiSetLoop(boolean on) {
        nativeMidiSetLoop(on);
    }

    boolean midiIsPlaying() {
        return nativeMidiIsPlaying();
    }

    float midiPositionMs() {
        return nativeMidiPositionMs();
    }

    float midiDurationMs() {
        return nativeMidiDurationMs();
    }

    long midiActiveLow() {
        return nativeMidiActiveLow();
    }

    long midiActiveHigh() {
        return nativeMidiActiveHigh();
    }

    // Loop Mix: instrument id 5 (kLoopMix), microphone route (1).
    boolean startLoopMix(int inputDeviceId, int outputDeviceId) {
        return nativeStart(5, 0, 1, inputDeviceId, outputDeviceId);
    }

    // Vocals: instrument id 6 (kVocals) — live mic through the vocal FX only.
    boolean startVocals(int inputDeviceId, int outputDeviceId) {
        return nativeStart(6, 0, 1, inputDeviceId, outputDeviceId);
    }

    // Guitar Keys: instrument id 7 (kGuitarKeys) — guitar audio in, piano out.
    boolean startGuitarKeys(int inputDeviceId, int outputDeviceId) {
        return nativeStart(7, 0, 1, inputDeviceId, outputDeviceId);
    }

    // Hear the live mic in the output (loops record it either way).
    void setLoopMonitor(boolean on) {
        nativeSetLoopMonitor(on);
    }

    // cmd: 1 = tap (rec/play/overdub cycle), 2 = pause toggle, 3 = clear.
    void loopCommand(int track, int cmd) {
        nativeLoopCommand(track, cmd);
    }

    int loopState(int track) {
        return nativeLoopState(track);
    }

    float loopPos(int track) {
        return nativeLoopPos(track);
    }

    float loopLenMs(int track) {
        return nativeLoopLenMs(track);
    }

    int loopLastOverdub() {
        return nativeLoopLastOverdub();
    }

    // 1 = pause all loops, 2 = resume all together from position 0.
    void loopGlobal(int cmd) {
        nativeLoopGlobal(cmd);
    }

    void loopWave(int track, float[] out) {
        nativeLoopWave(track, out);
    }

    void setHarmonizer(boolean on) {
        nativeSetHarmonizer(on);
    }

    // "Input off": the capture stream still opens but reads as silence.
    void setInputMute(boolean mute) {
        nativeSetInputMute(mute);
    }

    // Autotune: the live voice is snapped to the nearest semitone (Loop Mix).
    void setAutotune(boolean on) {
        nativeSetAutotune(on);
    }

    // Vocals screen: reverb amount over the whole vocal channel (0..1).
    void setVocalReverb(float level) {
        nativeSetVocalReverb(level);
    }

    // Piano slide mode: a key pressed while another is held bends the
    // sounding note to the new pitch (string bend / slide) instead of
    // re-attacking. Rate in semitones per second.
    void setPianoGlide(boolean on) {
        nativeSetPianoGlide(on);
    }

    void setPianoGlideRate(float semisPerSec) {
        nativeSetPianoGlideRate(semisPerSec);
    }

    // Same slide behavior for the Loop Mix keys channel.
    void setLoopKeysGlide(boolean on) {
        nativeSetLoopKeysGlide(on);
    }

    // Mono slide: overlapped presses slide; a detached press silences the
    // previous voice instead of stacking tails (stylophone / mono synth).
    void setPianoGlideMono(boolean on) {
        nativeSetPianoGlideMono(on);
    }

    void setLoopKeysGlideMono(boolean on) {
        nativeSetLoopKeysGlideMono(on);
    }

    // Guitar Keys: polyphonic (chords) vs monophonic (fastest) tracking.
    void setGuitarKeysPoly(boolean on) {
        nativeSetGkPoly(on);
    }

    // Guitar Keys: semitones added to every played note (bass = -12).
    void setGuitarKeysTranspose(int semis) {
        nativeSetGkTranspose(semis);
    }

    // Guitar Keys mono mode: pitch wheel rides the guitar's exact pitch so
    // string bends, vibrato and slides carry into the synthesized sound.
    void setGuitarKeysBendFollow(boolean on) {
        nativeSetGkBendFollow(on);
    }

    // Guitar Keys poly mode: currently detected notes as a bitmask,
    // bit i = MIDI note 36 + i.
    long guitarKeysNotes() {
        return nativeGkNotes();
    }

    // Capture device for the instrument line-in feeding loops 1-3 (-1 = none).
    // Applied when the Loop Mix engine (re)starts.
    void setLoopInstDevice(int deviceId) {
        nativeSetLoopInstDevice(deviceId);
    }

    // Per track: 0 = record until tapped; N = auto-close after N bars (hands-free).
    void setLoopRecBars(int track, int bars) {
        nativeSetLoopRecBars(track, bars);
    }

    // Loop Mix keys: melodic notes on the loop bus — they play live and print
    // into loops 1-3 exactly like the drum pads.
    void loopKeyOn(int note, float velocity) {
        nativeLoopKeyOn(foldRouted(note, keysSplit, keysLo, keysHi, keys2Lo, keys2Hi), velocity);
    }

    void loopKeyOff(int note) {
        nativeLoopKeyOff(foldRouted(note, keysSplit, keysLo, keysHi, keys2Lo, keys2Hi));
    }

    // GM program (0-127) for the looper keys sound.
    void setLoopKeysProgram(int program) {
        nativeSetLoopKeysProgram(program);
    }

    // Route the looper keys to an HQ piano font slot (-1 = GM font + program).
    void setLoopKeysSlot(int slot) {
        nativeSetLoopKeysSlot(slot);
    }

    // Manual wah pedal on the guitar chain: on/off + sweep position 0..1.
    void setWah(boolean on) {
        nativeSetWah(on);
    }

    void setWahPos(float pos) {
        nativeSetWahPos(pos);
    }

    // Guitar cabinet / IR pedal: on/off, cab voicing 0-4, dry↔cab blend 0..1.
    void setGuitarCab(boolean on, int type, float mix) {
        nativeSetGuitarCab(on, type, mix);
    }

    void setGuitarRackFx(boolean compOn, float compAmount,
                         boolean modOn, float modRate, float modDepth,
                         boolean delayOn, float delayTime, float delayFeedback, float delayMix,
                         boolean roomOn, float roomMix) {
        nativeSetGuitarRackFx(compOn, compAmount, modOn, modRate, modDepth,
                delayOn, delayTime, delayFeedback, delayMix, roomOn, roomMix);
    }

    void setBuiltInMetalRigFx(int style, float drive, float tone, float level,
                              float delayTime, float delayFeedback, float delayMix) {
        nativeSetBuiltInMetalRigFx(style, drive, tone, level,
                delayTime, delayFeedback, delayMix);
    }

    // Global mono output — sum L+R on every instrument (mixer / mono PA safe).
    void setMonoOutput(boolean on) {
        nativeSetMonoOutput(on);
    }

    // Fire the one-shot chime (Drums). No-op while it's still sounding.
    void triggerChimes() {
        nativeTriggerChimes();
    }

    void triggerSwell(int index) { nativeTriggerSwell(index); }

    // True once the selected drum kit's sound is loaded and ready to play.
    boolean drumKitReady() {
        return nativeDrumKitReady();
    }

    // Whether a drum pad (GM note) maps to a sound in the current kit (for
    // disabling pads a kit doesn't voice).
    boolean drumNoteHasSound(int note) {
        return nativeDrumNoteHasSound(note);
    }

    // Load the Chimes one-shot from decoded PCM (interleaved float, -1..1).
    void loadChimeSample(float[] data, int frames, int channels, int rate) {
        nativeLoadChimeSample(data, frames, channels, rate);
    }

    void loadSwellSample(int index, float[] data, int frames, int channels, int rate) {
        nativeLoadSwellSample(index, data, frames, channels, rate);
    }

    // Harmony voices in semitones (99 = voice off), optional detuned choir pair,
    // mix level 0..1.5, tone 0 flat / 1 warm / 2 bright, reverb send on/off.
    void setHarmonizerParams(float semi1, float semi2, boolean choir, float level, int tone, boolean reverb) {
        nativeSetHarmonizerParams(semi1, semi2, choir, level, tone, reverb);
    }

    boolean loopSave(int track, String path) {
        return nativeLoopSave(track, path);
    }

    void setPianoFx(float tone, float drive, float chorus, float tremolo) {
        nativeSetPianoFx(tone, drive, chorus, tremolo);
    }
    void setPianoSoft(float soft) { nativeSetPianoSoft(soft); }
    void setPianoFxB(float tone, float drive, float chorus, float trem) { nativeSetPianoFxB(tone, drive, chorus, trem); }
    void setPianoGuitarRig(boolean onA, boolean onB, int amp, int cab,
            float drive, float tone, float harmonics) {
        nativeSetPianoGuitarRig(onA, onB, amp, cab, drive, tone, harmonics);
    }
    void setVirtualGuitarPlayer(boolean on) { nativeSetVirtualGuitarPlayer(on); }
    boolean loadNamModel(byte[] data) { return nativeLoadNamModel(data); }
    void setNam(boolean on, float mix, float inputGain, float outputGain) {
        nativeSetNam(on, mix, inputGain, outputGain);
    }
    boolean namReady() { return nativeNamReady(); }
    float namExpectedRate() { return nativeNamExpectedRate(); }
    boolean loadNamIr(float[] data, int frames, int channels, int rate) {
        return nativeLoadNamIr(data, frames, channels, rate);
    }
    void setNamIr(boolean on) { nativeSetNamIr(on); }
    void setNamIrLevel(float level) { nativeSetNamIrLevel(level); }
    boolean namIrReady() { return nativeNamIrReady(); }
    void setPianoSoftB(float soft) { nativeSetPianoSoftB(soft); }
    void setLevelB(float lvl) { nativeSetLevelB(lvl); }
    void setManualSplit(boolean on) { nativeSetManualSplit(on); }

    boolean loadHqFont(int slot, int presetNumber, float gainDb, byte[] data) {
        return nativeLoadHqFont(slot, presetNumber, gainDb, data);
    }

    String[] hqFontPresetNames(int slot) {
        return nativeHqFontPresetNames(slot);
    }

    boolean setHqFontPreset(int slot, int presetIndex) {
        return nativeSetHqFontPreset(slot, presetIndex);
    }

    boolean loadDrumFont(int slot, float gainDb, byte[] data) {
        return nativeLoadDrumFont(slot, gainDb, data);
    }

    void setFontSlot(int slot) {
        nativeSetFontSlot(slot);
    }

    private native boolean nativeStart(int instrument, int tone, int inputRoute, int inputDeviceId, int outputDeviceId);

    private native void nativeStop();

    private native void nativeSetPreset(int instrument, int tone, int inputRoute, int inputDeviceId, int outputDeviceId);

    private native boolean nativeIsRunning();

    private native float nativeInputLevel();

    private native float nativeOutputLevel();

    private native float nativePitchHz();

    private native String nativeStatus();

    private native float nativeOutputLatencyMs();

    private native void nativeSetControls(
            float control1,
            float control2,
            float control3,
            float control4,
            float control5,
            float control6
    );

    private native void nativeNoteOn(int note, float velocity);

    private native void nativeNoteOff(int note);

    private native boolean nativeLoadSoundFont(byte[] data);

    private native void nativeSetMidiProgram(int program);

    private native void nativeAllNotesOff();

    private native void nativeSetKeySplit(int note);

    private native void nativeSetLoopKeySplit(int note);

    private native void nativeSetMidiLayer(int program);
    private native void nativeSetLayer3(int program);
    private native void nativeSetLayer4(int program);
    private native void nativeSetLayer5(int program);
    private native void nativeSetLayer6(int program);
    private native void nativeSetLayer7(int program);
    private native void nativeSetLayer8(int program);
    private native void nativeSetLayerFontSlot(int channel, int slot);
    private native void nativeSetLayerVolume(int idx, float vol);
    private native void nativeSetLayerBlend(int mode, int note);
    private native void nativeSetLayerZap(int ch, int semis);
    private native void nativeGetLayerMeters(float[] out);

    private native void nativeSetLoopKeysLayer(int program);

    private native void nativeSetDrumKit(int kit);
    private native void nativeSetDrumRemap(int id);

    private native void nativeSetCustomDrum(boolean on);

    private native void nativeSetDrumPieceSlot(int note, int slot);
    private native void nativeSetDrumPieceSrcNote(int note, int srcNote);
    private native void nativePreviewDrum(int slot, int note);

    private native void nativeSetDrumPieceGain(int note, float gain);

    private native void nativeSetDrumPiecePan(int note, float pan);

    private native void nativeSetSustain(boolean on);

    private native void nativeSetSustainPedal(boolean down);

    private native void nativeSetPitchWheel(int value);
    private native void nativeSetPitchWheelA(int value);
    private native void nativeSetPitchWheelB(int value);
    private native void nativeSetVibrato(float depth);
    private native void nativeSetVibratoA(float depth);
    private native void nativeSetVibratoB(float depth);

    private native void nativeSetLoopKeysBend(int value);

    private native void nativeSetBendRange(int semis);

    private native void nativeSetDualFontSlot(int slot);

    private native void nativeLoopKey2On(int note, float velocity);

    private native void nativeLoopKey2Off(int note);

    private native void nativeSetReverb(boolean on);

    private native void nativeSetSustainTime(float seconds);

    private native void nativeSetReverbLevel(float level);

    private native void nativeSetDrumRoom(float level);

    private native void nativeSetCymbalGain(int group, float gain);

    private native void nativeSetNoiseGate(float threshold);

    private native void nativeChokeCymbals();

    private native void nativeSetMidiVolume(int value);

    private native void nativeSetMidiExpression(int value);

    private native void nativeSetMetronome(boolean on, int bpm, int beatsPerBar);
    private native void nativeResetMetronome();

    private native void nativeStartRecording(String path);

    private native void nativeStopRecording();

    private native boolean nativeIsRecording();

    private native boolean nativeLoadMidi(byte[] data);

    private native void nativeMidiPlay();

    private native void nativeMidiPause();

    private native void nativeMidiStop();

    private native void nativeMidiSetLoop(boolean on);

    private native boolean nativeMidiIsPlaying();

    private native float nativeMidiPositionMs();

    private native float nativeMidiDurationMs();

    private native long nativeMidiActiveLow();

    private native long nativeMidiActiveHigh();

    private native void nativeLoopCommand(int track, int cmd);

    private native int nativeLoopState(int track);

    private native float nativeLoopPos(int track);

    private native float nativeLoopLenMs(int track);

    private native int nativeLoopLastOverdub();

    private native void nativeLoopGlobal(int cmd);

    private native void nativeSetLoopMonitor(boolean on);

    private native void nativeLoopWave(int track, float[] out);

    private native void nativeSetHarmonizer(boolean on);

    private native void nativeSetInputMute(boolean mute);

    private native void nativeSetAutotune(boolean on);

    private native void nativeSetVocalReverb(float level);

    private native void nativeSetPianoGlide(boolean on);

    private native void nativeSetPianoGlideRate(float rate);

    private native void nativeSetLoopKeysGlide(boolean on);

    private native void nativeSetPianoGlideMono(boolean on);

    private native void nativeSetLoopKeysGlideMono(boolean on);

    private native void nativeNote2On(int note, float velocity);

    private native void nativeNote2Off(int note);

    private native void nativeSetGkPoly(boolean on);

    private native void nativeSetGkTranspose(int semis);

    private native void nativeSetGkBendFollow(boolean on);

    private native long nativeGkNotes();

    private native void nativeSetLoopInstDevice(int deviceId);

    private native void nativeSetLoopRecBars(int track, int bars);

    private native void nativeLoopKeyOn(int note, float velocity);

    private native void nativeLoopKeyOff(int note);

    private native void nativeSetLoopKeysProgram(int program);

    private native void nativeSetLoopKeysSlot(int slot);

    private native void nativeSetWah(boolean on);

    private native void nativeSetGuitarCab(boolean on, int type, float mix);

    private native void nativeSetGuitarRackFx(boolean compOn, float compAmount,
                                              boolean modOn, float modRate, float modDepth,
                                              boolean delayOn, float delayTime,
                                              float delayFeedback, float delayMix,
                                              boolean roomOn, float roomMix);

    private native void nativeSetBuiltInMetalRigFx(int style, float drive, float tone, float level,
                                                    float delayTime, float delayFeedback,
                                                    float delayMix);

    private native void nativeSetMonoOutput(boolean on);

    private native void nativeTriggerChimes();
    private native void nativeTriggerSwell(int index);

    private native boolean nativeDrumKitReady();
    private native boolean nativeDrumNoteHasSound(int note);

    private native void nativeLoadChimeSample(float[] data, int frames, int channels, int rate);
    private native void nativeLoadSwellSample(int index, float[] data, int frames, int channels, int rate);

    private native void nativeSetWahPos(float pos);

    private native void nativeSetHarmonizerParams(float semi1, float semi2, boolean choir, float level, int tone, boolean reverb);

    private native boolean nativeLoopSave(int track, String path);

    private native void nativeSetPianoFx(float tone, float drive, float chorus, float tremolo);
    private native void nativeSetPianoSoft(float soft);
    private native void nativeSetPianoFxB(float tone, float drive, float chorus, float trem);
    private native void nativeSetPianoGuitarRig(boolean onA, boolean onB,
            int amp, int cab, float drive, float tone, float harmonics);
    private native void nativeSetVirtualGuitarPlayer(boolean on);
    private native boolean nativeLoadNamModel(byte[] data);
    private native void nativeSetNam(boolean on, float mix, float inputGain, float outputGain);
    private native boolean nativeNamReady();
    private native float nativeNamExpectedRate();
    private native boolean nativeLoadNamIr(float[] data, int frames, int channels, int rate);
    private native void nativeSetNamIr(boolean on);
    private native void nativeSetNamIrLevel(float level);
    private native boolean nativeNamIrReady();
    private native void nativeSetPianoSoftB(float soft);
    private native void nativeSetLevelB(float lvl);
    private native void nativeSetManualSplit(boolean on);

    private native boolean nativeLoadHqFont(int slot, int presetNumber, float gainDb, byte[] data);
    private native String[] nativeHqFontPresetNames(int slot);
    private native boolean nativeSetHqFontPreset(int slot, int presetIndex);

    private native boolean nativeLoadDrumFont(int slot, float gainDb, byte[] data);

    private native void nativeSetFontSlot(int slot);
}
