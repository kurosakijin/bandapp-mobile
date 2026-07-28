package com.instrumental.attachment;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.media.MediaPlayer;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbManager;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 1001;

    // Sky-blue workspace: light enough for long sessions, with blue selection
    // states and dark ink text that stay legible around dense instrument controls.
    private static final int COLOR_BACKGROUND = Color.rgb(232, 246, 255);
    private static final int COLOR_SURFACE = Color.rgb(241, 250, 255);
    private static final int COLOR_SURFACE_RAISED = Color.rgb(255, 255, 255);
    private static final int COLOR_SURFACE_PRESSED = Color.rgb(214, 239, 252);
    private static final int COLOR_BORDER = Color.rgb(182, 216, 234);
    private static final int COLOR_BORDER_STRONG = Color.rgb(126, 181, 211);
    private static final int COLOR_TEXT = Color.rgb(22, 53, 75);
    private static final int COLOR_MUTED = Color.rgb(75, 108, 130);
    private static final int COLOR_DIM = Color.rgb(111, 145, 166);
    private static final int COLOR_TEAL = Color.rgb(20, 151, 207);
    private static final int COLOR_AMBER = Color.rgb(220, 143, 48);
    private static final int COLOR_RED = Color.rgb(205, 75, 82);
    private static final int COLOR_GREEN = Color.rgb(35, 158, 109);
    private static final int COLOR_PURPLE = Color.rgb(112, 111, 209);
    private static final int COLOR_SKY_CONTROL = Color.rgb(220, 242, 253);
    private static final int COLOR_SKY_CONTROL_STRONG = Color.rgb(195, 227, 244);
    private static final int COLOR_SKY_TRACK = Color.rgb(158, 202, 226);
    private static final int COLOR_SKY_KEY_DARK = Color.rgb(48, 88, 113);

    // Feedback = the input pinned near clipping continuously. The raw
    // (Unprocessed) mic runs hotter, so a loud-but-musical bass note can peak
    // high for a moment; require a longer sustained pin so real playing no
    // longer trips the guard (which stops the engine), only an actual howl.
    private static final float FEEDBACK_DB = -0.5f;
    private static final int FEEDBACK_TICKS = 12;

    private final NativeAudioEngine engine = new NativeAudioEngine();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Button, TonePreset> presetButtons = new LinkedHashMap<>();
    private final Map<Button, InputRoute> routeButtons = new LinkedHashMap<>();

    private AudioDeviceRouter router;
    private LinearLayout presetGrid;
    private LinearLayout routeRow;
    private TextView statusText;
    private View statusDot;
    private TextView routeChipText;
    private View routeChipDot;
    private TextView errorBanner;
    private TextView toneText;
    private TextView usbText;
    private TextView deviceText;
    private TextView meterDbText;
    private TextView pianoNotesText;
    private LiveControlView liveControlView;
    private LevelMeterView inMeter;
    private LevelMeterView outMeter;
    private SignalChainView signalChainView;
    private PianoKeysView pianoKeysView;
    // Full playable piano (two manuals): persisted zoom (visible white keys) and
    // scroll position (leftmost white-key index) so it reopens where you left it.
    private int melodyZoom = 14, chordZoom = 10;
    private int melodyBaseWhite = whiteIndexOf(60);
    private int chordBaseWhite = whiteIndexOf(48);
    private boolean fullPianoChord = false;  // bottom (CHORDS) manual plays diatonic triads
    // Chord quality for the keyboard Chord mode. 0 = Fingered (root = major; a
    // second key within the chord sets the quality — ♭3=min, 4=sus4, 2=sus2,
    // ♭5=dim; the fifth is auto-added). 1 = Diatonic (scale-aware). 2+ = a fixed
    // quality on every key.
    private int fullPianoChordType = 0;
    private static final int[][] CHORD_QUALITY_IV = {
            {}, {}, {0, 4, 7}, {0, 3, 7}, {0, 5, 7}, {0, 2, 7},
            {0, 4, 7, 10}, {0, 4, 7, 11}, {0, 3, 7, 10}};
    private static final String[] CHORD_QUALITY_NAME = {
            "Fingered", "Diatonic", "Maj", "min", "sus4", "sus2", "7", "maj7", "m7"};
    private boolean fullPianoSplit = false;  // Play Keys: false = one whole keyboard, true = split
    // Split style: 0 = key-split (one keyboard, sounds separate at dualSplit,
    // low = Chords, high = Melody), 1 = two-manual (stacked Keyboard A + B).
    private int fullPianoSplitStyle = 0;
    // Full Keys "Tone" softness (0 = bright/open, 1 = dark). Rolls off highs to
    // tame a screaming lead. Persisted, applied to the whole piano output.
    private float fpSoft = 0f;
    // Full-piano key-split boundary (independent of dualSplit, which Layer Mode
    // forces to -1 for whole-keyboard blending).
    private int fpSplitNote = 60;
    private KeyVizView keyVizView;
    private DrumPadsView drumPadsView;
    private DrumKitView drumKitView;   // full-image kit (landscape full pads)
    private Button startButton;
    private Button sustainButton;
    private Button reverbButton;
    private boolean sustainOn;
    private boolean reverbOn;
    private SeekBar sustainSlider;
    private SeekBar reverbSlider;
    // Manual wah pedal on the guitar screen.
    private boolean wahOn;
    private float wahPos = 0.5f;
    private Button wahButton;
    private SeekBar wahSlider;
    // Cabinet / IR pedal on the guitar screen: on by default (a raw DI sounds
    // too "physical"); slider picks the cab voicing 0-4.
    private boolean cabOn = true;
    private int cabType = 0;
    private Button cabButton;
    private SeekBar cabSlider;
    private static final String[] CAB_NAMES = {
            "4x12 Modern", "4x12 Vintage", "2x12 Combo", "1x12 Tweed", "1x15 Warm"};
    // Noise gate (guitar/bass): mutes idle hum/buzz between notes. On by default
    // (high-gain amp sims amplify the buzz); amount sets how hard it clamps down.
    private boolean gateOn = true;
    private float gateAmount = 0.35f;
    private Button gateButton;
    private SeekBar gateSlider;
    private boolean guitarCompOn = true;
    private float guitarCompAmount = 0.35f;
    private boolean guitarModOn;
    private float guitarModRate = 0.25f;
    private float guitarModDepth = 0.30f;
    private boolean guitarDelayOn;
    private float guitarDelayTime = 0.32f;
    private float guitarDelayFeedback = 0.28f;
    private float guitarDelayMix = 0.22f;
    private boolean guitarRoomOn;
    private float guitarRoomMix = 0.22f;
    private boolean guitarNamTestOn;
    private boolean guitarNamTestReady;
    private boolean guitarNamTestLoading;
    private Button guitarNamTestButton;
    private TextView guitarNamTestStatus;
    private int metalRigStyle;
    private final Button[] metalRigStyleButtons = new Button[2];
    private int guitarNamIndex;
    private int guitarCabIrIndex;
    private static final String[] GUITAR_NAM_NAMES = {
            "5153 High Gain", "British Stack", "Boutique Lead", "AC Chime",
            "American Deluxe", "Jazz Clean", "Ampeg Grind"
    };
    private static final String[] GUITAR_NAM_ASSETS = {
            "guitar_rig/metal_5153.nam", "guitar_rig/fuzz_jcm.nam",
            "guitar_rig/amp_dumble.nam", "guitar_rig/amp_ac10.nam",
            "guitar_rig/amp_deluxe.nam", "guitar_rig/amp_jc.nam",
            "guitar_rig/amp_ampeg.nam"
    };
    private static final String[] GUITAR_CAB_NAMES = {
            "Mesa Modern 4x12", "Lead 800 Celestion",
            "Brutal Tight", "Brutal Edge", "Brutal Cut",
            "Brutal Body", "Brutal Dense", "Brutal Dry",
            "Brutal Blend", "Brutal Rock", "Brutal Focus",
            "Brutal Modern", "Brutal Dark", "Brutal Mid Focus",
            "Brutal Bright", "Brutal Wide", "Brutal Aggressive",
            "Greenback Warm", "Greenback Edge", "Greenback Bright",
            "Greenback Cream", "Greenback Body", "Greenback Cut"
    };
    private static final String[] GUITAR_CAB_ASSETS = {
            "guitar_rig/metal_mesa_4x12.wav", "guitar_rig/fuzz_lead_800.wav",
            "guitar_rig/cabinets/brutal_a.wav", "guitar_rig/cabinets/brutal_b.wav",
            "guitar_rig/cabinets/brutal_c.wav", "guitar_rig/cabinets/brutal_d.wav",
            "guitar_rig/cabinets/brutal_e.wav", "guitar_rig/cabinets/brutal_f.wav",
            "guitar_rig/cabinets/brutal_g.wav", "guitar_rig/cabinets/brutal_h.wav",
            "guitar_rig/cabinets/brutal_i.wav", "guitar_rig/cabinets/brutal_j.wav",
            "guitar_rig/cabinets/brutal_k.wav", "guitar_rig/cabinets/brutal_l.wav",
            "guitar_rig/cabinets/brutal_m.wav", "guitar_rig/cabinets/brutal_n.wav",
            "guitar_rig/cabinets/brutal_o.wav",
            "guitar_rig/cabinets/greenback_warm.wav",
            "guitar_rig/cabinets/greenback_edge.wav",
            "guitar_rig/cabinets/greenback_bright.wav",
            "guitar_rig/cabinets/greenback_cream.wav",
            "guitar_rig/cabinets/greenback_body.wav",
            "guitar_rig/cabinets/greenback_cut.wav"
    };
    private float metalBoostDrive = 0.34f;
    private float metalBoostTone = 0.52f;
    private float metalBoostLevel = 0.72f;
    private float metalDelayTime = 0.28f;
    private float metalDelayFeedback = 0.22f;
    private float metalDelayMix = 0.16f;
    private boolean midiPedalDown;   // hardware sustain pedal state (UI mirror)
    // Dual sound (piano keyboard): keys from the split point up play Sound 2,
    // or — in separate mode — the upper split keyboard plays Sound 2 whole.
    private boolean dualOn;
    private boolean dualSeparate;
    private TonePreset dualPreset = TonePreset.PIANO_STRINGS;
    private int dualSplit = 60;
    // Looper keys keep their OWN dual sound, independent of the piano keyboard.
    private boolean loopDualOn;
    private TonePreset loopDualPreset = TonePreset.PIANO_STRINGS;
    private int loopDualSplit = 60;
    private Button dualButton;
    // Extra keyboard layers 3 & 4 (GM programs blended on top of Sound 1/2),
    // each with a 0..1 level. Layer 2's level is the existing Sound 2 (dual).
    // Layers 3 & 4 pick from the FULL keyboard roster (by preset name); they
    // render the GM voice of that sound on their own channel. null = off.
    // Two independent 4-layer keyboards. Keyboard A = layers 1-4 (master L1 =
    // Sound 1, adds L2/L3/L4). Keyboard B = layers 5-8 (master L5 = Sound 2,
    // adds L6/L7/L8). A and B never share sounds. Added layers pick GM voices.
    private String layer2Preset = null, layer3Preset = null, layer4Preset = null;
    private String layer6Preset = null, layer7Preset = null, layer8Preset = null;
    // Masters at full, added layers at 0.
    private float layer1Level = 1.0f, layer2Level = 0f, layer3Level = 0f, layer4Level = 0f;
    private float layer5Level = 1.0f, layer6Level = 0f, layer7Level = 0f, layer8Level = 0f;
    // Per-layer attack "zap" depth in semitones (0 = off). Indexed by layer 1..8.
    private final int[] layerZap = new int[9];
    private static final int[] ZAP_STEPS = {0, 12, 24};   // Off / Zap / Zap+
    // Side-B levels: the second stack blended on the other half of a split
    // keyboard (Chords / Keyboard B). Same 4 sounds, independent levels.
    private float layer1LevelB = 0f, layer2LevelB = 0f, layer3LevelB = 0f, layer4LevelB = 0f;
    // Remembers the Dual state before entering Layer Mode, so leaving it restores
    // whether Dual was on rather than sticking it on.
    private boolean dualOnBeforeLayers = false;
    // Layer Mode: when on, all 4 layers blend across the whole keyboard and the
    // split is forced off (mutually exclusive with the Dual split). Off = the
    // normal single-sound / Dual + Split behaviour, layers 3 & 4 muted.
    private boolean layerMode = false;
    private int bendRange = 2;   // standard pitch-wheel default; adjustable from 1..24
    private TransportIconView recButton;
    private TransportIconView metroButton;
    private TextView bpmButton;
    private TextView sigButton;
    private boolean metronomeOn = false;
    private boolean countInOn = false;   // one bar of clicks before recording starts
    private int metronomeBpm = 120;
    private int timeSigNum = 4;
    private int timeSigDen = 4;
    // Preferred output sink as an AudioDeviceInfo type (-1 = auto). Persisted by
    // type, not device id — ids change across replug/reboot, types don't.
    private int preferredOutputType = -1;
    private int preferredInputType = -1;
    private String preferredInputName = "";
    // Global mono output (sum L+R) — one setting for all instruments, for mixer
    // and mono-PA rigs where a stereo feed drops panned/phase content.
    private boolean monoOutput;
    private String lastRecPath;
    private MediaPlayer recPlayer;
    private static final int REQ_PICK_MIDI = 4201;
    private static final int REQ_EXPORT_BACKUP = 4202;
    private static final int REQ_IMPORT_BACKUP = 4203;
    private static final int REQ_EXPORT_KIT = 4204;
    private static final int REQ_IMPORT_KIT = 4205;
    private static final int REQ_EXPORT_CHORDS = 4206;
    private static final int REQ_IMPORT_CHORDS = 4207;
    private static final int REQ_PICK_SF2_FOLDER = 4208;
    private boolean midiLoopOn = false;
    private TextView midiProgressText;
    private TextView midiPlayPauseBtn;
    private Runnable midiProgressTick;
    private String midiNowPlaying = "—";
    private float sustainTime = 2.0f;   // seconds before a sustained note auto-releases
    private static final float MAX_REVERB_LEVEL = 0.7f;   // engine clamp for the wet mix
    private float reverbLevel = 0.30f;  // reverb wet mix
    private View pickerSelectedRow;
    private TextView soundBarText;
    private LinearLayout sound2Bar;
    private TextView sound2BarText;
    private TextView soundLoadingText;
    private ShineBar soundLoadingBar;
    private ShineBar drumLoadingBar;   // shows while the selected kit's font loads
    // The piano browser replaces only the right-side performance pane. Keeping
    // it in place avoids a modal interrupting MIDI performance controls.
    private LinearLayout pianoPane;
    private LinearLayout pianoContentHost;
    private View pianoPerformancePane;
    private LinearLayout pianoBrowserHost;   // fixed left-rail sound list
    private boolean pianoSoundBrowserOpen;
    private boolean pianoBrowserDualLayout;
    private TextView pianoBrowserTitle;
    private EditText pianoBrowserSearch;
    private LinearLayout pianoBrowserSound1List;
    private LinearLayout pianoBrowserSound2List;
    private TextView pianoBrowserSf2Status;
    private View pianoBrowserSound2Column;
    private View pianoBrowserDivider;
    private android.animation.ValueAnimator pianoBrowserStretch;
    private final Map<View, TonePreset> pianoSound1Rows = new LinkedHashMap<>();
    private final Map<View, TonePreset> pianoSound2Rows = new LinkedHashMap<>();
    private final Map<View, String> externalSound1Rows = new LinkedHashMap<>();
    private final Map<View, String> externalSound2Rows = new LinkedHashMap<>();
    private final java.util.List<ExternalSf2File> externalSf2Files =
            new java.util.ArrayList<>();
    private final java.util.Map<String, String[]> externalSf2PresetCache =
            new java.util.HashMap<>();
    private final java.util.List<ExternalNamFile> externalNamFiles =
            new java.util.ArrayList<>();
    private final java.util.List<ExternalIrFile> externalIrFiles =
            new java.util.ArrayList<>();
    private final java.util.concurrent.ThreadPoolExecutor externalSf2Loader =
            new java.util.concurrent.ThreadPoolExecutor(
                    1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.PriorityBlockingQueue<>());
    private final java.util.concurrent.ExecutorService guitarArticulationLoader =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ExecutorService namLoader =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private String externalSf2TreeUri;
    private String activeExternalMainUri;
    private String activeExternalDualUri;
    private int activeExternalMainPreset;
    private int activeExternalDualPreset;
    private final int[] activeExternalLayerPreset = new int[9];
    private String loadedExternalMainUri;
    private String loadedExternalDualUri;
    private String loadingExternalMainUri;
    private String loadingExternalDualUri;
    private int externalMainScrollPosition;
    private int externalDualScrollPosition;
    private final int[] externalLayerScrollPosition = new int[9];
    private int soundLoadsInFlight;
    private int externalMainLoadToken;
    private int externalDualLoadToken;
    private final int[] externalLayerLoadToken = new int[9];
    private final String[] loadedExternalLayerUri = new String[9];
    private final String[] loadingExternalLayerUri = new String[9];
    private int externalScanToken;
    private String loadedGuitarPalmUri;
    private String loadedGuitarHarmUri;
    private boolean guitarArticulationsLoading;
    private String activeNamUri;
    private String loadedNamUri;
    private boolean namLoading;
    private String activeNamIrUri;
    private String loadedNamIrUri;
    private boolean namIrLoading;
    private volatile boolean soundFontReady;
    private TextView favStar;
    private LinearLayout pedalTabsRow;
    private final boolean[] keyOn = new boolean[128];
    private final Set<String> favorites = new HashSet<>();
    private boolean showFavoritesOnly;
    private SharedPreferences prefs;

    private InstrumentMode currentMode = InstrumentMode.ELECTRIC_GUITAR;
    // This workspace reuses the proven Piano MIDI transport internally while
    // keeping guitar sounds and performance controls out of Piano mode.
    private boolean virtualGuitarMidiMode;
    private TonePreset currentPreset = TonePreset.defaultFor(currentMode);
    private InputRoute currentRoute = InputRoute.USB;
    private boolean onInstrumentScreen;
    private String currentError;
    private int feedbackTicks;
    private final float[] liveControlValues = new float[]{0.62f, 0.52f, 0.58f, 0.56f, 0.60f, 0.72f};
    // Live Controls B (Keyboard B / Sound 2) — independent FX, only when B is on.
    private final float[] liveControlValuesB = new float[]{0.62f, 0.52f, 0.58f, 0.56f, 0.60f, 0.72f};
    private LiveControlView liveControlViewB;
    private int liveTab = 0;   // 0 = Keyboard A, 1 = Keyboard B
    private TextView liveTabAButton;
    private TextView liveTabBButton;
    private View pianoGuitarRigPanel;
    private Button pianoGuitarRigButton;
    private Button pianoGuitarAmpButton;
    private Button pianoGuitarCabButton;
    private Button pianoGuitarDriveButton;
    private Button pianoGuitarToneButton;
    private Button pianoGuitarHarmButton;
    private Button pianoGuitarMarkButton;
    private Button virtualGuitarPlayerButton;
    private Button pianoGuitarNamButton;
    private Button pianoGuitarNamModelButton;
    private Button pianoGuitarNamMixButton;
    private Button pianoGuitarNamInputButton;
    private Button pianoGuitarNamOutputButton;
    private Button pianoGuitarNamIrButton;
    private Button pianoGuitarNamIrModelButton;
    private boolean pianoGuitarRigOn;
    private boolean virtualGuitarPlayerOn = true;
    private int pianoGuitarAmp = 1;
    private int pianoGuitarCab = 0;
    private float pianoGuitarDrive = 0.55f;
    private float pianoGuitarTone = 0.58f;
    private float pianoGuitarHarmonics = 0.35f;
    private boolean pianoGuitarNamOn;
    private float pianoGuitarNamMix = 1.0f;
    private float pianoGuitarNamInputDb;
    private float pianoGuitarNamOutputDb = -3.0f;
    private boolean pianoGuitarNamIrOn;
    private final Set<String> guitarSf2Uris = new HashSet<>();
    private static final String[] PIANO_GUITAR_AMP_NAMES = {
            "Clean", "Crunch", "Lead", "Metal"};
    // A Mod (chorus) position set by hand, and the sound it was set for. Any
    // other preset falls back to that preset's baked chorus.
    private TonePreset modOverridePreset = null;
    private float modOverride = -1f;
    private boolean receiverRegistered;
    private MidiManager midiManager;
    // Up to two keyboards at once: player 1 = Sound 1, player 2 = Sound 2
    // (when Dual is on). Index in these lists = player number.
    private final java.util.List<MidiDevice> midiDevices = new java.util.ArrayList<>();
    private final java.util.List<MidiOutputPort> midiOutputPorts = new java.util.ArrayList<>();
    private MidiManager.DeviceCallback midiDeviceCallback;
    private boolean midiInputAvailable;
    private String midiDeviceLabel = "MIDI: not detected";

    // Per-piece drum MIDI assignment (mirrors the "MIDI Assignment" screen).
    private DrumPiece[] drumPieces;
    private static final int FIRST_EXTRA_DRUM_SLOT = 11;
    private static final int TOTAL_DRUM_FONT_SLOTS = 46;   // +6 sample-library fonts (slots 40-45)
    private final boolean[] extraDrumFontLoaded = new boolean[TOTAL_DRUM_FONT_SLOTS];
    private final boolean[] extraDrumFontLoading = new boolean[TOTAL_DRUM_FONT_SLOTS];
    private boolean drumAllChannels = true;
    private boolean drumCustomKit = false;
    private static final int CHIMES_MIDI_NOTE = 84;
    private static final int SWELL_FIRST_MIDI_NOTE = 85;
    private static final int KIT_SOUND_SWELL_BASE = 400;
    // Internal-only route used by Full Kit pieces set to Default. Carrying the
    // complete program preserves alternate presets and drive instead of reducing
    // the selected kit to a font slot.
    private static final int KIT_SOUND_SELECTED_BASE = 100000;
    private int drumSwellVariant;
    private float drumRoomLevel = 0.12f;
    private boolean padSnareRim = false;
    // Per-group cymbal volume, adjustable by long-pressing the pad. Defaults
    // match the previous fixed lift (GM cymbals sit quiet in the mix).
    private float cymGainHat = 1.15f, cymGainRide = 1.40f, cymGainCrash = 1.30f;
    // Slam-velocity thresholds (MIDI): a ride hit ≥ rideCrashVel crashes;
    // a crash hit < crashRideVel rides. Adjustable in MIDI Assignment.
    private float rideCrashVel = 0.92f, crashRideVel = 0.35f;
    // MIDI cymbal choke: a cymbal hit softer than this chokes the ringing
    // cymbals instead of striking (0 = off). Adjustable in MIDI Assignment.
    private float cymChokeVel = 0.0f;
    private TextView snareRimToggle;
    private TunerMeterView tunerMeter;
    private TextView tunerHzText;
    private Runnable tunerTick;
    private boolean drumMidiIn = true;
    private boolean onMidiAssignScreen;
    private boolean onFullKeyboard;
    private boolean onFullPiano;   // playable, zoomable two-manual piano
    private boolean onMixer;       // the layer channel-strip mixer screen
    private ChannelStripView[] mixerStrips;
    private final float[] mixerMeterBuf = new float[8];
    private Runnable mixerPoll;
    // Where the layer sound picker returns after a pick (null = the Layers sheet).
    private Runnable afterLayerPick;
    private boolean onFullPads;
    private boolean padsKitMode;   // full pads: false = grid pads, true = drawn kit
    private boolean kitEditMode;   // Kit Mode editor: add / move / resize / remove pieces
    private boolean onChordMode;   // full-screen strummable chord board
    private int kitLayoutSlot;     // active kit-layout preset (0..4), pref "kit_slot"
    private boolean onLoopMix;
    private boolean onVocalsScreen;
    // Guitar Keys: guitar audio converted live to piano notes.
    private boolean onGuitarKeys;
    private TonePreset gkPreset = TonePreset.PIANO_CONCERT_GRAND;
    private int gkRevAmount = 20;
    private boolean gkPoly = true;   // hear chords (poly) vs fastest single-note
    private int gkOct = 0;           // octave shift: -1 = play as bass
    private boolean gkBendFollow = true;   // mono: string bends carry into the sound
    private boolean gkBassMode;      // bass mode: only bass sounds offered, -1 oct
    private TextView gkNoteText;
    // Piano slide mode: legato keys bend to the new pitch instead of re-attacking.
    // Mono variant: detached presses also silence the previous voice (one voice
    // at a time, stylophone-style) — only overlapped presses slide.
    private boolean pianoGlideOn;
    private boolean pianoGlideMono;
    private boolean loopKeysSlideMono;
    private int pianoGlideRate = 60;   // semitones per second
    // Looper chord mode over MIDI: the exact notes struck per key, so the
    // note-off releases precisely what the note-on started.
    private final int[][] midiChordHeld = new int[128][];
    // Per-port MIDI parser state. Keyboards with chord/auto functions send
    // note bursts under running status (status byte sent once, then omitted);
    // messages can also split across onSend callbacks. Without this state the
    // omitted-status note-offs are dropped and chord notes ring forever.
    private final int[] midiRunStatus = new int[8];
    private final int[] midiPendData = new int[8];
    private final boolean[] midiHasData = new boolean[8];
    private final boolean[] midiInSysEx = new boolean[8];
    // Two keyboards: swap which one is player 1 (Sound 1) / player 2 (Sound 2).
    private boolean midiSwapPlayers;
    private int vocalRevAmount = 25;   // Vocals screen reverb, 0..100
    private VocalMeterView vocalMeter;
    private boolean onTunerScreen;
    private int loopKitProgram = -1;
    private int loopKitRemap;   // genre note-remap for the looper's drum kit
    private boolean loopMonitorOn;
    private int instInType = -2;     // instrument source for loops 1-3 (-2 = off)
    private String instInName = "";
    private final int[] loopRecBars = new int[4];   // per loop: 0 = manual, 1..4 bars
    private TextView loopMetroPill;
    // Harmonizer voicing, pedal-style (see applyHarmonizerParams).
    private int harmMode = 1;        // mode 1..11 picks the companion voices
    private int harmKey;             // 0 = off; 1..5 transpose up the scale (A→B .. A→F)
    private boolean harmSharp;       // ♯: raise the harmony one semitone
    private boolean harmAutotune;    // snap the live voice to the nearest semitone
    private int harmTone;            // 0 flat, 1 warm, 2 bright
    private boolean harmReverb;
    private int harmLevel = 75;      // harmony mix 0..100
    // Foot pedals: 4 track pedals + 1 pause/resume-all pedal. A binding is a
    // keycode (<1000), a MIDI note (1000+n) or a MIDI CC (2000+n); -1 = unbound.
    private final int[] pedalBind = {-1, -1, -1, -1, -1};
    private int pedalLearn = -1;
    private TextView[] pedalRows;
    private final boolean[] pedalHoldFired = new boolean[5];
    private final Runnable[] pedalHoldRunnable = new Runnable[5];
    private TextView loopKitButton;
    private TextView loopSound2Pill;
    // Looper pad area: drum pads (default) or a 4-6 key mini keyboard that
    // records into loops 1-3 the same way.
    private boolean loopPadsKeys;
    private TonePreset loopKeysPreset = TonePreset.PIANO_CONCERT_GRAND;   // looper keys sound
    private int loopKeysBase = 60;        // window start, always a C (fixed C-to-C octave)
    private int loopKeysMelodyBase = 72;  // split mode: upper keyboard's own window
    private boolean loopKeysChord;        // one key plays a full triad
    private boolean loopKeysSlide;        // legato keys bend instead of re-attacking
    private boolean loopKeysSplit;        // double keys: chords below, melody above
    private LinearLayout loopKeysMelodyNav;
    private TextView loopKeysMelodyLabel;
    private TextView dualKeysPill;
    private LoopKeysView loopKeysView;
    private LinearLayout loopPadHost;
    private TextView loopKeysRangeLabel;
    private boolean harmonizerOn;
    private final LoopRingView[] loopRings = new LoopRingView[5];   // 0 harm, 1 vocals, 2..4 loops
    private TextView loopPauseAllButton;
    private final TextView[] loopMuteChips = new TextView[4];
    private int armedPieceIndex = -1;
    private TextView midiAssignStatus;
    private LinearLayout midiAssignList;
    private EditText assignNoteField;
    private TextView cpuUsageText;
    private TextView ramUsageText;
    private TextView audioUsageText;
    private long performanceWallMs;
    private long performanceCpuMs;

    private final Runnable meterPump = new Runnable() {
        @Override
        public void run() {
            refreshMeter();
            refreshPerformanceMonitor();
            handler.postDelayed(this, 250);
        }
    };

    // Fast poll: turn the MIDI-file player's active-note bitmask into key on/off
    // events so the keyboard + landscape visualizer follow file playback too.
    private long midiMaskLo, midiMaskHi;
    private final Runnable midiNotePump = new Runnable() {
        @Override
        public void run() {
            pollMidiPlayerNotes();
            handler.postDelayed(this, 33);
        }
    };

    private void pollMidiPlayerNotes() {
        long lo = engine.midiActiveLow();
        long hi = engine.midiActiveHigh();
        long dLo = lo ^ midiMaskLo;
        long dHi = hi ^ midiMaskHi;
        for (int b = 0; b < 64 && dLo != 0; b++) {
            long m = 1L << b;
            if ((dLo & m) != 0) setKeyPressed(b, (lo & m) != 0);
        }
        for (int b = 0; b < 64 && dHi != 0; b++) {
            long m = 1L << b;
            if ((dHi & m) != 0) setKeyPressed(64 + b, (hi & m) != 0);
        }
        midiMaskLo = lo;
        midiMaskHi = hi;
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? "" : intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)
                    || UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                refreshDeviceStatus();
                restartForDeviceChangeIfNeeded();
            }
        }
    };

    // Phones render the dense landscape rig too tight ("very fitting"), while
    // tablets have room. Lower the effective DPI on smaller screens so the whole
    // UI — spacing, controls, text, custom views — scales down uniformly and
    // breathes. Tablets (smallest width ≥ 600dp) keep native density.
    // Device's recommended auto scale %, computed pre-override so the UI Scale
    // dialog can show/restore it even after the density has been overridden.
    private static int sAutoScalePct = 100;

    @Override
    protected void attachBaseContext(Context base) {
        Configuration cfg = base.getResources().getConfiguration();
        int sw = cfg.smallestScreenWidthDp;
        // Tablets (≥600dp) have room; phones render the dense landscape rig too
        // tight, so shrink more the smaller they are (Black Shark 5 ≈ 393dp → 85%).
        int autoPct = sw >= 600 ? 100 : (sw <= 340 ? 80 : (sw < 400 ? 85 : 88));
        sAutoScalePct = autoPct;
        int userPct = base.getSharedPreferences("instrumental", Context.MODE_PRIVATE)
                .getInt("ui_scale_pct", 0);
        int pct = (userPct >= 60 && userPct <= 100) ? userPct : autoPct;
        if (pct >= 100) {
            super.attachBaseContext(base);
            return;
        }
        Configuration override = new Configuration(cfg);
        override.densityDpi = Math.round(
                base.getResources().getDisplayMetrics().densityDpi * (pct / 100f));
        super.attachBaseContext(base.createConfigurationContext(override));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterPerformanceFullscreen();
        // Landscape everywhere: every screen is designed as a two-pane stage
        // layout (controls rail + play surface). sensorLandscape allows both
        // landscape directions.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        // Match the screen gradient so screen switches never flash a flat color.
        getWindow().setBackgroundDrawable(stageBackground());
        // Sustained performance: trade a little peak clock for STABLE clocks —
        // on throttling-happy devices this stops the mid-song downclocks that
        // starve the audio callback (underrun "tak"s).
        android.os.PowerManager pm =
                (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isSustainedPerformanceModeSupported()) {
            getWindow().setSustainedPerformanceMode(true);
        }
        router = new AudioDeviceRouter(this);
        midiManager = (MidiManager) getSystemService(Context.MIDI_SERVICE);
        prefs = getSharedPreferences("instrumental", Context.MODE_PRIVATE);
        externalSf2TreeUri = prefs.getString("external_sf2_tree", null);
        activeExternalMainUri = prefs.getString("external_sf2_main", null);
        activeExternalDualUri = prefs.getString("external_sf2_dual", null);
        activeExternalMainPreset = prefs.getInt("external_sf2_main_preset", 0);
        activeExternalDualPreset = prefs.getInt("external_sf2_dual_preset", 0);
        for (int layer = 1; layer <= 8; layer++) {
            activeExternalLayerPreset[layer] =
                    prefs.getInt("external_sf2_layer_" + layer + "_preset", 0);
        }
        guitarSf2Uris.addAll(prefs.getStringSet("guitar_sf2_uris", new HashSet<>()));
        pianoGuitarRigOn = prefs.getBoolean("piano_guitar_rig_on", false);
        pianoGuitarAmp = Math.max(0, Math.min(3,
                prefs.getInt("piano_guitar_amp", 1)));
        pianoGuitarCab = Math.max(0, Math.min(CAB_NAMES.length - 1,
                prefs.getInt("piano_guitar_cab", 0)));
        pianoGuitarDrive = prefs.getFloat("piano_guitar_drive", 0.55f);
        pianoGuitarTone = prefs.getFloat("piano_guitar_tone", 0.58f);
        pianoGuitarHarmonics = prefs.getFloat("piano_guitar_harmonics", 0.35f);
        activeNamUri = prefs.getString("piano_guitar_nam_uri", null);
        pianoGuitarNamOn = prefs.getBoolean("piano_guitar_nam_on", false);
        pianoGuitarNamMix = prefs.getFloat("piano_guitar_nam_mix", 1.0f);
        pianoGuitarNamInputDb = prefs.getFloat("piano_guitar_nam_input_db", 0.0f);
        pianoGuitarNamOutputDb = prefs.getFloat("piano_guitar_nam_output_db", -3.0f);
        activeNamIrUri = prefs.getString("piano_guitar_nam_ir_uri", null);
        pianoGuitarNamIrOn = prefs.getBoolean("piano_guitar_nam_ir_on", false);
        virtualGuitarPlayerOn = prefs.getBoolean("virtual_guitar_player_on", true);
        favorites.addAll(prefs.getStringSet("favorites", new HashSet<>()));
        sustainOn = prefs.getBoolean("fx_sustain_on", false);
        reverbOn = prefs.getBoolean("fx_reverb_on", false);
        sustainTime = prefs.getFloat("fx_sustain_time", 2.0f);
        reverbLevel = prefs.getFloat("fx_reverb_level", 0.30f);
        wahOn = prefs.getBoolean("guitar_wah_on", false);
        wahPos = prefs.getFloat("guitar_wah_pos", 0.5f);
        cabOn = prefs.getBoolean("guitar_cab_on", true);
        cabType = prefs.getInt("guitar_cab_type", 0);
        gateOn = prefs.getBoolean("guitar_gate_on", true);
        gateAmount = prefs.getFloat("guitar_gate_amount", 0.35f);
        guitarCompOn = prefs.getBoolean("guitar_comp_on", true);
        guitarCompAmount = prefs.getFloat("guitar_comp_amount", 0.35f);
        guitarModOn = prefs.getBoolean("guitar_mod_on", false);
        guitarModRate = prefs.getFloat("guitar_mod_rate", 0.25f);
        guitarModDepth = prefs.getFloat("guitar_mod_depth", 0.30f);
        guitarDelayOn = prefs.getBoolean("guitar_delay_on", false);
        guitarDelayTime = prefs.getFloat("guitar_delay_time", 0.32f);
        guitarDelayFeedback = prefs.getFloat("guitar_delay_feedback", 0.28f);
        guitarDelayMix = prefs.getFloat("guitar_delay_mix", 0.22f);
        guitarRoomOn = prefs.getBoolean("guitar_room_on", false);
        guitarRoomMix = prefs.getFloat("guitar_room_mix", 0.22f);
        guitarNamTestOn = prefs.getBoolean("guitar_nam_test_on", false);
        metalRigStyle = Math.max(0, Math.min(1, prefs.getInt("metal_rig_style", 0)));
        guitarNamIndex = Math.max(0, Math.min(GUITAR_NAM_NAMES.length - 1,
                prefs.getInt("guitar_nam_index", metalRigStyle == 0 ? 0 : 1)));
        guitarCabIrIndex = Math.max(0, Math.min(GUITAR_CAB_NAMES.length - 1,
                prefs.getInt("guitar_cab_ir_index", metalRigStyle == 0 ? 0 : 1)));
        metalBoostDrive = prefs.getFloat("metal_boost_drive", 0.34f);
        metalBoostTone = prefs.getFloat("metal_boost_tone", 0.52f);
        metalBoostLevel = prefs.getFloat("metal_boost_level", 0.72f);
        metalDelayTime = prefs.getFloat("metal_delay_time", 0.28f);
        metalDelayFeedback = prefs.getFloat("metal_delay_feedback", 0.22f);
        metalDelayMix = prefs.getFloat("metal_delay_mix", 0.16f);
        dualOn = prefs.getBoolean("dual_on", false);
        dualSeparate = prefs.getBoolean("dual_separate", false);
        dualSplit = prefs.getInt("dual_split", 60);
        loopDualOn = prefs.getBoolean("loop_dual_on", false);
        loopDualSplit = prefs.getInt("loop_dual_split", 60);
        try {
            loopDualPreset = TonePreset.valueOf(prefs.getString("loop_dual_preset", "PIANO_STRINGS"));
        } catch (IllegalArgumentException e) {
            loopDualPreset = TonePreset.PIANO_STRINGS;
        }
        layer2Preset = prefs.getString("layer2_preset", null);
        layer3Preset = prefs.getString("layer3_preset", null);
        layer4Preset = prefs.getString("layer4_preset", null);
        layer6Preset = prefs.getString("layer6_preset", null);
        layer7Preset = prefs.getString("layer7_preset", null);
        layer8Preset = prefs.getString("layer8_preset", null);
        layer1Level = prefs.getFloat("layer1_lvl", 1.0f);
        layer2Level = prefs.getFloat("layer2_lvl", 0f);
        layer3Level = prefs.getFloat("layer3_lvl", 0f);
        layer4Level = prefs.getFloat("layer4_lvl", 0f);
        layer5Level = prefs.getFloat("layer5_lvl", 1.0f);
        layer6Level = prefs.getFloat("layer6_lvl", 0f);
        layer7Level = prefs.getFloat("layer7_lvl", 0f);
        layer8Level = prefs.getFloat("layer8_lvl", 0f);
        for (int i = 1; i <= 8; i++) layerZap[i] = prefs.getInt("layer" + i + "_zap", 0);
        // Fresh install: preload a built-in Stage Rig into Keyboard A's extra
        // layers (strings + bells + pad) so enabling Layer Mode gives an instant
        // piano-and-strings workstation. Silent until Layer Mode is turned on.
        if (!prefs.getBoolean("stage_rig_seeded", false)) {
            layer2Preset = TonePreset.RIG_LASTRING_SLOW.name();
            layer3Preset = TonePreset.RIG_D_BELLS.name();
            layer4Preset = TonePreset.RIG_C_PAD.name();
            layer2Level = 0.60f; layer3Level = 0.40f; layer4Level = 0.55f;
            prefs.edit()
                    .putString("layer2_preset", layer2Preset)
                    .putString("layer3_preset", layer3Preset)
                    .putString("layer4_preset", layer4Preset)
                    .putFloat("layer2_lvl", layer2Level)
                    .putFloat("layer3_lvl", layer3Level)
                    .putFloat("layer4_lvl", layer4Level)
                    .putBoolean("stage_rig_seeded", true)
                    .apply();
        }
        layer1LevelB = prefs.getFloat("layer1b_lvl", 0f);
        layer2LevelB = prefs.getFloat("layer2b_lvl", 0f);
        layer3LevelB = prefs.getFloat("layer3b_lvl", 0f);
        layer4LevelB = prefs.getFloat("layer4b_lvl", 0f);
        layerMode = prefs.getBoolean("layer_mode", false);
        dualOnBeforeLayers = prefs.getBoolean("dual_on_prelayer", false);
        fullPianoSplitStyle = prefs.getInt("fp_split_style", 0);
        fpSoft = prefs.getFloat("fp_soft", 0f);
        for (int i = 0; i < 6; i++) liveControlValuesB[i] = prefs.getFloat("lcb" + i, liveControlValuesB[i]);
        fpSplitNote = prefs.getInt("fp_split_note", 60);
        // Migrate existing installs away from the former +/-2-octave default.
        // The marker keeps later user-selected ranges persistent.
        if (!prefs.getBoolean("bend_default_2_migrated", false)) {
            bendRange = 2;
            prefs.edit()
                    .putInt("bend_range", bendRange)
                    .putBoolean("bend_default_2_migrated", true)
                    .apply();
        } else {
            bendRange = prefs.getInt("bend_range", 2);
        }
        engine.setBendRange(bendRange);
        applyDualFontRouting();
        try {
            dualPreset = TonePreset.valueOf(prefs.getString("dual_preset", "PIANO_STRINGS"));
        } catch (IllegalArgumentException e) {
            dualPreset = TonePreset.PIANO_STRINGS;
        }
        loadAudioPrefs();
        loopKitProgram = prefs.getInt("loop_kit", -1);
        loopKitRemap = prefs.getInt("loop_kit_remap", 0);
        loopPadsKeys = prefs.getBoolean("loop_pads_keys", false);
        try {
            loopKeysPreset = TonePreset.valueOf(
                    prefs.getString("loop_keys_preset", "PIANO_CONCERT_GRAND"));
        } catch (IllegalArgumentException e) {
            loopKeysPreset = TonePreset.PIANO_CONCERT_GRAND;
        }
        loopKeysBase = prefs.getInt("loop_keys_base", 60);
        loopKeysMelodyBase = prefs.getInt("loop_keys_mel_base", 72);
        loopKeysChord = prefs.getBoolean("loop_keys_chord", false);
        loopKeysSlide = prefs.getBoolean("loop_keys_slide", false);
        loopKeysSplit = prefs.getBoolean("loop_keys_split", false);
        loopMonitorOn = prefs.getBoolean("loop_monitor", false);
        instInType = prefs.getInt("inst_in_type", -2);
        instInName = prefs.getString("inst_in_name", "");
        for (int t = 1; t <= 3; t++) {
            loopRecBars[t] = prefs.getInt("loop_rec_bars_" + t, 0);
        }
        vocalRevAmount = prefs.getInt("vocal_rev", 25);
        gkRevAmount = prefs.getInt("gk_rev", 20);
        gkPoly = prefs.getBoolean("gk_poly", true);
        gkOct = prefs.getInt("gk_oct", 0);
        gkBendFollow = prefs.getBoolean("gk_bend", true);
        pianoGlideOn = prefs.getBoolean("piano_glide", false);
        pianoGlideMono = prefs.getBoolean("piano_glide_mono", false);
        pianoGlideRate = prefs.getInt("piano_glide_rate", 60);
        fullPianoChord = prefs.getBoolean("full_piano_chord", false);
        fullPianoChordType = prefs.getInt("full_piano_chord_type", 0);
        fullPianoSplit = prefs.getBoolean("full_piano_split", false);
        countInOn = prefs.getBoolean("count_in", false);
        loopKeysSlideMono = prefs.getBoolean("loop_keys_slide_mono", false);
        gkBassMode = prefs.getBoolean("gk_bassmode", false);
        midiSwapPlayers = prefs.getBoolean("midi_swap", false);
        try {
            gkPreset = TonePreset.valueOf(prefs.getString("gk_preset", "PIANO_CONCERT_GRAND"));
        } catch (IllegalArgumentException e) {
            gkPreset = TonePreset.PIANO_CONCERT_GRAND;
        }
        padsKitMode = prefs.getBoolean("pads_kit_mode", true);
        // One-time flip: full-screen drums now open straight into the 3D kit.
        if (!prefs.getBoolean("pads_kit_v2", false)) {
            padsKitMode = true;
            prefs.edit().putBoolean("pads_kit_v2", true).putBoolean("pads_kit_mode", true).apply();
        }
        // One-time reset when the harmonizer voicing changes generation, so a
        // better default actually reaches devices that saved the old one.
        if (!prefs.getBoolean("harm_v4", false)) {
            prefs.edit().putBoolean("harm_v4", true)
                    .remove("harm_mode").remove("harm_key").remove("harm_tone")
                    .remove("harm_rev").remove("harm_level").remove("harm_duet").apply();
        }
        harmMode = prefs.getInt("harm_mode", 1);   // mode 1 = default double
        harmKey = prefs.getInt("harm_key", 0);     // 0 = key off
        harmSharp = prefs.getBoolean("harm_sharp", false);
        harmAutotune = prefs.getBoolean("harm_tune", false);
        harmTone = prefs.getInt("harm_tone", 0);
        harmReverb = prefs.getBoolean("harm_rev", false);
        harmLevel = prefs.getInt("harm_level", 75);
        for (int i = 0; i < 5; i++) {
            pedalBind[i] = prefs.getInt("pedal_bind_" + i, -1);
        }
        monoOutput = prefs.getBoolean("mono_output", false);
        engine.setMonoOutput(monoOutput);   // global, persists across restarts
        initDrumPieces();
        loadSoundFontAsync();
        scanExternalSf2Folder();

        // If the activity was recreated (e.g. returning after an app switch) the native
        // engine may still be running; reset to a clean stopped state for the picker.
        engine.stop();

        showPicker();
        setupMidiInput();
        requestAudioPermissionIfNeeded();
        registerUsbReceiver();
        handler.post(meterPump);
        handler.post(midiNotePump);
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        installPerformanceGestureGuards(view);
        enterPerformanceFullscreen();
    }

    // Immersive sticky keeps Back/Home/Recents off the instrument surface. On
    // gesture-navigation devices, a system-bar reveal is transient and the
    // left/right exclusion strips keep performance swipes from becoming Back.
    @SuppressWarnings("deprecation")
    private void enterPerformanceFullscreen() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsetsController controller =
                    getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController
                                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void installPerformanceGestureGuards(View root) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        root.post(() -> {
            int edge = dp(24);
            int width = root.getWidth();
            int height = root.getHeight();
            if (width <= edge * 2 || height <= 0) return;
            java.util.ArrayList<Rect> exclusions = new java.util.ArrayList<>(2);
            exclusions.add(new Rect(0, 0, edge, height));
            exclusions.add(new Rect(width - edge, 0, width, height));
            root.setSystemGestureExclusionRects(exclusions);
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterPerformanceFullscreen();
    }

    @Override
    public void onBackPressed() {
        if (pianoSoundBrowserOpen) {
            closePianoSoundBrowser();
            return;
        }
        if (onLoopMix) {
            exitLoopMix();
            return;
        }
        if (onVocalsScreen) {
            exitVocals();
            return;
        }
        if (onGuitarKeys) {
            exitGuitarKeys();
            return;
        }
        if (onTunerScreen) {
            exitTuner();
            return;
        }
        if (onFullKeyboard) {
            closeFullKeyboard();
            return;
        }
        if (onFullPiano) {
            closeFullPiano();
            return;
        }
        if (onChordMode) {
            closeChordMode();
            return;
        }
        if (onFullPads) {
            closeFullPads();
            return;
        }
        if (onMidiAssignScreen) {
            closeMidiAssignment();
            return;
        }
        if (onInstrumentScreen) {
            goToPicker();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Rebuild the current screen so it switches between phone (portrait) and wide (landscape) layouts.
        if (onLoopMix) {
            showLoopMix();
        } else if (onVocalsScreen) {
            showVocals();
        } else if (onGuitarKeys) {
            showGuitarKeys();
        } else if (onTunerScreen) {
            showTuner();
        } else if (onFullKeyboard) {
            showFullKeyboard();
        } else if (onFullPiano) {
            showFullPiano();
        } else if (onChordMode) {
            showChordMode();
        } else if (onFullPads) {
            showFullPads();
        } else if (onMidiAssignScreen) {
            showMidiAssignment();
        } else if (onInstrumentScreen) {
            showInstrumentScreen();
        } else {
            showPicker();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(meterPump);
        handler.removeCallbacks(midiNotePump);
        engine.stop();
        closeMidiInput();
        if (receiverRegistered) {
            unregisterReceiver(usbReceiver);
            receiverRegistered = false;
        }
        externalSf2Loader.shutdownNow();
        guitarArticulationLoader.shutdownNow();
        namLoader.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterPerformanceFullscreen();
        if (prefs != null && externalSf2TreeUri != null) {
            scanExternalSf2Folder();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO && !hasRecordAudioPermission() && onInstrumentScreen) {
            currentError = "Microphone permission required to capture your instrument.";
            updateSelectionStyles();
        }
    }

    private void showPicker() {
        onInstrumentScreen = false;
        virtualGuitarMidiMode = false;
        // Whatever screen we came from: nothing may keep ringing on the menu.
        engine.allNotesOff();
        clearInstrumentViewRefs();

        // Landscape home: brand rail on the left, instrument grid on the right.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(14));

        // --- Left rail: badge + wordmark + tuner ---
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(dp(18), dp(18), dp(18), dp(16));
        rail.setBackground(panelBackground(Color.rgb(43, 120, 165), COLOR_BORDER_STRONG));

        TextView badge = new TextView(this);
        badge.setText("♬");
        badge.setTextColor(COLOR_SURFACE_RAISED);
        badge.setTextSize(25);
        badge.setGravity(Gravity.CENTER);
        badge.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(COLOR_TEAL);
        badgeBg.setCornerRadius(dp(27));
        badge.setBackground(badgeBg);
        int badgeSz = dp(54);
        rail.addView(badge, new LinearLayout.LayoutParams(badgeSz, badgeSz));

        TextView eyebrow = labelText("INSTRUMENT WORKSPACE");
        eyebrow.setTextColor(Color.WHITE);
        rail.addView(eyebrow, topMargin(matchWrap(), 14));
        TextView title = new TextView(this);
        title.setText("BandApp");
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextSize(30);
        rail.addView(title, topMargin(matchWrap(), 2));

        TextView subtitle = new TextView(this);
        subtitle.setText("Live sound, MIDI, and loop tools");
        subtitle.setTextColor(Color.WHITE);
        subtitle.setTextSize(15);
        rail.addView(subtitle, topMargin(matchWrap(), 8));

        View rule = new View(this);
        rule.setBackgroundColor(COLOR_BORDER);
        rail.addView(rule, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)), 20));

        View railSpacer = new View(this);
        rail.addView(railSpacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        rail.addView(buildTunerCard(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(86)));

        // --- Right pane: everything on screen at once, no scrolling.
        // Top row: the four instruments. Bottom row: the three features. ---
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);

        LinearLayout gridHeader = new LinearLayout(this);
        gridHeader.setOrientation(LinearLayout.HORIZONTAL);
        gridHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView gridTitle = new TextView(this);
        gridTitle.setText("Instruments");
        gridTitle.setTextColor(Color.WHITE);
        gridTitle.setTextSize(24);
        gridTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        gridHeader.addView(gridTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView ready = new TextView(this);
        ready.setText("READY");
        ready.setTextColor(Color.WHITE);
        ready.setTextSize(11);
        ready.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        ready.setLetterSpacing(0.08f);
        ready.setGravity(Gravity.CENTER);
        ready.setPadding(dp(10), dp(6), dp(10), dp(6));
        ready.setBackground(pillBackground(Color.argb(28, Color.red(COLOR_TEAL),
                Color.green(COLOR_TEAL), Color.blue(COLOR_TEAL)), COLOR_TEAL));
        gridHeader.addView(ready, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        grid.addView(gridHeader, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        View[] instruments = {
                gridPickerCard(InstrumentMode.ELECTRIC_GUITAR, COLOR_TEAL),
                gridPickerCard(InstrumentMode.BASS, COLOR_GREEN),
                gridPickerCard(InstrumentMode.PIANO, COLOR_AMBER),
                gridPickerCard(InstrumentMode.DRUMS, COLOR_PURPLE),
                gridVirtualGuitarMidiCard()};
        for (int i = 0; i < instruments.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) lp.leftMargin = dp(10);
            row1.addView(instruments[i], lp);
        }
        grid.addView(row1, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.08f));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        View[] features = {buildLoopMixCard(), buildVocalsCard(), buildGuitarKeysCard()};
        for (int i = 0; i < features.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) lp.leftMargin = dp(10);
            row2.addView(features[i], lp);
        }
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.92f);
        row2Lp.topMargin = dp(10);
        grid.addView(row2, row2Lp);

        root.addView(rail, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 3.8f));
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 7.8f);
        gridLp.leftMargin = dp(14);
        root.addView(grid, gridLp);

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.addView(root, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));

        enablePadInsets(screen, root);
        paintStage(screen);
        setContentView(screen);
    }

    private View pickerCard(final InstrumentMode mode, String description, int accent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setMinimumHeight(dp(98));
        card.setBackground(landingButtonBackground(accent));
        card.setClickable(true);
        card.setOnClickListener(v -> launchFromLanding(card, () -> openInstrument(mode)));

        TextView name = new TextView(this);
        name.setText(mode.label);
        name.setTextColor(Color.WHITE);
        name.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        name.setTextSize(20);
        card.addView(name, matchWrap());

        TextView desc = new TextView(this);
        desc.setText(description);
        desc.setTextColor(Color.WHITE);
        desc.setTextSize(13);
        card.addView(desc, topMargin(matchWrap(), 6));
        return card;
    }

    private LinearLayout gridRow(View a, View b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lpA = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1.0f);
        lpA.rightMargin = dp(7);
        LinearLayout.LayoutParams lpB = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1.0f);
        lpB.leftMargin = dp(7);
        row.addView(a, lpA);
        row.addView(b, lpB);
        return row;
    }

    private View gridPickerCard(final InstrumentMode mode, int accent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(10), dp(10), dp(10), dp(8));
        card.setBackground(landingButtonBackground(accent));
        card.setClickable(true);
        card.setOnClickListener(v -> launchFromLanding(card, () -> openInstrument(mode)));

        InstrumentIconView icon = new InstrumentIconView(this, mode, accent);
        int iconSize = dp(56);
        card.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

        TextView name = new TextView(this);
        name.setText(mode.label);
        name.setTextColor(Color.WHITE);
        name.setGravity(Gravity.CENTER);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        name.setTextSize(15);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(name, topMargin(matchWrap(), 6));

        TextView tag = new TextView(this);
        tag.setText(taglineFor(mode));
        tag.setTextColor(Color.WHITE);
        tag.setGravity(Gravity.CENTER);
        tag.setTextSize(11);
        tag.setSingleLine(true);
        tag.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(tag, topMargin(matchWrap(), 2));
        if (mode == InstrumentMode.ELECTRIC_GUITAR || mode == InstrumentMode.BASS) {
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            bLp.topMargin = dp(4);
            bLp.gravity = Gravity.CENTER_HORIZONTAL;
            card.addView(betaBadge(), bLp);
        }
        return card;
    }

    private View gridVirtualGuitarMidiCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(8), dp(10), dp(8), dp(8));
        card.setBackground(landingButtonBackground(COLOR_TEAL));
        card.setClickable(true);
        card.setOnClickListener(v ->
                launchFromLanding(card, this::openVirtualGuitarMidi));

        InstrumentIconView icon = new InstrumentIconView(
                this, InstrumentMode.ELECTRIC_GUITAR, COLOR_TEAL);
        int iconSize = dp(56);
        card.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

        TextView name = new TextView(this);
        name.setText("Virtual Guitar MIDI");
        name.setTextColor(Color.WHITE);
        name.setGravity(Gravity.CENTER);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        name.setTextSize(13);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(name, topMargin(matchWrap(), 6));

        TextView tag = new TextView(this);
        tag.setText("MIDI guitarist");
        tag.setTextColor(Color.WHITE);
        tag.setGravity(Gravity.CENTER);
        tag.setTextSize(11);
        tag.setSingleLine(true);
        card.addView(tag, topMargin(matchWrap(), 2));

        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.topMargin = dp(4);
        badgeLp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(betaBadge(), badgeLp);
        return card;
    }

    // Small amber "BETA" pill. Marks the parts that are still rough: the live
    // audio paths (guitar, bass, looper, vocals) and guitar-to-MIDI pitch
    // tracking, as opposed to the settled MIDI-driven keys and drums.
    private TextView betaBadge() {
        TextView badge = new TextView(this);
        badge.setText("BETA");
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(9);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        badge.setLetterSpacing(0.10f);
        badge.setGravity(Gravity.CENTER);
        badge.setIncludeFontPadding(false);
        badge.setPadding(dp(6), dp(2), dp(6), dp(2));
        badge.setBackground(pillBackground(Color.argb(32, Color.red(COLOR_AMBER),
                Color.green(COLOR_AMBER), Color.blue(COLOR_AMBER)), COLOR_AMBER));
        return badge;
    }

    private String taglineFor(InstrumentMode mode) {
        switch (mode) {
            case ELECTRIC_GUITAR: return "Amp & FX";
            case BASS: return "Low end";
            case PIANO: return "Keys & MIDI";
            case DRUMS: return "Pads & kits";
            default: return "";
        }
    }


    // Full-width Tuner rectangle, sits above the guitar/bass cards in the picker.
    private View buildTunerCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(landingButtonBackground(COLOR_AMBER));
        card.setClickable(true);
        card.setOnClickListener(v -> launchFromLanding(card, this::showTuner));

        TunerIconView icon = new TunerIconView(this, COLOR_AMBER);
        int sz = dpT(40, 54);
        card.addView(icon, new LinearLayout.LayoutParams(sz, sz));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText("Tuner");
        name.setTextColor(Color.WHITE);
        name.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        name.setTextSize(20);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(name, matchWrap());
        TextView desc = new TextView(this);
        desc.setText("Tune any instrument · mic or USB-C");
        desc.setTextColor(Color.WHITE);
        desc.setTextSize(13);
        desc.setMaxLines(2);
        desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(desc, topMargin(matchWrap(), 2));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(16);
        card.addView(text, tlp);

        TextView chev = new TextView(this);
        chev.setText("›");
        chev.setTextColor(Color.WHITE);
        chev.setTextSize(26);
        card.addView(chev, matchWrap());
        return card;
    }

    private void showInstrumentScreen() {
        onInstrumentScreen = true;
        if (currentMode != InstrumentMode.ELECTRIC_GUITAR && !virtualGuitarMidiMode) {
            engine.setNam(false, 1.0f, 1.0f, 0.72f);
            engine.setNamIr(false);
        }

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);

        // Landscape stage: controls rail left, play surface right.
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setPadding(dp(14), dp(10), dp(14), dp(10));
        screen.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        // ---- left rail: app bar, transport, sound picker, start ----
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(dp(12), dp(12), dp(12), dp(12));
        rail.setBackground(bubblyPanelBackground());

        LinearLayout appBar = new LinearLayout(this);
        appBar.setOrientation(LinearLayout.HORIZONTAL);
        appBar.setGravity(Gravity.CENTER_VERTICAL);
        rail.addView(appBar, matchWrap());

        appBar.addView(buildMenuButton(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        titleLp.leftMargin = dp(12);
        appBar.addView(titleBlock, titleLp);

        TextView title = new TextView(this);
        title.setText(virtualGuitarMidiMode ? "Virtual Guitar MIDI" : currentMode.label);
        title.setTextColor(COLOR_TEXT);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextSize(20);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (currentMode == InstrumentMode.ELECTRIC_GUITAR
                || currentMode == InstrumentMode.BASS || virtualGuitarMidiMode) {
            // Live-audio rigs stay flagged inside the screen, not just on the
            // picker card.
            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            titleRow.addView(title, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            bLp.leftMargin = dp(8);
            titleRow.addView(betaBadge(), bLp);
            titleBlock.addView(titleRow, matchWrap());
        } else {
            titleBlock.addView(title, matchWrap());
        }

        toneText = new TextView(this);
        toneText.setTextColor(COLOR_MUTED);
        toneText.setTextSize(12);
        toneText.setSingleLine(true);
        toneText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBlock.addView(toneText, matchWrap());

        // Route + engine status share one pill so they can't crowd or overlap the title.
        appBar.addView(buildStatusChip(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams ovLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ovLp.leftMargin = dp(6);
        appBar.addView(buildOverflowButton(), ovLp);

        // Readiness notes are a small quiet description line, not a banner.
        errorBanner = new TextView(this);
        errorBanner.setTextSize(11);
        errorBanner.setLineSpacing(dp(1), 1.0f);
        errorBanner.setPadding(dp(2), dp(2), dp(2), dp(2));
        errorBanner.setVisibility(View.GONE);
        rail.addView(errorBanner, topMargin(matchWrap(), 8));

        int stageAccent = toneAccentStatic(currentPreset);
        // Piano transport lives in the ENGINE panel below; other instruments keep
        // the plain transport row here.
        if (currentMode != InstrumentMode.PIANO) {
            rail.addView(buildTransportRow(), topMargin(matchWrap(), 8));
        }

        if (currentMode == InstrumentMode.PIANO) {
            // A thin faceplate accent strip under the header, hardware-style.
            View faceStrip = new View(this);
            faceStrip.setBackgroundColor(stageAccent);
            rail.addView(faceStrip, topMargin(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(2, dp(3))), 8));

            // Sounds live in a fixed, always-open browser on the left rail, now
            // framed as the PROGRAM panel of the stage faceplate.
            pianoBrowserHost = new LinearLayout(this);
            pianoBrowserHost.setOrientation(LinearLayout.VERTICAL);
            pianoBrowserHost.addView(buildPianoSoundBrowser(), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
            LinearLayout programPanel = stagePanel(
                    virtualGuitarMidiMode ? "GUITAR · INSTRUMENT" : "PROGRAM · SOUNDS",
                    stageAccent);
            programPanel.addView(pianoBrowserHost, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            rail.addView(programPanel, topMargin(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f), 10));
        } else {
            String barLabel = currentMode == InstrumentMode.DRUMS ? "KIT"
                    : (currentMode == InstrumentMode.ELECTRIC_GUITAR ? "NAM AMP" : "PEDAL");
            rail.addView(buildSoundBar(barLabel), topMargin(matchWrap(), 10));
            if (currentMode != InstrumentMode.DRUMS) {
                rail.addView(buildMeterBar(), topMargin(matchWrap(), 10));
            } else {
                drumLoadingBar = new ShineBar(this);
                drumLoadingBar.setAccent(toneAccentStatic(currentPreset));
                drumLoadingBar.setVisibility(View.GONE);
                rail.addView(drumLoadingBar, topMargin(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(5)), 8));
                rail.addView(buildSnareRimToggle(), topMargin(matchWrap(), 10));
                rail.addView(buildChimesButton(), topMargin(matchWrap(), 10));
                refreshDrumLoadingBar();
            }
            View railGap = new View(this);
            rail.addView(railGap, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        startButton = primaryButton("Start Engine");
        startButton.setOnClickListener(v -> toggleEngine());
        if (currentMode == InstrumentMode.PIANO) {
            LinearLayout enginePanel = stagePanel("ENGINE · OUTPUT", COLOR_GREEN);
            enginePanel.addView(buildTransportRow(), matchWrap());
            enginePanel.addView(startButton, topMargin(matchWrap(), 10));
            rail.addView(enginePanel, topMargin(matchWrap(), 10));
        } else {
            rail.addView(startButton, topMargin(matchWrap(), 10));
        }

        // ---- right pane: the playing surface per instrument ----
        LinearLayout pane = paneColumn();
        pane.setPadding(dp(12), dp(12), dp(12), dp(12));
        pane.setBackground(bubblyPanelBackground());
        if (currentMode == InstrumentMode.PIANO) {
            pianoPane = pane;
            pianoKeysView = null;   // playable keyboard lives in the Full Keys view

            // PERFORM panel: the pitch bender + the live "keys detected" readout,
            // framed like a hardware performance section.
            LinearLayout perform = stagePanel("PERFORM", stageAccent);
            PitchBendView pianoBend = new PitchBendView(this);
            pianoBend.setListener(v -> engine.setPitchWheel(8192 + Math.round(v * 8191)));
            perform.addView(bendRow(pianoBend), matchWrap());

            LinearLayout keysRow = new LinearLayout(this);
            keysRow.setOrientation(LinearLayout.VERTICAL);
            TextView keysLbl = labelText("KEYS DETECTED");
            keysLbl.setTextColor(COLOR_MUTED);
            keysRow.addView(keysLbl, matchWrap());
            pianoNotesText = new TextView(this);
            pianoNotesText.setTextColor(stageAccent);
            pianoNotesText.setTextSize(20);
            pianoNotesText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            pianoNotesText.setText("--");
            keysRow.addView(pianoNotesText, topMargin(matchWrap(), 4));
            perform.addView(keysRow, topMargin(matchWrap(), 12));
            pane.addView(perform, matchWrap());

            // CONTROLS panel: effect toggles + the A/B tabbed performance knobs.
            LinearLayout controls = stagePanel(
                    virtualGuitarMidiMode ? "GUITAR · PERFORMANCE" : "CONTROLS · FX",
                    stageAccent);
            controls.addView(buildEffectToggles(), matchWrap());
            if (virtualGuitarMidiMode) {
                pianoGuitarRigPanel = buildPianoGuitarRigPanel();
                controls.addView(pianoGuitarRigPanel, topMargin(matchWrap(), 12));
                refreshPianoGuitarRig();
            } else {
                engine.setPianoGuitarRig(false, false, pianoGuitarAmp, pianoGuitarCab,
                        pianoGuitarDrive, pianoGuitarTone, pianoGuitarHarmonics);
            }
            controls.addView(buildLiveControlsSection(stageAccent), topMargin(matchWrap(), 12));
            controls.addView(buildPerformanceMonitor(), topMargin(matchWrap(), 14));

            ScrollView fxScroll = new ScrollView(this);
            fxScroll.setVerticalScrollBarEnabled(false);
            fxScroll.addView(controls, new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
            pane.addView(fxScroll, topMargin(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f), 10));
        } else if (currentMode == InstrumentMode.DRUMS) {
            drumPadsView = new DrumPadsView(this);
            drumPadsView.setAccent(toneAccentStatic(currentPreset));
            drumPadsView.setListener(this::onDrumPad);
            drumPadsView.setHoldListener(this::cymbalVolumeSlider);
            drumPadsView.setChokeListener(engine::chokeCymbals);
            drumPadsView.setSnareRim(padSnareRim);
            pane.addView(drumPadsView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            refreshPadAvailability();
            // Kit Mode + MIDI Assignment live in the app-bar ⋮ menu.
        } else {
            signalChainView = new SignalChainView(this);
            signalChainView.setChain(signalChainLabels(), toneAccentStatic(currentPreset), signalChainHighlight());
            pane.addView(signalChainView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(84)));

            if (currentMode == InstrumentMode.ELECTRIC_GUITAR) {
                pane.removeView(signalChainView);
                pane.addView(buildGuitarRack(), new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            } else {
                pane.addView(buildGateRow(), topMargin(matchWrap(), 8));
                liveControlView = new LiveControlView(this);
                liveControlView.setControlsChangedListener(this::applyLiveControls);
                pane.addView(liveControlView, topMargin(weight(1.0f), 10));
            }
            // Noise gate on both guitar and bass — kills idle hum/buzz that
            // high-gain tones amplify.
            if (currentMode == InstrumentMode.ELECTRIC_GUITAR) {
                pushGuitarRackFx();
            }

        }
        applyLiveControls(liveControlValues);
        if (currentMode == InstrumentMode.PIANO) applyLiveControlsB(liveControlValuesB);

        // Piano: the rail holds the always-open sound browser, so it stays
        // unscrolled (the list scrolls internally) and gets extra width for the
        // Dual two-column split. Other instruments keep the scrolling rail.
        boolean piano = currentMode == InstrumentMode.PIANO;
        View railView = piano ? rail : railScroll(rail);
        content.addView(railView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, piano ? 5.8f : 4.6f));
        LinearLayout.LayoutParams paneLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, piano ? 6.2f : 7.4f);
        paneLp.leftMargin = dp(14);
        content.addView(pane, paneLp);

        enablePadInsets(screen, content);
        paintStage(screen);
        setContentView(screen);
        refreshDeviceStatus();
        updateSelectionStyles();
    }

    // Full keyboard: every key on screen at once, no sliding.
    private void showFullKeyboard() {
        onFullKeyboard = true;

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(10), dp(6), dp(10), dp(6));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(backArrowButton(this::closeFullKeyboard),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(pianoSoundName(false) + " · Full Keyboard");
        t.setTextColor(COLOR_TEXT);
        t.setTextSize(15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleBlock.addView(t, matchWrap());
        pianoNotesText = new TextView(this);
        pianoNotesText.setTextColor(COLOR_AMBER);
        pianoNotesText.setTextSize(13);
        pianoNotesText.setSingleLine(true);
        pianoNotesText.setText(heldNotesSummary() != null ? heldNotesSummary() : "--");
        titleBlock.addView(pianoNotesText, matchWrap());
        LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        tbLp.leftMargin = dp(8);
        bar.addView(titleBlock, tbLp);
        screen.addView(bar, matchWrap());

        // Rising-note visualizer sits above the keyboard, aligned to the same keys.
        keyVizView = new KeyVizView(this);
        keyVizView.setPressedNotes(keyOn);
        screen.addView(keyVizView, topMargin(weight(1.7f), 8));

        pianoKeysView = new PianoKeysView(this);
        pianoKeysView.setAccent(toneAccentStatic(currentPreset));
        pianoKeysView.setPressedNotes(keyOn);
        // Display-only: piano is MIDI-driven, no touch-to-play.
        pianoKeysView.setVisibleWhites(52.0f);   // A0..C8 = 52 white keys, all visible
        screen.addView(pianoKeysView, topMargin(weight(1.0f), 4));

        enablePadInsets(screen, screen);
        paintStage(screen);
        setContentView(screen);
    }

    private void closeFullKeyboard() {
        onFullKeyboard = false;
        showInstrumentScreen();
    }

    // ---- Full playable piano: a real, touch-playable 88-key keyboard that
    // zooms (all 88 keys down to ~7) and scrolls per manual. Top manual =
    // melody (single notes), bottom = chords (diatonic triads). The Sustain /
    // Reverb / Slide / Split / Dual / Sound 1 / Sound 2 controls are pinned at
    // the top; the sound lists open as popups. Distinct from the MIDI-driven
    // Full Keys view, which is display-only. ----
    private PianoBoardView fpMelody, fpChord;
    private TextView fpSound2Pill;
    // Tracks the last split layout so entering a dual-keyboard mode animates in.
    private int lastSplitAnimMode = -1;

    private void showFullPiano() {
        onFullPiano = true;
        ensurePianoEngine();

        // Play Keys mirrors the ONE shared sound config, not a separate copy:
        // Split = Dual on, Two-Manual = separate boards, split point = dualSplit.
        // (Layer Mode pins dualSplit to -1, so keep the last real split note.)
        fullPianoSplit = dualOn;
        fullPianoSplitStyle = dualSeparate ? 1 : 0;
        if (dualSplit >= 21) fpSplitNote = dualSplit;

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(8), dp(6), dp(8), dp(6));

        // --- Fixed top bar: back + pinned settings (scrolls sideways if needed) ---
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(backArrowButton(this::closeFullPiano),
                new LinearLayout.LayoutParams(dp(40), dp(40)));

        final TextView sustainPill = transportPill("Sustain");
        styleTogglePill(sustainPill, sustainOn);
        sustainPill.setOnClickListener(v -> {
            sustainOn = !sustainOn;
            engine.setSustain(sustainOn);
            if (!sustainOn) { engine.setSustainPedal(false); midiPedalDown = false; }
            styleTogglePill(sustainPill, sustainOn);
            saveEffectPrefs();
        });

        final TextView reverbPill = transportPill("Reverb");
        styleTogglePill(reverbPill, reverbOn);
        reverbPill.setOnClickListener(v -> {
            reverbOn = !reverbOn;
            engine.setReverb(reverbOn);
            styleTogglePill(reverbPill, reverbOn);
            saveEffectPrefs();
        });

        final TextView slidePill = transportPill(pianoGlideMono && pianoGlideOn ? "Mono" : "Slide");
        styleTogglePill(slidePill, pianoGlideOn);
        slidePill.setOnClickListener(v -> {
            if (!pianoGlideOn) { pianoGlideOn = true; pianoGlideMono = false; }
            else if (!pianoGlideMono) { pianoGlideMono = true; }
            else { pianoGlideOn = false; pianoGlideMono = false; }
            pushPianoGlide();
            engine.allNotesOff();
            if (fpMelody != null) fpMelody.releaseAll();
            if (fpChord != null) fpChord.releaseAll();
            slidePill.setText(pianoGlideMono && pianoGlideOn ? "Mono" : "Slide");
            styleTogglePill(slidePill, pianoGlideOn);
            prefs.edit().putBoolean("piano_glide", pianoGlideOn)
                    .putBoolean("piano_glide_mono", pianoGlideMono).apply();
        });

        // Tone: a softness knob (rolls highs off the whole piano output) to tame a
        // bright/screaming lead. Set-and-forget knob, opened from this pill.
        engine.setPianoSoft(fpSoft);
        final TextView tonePill = transportPill("Tone " + Math.round(fpSoft * 100) + "%");
        styleTogglePill(tonePill, fpSoft > 0f);
        tonePill.setOnClickListener(v -> toneKnobDialog(tonePill));

        // One 3-way Split control: No Split → Key-Split → Two-Manual, mapped to
        // the shared Dual state (No Split = dualOn off, Key-Split = dualOn +
        // pitch split, Two-Manual = dualOn + separate boards).
        int splitMode = !dualOn ? 0 : (dualSeparate ? 2 : 1);
        final String[] splitLabels = {"⌨ No Split", "⑃ Key-Split", "▤ Two-Manual"};
        final TextView splitPill = transportPill(splitLabels[splitMode]);
        styleTogglePill(splitPill, splitMode != 0);
        splitPill.setOnClickListener(v -> {
            int next = (splitMode + 1) % 3;
            if (next == 0) { dualOn = false; }
            else if (next == 1) { dualOn = true; dualSeparate = false; }
            else { dualOn = true; dualSeparate = true; }
            applyDualSound();
            engine.allNotesOff();
            showFullPiano();
        });

        // Chord: the bottom (CHORDS) manual plays a diatonic triad per key. Off =
        // it plays single notes like the melody manual.
        final TextView chordPill = transportPill("Chord");
        styleTogglePill(chordPill, fullPianoChord);
        chordPill.setOnClickListener(v -> {
            fullPianoChord = !fullPianoChord;
            prefs.edit().putBoolean("full_piano_chord", fullPianoChord).apply();
            // Two-manual: the CHORDS board. Single board (No-Split / Key-Split):
            // the whole keyboard plays triads.
            if (fpChord != null) fpChord.setChord(fullPianoChord);
            else if (fpMelody != null) fpMelody.setChord(fullPianoChord);
            styleTogglePill(chordPill, fullPianoChord);
        });

        // Chord quality: Diatonic (scale-aware) or a fixed shape (min/sus4/…) on
        // every key. Tap to cycle.
        final TextView chordQualPill = transportPill(CHORD_QUALITY_NAME[fullPianoChordType]);
        styleTogglePill(chordQualPill, fullPianoChordType != 0);
        chordQualPill.setOnClickListener(v -> {
            fullPianoChordType = (fullPianoChordType + 1) % CHORD_QUALITY_NAME.length;
            prefs.edit().putInt("full_piano_chord_type", fullPianoChordType).apply();
            if (fpMelody != null) fpMelody.setChordType(fullPianoChordType);
            if (fpChord != null) fpChord.setChordType(fullPianoChordType);
            chordQualPill.setText(CHORD_QUALITY_NAME[fullPianoChordType]);
            styleTogglePill(chordQualPill, fullPianoChordType != 0);
        });

        // Key-split boundary: which note divides Chords (low) from Melody (high).
        final TextView splitAtPill = transportPill("Split @ " + noteName(fpSplitNote));
        styleTogglePill(splitAtPill, false);
        splitAtPill.setOnClickListener(v -> fullPianoSplitPointDialog());

        final TextView layersPill = transportPill("⧉ Layers");
        styleTogglePill(layersPill, layerMode);
        layersPill.setOnClickListener(v -> layersDialog());

        final TextView mixerPill = transportPill("▥ Mixer");
        styleTogglePill(mixerPill, false);
        mixerPill.setOnClickListener(v -> showMixer());

        final TextView sound1Pill = transportPill(
                "Snd 1: " + pianoSoundName(false) + "  ▾");
        styleTogglePill(sound1Pill, false);
        sound1Pill.setTextColor(contrastTextColor(COLOR_SURFACE_RAISED));
        sound1Pill.setOnClickListener(v -> pianoSoundPopup(false, () -> {
            fullKeysRoute();
            sound1Pill.setText("Snd 1: " + pianoSoundName(false) + "  ▾");
            sound1Pill.setTextColor(contrastTextColor(COLOR_SURFACE_RAISED));
        }));

        fpSound2Pill = transportPill("Sound 2  ▾");
        fpSound2Pill.setOnClickListener(v -> pianoSoundPopup(true, () -> {
            dualOn = true;
            if (fpMelody != null) fpMelody.setSound2(true);
            fullKeysRoute();
            refreshFullPianoSound2Pill();
        }));
        refreshFullPianoSound2Pill();

        LinearLayout pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setGravity(Gravity.CENTER_VERTICAL);
        // Grouped, labelled clusters (Play · Split · Sounds · Layers) with thin
        // dividers so the many controls read cleanly. Split IS the shared Dual;
        // its style/point/Chord only show when a split is active.
        java.util.List<View> play = java.util.Arrays.asList(sustainPill, reverbPill, slidePill, tonePill);
        java.util.ArrayList<View> splitG = new java.util.ArrayList<>();
        splitG.add(splitPill);
        if (fullPianoSplit && fullPianoSplitStyle == 0) splitG.add(splitAtPill);   // key-split only
        splitG.add(chordPill);
        splitG.add(chordQualPill);
        java.util.ArrayList<View> sounds = new java.util.ArrayList<>();
        sounds.add(sound1Pill);
        if (fullPianoSplit) sounds.add(fpSound2Pill);
        addPillCluster(pills, "PLAY", play, false);
        addPillCluster(pills, "SPLIT", splitG, true);
        addPillCluster(pills, "SOUNDS", sounds, true);
        addPillCluster(pills, "LAYERS", java.util.Arrays.asList(layersPill, mixerPill), true);
        HorizontalScrollView pillScroll = new HorizontalScrollView(this);
        pillScroll.setHorizontalScrollBarEnabled(false);
        pillScroll.addView(pills);
        LinearLayout.LayoutParams pillScrollLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pillScrollLp.leftMargin = dp(8);
        bar.addView(pillScroll, pillScrollLp);
        screen.addView(bar, matchWrap());

        int accent = toneAccentStatic(currentPreset);

        // Animate only when ENTERING a dual keyboard (from no split). Switching
        // between the two split styles, or changing the split point, does not
        // re-animate — the dual keyboard already exists.
        int splitAnimMode = !fullPianoSplit ? 0 : (fullPianoSplitStyle == 1 ? 2 : 1);
        boolean wasDual = lastSplitAnimMode == 1 || lastSplitAnimMode == 2;
        boolean animateSplitIn = fullPianoSplit && !wasDual;
        lastSplitAnimMode = splitAnimMode;

        if (fullPianoSplit && fullPianoSplitStyle == 1) {
            // --- Two manuals: MELODY (single notes) + CHORDS (diatonic triads) ---
            fpMelody = new PianoBoardView(this);
            fpMelody.setAccent(accent);
            fpMelody.setChord(false);
            fpMelody.setSound2(dualOn);
            fpMelody.setVisibleWhites(melodyZoom);
            fpMelody.setScrollWhite(melodyBaseWhite);
            fpMelody.setListener(this::playFullPianoNote);
            fpMelody.setStateSink((zoom, base) -> { melodyZoom = zoom; melodyBaseWhite = base; });
            screen.addView(fullPianoNavRow("MELODY", fpMelody, accent), topMargin(matchWrap(), 6));
            screen.addView(keyboardWithBend(fpMelody, dualOn ? 2 : 1, accent), topMargin(weight(1.0f), 4));

            fpChord = new PianoBoardView(this);
            fpChord.setAccent(COLOR_PURPLE);
            fpChord.setChord(fullPianoChord);
            fpChord.setSound2(false);
            fpChord.setVisibleWhites(chordZoom);
            fpChord.setScrollWhite(chordBaseWhite);
            fpChord.setListener(this::playFullPianoNote);
            fpChord.setStateSink((zoom, base) -> { chordZoom = zoom; chordBaseWhite = base; });
            screen.addView(fullPianoNavRow("CHORDS", fpChord, COLOR_PURPLE), topMargin(matchWrap(), 6));
            screen.addView(keyboardWithBend(fpChord, 1, COLOR_PURPLE), topMargin(weight(1.0f), 4));
        } else if (fullPianoSplit) {
            // --- Key-Split: a DUAL keyboard. The two stacked boards are the split
            // halves — upper board = the HIGH range playing Sound 2, lower board =
            // the LOW range playing Sound 1 — divided at the split note. ---
            fpMelody = new PianoBoardView(this);   // upper = Sound 2 (high)
            fpMelody.setAccent(COLOR_PURPLE);
            fpMelody.setChord(false);
            fpMelody.setSound2(true);
            fpMelody.setVisibleWhites(melodyZoom);
            fpMelody.setScrollWhite(whiteIndexOf(fpSplitNote));
            fpMelody.setListener(this::playFullPianoNote);
            fpMelody.setStateSink((zoom, base) -> { melodyZoom = zoom; });
            screen.addView(fullPianoNavRow("SOUND 2 · HIGH ≥ " + noteName(fpSplitNote),
                    fpMelody, COLOR_PURPLE), topMargin(matchWrap(), 6));
            screen.addView(keyboardWithBend(fpMelody, 2, COLOR_PURPLE), topMargin(weight(1.0f), 4));

            fpChord = new PianoBoardView(this);    // lower = Sound 1 (low)
            fpChord.setAccent(accent);
            fpChord.setChord(fullPianoChord);
            fpChord.setSound2(false);
            fpChord.setVisibleWhites(chordZoom);
            fpChord.setScrollWhite(whiteIndexOf(fpSplitNote) - chordZoom);
            fpChord.setListener(this::playFullPianoNote);
            fpChord.setStateSink((zoom, base) -> { chordZoom = zoom; });
            screen.addView(fullPianoNavRow("SOUND 1 · LOW < " + noteName(fpSplitNote),
                    fpChord, accent), topMargin(matchWrap(), 6));
            screen.addView(keyboardWithBend(fpChord, 1, accent), topMargin(weight(1.0f), 4));
        } else {
            // --- One whole keyboard (single sound across the full range) ---
            fpChord = null;
            fpMelody = new PianoBoardView(this);
            fpMelody.setAccent(accent);
            fpMelody.setChord(fullPianoChord);
            fpMelody.setSound2(false);
            fpMelody.setVisibleWhites(melodyZoom);
            fpMelody.setScrollWhite(melodyBaseWhite);
            fpMelody.setListener(this::playFullPianoNote);
            fpMelody.setStateSink((zoom, base) -> { melodyZoom = zoom; melodyBaseWhite = base; });
            screen.addView(fullPianoNavRow("KEYBOARD", fpMelody, accent), topMargin(matchWrap(), 6));
            screen.addView(keyboardWithBend(fpMelody, 0, accent), topMargin(weight(2.0f), 4));
        }

        if (fpMelody != null) fpMelody.setChordType(fullPianoChordType);
        if (fpChord != null) fpChord.setChordType(fullPianoChordType);

        // Dual keyboards slide together in from top & bottom when a split opens.
        if (animateSplitIn && fpChord != null) {
            animateBoardIn(fpMelody, -dp(36), 0);
            animateBoardIn(fpChord, dp(36), 50);
        }

        fullKeysRoute();
        enablePadInsets(screen, screen);
        paintStage(screen);
        setContentView(screen);
    }

    // Slide + fade a board into place (used when a split layout opens).
    private void animateBoardIn(View v, float fromDy, long delay) {
        v.setAlpha(0f);
        v.setTranslationY(fromDy);
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(300)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    // Wrap a keyboard with one combined pitch/mod lever on its far left. bendSide:
    // 0 = whole keyboard (both sounds), 1 = Sound 1 / side A, 2 = Sound 2 / side B.
    private View keyboardWithBend(final PianoBoardView board, final int bendSide, int accent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        engine.setBendRange(bendRange);

        PitchVibratoView control = new PitchVibratoView(this);
        control.setAccent(accent);
        control.setRange(bendRange);
        control.setListener((bend, vibrato) -> {
            int pw = 8192 + Math.round(bend * 8191);
            if (bendSide == 1) engine.setPitchWheelA(pw);
            else if (bendSide == 2) engine.setPitchWheelB(pw);
            else engine.setPitchWheel(pw);
            if (bendSide == 1) engine.setVibratoA(vibrato);
            else if (bendSide == 2) engine.setVibratoB(vibrato);
            else engine.setVibrato(vibrato);
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(112),
                LinearLayout.LayoutParams.MATCH_PARENT);
        blp.rightMargin = dp(6);
        row.addView(control, blp);

        row.addView(board, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        return row;
    }

    // A per-manual control cluster: [◄] scroll left · [–]/[+] zoom · [►] scroll
    // right, plus the manual's name and its current low note.
    private View fullPianoNavRow(String label, final PianoBoardView board, int accent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(accent);
        name.setTextSize(12);
        name.setLetterSpacing(0.08f);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(name, new LinearLayout.LayoutParams(dp(72),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView range = new TextView(this);
        range.setTextColor(COLOR_DIM);
        range.setTextSize(11);
        board.setRangeReadout(range);
        row.addView(range, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(navPill("◄", () -> board.scrollByWhites(-2)));
        row.addView(navPill("–", () -> board.zoomBy(2)));
        row.addView(navPill("+", () -> board.zoomBy(-2)));
        row.addView(navPill("►", () -> board.scrollByWhites(2)));
        return row;
    }

    private TextView navPill(String glyph, final Runnable onClick) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextColor(COLOR_TEXT);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setBackground(pillBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        t.setClickable(true);
        t.setOnClickListener(v -> onClick.run());
        // Repeat while held so scrolling/zooming across 88 keys is quick.
        t.setOnLongClickListener(v -> { autoRepeat(v, onClick); return true; });
        t.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(navRepeat);
            }
            return false;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(40));
        lp.leftMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private Runnable navRepeat;
    private void autoRepeat(View v, final Runnable onClick) {
        navRepeat = new Runnable() {
            @Override public void run() {
                if (!v.isPressed()) return;
                onClick.run();
                handler.postDelayed(this, 90);
            }
        };
        handler.postDelayed(navRepeat, 90);
    }

    // A labelled cluster of pills in the Play Keys bar, preceded by a thin
    // divider (except the first) so the controls read as tidy groups.
    private void addPillCluster(LinearLayout bar, String label, java.util.List<View> items,
            boolean divider) {
        if (divider) {
            View d = new View(this);
            d.setBackgroundColor(COLOR_BORDER);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(1), dp(26));
            dlp.leftMargin = dp(9);
            bar.addView(d, dlp);
        }
        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextColor(COLOR_DIM);
        lab.setTextSize(9);
        lab.setLetterSpacing(0.12f);
        lab.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = dp(divider ? 6 : 2);
        bar.addView(lab, llp);
        for (View it : items) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(5);
            bar.addView(it, lp);
        }
    }

    private void styleTogglePill(TextView pill, boolean on) {
        pill.setTextColor(contrastTextColor(COLOR_SURFACE_RAISED));
        pill.setBackground(pillBackground(COLOR_SURFACE_RAISED, on ? COLOR_GREEN : COLOR_BORDER));
    }

    private void refreshFullPianoSound2Pill() {
        if (fpSound2Pill == null) return;
        fpSound2Pill.setEnabled(dualOn);
        fpSound2Pill.setAlpha(dualOn ? 1f : 0.42f);
        fpSound2Pill.setText((dualOn ? "Snd 2: " + pianoSoundName(true) : "Sound 2")
                + "  ▾");
        fpSound2Pill.setTextColor(contrastTextColor(COLOR_SURFACE_RAISED));
        fpSound2Pill.setBackground(pillBackground(COLOR_SURFACE_RAISED,
                dualOn ? toneAccentStatic(dualPreset) : COLOR_BORDER));
    }

    // Play a note from a full-piano manual. Sound 1 goes to the main channel,
    // Sound 2 (Dual + melody manual) to the layer channel. No pitch auto-split.
    private void playFullPianoNote(int note, float vel, boolean sound2, boolean down) {
        // Key-split style routes by PITCH: at/above the split note = Sound 2,
        // below = Sound 1 — regardless of which board was tapped (the boards can
        // be scrolled across the split point). Two-manual routes per board.
        boolean useSound2;
        if (fullPianoSplit && fullPianoSplitStyle == 0 && dualOn) {
            useSound2 = note >= fpSplitNote;
        } else {
            useSound2 = sound2 && dualOn;
        }
        if (useSound2) {
            if (down) engine.note2On(note, vel); else engine.note2Off(note);
        } else {
            if (down) engine.noteOn(note, vel); else engine.noteOff(note);
        }
    }

    // Force manual per-manual routing: disable the pitch auto-split so noteOn is
    // always Sound 1 and note2On is always Sound 2. Called after any sound/dual
    // change while the full piano is open.
    // Native note routing for the current full-piano split style: whole keyboard
    // and two-manual route per-manual in Java (no pitch split); key-split hands
    // the split note to the engine so low/high play different sounds.
    private void fullPianoRouteConfig() {
        // Both split styles are dual keyboards routed per board (lower board →
        // Sound 1 / noteOn, upper board → Sound 2 / note2On), never a pitch split.
        // Per-board mode: a noteOn plays Sound 1 ONLY (no dual auto-layering).
        engine.setKeySplitConfig(0, -1);
        engine.setManualSplit(true);
    }

    private void fullKeysRoute() {
        if (!onFullPiano) return;
        fullPianoRouteConfig();
        applyLayers();
    }

    // Choose the key-split boundary note for the full piano (key-split style).
    private void fullPianoSplitPointDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(dialogSheet());
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(16), dp(18), dp(16));
        TextView title = new TextView(this);
        title.setText("Split Point");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());
        final TextView lab = new TextView(this);
        lab.setText("Sound 1 below · Sound 2 above · split at " + noteName(fpSplitNote));
        lab.setTextColor(COLOR_MUTED);
        lab.setTextSize(13);
        lab.setGravity(Gravity.CENTER);
        content.addView(lab, topMargin(matchWrap(), 10));
        SeekBar sb = new SeekBar(this);
        sb.setMax(108 - 21);
        sb.setProgress(Math.max(0, Math.min(108, fpSplitNote) - 21));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                fpSplitNote = 21 + p;
                if (!layerMode) dualSplit = fpSplitNote;   // shared point (unless layer-pinned)
                lab.setText("Sound 1 below · Sound 2 above · split at " + noteName(fpSplitNote));
                fullPianoRouteConfig();
                applyLayers();
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        content.addView(sb, topMargin(matchWrap(), 12));
        TextView done = new TextView(this);
        done.setText("Done");
        done.setTextColor(COLOR_GREEN);
        done.setTextSize(15);
        done.setGravity(Gravity.CENTER);
        done.setPadding(dp(12), dp(12), dp(12), dp(12));
        done.setClickable(true);
        done.setBackground(moduleBackground(COLOR_SURFACE_RAISED, COLOR_BORDER, COLOR_GREEN, true));
        done.setOnClickListener(v -> {
            prefs.edit().putInt("fp_split_note", fpSplitNote).apply();
            if (!layerMode) { dualSplit = fpSplitNote; applyDualSound(); }   // persist shared point
            dialog.dismiss();
            showFullPiano();   // refresh the "Split @" labels
        });
        content.addView(done, topMargin(matchWrap(), 14));
        presentMenu(dialog, content, dialogWidth(0.82f, 380));
    }

    // ---- Chord Mode: a full-screen board of 6-20 strummable chord strips. Each
    // strip is one chord; its horizontal bands are the chord's notes low->high,
    // so dragging down a strip strums it and tapping a band plucks one note.
    // Tap a strip's name to swap in any chord. Separate from the play keys. ----
    private static final int CHORD_SLOTS_MIN = 6;
    private static final int CHORD_SLOTS_DEFAULT = 8;
    private static final int CHORD_SLOTS_MAX = 20;
    private static final int CHORD_PLAY_BLOCK = 0;
    private static final int CHORD_PLAY_STUDIO = 1;
    private static final int CHORD_PLAY_ROLLED = 2;
    private static final int CHORD_PLAY_REVERSE = 3;
    private static final int CHORD_PLAY_BALLAD = 4;
    private static final int CHORD_PLAY_ARPEGGIO = 5;
    private static final int CHORD_PLAY_STRUM = 6;
    private static final int CHORD_PLAY_JOYOUS = 7;
    private static final int CHORD_PLAY_FUNKY = 8;
    private static final int CHORD_PLAY_LIVELY = 9;
    private static final String[] CHORD_PLAY_NAMES = {
            "Block", "Studio", "Rolled", "Reverse", "Ballad", "Arpeggio", "Strum",
            "Joyous", "Funky", "Lively"
    };
    // Band 0 (top) plays the WHOLE chord; bands 1..6 are its single notes.
    private static final int CHORD_BANDS = 7;
    private static final int CHORD_SINGLES = CHORD_BANDS - 1;
    // Practical naming: sharps where they're normally written, flats where they
    // are (Eb, Ab, Bb) — so slash chords read like charts do (Bb/F, Bb/Eb).
    private static final String[] ROOT_NAMES = {
            "C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"};
    private static final String[] CHORD_SUFFIX = {
            "", "m", "7", "m7", "maj7", "6", "m6", "sus2", "sus4", "9", "m9",
            "add9", "dim", "aug",
            "5", "maj9", "11", "m11", "13", "m13", "maj13",
            "7sus2", "7sus4", "9sus4", "6/9", "m6/9",
            "add2", "add4", "add11", "madd9", "mMaj7",
            "dim7", "m7b5", "7b5", "7#5", "maj7#5",
            "7b9", "7#9", "7#11", "7b13",
            "9b5", "9#5", "9#11", "13b9", "13#9",
            "sus2sus4", "maj7sus2", "maj7sus4", "m7add11", "m7add13",
            "add#11", "7sus4b9", "maj9#11", "m9b5",
            "dimMaj7", "augMaj7", "m#5", "m7#5", "7alt"};
    private static final int[][] CHORD_INTERVALS = {
            {0, 4, 7},          // major
            {0, 3, 7},          // minor
            {0, 4, 7, 10},      // 7
            {0, 3, 7, 10},      // m7
            {0, 4, 7, 11},      // maj7
            {0, 4, 7, 9},       // 6
            {0, 3, 7, 9},       // m6
            {0, 2, 7},          // sus2
            {0, 5, 7},          // sus4
            {0, 4, 7, 10, 14},  // 9
            {0, 3, 7, 10, 14},  // m9
            {0, 4, 7, 14},      // add9
            {0, 3, 6},          // dim
            {0, 4, 8},          // aug
            {0, 7},                  // 5
            {0, 4, 7, 11, 14},      // maj9
            {0, 4, 7, 10, 14, 17},  // 11
            {0, 3, 7, 10, 14, 17},  // m11
            {0, 4, 10, 14, 17, 21}, // 13 (fifth omitted in six-note voicing)
            {0, 3, 10, 14, 17, 21}, // m13
            {0, 4, 11, 14, 17, 21}, // maj13
            {0, 2, 7, 10},          // 7sus2
            {0, 5, 7, 10},          // 7sus4
            {0, 5, 7, 10, 14},      // 9sus4
            {0, 4, 7, 9, 14},       // 6/9
            {0, 3, 7, 9, 14},       // m6/9
            {0, 2, 4, 7},           // add2
            {0, 4, 5, 7},           // add4
            {0, 4, 7, 17},          // add11
            {0, 3, 7, 14},          // madd9
            {0, 3, 7, 11},          // mMaj7
            {0, 3, 6, 9},           // dim7
            {0, 3, 6, 10},          // m7b5
            {0, 4, 6, 10},          // 7b5
            {0, 4, 8, 10},          // 7#5
            {0, 4, 8, 11},          // maj7#5
            {0, 4, 7, 10, 13},      // 7b9
            {0, 4, 7, 10, 15},      // 7#9
            {0, 4, 7, 10, 18},      // 7#11
            {0, 4, 7, 10, 20},      // 7b13
            {0, 4, 6, 10, 14},      // 9b5
            {0, 4, 8, 10, 14},      // 9#5
            {0, 4, 7, 10, 14, 18},  // 9#11
            {0, 4, 7, 10, 13, 21},  // 13b9
            {0, 4, 7, 10, 15, 21},  // 13#9
            {0, 2, 5, 7},           // sus2sus4
            {0, 2, 7, 11},          // maj7sus2
            {0, 5, 7, 11},          // maj7sus4
            {0, 3, 7, 10, 17},      // m7add11
            {0, 3, 7, 10, 21},      // m7add13
            {0, 4, 7, 18},          // add#11
            {0, 5, 7, 10, 13},      // 7sus4b9
            {0, 4, 7, 11, 14, 18},  // maj9#11
            {0, 3, 6, 10, 14},      // m9b5
            {0, 3, 6, 11},          // dimMaj7
            {0, 4, 8, 11},          // augMaj7
            {0, 3, 8},              // m#5
            {0, 3, 8, 10},          // m7#5
            {0, 4, 8, 10, 13, 15},  // 7alt
    };
    // Default board: a usable pop/ballad set out of the box.
    private static final int[] CHORD_DEFAULT_ROOT = {2, 4, 5, 9, 0, 0, 11, 5};
    private static final int[] CHORD_DEFAULT_TYPE = {1, 1, 0, 1, 0, 1, 9, 1};
    private int chordSlotCount = CHORD_SLOTS_DEFAULT;
    private final int[] chordRoot = new int[CHORD_SLOTS_MAX];
    private final int[] chordType = new int[CHORD_SLOTS_MAX];
    private final int[] chordBass = new int[CHORD_SLOTS_MAX];   // -1 = no slash bass
    private ChordBoardView chordBoard;
    private int chordPlayMode = CHORD_PLAY_BLOCK;
    private int chordStrumMs = 30;

    // "Bb", "Dm7", or a slash chord "Bb/F" when an alternate bass is set.
    private static String chordName(int root, int type, int bass) {
        String s = ROOT_NAMES[root] + CHORD_SUFFIX[type];
        return (bass >= 0 && bass != root) ? s + "/" + ROOT_NAMES[bass] : s;
    }

    // Solo-piano voicing: bass/root in the left hand and five close upper
    // voices. Extensions stay above middle C so 9ths/11ths/13ths do not turn
    // muddy in the bass register.
    private int[] chordVoicing(int root, int type, int bass) {
        int[] iv = CHORD_INTERVALS[type];
        int[] out = new int[CHORD_SINGLES];
        out[0] = 36 + (bass >= 0 ? bass : root);
        for (int i = 1; i < CHORD_SINGLES; i++) {
            int k = i - 1;
            out[i] = 48 + root + iv[k % iv.length] + 12 * (k / iv.length);
        }
        return out;
    }

    // Voice-lead a board slot from everything before it. Candidate inversions
    // retain the chord tones but choose the register with the least total upper
    // voice movement, which naturally keeps common tones and stepwise motion.
    private int[] chordVoicingForSlot(int slot) {
        int[] voiced = chordVoicing(chordRoot[0], chordType[0], chordBass[0]);
        for (int s = 1; s <= slot && s < chordSlotCount; s++) {
            voiced = voiceLeadChord(voiced,
                    chordRoot[s], chordType[s], chordBass[s]);
        }
        return voiced;
    }

    private int[] voiceLeadChord(int[] previous, int root, int type, int bass) {
        int[] intervals = CHORD_INTERVALS[type];
        int[] best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int inversion = 0; inversion < intervals.length; inversion++) {
            for (int octave = -12; octave <= 12; octave += 12) {
                int[] candidate = new int[CHORD_SINGLES];
                candidate[0] = 36 + (bass >= 0 ? bass : root);
                boolean inRange = true;
                for (int voice = 1; voice < CHORD_SINGLES; voice++) {
                    int sequence = inversion + voice - 1;
                    int note = 48 + root + intervals[sequence % intervals.length]
                            + 12 * (sequence / intervals.length) + octave;
                    candidate[voice] = note;
                    if (note < 48 || note > 88
                            || (voice > 1 && note <= candidate[voice - 1])) {
                        inRange = false;
                    }
                }
                if (!inRange) continue;
                int score = 0;
                for (int voice = 1; voice < CHORD_SINGLES; voice++) {
                    int movement = Math.abs(candidate[voice] - previous[voice]);
                    score += movement * movement;
                    if (movement > 4) score += (movement - 4) * 8;
                }
                // Prefer a compact right hand, but allow wider shapes when they
                // provide materially smoother movement.
                score += candidate[CHORD_SINGLES - 1] - candidate[1];
                if (score < bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return best != null ? best : chordVoicing(root, type, bass);
    }

    private void loadChordSlots() {
        chordSlotCount = clampChordSlotCount(
                prefs.getInt("chord_slot_count", CHORD_SLOTS_DEFAULT));
        applyChordSlotsString(prefs.getString("chord_slots", null));
    }

    private void applyChordSlotsString(String s) {
        for (int i = 0; i < CHORD_SLOTS_MAX; i++) {
            chordRoot[i] = CHORD_DEFAULT_ROOT[i % CHORD_DEFAULT_ROOT.length];
            chordType[i] = CHORD_DEFAULT_TYPE[i % CHORD_DEFAULT_TYPE.length];
            chordBass[i] = -1;
        }
        if (s == null || s.isEmpty()) return;
        String data = s;
        int divider = s.indexOf('|');
        if (divider > 0) {
            try {
                chordSlotCount = clampChordSlotCount(Integer.parseInt(s.substring(0, divider)));
            } catch (NumberFormatException ignored) { }
            data = s.substring(divider + 1);
        }
        String[] toks = data.split(",");
        // Legacy snapshots did not store a count; their token count is the board size.
        if (divider < 0 && toks.length >= CHORD_SLOTS_MIN) {
            chordSlotCount = clampChordSlotCount(toks.length);
        }
        for (int i = 0; i < CHORD_SLOTS_MAX && i < toks.length; i++) {
            // "root:type" (old) or "root:type:bass" (with a slash bass).
            String[] f = toks[i].split(":");
            if (f.length < 2) continue;
            try {
                int r = Integer.parseInt(f[0]), t = Integer.parseInt(f[1]);
                if (r >= 0 && r < 12 && t >= 0 && t < CHORD_SUFFIX.length) {
                    chordRoot[i] = r;
                    chordType[i] = t;
                    chordBass[i] = -1;
                    if (f.length >= 3) {
                        int b = Integer.parseInt(f[2]);
                        chordBass[i] = (b >= 0 && b < 12) ? b : -1;
                    }
                }
            } catch (NumberFormatException ignored) { }
        }
    }

    private String chordSlotsString() {
        StringBuilder sb = new StringBuilder();
        sb.append(chordSlotCount).append('|');
        // Keep all 20 definitions so temporarily shrinking the board does not
        // destroy the customized chords that are currently hidden.
        for (int i = 0; i < CHORD_SLOTS_MAX; i++) {
            sb.append(chordRoot[i]).append(':').append(chordType[i])
                    .append(':').append(chordBass[i]);
            if (i < CHORD_SLOTS_MAX - 1) sb.append(',');
        }
        return sb.toString();
    }

    private void saveChordSlots() {
        prefs.edit().putInt("chord_slot_count", chordSlotCount)
                .putString("chord_slots", chordSlotsString()).apply();
    }

    private static int clampChordSlotCount(int count) {
        return Math.max(CHORD_SLOTS_MIN, Math.min(CHORD_SLOTS_MAX, count));
    }

    // ---- Chord songs: named snapshots of the current 6-20 chord board.
    // name the board and save it, then Play any song to set those chords back.
    // Rows carry icon actions: ▶ play · 💾 overwrite with what's on screen · ✕ remove.
    private static final String CHORD_SONG_SEP = "\u001f";   // unit separator: cannot occur in a typed name

    private java.util.List<String> chordSongNames() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String s = prefs.getString("chord_song_names", "");
        if (s == null || s.isEmpty()) return out;
        for (String n : s.split(CHORD_SONG_SEP)) {
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    private void putChordSongNames(java.util.List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(CHORD_SONG_SEP);
            sb.append(names.get(i));
        }
        prefs.edit().putString("chord_song_names", sb.toString()).apply();
    }

    // Save (or overwrite) a song with whatever is on the board right now. The
    // modified time lets import decide "latest wins" on a name clash.
    private void saveChordSong(String name) {
        if (name == null) return;
        String n = name.trim().replace(CHORD_SONG_SEP, "");
        if (n.isEmpty()) return;
        prefs.edit().putString("chord_song_" + n, chordSlotsString())
                .putLong("chord_song_t_" + n, System.currentTimeMillis()).apply();
        java.util.List<String> names = chordSongNames();
        if (!names.contains(n)) {
            names.add(n);
            putChordSongNames(names);
        }
    }

    private void playChordSong(String name) {
        String s = prefs.getString("chord_song_" + name, null);
        if (s == null) return;
        applyChordSlotsString(s);
        saveChordSlots();
        if (chordBoard != null) chordBoard.setChords(chordRoot, chordType);
    }

    private void removeChordSong(String name) {
        java.util.List<String> names = chordSongNames();
        names.remove(name);
        putChordSongNames(names);
        prefs.edit().remove("chord_song_" + name).remove("chord_song_t_" + name).apply();
    }

    // ---- Chord-song list export / import (share the whole song list) ----
    private void exportChordSongs() {
        if (chordSongNames().isEmpty()) {
            Toast.makeText(this, "No songs to export yet", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "bandapp-chord-songs.json");
        try { startActivityForResult(intent, REQ_EXPORT_CHORDS); }
        catch (Exception e) { Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show(); }
    }

    private void importChordSongs() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try { startActivityForResult(intent, REQ_IMPORT_CHORDS); }
        catch (Exception e) { Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show(); }
    }

    private void writeChordSongs(Uri uri) {
        try {
            org.json.JSONObject songs = new org.json.JSONObject();
            for (String name : chordSongNames()) {
                // {chords, modified-time} so import can resolve clashes by "latest wins".
                org.json.JSONObject song = new org.json.JSONObject();
                song.put("c", prefs.getString("chord_song_" + name, ""));
                song.put("t", prefs.getLong("chord_song_t_" + name, 0));
                songs.put(name, song);
            }
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("app", "bandapp-chords");
            root.put("songs", songs);
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("no stream");
                out.write(root.toString(2).getBytes("UTF-8"));
            }
            Toast.makeText(this, "Exported " + songs.length() + " songs", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void readChordSongs(Uri uri) {
        byte[] bytes = readUri(uri);
        if (bytes == null || bytes.length == 0) {
            Toast.makeText(this, "Could not read that file", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            org.json.JSONObject root = new org.json.JSONObject(new String(bytes, "UTF-8"));
            org.json.JSONObject songs = root.optJSONObject("songs");
            if (!"bandapp-chords".equals(root.optString("app")) || songs == null) {
                Toast.makeText(this, "Not a BandApp chord-songs file", Toast.LENGTH_LONG).show();
                return;
            }
            java.util.List<String> names = chordSongNames();
            SharedPreferences.Editor ed = prefs.edit();
            int n = 0;
            int skipped = 0, updated = 0;
            for (java.util.Iterator<String> it = songs.keys(); it.hasNext(); ) {
                String raw = it.next();
                String chords; long mtime;
                Object v = songs.opt(raw);
                if (v instanceof org.json.JSONObject) {           // {c, t}
                    chords = ((org.json.JSONObject) v).optString("c");
                    mtime = ((org.json.JSONObject) v).optLong("t", 0);
                } else {                                          // legacy: plain string
                    chords = songs.optString(raw); mtime = 0;
                }
                String name = raw.trim().replace(CHORD_SONG_SEP, "");
                if (name.isEmpty()) continue;
                boolean exists = names.contains(name);
                if (exists) {
                    long curT = prefs.getLong("chord_song_t_" + name, 0);
                    if (mtime <= curT) { skipped++; continue; }   // ours is same/newer → keep
                    updated++;                                    // theirs is newer → overwrite
                } else {
                    names.add(name); n++;
                }
                ed.putString("chord_song_" + name, chords);
                ed.putLong("chord_song_t_" + name, mtime);
            }
            ed.apply();
            putChordSongNames(names);
            Toast.makeText(this, n + " added · " + updated + " updated · " + skipped + " kept",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private TextView chordIcon(String glyph) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(COLOR_TEXT);
        t.setClickable(true);
        t.setBackground(pillBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        return t;
    }

    private void chordSongDialog() {
        if (chordBoard != null) chordBoard.releaseAll();
        engine.allNotesOff();
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView title = new TextView(this);
        title.setText("Songs");
        title.setTextColor(COLOR_TEAL);
        title.setTextSize(16);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        content.addView(title, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("Name the chords on screen and tap ＋ to save. ▶ sets a song's chords · 💾 overwrites it · ✕ removes it.");
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(12);
        content.addView(hint, topMargin(matchWrap(), 4));

        LinearLayout newRow = new LinearLayout(this);
        newRow.setOrientation(LinearLayout.HORIZONTAL);
        newRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText nameField = new EditText(this);
        textIme(nameField);
        nameField.setHint("Song name");
        nameField.setHintTextColor(COLOR_DIM);
        nameField.setTextColor(COLOR_TEXT);
        nameField.setTextSize(15);
        nameField.setSingleLine(true);
        nameField.setPadding(dp(12), dp(10), dp(12), dp(10));
        nameField.setBackground(panelBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        newRow.addView(nameField, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView saveBtn = chordIcon("＋");
        newRow.addView(saveBtn, leftMargin(new LinearLayout.LayoutParams(dp(48), dp(42)), 8));
        content.addView(newRow, topMargin(matchWrap(), 10));

        // Export / import the whole song list (transfer to another device).
        LinearLayout ioRow = new LinearLayout(this);
        ioRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView exp = chordIcon("⬆  Export");
        exp.setTextSize(13);
        exp.setOnClickListener(v -> { dialog.dismiss(); exportChordSongs(); });
        ioRow.addView(exp, new LinearLayout.LayoutParams(0, dp(38), 1f));
        TextView imp = chordIcon("⬇  Import");
        imp.setTextSize(13);
        imp.setOnClickListener(v -> { dialog.dismiss(); importChordSongs(); });
        ioRow.addView(imp, leftMargin(new LinearLayout.LayoutParams(0, dp(38), 1f), 8));
        content.addView(ioRow, topMargin(matchWrap(), 8));

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list, topMargin(matchWrap(), 10));

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> populateChordSongs(list, dialog, refresh[0]);
        refresh[0].run();

        saveBtn.setOnClickListener(v -> {
            String n = nameField.getText().toString().trim();
            if (n.isEmpty()) {
                Toast.makeText(this, "Name the song first", Toast.LENGTH_SHORT).show();
                return;
            }
            saveChordSong(n);
            nameField.setText("");
            refresh[0].run();
            Toast.makeText(this, "Saved " + n, Toast.LENGTH_SHORT).show();
        });

        // One outer scroll for the whole sheet (safe at every UI scale).
        ScrollView outer = new ScrollView(this);
        outer.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(outer, new android.view.ViewGroup.LayoutParams(
                dialogWidth(0.92f, 440), android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.show();
    }

    private void populateChordSongs(LinearLayout list, final Dialog dialog, final Runnable refresh) {
        list.removeAllViews();
        java.util.List<String> names = chordSongNames();
        if (names.isEmpty()) {
            list.addView(detailText("No songs yet."), topMargin(matchWrap(), 6));
            return;
        }
        for (final String name : names) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(7), dp(7), dp(7));
            row.setBackground(moduleBackground(COLOR_SURFACE_RAISED, COLOR_BORDER,
                    COLOR_TEAL, true));

            TextView nm = new TextView(this);
            nm.setText(name);
            nm.setTextColor(COLOR_TEXT);
            nm.setTextSize(15);
            nm.setSingleLine(true);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(nm, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView play = chordIcon("▶");
            play.setTextColor(COLOR_GREEN);
            play.setOnClickListener(v -> {
                playChordSong(name);
                dialog.dismiss();
                showChordMode();
                Toast.makeText(this, name, Toast.LENGTH_SHORT).show();
            });
            row.addView(play, leftMargin(new LinearLayout.LayoutParams(dp(44), dp(38)), 6));

            TextView over = chordIcon("💾");
            over.setOnClickListener(v -> {
                saveChordSong(name);
                Toast.makeText(this, "Overwrote " + name, Toast.LENGTH_SHORT).show();
            });
            row.addView(over, leftMargin(new LinearLayout.LayoutParams(dp(44), dp(38)), 6));

            TextView del = chordIcon("✕");
            del.setTextColor(Color.rgb(220, 96, 96));
            del.setOnClickListener(v -> {
                removeChordSong(name);
                refresh.run();
            });
            row.addView(del, leftMargin(new LinearLayout.LayoutParams(dp(44), dp(38)), 6));

            list.addView(row, topMargin(matchWrap(), 6));
        }
    }

    private void showChordMode() {
        onChordMode = true;
        ensurePianoEngine();
        loadChordSlots();
        chordPlayMode = prefs.getInt("chord_play_mode",
                prefs.getBoolean("chord_strum", false)
                        ? CHORD_PLAY_STRUM : CHORD_PLAY_BLOCK);
        chordPlayMode = Math.max(CHORD_PLAY_BLOCK,
                Math.min(CHORD_PLAY_LIVELY, chordPlayMode));
        chordStrumMs = Math.max(1, Math.min(1000,
                prefs.getInt("chord_strum_ms", 30)));
        engine.setKeySplitConfig(0, -1);   // chords play one sound, never key-split
        applyLayers();                     // honour Layer Mode (side A blend)

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(8), dp(6), dp(8), dp(6));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(backArrowButton(this::closeChordMode),
                new LinearLayout.LayoutParams(dp(40), dp(40)));

        // Same play controls as Play Keys (the ones that apply to chords):
        // Sustain / Reverb / Slide · Sound 1 · Layers · Songs.
        final TextView sustainPill = transportPill("Sustain");
        styleTogglePill(sustainPill, sustainOn);
        sustainPill.setOnClickListener(v -> {
            sustainOn = !sustainOn;
            engine.setSustain(sustainOn);
            if (!sustainOn) { engine.setSustainPedal(false); midiPedalDown = false; }
            styleTogglePill(sustainPill, sustainOn);
            saveEffectPrefs();
        });
        final TextView reverbPill = transportPill("Reverb");
        styleTogglePill(reverbPill, reverbOn);
        reverbPill.setOnClickListener(v -> {
            reverbOn = !reverbOn;
            engine.setReverb(reverbOn);
            styleTogglePill(reverbPill, reverbOn);
            saveEffectPrefs();
        });
        final TextView slidePill = transportPill(pianoGlideMono && pianoGlideOn ? "Mono" : "Slide");
        styleTogglePill(slidePill, pianoGlideOn);
        slidePill.setOnClickListener(v -> {
            if (!pianoGlideOn) { pianoGlideOn = true; pianoGlideMono = false; }
            else if (!pianoGlideMono) { pianoGlideMono = true; }
            else { pianoGlideOn = false; pianoGlideMono = false; }
            pushPianoGlide();
            engine.allNotesOff();
            slidePill.setText(pianoGlideMono && pianoGlideOn ? "Mono" : "Slide");
            styleTogglePill(slidePill, pianoGlideOn);
            prefs.edit().putBoolean("piano_glide", pianoGlideOn)
                    .putBoolean("piano_glide_mono", pianoGlideMono).apply();
        });
        final TextView sound1Pill = transportPill(
                "Snd 1: " + pianoSoundName(false) + "  ▾");
        styleTogglePill(sound1Pill, false);
        sound1Pill.setTextColor(contrastTextColor(COLOR_SURFACE_RAISED));
        sound1Pill.setOnClickListener(v -> pianoSoundPopup(false, this::showChordMode));
        final TextView layersPill = transportPill("⧉ Layers");
        styleTogglePill(layersPill, layerMode);
        layersPill.setOnClickListener(v -> layersDialog());
        final TextView songsPill = transportPill("♫ Songs");
        songsPill.setOnClickListener(v -> chordSongDialog());
        final TextView slotsPill = transportPill(chordSlotCount + " Slots");
        styleTogglePill(slotsPill, chordSlotCount != CHORD_SLOTS_DEFAULT);
        slotsPill.setOnClickListener(v -> chordSlotCountDialog());
        final TextView playModePill = transportPill(
                "Play: " + CHORD_PLAY_NAMES[chordPlayMode]);
        styleTogglePill(playModePill, chordPlayMode != CHORD_PLAY_BLOCK);
        playModePill.setOnClickListener(v -> chordPlayModeDialog(playModePill));
        playModePill.setOnLongClickListener(v -> {
            chordStrumTimingDialog();
            return true;
        });

        LinearLayout pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setGravity(Gravity.CENTER_VERTICAL);
        addPillCluster(pills, "PLAY", java.util.Arrays.asList(
                (View) sustainPill, reverbPill, slidePill, playModePill), false);
        addPillCluster(pills, "SOUND", java.util.Arrays.asList((View) sound1Pill), true);
        addPillCluster(pills, "LAYERS", java.util.Arrays.asList((View) layersPill), true);
        addPillCluster(pills, "CHORDS", java.util.Arrays.asList(
                (View) slotsPill, songsPill), true);
        HorizontalScrollView pillScroll = new HorizontalScrollView(this);
        pillScroll.setHorizontalScrollBarEnabled(false);
        pillScroll.addView(pills);
        LinearLayout.LayoutParams psLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        psLp.leftMargin = dp(8);
        bar.addView(pillScroll, psLp);
        screen.addView(bar, matchWrap());

        chordBoard = new ChordBoardView(this);
        chordBoard.setAccent(toneAccentStatic(currentPreset));
        chordBoard.setPlayMode(chordPlayMode);
        chordBoard.setStrumDelayMs(chordStrumMs);
        chordBoard.setChords(chordRoot, chordType);
        chordBoard.setListener(new ChordBoardListener() {
            @Override public void onNote(int note, float vel, boolean down) {
                if (down) engine.noteOn(note, vel); else engine.noteOff(note);
            }
            @Override public int[] voicing(int slot) {
                return chordVoicingForSlot(slot);
            }
            @Override public String name(int slot) {
                return chordName(chordRoot[slot], chordType[slot], chordBass[slot]);
            }
            @Override public void onPickChord(int slot) { chordPickerDialog(slot); }
        });
        screen.addView(chordBoard, topMargin(weight(1.0f), 8));

        enablePadInsets(screen, screen);
        paintStage(screen);
        setContentView(screen);
    }

    private void chordPlayModeDialog(final TextView pill) {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("Piano Play Mode");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        String[] descriptions = {
                "Tight chord with every note together",
                "Natural studio timing and dynamics",
                "Low notes rolling upward",
                "High notes rolling downward",
                "Bass first, then the upper voices",
                "Piano-pattern note ordering",
                "Even low-to-high spacing",
                "Bright bass-to-melody lift",
                "Syncopated outer-and-inner attack",
                "Quick bouncing piano flourish"
        };
        for (int i = 0; i < CHORD_PLAY_NAMES.length; i++) {
            final int mode = i;
            boolean selected = chordPlayMode == mode;
            TextView item = menuItem((selected ? "●  " : "○  ")
                    + CHORD_PLAY_NAMES[i] + "\n" + descriptions[i], () -> {
                chordPlayMode = mode;
                prefs.edit().putInt("chord_play_mode", mode)
                        .putBoolean("chord_strum", mode == CHORD_PLAY_STRUM).apply();
                if (pill != null) {
                    pill.setText("Play: " + CHORD_PLAY_NAMES[mode]);
                    styleTogglePill(pill, mode != CHORD_PLAY_BLOCK);
                }
                if (chordBoard != null) chordBoard.setPlayMode(mode);
                dialog.dismiss();
            });
            content.addView(item, topMargin(matchWrap(), i == 0 ? 10 : 6));
        }
        presentMenu(dialog, content, dialogWidth(0.80f, 480));
    }

    private void chordProgressionDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("Piano Progressions");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        addChordProgression(content, dialog, "Joyous Funk",
                "Cmaj9 · A13 · Dm9 · G13 · Em7 · A7#9 · Dm9 · G13",
                new int[]{0, 9, 2, 7, 4, 9, 2, 7},
                new int[]{15, 18, 10, 18, 3, 37, 10, 18},
                null);
        addChordProgression(content, dialog, "Sunny Disco",
                "Fmaj7 · G6 · Em7 · A7 · Dm9 · G13 · Cmaj9 · C6/9",
                new int[]{5, 7, 4, 9, 2, 7, 0, 0},
                new int[]{4, 5, 3, 2, 10, 18, 15, 24},
                null);
        addChordProgression(content, dialog, "Neo Soul Lift",
                "Cmaj9 · Bm7b5 · E7#9 · Am9 · Gm9 · C13 · Fmaj9 · G13",
                new int[]{0, 11, 4, 9, 7, 0, 5, 7},
                new int[]{15, 32, 37, 10, 10, 18, 15, 18},
                null);
        addChordProgression(content, dialog, "Lively Gospel",
                "C6/9 · E7 · Am9 · D7/F# · G13 · C/E · F6/9 · G13",
                new int[]{0, 4, 9, 2, 7, 0, 5, 7},
                new int[]{24, 2, 10, 2, 18, 0, 24, 18},
                new int[]{-1, -1, -1, 6, -1, 4, -1, -1});

        presentMenu(dialog, content, dialogWidth(0.88f, 540));
    }

    private void addChordProgression(LinearLayout content, Dialog dialog,
            String name, String detail, int[] roots, int[] types, int[] basses) {
        TextView item = menuItem(name + "\n" + detail, () -> {
            chordSlotCount = Math.min(CHORD_SLOTS_MAX,
                    Math.min(roots.length, types.length));
            for (int i = 0; i < chordSlotCount; i++) {
                chordRoot[i] = roots[i];
                chordType[i] = types[i];
                chordBass[i] = basses != null && i < basses.length ? basses[i] : -1;
            }
            saveChordSlots();
            engine.allNotesOff();
            dialog.dismiss();
            showChordMode();
        });
        content.addView(item, topMargin(matchWrap(), 8));
    }

    private void chordStrumTimingDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("Play interval");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        TextView value = new TextView(this);
        value.setText(chordStrumMs + " ms");
        value.setTextColor(COLOR_TEAL);
        value.setTextSize(16);
        value.setGravity(Gravity.CENTER);
        content.addView(value, topMargin(matchWrap(), 12));

        SeekBar timing = new SeekBar(this);
        timing.setMax(999);
        timing.setProgress(chordStrumMs - 1);
        timing.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                    boolean fromUser) {
                chordStrumMs = progress + 1;
                value.setText(chordStrumMs + " ms");
                if (chordBoard != null) chordBoard.setStrumDelayMs(chordStrumMs);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt("chord_strum_ms", chordStrumMs).apply();
            }
        });
        content.addView(timing, topMargin(matchWrap(), 8));

        TextView done = transportPill("Done");
        done.setGravity(Gravity.CENTER);
        done.setOnClickListener(v -> {
            prefs.edit().putInt("chord_strum_ms", chordStrumMs).apply();
            dialog.dismiss();
        });
        content.addView(done, topMargin(matchWrap(), 12));
        presentMenu(dialog, content, dialogWidth(0.72f, 420));
    }

    private void closeChordMode() {
        onChordMode = false;
        if (chordBoard != null) chordBoard.releaseAll();
        chordBoard = null;
        engine.allNotesOff();
        showInstrumentScreen();
    }

    private void chordSlotCountDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(dialogSheet());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(16), dp(18), dp(16));

        TextView title = new TextView(this);
        title.setText("Chord Slots");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        final TextView value = new TextView(this);
        value.setText(chordSlotCount + " chords");
        value.setTextColor(COLOR_TEAL);
        value.setTextSize(22);
        value.setGravity(Gravity.CENTER);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        content.addView(value, topMargin(matchWrap(), 10));

        SeekBar slots = new SeekBar(this);
        slots.setMax(CHORD_SLOTS_MAX - CHORD_SLOTS_MIN);
        slots.setProgress(chordSlotCount - CHORD_SLOTS_MIN);
        slots.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText((CHORD_SLOTS_MIN + progress) + " chords");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        content.addView(slots, topMargin(matchWrap(), 8));

        LinearLayout range = new LinearLayout(this);
        range.setOrientation(LinearLayout.HORIZONTAL);
        TextView low = labelText(String.valueOf(CHORD_SLOTS_MIN));
        TextView high = labelText(String.valueOf(CHORD_SLOTS_MAX));
        low.setGravity(Gravity.START);
        high.setGravity(Gravity.END);
        range.addView(low, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        range.addView(high, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(range, matchWrap());

        TextView apply = menuItem("Apply", () -> {
            if (chordBoard != null) chordBoard.releaseAll();
            chordSlotCount = CHORD_SLOTS_MIN + slots.getProgress();
            saveChordSlots();
            dialog.dismiss();
            showChordMode();
        });
        apply.setTextColor(COLOR_GREEN);
        content.addView(apply, topMargin(matchWrap(), 12));
        presentMenu(dialog, content, dialogWidth(0.70f, 380));
    }

    // Choose Chords: Root Note | Chords Type | Bass, side by side. The Type
    // column is labelled with the provisional root AND bass, so you read the
    // real chord ("Bb/F") before committing.
    //
    // The WHOLE dialog is one ScrollView and the columns size to their content
    // (no fixed heights, no inner scrollers). Fixed column heights overflowed
    // the short landscape screen at reduced UI scales and clipped the bottom
    // rows with no way to reach them.
    private void chordPickerDialog(final int slot) {
        if (chordBoard != null) chordBoard.releaseAll();
        engine.allNotesOff();
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView title = new TextView(this);
        title.setText("Choose Chords");
        title.setTextColor(COLOR_TEAL);
        title.setTextSize(16);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        content.addView(title, matchWrap());

        LinearLayout heads = new LinearLayout(this);
        heads.setOrientation(LinearLayout.HORIZONTAL);
        String[] headNames = {"Root Note", "Chords Type", "Bass"};
        float[] headWeights = {1f, 1.5f, 1f};
        for (int i = 0; i < 3; i++) {
            TextView h = new TextView(this);
            h.setText(headNames[i]);
            h.setTextColor(COLOR_MUTED);
            h.setTextSize(11);
            h.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, headWeights[i]);
            if (i > 0) hLp.leftMargin = dp(6);
            heads.addView(h, hLp);
        }
        content.addView(heads, topMargin(matchWrap(), 8));

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final LinearLayout rootList = new LinearLayout(this);
        rootList.setOrientation(LinearLayout.VERTICAL);
        row.addView(rootList, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final LinearLayout typeList = new LinearLayout(this);
        typeList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f);
        tLp.leftMargin = dp(6);
        row.addView(typeList, tLp);

        final LinearLayout bassList = new LinearLayout(this);
        bassList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bLp.leftMargin = dp(6);
        row.addView(bassList, bLp);

        content.addView(row, topMargin(matchWrap(), 6));

        // Root and bass stay provisional until a chord type is tapped.
        final int[] pickRoot = {chordRoot[slot]};
        final int[] pickBass = {chordBass[slot]};
        final View[] typeSel = new View[1];
        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            rootList.removeAllViews();
            for (int r = 0; r < 12; r++) {
                final int root = r;
                boolean sel = pickRoot[0] == root;
                TextView item = chordCell(ROOT_NAMES[root], sel);
                item.setOnClickListener(v -> { pickRoot[0] = root; rebuild[0].run(); });
                rootList.addView(item, topMargin(matchWrap(), r == 0 ? 0 : 4));
            }
            typeList.removeAllViews();
            typeSel[0] = null;
            for (int t = 0; t < CHORD_SUFFIX.length; t++) {
                final int type = t;
                boolean sel = (chordRoot[slot] == pickRoot[0] && chordType[slot] == type
                        && chordBass[slot] == pickBass[0]);
                TextView item = chordCell(chordName(pickRoot[0], type, pickBass[0]), sel);
                item.setOnClickListener(v -> {
                    chordRoot[slot] = pickRoot[0];
                    chordType[slot] = type;
                    chordBass[slot] = pickBass[0];
                    saveChordSlots();
                    if (chordBoard != null) chordBoard.setChords(chordRoot, chordType);
                    dialog.dismiss();
                });
                if (sel) typeSel[0] = item;
                typeList.addView(item, topMargin(matchWrap(), t == 0 ? 0 : 4));
            }
            bassList.removeAllViews();
            // The dash clears the slash bass (plain root-position chord).
            TextView none = chordCell("\u2014", pickBass[0] < 0);
            none.setOnClickListener(v -> { pickBass[0] = -1; rebuild[0].run(); });
            bassList.addView(none, matchWrap());
            for (int b = 0; b < 12; b++) {
                final int bass = b;
                boolean sel = pickBass[0] == bass;
                TextView item = chordCell(ROOT_NAMES[bass], sel);
                item.setOnClickListener(v -> { pickBass[0] = bass; rebuild[0].run(); });
                bassList.addView(item, topMargin(matchWrap(), 4));
            }
        };
        rebuild[0].run();

        // One outer scroll for the whole sheet: safe at every UI scale.
        final ScrollView outer = new ScrollView(this);
        outer.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(outer, new android.view.ViewGroup.LayoutParams(
                dialogWidth(0.94f, 430), android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.show();
        outer.post(() -> {
            if (typeSel[0] != null) {
                outer.scrollTo(0, Math.max(0, row.getTop() + typeSel[0].getTop() - dp(70)));
            }
        });
    }

    private TextView chordCell(String label, boolean selected) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(12), dp(10), dp(12));
        t.setClickable(true);
        t.setTextColor(selected ? Color.WHITE : COLOR_MUTED);
        t.setBackground(moduleBackground(selected ? darken(COLOR_TEAL) : COLOR_SURFACE_RAISED,
                selected ? COLOR_TEAL : COLOR_BORDER, COLOR_TEAL, true));
        return t;
    }

    // A modal sound picker (popup) for Sound 1 or Sound 2, reusing the piano
    // sound list. onPicked runs after a selection is applied.
    private void pianoSoundPopup(final boolean soundTwo, final Runnable onPicked) {
        // Release any held keys first: once the picker (and its soft keyboard) is
        // up, the manuals stop getting touch events, so a held note would hang.
        if (fpMelody != null) fpMelody.releaseAll();
        if (fpChord != null) fpChord.releaseAll();
        engine.allNotesOff();
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
            // Landscape apps otherwise pop a full-screen IME "extract" editor that
            // covers everything and traps the user ("search left me hanging").
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText(soundTwo ? "Sound 2" : "Sound 1");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        EditText search = new EditText(this);
        search.setHint("Search piano sounds");
        search.setHintTextColor(COLOR_DIM);
        search.setTextColor(COLOR_TEXT);
        search.setTextSize(15);
        search.setSingleLine(true);
        searchIme(search);
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        search.setBackground(panelBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        content.addView(search, topMargin(matchWrap(), 10));

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        // Cap the scroll height so a long list scrolls inside the sheet instead
        // of overflowing the screen (picker-ui-rules).
        final ScrollView scroll = new ScrollView(this);
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        int maxListH = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
        content.addView(scroll, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxListH), 10));

        populatePianoPopupList(list, "", soundTwo, dialog, onPicked);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                populatePianoPopupList(list, s.toString(), soundTwo, dialog, onPicked);
            }
        });

        dialog.setContentView(content, new android.view.ViewGroup.LayoutParams(dp(420),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.show();
        // Open at the current selection, not the top of the list.
        scroll.post(() -> {
            if (pickerSelectedRow != null) {
                scroll.scrollTo(0, Math.max(0, pickerSelectedRow.getTop() - dp(72)));
            }
        });
    }

    private void populatePianoPopupList(LinearLayout list, String filter, final boolean soundTwo,
                                        final Dialog dialog, final Runnable onPicked) {
        list.removeAllViews();
        pickerSelectedRow = null;
        String f = filter.trim().toLowerCase(Locale.US);
        String currentCategory = null;
        java.util.List<ExternalSf2File> externalMatches = matchingExternalSf2(f);
        if (!externalMatches.isEmpty()) {
            String activeExternal = soundTwo ? activeExternalDualUri : activeExternalMainUri;
            boolean externalSelected = activeExternal != null;
            TextView external = chordCell(
                    externalSelected
                            ? "External: " + externalSf2Name(activeExternal) + "  \u25be"
                            : "External SF2 (" + externalMatches.size() + ")",
                    externalSelected);
            external.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            if (externalSelected) pickerSelectedRow = external;
            external.setOnClickListener(v -> showExternalSf2Picker(
                    soundTwo, 0, f, () -> {
                        if (onPicked != null) onPicked.run();
                        dialog.dismiss();
                    }));
            list.addView(external, topMargin(matchWrap(), 4));
        }
        for (final TonePreset preset : TonePreset.forMode(InstrumentMode.PIANO)) {
            if ((!virtualGuitarMidiMode && preset == TonePreset.VIRTUAL_GUITAR_STARTER)
                    || (virtualGuitarMidiMode && !isGuitarPreset(preset))) {
                continue;
            }
            if (!f.isEmpty()
                    && !preset.label.toLowerCase(Locale.US).contains(f)
                    && !preset.detail.toLowerCase(Locale.US).contains(f)
                    && !preset.category.toLowerCase(Locale.US).contains(f)) {
                continue;
            }
            String category = pianoCategory(preset);
            if (!category.equals(currentCategory)) {
                currentCategory = category;
                TextView head = new TextView(this);
                head.setText(category.toUpperCase(Locale.US));
                head.setTextColor(COLOR_AMBER);
                head.setTextSize(11);
                head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                list.addView(head, topMargin(matchWrap(), list.getChildCount() == 0 ? 4 : 14));
            }
            boolean selected = soundTwo
                    ? activeExternalDualUri == null && preset == dualPreset
                    : activeExternalMainUri == null && preset == currentPreset;
            int accent = toneAccentStatic(preset);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(12), dp(10), dp(12), dp(10));
            item.setClickable(true);
            item.setBackground(moduleBackground(selected ? darken(accent) : COLOR_SURFACE_RAISED,
                    selected ? accent : COLOR_BORDER, accent, true));
            if (selected) {
                pickerSelectedRow = item;
            }
            item.setOnClickListener(v -> {
                if (soundTwo) {
                    clearExternalSf2Selection(true);
                    dualPreset = preset;
                    dualOn = true;
                    applyDualSound();
                }
                else { selectPreset(preset); }
                if (onPicked != null) onPicked.run();
                dialog.dismiss();
            });
            TextView nm = new TextView(this);
            nm.setText(preset.label);
            nm.setTextColor(selected ? COLOR_TEXT : COLOR_MUTED);
            nm.setTextSize(14);
            item.addView(nm, matchWrap());
            TextView dt = new TextView(this);
            dt.setText(preset.detail);
            dt.setTextColor(COLOR_DIM);
            dt.setTextSize(11);
            item.addView(dt, topMargin(matchWrap(), 2));
            list.addView(item, topMargin(matchWrap(), 6));
        }
        if (list.getChildCount() == 0) {
            list.addView(detailText("No matching sounds."), topMargin(matchWrap(), 8));
        }
    }

    private void ensurePianoEngine() {
        if (engine.isRunning()) return;
        if (currentRoute != InputRoute.MIDI && !hasRecordAudioPermission()) {
            currentRoute = InputRoute.MIDI;   // MIDI needs no mic; keys still play
        }
        AudioDeviceRouter.DeviceSelection devices = router.select(currentRoute);
        engine.start(currentMode, currentPreset, currentRoute,
                resolvePreferredInput(devices.inputDeviceId),
                resolvePreferredOutput(devices.outputDeviceId));
    }

    private void closeFullPiano() {
        onFullPiano = false;
        lastSplitAnimMode = -1;   // re-animate the split next time Play Keys opens
        engine.setVibrato(0f);    // the vibrato lever is Full Keys only
        engine.setPianoSoft(0f);  // Tone softness is Full Keys only too
        engine.setManualSplit(false);   // back to normal dual-layer routing
        if (fpMelody != null) fpMelody.releaseAll();
        if (fpChord != null) fpChord.releaseAll();
        fpMelody = null;
        fpChord = null;
        fpSound2Pill = null;
        applyPianoProgram();   // restore normal dual pitch-split routing
        showInstrumentScreen();
    }

    // Channel-strip mixer for the 8 layer channels: a vertical fader + live LED
    // meter each. Opened from Full Keys; needs Layer Mode (the layer channels
    // only sound then), so entering the mixer turns it on.
    private void showMixer() {
        onMixer = true;
        onFullPiano = false;
        ensurePianoEngine();
        if (!layerMode) { layerMode = true; prefs.edit().putBoolean("layer_mode", true).apply(); }
        applyPianoProgram();
        applyLayers();

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(8), dp(6), dp(8), dp(6));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(backArrowButton(this::closeMixer),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = new TextView(this);
        title.setText("MIXER");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(14);
        title.setLetterSpacing(0.12f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(8);
        bar.addView(title, tlp);
        screen.addView(bar, matchWrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        mixerStrips = new ChannelStripView[9];
        row.addView(mixerColumn(1, "1", () -> pianoSoundName(false),
                () -> pianoSoundPopup(false, this::showMixer),
                layer1Level, v -> layer1Level = v, COLOR_TEAL));
        row.addView(mixerColumn(2, "2", () -> layerLabel(layer2Preset),
                () -> { afterLayerPick = this::showMixer; layerSoundPicker(2); },
                layer2Level, v -> layer2Level = v, COLOR_TEAL));
        row.addView(mixerColumn(3, "3", () -> layerLabel(layer3Preset),
                () -> { afterLayerPick = this::showMixer; layerSoundPicker(3); },
                layer3Level, v -> layer3Level = v, COLOR_TEAL));
        row.addView(mixerColumn(4, "4", () -> layerLabel(layer4Preset),
                () -> { afterLayerPick = this::showMixer; layerSoundPicker(4); },
                layer4Level, v -> layer4Level = v, COLOR_TEAL));
        row.addView(mixerColumn(5, "5", () -> dualOn ? pianoSoundName(true) : "Off",
                () -> pianoSoundPopup(true, this::showMixer),
                layer5Level, v -> layer5Level = v, COLOR_PURPLE));
        row.addView(mixerColumn(6, "6", () -> layerLabel(layer6Preset),
                () -> { afterLayerPick = this::showMixer; layerSoundPicker(6); },
                layer6Level, v -> layer6Level = v, COLOR_PURPLE));
        row.addView(mixerColumn(7, "7", () -> layerLabel(layer7Preset),
                () -> { afterLayerPick = this::showMixer; layerSoundPicker(7); },
                layer7Level, v -> layer7Level = v, COLOR_PURPLE));
        row.addView(mixerColumn(8, "8", () -> layerLabel(layer8Preset),
                () -> { afterLayerPick = this::showMixer; layerSoundPicker(8); },
                layer8Level, v -> layer8Level = v, COLOR_PURPLE));
        screen.addView(row, topMargin(weight(1f), 6));

        // A thin keyboard along the bottom so you can play while you mix — notes
        // feed the same layer engine, so the meters bounce as you play.
        int accent = toneAccentStatic(currentPreset);
        PianoBoardView kb = new PianoBoardView(this);
        kb.setAccent(accent);
        kb.setChord(false);
        kb.setSound2(false);
        kb.setVisibleWhites(melodyZoom);
        kb.setScrollWhite(melodyBaseWhite);
        kb.setListener(this::playFullPianoNote);
        kb.setStateSink((zoom, base) -> { melodyZoom = zoom; melodyBaseWhite = base; });
        screen.addView(fullPianoNavRow("KEYBOARD", kb, accent), topMargin(matchWrap(), 4));
        screen.addView(kb, topMargin(weight(0.62f), 4));

        enablePadInsets(screen, screen);
        paintStage(screen);
        setContentView(screen);

        if (mixerPoll == null) {
            mixerPoll = new Runnable() {
                @Override public void run() {
                    if (!onMixer) return;
                    engine.getLayerMeters(mixerMeterBuf);
                    if (mixerStrips != null) {
                        for (int i = 1; i <= 8; i++) {
                            if (mixerStrips[i] != null) mixerStrips[i].setMeter(mixerMeterBuf[LAYER_CH[i]]);
                        }
                    }
                    handler.postDelayed(this, 45);
                }
            };
        }
        handler.removeCallbacks(mixerPoll);
        handler.postDelayed(mixerPoll, 45);
    }

    private View mixerColumn(int layerNum, String num, java.util.concurrent.Callable<String> label,
            Runnable onPick, float level, FloatSetter setField, int accent) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(4), dp(6), dp(4), dp(6));
        col.setBackground(moduleBackground(COLOR_SURFACE_RAISED, COLOR_BORDER, accent, true));

        TextView name = new TextView(this);
        try { name.setText(num + "  " + label.call()); } catch (Exception e) { name.setText(num); }
        name.setTextColor(accent);
        name.setTextSize(10);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setClickable(true);
        name.setPadding(dp(2), dp(3), dp(2), dp(4));
        name.setOnClickListener(v -> onPick.run());
        col.addView(name, matchWrap());

        final TextView pct = new TextView(this);
        pct.setTextColor(COLOR_MUTED);
        pct.setTextSize(10);
        pct.setGravity(Gravity.CENTER);
        pct.setText(Math.round(level * 100) + "");

        ChannelStripView strip = new ChannelStripView(this);
        strip.setAccent(accent);
        strip.setValue(level);
        strip.setOnChange(v -> {
            setField.set(v);
            pct.setText(Math.round(v * 100) + "");
            saveLayers();
            applyLayers();
        });
        mixerStrips[layerNum] = strip;
        col.addView(strip, topMargin(weight(1f), 4));
        col.addView(pct, topMargin(matchWrap(), 3));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        col.setLayoutParams(lp);
        return col;
    }

    private void closeMixer() {
        onMixer = false;
        if (mixerPoll != null) handler.removeCallbacks(mixerPoll);
        mixerStrips = null;
        engine.allNotesOff();
        showFullPiano();
    }

    private Button drumActionButton(String text, Runnable onClick) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(COLOR_TEXT);
        b.setTextSize(14);
        b.setMinHeight(dp(44));
        b.setBackground(animatedButtonBackground(
                COLOR_SURFACE_RAISED, dp(8), COLOR_PURPLE));
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    // Landscape Kit Mode: the flat photo drum kit for two-handed drumming.
    private void showFullPads() {
        onFullPads = true;

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(10), dp(6), dp(10), dp(6));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(currentPreset.label + " · Kit Mode");
        t.setTextColor(COLOR_TEXT);
        t.setTextSize(15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bar.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        // While editing: the piece catalog + 5 layout-preset slots (each slot
        // remembers its own arrangement; edits save into the active slot).
        kitLayoutSlot = prefs.getInt("kit_slot", 0);
        if (kitEditMode) {
            Button add = chipButton("+ Add");
            add.setOnClickListener(v -> addKitPieceDialog());
            styleChipButton(add, false);
            LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(dp(92),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            addLp.rightMargin = dp(8);
            bar.addView(add, addLp);
            for (int s = 0; s < 5; s++) {
                final int slot = s;
                Button chip = chipButton(String.valueOf(s + 1));
                chip.setOnClickListener(v -> {
                    kitLayoutSlot = slot;
                    prefs.edit().putInt("kit_slot", slot).apply();
                    showFullPads();
                });
                styleChipButton(chip, slot == kitLayoutSlot);
                LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(dp(44),
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                chipLp.rightMargin = dp(s == 4 ? 8 : 4);
                bar.addView(chip, chipLp);
            }
            // Move one arrangement in or out on its own (the whole-app Backup
            // covers all five slots; this is for a single layout).
            Button io = chipButton("⇅ Layout");
            io.setOnClickListener(v -> kitLayoutIoDialog());
            styleChipButton(io, false);
            LinearLayout.LayoutParams ioLp = new LinearLayout.LayoutParams(dp(104),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            ioLp.rightMargin = dp(8);
            bar.addView(io, ioLp);
        }
        // Change the sound kit without leaving Kit Mode: the standard searchable
        // kit picker (scroll-safe, opens at the current kit).
        Button kitBtn = chipButton("Kit ▾");
        kitBtn.setOnClickListener(v -> showProgramPicker());
        styleChipButton(kitBtn, false);
        LinearLayout.LayoutParams kitLp = new LinearLayout.LayoutParams(dp(84),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        kitLp.rightMargin = dp(8);
        bar.addView(kitBtn, kitLp);
        // Pencil edit toggle (highlighted while editing).
        Button edit = chipButton(kitEditMode ? "✎ Editing" : "✎ Edit");
        edit.setOnClickListener(v -> { kitEditMode = !kitEditMode; showFullPads(); });
        styleChipButton(edit, kitEditMode);
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(dp(112),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        editLp.rightMargin = dp(8);
        bar.addView(edit, editLp);
        Button close = chipButton("Close");
        close.setOnClickListener(v -> closeFullPads());
        styleChipButton(close, false);
        bar.addView(close, new LinearLayout.LayoutParams(dp(120),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        screen.addView(bar, matchWrap());

        // Kit Mode: the flat photo kit is the only full-pads layout now.
        drumPadsView = null;
        drumKitView = new DrumKitView(this);
        drumKitView.setListener(this::onDrumPad);
        drumKitView.setEditListener(s -> {
            prefs.edit().putString("kit_layout_" + kitLayoutSlot, s).apply();
            applyDrumKit();   // re-route per-piece sounds when the layout changes
        });
        drumKitView.setPieceEditListener(this::changeKitPieceDialog);
        // Slot 0 inherits the pre-preset single layout ("kit_layout") if present.
        String layout = prefs.getString("kit_layout_" + kitLayoutSlot,
                kitLayoutSlot == 0 ? prefs.getString("kit_layout", null) : null);
        drumKitView.setLayout(layout);
        drumKitView.setEditMode(kitEditMode);
        screen.addView(drumKitView, topMargin(weight(1.0f), 8));
        applyDrumKit();   // engage per-piece custom routing if this layout uses it
        refreshPadAvailability();

        enablePadInsets(screen, screen);
        paintStage(screen);
        setContentView(screen);
    }

    // Export/import just the arrangement in the active kit slot, as a small
    // .kit file: moving one layout between slots or devices without carrying
    // the whole app's settings.
    private void kitLayoutIoDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("Kit Layout " + (kitLayoutSlot + 1));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("Save or load this complete slot: positions, sizes, order, "
                + "piece sounds, selected kit and MIDI sound assignments.");
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(12);
        content.addView(hint, topMargin(matchWrap(), 4));

        content.addView(menuItem("⬆  Export layout", () -> {
            dialog.dismiss();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE,
                    "bandapp-kit-" + (kitLayoutSlot + 1) + ".kit");
            try {
                startActivityForResult(intent, REQ_EXPORT_KIT);
            } catch (Exception e) {
                Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
            }
        }), topMargin(matchWrap(), 12));

        content.addView(menuItem("⬇  Import into this slot", () -> {
            dialog.dismiss();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            try {
                startActivityForResult(intent, REQ_IMPORT_KIT);
            } catch (Exception e) {
                Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
            }
        }), topMargin(matchWrap(), 8));

        TextView note = new TextView(this);
        note.setText("Import replaces slot " + (kitLayoutSlot + 1) + " only.");
        note.setTextColor(COLOR_MUTED);
        note.setTextSize(12);
        content.addView(note, topMargin(matchWrap(), 12));

        presentMenu(dialog, content, dialogWidth(0.82f, 460));
    }

    private void writeKitLayout(Uri uri) {
        String layout = drumKitView != null ? drumKitView.layoutString()
                : prefs.getString("kit_layout_" + kitLayoutSlot, null);
        if (layout == null || layout.isEmpty()) {
            Toast.makeText(this, "This layout is empty", Toast.LENGTH_LONG).show();
            return;
        }
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new java.io.IOException("no stream");
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("layout", layout);
            root.put("preset", currentPreset.name());
            root.put("customKit", drumCustomKit);
            root.put("allChannels", drumAllChannels);
            root.put("midiIn", drumMidiIn);
            root.put("room", drumRoomLevel);
            root.put("snareRim", padSnareRim);
            root.put("cymHat", cymGainHat);
            root.put("cymRide", cymGainRide);
            root.put("cymCrash", cymGainCrash);
            root.put("rideCrashVelocity", rideCrashVel);
            root.put("crashRideVelocity", crashRideVel);
            root.put("chokeVelocity", cymChokeVel);
            root.put("swellVariant", drumSwellVariant);
            org.json.JSONArray sounds = new org.json.JSONArray();
            for (DrumPiece p : drumPieces) {
                org.json.JSONObject sound = new org.json.JSONObject();
                sound.put("note", p.gmNote);
                sound.put("input", p.inNote);
                sound.put("channel", p.channel);
                sound.put("slot", p.kitSlot);
                sound.put("sourceNote", p.sourceNote);
                sound.put("level", p.level);
                sound.put("pan", p.pan);
                sounds.put(sound);
            }
            root.put("midiAssignments", sounds);
            out.write(("bandapp-kit/2\n" + root.toString(2)).getBytes("UTF-8"));
            Toast.makeText(this, "Layout and sounds exported", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void readKitLayout(Uri uri) {
        byte[] bytes = readUri(uri);
        if (bytes == null || bytes.length == 0) {
            Toast.makeText(this, "Could not read that file", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            String text = new String(bytes, "UTF-8");
            int nl = text.indexOf('\n');
            if (nl < 0 || !text.startsWith("bandapp-kit/")) {
                Toast.makeText(this, "Not a BandApp kit layout", Toast.LENGTH_LONG).show();
                return;
            }
            String header = text.substring(0, nl).trim();
            String payload = text.substring(nl + 1).trim();
            String layout;
            boolean restoredSounds = false;
            if ("bandapp-kit/2".equals(header)) {
                org.json.JSONObject root = new org.json.JSONObject(payload);
                layout = root.optString("layout", "");
                try {
                    TonePreset importedPreset = TonePreset.valueOf(
                            root.optString("preset", ""));
                    if (importedPreset.mode == InstrumentMode.DRUMS) {
                        currentPreset = importedPreset;
                        rememberPreset(InstrumentMode.DRUMS, importedPreset);
                    }
                } catch (IllegalArgumentException ignored) { }
                drumCustomKit = root.optBoolean("customKit", drumCustomKit);
                drumAllChannels = root.optBoolean("allChannels", drumAllChannels);
                drumMidiIn = root.optBoolean("midiIn", drumMidiIn);
                drumRoomLevel = (float) root.optDouble("room", drumRoomLevel);
                padSnareRim = root.optBoolean("snareRim", padSnareRim);
                cymGainHat = (float) root.optDouble("cymHat", cymGainHat);
                cymGainRide = (float) root.optDouble("cymRide", cymGainRide);
                cymGainCrash = (float) root.optDouble("cymCrash", cymGainCrash);
                rideCrashVel = (float) root.optDouble(
                        "rideCrashVelocity", rideCrashVel);
                crashRideVel = (float) root.optDouble(
                        "crashRideVelocity", crashRideVel);
                cymChokeVel = (float) root.optDouble("chokeVelocity", cymChokeVel);
                drumSwellVariant = Math.max(0, Math.min(5,
                        root.optInt("swellVariant", drumSwellVariant)));
                org.json.JSONArray sounds = root.optJSONArray("midiAssignments");
                if (sounds != null) {
                    for (int i = 0; i < sounds.length(); i++) {
                        org.json.JSONObject sound = sounds.optJSONObject(i);
                        if (sound == null) continue;
                        DrumPiece piece = drumPieceForNote(sound.optInt("note", -1));
                        if (piece == null) continue;
                        piece.inNote = sound.optInt("input", piece.inNote);
                        piece.channel = sound.optInt("channel", piece.channel);
                        piece.kitSlot = sound.optInt("slot", piece.kitSlot);
                        piece.sourceNote = sound.optInt(
                                "sourceNote", piece.sourceNote);
                        piece.level = (float) sound.optDouble("level", piece.level);
                        piece.pan = (float) sound.optDouble("pan", piece.pan);
                    }
                }
                saveDrumAssignments();
                prefs.edit()
                        .putFloat("cym_gain_hat", cymGainHat)
                        .putFloat("cym_gain_ride", cymGainRide)
                        .putFloat("cym_gain_crash", cymGainCrash)
                        .putFloat("cym_ride_crash_vel", rideCrashVel)
                        .putFloat("cym_crash_ride_vel", crashRideVel)
                        .putFloat("cym_choke_vel", cymChokeVel)
                        .apply();
                restoredSounds = true;
            } else if ("bandapp-kit/1".equals(header)) {
                // Version 1 files contained only the serialized arrangement.
                layout = payload;
            } else {
                Toast.makeText(this, "Unsupported BandApp kit version",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (layout.isEmpty()) {
                Toast.makeText(this, "That file has no pieces", Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putString("kit_layout_" + kitLayoutSlot, layout).apply();
            showFullPads();     // rebuild Kit Mode from the restored slot
            Toast.makeText(this, (restoredSounds ? "Layout and sounds" : "Legacy layout")
                            + " imported into slot " + (kitLayoutSlot + 1),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Import failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void closeFullPads() {
        onFullPads = false;
        kitEditMode = false;
        // Full Kit uses native per-piece routing. Restore Pad Mode's selected
        // kit before rebuilding its UI so those custom routes cannot leak into
        // the normal pads and leave them silent or playing the wrong source.
        applyDrumKit();
        showInstrumentScreen();
    }

    // In-progress "Add piece" selection.
    private int addPieceCat = 1;          // default Snare
    private String addPieceName = "";
    private int addPieceSlot = -1;        // chosen sound source code (-1 = none yet)
    private int addPieceNote = -1;        // chosen sample note in that source
    private String addPieceSoundLabel = "";
    private boolean addPieceDefault = false; // inherit this category from the selected kit
    private boolean editingPiece = false; // true = "Change piece" (edit the selected one)

    // A selectable sound: a source code (font slot / 200+drive / 100+GM), the
    // note to sound in it, a display name, and a tag showing where it came from.
    private static final class KitSound { final int code, note; final String name, tag;
        KitSound(int c, int n, String nm, String tg) { code = c; note = n; name = nm; tag = tg; } }

    private static String drumSourceTag(int code) {
        if (code >= KIT_SOUND_SWELL_BASE && code < KIT_SOUND_SWELL_BASE + 6) return "SWELL WAV";
        if (code >= DrumSampleLib.SLOT_BASE && code < DrumSampleLib.SLOT_BASE + 6) return "LIBRARY";
        if (code >= 200) return "METAL";
        if (code >= 100) return "GM";
        return "HQ";
    }

    // Sounds available for a category: the WAV sample library first, then every
    // HQ / GM / metal kit's version of that piece (tagged by origin). Floor/Tom
    // share the tom font but split by name; Chimes is the WAV (no picker).
    private java.util.List<KitSound> kitSoundOptions(int cat) {
        java.util.List<KitSound> out = new java.util.ArrayList<>();
        if (cat == 4) {
            for (int i = 0; i < 6; ++i) {
                out.add(new KitSound(KIT_SOUND_SWELL_BASE + i, i,
                        "Swell Cymbal " + (i + 1), "SWELL WAV"));
            }
        }
        int lib = DrumKitView.KCAT_LIB[cat];
        if (lib < 0) return out;   // Chimes: WAV, no picker
        // 1) The dedicated WAV sample library for this category.
        int slot = DrumSampleLib.SLOT[lib];
        String[] names = DrumSampleLib.NAMES[lib];
        boolean floor = cat == 3, tom = cat == 2;   // both use the tom font
        for (int i = 0; i < names.length; i++) {
            boolean isFloor = names[i].startsWith("Floor");
            if (floor && !isFloor) continue;
            if (tom && isFloor) continue;
            out.add(new KitSound(slot, 36 + i, names[i], "LIBRARY"));
        }
        // 2) Every bundled HQ / GM / metal kit's version of this piece, using the
        //    category's canonical GM note. Only the core acoustic pieces map
        //    cleanly across kits; Clap/Perc stay library-only.
        if (cat <= 4) {
            int gmNote = DrumKitView.KCAT_GM[cat];
            for (int k = 0; k < CUSTOM_KIT_SLOTS.length; k++) {
                int code = CUSTOM_KIT_SLOTS[k];
                out.add(new KitSound(code, gmNote, CUSTOM_KIT_NAMES[k], drumSourceTag(code)));
            }
        }
        // 3) Curated genre sounds by their ACTUAL sample notes (the custom path
        //    applies no genre remap, so these address the raw samples directly).
        addGenreSounds(out, cat);
        return out;
    }

    // Genre/latin kits are percussion-mapped, so their pieces can't be found by a
    // plain GM note — each is hand-placed at its sample's REAL note (dumped from
    // the fonts). The custom path applies no genre remap, so these address the
    // samples directly. Format "cat,code,note,name,tag" (cat: 0 Kick 1 Snare
    // 4 Cymbal 6 Perc). Reggae's snare is a "double": the snare crack AND the
    // open kwam (hi timbale). Codes: 21 congas, 22 reggae, 26 reggaeton. The
    // default photo kit is unaffected by any of this.
    private static final String[] GENRE_SOUNDS = {
        // Kick
        "0,22,36,Reggae Kick,REGGAE", "0,26,36,Reggaeton Kick,LATIN",
        // Snare (reggae double = snare + kwam)
        "1,22,40,Reggae Snare,REGGAE", "1,22,65,Reggae Kwam,REGGAE",
        "1,22,37,Reggae Rimshot,REGGAE",
        "1,26,38,Reggaeton Snare,LATIN", "1,26,37,Reggaeton Rim,LATIN",
        // Cymbals
        "4,22,49,Reggae Crash,REGGAE", "4,22,51,Reggae Ride,REGGAE",
        "4,22,53,Reggae Ride Bell,REGGAE", "4,22,55,Reggae Splash,REGGAE",
        "4,22,52,Reggae China,REGGAE",
        "4,26,49,909 Crash,LATIN", "4,26,57,707 Crash,LATIN",
        // Percussion — congas / bongos / timbales / hand percussion
        "6,21,65,Conga Low,CONGA", "6,21,67,Conga Mid,CONGA", "6,21,69,Conga High,CONGA",
        "6,21,71,Conga Slap,CONGA", "6,21,72,Conga Finger,CONGA",
        "6,21,60,Tumba Low,CONGA", "6,21,62,Tumba Mid,CONGA", "6,21,64,Tumba High,CONGA",
        "6,22,61,Reggae Bongo,REGGAE", "6,22,63,Reggae Conga Hi,REGGAE",
        "6,22,66,Reggae Timbale,REGGAE", "6,22,67,Agogo High,REGGAE", "6,22,68,Agogo Low,REGGAE",
        "6,22,70,Maracas,REGGAE", "6,22,82,Shaker,REGGAE", "6,22,75,Clave,REGGAE",
        "6,22,84,Bell Tree,REGGAE",
        "6,26,60,Bongo Hi,LATIN", "6,26,61,Bongo Lo,LATIN", "6,26,63,Conga Hi,LATIN",
        "6,26,64,Conga Lo,LATIN", "6,26,56,Cowbell,LATIN", "6,26,54,Tambourine,LATIN",
        "6,26,58,Vibraslap,LATIN",
    };

    private void addGenreSounds(java.util.List<KitSound> out, int cat) {
        for (String g : GENRE_SOUNDS) {
            String[] f = g.split(",");
            if (Integer.parseInt(f[0]) != cat) continue;
            out.add(new KitSound(Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3], f[4]));
        }
    }

    // Kit Mode editor: add a categorised, individually-voiced piece.
    // Category ▾ | Name | Sound ▾ (all snares/kicks/… from the sample library).
    private void addKitPieceDialog() {
        if (drumKitView == null) return;
        editingPiece = false;
        addPieceCat = 1; addPieceName = ""; addPieceSlot = -1; addPieceNote = -1;
        addPieceSoundLabel = ""; addPieceDefault = false;
        showAddPieceDialog();
    }

    // Pencil on a selected piece → the same sheet as "Change piece", pre-filled
    // with the piece's category, name and current sound (highlighted).
    private void changeKitPieceDialog() {
        if (drumKitView == null || !drumKitView.hasSelection()) return;
        editingPiece = true;
        int cat = drumKitView.selCat();
        if (cat < 0) cat = inferKitCat(drumKitView.selNote());   // legacy/default piece
        addPieceCat = cat;
        addPieceName = drumKitView.selName();
        int code = drumKitView.selSoundCode();
        int note = drumKitView.selSoundNote();
        if (code < 0) {
            // Keep inherited pieces inherited. Converting this to an explicit
            // source made it stale when the user selected another full kit.
            addPieceDefault = true;
            note = -1;
        } else {
            addPieceDefault = false;
        }
        addPieceSlot = code;
        addPieceNote = note;
        addPieceSoundLabel = addPieceDefault ? defaultKitPieceLabel() : "";
        if (!addPieceDefault) {
            for (KitSound ks : kitSoundOptions(addPieceCat)) {
                if (ks.code == addPieceSlot && ks.note == addPieceNote) {
                    addPieceSoundLabel = ks.name;
                    break;
                }
            }
        }
        showAddPieceDialog();
    }

    // Best-guess category for a legacy piece from its GM note.
    private static int inferKitCat(int n) {
        if (n == 36 || n == 35) return 0;                 // Kick
        if (n == 38 || n == 37 || n == 40) return 1;      // Snare
        if (n == 43 || n == 41) return 3;                 // Floor
        if (n == 45 || n == 47 || n == 48 || n == 50) return 2;  // Tom
        if (n == 84) return DrumKitView.CAT_CHIMES;       // Chimes
        return 4;                                         // Cymbal
    }

    private void showAddPieceDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        dialog.setCanceledOnTouchOutside(false);   // don't lose a half-built piece on a stray tap
        int accent = toneAccentStatic(currentPreset);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText(editingPiece ? "Change piece" : "Add piece");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        // Category chips (the down-arrow "snare" selector).
        content.addView(fieldLabel("Category"), topMargin(matchWrap(), 12));
        LinearLayout catRow1 = new LinearLayout(this); catRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout catRow2 = new LinearLayout(this); catRow2.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < DrumKitView.KCAT_NAME.length; i++) {
            final int cat = i;
            Button chip = chipButton(DrumKitView.KCAT_NAME[i]);
            styleChipButton(chip, cat == addPieceCat);
            chip.setOnClickListener(v -> {
                addPieceCat = cat;
                addPieceSlot = -1; addPieceNote = -1; addPieceSoundLabel = "";
                addPieceDefault = false;
                dialog.dismiss(); showAddPieceDialog();
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            clp.rightMargin = dp(6); clp.topMargin = dp(6);
            (i < 4 ? catRow1 : catRow2).addView(chip, clp);
        }
        content.addView(catRow1, matchWrap());
        content.addView(catRow2, matchWrap());

        // Name (defaults to the category name if left blank).
        content.addView(fieldLabel("Name"), topMargin(matchWrap(), 12));
        final EditText nameField = new EditText(this);
        nameField.setText(addPieceName);
        nameField.setHint(DrumKitView.KCAT_NAME[addPieceCat]);
        nameField.setTextColor(COLOR_TEXT);
        nameField.setHintTextColor(COLOR_MUTED);
        nameField.setTextSize(15);
        nameField.setBackground(moduleBackground(COLOR_SURFACE_RAISED, COLOR_BORDER, accent, true));
        nameField.setPadding(dp(12), dp(10), dp(12), dp(10));
        textIme(nameField);
        content.addView(nameField, topMargin(matchWrap(), 4));

        // Sound picker (skipped for Chimes, which is the built-in WAV).
        boolean isChimes = addPieceCat == DrumKitView.CAT_CHIMES;
        if (!isChimes) {
            content.addView(fieldLabel("Sound"), topMargin(matchWrap(), 12));
            TextView sound = new TextView(this);
            boolean soundChosen = addPieceDefault || addPieceSlot >= 0;
            sound.setText((soundChosen ? addPieceSoundLabel : "Choose sound") + "   ▾");
            sound.setTextColor(soundChosen ? COLOR_TEXT : COLOR_MUTED);
            sound.setTextSize(15);
            sound.setClickable(true);
            sound.setPadding(dp(12), dp(11), dp(12), dp(11));
            sound.setBackground(moduleBackground(COLOR_SURFACE_RAISED, COLOR_BORDER, accent, true));
            sound.setOnClickListener(v -> {
                addPieceName = nameField.getText().toString();
                dialog.dismiss();
                chooseKitSoundDialog();
            });
            content.addView(sound, topMargin(matchWrap(), 4));
        }

        // Add
        TextView add = new TextView(this);
        add.setText(editingPiece ? "✓  Save changes" : "＋  Add to kit");
        add.setTextColor(COLOR_GREEN);
        add.setTextSize(16);
        add.setGravity(Gravity.CENTER);
        add.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        add.setClickable(true);
        add.setPadding(dp(12), dp(12), dp(12), dp(12));
        add.setBackground(moduleBackground(COLOR_SURFACE_RAISED, COLOR_BORDER, COLOR_GREEN, true));
        add.setOnClickListener(v -> {
            String nm = nameField.getText().toString().trim();
            if (!isChimes && addPieceSlot < 0 && !addPieceDefault) {
                Toast.makeText(this, "Choose a sound first", Toast.LENGTH_SHORT).show();
                return;
            }
            int code = isChimes ? -1 : addPieceSlot;
            int note = isChimes || addPieceDefault ? -1 : addPieceNote;
            if (editingPiece) drumKitView.updateSelected(addPieceCat, nm, code, note);
            else              drumKitView.addPiece(addPieceCat, nm, code, note);
            dialog.dismiss();
            applyDrumKit();
            refreshPadAvailability();
        });
        content.addView(add, topMargin(matchWrap(), 16));

        TextView cancel = new TextView(this);
        cancel.setText("Cancel");
        cancel.setTextColor(COLOR_MUTED);
        cancel.setTextSize(14);
        cancel.setGravity(Gravity.CENTER);
        cancel.setClickable(true);
        cancel.setPadding(dp(12), dp(10), dp(12), dp(4));
        cancel.setOnClickListener(v -> dialog.dismiss());
        content.addView(cancel, topMargin(matchWrap(), 6));

        presentMenu(dialog, content, dialogWidth(0.82f, 460));
    }

    // "Choose sound" — the searchable list of every sample found for the
    // category (all snares, all kicks, …). Tap a row to HEAR it and select it;
    // Back returns to Add piece keeping the choice. Follows the picker rules.
    private String defaultKitPieceLabel() {
        return "Default · " + currentPreset.label;
    }

    private void chooseKitSoundDialog() {
        final java.util.List<KitSound> opts = kitSoundOptions(addPieceCat);
        // Preload the category font so the very first preview isn't silent.
        if (!opts.isEmpty()) ensureDrumSlotForCode(opts.get(0).code);
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        dialog.setCanceledOnTouchOutside(false);   // a stray background tap must not discard the piece
        int accent = toneAccentStatic(currentPreset);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(14));

        // Header: Back + title.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("◀  Back");
        back.setTextColor(accent);
        back.setTextSize(15);
        back.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        back.setClickable(true);
        back.setPadding(dp(8), dp(6), dp(12), dp(6));
        back.setOnClickListener(v -> { dialog.dismiss(); showAddPieceDialog(); });
        head.addView(back, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView title = new TextView(this);
        title.setText(DrumKitView.KCAT_NAME[addPieceCat]);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(17);
        title.setGravity(Gravity.END);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        head.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(head, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("Tap a sound to hear it — Back keeps your pick.");
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(12);
        content.addView(hint, topMargin(matchWrap(), 2));

        final EditText search = new EditText(this);
        search.setHint("Search " + opts.size() + " sounds…");
        search.setTextColor(COLOR_TEXT);
        search.setHintTextColor(COLOR_MUTED);
        search.setTextSize(14);
        search.setBackground(moduleBackground(COLOR_SURFACE_RAISED, COLOR_BORDER, accent, true));
        search.setPadding(dp(12), dp(9), dp(12), dp(9));
        searchIme(search);
        content.addView(search, topMargin(matchWrap(), 10));

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        final ScrollView scroll = new ScrollView(this);
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        final View[] selectedRow = new View[1];   // the highlighted row, for scroll-to
        Runnable rebuild = () -> {
            selectedRow[0] = null;
            list.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase(Locale.US);
            String defaultLabel = defaultKitPieceLabel();
            if (q.isEmpty() || defaultLabel.toLowerCase(Locale.US).contains(q)
                    || "selected kit".contains(q)) {
                boolean sel = addPieceDefault;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(11), dp(12), dp(11));
                row.setClickable(true);
                row.setBackground(moduleBackground(sel ? COLOR_SKY_CONTROL_STRONG : COLOR_SURFACE_RAISED,
                        sel ? accent : COLOR_BORDER, accent, true));
                TextView nm = new TextView(this);
                nm.setText((sel ? "♪  " : "↺  ") + defaultLabel);
                nm.setTextColor(sel ? COLOR_TEXT : COLOR_MUTED);
                nm.setTextSize(15);
                row.addView(nm, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(originTag("KIT"), new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                row.setOnClickListener(v -> {
                    addPieceDefault = true;
                    addPieceSlot = -1;
                    addPieceNote = -1;
                    addPieceSoundLabel = defaultKitPieceLabel();
                    int code = pieceCodeForProgram(currentPreset.program);
                    ensureDrumSlotForCode(code);
                    engine.previewDrum(code, defaultKitSourceNote(
                            currentPreset, DrumKitView.KCAT_GM[addPieceCat]));
                    int y = scroll.getScrollY();
                    if (kitSoundRebuild != null) kitSoundRebuild.run();
                    scroll.post(() -> scroll.scrollTo(0, y));
                });
                if (sel) selectedRow[0] = row;
                list.addView(row, topMargin(matchWrap(), 6));
            }
            for (KitSound ks : opts) {
                if (!q.isEmpty()
                        && !ks.name.toLowerCase(Locale.US).contains(q)
                        && !ks.tag.toLowerCase(Locale.US).contains(q)) continue;
                boolean sel = ks.code == addPieceSlot && ks.note == addPieceNote;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(11), dp(12), dp(11));
                row.setClickable(true);
                row.setBackground(moduleBackground(sel ? darken(accent) : COLOR_SURFACE_RAISED,
                        sel ? accent : COLOR_BORDER, accent, true));
                TextView nm = new TextView(this);
                nm.setText((sel ? "♪  " : "▶  ") + ks.name);
                nm.setTextColor(sel ? COLOR_TEXT : COLOR_MUTED);
                nm.setTextSize(15);
                row.addView(nm, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(originTag(ks.tag), new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                row.setOnClickListener(v -> {
                    // Select + audition; stay open so several can be compared.
                    addPieceDefault = false;
                    addPieceSlot = ks.code; addPieceNote = ks.note; addPieceSoundLabel = ks.name;
                    previewKitSound(ks.code, ks.note);
                    int y = scroll.getScrollY();
                    if (kitSoundRebuild != null) kitSoundRebuild.run();
                    scroll.post(() -> scroll.scrollTo(0, y));
                });
                if (sel) selectedRow[0] = row;
                list.addView(row, topMargin(matchWrap(), 6));
            }
        };
        // Keep a reference so the row handler can re-run the same builder.
        kitSoundRebuild = rebuild;
        rebuild.run();
        // Open scrolled to the currently-selected sound so it's visible + highlighted.
        if (selectedRow[0] != null) {
            scroll.post(() -> scroll.scrollTo(0,
                    Math.max(0, selectedRow[0].getTop() - dp(70))));
        }
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { rebuild.run(); }
            public void afterTextChanged(android.text.Editable s) {}
        });
        // WRAP_CONTENT last child so the list gets exactly the leftover height.
        content.addView(scroll, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT), 10));

        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.82f, 460),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.setContentView(content);
        dialog.show();
    }

    private Runnable kitSoundRebuild;

    private TextView fieldLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s.toUpperCase(Locale.US));
        t.setTextColor(COLOR_MUTED);
        t.setTextSize(11);
        t.setLetterSpacing(0.08f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    // Small colour-coded pill marking a sound's origin (Library / HQ / GM / Metal).
    private TextView originTag(String tag) {
        int c;
        switch (tag) {
            case "LIBRARY": c = COLOR_TEAL; break;
            case "GM":      c = COLOR_AMBER; break;
            case "METAL":   c = COLOR_PURPLE; break;
            case "REGGAE":  c = Color.rgb(90, 200, 120); break;
            case "CONGA":   c = Color.rgb(214, 146, 74); break;
            case "LATIN":   c = Color.rgb(230, 112, 132); break;
            default:        c = COLOR_GREEN; break;   // HQ
        }
        TextView t = new TextView(this);
        t.setText(tag);
        t.setTextColor(c);
        t.setTextSize(9);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setLetterSpacing(0.06f);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setPadding(dp(6), dp(2), dp(6), dp(2));
        t.setBackground(pillBackground(Color.argb(30, Color.red(c), Color.green(c), Color.blue(c)), c));
        return t;
    }

    private View buildMenuButton() {
        // Back button: returns to the instrument picker and stops the engine (via goToPicker()).
        return backArrowButton(this::goToPicker);
    }

    // Arrow-only back button: a pill with a drawn ← arrow icon (used on every screen).
    private View backArrowButton(final Runnable onClick) {
        BackIconView arrow = new BackIconView(this, COLOR_TEXT);
        int sz = dp(40);
        arrow.setMinimumWidth(sz);
        arrow.setMinimumHeight(sz);
        arrow.setPadding(dp(11), dp(11), dp(11), dp(11));
        arrow.setBackground(pillBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        arrow.setClickable(true);
        arrow.setOnClickListener(v -> onClick.run());
        return arrow;
    }

    // App-bar menu (☰): secondary/navigation actions, contextual per instrument.
    private View buildOverflowButton() {
        MenuIconView b = new MenuIconView(this, COLOR_TEXT);
        int sz = dp(40);
        b.setMinimumWidth(sz);
        b.setMinimumHeight(sz);
        b.setPadding(dp(10), dp(11), dp(10), dp(11));
        b.setBackground(pillBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        b.setClickable(true);
        b.setOnClickListener(v -> showOverflowMenu());
        return b;
    }

    private void showOverflowMenu() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText(currentMode.label);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        if (currentMode == InstrumentMode.PIANO) {
            content.addView(menuItem("🎹  Play Keys (zoom, landscape)", () -> { dialog.dismiss(); showFullPiano(); }), topMargin(matchWrap(), 10));
            content.addView(menuItem("⛶  Full Keys (MIDI view)", () -> { dialog.dismiss(); showFullKeyboard(); }), topMargin(matchWrap(), 8));
            content.addView(menuItem("🎼  Chord Mode", () -> { dialog.dismiss(); showChordMode(); }), topMargin(matchWrap(), 8));
            content.addView(menuItem("⧉  Layers · blend sounds", () -> { dialog.dismiss(); layersDialog(); }), topMargin(matchWrap(), 8));
            content.addView(menuItem("♫  MIDI Player", () -> { dialog.dismiss(); midiPlayerDialog(); }), topMargin(matchWrap(), 8));
            if (midiOutputPorts.size() > 1) {
                // Two keyboards connected: choose who is player 1 / player 2.
                content.addView(menuItem("⇄  Swap MIDI players"
                        + (midiSwapPlayers ? "  ●" : ""), () -> {
                    dialog.dismiss();
                    toggleMidiSwap();
                }), topMargin(matchWrap(), 8));
            }
        } else if (currentMode == InstrumentMode.DRUMS) {
            content.addView(menuItem("⛶  Kit Mode", () -> { dialog.dismiss(); showFullPads(); }), topMargin(matchWrap(), 10));
            content.addView(menuItem("⊞  MIDI Assignment", () -> { dialog.dismiss(); showMidiAssignment(); }), topMargin(matchWrap(), 8));
        }
        content.addView(menuItem("💾  Song Presets", () -> { dialog.dismiss(); songPresetsDialog(); }), topMargin(matchWrap(), 8));
        content.addView(menuItem("⌨  MIDI Learn · Foot controls", () -> {
            dialog.dismiss();
            pedalDialog();
        }), topMargin(matchWrap(), 8));
        content.addView(menuItem("🗄  Backup · Export / Import", () -> { dialog.dismiss(); backupDialog(); }), topMargin(matchWrap(), 8));
        content.addView(menuItem("⏺  Recordings", () -> { dialog.dismiss(); showRecordings(); }), topMargin(matchWrap(), 8));
        content.addView(menuItem("🔊  Output · " + currentOutputLabel(), () -> { dialog.dismiss(); audioOutputDialog(); }), topMargin(matchWrap(), 8));
        content.addView(menuItem("🔍  UI Scale · " + uiScaleLabel(), () -> { dialog.dismiss(); uiScaleDialog(); }), topMargin(matchWrap(), 8));
        content.addView(menuItem("⌁  Diagnostics · Performance Lock ON", () -> {
            dialog.dismiss();
            diagnosticsDialog();
        }), topMargin(matchWrap(), 8));
        if (currentMode == InstrumentMode.ELECTRIC_GUITAR || currentMode == InstrumentMode.BASS) {
            // Guitar/bass capture a live instrument; piano & drums are MIDI-driven
            // over USB-C, so they have no audio input to choose.
            content.addView(menuItem("🎸  Input · " + currentInputLabel(), () -> { dialog.dismiss(); audioInputDialog(); }), topMargin(matchWrap(), 8));
        }

        presentMenu(dialog, content, dialogWidth(0.82f, 460));
    }

    private void diagnosticsDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("Performance Diagnostics");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        float latency = engine.isRunning() ? engine.outputLatencyMs() : 0f;
        String report = "Performance Lock  ON\n"
                + "Engine  " + (engine.isRunning() ? engine.status() : "stopped") + "\n"
                + "Measured latency  " + (latency > 0f
                ? String.format(Locale.US, "%.1f ms", latency) : "--") + "\n"
                + "Output  " + currentOutputLabel() + "\n"
                + "Input  " + (currentMode == InstrumentMode.PIANO
                || currentMode == InstrumentMode.DRUMS ? midiDeviceLabel : currentInputLabel()) + "\n"
                + "External SF2 files  " + externalSf2Files.size() + "\n"
                + "MIDI ports  " + midiOutputPorts.size();
        if (currentMode == InstrumentMode.DRUMS && engine.isRunning()) {
            int available = 0;
            int missing = 0;
            for (int note = 35; note <= 81; note++) {
                if (engine.drumNoteHasSound(note)) available++; else missing++;
            }
            report += "\nDrum map  " + available + " playable · " + missing + " missing";
        }

        TextView details = performanceValue(report);
        details.setTextSize(12);
        details.setLineSpacing(dp(3), 1f);
        content.addView(details, topMargin(matchWrap(), 12));

        TextView check = menuItem("Run audio route check", () -> {
            float ms = engine.isRunning() ? engine.outputLatencyMs() : 0f;
            String result = !engine.isRunning() ? "Start the engine to run the check"
                    : ms > 35f ? "High latency route detected: " + Math.round(ms) + " ms"
                    : "Audio route responsive: " + Math.round(ms) + " ms";
            Toast.makeText(this, result, Toast.LENGTH_LONG).show();
        });
        content.addView(check, topMargin(matchWrap(), 12));
        presentMenu(dialog, content, dialogWidth(0.86f, 520));
    }

    private String currentOutputLabel() {
        if (preferredOutputType < 0) {
            return "Auto";
        }
        android.media.AudioDeviceInfo device = router.outputOfType(preferredOutputType);
        return device != null ? router.outputOptionLabel(device) : "Auto (unplugged)";
    }

    // Pick where the sound comes out: auto, phone speaker, 3.5mm jack, USB-C, Bluetooth.
    private void audioOutputDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("Audio Output");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("Where the instrument sound plays. Auto follows the system.");
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(12);
        content.addView(hint, topMargin(matchWrap(), 4));

        boolean autoActive = preferredOutputType < 0;
        TextView auto = menuItem((autoActive ? "●  " : "○  ") + "Auto", () -> {
            selectOutputType(-1);
            dialog.dismiss();
        });
        if (autoActive) auto.setTextColor(COLOR_GREEN);
        content.addView(auto, topMargin(matchWrap(), 12));

        java.util.List<android.media.AudioDeviceInfo> outs = router.outputOptions();
        java.util.HashSet<Integer> seenTypes = new java.util.HashSet<>();
        for (android.media.AudioDeviceInfo device : outs) {
            final int type = device.getType();
            if (!seenTypes.add(type)) continue;   // one entry per sink type
            boolean active = type == preferredOutputType;
            TextView item = menuItem((active ? "●  " : "○  ") + router.outputOptionLabel(device), () -> {
                selectOutputType(type);
                dialog.dismiss();
            });
            if (active) item.setTextColor(COLOR_GREEN);
            content.addView(item, topMargin(matchWrap(), 8));
        }
        if (outs.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("No output devices detected.");
            none.setTextColor(COLOR_MUTED);
            none.setTextSize(13);
            content.addView(none, topMargin(matchWrap(), 10));
        }

        // Global mono toggle: applies to every instrument. Sums L+R so a single
        // mixer channel or mono PA send keeps all content (no dropped side).
        TextView monoItem = menuItem((monoOutput ? "●  " : "○  ") + "Mono output (mixer/PA)", () -> {
            monoOutput = !monoOutput;
            engine.setMonoOutput(monoOutput);
            prefs.edit().putBoolean("mono_output", monoOutput).apply();
            Toast.makeText(this, monoOutput ? "Mono output — summed L+R"
                    : "Stereo output", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        if (monoOutput) monoItem.setTextColor(COLOR_GREEN);
        content.addView(monoItem, topMargin(matchWrap(), 14));

        presentMenu(dialog, content, dialogWidth(0.85f, 480));
    }

    private void selectOutputType(int type) {
        preferredOutputType = type;
        prefs.edit().putInt("audio_out_type_" + audioContextKey(), type).apply();
        restartActiveEngine();
        Toast.makeText(this, "Output: " + currentOutputLabel(), Toast.LENGTH_SHORT).show();
    }

    private String uiScaleLabel() {
        int userPct = prefs.getInt("ui_scale_pct", 0);
        return (userPct >= 60 && userPct <= 100) ? userPct + "%"
                : "Auto " + sAutoScalePct + "%";
    }

    // Live UI-density control. Smaller % = lower effective DPI = more fits on
    // screen. Applying re-runs attachBaseContext (via recreate) at the new
    // density, so the whole UI rescales — spacing, controls, text, custom views.
    private void uiScaleDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("UI Scale");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("Smaller fits more on screen. Applies instantly (screen reloads).");
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(12);
        content.addView(hint, topMargin(matchWrap(), 4));

        int userPct = prefs.getInt("ui_scale_pct", 0);
        final int startPct = (userPct >= 60 && userPct <= 100) ? userPct : sAutoScalePct;

        final TextView value = new TextView(this);
        value.setTextColor(COLOR_TEXT);
        value.setTextSize(15);
        value.setText("Scale: " + startPct + "%");
        content.addView(value, topMargin(matchWrap(), 12));

        final SeekBar bar = new SeekBar(this);
        bar.setMax(40);   // maps to 60%..100%
        bar.setProgress(Math.max(0, Math.min(40, startPct - 60)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                value.setText("Scale: " + (60 + p) + "%");
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        content.addView(bar, topMargin(matchWrap(), 8));

        TextView apply = menuItem("✓  Apply", () -> {
            prefs.edit().putInt("ui_scale_pct", 60 + bar.getProgress()).apply();
            dialog.dismiss();
            recreate();
        });
        apply.setTextColor(COLOR_GREEN);
        content.addView(apply, topMargin(matchWrap(), 14));

        content.addView(menuItem("↺  Auto (" + sAutoScalePct + "%)", () -> {
            prefs.edit().remove("ui_scale_pct").apply();
            dialog.dismiss();
            recreate();
        }), topMargin(matchWrap(), 8));

        presentMenu(dialog, content, dialogWidth(0.7f, 420));
    }

    // Input/output choices are PER CONTEXT (each instrument, the looper, the
    // vocals rig and the tuner all keep their own), and each defaults to Auto —
    // switching instruments never inherits another screen's routing.
    private String audioContextKey() {
        if (onLoopMix) return "loop";
        if (onVocalsScreen) return "vocals";
        if (onGuitarKeys) return "gkeys";
        if (onTunerScreen) return "tuner";
        return currentMode != null ? currentMode.name() : "global";
    }

    private void loadAudioPrefs() {
        String ctx = audioContextKey();
        preferredOutputType = prefs.getInt("audio_out_type_" + ctx, -1);
        preferredInputType = prefs.getInt("audio_in_type_" + ctx, -1);
        preferredInputName = prefs.getString("audio_in_name_" + ctx, "");
        engine.setInputMute(preferredInputType == -2);
    }

    // Re-open whatever is currently running on the newly chosen devices.
    private void restartActiveEngine() {
        if (onLoopMix) {
            engine.stop();
            startLoopEngine();
        } else if (onVocalsScreen) {
            if (engine.isRunning()) {
                engine.stop();
                startVocalEngine();
            }
        } else if (onGuitarKeys) {
            if (engine.isRunning()) {
                engine.stop();
                startGuitarKeysEngine();
            }
        } else if (onTunerScreen) {
            engine.stop();
            engine.startTuner(resolvePreferredInput(-1), resolvePreferredOutput(-1));
        } else if (engine.isRunning()) {
            engine.stop();
            startEngine();
        }
    }

    private String currentInputLabel() {
        if (preferredInputType == -2) {
            return "Off";
        }
        if (preferredInputType < 0) {
            return "Auto";
        }
        android.media.AudioDeviceInfo device = router.inputMatching(preferredInputType, preferredInputName);
        return device != null ? router.inputOptionLabel(device) : "Auto (unplugged)";
    }

    // Pick what gets captured: auto, internal mic, headset mic, or a specific
    // USB-C device (a type-C mic and a type-C instrument box are separate entries).
    private void audioInputDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("Audio Input");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("What gets captured (instrument, tuner, loops). Auto follows the system.");
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(12);
        content.addView(hint, topMargin(matchWrap(), 4));

        boolean autoActive = preferredInputType == -1;
        TextView auto = menuItem((autoActive ? "●  " : "○  ") + "Auto", () -> {
            selectInput(-1, "");
            dialog.dismiss();
        });
        if (autoActive) auto.setTextColor(COLOR_GREEN);
        content.addView(auto, topMargin(matchWrap(), 12));

        // No capture at all — stops mixer/speaker feedback through the mic.
        boolean offActive = preferredInputType == -2;
        TextView off = menuItem((offActive ? "●  " : "○  ") + "Off (no mic, stops feedback)", () -> {
            selectInput(-2, "");
            dialog.dismiss();
        });
        if (offActive) off.setTextColor(COLOR_GREEN);
        content.addView(off, topMargin(matchWrap(), 8));

        java.util.List<android.media.AudioDeviceInfo> ins = router.inputOptions();
        java.util.HashSet<Integer> seenTypes = new java.util.HashSet<>();
        for (android.media.AudioDeviceInfo device : ins) {
            final int type = device.getType();
            final boolean usb = router.isUsbType(type);
            final String name = usb ? router.productNameOf(device) : "";
            // Phones report the built-in mic once per position; one entry is enough.
            if (!usb && !seenTypes.add(type)) continue;
            boolean active = type == preferredInputType
                    && (!usb || preferredInputName.isEmpty() || preferredInputName.equals(name));
            TextView item = menuItem((active ? "●  " : "○  ") + router.inputOptionLabel(device), () -> {
                selectInput(type, name);
                dialog.dismiss();
            });
            if (active) item.setTextColor(COLOR_GREEN);
            content.addView(item, topMargin(matchWrap(), 8));
        }
        if (ins.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("No input devices detected.");
            none.setTextColor(COLOR_MUTED);
            none.setTextSize(13);
            content.addView(none, topMargin(matchWrap(), 10));
        }

        presentMenu(dialog, content, dialogWidth(0.85f, 480));
    }

    private void selectInput(int type, String name) {
        preferredInputType = type;
        preferredInputName = name == null ? "" : name;
        String ctx = audioContextKey();
        prefs.edit().putInt("audio_in_type_" + ctx, type)
                .putString("audio_in_name_" + ctx, preferredInputName).apply();
        engine.setInputMute(type == -2);
        restartActiveEngine();
        Toast.makeText(this, "Input: " + currentInputLabel(), Toast.LENGTH_SHORT).show();
    }

    private TextView menuItem(String label, Runnable onClick) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(contrastTextColor(COLOR_SURFACE_RAISED));
        t.setTextSize(16);
        t.setPadding(dp(14), dp(13), dp(14), dp(13));
        t.setBackground(animatedButtonBackground(
                COLOR_SURFACE_RAISED, dp(8), COLOR_TEAL));
        t.setClickable(true);
        t.setOnClickListener(v -> onClick.run());
        return t;
    }

    // Landscape IME guard for search boxes: without these flags the keyboard
    // opens its fullscreen "extract" editor over the whole dialog and traps the
    // user ("search left me hanging"). Every picker search field goes through
    // this.
    private void searchIme(EditText search) {
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
    }

    // Same guard for non-search inputs (names, note numbers): keeps the field's
    // own inputType, just blocks the fullscreen extract editor.
    private void textIme(EditText field) {
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
    }

    // Wrap a stacked menu in a ScrollView so tall context menus scroll instead of
    // running off the short landscape screen (bottom items were unreachable —
    // "hanging"), then size the window and show it.
    private void presentMenu(Dialog dialog, View content, int width) {
        ScrollView sv = new ScrollView(this);
        sv.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(sv);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(width, LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    // ---- Recordings manager: list / play / share / delete the captured WAVs ----
    private void showRecordings() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        dialog.setOnDismissListener(d -> stopRecPlayer());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("Recordings");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        File dir = new File(getExternalFilesDir(null), "recordings");
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.US).endsWith(".wav"));
        if (files == null || files.length == 0) {
            TextView empty = new TextView(this);
            empty.setText("No recordings yet. Tap ● REC on an instrument to capture one.");
            empty.setTextColor(COLOR_MUTED);
            empty.setTextSize(14);
            content.addView(empty, topMargin(matchWrap(), 12));
        } else {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            ScrollView sv = new ScrollView(this);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            for (File f : files) {
                list.addView(recordingRow(f, () -> { dialog.dismiss(); showRecordings(); }), topMargin(matchWrap(), 8));
            }
            sv.addView(list);
            content.addView(sv, topMargin(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT), 12));
        }

        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.9f, 520), LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private View recordingRow(final File file, final Runnable onChanged) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setBackground(moduleBackground(COLOR_SURFACE_RAISED, COLOR_BORDER, COLOR_TEAL, true));

        TextView name = new TextView(this);
        name.setText(prettyRecName(file.getName()));
        name.setTextColor(COLOR_TEXT);
        name.setTextSize(15);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(name, matchWrap());

        TextView meta = new TextView(this);
        meta.setText(formatBytes(file.length()));
        meta.setTextColor(COLOR_MUTED);
        meta.setTextSize(12);
        row.addView(meta, topMargin(matchWrap(), 2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        final TextView play = recRowButton("▶  Play");
        play.setOnClickListener(v -> {
            if (recPlayer != null) { stopRecPlayer(); play.setText("▶  Play"); return; }
            playRecording(file, () -> play.setText("▶  Play"));
            play.setText("■  Stop");
        });
        actions.addView(play, chipParams(true));
        TextView share = recRowButton("⇪  Share");
        share.setOnClickListener(v -> shareRecording(file));
        actions.addView(share, chipParams(true));
        TextView del = recRowButton("🗑  Delete");
        del.setTextColor(COLOR_RED);
        del.setOnClickListener(v -> confirmDeleteRecording(file, onChanged));
        actions.addView(del, chipParams(false));
        row.addView(actions, topMargin(matchWrap(), 10));
        return row;
    }

    private TextView recRowButton(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(contrastTextColor(COLOR_SURFACE));
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(10), dp(9), dp(10), dp(9));
        t.setBackground(pillBackground(COLOR_SURFACE, COLOR_BORDER_STRONG));
        t.setClickable(true);
        return t;
    }

    private void playRecording(File file, final Runnable onDone) {
        stopRecPlayer();
        try {
            recPlayer = new MediaPlayer();
            recPlayer.setDataSource(file.getAbsolutePath());
            recPlayer.setOnCompletionListener(mp -> { stopRecPlayer(); if (onDone != null) onDone.run(); });
            recPlayer.prepare();
            recPlayer.start();
        } catch (Exception e) {
            stopRecPlayer();
            Toast.makeText(this, "Can't play this file", Toast.LENGTH_SHORT).show();
            if (onDone != null) onDone.run();
        }
    }

    private void stopRecPlayer() {
        if (recPlayer != null) {
            try { recPlayer.stop(); } catch (Exception ignored) {}
            recPlayer.release();
            recPlayer = null;
        }
    }

    private void shareRecording(File file) {
        try {
            Uri uri = RecordingsProvider.uriFor(file.getName());
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("audio/wav");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share recording"));
        } catch (Exception e) {
            Toast.makeText(this, "Can't share this file", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDeleteRecording(final File file, final Runnable onChanged) {
        final Dialog d = new Dialog(this);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(14));
        TextView t = new TextView(this);
        t.setText("Delete " + prettyRecName(file.getName()) + "?");
        t.setTextColor(COLOR_TEXT);
        t.setTextSize(16);
        c.addView(t, matchWrap());
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = recRowButton("Cancel");
        cancel.setOnClickListener(v -> d.dismiss());
        btns.addView(cancel, chipParams(true));
        TextView ok = recRowButton("Delete");
        ok.setTextColor(COLOR_RED);
        ok.setOnClickListener(v -> {
            stopRecPlayer();
            boolean deleted = file.delete();
            d.dismiss();
            Toast.makeText(this, deleted ? "Deleted" : "Couldn't delete", Toast.LENGTH_SHORT).show();
            if (onChanged != null) onChanged.run();
        });
        btns.addView(ok, chipParams(false));
        c.addView(btns, topMargin(matchWrap(), 16));
        d.setContentView(c);
        if (d.getWindow() != null) {
            d.getWindow().setLayout(dialogWidth(0.8f, 420), LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    // "rec_20260628_011045.wav" -> "Jun 28, 01:10:45"
    private String prettyRecName(String fileName) {
        try {
            String s = fileName.replace("rec_", "").replace(".wav", "");
            Date date = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(s);
            return new SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(date);
        } catch (Exception e) {
            return fileName;
        }
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
    }

    // Global transport: record (●) · metronome icon · BPM · time signature.
    private View buildTransportRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        recButton = transportIcon(TransportIconView.RECORD);
        recButton.setOnClickListener(v -> toggleRecording());
        row.addView(recButton, iconPillParams());
        styleRecButton();

        metroButton = transportIcon(TransportIconView.METRONOME);
        metroButton.setOnClickListener(v -> toggleMetronome());
        row.addView(metroButton, leftMargin(iconPillParams(), 8));
        styleMetroButton();

        bpmButton = transportPill(metronomeBpm + " BPM");
        bpmButton.setOnClickListener(v -> bpmDialog());
        row.addView(bpmButton, leftMargin(matchWrap(), 8));

        sigButton = transportPill(timeSigNum + "/" + timeSigDen);
        sigButton.setOnClickListener(v -> timeSigDialog());
        row.addView(sigButton, leftMargin(matchWrap(), 8));

        // Wrap so the pills can never be clipped off-screen on a narrow phone.
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    private TransportIconView transportIcon(int kind) {
        TransportIconView v = new TransportIconView(this, kind, COLOR_MUTED);
        v.setBackground(pillBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        v.setClickable(true);
        return v;
    }

    private LinearLayout.LayoutParams iconPillParams() {
        return new LinearLayout.LayoutParams(dp(50), dp(38));
    }

    // Common time signatures; numerator drives the metronome accent (beat 1).
    private void timeSigDialog() {
        final int[][] sigs = {{4, 4}, {3, 4}, {2, 4}, {6, 8}, {5, 4}, {7, 8}, {12, 8}};
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));
        TextView title = new TextView(this);
        title.setText("Time Signature");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());
        for (final int[] s : sigs) {
            boolean active = (s[0] == timeSigNum && s[1] == timeSigDen);
            TextView item = menuItem((active ? "●  " : "○  ") + s[0] + " / " + s[1], () -> {
                timeSigNum = s[0];
                timeSigDen = s[1];
                if (sigButton != null) sigButton.setText(timeSigNum + "/" + timeSigDen);
                // Always push tempo/signature: the loop bar auto-stop reads them
                // from the engine even when the click itself is off.
                engine.setMetronome(metronomeOn, metronomeBpm, timeSigNum);
                dialog.dismiss();
            });
            if (active) item.setTextColor(COLOR_GREEN);
            content.addView(item, topMargin(matchWrap(), 8));
        }
        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.7f, 360), LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private TextView transportPill(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(contrastTextColor(COLOR_SURFACE_RAISED));
        t.setTextSize(13);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setBackground(pillBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        t.setClickable(true);
        return t;
    }

    private LinearLayout.LayoutParams leftMargin(LinearLayout.LayoutParams lp, int dp) {
        lp.leftMargin = dp(dp);
        return lp;
    }

    private boolean recording() {
        return engine != null && engine.isRecording();
    }

    private void styleRecButton() {
        if (recButton == null) return;
        boolean on = recording();
        recButton.setRecording(on || countingIn);
        int c = on ? COLOR_RED : (countingIn ? COLOR_AMBER : COLOR_MUTED);
        recButton.setColor(c);
        recButton.setBackground(pillBackground(COLOR_SURFACE_RAISED,
                on ? COLOR_RED : (countingIn ? COLOR_AMBER : COLOR_BORDER)));
    }

    private void styleMetroButton() {
        if (metroButton == null) return;
        metroButton.setColor(metronomeOn ? COLOR_GREEN : COLOR_MUTED);
        metroButton.setBackground(pillBackground(COLOR_SURFACE_RAISED, metronomeOn ? COLOR_GREEN : COLOR_BORDER));
    }

    private void toggleRecording() {
        if (recording()) {
            engine.stopRecording();
            styleRecButton();
            if (lastRecPath != null) {
                Toast.makeText(this, "Saved " + new File(lastRecPath).getName(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (!engine.isRunning()) {
            Toast.makeText(this, "Start the engine first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (countInOn) {
            startCountIn();
            return;
        }
        beginRecording();
    }

    private void beginRecording() {
        File dir = new File(getExternalFilesDir(null), "recordings");
        dir.mkdirs();
        String name = "rec_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".wav";
        lastRecPath = new File(dir, name).getAbsolutePath();
        engine.startRecording(lastRecPath);
        styleRecButton();
        Toast.makeText(this, "Recording…", Toast.LENGTH_SHORT).show();
    }

    private boolean countingIn = false;

    // Play one bar of clicks aligned to beat 1, then start recording on the next
    // downbeat. The click restores to its prior on/off state afterwards.
    private void startCountIn() {
        if (countingIn) return;
        countingIn = true;
        final boolean wasMetro = metronomeOn;
        engine.setMetronome(true, metronomeBpm, timeSigNum);
        engine.resetMetronome();   // click starts on beat 1 now
        final int beats = Math.max(1, timeSigNum);
        final long beatMs = Math.round(60000.0 / metronomeBpm);
        styleRecButton();   // REC pulses amber while counting in
        Toast.makeText(this, "Count-in… " + beats + " beats", Toast.LENGTH_SHORT).show();
        handler.postDelayed(() -> {
            countingIn = false;
            if (!wasMetro) {
                metronomeOn = false;
                engine.setMetronome(false, metronomeBpm, timeSigNum);
                styleMetroButton();
            }
            beginRecording();
        }, (long) beats * beatMs);
    }

    private void toggleMetronome() {
        metronomeOn = !metronomeOn;
        engine.setMetronome(metronomeOn, metronomeBpm, timeSigNum);
        styleMetroButton();
    }

    private void bpmDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));
        final TextView title = new TextView(this);
        title.setText("Metronome  " + metronomeBpm + " BPM");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        SeekBar bpm = new SeekBar(this);
        bpm.setMax(270);   // 30..300
        bpm.setProgress(metronomeBpm - 30);
        bpm.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                metronomeBpm = prog + 30;
                title.setText("Metronome  " + metronomeBpm + " BPM");
                if (bpmButton != null) bpmButton.setText(metronomeBpm + " BPM");
                if (loopMetroPill != null && metronomeOn) loopMetroPill.setText("♩ " + metronomeBpm);
                // Always push tempo/signature: the loop bar auto-stop reads them
                // from the engine even when the click itself is off.
                engine.setMetronome(metronomeOn, metronomeBpm, timeSigNum);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        content.addView(bpm, topMargin(matchWrap(), 14));

        // Count-in: one bar of clicks before recording starts.
        final TextView countIn = menuItem((countInOn ? "●  " : "○  ")
                + "Count-in before recording (1 bar)", () -> { });
        countIn.setTextColor(countInOn ? COLOR_GREEN : COLOR_TEXT);
        countIn.setOnClickListener(v -> {
            countInOn = !countInOn;
            prefs.edit().putBoolean("count_in", countInOn).apply();
            countIn.setText((countInOn ? "●  " : "○  ") + "Count-in before recording (1 bar)");
            countIn.setTextColor(countInOn ? COLOR_GREEN : COLOR_TEXT);
        });
        content.addView(countIn, topMargin(matchWrap(), 14));

        presentMenu(dialog, content, dialogWidth(0.8f, 460));
    }

    private static final String[] DEMO_FILES = {"midi/ode_to_joy.mid", "midi/fur_elise.mid", "midi/arpeggio_demo.mid"};
    private static final String[] DEMO_NAMES = {"Ode to Joy", "Für Elise", "Arpeggio Demo"};

    private void midiPlayerDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("MIDI Player");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("Plays through the current piano sound. Start the engine first.");
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(12);
        content.addView(hint, topMargin(matchWrap(), 4));

        content.addView(sectionTitle("Demo Songs"), topMargin(matchWrap(), 14));
        for (int i = 0; i < DEMO_FILES.length; i++) {
            final String asset = DEMO_FILES[i];
            final String name = DEMO_NAMES[i];
            TextView item = new TextView(this);
            item.setText("▸  " + name);
            item.setTextColor(COLOR_TEXT);
            item.setTextSize(16);
            item.setPadding(dp(14), dp(12), dp(14), dp(12));
            item.setBackground(moduleBackground(COLOR_SURFACE_RAISED, COLOR_BORDER, COLOR_BORDER, true));
            item.setClickable(true);
            item.setOnClickListener(v -> { loadAndPlayMidi(readAsset(asset), name); });
            content.addView(item, topMargin(matchWrap(), 8));
        }

        TextView open = new TextView(this);
        open.setText("＋  Open .mid file…");
        open.setTextColor(COLOR_TEAL);
        open.setTextSize(16);
        open.setPadding(dp(14), dp(12), dp(14), dp(12));
        open.setBackground(moduleBackground(COLOR_SURFACE_RAISED, COLOR_BORDER, COLOR_TEAL, true));
        open.setClickable(true);
        open.setOnClickListener(v -> { dialog.dismiss(); pickMidiFile(); });
        content.addView(open, topMargin(matchWrap(), 8));

        // transport
        content.addView(sectionTitle("Now Playing"), topMargin(matchWrap(), 14));
        midiProgressText = new TextView(this);
        midiProgressText.setTextColor(COLOR_TEXT);
        midiProgressText.setTextSize(14);
        content.addView(midiProgressText, topMargin(matchWrap(), 4));

        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        midiPlayPauseBtn = transportPill(engine.midiIsPlaying() ? "⏸ Pause" : "▶ Play");
        midiPlayPauseBtn.setOnClickListener(v -> {
            if (engine.midiIsPlaying()) { engine.midiPause(); }
            else { if (!engine.isRunning()) { Toast.makeText(this, "Start the engine first", Toast.LENGTH_SHORT).show(); return; } engine.midiPlay(); }
            updateMidiTransport();
        });
        transport.addView(midiPlayPauseBtn, matchWrap());

        TextView stop = transportPill("■ Stop");
        stop.setOnClickListener(v -> { engine.midiStop(); updateMidiTransport(); });
        transport.addView(stop, leftMargin(matchWrap(), 8));

        final TextView loop = transportPill("↻ Loop");
        loop.setTextColor(midiLoopOn ? COLOR_GREEN : COLOR_MUTED);
        loop.setOnClickListener(v -> {
            midiLoopOn = !midiLoopOn;
            engine.midiSetLoop(midiLoopOn);
            loop.setTextColor(midiLoopOn ? COLOR_GREEN : COLOR_MUTED);
        });
        transport.addView(loop, leftMargin(matchWrap(), 8));
        content.addView(transport, topMargin(matchWrap(), 8));

        // Live performance controls, so the sound can be shaped while MIDI plays.
        content.addView(sectionTitle("Live Controls"), topMargin(matchWrap(), 14));
        LiveControlView midiLc = new LiveControlView(this);
        midiLc.setControlsChangedListener(this::applyLiveControls);
        midiLc.setValues(liveControlValues);
        content.addView(midiLc, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(150)), 8));

        updateMidiTransport();
        midiProgressTick = new Runnable() {
            public void run() {
                if (midiProgressText == null) return;
                updateMidiProgress();
                updateMidiTransport();
                handler.postDelayed(this, 300);
            }
        };
        handler.post(midiProgressTick);
        dialog.setOnDismissListener(d -> {
            handler.removeCallbacks(midiProgressTick);
            midiProgressText = null;
            midiPlayPauseBtn = null;
        });

        // Wrap in a ScrollView so the (now taller) player scrolls instead of
        // clipping off-screen in landscape.
        ScrollView midiScroll = new ScrollView(this);
        midiScroll.setVerticalScrollBarEnabled(false);
        midiScroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(midiScroll);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.86f, 480), LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void loadAndPlayMidi(byte[] data, String name) {
        if (data == null) { Toast.makeText(this, "Couldn't read file", Toast.LENGTH_SHORT).show(); return; }
        if (!engine.loadMidi(data)) { Toast.makeText(this, "Not a valid MIDI file", Toast.LENGTH_SHORT).show(); return; }
        midiNowPlaying = name;
        engine.midiSetLoop(midiLoopOn);
        if (!engine.isRunning()) {
            Toast.makeText(this, "Loaded — start the engine to hear it", Toast.LENGTH_SHORT).show();
        } else {
            engine.midiPlay();
        }
        updateMidiTransport();
        updateMidiProgress();
    }

    private void updateMidiTransport() {
        if (midiPlayPauseBtn != null) {
            midiPlayPauseBtn.setText(engine.midiIsPlaying() ? "⏸ Pause" : "▶ Play");
        }
    }

    private void updateMidiProgress() {
        if (midiProgressText == null) return;
        float pos = engine.midiPositionMs(), dur = engine.midiDurationMs();
        midiProgressText.setText(midiNowPlaying + "   " + fmtTime(pos) + " / " + fmtTime(dur));
    }

    private String fmtTime(float ms) {
        int s = Math.max(0, Math.round(ms / 1000f));
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void pickMidiFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_PICK_MIDI);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Backup / restore -------------------------------------------------
    // Everything the player builds up — Kit Mode layouts, kit slot presets,
    // chord songs, song presets, favorites, routing, UI scale — lives in the
    // single "instrumental" prefs file, so one whole-prefs dump backs up all of
    // it. Uninstalling wipes that file (and a signing-key change forces an
    // uninstall), which is exactly what this exists to survive.

    private void backupDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("Backup · Sync all");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("One file for everything: every instrument's song presets, all "
                + "chord songs, kit layouts and settings. Import brings them all back "
                + "into their own lists.");
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(12);
        content.addView(hint, topMargin(matchWrap(), 4));
        content.addView(menuItem("⬆  Export to file", () -> {
            dialog.dismiss();
            exportBackup();
        }), topMargin(matchWrap(), 12));
        content.addView(menuItem("⬇  Import from file", () -> {
            dialog.dismiss();
            importBackup();
        }), topMargin(matchWrap(), 8));
        TextView note = new TextView(this);
        note.setText("Import merges into what's here now and restarts the screen.");
        note.setTextColor(COLOR_MUTED);
        note.setTextSize(12);
        content.addView(note, topMargin(matchWrap(), 12));
        presentMenu(dialog, content, dialogWidth(0.82f, 460));
    }

    private void exportBackup() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "bandapp-backup-"
                + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                        .format(new java.util.Date()) + ".json");
        try {
            startActivityForResult(intent, REQ_EXPORT_BACKUP);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
        }
    }

    private void importBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_IMPORT_BACKUP);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
        }
    }

    // Each value carries its type tag: SharedPreferences is strongly typed and
    // restoring an int as a String throws ClassCastException on the next read.
    private void writeBackup(Uri uri) {
        try {
            org.json.JSONObject vals = new org.json.JSONObject();
            for (java.util.Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
                Object v = e.getValue();
                org.json.JSONObject entry = new org.json.JSONObject();
                if (v instanceof java.util.Set) {
                    org.json.JSONArray arr = new org.json.JSONArray();
                    for (Object s : (java.util.Set<?>) v) arr.put(String.valueOf(s));
                    entry.put("t", "set").put("v", arr);
                } else if (v instanceof Boolean) {
                    entry.put("t", "b").put("v", v);
                } else if (v instanceof Integer) {
                    entry.put("t", "i").put("v", v);
                } else if (v instanceof Long) {
                    entry.put("t", "l").put("v", v);
                } else if (v instanceof Float) {
                    entry.put("t", "f").put("v", ((Float) v).doubleValue());
                } else {
                    entry.put("t", "s").put("v", String.valueOf(v));
                }
                vals.put(e.getKey(), entry);
            }
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("app", "bandapp");
            root.put("prefs", vals);
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("no stream");
                out.write(root.toString(2).getBytes("UTF-8"));
            }
            Toast.makeText(this, "Backed up " + vals.length() + " settings",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void readBackup(Uri uri) {
        byte[] bytes = readUri(uri);
        if (bytes == null || bytes.length == 0) {
            Toast.makeText(this, "Could not read that file", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            org.json.JSONObject root = new org.json.JSONObject(new String(bytes, "UTF-8"));
            org.json.JSONObject vals = root.optJSONObject("prefs");
            if (!"bandapp".equals(root.optString("app")) || vals == null) {
                Toast.makeText(this, "Not a BandApp backup", Toast.LENGTH_LONG).show();
                return;
            }
            SharedPreferences.Editor ed = prefs.edit();
            int added = 0, updated = 0, kept = 0;
            for (java.util.Iterator<String> it = vals.keys(); it.hasNext(); ) {
                String key = it.next();
                org.json.JSONObject entry = vals.optJSONObject(key);
                if (entry == null) continue;
                String t = entry.optString("t");

                boolean isMtime = key.startsWith("chord_song_t_") || key.startsWith("songpresetmtime_");
                if (isMtime) continue;   // written together with its value below

                // The two name collections MERGE (union) so no list is clobbered.
                if (key.equals("chord_song_names")) {
                    java.util.List<String> cur = chordSongNames();
                    for (String nm : entry.optString("v").split(CHORD_SONG_SEP)) {
                        if (!nm.isEmpty() && !cur.contains(nm)) cur.add(nm);
                    }
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < cur.size(); i++) {
                        if (i > 0) sb.append(CHORD_SONG_SEP);
                        sb.append(cur.get(i));
                    }
                    ed.putString(key, sb.toString());
                    continue;
                }
                if (key.startsWith("songpresets_")) {   // StringSet of preset names
                    java.util.HashSet<String> set = new java.util.HashSet<>(
                            prefs.getStringSet(key, new java.util.HashSet<>()));
                    org.json.JSONArray arr = entry.optJSONArray("v");
                    for (int i = 0; arr != null && i < arr.length(); i++) set.add(arr.optString(i));
                    ed.putStringSet(key, set);
                    continue;
                }

                // Preset / song VALUES: add if new, else keep unless the imported
                // copy is newer (latest wins), compared via the paired mtime.
                boolean isChordVal = key.startsWith("chord_song_");
                boolean isPresetVal = key.startsWith("songpreset_");
                if (isChordVal || isPresetVal) {
                    String mtimeKey = isChordVal
                            ? "chord_song_t_" + key.substring("chord_song_".length())
                            : "songpresetmtime_" + key.substring("songpreset_".length());
                    long impT = 0;
                    org.json.JSONObject me = vals.optJSONObject(mtimeKey);
                    if (me != null) impT = me.optLong("v", 0);
                    long curT = prefs.getLong(mtimeKey, 0);
                    boolean exists = prefs.contains(key);
                    if (exists && impT <= curT) { kept++; continue; }   // ours is same/newer
                    ed.putString(key, entry.optString("v"));
                    ed.putLong(mtimeKey, impT);
                    if (exists) updated++; else added++;
                    continue;
                }

                // Any other setting: add only if it isn't already set (don't
                // overwrite what's on this device).
                if (prefs.contains(key)) { kept++; continue; }
                switch (t) {
                    case "set": {
                        org.json.JSONArray arr = entry.optJSONArray("v");
                        java.util.HashSet<String> set = new java.util.HashSet<>();
                        for (int i = 0; arr != null && i < arr.length(); i++) set.add(arr.optString(i));
                        ed.putStringSet(key, set);
                        break;
                    }
                    case "b": ed.putBoolean(key, entry.optBoolean("v")); break;
                    case "i": ed.putInt(key, entry.optInt("v")); break;
                    case "l": ed.putLong(key, entry.optLong("v")); break;
                    case "f": ed.putFloat(key, (float) entry.optDouble("v")); break;
                    case "s": ed.putString(key, entry.optString("v")); break;
                    default: continue;
                }
                added++;
            }
            ed.apply();
            Toast.makeText(this, "Sync: " + added + " added · " + updated
                    + " updated · " + kept + " kept", Toast.LENGTH_LONG).show();
            // Everything is read into fields at startup, so rebuild the screen
            // rather than trying to re-apply each setting by hand.
            recreate();
        } catch (Exception e) {
            Toast.makeText(this, "Import failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == REQ_PICK_SF2_FOLDER) {
            int flags = data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, flags | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) {
                Toast.makeText(this, "This folder provider cannot keep access",
                        Toast.LENGTH_LONG).show();
            }
            externalSf2TreeUri = uri.toString();
            prefs.edit().putString("external_sf2_tree", externalSf2TreeUri).apply();
            scanExternalSf2Folder();
        } else if (request == REQ_PICK_MIDI) {
            byte[] bytes = readUri(uri);
            String name = uri.getLastPathSegment();
            if (name != null && name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
            loadAndPlayMidi(bytes, name != null ? name : "MIDI file");
            midiPlayerDialog();
        } else if (request == REQ_EXPORT_BACKUP) {
            writeBackup(uri);
        } else if (request == REQ_IMPORT_BACKUP) {
            readBackup(uri);
        } else if (request == REQ_EXPORT_KIT) {
            writeKitLayout(uri);
        } else if (request == REQ_IMPORT_KIT) {
            readKitLayout(uri);
        } else if (request == REQ_EXPORT_CHORDS) {
            writeChordSongs(uri);
        } else if (request == REQ_IMPORT_CHORDS) {
            readChordSongs(uri);
        }
    }

    private void pickExternalSf2Folder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_SF2_FOLDER);
        } catch (RuntimeException e) {
            Toast.makeText(this, "No folder picker is available",
                    Toast.LENGTH_LONG).show();
        }
    }

    private static final class ExternalSf2File {
        final String uri;
        final String name;
        final String relativePath;
        final long size;

        ExternalSf2File(Uri uri, String name, String relativePath, long size) {
            this.uri = uri.toString();
            this.name = name;
            this.relativePath = relativePath;
            this.size = size;
        }
    }

    private static final class Sf2LoadTask implements Runnable, Comparable<Sf2LoadTask> {
        private static final java.util.concurrent.atomic.AtomicLong NEXT =
                new java.util.concurrent.atomic.AtomicLong();
        final int priority;
        final long order = NEXT.getAndIncrement();
        final Runnable action;

        Sf2LoadTask(int priority, Runnable action) {
            this.priority = priority;
            this.action = action;
        }

        @Override public void run() { action.run(); }

        @Override public int compareTo(Sf2LoadTask other) {
            int byPriority = Integer.compare(priority, other.priority);
            return byPriority != 0 ? byPriority : Long.compare(order, other.order);
        }
    }

    private void queueExternalSf2Load(int priority, Runnable action) {
        externalSf2Loader.execute(new Sf2LoadTask(priority, action));
    }

    private static final class ExternalNamFile {
        final String uri;
        final String name;
        final String relativePath;
        final long size;

        ExternalNamFile(Uri uri, String name, String relativePath, long size) {
            this.uri = uri.toString();
            this.name = name;
            this.relativePath = relativePath;
            this.size = size;
        }
    }

    private static final class ExternalIrFile {
        final String uri;
        final String name;
        final String relativePath;
        final long size;

        ExternalIrFile(Uri uri, String name, String relativePath, long size) {
            this.uri = uri.toString();
            this.name = name;
            this.relativePath = relativePath;
            this.size = size;
        }
    }

    private void scanExternalSf2Folder() {
        final String tree = externalSf2TreeUri;
        final int token = ++externalScanToken;
        if (tree == null) {
            externalSf2Files.clear();
            externalNamFiles.clear();
            externalIrFiles.clear();
            refreshExternalSf2Browser();
            return;
        }
        new Thread(() -> {
            java.util.ArrayList<ExternalSf2File> found = new java.util.ArrayList<>();
            java.util.ArrayList<ExternalNamFile> foundNam = new java.util.ArrayList<>();
            java.util.ArrayList<ExternalIrFile> foundIr = new java.util.ArrayList<>();
            boolean readable = false;
            try {
                Uri treeUri = Uri.parse(tree);
                String rootId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
                scanExternalSf2Children(treeUri, rootId, "", found, foundNam, foundIr, 0);
                readable = true;
            } catch (Exception ignored) {
            }
            final boolean folderReadable = readable;
            java.util.Collections.sort(found,
                    (a, b) -> a.name.compareToIgnoreCase(b.name));
            java.util.Collections.sort(foundNam,
                    (a, b) -> a.name.compareToIgnoreCase(b.name));
            java.util.Collections.sort(foundIr,
                    (a, b) -> a.name.compareToIgnoreCase(b.name));
            handler.post(() -> {
                if (token != externalScanToken) return;
                externalSf2Files.clear();
                externalSf2Files.addAll(found);
                externalNamFiles.clear();
                externalNamFiles.addAll(foundNam);
                externalIrFiles.clear();
                externalIrFiles.addAll(foundIr);
                refreshExternalSf2Browser();
                if (currentMode == InstrumentMode.PIANO && layerMode) {
                    applyLayers();
                }
                if (virtualGuitarMidiMode && virtualGuitarPlayerOn) {
                    ensureVirtualGuitarArticulations();
                }
                if (virtualGuitarMidiMode && activeNamUri != null
                        && !activeNamUri.equals(loadedNamUri) && !namLoading) {
                    ExternalNamFile active = findExternalNam(activeNamUri);
                    if (active != null) loadNamModel(active, false);
                }
                if (virtualGuitarMidiMode && activeNamIrUri != null
                        && !activeNamIrUri.equals(loadedNamIrUri) && !namIrLoading) {
                    ExternalIrFile active = findExternalIr(activeNamIrUri);
                    if (active != null) loadNamIr(active, false);
                }
                if (!folderReadable) {
                    Toast.makeText(this,
                            "External SF2 folder is unavailable. Choose it again.",
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "external-sf2-scan").start();
    }

    private void scanExternalSf2Children(Uri treeUri, String parentId, String path,
            java.util.List<ExternalSf2File> found,
            java.util.List<ExternalNamFile> foundNam,
            java.util.List<ExternalIrFile> foundIr, int depth) {
        if (depth > 12) return;
        Uri children = android.provider.DocumentsContract
                .buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] projection = {
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                android.provider.DocumentsContract.Document.COLUMN_SIZE
        };
        try (android.database.Cursor cursor = getContentResolver().query(
                children, projection, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long size = cursor.isNull(3) ? -1L : cursor.getLong(3);
                if (name == null || id == null) continue;
                if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    scanExternalSf2Children(
                            treeUri, id, path + name + "/", found, foundNam, foundIr,
                            depth + 1);
                } else if (name.toLowerCase(Locale.US).endsWith(".sf2")
                        && found.size() < 1000) {
                    Uri document = android.provider.DocumentsContract
                            .buildDocumentUriUsingTree(treeUri, id);
                    found.add(new ExternalSf2File(document, name, path, size));
                } else if (name.toLowerCase(Locale.US).endsWith(".nam")
                        && foundNam.size() < 1000) {
                    Uri document = android.provider.DocumentsContract
                            .buildDocumentUriUsingTree(treeUri, id);
                    foundNam.add(new ExternalNamFile(document, name, path, size));
                } else if (name.toLowerCase(Locale.US).endsWith(".wav")
                        && foundIr.size() < 1000) {
                    Uri document = android.provider.DocumentsContract
                            .buildDocumentUriUsingTree(treeUri, id);
                    foundIr.add(new ExternalIrFile(document, name, path, size));
                }
            }
        }
    }

    private byte[] readUri(Uri uri) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1 << 16);
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // Combined pill: input route (left) + engine status (right), separated by a
    // thin divider. One element keeps the app bar compact so the title never
    // crowds or overlaps on narrow screens.
    private LinearLayout buildStatusChip() {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(11), dp(7), dp(11), dp(7));
        chip.setBackground(pillBackground(COLOR_SURFACE_RAISED, COLOR_BORDER_STRONG));

        routeChipDot = new View(this);
        routeChipDot.setBackground(dotDrawable(COLOR_DIM));
        chip.addView(routeChipDot, new LinearLayout.LayoutParams(dp(9), dp(9)));

        routeChipText = new TextView(this);
        routeChipText.setTextColor(COLOR_MUTED);
        routeChipText.setTextSize(12);
        routeChipText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(7);
        chip.addView(routeChipText, rlp);

        View divider = new View(this);
        divider.setBackgroundColor(COLOR_BORDER_STRONG);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(14));
        dlp.leftMargin = dp(9);
        dlp.rightMargin = dp(9);
        chip.addView(divider, dlp);

        statusDot = new View(this);
        statusDot.setBackground(dotDrawable(COLOR_DIM));
        chip.addView(statusDot, new LinearLayout.LayoutParams(dp(9), dp(9)));

        statusText = new TextView(this);
        statusText.setText("Stopped");
        statusText.setTextColor(COLOR_MUTED);
        statusText.setTextSize(12);
        statusText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(7);
        chip.addView(statusText, slp);

        updateRouteChip();
        return chip;
    }

    private void updateRouteChip() {
        if (routeChipText == null || routeChipDot == null) {
            return;
        }
        boolean midi = currentMode == InstrumentMode.PIANO || currentMode == InstrumentMode.DRUMS;
        boolean connected = midi ? midiInputAvailable : router.hasUsbInput();
        routeChipText.setText(midi ? "MIDI" : "USB-C");
        routeChipText.setTextColor(connected ? COLOR_GREEN : COLOR_MUTED);
        routeChipDot.setBackground(dotDrawable(connected ? COLOR_GREEN : COLOR_RED));
    }

    private void clearInstrumentViewRefs() {
        presetGrid = null;
        routeRow = null;
        statusText = null;
        statusDot = null;
        routeChipText = null;
        routeChipDot = null;
        errorBanner = null;
        toneText = null;
        usbText = null;
        deviceText = null;
        meterDbText = null;
        pianoNotesText = null;
        liveControlView = null;
        liveControlViewB = null;
        liveTabAButton = null;
        liveTabBButton = null;
        pianoGuitarRigPanel = null;
        pianoGuitarRigButton = null;
        pianoGuitarAmpButton = null;
        pianoGuitarCabButton = null;
        pianoGuitarDriveButton = null;
        pianoGuitarToneButton = null;
        pianoGuitarHarmButton = null;
        pianoGuitarMarkButton = null;
        virtualGuitarPlayerButton = null;
        pianoGuitarNamButton = null;
        pianoGuitarNamModelButton = null;
        pianoGuitarNamMixButton = null;
        pianoGuitarNamInputButton = null;
        pianoGuitarNamOutputButton = null;
        pianoGuitarNamIrButton = null;
        pianoGuitarNamIrModelButton = null;
        inMeter = null;
        outMeter = null;
        signalChainView = null;
        pianoKeysView = null;
        keyVizView = null;
        drumPadsView = null;
        drumKitView = null;
        startButton = null;
        sustainButton = null;
        reverbButton = null;
        sustainSlider = null;
        reverbSlider = null;
        soundBarText = null;
        sound2Bar = null;
        sound2BarText = null;
        soundLoadingText = null;
        soundLoadingBar = null;
        cpuUsageText = null;
        ramUsageText = null;
        audioUsageText = null;
        performanceWallMs = 0L;
        performanceCpuMs = 0L;
        pianoPane = null;
        pianoContentHost = null;
        pianoPerformancePane = null;
        pianoBrowserHost = null;
        pianoSoundBrowserOpen = false;
        pianoBrowserDualLayout = false;
        pianoBrowserTitle = null;
        pianoBrowserSearch = null;
        pianoBrowserSound1List = null;
        pianoBrowserSound2List = null;
        pianoBrowserSf2Status = null;
        pianoBrowserSound2Column = null;
        pianoBrowserDivider = null;
        if (pianoBrowserStretch != null) pianoBrowserStretch.cancel();
        pianoBrowserStretch = null;
        pianoSound1Rows.clear();
        pianoSound2Rows.clear();
        externalSound1Rows.clear();
        externalSound2Rows.clear();
        favStar = null;
        pedalTabsRow = null;
        presetButtons.clear();
        routeButtons.clear();
    }

    private View buildMeterBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(10), dp(14), dp(10));
        bar.setBackground(panelBackground(COLOR_SURFACE, COLOR_BORDER));

        bar.addView(miniLabel("IN"), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        inMeter = new LevelMeterView(this);
        LinearLayout.LayoutParams inParams = new LinearLayout.LayoutParams(0, dp(14), 1.0f);
        inParams.leftMargin = dp(8);
        bar.addView(inMeter, inParams);

        meterDbText = new TextView(this);
        meterDbText.setText("-inf dB");
        meterDbText.setTextColor(COLOR_TEXT);
        meterDbText.setTextSize(12);
        meterDbText.setGravity(Gravity.CENTER);
        meterDbText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams dbParams = new LinearLayout.LayoutParams(dp(74),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dbParams.leftMargin = dp(8);
        dbParams.rightMargin = dp(8);
        bar.addView(meterDbText, dbParams);

        outMeter = new LevelMeterView(this);
        bar.addView(outMeter, new LinearLayout.LayoutParams(0, dp(14), 1.0f));
        LinearLayout.LayoutParams outLabelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        outLabelParams.leftMargin = dp(8);
        bar.addView(miniLabel("OUT"), outLabelParams);
        return bar;
    }

    private TextView miniLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_MUTED);
        view.setTextSize(11);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setLetterSpacing(0.08f);
        return view;
    }

    private String[] signalChainLabels() {
        if (currentMode == InstrumentMode.BASS) {
            return new String[]{"Mic", "Comp", "Pre", "Drive", "Cab", "Out"};
        }
        return new String[]{"Mic", "Gate", "Drive", "Amp", "Cab", "Dly", "Rvb", "Out"};
    }

    private int signalChainHighlight() {
        return currentMode == InstrumentMode.BASS ? 2 : 3;
    }

    private View buildPerformanceMonitor() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        View divider = new View(this);
        divider.setBackgroundColor(COLOR_BORDER);
        section.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        TextView heading = new TextView(this);
        heading.setText("PERFORMANCE");
        heading.setTextColor(COLOR_MUTED);
        heading.setTextSize(10);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setLetterSpacing(0.08f);
        section.addView(heading, topMargin(matchWrap(), 10));

        LinearLayout values = new LinearLayout(this);
        values.setOrientation(LinearLayout.HORIZONTAL);
        values.setGravity(Gravity.CENTER_VERTICAL);

        cpuUsageText = performanceValue("CPU  --");
        ramUsageText = performanceValue("RAM  --");
        audioUsageText = performanceValue("AUDIO  --");
        values.addView(cpuUsageText, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams ramLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ramLp.leftMargin = dp(12);
        values.addView(ramUsageText, ramLp);
        section.addView(values, topMargin(matchWrap(), 6));
        section.addView(audioUsageText, topMargin(matchWrap(), 4));

        performanceWallMs = 0L;
        performanceCpuMs = 0L;
        refreshPerformanceMonitor();
        return section;
    }

    private TextView performanceValue(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(COLOR_TEXT);
        text.setTextSize(13);
        text.setTypeface(Typeface.create("sans-serif-monospace", Typeface.NORMAL));
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }

    private void refreshPerformanceMonitor() {
        if (cpuUsageText == null || ramUsageText == null || audioUsageText == null) return;
        long now = SystemClock.elapsedRealtime();
        if (performanceWallMs != 0L && now - performanceWallMs < 1000L) return;

        long cpu = android.os.Process.getElapsedCpuTime();
        if (performanceWallMs != 0L) {
            long wallDelta = Math.max(1L, now - performanceWallMs);
            long cpuDelta = Math.max(0L, cpu - performanceCpuMs);
            int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            float percent = cpuDelta * 100f / (wallDelta * cores);
            cpuUsageText.setText(String.format(Locale.US, "CPU  %.1f%%",
                    Math.max(0f, Math.min(100f, percent))));
        }
        performanceWallMs = now;
        performanceCpuMs = cpu;

        android.app.ActivityManager manager =
                (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            android.os.Debug.MemoryInfo[] info = manager.getProcessMemoryInfo(
                    new int[]{android.os.Process.myPid()});
            if (info.length > 0) {
                float mb = info[0].getTotalPss() / 1024f;
                ramUsageText.setText(String.format(Locale.US, "RAM  %.1f MB", mb));
            }
        }
        if (engine.isRunning()) {
            float latency = engine.outputLatencyMs();
            String status = engine.status();
            String rate = status == null ? "" : status
                    .replace("Engine: running at ", "")
                    .replace(" Hz", " Hz");
            audioUsageText.setText(String.format(Locale.US, "AUDIO  %s  ·  %.1f ms",
                    rate.isEmpty() ? "running" : rate, Math.max(0f, latency)));
            audioUsageText.setTextColor(latency > 25f ? COLOR_AMBER : COLOR_GREEN);
        } else {
            audioUsageText.setText("AUDIO  stopped");
            audioUsageText.setTextColor(COLOR_MUTED);
        }
    }

    private void beginSoundLoad(String label) {
        soundLoadsInFlight++;
        if (soundLoadingText != null) {
            soundLoadingText.setText(label);
            soundLoadingText.setVisibility(View.VISIBLE);
        }
        if (soundLoadingBar != null) soundLoadingBar.setVisibility(View.VISIBLE);
    }

    private void finishSoundLoad() {
        soundLoadsInFlight = Math.max(0, soundLoadsInFlight - 1);
        if (soundLoadsInFlight == 0) {
            if (soundLoadingText != null) soundLoadingText.setVisibility(View.GONE);
            if (soundLoadingBar != null) soundLoadingBar.setVisibility(View.GONE);
        }
    }

    private byte[] readExternalSf2(Uri uri, long knownSize) {
        final long maxBytes = 256L * 1024L * 1024L;
        if (knownSize > maxBytes) return null;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            int initial = knownSize > 0
                    ? (int) Math.min(knownSize, 1024L * 1024L) : 1 << 16;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(initial);
            byte[] buffer = new byte[1 << 16];
            long total = 0L;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > maxBytes) return null;
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] readExternalNam(Uri uri, long knownSize) {
        final long maxBytes = 64L * 1024L * 1024L;
        if (knownSize > maxBytes) return null;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            int initial = knownSize > 0
                    ? (int) Math.min(knownSize, 1024L * 1024L) : 1 << 16;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(initial);
            byte[] buffer = new byte[1 << 16];
            long total = 0L;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > maxBytes) return null;
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static final class WavPcm {
        final float[] samples;
        final int frames;
        final int channels;
        final int rate;

        WavPcm(float[] samples, int frames, int channels, int rate) {
            this.samples = samples;
            this.frames = frames;
            this.channels = channels;
            this.rate = rate;
        }
    }

    private WavPcm decodeIrWav(byte[] wav) {
        if (wav == null || wav.length < 44 || leI32(wav, 0) != 0x46464952
                || leI32(wav, 8) != 0x45564157) return null;
        int format = 1, channels = 0, rate = 0, bits = 0;
        int dataOff = -1, dataLen = 0;
        int p = 12;
        while (p + 8 <= wav.length) {
            int id = leI32(wav, p);
            int size = leI32(wav, p + 4);
            int body = p + 8;
            if (size < 0 || body > wav.length || size > wav.length - body) break;
            if (id == 0x20746d66 && size >= 16) {
                format = leI16(wav, body);
                channels = leI16(wav, body + 2);
                rate = leI32(wav, body + 4);
                bits = leI16(wav, body + 14);
            } else if (id == 0x61746164) {
                dataOff = body;
                dataLen = size;
                break;
            }
            p = body + size + (size & 1);
        }
        int bytesPerSample = bits / 8;
        if (dataOff < 0 || channels < 1 || channels > 8 || rate < 8000
                || bytesPerSample < 2 || bytesPerSample > 4
                || (format != 1 && format != 3)
                || (format == 3 && bits != 32)) return null;
        int frames = dataLen / (channels * bytesPerSample);
        frames = Math.min(frames, rate * 2);
        if (frames <= 0) return null;
        float[] samples = new float[frames * channels];
        int at = dataOff;
        for (int i = 0; i < samples.length; ++i, at += bytesPerSample) {
            if (format == 3) {
                samples[i] = Float.intBitsToFloat(leI32(wav, at));
            } else if (bits == 16) {
                samples[i] = (short) leI16(wav, at) / 32768f;
            } else if (bits == 24) {
                int value = (wav[at] & 0xff) | ((wav[at + 1] & 0xff) << 8)
                        | ((wav[at + 2] & 0xff) << 16);
                if ((value & 0x800000) != 0) value |= 0xff000000;
                samples[i] = value / 8388608f;
            } else {
                samples[i] = leI32(wav, at) / 2147483648f;
            }
        }
        return new WavPcm(samples, frames, channels, rate);
    }

    private View buildEffectToggles() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // --- Sustain: toggle + hold-time slider (timer so notes auto-release) ---
        // Four effect buttons: tap = on/off, long-press = set the amount.
        sustainButton = chipButton("Sustain");
        sustainButton.setOnClickListener(v -> {
            sustainOn = !sustainOn;
            engine.setSustain(sustainOn);
            if (!sustainOn) {
                // Chip OFF is a hard stop: also drop the MIDI pedal flag. A
                // lost CC64 release (keyboard chord mode, unplug mid-hold)
                // otherwise keeps pedal-held notes ringing forever.
                engine.setSustainPedal(false);
                midiPedalDown = false;
            }
            refreshSustainChip();
            saveEffectPrefs();
        });
        sustainButton.setOnLongClickListener(v -> {
            levelDialog("Sustain hold", 100, sustainTimeToProgress(sustainTime), p -> {
                sustainTime = progressToSustainTime(p);
                engine.setSustainTime(sustainTime);
                saveEffectPrefs();
                return String.format(Locale.US, "%.1f s", sustainTime);
            });
            return true;
        });

        reverbButton = chipButton("Reverb");
        reverbButton.setOnClickListener(v -> {
            reverbOn = !reverbOn;
            engine.setReverb(reverbOn);
            styleChipButton(reverbButton, reverbOn);
            saveEffectPrefs();
        });
        reverbButton.setOnLongClickListener(v -> {
            levelDialog("Reverb level", 100, reverbLevelToProgress(reverbLevel), p -> {
                reverbLevel = progressToReverbLevel(p);
                engine.setReverbLevel(reverbLevel);
                syncSpaceKnob();   // keep the "Space" fader in step
                saveEffectPrefs();
                return Math.round(reverbLevel * 100) + "%";
            });
            return true;
        });

        // Dual: tap = on/off. Sound 2 is picked from its own bar next to the
        // main sound (no long-press needed).
        dualButton = chipButton(dualLabel());
        dualButton.setOnClickListener(v -> {
            dualOn = !dualOn;
            applyDualSound();
        });

        // Slide: legato keys bend to the new pitch; hold sets the slide time.
        // Tap cycles Off → Slide → Mono — Mono keeps the slide on overlapped
        // presses but a detached press cuts the last voice (one note at a
        // time, stylophone-style) instead of letting tails stack.
        final Button glideButton = chipButton(pianoGlideMono && pianoGlideOn ? "Mono" : "Slide");
        glideButton.setOnClickListener(v -> {
            if (!pianoGlideOn) {
                pianoGlideOn = true;
                pianoGlideMono = false;
            } else if (!pianoGlideMono) {
                pianoGlideMono = true;
                Toast.makeText(this, "Mono Slide — held keys slide, separate presses cut the last note",
                        Toast.LENGTH_SHORT).show();
            } else {
                pianoGlideOn = false;
                pianoGlideMono = false;
            }
            pushPianoGlide();
            engine.allNotesOff();
            glideButton.setText(pianoGlideMono && pianoGlideOn ? "Mono" : "Slide");
            styleChipButton(glideButton, pianoGlideOn);
            prefs.edit().putBoolean("piano_glide", pianoGlideOn)
                    .putBoolean("piano_glide_mono", pianoGlideMono).apply();
        });
        glideButton.setOnLongClickListener(v -> {
            levelDialog("Slide time", 100, glideRateToProgress(pianoGlideRate), p -> {
                pianoGlideRate = 200 - Math.round(1.85f * p);
                engine.setPianoGlideRate(pianoGlideRate);
                prefs.edit().putInt("piano_glide_rate", pianoGlideRate).apply();
                return pianoGlideRate >= 120 ? "fast" : pianoGlideRate >= 55 ? "medium" : "slow";
            });
            return true;
        });

        // MIDI input settings live here on the piano — the looper only ever
        // uses one keyboard, so it has no MIDI controls of its own.
        final Button midiButton = chipButton("MIDI ⇄");
        midiButton.setOnClickListener(v -> midiKeyboardsDialog());

        col.addView(pillGrid(5, sustainButton, reverbButton, dualButton, glideButton, midiButton),
                matchWrap());
        TextView holdHint = new TextView(this);
        holdHint.setText("Hold a button to set its amount");
        holdHint.setTextColor(COLOR_DIM);
        holdHint.setTextSize(11);
        col.addView(holdHint, topMargin(matchWrap(), 4));

        refreshSustainChip();
        styleChipButton(reverbButton, reverbOn);
        styleChipButton(dualButton, dualOn);
        styleChipButton(glideButton, pianoGlideOn);
        engine.setSustain(sustainOn);
        engine.setReverb(reverbOn);
        engine.setSustainTime(sustainTime);
        engine.setReverbLevel(reverbLevel);
        pushPianoGlide();
        engine.setPianoGlideRate(pianoGlideRate);
        return col;
    }

    private interface LevelChange {
        String apply(int progress);
    }

    // Long-press popup for effect amounts: one slider + a live value readout.
    private void levelDialog(String title, int max, int progress, final LevelChange change) {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(16), dp(18), dp(14));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(COLOR_TEXT);
        t.setTextSize(17);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(t, matchWrap());
        final TextView value = new TextView(this);
        value.setTextColor(COLOR_MUTED);
        value.setTextSize(13);
        value.setText(change.apply(progress));
        content.addView(value, topMargin(matchWrap(), 6));
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(progress);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                value.setText(change.apply(p));
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        content.addView(bar, topMargin(matchWrap(), 8));
        dialog.setContentView(content, new android.view.ViewGroup.LayoutParams(
                dialogWidth(0.55f, 420), android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.show();
    }

    // Manual wah-wah for the guitar: toggle + sweep slider (heel ← → toe).
    // Sits ahead of the drive, so it works with every pedal/amp tone.
    private void pushGuitarRackFx() {
        engine.setGuitarRackFx(guitarCompOn, guitarCompAmount,
                guitarModOn, guitarModRate, guitarModDepth,
                guitarDelayOn, guitarDelayTime, guitarDelayFeedback, guitarDelayMix,
                guitarRoomOn, guitarRoomMix);
    }

    private ScrollView buildGuitarRack() {
        LinearLayout rack = new LinearLayout(this);
        rack.setOrientation(LinearLayout.VERTICAL);
        signalChainView = new SignalChainView(this);
        signalChainView.setChain(new String[] {
                "IN", "GATE", "COMP", "WAH", "AMP", "CAB", "MOD", "DELAY", "ROOM", "OUT"
        }, toneAccentStatic(currentPreset), 4);
        rack.addView(signalChainView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(82)));

        LinearLayout neural = stagePanel("METAL NAM TEST · BUILT IN", 0xffd34b58);
        LinearLayout neuralRow = new LinearLayout(this);
        neuralRow.setOrientation(LinearLayout.HORIZONTAL);
        neuralRow.setGravity(Gravity.CENTER_VERTICAL);
        guitarNamTestButton = chipButton("Metal NAM");
        styleChipButton(guitarNamTestButton, guitarNamTestOn);
        guitarNamTestButton.setOnClickListener(v -> {
            if (guitarNamTestLoading) return;
            guitarNamTestOn = !guitarNamTestOn;
            prefs.edit().putBoolean("guitar_nam_test_on", guitarNamTestOn).apply();
            if (guitarNamTestOn && !guitarNamTestReady) {
                loadBuiltInMetalRig();
            } else {
                pushBuiltInMetalRigState();
                refreshBuiltInMetalRig();
            }
        });
        neuralRow.addView(guitarNamTestButton, new LinearLayout.LayoutParams(
                dp(116), LinearLayout.LayoutParams.WRAP_CONTENT));
        guitarNamTestStatus = labelText("");
        guitarNamTestStatus.setTextColor(COLOR_TEXT);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        statusLp.leftMargin = dp(12);
        neuralRow.addView(guitarNamTestStatus, statusLp);
        neural.addView(neuralRow, matchWrap());
        LinearLayout rigChoices = new LinearLayout(this);
        rigChoices.setOrientation(LinearLayout.HORIZONTAL);
        String[] rigNames = {"Tight Delay", "HiGain Fuzz"};
        for (int i = 0; i < rigNames.length; i++) {
            final int style = i;
            Button choice = chipButton(rigNames[i]);
            metalRigStyleButtons[i] = choice;
            styleChipButton(choice, metalRigStyle == i);
            choice.setOnClickListener(v -> {
                if (guitarNamTestLoading || metalRigStyle == style) return;
                metalRigStyle = style;
                guitarNamIndex = style == 0 ? 0 : 1;
                guitarCabIrIndex = style == 0 ? 0 : 1;
                guitarNamTestOn = true;
                guitarNamTestReady = false;
                prefs.edit().putInt("metal_rig_style", style)
                        .putInt("guitar_nam_index", guitarNamIndex)
                        .putInt("guitar_cab_ir_index", guitarCabIrIndex)
                        .putBoolean("guitar_nam_test_on", true).apply();
                pushBuiltInMetalRigFx();
                refreshBuiltInMetalRig();
                loadBuiltInMetalRig();
            });
            LinearLayout.LayoutParams choiceLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) choiceLp.leftMargin = dp(8);
            rigChoices.addView(choice, choiceLp);
        }
        neural.addView(rigChoices, topMargin(matchWrap(), 8));
        TextView neuralHint = labelText(metalRigStyle == 0
                ? "Screamer · 5153 NAM · Mesa 4x12 IR · Delay"
                : "Red Fuzz · British high-gain NAM · Lead 800 IR");
        neuralHint.setTextColor(COLOR_TEXT);
        neural.addView(neuralHint, topMargin(matchWrap(), 6));
        Button cabinetPicker = chipButton("Cabinet · " + GUITAR_CAB_NAMES[guitarCabIrIndex]);
        cabinetPicker.setOnClickListener(v -> showGuitarCabinetPicker());
        neural.addView(cabinetPicker, topMargin(matchWrap(), 8));
        LinearLayout boostControls = new LinearLayout(this);
        boostControls.setOrientation(LinearLayout.HORIZONTAL);
        boostControls.addView(buildRackSlider("Drive", metalBoostDrive, value -> {
            metalBoostDrive = value;
            prefs.edit().putFloat("metal_boost_drive", value).apply();
            pushBuiltInMetalRigFx();
        }), rackWeight());
        boostControls.addView(buildRackSlider("Tone", metalBoostTone, value -> {
            metalBoostTone = value;
            prefs.edit().putFloat("metal_boost_tone", value).apply();
            pushBuiltInMetalRigFx();
        }), rackWeight());
        boostControls.addView(buildRackSlider("Level", metalBoostLevel, value -> {
            metalBoostLevel = value;
            prefs.edit().putFloat("metal_boost_level", value).apply();
            pushBuiltInMetalRigFx();
        }), rackWeight());
        neural.addView(boostControls, topMargin(matchWrap(), 8));
        LinearLayout echoControls = new LinearLayout(this);
        echoControls.setOrientation(LinearLayout.HORIZONTAL);
        echoControls.addView(buildRackSlider("Delay", metalDelayTime, value -> {
            metalDelayTime = value;
            prefs.edit().putFloat("metal_delay_time", value).apply();
            pushBuiltInMetalRigFx();
        }), rackWeight());
        echoControls.addView(buildRackSlider("Repeats", metalDelayFeedback / 0.78f, value -> {
            metalDelayFeedback = value * 0.78f;
            prefs.edit().putFloat("metal_delay_feedback", metalDelayFeedback).apply();
            pushBuiltInMetalRigFx();
        }), rackWeight());
        echoControls.addView(buildRackSlider("Delay Mix", metalDelayMix / 0.55f, value -> {
            metalDelayMix = value * 0.55f;
            prefs.edit().putFloat("metal_delay_mix", metalDelayMix).apply();
            pushBuiltInMetalRigFx();
        }), rackWeight());
        neural.addView(echoControls, topMargin(matchWrap(), 6));
        rack.addView(neural, topMargin(matchWrap(), 8));
        refreshBuiltInMetalRig();
        pushBuiltInMetalRigFx();
        pushBuiltInMetalRigState();
        if (guitarNamTestOn && !guitarNamTestReady) loadBuiltInMetalRig();

        LinearLayout input = stagePanel("01  INPUT · DYNAMICS", COLOR_GREEN);
        input.addView(buildGateRow(), matchWrap());
        input.addView(buildRackToggle("Compressor", guitarCompOn, value -> {
            guitarCompOn = value;
            prefs.edit().putBoolean("guitar_comp_on", value).apply();
            pushGuitarRackFx();
        }, buildRackSlider("Amount", guitarCompAmount, value -> {
            guitarCompAmount = value;
            prefs.edit().putFloat("guitar_comp_amount", value).apply();
            pushGuitarRackFx();
        })), topMargin(matchWrap(), 6));
        rack.addView(input, topMargin(matchWrap(), 8));

        LinearLayout wah = stagePanel("02  WAH · FILTER", 0xff2aa36b);
        wah.addView(buildWahRow(), matchWrap());
        rack.addView(wah, topMargin(matchWrap(), 8));

        LinearLayout amp = stagePanel("03  AMP · EQ · OUTPUT", toneAccentStatic(currentPreset));
        TextView ampName = labelText(currentPreset.label.toUpperCase(Locale.ROOT));
        ampName.setTextColor(COLOR_TEXT);
        amp.addView(ampName, matchWrap());
        liveControlView = new LiveControlView(this);
        liveControlView.setControlsChangedListener(this::applyLiveControls);
        amp.addView(liveControlView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(245)));
        rack.addView(amp, topMargin(matchWrap(), 8));

        LinearLayout cab = stagePanel("04  SPEAKER · CABINET", 0xffdf9d32);
        cab.addView(buildCabRow(), matchWrap());
        rack.addView(cab, topMargin(matchWrap(), 8));

        LinearLayout mod = stagePanel("05  MODULATION · CHORUS", 0xff7a62df);
        mod.addView(buildRackToggle("Chorus", guitarModOn, value -> {
            guitarModOn = value;
            prefs.edit().putBoolean("guitar_mod_on", value).apply();
            pushGuitarRackFx();
        }, buildRackSlider("Rate", guitarModRate, value -> {
            guitarModRate = value;
            prefs.edit().putFloat("guitar_mod_rate", value).apply();
            pushGuitarRackFx();
        }), buildRackSlider("Depth", guitarModDepth, value -> {
            guitarModDepth = value;
            prefs.edit().putFloat("guitar_mod_depth", value).apply();
            pushGuitarRackFx();
        })), matchWrap());
        rack.addView(mod, topMargin(matchWrap(), 8));

        LinearLayout delay = stagePanel("06  DELAY · ECHO", 0xffd75b76);
        delay.addView(buildRackToggle("Delay", guitarDelayOn, value -> {
            guitarDelayOn = value;
            prefs.edit().putBoolean("guitar_delay_on", value).apply();
            pushGuitarRackFx();
        }, buildRackSlider("Time", guitarDelayTime, value -> {
            guitarDelayTime = value;
            prefs.edit().putFloat("guitar_delay_time", value).apply();
            pushGuitarRackFx();
        }), buildRackSlider("Feedback", guitarDelayFeedback / 0.82f, value -> {
            guitarDelayFeedback = value * 0.82f;
            prefs.edit().putFloat("guitar_delay_feedback", guitarDelayFeedback).apply();
            pushGuitarRackFx();
        }), buildRackSlider("Mix", guitarDelayMix / 0.65f, value -> {
            guitarDelayMix = value * 0.65f;
            prefs.edit().putFloat("guitar_delay_mix", guitarDelayMix).apply();
            pushGuitarRackFx();
        })), matchWrap());
        rack.addView(delay, topMargin(matchWrap(), 8));

        LinearLayout room = stagePanel("07  ROOM · REVERB", 0xff278bc2);
        room.addView(buildRackToggle("Room", guitarRoomOn, value -> {
            guitarRoomOn = value;
            prefs.edit().putBoolean("guitar_room_on", value).apply();
            pushGuitarRackFx();
        }, buildRackSlider("Mix", guitarRoomMix / 0.55f, value -> {
            guitarRoomMix = value * 0.55f;
            prefs.edit().putFloat("guitar_room_mix", guitarRoomMix).apply();
            pushGuitarRackFx();
        })), matchWrap());
        rack.addView(room, topMargin(matchWrap(), 8));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(rack, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        pushGuitarRackFx();
        return scroll;
    }

    private void pushBuiltInMetalRigState() {
        boolean enabled = currentMode == InstrumentMode.ELECTRIC_GUITAR
                && guitarNamTestOn && guitarNamTestReady;
        // Cabinet convolution and the output safety curve consume a little
        // headroom. Apply fixed makeup gain here; this is not auto-leveling.
        engine.setNam(enabled, 1.0f, 1.0f, 1.30f);
        engine.setNamIr(enabled && engine.namIrReady());
    }

    private void pushBuiltInMetalRigFx() {
        engine.setBuiltInMetalRigFx(metalRigStyle,
                metalBoostDrive, metalBoostTone, metalBoostLevel,
                metalDelayTime, metalDelayFeedback, metalDelayMix);
    }

    private void refreshBuiltInMetalRig() {
        if (guitarNamTestButton != null) {
            guitarNamTestButton.setEnabled(!guitarNamTestLoading);
            styleChipButton(guitarNamTestButton, guitarNamTestOn);
            guitarNamTestButton.setText(guitarNamTestLoading ? "Loading..." : "Metal NAM");
        }
        if (guitarNamTestStatus != null) {
            String status;
            if (guitarNamTestLoading) status = "Loading neural model and cabinet...";
            else if (guitarNamTestOn && guitarNamTestReady) {
                status = metalRigStyle == 0
                        ? "READY · Tight Delay active"
                        : "READY · HiGain Fuzz active";
            }
            else if (guitarNamTestReady) status = "Ready · bypassed";
            else status = "Tap to load the built-in test rig";
            guitarNamTestStatus.setText(status);
        }
        for (int i = 0; i < metalRigStyleButtons.length; i++) {
            if (metalRigStyleButtons[i] != null) {
                metalRigStyleButtons[i].setEnabled(!guitarNamTestLoading);
                styleChipButton(metalRigStyleButtons[i], metalRigStyle == i);
            }
        }
    }

    private void loadBuiltInMetalRig() {
        if (guitarNamTestLoading) return;
        guitarNamTestLoading = true;
        refreshBuiltInMetalRig();
        beginSoundLoad("Loading built-in metal NAM...");
        final int requestedNam = guitarNamIndex;
        final int requestedCab = guitarCabIrIndex;
        namLoader.execute(() -> {
            byte[] model = readAsset(GUITAR_NAM_ASSETS[requestedNam]);
            byte[] irBytes = readAsset(GUITAR_CAB_ASSETS[requestedCab]);
            boolean modelOk = model != null && engine.loadNamModel(model);
            WavPcm ir = decodeIrWav(irBytes);
            boolean irOk = ir != null && engine.loadNamIr(
                    ir.samples, ir.frames, ir.channels, ir.rate);
            handler.post(() -> {
                guitarNamTestLoading = false;
                guitarNamTestReady = modelOk && irOk;
                finishSoundLoad();
                if (guitarNamTestReady) {
                    // The shared NAM engine now contains the bundled model.
                    // Force an external model to reload when Virtual Guitar
                    // MIDI is opened later.
                    loadedNamUri = null;
                    loadedNamIrUri = null;
                    pushBuiltInMetalRigState();
                } else {
                    guitarNamTestOn = false;
                    prefs.edit().putBoolean("guitar_nam_test_on", false).apply();
                    engine.setNam(false, 1.0f, 1.0f, 1.0f);
                    engine.setNamIr(false);
                    float expected = engine.namExpectedRate();
                    String reason = expected > 0f
                            ? "Built-in NAM needs 48 kHz; current output is "
                                    + Math.round(expected) + " Hz"
                            : "Built-in metal NAM or IR failed to load";
                    Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
                }
                refreshBuiltInMetalRig();
            });
        });
    }

    private View buildRackToggle(String title, boolean active, Consumer<Boolean> change,
                                 View... controls) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button toggle = chipButton(title);
        final boolean[] state = {active};
        styleChipButton(toggle, active);
        toggle.setOnClickListener(v -> {
            state[0] = !state[0];
            styleChipButton(toggle, state[0]);
            change.accept(state[0]);
        });
        row.addView(toggle, new LinearLayout.LayoutParams(dp(116),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        for (View control : controls) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = dp(8);
            row.addView(control, lp);
        }
        return row;
    }

    private LinearLayout.LayoutParams rackWeight() {
        return new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private View buildRackSlider(String label, float value, Consumer<Float> change) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView text = labelText(label + "  " + Math.round(value * 100) + "%");
        text.setTextColor(COLOR_TEXT);
        box.addView(text, matchWrap());
        SeekBar slider = new SeekBar(this);
        slider.setMax(100);
        slider.setProgress(Math.round(value * 100));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                float next = progress / 100f;
                text.setText(label + "  " + progress + "%");
                change.accept(next);
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        box.addView(slider, matchWrap());
        return box;
    }

    private View buildWahRow() {
        wahButton = chipButton("Wah");
        wahSlider = new SeekBar(this);
        wahSlider.setMax(100);
        wahSlider.setProgress(Math.round(wahPos * 100));
        wahButton.setOnClickListener(v -> {
            wahOn = !wahOn;
            engine.setWah(wahOn);
            styleChipButton(wahButton, wahOn);
            updateEffectSlider(wahSlider, wahOn);
            prefs.edit().putBoolean("guitar_wah_on", wahOn).apply();
        });
        wahSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                wahPos = p / 100.0f;
                engine.setWahPos(wahPos);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {
                prefs.edit().putFloat("guitar_wah_pos", wahPos).apply();
            }
        });
        styleChipButton(wahButton, wahOn);
        updateEffectSlider(wahSlider, wahOn);
        engine.setWah(wahOn);
        engine.setWahPos(wahPos);
        return effectRow(wahButton, wahSlider);
    }

    private void pushGuitarCab() {
        engine.setGuitarCab(cabOn, cabType, 1.0f);
    }

    // Cabinet / IR pedal: the toggle turns the speaker sim on/off, the slider
    // picks the cab voicing. This is what stops the DI sounding raw/"physical".
    private View buildCabRow() {
        cabButton = chipButton("Cab: " + CAB_NAMES[cabType]);
        cabSlider = new SeekBar(this);
        cabSlider.setMax(CAB_NAMES.length - 1);
        cabSlider.setProgress(cabType);
        cabButton.setOnClickListener(v -> {
            cabOn = !cabOn;
            styleChipButton(cabButton, cabOn);
            updateEffectSlider(cabSlider, cabOn);
            prefs.edit().putBoolean("guitar_cab_on", cabOn).apply();
            pushGuitarCab();
        });
        cabSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                cabType = p;
                cabButton.setText("Cab: " + CAB_NAMES[cabType]);
                pushGuitarCab();
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {
                prefs.edit().putInt("guitar_cab_type", cabType).apply();
            }
        });
        styleChipButton(cabButton, cabOn);
        updateEffectSlider(cabSlider, cabOn);
        pushGuitarCab();
        return effectRow(cabButton, cabSlider);
    }

    // Noise gate (guitar & bass): toggle + amount. Higher amount = clamps down
    // harder, so idle hum/buzz stays dead silent between notes.
    private float gateThreshold() {
        return 0.006f + gateAmount * 0.055f;   // ~0.006 .. 0.061
    }

    private void pushNoiseGate() {
        engine.setNoiseGate(gateOn ? gateThreshold() : 0.0f);
    }

    private View buildGateRow() {
        gateButton = chipButton("Gate");
        gateSlider = new SeekBar(this);
        gateSlider.setMax(100);
        gateSlider.setProgress(Math.round(gateAmount * 100));
        gateButton.setOnClickListener(v -> {
            gateOn = !gateOn;
            pushNoiseGate();
            styleChipButton(gateButton, gateOn);
            updateEffectSlider(gateSlider, gateOn);
            prefs.edit().putBoolean("guitar_gate_on", gateOn).apply();
        });
        gateSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                gateAmount = p / 100.0f;
                pushNoiseGate();
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {
                prefs.edit().putFloat("guitar_gate_amount", gateAmount).apply();
            }
        });
        styleChipButton(gateButton, gateOn);
        updateEffectSlider(gateSlider, gateOn);
        pushNoiseGate();
        return effectRow(gateButton, gateSlider);
    }

    private View effectRow(Button toggle, SeekBar slider) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(dp(108),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        sLp.leftMargin = dp(14);
        row.addView(toggle, tLp);
        row.addView(slider, sLp);
        return row;
    }

    // The Sustain chip lights while the hardware pedal is held, so the app
    // mirrors the pedal state even when the on-screen toggle is off.
    private void refreshSustainChip() {
        if (sustainButton != null) {
            styleChipButton(sustainButton, sustainOn || midiPedalDown);
        }
    }

    // Slider is only active (and lit) when its effect is turned on.
    private void updateEffectSlider(SeekBar slider, boolean active) {
        slider.setEnabled(active);
        slider.setAlpha(active ? 1.0f : 0.4f);
        int tint = active ? COLOR_AMBER : COLOR_DIM;
        slider.setProgressTintList(android.content.res.ColorStateList.valueOf(tint));
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(tint));
        slider.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(COLOR_BORDER));
    }

    private int sustainTimeToProgress(float s) {
        return Math.round((s - 0.2f) / 5.8f * 100.0f);
    }

    private float progressToSustainTime(int p) {
        return 0.2f + p / 100.0f * 5.8f;
    }

    // Mirror the current reverb wet mix onto the "Space" fader.
    private void syncSpaceKnob() {
        liveControlValues[4] = Math.min(1f, reverbLevel / MAX_REVERB_LEVEL);
        if (liveControlView != null) liveControlView.setValues(liveControlValues);
    }

    private int reverbLevelToProgress(float l) {
        return Math.round(l / 0.6f * 100.0f);
    }

    private float progressToReverbLevel(int p) {
        return p / 100.0f * 0.6f;
    }

    private void saveEffectPrefs() {
        prefs.edit()
                .putBoolean("fx_sustain_on", sustainOn)
                .putBoolean("fx_reverb_on", reverbOn)
                .putFloat("fx_sustain_time", sustainTime)
                .putFloat("fx_reverb_level", reverbLevel)
                .apply();
    }

    private View buildSoundBar(String labelText) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(13), dp(16), dp(13));
        bar.setBackground(moduleBackground(COLOR_SURFACE_RAISED, COLOR_BORDER,
                toneAccentStatic(currentPreset), true));
        bar.setClickable(true);
        bar.setOnClickListener(v -> {
            if (currentMode == InstrumentMode.PIANO) {
                showPianoSoundBrowser();
            } else if (currentMode == InstrumentMode.ELECTRIC_GUITAR) {
                showGuitarNamPicker();
            } else {
                showProgramPicker();
            }
        });

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        bar.addView(textCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView label = labelText(labelText);
        label.setTextColor(COLOR_AMBER);
        textCol.addView(label, matchWrap());

        soundBarText = new TextView(this);
        soundBarText.setText(currentMode == InstrumentMode.PIANO
                ? pianoSoundName(false)
                : (currentMode == InstrumentMode.ELECTRIC_GUITAR
                    ? GUITAR_NAM_NAMES[guitarNamIndex] : currentPreset.label));
        soundBarText.setTextColor(COLOR_TEXT);
        soundBarText.setTextSize(17);
        soundBarText.setSingleLine(true);
        soundBarText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        soundBarText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textCol.addView(soundBarText, topMargin(matchWrap(), 2));

        if (currentMode == InstrumentMode.PIANO) {
            soundLoadingText = new TextView(this);
            soundLoadingText.setText("Loading sounds...");
            soundLoadingText.setTextColor(COLOR_AMBER);
            soundLoadingText.setTextSize(12);
            boolean loadingSound = !soundFontReady || soundLoadsInFlight > 0;
            soundLoadingText.setVisibility(loadingSound ? View.VISIBLE : View.GONE);
            textCol.addView(soundLoadingText, topMargin(matchWrap(), 3));
            soundLoadingBar = new ShineBar(this);
            soundLoadingBar.setAccent(COLOR_AMBER);
            soundLoadingBar.setVisibility(loadingSound ? View.VISIBLE : View.GONE);
            textCol.addView(soundLoadingBar, topMargin(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(4)), 4));
        }

        TextView chevron = new TextView(this);
        chevron.setText(">");
        chevron.setTextColor(COLOR_MUTED);
        chevron.setTextSize(20);
        bar.addView(chevron, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (currentMode != InstrumentMode.PIANO) {
            return bar;
        }
        // Piano: Sound 1 and Sound 2 side by side. Sound 2 opens its picker
        // with a tap (no more long-press on Dual) and only comes alive while
        // Dual is on.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lLp.rightMargin = dp(5);
        row.addView(bar, lLp);

        sound2Bar = new LinearLayout(this);
        sound2Bar.setOrientation(LinearLayout.VERTICAL);
        sound2Bar.setGravity(Gravity.CENTER_VERTICAL);
        sound2Bar.setPadding(dp(16), dp(13), dp(16), dp(13));
        sound2Bar.setClickable(true);
        sound2Bar.setOnClickListener(v -> {
            if (!dualOn) {
                Toast.makeText(this, "Turn on Dual first — Sound 2 plays with it",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            showPianoSoundBrowser();
        });
        TextView label2 = labelText("SOUND 2");
        label2.setTextColor(COLOR_AMBER);
        sound2Bar.addView(label2, matchWrap());
        sound2BarText = new TextView(this);
        sound2BarText.setTextSize(17);
        sound2BarText.setSingleLine(true);
        sound2BarText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sound2BarText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        sound2Bar.addView(sound2BarText, topMargin(matchWrap(), 2));
        refreshSound2Bar();
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        rLp.leftMargin = dp(5);
        row.addView(sound2Bar, rLp);
        return row;
    }

    // Sound 2 half of the piano sound bar: dim while Dual is off.
    private void refreshSound2Bar() {
        if (sound2Bar == null || sound2BarText == null) {
            return;
        }
        sound2Bar.setBackground(moduleBackground(
                dualOn ? COLOR_SURFACE_RAISED : COLOR_SURFACE,
                COLOR_BORDER,
                dualOn ? toneAccentStatic(dualPreset) : COLOR_DIM, true));
        sound2BarText.setText(pianoSoundName(true));
        sound2BarText.setTextColor(dualOn ? COLOR_TEXT : COLOR_DIM);
    }

    private View buildPresetBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bar.setBackground(panelBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));

        favStar = new TextView(this);
        favStar.setText("*");
        favStar.setTextSize(24);
        favStar.setGravity(Gravity.CENTER);
        favStar.setPadding(dp(12), 0, dp(12), 0);
        favStar.setClickable(true);
        favStar.setOnClickListener(v -> {
            toggleFavorite(currentPreset);
            updateSelectionStyles();
            if (showFavoritesOnly) {
                rebuildPresetButtons();
            }
        });
        bar.addView(favStar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        soundBarText = new TextView(this);
        soundBarText.setText(currentMode == InstrumentMode.PIANO
                ? pianoSoundName(false) : currentPreset.label);
        soundBarText.setTextColor(COLOR_TEXT);
        soundBarText.setTextSize(15);
        soundBarText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bar.addView(soundBarText, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button save = chipButton("SAVE");
        save.setOnClickListener(v -> {
            saveFavorites();
            save.setText("SAVED");
        });
        styleChipButton(save, false);
        bar.addView(save, new LinearLayout.LayoutParams(dp(70), dp(42)));
        return bar;
    }

    private LinearLayout buildPedalTabs() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button all = chipButton("All");
        all.setOnClickListener(v -> {
            showFavoritesOnly = false;
            rebuildPresetButtons();
        });
        Button fav = chipButton("Favorites");
        fav.setOnClickListener(v -> {
            showFavoritesOnly = true;
            rebuildPresetButtons();
        });
        row.addView(all, chipParams(true));
        row.addView(fav, chipParams(false));
        row.setTag(new Button[]{all, fav});
        return row;
    }

    private void showGuitarNamPicker() {
        showGuitarRigPicker(false);
    }

    private void showGuitarCabinetPicker() {
        showGuitarRigPicker(true);
    }

    private void showGuitarRigPicker(boolean cabinets) {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));
        TextView title = new TextView(this);
        title.setText(cabinets ? "Choose Cabinet IR" : "Choose NAM Amp");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(19);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());
        EditText search = new EditText(this);
        searchIme(search);
        search.setHint(cabinets ? "Search 23 cabinets" : "Search NAM amps");
        search.setTextColor(COLOR_TEXT);
        search.setHintTextColor(COLOR_DIM);
        search.setSingleLine(true);
        search.setBackground(panelBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        content.addView(search, topMargin(matchWrap(), 10));
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360)), 10));
        String[] names = cabinets ? GUITAR_CAB_NAMES : GUITAR_NAM_NAMES;
        Runnable populate = () -> {
            list.removeAllViews();
            String filter = search.getText().toString().trim().toLowerCase(Locale.US);
            int shown = 0;
            for (int i = 0; i < names.length; i++) {
                if (!filter.isEmpty() && !names[i].toLowerCase(Locale.US).contains(filter)) {
                    continue;
                }
                final int index = i;
                Button row = chipButton(names[i]);
                boolean selected = cabinets
                        ? guitarCabIrIndex == index : guitarNamIndex == index;
                styleChipButton(row, selected);
                row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                row.setOnClickListener(v -> {
                    if (cabinets) guitarCabIrIndex = index;
                    else guitarNamIndex = index;
                    guitarNamTestOn = true;
                    guitarNamTestReady = false;
                    prefs.edit()
                            .putInt("guitar_nam_index", guitarNamIndex)
                            .putInt("guitar_cab_ir_index", guitarCabIrIndex)
                            .putBoolean("guitar_nam_test_on", true)
                            .apply();
                    if (soundBarText != null) {
                        soundBarText.setText(GUITAR_NAM_NAMES[guitarNamIndex]);
                    }
                    dialog.dismiss();
                    loadBuiltInMetalRig();
                });
                list.addView(row, topMargin(matchWrap(), shown++ == 0 ? 0 : 6));
            }
        };
        populate.run();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { populate.run(); }
        });
        dialog.setContentView(content);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.88f, 600),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
    }

    private void styleTabs() {
        if (pedalTabsRow == null) {
            return;
        }
        Object tag = pedalTabsRow.getTag();
        if (tag instanceof Button[]) {
            Button[] tabs = (Button[]) tag;
            styleChipButton(tabs[0], !showFavoritesOnly);
            styleChipButton(tabs[1], showFavoritesOnly);
        }
    }

    private void showProgramPicker() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        boolean piano = currentMode == InstrumentMode.PIANO;
        TextView title = new TextView(this);
        title.setText(piano ? "Choose Sound" : "Choose Pedal");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(19);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        final EditText search = new EditText(this);
        searchIme(search);
        search.setHint(piano ? "Search sounds" : "Search pedals");
        search.setHintTextColor(COLOR_DIM);
        search.setTextColor(COLOR_TEXT);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        search.setBackground(panelBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        content.addView(search, topMargin(matchWrap(), 12));

        ScrollView scroll = new ScrollView(this);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        // Screen-adaptive height: a fixed dp(440) overflowed the short
        // landscape screen, so the list "choked" — its bottom sat off-screen
        // and couldn't be scrolled to. Cap it to a fraction of screen height.
        content.addView(scroll, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT), 12));

        populateProgramList(list, "", dialog);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                populateProgramList(list, s.toString(), dialog);
            }
        });

        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    dialogWidth(0.92f, 620),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        // Open scrolled to the currently-selected sound instead of the top.
        scroll.post(() -> {
            if (pickerSelectedRow != null) {
                scroll.scrollTo(0, Math.max(0, pickerSelectedRow.getTop() - dp(72)));
            }
        });
    }

    // The piano sound list is now a fixed, always-open browser in the left
    // rail (built in showInstrumentScreen), so there is nothing to open/close.
    private void showPianoSoundBrowser() { }

    private void closePianoSoundBrowser() { }

    private View buildPianoSoundBrowser() {
        pianoBrowserDualLayout = dualOn;
        LinearLayout browser = new LinearLayout(this);
        browser.setOrientation(LinearLayout.VERTICAL);
        browser.setPadding(dp(2), dp(2), dp(2), dp(2));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        pianoBrowserTitle = new TextView(this);
        pianoBrowserTitle.setText(dualOn ? "SOUND 1 + SOUND 2" : "SOUNDS");
        pianoBrowserTitle.setTextColor(COLOR_TEXT);
        pianoBrowserTitle.setTextSize(15);
        pianoBrowserTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        header.addView(pianoBrowserTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView folderButton = iconButton("+ SF2", this::pickExternalSf2Folder);
        folderButton.setTextSize(13);
        folderButton.setContentDescription("Choose external SoundFont folder");
        folderButton.setBackground(moduleBackground(
                COLOR_SKY_CONTROL, COLOR_BORDER_STRONG, COLOR_TEAL, true));
        header.addView(folderButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)));
        browser.addView(header, matchWrap());

        pianoBrowserSf2Status = new TextView(this);
        pianoBrowserSf2Status.setTextColor(COLOR_DIM);
        pianoBrowserSf2Status.setTextSize(10);
        updateExternalSf2Status();
        browser.addView(pianoBrowserSf2Status, topMargin(matchWrap(), 3));

        pianoBrowserSearch = new EditText(this);
        pianoBrowserSearch.setHint(
                virtualGuitarMidiMode ? "Search guitar instruments" : "Search piano sounds");
        pianoBrowserSearch.setHintTextColor(COLOR_DIM);
        pianoBrowserSearch.setTextColor(COLOR_TEXT);
        pianoBrowserSearch.setTextSize(15);
        pianoBrowserSearch.setSingleLine(true);
        searchIme(pianoBrowserSearch);
        pianoBrowserSearch.setPadding(dp(12), dp(10), dp(12), dp(10));
        pianoBrowserSearch.setBackground(panelBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        browser.addView(pianoBrowserSearch, topMargin(matchWrap(), 8));

        LinearLayout lists = new LinearLayout(this);
        lists.setOrientation(LinearLayout.HORIZONTAL);
        pianoBrowserSound1List = new LinearLayout(this);
        pianoBrowserSound1List.setOrientation(LinearLayout.VERTICAL);
        lists.addView(buildPianoSoundColumn("SOUND 1", pianoBrowserSound1List, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        pianoBrowserDivider = new View(this);
        pianoBrowserDivider.setBackgroundColor(COLOR_BORDER);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                dualOn ? dp(1) : 0, LinearLayout.LayoutParams.MATCH_PARENT);
        dividerLp.leftMargin = dualOn ? dp(8) : 0;
        dividerLp.rightMargin = dualOn ? dp(8) : 0;
        pianoBrowserDivider.setAlpha(dualOn ? 1f : 0f);
        pianoBrowserDivider.setVisibility(dualOn ? View.VISIBLE : View.GONE);
        lists.addView(pianoBrowserDivider, dividerLp);

        pianoBrowserSound2List = new LinearLayout(this);
        pianoBrowserSound2List.setOrientation(LinearLayout.VERTICAL);
        pianoBrowserSound2Column = buildPianoSoundColumn(
                "SOUND 2", pianoBrowserSound2List, true);
        pianoBrowserSound2Column.setVisibility(dualOn ? View.VISIBLE : View.GONE);
        pianoBrowserSound2Column.setAlpha(dualOn ? 1f : 0f);
        pianoBrowserSound2Column.setPivotX(0f);
        lists.addView(pianoBrowserSound2Column, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, dualOn ? 1f : 0f));
        browser.addView(lists, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f), 8));

        populatePianoSoundBrowserList(pianoBrowserSound1List, "", false);
        if (dualOn) {
            populatePianoSoundBrowserList(pianoBrowserSound2List, "", true);
        }
        pianoBrowserSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String filter = s.toString();
                populatePianoSoundBrowserList(pianoBrowserSound1List, filter, false);
                if (dualOn && pianoBrowserSound2List != null) {
                    populatePianoSoundBrowserList(pianoBrowserSound2List, filter, true);
                }
            }
        });
        return browser;
    }

    private View buildPianoSoundColumn(String heading, LinearLayout list, boolean soundTwo) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView head = new TextView(this);
        head.setText(heading);
        head.setTextColor(soundTwo ? toneAccentStatic(dualPreset) : toneAccentStatic(currentPreset));
        head.setTextSize(12);
        head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        column.addView(head, matchWrap());
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f), 6));
        return column;
    }

    private void animatePianoBrowserDual(boolean showSound2) {
        if (pianoBrowserSound2Column == null || pianoBrowserDivider == null) return;
        if (pianoBrowserStretch != null) {
            android.animation.ValueAnimator old = pianoBrowserStretch;
            pianoBrowserStretch = null;
            old.cancel();
        }
        pianoBrowserDualLayout = showSound2;
        if (pianoBrowserTitle != null) {
            pianoBrowserTitle.setText(showSound2 ? "SOUND 1 + SOUND 2" : "SOUNDS");
        }
        if (showSound2) {
            String filter = pianoBrowserSearch == null
                    ? "" : pianoBrowserSearch.getText().toString();
            populatePianoSoundBrowserList(pianoBrowserSound2List, filter, true);
            pianoBrowserSound2Column.setVisibility(View.VISIBLE);
            pianoBrowserDivider.setVisibility(View.VISIBLE);
        }

        LinearLayout.LayoutParams sound2Lp =
                (LinearLayout.LayoutParams) pianoBrowserSound2Column.getLayoutParams();
        float start = sound2Lp.weight;
        float end = showSound2 ? 1f : 0f;
        android.animation.ValueAnimator animator =
                android.animation.ValueAnimator.ofFloat(start, end);
        pianoBrowserStretch = animator;
        animator.setDuration(300);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animator.addUpdateListener(valueAnimator -> {
            float amount = (float) valueAnimator.getAnimatedValue();
            sound2Lp.weight = amount;
            pianoBrowserSound2Column.setLayoutParams(sound2Lp);
            pianoBrowserSound2Column.setAlpha(amount);
            pianoBrowserSound2Column.setScaleX(0.86f + amount * 0.14f);

            LinearLayout.LayoutParams dividerLp =
                    (LinearLayout.LayoutParams) pianoBrowserDivider.getLayoutParams();
            dividerLp.width = amount > 0.01f ? dp(1) : 0;
            int margin = Math.round(dp(8) * amount);
            dividerLp.leftMargin = margin;
            dividerLp.rightMargin = margin;
            pianoBrowserDivider.setLayoutParams(dividerLp);
            pianoBrowserDivider.setAlpha(amount);
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (pianoBrowserStretch != animator) return;
                pianoBrowserStretch = null;
                if (!showSound2) {
                    pianoBrowserSound2Column.setVisibility(View.GONE);
                    pianoBrowserDivider.setVisibility(View.GONE);
                    pianoSound2Rows.clear();
                    pianoBrowserSound2List.removeAllViews();
                }
                pianoBrowserSound2Column.setScaleX(1f);
            }
        });
        animator.start();
    }

    private void populatePianoSoundBrowserList(LinearLayout list, String filter, boolean soundTwo) {
        list.removeAllViews();
        Map<View, TonePreset> rows = soundTwo ? pianoSound2Rows : pianoSound1Rows;
        Map<View, String> externalRows =
                soundTwo ? externalSound2Rows : externalSound1Rows;
        rows.clear();
        externalRows.clear();
        String f = filter.trim().toLowerCase(Locale.US);
        addExternalSf2Rows(list, f, soundTwo, externalRows);
        String currentCategory = null;
        for (final TonePreset preset : TonePreset.forMode(InstrumentMode.PIANO)) {
            if ((!virtualGuitarMidiMode && preset == TonePreset.VIRTUAL_GUITAR_STARTER)
                    || (virtualGuitarMidiMode && !isGuitarPreset(preset))) {
                continue;
            }
            if (!f.isEmpty()
                    && !preset.label.toLowerCase(Locale.US).contains(f)
                    && !preset.detail.toLowerCase(Locale.US).contains(f)
                    && !preset.category.toLowerCase(Locale.US).contains(f)) {
                continue;
            }
            String category = pianoCategory(preset);
            if (!category.equals(currentCategory)) {
                currentCategory = category;
                TextView head = new TextView(this);
                head.setText(category.toUpperCase(Locale.US));
                head.setTextColor(COLOR_AMBER);
                head.setTextSize(10);
                head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                list.addView(head, topMargin(matchWrap(), list.getChildCount() == 0 ? 4 : 12));
            }
            boolean selected = soundTwo
                    ? activeExternalDualUri == null && preset == dualPreset
                    : activeExternalMainUri == null && preset == currentPreset;
            int accent = toneAccentStatic(preset);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(10), dp(8), dp(10), dp(8));
            item.setClickable(true);
            item.setBackground(moduleBackground(selected ? darken(accent) : COLOR_SURFACE_RAISED,
                    selected ? accent : COLOR_BORDER, accent, true));
            item.setOnClickListener(v -> {
                if (soundTwo) {
                    clearExternalSf2Selection(true);
                    dualPreset = preset;
                    applyDualSound();
                } else {
                    selectPreset(preset);
                }
                refreshPianoSoundBrowserSelection();
            });
            rows.put(item, preset);
            TextView name = new TextView(this);
            name.setText(preset.label);
            name.setTextColor(selected ? COLOR_TEXT : COLOR_MUTED);
            name.setTextSize(13);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.addView(name, matchWrap());
            TextView detail = new TextView(this);
            detail.setText(preset.detail);
            detail.setTextColor(COLOR_DIM);
            detail.setTextSize(10);
            detail.setMaxLines(2);
            item.addView(detail, topMargin(matchWrap(), 1));
            list.addView(item, topMargin(matchWrap(), 5));
        }
        if (list.getChildCount() == 0) {
            list.addView(detailText("No matching sounds."), topMargin(matchWrap(), 8));
        }
    }

    // Selecting a program should update just the two affected rows. Rebuilding
    // 97 rows on every tap made the right-side browser feel sticky on phones.
    private void refreshPianoSoundBrowserSelection() {
        refreshPianoSoundBrowserRows(pianoSound1Rows, false);
        refreshPianoSoundBrowserRows(pianoSound2Rows, true);
        refreshExternalSf2Rows(externalSound1Rows, false);
        refreshExternalSf2Rows(externalSound2Rows, true);
    }

    private void refreshPianoSoundBrowserRows(Map<View, TonePreset> rows, boolean soundTwo) {
        for (Map.Entry<View, TonePreset> entry : rows.entrySet()) {
            View view = entry.getKey();
            TonePreset preset = entry.getValue();
            boolean selected = soundTwo
                    ? activeExternalDualUri == null && preset == dualPreset
                    : activeExternalMainUri == null && preset == currentPreset;
            int accent = toneAccentStatic(preset);
            view.setBackground(moduleBackground(selected ? darken(accent) : COLOR_SURFACE_RAISED,
                    selected ? accent : COLOR_BORDER, accent, true));
            if (view instanceof LinearLayout && ((LinearLayout) view).getChildCount() > 0) {
                View name = ((LinearLayout) view).getChildAt(0);
                if (name instanceof TextView) {
                    ((TextView) name).setTextColor(selected ? COLOR_TEXT : COLOR_MUTED);
                }
            }
        }
    }

    private void populateProgramList(LinearLayout list, String filter, final Dialog dialog) {
        list.removeAllViews();
        pickerSelectedRow = null;
        String f = filter.trim().toLowerCase(Locale.US);
        String currentCategory = null;
        for (final TonePreset preset : TonePreset.forMode(currentMode)) {
            // Match name, description, or category — "korg" finds the M1 bank.
            if (!f.isEmpty()
                    && !preset.label.toLowerCase(Locale.US).contains(f)
                    && !preset.detail.toLowerCase(Locale.US).contains(f)
                    && !preset.category.toLowerCase(Locale.US).contains(f)) {
                continue;
            }
            String category = pianoCategory(preset);
            if (!category.equals(currentCategory)) {
                currentCategory = category;
                TextView head = new TextView(this);
                head.setText(category.toUpperCase(Locale.US));
                head.setTextColor(COLOR_AMBER);
                head.setTextSize(11);
                head.setLetterSpacing(0.08f);
                head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                list.addView(head, topMargin(matchWrap(), list.getChildCount() == 0 ? 6 : 16));
            }
            boolean current = preset == currentPreset;
            int accent = toneAccentStatic(preset);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(12), dp(10), dp(12), dp(10));
            item.setClickable(true);
            item.setBackground(moduleBackground(
                    current ? darken(accent) : COLOR_SURFACE_RAISED,
                    current ? accent : COLOR_BORDER, accent, true));
            item.setOnClickListener(v -> {
                selectPreset(preset);
                dialog.dismiss();
            });
            if (current) {
                pickerSelectedRow = item;
            }
            TextView nm = new TextView(this);
            nm.setText(preset.label);
            nm.setTextColor(current ? COLOR_TEXT : COLOR_MUTED);
            nm.setTextSize(14);
            item.addView(nm, matchWrap());
            TextView dt = new TextView(this);
            dt.setText(preset.detail);
            dt.setTextColor(COLOR_DIM);
            dt.setTextSize(11);
            item.addView(dt, topMargin(matchWrap(), 2));
            list.addView(item, topMargin(matchWrap(), 6));
        }
        if (list.getChildCount() == 0) {
            list.addView(detailText("No matching programs."), topMargin(matchWrap(), 8));
        }
    }

    private void onKeyTouched(int note, boolean down) {
        if (down) {
            engine.noteOn(note, 0.85f);
        } else {
            engine.noteOff(note);
        }
        setKeyPressed(note, down);
    }

    // One spelling everywhere (tuner, key readouts, split labels, MIDI mapping)
    // and it matches the chord picker: flats where charts write them.
    private static final String[] NOTE_NAMES = ROOT_NAMES;

    private String noteName(int midi) {
        if (midi < 0 || midi > 127) {
            return "--";
        }
        return NOTE_NAMES[midi % 12] + (midi / 12 - 1);
    }

    private int nearestMidiNote(float hz) {
        if (hz <= 0.0f) {
            return -1;
        }
        return Math.round(69.0f + 12.0f * (float) (Math.log(hz / 440.0) / Math.log(2.0)));
    }

    private String heldNotesSummary() {
        StringBuilder sb = new StringBuilder();
        for (int n = 0; n < 128; n++) {
            if (keyOn[n]) {
                if (sb.length() > 0) {
                    sb.append("   ");
                }
                sb.append(noteName(n));
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private void setKeyPressed(int note, boolean on) {
        if (note < 0 || note > 127) {
            return;
        }
        keyOn[note] = on;
        handler.post(() -> {
            refreshPianoKeys();
            if (on && drumPadsView != null) {
                drumPadsView.flashNote(note);
            }
            if (keyVizView != null) {
                keyVizView.onNote(note, on);
            }
        });
    }

    private void clearKeys() {
        for (int i = 0; i < keyOn.length; i++) {
            keyOn[i] = false;
        }
    }

    private void refreshPianoKeys() {
        if (pianoKeysView != null) {
            pianoKeysView.setPressedNotes(keyOn);
        }
        if (keyVizView != null) {
            keyVizView.setPressedNotes(keyOn);
        }
        if (pianoNotesText != null) {
            String held = heldNotesSummary();
            pianoNotesText.setText(held != null ? held : "--");
        }
    }

    private void loadSoundFontAsync() {
        new Thread(() -> {
            byte[] gm = readAsset("instrument.sf3");
            if (gm != null) {
                final boolean ok = engine.loadSoundFont(gm);
                handler.post(() -> {
                    soundFontReady = ok;
                    if (soundLoadingText != null) {
                        soundLoadingText.setVisibility(ok ? View.GONE : View.VISIBLE);
                    }
                    if (soundLoadingBar != null) {
                        soundLoadingBar.setVisibility(ok ? View.GONE : View.VISIBLE);
                    }
                });
            }
            // Load the DEFAULT drum kit's font first (slot 6 = Rock 1), right
            // after GM. Otherwise it lands late in this sequence and the first
            // drum hits play the GM fallback, then audibly swap to the real kit
            // once it finishes — the "sudden change of sound at first run".
            byte[] drumDefault = readAsset("drums_tama.sf2");
            if (drumDefault != null) {
                engine.loadDrumFont(6, -9.1f, drumDefault);
            }
            // Gains measured on host (worst-case chord, target peak 0.70).
            loadHqAsset(0, 0, -8.6f, "grand.sf2");
            loadHqAsset(1, 1, -9.2f, "rhodes.sf2");
            loadHqAsset(2, 0, -6.1f, "wurli.sf2");
            loadHqAsset(3, 0, -4.8f, "clav.sf2");
            // Slot 6 = the default Concert Steinway, preloaded so the default
            // piano is instant (and never evicted by other library picks).
            loadHqAsset(STEINWAY_SLOT, 0, -6.0f, "steinway_grand.sf2");
            // Piano library (slot 4) is lazy-loaded on selection — see loadLibraryPiano().
            byte[] drumHq = readAsset("drums_acoustic.sf2");
            if (drumHq != null) {
                engine.loadDrumFont(0, -6.9f, drumHq);
            }
            byte[] drum808 = readAsset("drums_808.sf2");
            if (drum808 != null) {
                engine.loadDrumFont(1, -6.9f, drum808);
            }
            byte[] drum909 = readAsset("drums_909.sf2");
            if (drum909 != null) {
                engine.loadDrumFont(2, -8.2f, drum909);
            }
            byte[] drumNatural = readAsset("drums_natural.sf2");
            if (drumNatural != null) {
                engine.loadDrumFont(3, -9.1f, drumNatural);
            }
            byte[] drumArdency = readAsset("drums_ardency.sf2");
            if (drumArdency != null) {
                engine.loadDrumFont(4, -2.2f, drumArdency);   // Funk sat ~2.5 dB low
            }
            byte[] drumPhat = readAsset("drums_phat.sf2");
            if (drumPhat != null) {
                engine.loadDrumFont(5, -5.6f, drumPhat);
            }
            // slot 6 (drums_tama) is loaded first, up top — see above.
            byte[] drumRock = readAsset("drums_rock.sf2");
            if (drumRock != null) {
                engine.loadDrumFont(7, -5.0f, drumRock);
            }
            byte[] drumLinn = readAsset("drums_linn.sf2");
            if (drumLinn != null) {
                engine.loadDrumFont(8, -7.2f, drumLinn);
            }
            byte[] drumR8 = readAsset("drums_r8.sf2");
            if (drumR8 != null) {
                engine.loadDrumFont(9, -4.4f, drumR8);
            }
            byte[] drumTechno = readAsset("drums_techno.sf2");
            if (drumTechno != null) {
                engine.loadDrumFont(10, -8.4f, drumTechno);
            }
            loadChimeSample();
            loadSwellSamples();
        }, "sf-loader").start();
    }

    // Decode chimes.wav (PCM WAV) into interleaved floats and hand it to the
    // engine as the Drums "Chimes" one-shot.
    private void loadChimeSample() {
        loadOneShotSample("chimes.wav", -1);
    }

    private void loadSwellSamples() {
        for (int i = 0; i < 6; i++) loadOneShotSample("swell_" + (i + 1) + ".wav", i);
    }

    private void loadOneShotSample(String assetName, int swellIndex) {
        byte[] wav = readAsset(assetName);
        if (wav == null) {
            return;
        }
        try {
            // Walk the RIFF chunks to find "fmt " and "data" (little-endian).
            int channels = 2, rate = 48000, bits = 16, dataOff = -1, dataLen = 0;
            int p = 12;   // skip "RIFF" + size + "WAVE"
            while (p + 8 <= wav.length) {
                int id = leI32(wav, p);
                int sz = leI32(wav, p + 4);
                int body = p + 8;
                if (id == 0x20746d66) {          // "fmt "
                    channels = leI16(wav, body + 2);
                    rate = leI32(wav, body + 4);
                    bits = leI16(wav, body + 14);
                } else if (id == 0x61746164) {   // "data"
                    dataOff = body;
                    dataLen = Math.min(sz, wav.length - body);
                    break;
                }
                p = body + sz + (sz & 1);         // chunks are word-aligned
            }
            if (dataOff < 0 || channels < 1 || bits != 16) {
                return;   // only 16-bit PCM is shipped for chimes
            }
            int frames = dataLen / (channels * 2);
            float[] out = new float[frames * channels];
            int idx = dataOff;
            for (int i = 0; i < frames * channels; i++) {
                short s = (short) (leI16(wav, idx));
                out[i] = s / 32768f;
                idx += 2;
            }
            if (swellIndex < 0) engine.loadChimeSample(out, frames, channels, rate);
            else engine.loadSwellSample(swellIndex, out, frames, channels, rate);
        } catch (RuntimeException ignore) {
            // Malformed WAV: skip the chime rather than crash startup.
        }
    }

    private static int leI16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int leI32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private void loadHqAsset(int slot, int presetNumber, float gainDb, String name) {
        byte[] data = readAsset(name);
        if (data != null) {
            engine.loadHqFont(slot, presetNumber, gainDb, data);
        }
    }

    private byte[] readAsset(String name) {
        try (java.io.InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1 << 20);
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    // HQ font slots: 0 = grand.sf2, 1 = rhodes.sf2, 2 = wurli.sf2, 3 = clav.sf2,
    // 4 = lazy-loaded piano library, 5 = lazy Dual Sound 2,
    // 6 = preloaded Concert Steinway (the default piano — kept resident so the
    // default sound is instant, never evicted by other library selections);
    // -1 = GM soundfont.
    private static final int LAZY_PIANO_SLOT = 4;
    private static final int DUAL_LAZY_SLOT = 5;
    private static final int STEINWAY_SLOT = 6;
    private static final int EXTERNAL_PIANO_SLOT = 7;
    private static final int EXTERNAL_DUAL_SLOT = 8;
    private static final int GUITAR_PALM_SLOT = 9;
    private static final int GUITAR_HARM_SLOT = 10;
    private static final int EXTERNAL_LAYER_SLOT_BASE = 11;
    private static final float EXTERNAL_SF2_GAIN_DB = -9.0f;

    private static int externalLayerSlot(int layer) {
        switch (layer) {
            case 2: return EXTERNAL_LAYER_SLOT_BASE;
            case 3: return EXTERNAL_LAYER_SLOT_BASE + 1;
            case 4: return EXTERNAL_LAYER_SLOT_BASE + 2;
            case 6: return EXTERNAL_LAYER_SLOT_BASE + 3;
            case 7: return EXTERNAL_LAYER_SLOT_BASE + 4;
            case 8: return EXTERNAL_LAYER_SLOT_BASE + 5;
            default: return -1;
        }
    }

    private int pianoFontSlot(TonePreset p) {
        if (p == TonePreset.STEINWAY_LIB) {
            return STEINWAY_SLOT;   // preloaded, not lazy
        }
        if (p.asset != null) {
            return LAZY_PIANO_SLOT;
        }
        switch (p) {
            case SIG_NORD_GRAND:
            case PIANO_CONCERT_GRAND:
            case PIANO_STUDIO_GRAND:
            case PIANO_MELLOW_GRAND:
            case PIANO_ROCK_GRAND:
            case PIANO_HOUSE:
                return 0;
            case SIG_SUITCASE:
            case SIG_DX7:
            case PIANO_STAGE_TINE:
            case PIANO_SUITCASE_73:
            case PIANO_FM_RHODES:
            case PIANO_DX7:
            case PIANO_DYNO:
            case PIANO_LA_BALLAD:
            case PIANO_GLASSY_EP:
                return 1;
            case SIG_WURLI:
            case PIANO_AMPED_REED:
            case PIANO_TREMOLO_WURLY:
                return 2;
            case SIG_CLAV:
            case PIANO_CLAV_FUNK:
            case PIANO_WAH_CLAV:
                return 3;
            default:
                return -1;
        }
    }

    private int pianoProgram(TonePreset preset) {
        return Math.max(0, Math.min(127, preset.program));
    }

    private void applyPianoProgram() {
        if (currentMode == InstrumentMode.PIANO) {
            // Notes held across a sound switch would fold/route differently on
            // release and ring forever on sustaining fonts — silence them first.
            engine.allNotesOff();
            // An external guitar SF2 is reloaded asynchronously after process
            // recreation. Keep that interval playable as a GM electric guitar
            // instead of briefly falling back to Acoustic Grand Piano.
            engine.setMidiProgram(virtualGuitarMidiMode
                    ? 27 : pianoProgram(currentPreset));
            engine.setMidiLayer(dualOn ? pianoProgram(dualPreset) : currentPreset.layer);
            // The full piano routes per-manual (Sound 1 = chord manual, Sound 2 =
            // melody manual), so it must NOT apply a pitch split — otherwise Dual
            // leaves a middle-C split active ("split always on"). fullKeysRoute
            // also forces this, but pin it here too since applyPianoProgram runs
            // from many paths.
            if (onFullPiano) {
                fullPianoRouteConfig();            // per split style (key-split or two-manual)
            } else if (layerMode) {
                engine.setKeySplitConfig(0, -1);   // whole-keyboard layering, no split
            } else {
                engine.setKeySplitConfig(dualSplit, dualOn ? dualFoldProgram() : -1);
            }
            // GM sounds with partial keyboards get octave folding; HQ fonts don't.
            engine.setNoteFoldProgram(activeExternalMainUri == null
                    && currentPreset.asset == null
                    && pianoFontSlot(currentPreset) == -1 ? pianoProgram(currentPreset) : -1);
            float[] fx = pianoFx(currentPreset);
            // A Mod position the player dialled in by hand outranks the preset's
            // baked chorus, so re-entering this path (mode switches, full-keys
            // toggles) can't silently snap the knob back. Changing the sound
            // clears the override — see modOverridePreset.
            float chorus = (modOverridePreset == currentPreset && modOverride >= 0f)
                    ? modOverride : fx[2];
            engine.setPianoFx(fx[0], fx[1], chorus, fx[3]);
            // Keep the "Mod" (chorus) and "Space" (reverb) faders in sync with
            // what's actually sounding, so knob positions never lie.
            liveControlValues[2] = chorus;
            liveControlValues[4] = Math.min(1f, reverbLevel / MAX_REVERB_LEVEL);
            if (liveControlView != null) liveControlView.setValues(liveControlValues);
            if (activeExternalMainUri != null) {
                if (activeExternalMainUri.equals(loadedExternalMainUri)) {
                    engine.setHqFontPreset(EXTERNAL_PIANO_SLOT, activeExternalMainPreset);
                    engine.setFontSlot(EXTERNAL_PIANO_SLOT);
                } else {
                    engine.setFontSlot(-1);
                    loadExternalSf2(findExternalSf2(activeExternalMainUri), false);
                }
            } else if (currentPreset.asset != null
                    && pianoFontSlot(currentPreset) == LAZY_PIANO_SLOT) {
                loadLibraryPiano(currentPreset);
            } else {
                engine.setFontSlot(pianoFontSlot(currentPreset));   // preloaded slot (incl. Steinway)
            }
            pushPianoGlide();
            applyLayers();   // keep the extra-layer blend live with the sound
            pushPianoGuitarRig();
            refreshPianoGuitarRig();
        }
    }

    // Per-font load gain (dB) for the 42 library pianos; hot fonts pulled down to avoid clipping.
    // Measured on host: worst-case chord at max velocity, target peak 0.70.
    private static final float[] PIANO_LIB_GAIN = {
        -7.4f, -6.8f, -10.9f, -10.4f, -15.6f, -10.9f, -14.3f,
        -12.6f, -14.0f, -18.8f, -5.9f, -11.1f, -16.6f, -15.1f,
        -10.7f, -9.3f, -10.6f, -7.4f, -9.1f, -6.7f, -5.7f,
        -20.3f, -7.4f, -10.3f, -1.2f, -1.7f, -11.6f, -7.8f,
        -2.5f, -13.3f, -12.3f, -13.8f, -3.7f, -10.0f, -10.2f,
        -13.3f, -9.9f, -6.3f, -10.8f, -13.1f, -7.0f, -13.4f,
    };

    private float libraryPianoGain(String asset) {
        // Synthesized patches: trims keep sustained stacked notes clean.
        if ("firefly_lead.sf2".equals(asset)) {
            return -2.0f;
        }
        if ("firefly_chords.sf2".equals(asset)) {
            return -4.5f;
        }
        if ("steinway_grand.sf2".equals(asset)) {
            return -6.0f;   // sampled Steinway (SF3 4 MB, 0.4s release); trims stacked chords
        }
        if ("giga_grand.sf2".equals(asset)) {
            return -6.0f;   // bright Gigapiano grand (SF3)
        }
        if ("rhodes_vs.sf2".equals(asset)) {
            return -11.2f;  // Rhodes Mark I (SF3, 0.5s release cap); hot samples
        }
        if ("wurli_dry.sf2".equals(asset)) {
            return -1.6f;   // dry Wurlitzer (SF3); quiet one-shot samples
        }
        if ("dx7_ep.sf2".equals(asset)) {
            return -11.3f;  // Yamaha DX7 FM piano (SF3, added 0.3s release); hot samples
        }
        if ("nice_piano.sf2".equals(asset)) {
            return -10.5f;  // JV-1080 Nice Piano (SF3, extracted, 0.5s release cap)
        }
        if ("ai_grand.sf2".equals(asset)) {
            return -10.6f;  // AI sampled grand (SF3, one-shot)
        }
        if ("wurly_dark.sf2".equals(asset)) {
            return -11.0f;  // dark Wurlitzer (SF3)
        }
        if ("motif_grand.sf2".equals(asset)) {
            return -11.7f;  // Motif ES concert grand (SF3, extracted, 0.5s release cap)
        }
        if ("cola_grand.sf2".equals(asset)) {
            return -12.5f;  // Piano de Cola looped grand (SF3, 0.5s release cap)
        }
        if ("marimba.sf2".equals(asset)) {
            return -8.9f;   // Korg IS-50 marimba (SF3, added 0.2s release)
        }
        try {
            int n = Integer.parseInt(asset.substring(10, 12));   // piano_lib_NN.sf2
            if (n >= 0 && n < PIANO_LIB_GAIN.length) {
                return PIANO_LIB_GAIN[n];
            }
        } catch (RuntimeException ignored) {
        }
        return -9.0f;
    }

    // Library pianos are loaded on demand (one at a time) to keep memory low.
    private String loadedLibraryAsset;

    private void loadLibraryPiano(final TonePreset preset) {
        final String asset = preset.asset;
        if (asset == null) {
            return;
        }
        if (asset.equals(loadedLibraryAsset)) {
            engine.setFontSlot(LAZY_PIANO_SLOT);
            return;
        }
        if (soundLoadingText != null) {
            soundLoadingText.setText("Loading sound...");
            soundLoadingText.setVisibility(View.VISIBLE);
        }
        if (soundLoadingBar != null) soundLoadingBar.setVisibility(View.VISIBLE);
        final float gainDb = libraryPianoGain(asset);
        new Thread(() -> {
            byte[] data = readAsset(asset);
            final boolean ok = data != null && engine.loadHqFont(LAZY_PIANO_SLOT, 0, gainDb, data);
            handler.post(() -> {
                if (ok) {
                    loadedLibraryAsset = asset;
                }
                // Only route if this preset is still the selected one.
                if (currentMode == InstrumentMode.PIANO
                        && activeExternalMainUri == null && preset == currentPreset) {
                    engine.setFontSlot(ok ? LAZY_PIANO_SLOT : -1);
                }
                if (soundLoadingText != null) {
                    soundLoadingText.setVisibility(View.GONE);
                }
                if (soundLoadingBar != null) soundLoadingBar.setVisibility(View.GONE);
            });
        }, "piano-lib-loader").start();
    }

    private void pushCymbalGains() {
        engine.setCymbalGain(0, cymGainHat);
        engine.setCymbalGain(1, cymGainRide);
        engine.setCymbalGain(2, cymGainCrash);
    }

    // Poll the engine: show the shine bar while the selected kit's font loads,
    // hide it the instant the sound is ready. (The drums play silence rather
    // than the GM fallback during this window — no more mid-play sound swap.)
    private final Runnable drumLoadPoll = new Runnable() {
        @Override public void run() {
            if (drumLoadingBar == null || currentMode != InstrumentMode.DRUMS) {
                return;
            }
            boolean loading = !engine.drumKitReady();
            drumLoadingBar.setVisibility(loading ? View.VISIBLE : View.GONE);
            if (loading) {
                handler.postDelayed(this, 200);
            }
        }
    };

    private void refreshDrumLoadingBar() {
        if (drumLoadingBar == null) {
            return;
        }
        handler.removeCallbacks(drumLoadPoll);
        handler.post(drumLoadPoll);
    }

    // Grey out (and disable) drum pads the current kit doesn't voice. While a kit
    // is still loading, drumNoteHasSound reports everything available, so this
    // re-polls until the font is ready and the real map is known.
    private final Runnable padAvailPoll = this::refreshPadAvailability;

    private void refreshPadAvailability() {
        if (currentMode != InstrumentMode.DRUMS) {
            return;
        }
        handler.removeCallbacks(padAvailPoll);
        boolean[] avail = new boolean[128];
        for (int n = 0; n < 128; n++) {
            avail[n] = engine.drumNoteHasSound(n);
        }
        avail[84] = true;   // Chimes piece plays the bundled chimes.wav, kit-independent
        if (drumKitView != null) drumKitView.markDirectWavAvailable(avail);
        if (drumPadsView != null) drumPadsView.setNoteAvailability(avail);
        if (drumKitView != null) drumKitView.setNoteAvailability(avail);
        if (!engine.drumKitReady()) {
            handler.postDelayed(padAvailPoll, 200);
        }
    }

    private void applyDrumKit() {
        if (currentMode != InstrumentMode.DRUMS) {
            return;
        }
        engine.setDrumRoom(drumRoomLevel);
        pushCymbalGains();
        // Kit Mode with per-piece sounds takes over the custom-drum path.
        if (onFullPads && drumKitView != null && drumKitView.needsPieceRouting()) {
            applyKitModeSounds();
            refreshDrumLoadingBar();
            refreshPadAvailability();
            return;
        }
        if (drumCustomKit) {
            engine.setCustomDrum(true);
            engine.setDrumRemap(0);
            clearCustomDrumRouting();
            for (DrumPiece p : drumPieces) {
                ensureDrumSlotForCode(p.kitSlot);
                engine.setDrumPieceSlot(p.gmNote, p.kitSlot);
                engine.setDrumPieceSrcNote(p.gmNote,
                        p.sourceNote >= 0 ? p.sourceNote : p.gmNote);
                engine.setDrumPieceGain(p.gmNote, p.level);
                engine.setDrumPiecePan(p.gmNote, p.pan);
            }
        } else {
            engine.setCustomDrum(false);
            ensureExtraDrumFontLoaded(currentPreset.program);
            engine.setDrumKit(currentPreset.program);
            engine.setDrumRemap(drumRemapFor(currentPreset));
        }
        refreshDrumLoadingBar();
        refreshPadAvailability();
    }

    // Kit Mode per-piece routing: Default pieces carry the complete selected-kit
    // program into native code. This is deliberately not pieceCodeForProgram():
    // reducing 1102/1104/1901/etc. to a slot made Kit Mode play preset 0 while
    // Pad Mode played the requested alternate preset. Assigned pieces still use
    // an exact (font slot, sample note).
    private void applyKitModeSounds() {
        engine.setCustomDrum(true);
        engine.setDrumRemap(0);
        clearCustomDrumRouting();
        int selectedCode = KIT_SOUND_SELECTED_BASE + currentPreset.program;
        ensureExtraDrumFontLoaded(currentPreset.program);
        for (int[] row : drumKitView.pieceRouting()) {
            int note = row[0], code = row[1], srcNote = row[2], isChimes = row[4];
            int category = row.length > 5 ? row[5] : -1;
            if (isChimes == 1) continue;   // WAV chimes fire via onDrumPad, not a font
            if (isSwellSoundCode(code)) continue; // swell WAVs use the direct path
            int useCode = code >= 0 ? code : selectedCode;
            int useNote = srcNote >= 0 ? srcNote
                    : (category >= 0 && category < DrumKitView.KCAT_GM.length
                            ? defaultKitSourceNote(currentPreset, DrumKitView.KCAT_GM[category])
                            : defaultKitSourceNote(currentPreset, note));
            if (code >= 0) ensureDrumSlotForCode(useCode);
            engine.setDrumPieceSlot(note, useCode);
            engine.setDrumPieceSrcNote(note, useNote);
            engine.setDrumPieceGain(note, 1.0f);   // VOL is already folded into strike velocity
            engine.setDrumPiecePan(note, 0.5f);
        }
    }

    // Map a drum kit PROGRAM to a custom-piece source code (mirrors CUSTOM_KIT_SLOTS):
    // metal → 200+slot, HQ/extra → slot, GM bank-128 program → 100+kit index.
    private static int pieceCodeForProgram(int program) {
        if (program >= 10000) return 200 + drumFontSlot(program);
        if (program >= 1000) return drumFontSlot(program);
        switch (program) {
            case 0: return 100; case 8: return 101; case 16: return 102;
            case 24: return 103; case 32: return 104; case 40: return 105;
            default: return 100;
        }
    }

    // Lazy-load the font a source code needs (GM codes 100+ need none).
    private void ensureDrumSlotForCode(int code) {
        if (isSwellSoundCode(code)) return;
        int slot = code >= 200 ? code - 200 : (code < 100 ? code : -1);
        if (slot >= FIRST_EXTRA_DRUM_SLOT && slot < TOTAL_DRUM_FONT_SLOTS) {
            ensureExtraDrumFontLoaded(1000 + slot * 100);
        }
    }

    // The extra kits are intentionally loaded only when selected. Together
    // they are about 68 MB and would otherwise make every launch needlessly heavy.
    private void ensureExtraDrumFontLoaded(int kitProgram) {
        int slot = drumFontSlot(kitProgram);
        if (slot < FIRST_EXTRA_DRUM_SLOT || slot >= TOTAL_DRUM_FONT_SLOTS) {
            return;
        }
        final String asset = extraDrumAsset(slot);
        if (asset == null) {
            return;
        }
        synchronized (extraDrumFontLoading) {
            if (extraDrumFontLoaded[slot] || extraDrumFontLoading[slot]) {
                return;
            }
            extraDrumFontLoading[slot] = true;
        }
        final int targetSlot = slot;
        final float gainDb = extraDrumGainDb(slot);
        new Thread(() -> {
            // Parsing a multi-megabyte SoundFont is allowed to take its time;
            // keep the UI and real-time audio callbacks ahead of this worker.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            byte[] data = readAsset(asset);
            boolean loaded = data != null && engine.loadDrumFont(targetSlot, gainDb, data);
            handler.post(() -> {
                synchronized (extraDrumFontLoading) {
                    extraDrumFontLoading[targetSlot] = false;
                    extraDrumFontLoaded[targetSlot] = loaded;
                }
                // The selected kit used GM while loading. Switch to its real
                // font as soon as it is ready, only if it is still selected.
                if (loaded && currentMode == InstrumentMode.DRUMS
                        && drumFontSlot(currentPreset.program) == targetSlot) {
                    engine.setDrumKit(currentPreset.program);
                }
            });
        }, "drum-font-" + targetSlot).start();
    }

    // Genre note-remap id for the new one-font-many-kits genre kits.
    private static int drumRemapFor(TonePreset p) {
        // Reggae and Beatbox now play real extracted sample kits (HQ slots 22/23),
        // so only Mambo (GM Latin-percussion remap) and Congas (chromatic conga
        // font spread across the pads) still use a note-remap.
        if (p == TonePreset.DRUM_MAMBO) return 3;
        if (p == TonePreset.DRUM_CONGAS) return 5;
        if (p == TonePreset.DRUM_REGGAE) return 2;           // kwam snare + tuned open toms
        if (p == TonePreset.DRUM_REGGAE_ONEDROP) return 6;   // cross-stick lead, kwam on rim toggle
        return 0;
    }

    // Resolve a standard kit-piece note exactly as the selected full kit does.
    // Default pieces call this at playback time, so changing the kit immediately
    // changes their sound instead of retaining an old custom source note.
    private static int defaultKitSourceNote(TonePreset preset, int note) {
        int remap = drumRemapFor(preset);
        if (remap == 0 && drumFontSlot(preset.program) == 2) remap = 1; // 808 slot
        switch (remap) {
            case 1:
                switch (note) {
                    case 51: return 56;
                    case 47: return 64;
                    case 43: case 41: return 45;
                    case 44: return 42;
                    case 48: return 50;
                    default: return note;
                }
            case 2:
                if (note == 40) return 37;
                return reggaeKitSourceNote(note);
            case 3:
                switch (note) {
                    case 38: return 66; case 40: return 65; case 37: return 75;
                    case 50: return 60; case 48: return 62; case 47: return 63;
                    case 45: return 64; case 43: return 61; case 41: return 64;
                    case 42: return 69; case 44: return 73; case 46: return 70;
                    case 49: case 57: return 56; case 51: return 68; case 59: return 67;
                    default: return note;
                }
            case 5:
                switch (note) {
                    case 35: case 36: case 44: return 60;
                    case 41: case 43: return 62;
                    case 45: case 47: return 64;
                    case 37: case 38: case 40: case 48: return 65;
                    case 50: return 67; case 42: return 69; case 46: return 71;
                    case 49: case 51: case 52: case 53: case 55: case 57: return 72;
                    default: return note >= 60 && note <= 72 ? note : 65;
                }
            case 6:
                if (note == 38) return 37;
                if (note == 37) return 33;
                return reggaeKitSourceNote(note);
            default:
                return note;
        }
    }

    private static boolean isSwellSoundCode(int code) {
        return code >= KIT_SOUND_SWELL_BASE && code < KIT_SOUND_SWELL_BASE + 6;
    }

    private void previewKitSound(int code, int note) {
        if (!engine.isRunning()) {
            Toast.makeText(this, "Start the engine first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isSwellSoundCode(code)) {
            engine.triggerSwell(code - KIT_SOUND_SWELL_BASE);
            return;
        }
        ensureDrumSlotForCode(code);
        engine.previewDrum(code, note);
    }

    private void clearCustomDrumRouting() {
        for (int note = 0; note < 128; note++) {
            engine.setDrumPieceSlot(note, -1);
            engine.setDrumPieceSrcNote(note, -1);
            engine.setDrumPieceGain(note, 1.0f);
            engine.setDrumPiecePan(note, 0.5f);
        }
    }

    private void refreshExternalSf2Rows(Map<View, String> rows, boolean soundTwo) {
        String active = soundTwo ? activeExternalDualUri : activeExternalMainUri;
        for (Map.Entry<View, String> entry : rows.entrySet()) {
            boolean selected = active != null
                    && (entry.getValue() == null || entry.getValue().equals(active));
            View row = entry.getKey();
            row.setBackground(moduleBackground(
                    selected ? darken(COLOR_TEAL) : COLOR_SURFACE_RAISED,
                    selected ? COLOR_TEAL : COLOR_BORDER, COLOR_TEAL, true));
            if (row instanceof LinearLayout && ((LinearLayout) row).getChildCount() > 0) {
                View name = ((LinearLayout) row).getChildAt(0);
                if (name instanceof TextView) {
                    ((TextView) name).setTextColor(selected ? COLOR_TEXT : COLOR_MUTED);
                }
            }
        }
    }

    private void addExternalSf2RowsLegacy(LinearLayout list, String filter, boolean soundTwo,
            Map<View, String> rows) {
        boolean headingAdded = false;
        for (ExternalSf2File file : externalSf2Files) {
            String searchable = (file.name + " " + file.relativePath).toLowerCase(Locale.US);
            if (!filter.isEmpty() && !searchable.contains(filter)) continue;
            if (!headingAdded) {
                TextView heading = new TextView(this);
                heading.setText("EXTERNAL SF2");
                heading.setTextColor(COLOR_TEAL);
                heading.setTextSize(10);
                heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                list.addView(heading, topMargin(matchWrap(), 4));
                headingAdded = true;
            }
            String active = soundTwo ? activeExternalDualUri : activeExternalMainUri;
            boolean selected = file.uri.equals(active);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(10), dp(8), dp(10), dp(8));
            item.setClickable(true);
            item.setBackground(moduleBackground(
                    selected ? darken(COLOR_TEAL) : COLOR_SURFACE_RAISED,
                    selected ? COLOR_TEAL : COLOR_BORDER, COLOR_TEAL, true));
            item.setOnClickListener(v -> loadExternalSf2(file, soundTwo));
            rows.put(item, file.uri);

            TextView name = new TextView(this);
            name.setText(file.name);
            name.setTextColor(selected ? COLOR_TEXT : COLOR_MUTED);
            name.setTextSize(13);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.addView(name, matchWrap());

            TextView detail = new TextView(this);
            detail.setText(file.relativePath + "  ·  " + formatFileSize(file.size));
            detail.setTextColor(COLOR_DIM);
            detail.setTextSize(10);
            detail.setSingleLine(true);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            item.addView(detail, topMargin(matchWrap(), 1));
            list.addView(item, topMargin(matchWrap(), 5));
        }
    }

    private void addExternalSf2Rows(LinearLayout list, String filter, boolean soundTwo,
            Map<View, String> rows) {
        java.util.List<ExternalSf2File> matches = matchingExternalSf2(filter);
        if (matches.isEmpty()) return;

        TextView heading = new TextView(this);
        heading.setText("EXTERNAL SF2");
        heading.setTextColor(COLOR_TEAL);
        heading.setTextSize(10);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        list.addView(heading, topMargin(matchWrap(), 4));

        String active = soundTwo ? activeExternalDualUri : activeExternalMainUri;
        boolean selected = active != null;
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(10), dp(9), dp(10), dp(9));
        item.setClickable(true);
        item.setBackground(moduleBackground(
                selected ? darken(COLOR_TEAL) : COLOR_SURFACE_RAISED,
                selected ? COLOR_TEAL : COLOR_BORDER, COLOR_TEAL, true));
        item.setOnClickListener(v -> showExternalSf2Picker(soundTwo, 0, filter, null));
        rows.put(item, active);

        TextView name = new TextView(this);
        name.setText(selected ? externalSf2Name(active)
                : "Browse " + matches.size() + " external SoundFonts");
        name.setTextColor(selected ? COLOR_TEXT : COLOR_MUTED);
        name.setTextSize(13);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        item.addView(name, matchWrap());

        TextView detail = new TextView(this);
        detail.setText("Searchable catalog - loads only after selection");
        detail.setTextColor(COLOR_DIM);
        detail.setTextSize(10);
        item.addView(detail, topMargin(matchWrap(), 1));
        list.addView(item, topMargin(matchWrap(), 5));
    }

    private java.util.List<ExternalSf2File> matchingExternalSf2(String filter) {
        String f = filter == null ? "" : filter.trim().toLowerCase(Locale.US);
        java.util.ArrayList<ExternalSf2File> matches = new java.util.ArrayList<>();
        for (ExternalSf2File file : externalSf2Files) {
            String searchable = (file.name + " " + file.relativePath).toLowerCase(Locale.US);
            if (f.isEmpty() || searchable.contains(f)) matches.add(file);
        }
        return matches;
    }

    private interface Sf2LoadCallback {
        void complete(boolean ok);
    }

    private void showExternalSf2Picker(boolean soundTwo, int layer, String initialFilter,
            Runnable onPicked) {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(dialogSheet());
        dialog.setCanceledOnTouchOutside(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText(layer > 0 ? "Layer " + layer + " - External SF2"
                : (soundTwo ? "Sound 2 - External SF2" : "Sound 1 - External SF2"));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        EditText search = new EditText(this);
        search.setHint("Search external SoundFonts");
        search.setText(initialFilter == null ? "" : initialFilter);
        search.setTextColor(COLOR_TEXT);
        search.setHintTextColor(COLOR_DIM);
        search.setSingleLine(true);
        searchIme(search);
        search.setPadding(dp(12), dp(9), dp(12), dp(9));
        search.setBackground(panelBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        content.addView(search, topMargin(matchWrap(), 9));

        TextView status = new TextView(this);
        status.setTextColor(COLOR_MUTED);
        status.setTextSize(11);
        content.addView(status, topMargin(matchWrap(), 5));

        ShineBar progress = new ShineBar(this);
        progress.setAccent(COLOR_TEAL);
        progress.setVisibility(View.GONE);
        content.addView(progress, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(5)), 5));

        java.util.ArrayList<ExternalSf2File> filtered = new java.util.ArrayList<>();
        android.widget.ListView list = new android.widget.ListView(this);
        list.setDividerHeight(dp(5));
        list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setCacheColorHint(Color.TRANSPARENT);
        android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return filtered.size(); }
            @Override public Object getItem(int position) { return filtered.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView,
                    android.view.ViewGroup parent) {
                LinearLayout row;
                TextView name;
                TextView detail;
                if (convertView instanceof LinearLayout) {
                    row = (LinearLayout) convertView;
                    name = (TextView) row.getChildAt(0);
                    detail = (TextView) row.getChildAt(1);
                } else {
                    row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    row.setPadding(dp(12), dp(9), dp(12), dp(9));
                    name = new TextView(MainActivity.this);
                    name.setTextSize(14);
                    name.setSingleLine(true);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    row.addView(name, matchWrap());
                    detail = new TextView(MainActivity.this);
                    detail.setTextSize(10);
                    detail.setSingleLine(true);
                    detail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                    row.addView(detail, topMargin(matchWrap(), 2));
                }
                ExternalSf2File file = filtered.get(position);
                String active = layer > 0 ? externalLayerUri(layer)
                        : (soundTwo ? activeExternalDualUri : activeExternalMainUri);
                boolean selected = file.uri.equals(active);
                row.setBackground(moduleBackground(
                        selected ? darken(COLOR_TEAL) : COLOR_SURFACE_RAISED,
                        selected ? COLOR_TEAL : COLOR_BORDER, COLOR_TEAL, true));
                name.setText(file.name);
                name.setTextColor(selected ? COLOR_TEXT : COLOR_MUTED);
                detail.setText(file.relativePath + " - " + formatFileSize(file.size));
                detail.setTextColor(COLOR_DIM);
                return row;
            }
        };
        list.setAdapter(adapter);
        content.addView(list, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(350)), 8));

        final int savedScroll = layer > 0 ? externalLayerScrollPosition[layer]
                : (soundTwo ? externalDualScrollPosition : externalMainScrollPosition);
        final boolean[] initialPositionApplied = { false };
        Runnable filter = () -> {
            filtered.clear();
            filtered.addAll(matchingExternalSf2(search.getText().toString()));
            status.setText(filtered.size() + (filtered.size() == 1
                    ? " SoundFont" : " SoundFonts") + " - not preloaded");
            adapter.notifyDataSetChanged();
            if (!initialPositionApplied[0]) {
                initialPositionApplied[0] = true;
                String active = layer > 0 ? externalLayerUri(layer)
                        : (soundTwo ? activeExternalDualUri : activeExternalMainUri);
                int selectedPosition = -1;
                if (active != null) {
                    for (int i = 0; i < filtered.size(); i++) {
                        if (active.equals(filtered.get(i).uri)) {
                            selectedPosition = i;
                            break;
                        }
                    }
                }
                final int target = selectedPosition >= 0 ? selectedPosition
                        : Math.min(savedScroll, Math.max(0, filtered.size() - 1));
                list.post(() -> list.setSelection(target));
            }
        };
        filter.run();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { filter.run(); }
        });
        list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView view,
                    int scrollState) { }

            @Override public void onScroll(android.widget.AbsListView view,
                    int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (layer > 0) externalLayerScrollPosition[layer] = firstVisibleItem;
                else if (soundTwo) externalDualScrollPosition = firstVisibleItem;
                else externalMainScrollPosition = firstVisibleItem;
            }
        });

        list.setOnItemClickListener((parent, view, position, id) -> {
            ExternalSf2File file = filtered.get(position);
            list.setEnabled(false);
            search.setEnabled(false);
            progress.setVisibility(View.VISIBLE);
            status.setText("Loading " + file.name + "...");
            Sf2LoadCallback done = ok -> {
                if (ok) {
                    dialog.dismiss();
                    showExternalSf2PresetPicker(
                            file.name, file.uri, soundTwo, layer, onPicked);
                } else {
                    progress.setVisibility(View.GONE);
                    list.setEnabled(true);
                    search.setEnabled(true);
                    status.setText("Could not load " + file.name);
                }
            };
            if (layer > 0) loadExternalLayerSf2(file, layer, done);
            else loadExternalSf2(file, soundTwo, done);
        });

        dialog.setContentView(content);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.90f, 620),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showExternalSf2PresetPicker(String fileName, String fileUri,
            boolean soundTwo,
            int layer, Runnable onPicked) {
        int slot = layer > 0 ? externalLayerSlot(layer)
                : (soundTwo ? EXTERNAL_DUAL_SLOT : EXTERNAL_PIANO_SLOT);
        String[] cachedPresets = externalSf2PresetCache.get(fileUri);
        if (cachedPresets == null) {
            cachedPresets = engine.hqFontPresetNames(slot);
            if (cachedPresets != null) {
                externalSf2PresetCache.put(fileUri, cachedPresets);
            }
        }
        final String[] all = cachedPresets;
        if (all == null || all.length <= 1) {
            if (onPicked != null) onPicked.run();
            return;
        }
        final Dialog dialog = new Dialog(this);
        final boolean[] completed = { false };
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(dialogSheet());
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText(fileName);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        EditText search = new EditText(this);
        search.setHint("Search banks and presets");
        search.setSingleLine(true);
        search.setTextColor(COLOR_TEXT);
        search.setHintTextColor(COLOR_DIM);
        search.setBackground(panelBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        search.setPadding(dp(12), dp(9), dp(12), dp(9));
        searchIme(search);
        content.addView(search, topMargin(matchWrap(), 9));

        java.util.ArrayList<Integer> filtered = new java.util.ArrayList<>();
        android.widget.ListView list = new android.widget.ListView(this);
        list.setDividerHeight(dp(5));
        list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        int selectedPreset = layer > 0 ? activeExternalLayerPreset[layer]
                : (soundTwo ? activeExternalDualPreset : activeExternalMainPreset);
        android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return filtered.size(); }
            @Override public Object getItem(int position) { return filtered.get(position); }
            @Override public long getItemId(int position) { return filtered.get(position); }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = convertView instanceof TextView
                        ? (TextView) convertView : new TextView(MainActivity.this);
                int preset = filtered.get(position);
                boolean selected = preset == selectedPreset;
                row.setText(all[preset]);
                row.setTextSize(14);
                row.setTextColor(selected ? COLOR_TEXT : COLOR_MUTED);
                row.setPadding(dp(12), dp(11), dp(12), dp(11));
                row.setSingleLine(true);
                row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.setBackground(moduleBackground(
                        selected ? darken(COLOR_TEAL) : COLOR_SURFACE_RAISED,
                        selected ? COLOR_TEAL : COLOR_BORDER, COLOR_TEAL, true));
                return row;
            }
        };
        list.setAdapter(adapter);
        content.addView(list, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360)), 8));
        Runnable filter = () -> {
            String q = search.getText().toString().trim().toLowerCase(Locale.US);
            filtered.clear();
            for (int i = 0; i < all.length; i++) {
                if (q.isEmpty() || all[i].toLowerCase(Locale.US).contains(q)) {
                    filtered.add(i);
                }
            }
            adapter.notifyDataSetChanged();
        };
        filter.run();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { filter.run(); }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            int preset = filtered.get(position);
            if (!engine.setHqFontPreset(slot, preset)) return;
            engine.allNotesOff();
            if (layer > 0) {
                activeExternalLayerPreset[layer] = preset;
                prefs.edit().putInt(
                        "external_sf2_layer_" + layer + "_preset", preset).apply();
                applyLayers();
            } else if (soundTwo) {
                activeExternalDualPreset = preset;
                prefs.edit().putInt("external_sf2_dual_preset", preset).apply();
                applyDualSound();
            } else {
                activeExternalMainPreset = preset;
                prefs.edit().putInt("external_sf2_main_preset", preset).apply();
                applyPianoProgram();
            }
            completed[0] = true;
            if (onPicked != null) onPicked.run();
            dialog.dismiss();
        });
        dialog.setOnDismissListener(ignored -> {
            if (!completed[0] && onPicked != null) onPicked.run();
        });
        dialog.setContentView(content);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.90f, 620),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        int selectedPosition = filtered.indexOf(selectedPreset);
        if (selectedPosition >= 0) list.post(() -> list.setSelection(selectedPosition));
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 0) return "size unknown";
        if (bytes < 1024L * 1024L) return Math.max(1L, bytes / 1024L) + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }

    private void updateExternalSf2Status() {
        if (pianoBrowserSf2Status == null) return;
        if (externalSf2TreeUri == null) {
            pianoBrowserSf2Status.setText("No external SF2 folder");
        } else {
            int count = externalSf2Files.size();
            pianoBrowserSf2Status.setText(count + (count == 1 ? " external SoundFont" :
                    " external SoundFonts"));
        }
    }

    private void refreshExternalSf2Browser() {
        updateExternalSf2Status();
        String filter = pianoBrowserSearch == null
                ? "" : pianoBrowserSearch.getText().toString();
        if (pianoBrowserSound1List != null) {
            populatePianoSoundBrowserList(pianoBrowserSound1List, filter, false);
        }
        if (dualOn && pianoBrowserSound2List != null) {
            populatePianoSoundBrowserList(pianoBrowserSound2List, filter, true);
        }
    }

    private ExternalSf2File findExternalSf2(String uri) {
        if (uri == null) return null;
        for (ExternalSf2File file : externalSf2Files) {
            if (uri.equals(file.uri)) return file;
        }
        Uri parsed = Uri.parse(uri);
        String name = parsed.getLastPathSegment();
        if (name == null) name = "External SoundFont";
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        return new ExternalSf2File(parsed, name, "External", -1L);
    }

    private String externalSf2Name(String uri) {
        return findExternalSf2(uri).name;
    }

    private String pianoSoundName(boolean soundTwo) {
        String external = soundTwo ? activeExternalDualUri : activeExternalMainUri;
        if (external != null) {
            int slot = soundTwo ? EXTERNAL_DUAL_SLOT : EXTERNAL_PIANO_SLOT;
            int preset = soundTwo ? activeExternalDualPreset : activeExternalMainPreset;
            String[] names = externalSf2PresetCache.get(external);
            if (names == null) {
                names = engine.hqFontPresetNames(slot);
                if (names != null) externalSf2PresetCache.put(external, names);
            }
            if (names != null && preset >= 0 && preset < names.length) {
                String label = names[preset];
                int bank = label.lastIndexOf("  [B");
                return bank > 0 ? label.substring(0, bank) : label;
            }
            return externalSf2Name(external);
        }
        return soundTwo ? dualPreset.label : currentPreset.label;
    }

    private void clearExternalSf2Selection(boolean soundTwo) {
        if (soundTwo) {
            activeExternalDualUri = null;
            activeExternalDualPreset = 0;
            loadingExternalDualUri = null;
            externalDualLoadToken++;
            prefs.edit().remove("external_sf2_dual")
                    .remove("external_sf2_dual_preset").apply();
        } else {
            activeExternalMainUri = null;
            activeExternalMainPreset = 0;
            loadingExternalMainUri = null;
            externalMainLoadToken++;
            prefs.edit().remove("external_sf2_main")
                    .remove("external_sf2_main_preset").apply();
        }
    }

    private void loadExternalSf2(final ExternalSf2File file, final boolean soundTwo) {
        loadExternalSf2(file, soundTwo, null);
    }

    private void loadExternalSf2(final ExternalSf2File file, final boolean soundTwo,
            final Sf2LoadCallback callback) {
        if (file == null) {
            if (callback != null) callback.complete(false);
            return;
        }
        final String loaded = soundTwo ? loadedExternalDualUri : loadedExternalMainUri;
        if (file.uri.equals(loaded)) {
            if (soundTwo) {
                activeExternalDualUri = file.uri;
                dualOn = true;
                prefs.edit().putString("external_sf2_dual", file.uri).apply();
                applyDualSound();
            } else {
                activeExternalMainUri = file.uri;
                prefs.edit().putString("external_sf2_main", file.uri).apply();
                applyPianoProgram();
                updateSelectionStyles();
            }
            refreshExternalSf2Browser();
            if (callback != null) callback.complete(true);
            return;
        }
        String loading = soundTwo ? loadingExternalDualUri : loadingExternalMainUri;
        if (file.uri.equals(loading)) {
            if (callback != null) callback.complete(false);
            return;
        }
        final int token;
        if (soundTwo) {
            loadingExternalDualUri = file.uri;
            token = ++externalDualLoadToken;
        } else {
            loadingExternalMainUri = file.uri;
            token = ++externalMainLoadToken;
        }
        beginSoundLoad("Loading " + file.name + "...");
        final int presetIndex = file.uri.equals(soundTwo
                ? activeExternalDualUri : activeExternalMainUri)
                ? (soundTwo ? activeExternalDualPreset : activeExternalMainPreset) : 0;
        queueExternalSf2Load(0, () -> {
            int latestBeforeRead = soundTwo ? externalDualLoadToken : externalMainLoadToken;
            if (token != latestBeforeRead) {
                handler.post(() -> {
                    finishSoundLoad();
                    if (callback != null) callback.complete(false);
                });
                return;
            }
            byte[] bytes = readExternalSf2(Uri.parse(file.uri), file.size);
            int slot = soundTwo ? EXTERNAL_DUAL_SLOT : EXTERNAL_PIANO_SLOT;
            final boolean ok = bytes != null
                    && engine.loadHqFont(slot, presetIndex, EXTERNAL_SF2_GAIN_DB, bytes);
            handler.post(() -> {
                finishSoundLoad();
                int latest = soundTwo ? externalDualLoadToken : externalMainLoadToken;
                if (token != latest) {
                    if (callback != null) callback.complete(false);
                    return;
                }
                if (soundTwo) loadingExternalDualUri = null;
                else loadingExternalMainUri = null;
                if (!ok) {
                    Toast.makeText(this,
                            file.size > 256L * 1024L * 1024L
                                    ? "SF2 is larger than the 256 MB limit"
                                    : "Could not load " + file.name,
                            Toast.LENGTH_LONG).show();
                    if (callback != null) callback.complete(false);
                    return;
                }
                String[] presetNames = engine.hqFontPresetNames(slot);
                if (presetNames != null) {
                    externalSf2PresetCache.put(file.uri, presetNames);
                }
                engine.allNotesOff();
                if (soundTwo) {
                    loadedExternalDualUri = file.uri;
                    activeExternalDualUri = file.uri;
                    activeExternalDualPreset = presetIndex;
                    dualOn = true;
                    prefs.edit().putString("external_sf2_dual", file.uri)
                            .putInt("external_sf2_dual_preset", presetIndex).apply();
                    applyDualSound();
                } else {
                    loadedExternalMainUri = file.uri;
                    activeExternalMainUri = file.uri;
                    activeExternalMainPreset = presetIndex;
                    prefs.edit().putString("external_sf2_main", file.uri)
                            .putInt("external_sf2_main_preset", presetIndex).apply();
                    applyPianoProgram();
                    updateSelectionStyles();
                }
                refreshExternalSf2Browser();
                if (callback != null) callback.complete(true);
            });
        });
    }

    private void loadExternalLayerSf2(final ExternalSf2File file, final int layer,
            final Sf2LoadCallback callback) {
        int slot = externalLayerSlot(layer);
        int channel = layer >= 0 && layer < LAYER_CH.length ? LAYER_CH[layer] : -1;
        if (file == null || slot < 0 || channel < 0) {
            if (callback != null) callback.complete(false);
            return;
        }
        if (file.uri.equals(loadedExternalLayerUri[layer])) {
            setLayerPreset(layer, EXTERNAL_LAYER_PREFIX + file.uri);
            engine.setLayerFontSlot(channel, slot);
            ensureLayerAudible(layer);
            saveLayers();
            applyLayers();
            if (callback != null) callback.complete(true);
            return;
        }
        if (file.uri.equals(loadingExternalLayerUri[layer])) {
            if (callback != null) callback.complete(false);
            return;
        }

        loadingExternalLayerUri[layer] = file.uri;
        final int token = ++externalLayerLoadToken[layer];
        final int presetIndex = file.uri.equals(externalLayerUri(layer))
                ? activeExternalLayerPreset[layer] : 0;
        beginSoundLoad("Loading Layer " + layer + " - " + file.name + "...");
        queueExternalSf2Load(callback == null ? 2 : 1, () -> {
            if (token != externalLayerLoadToken[layer]) {
                handler.post(() -> {
                    finishSoundLoad();
                    if (callback != null) callback.complete(false);
                });
                return;
            }
            byte[] bytes = readExternalSf2(Uri.parse(file.uri), file.size);
            final boolean ok = bytes != null
                    && engine.loadHqFont(slot, presetIndex, EXTERNAL_SF2_GAIN_DB, bytes);
            handler.post(() -> {
                finishSoundLoad();
                if (token != externalLayerLoadToken[layer]) {
                    if (callback != null) callback.complete(false);
                    return;
                }
                loadingExternalLayerUri[layer] = null;
                if (!ok) {
                    Toast.makeText(this,
                            file.size > 256L * 1024L * 1024L
                                    ? "SF2 is larger than the 256 MB limit"
                                    : "Could not load " + file.name,
                            Toast.LENGTH_LONG).show();
                    if (callback != null) callback.complete(false);
                    return;
                }
                engine.allNotesOff();
                loadedExternalLayerUri[layer] = file.uri;
                String[] presetNames = engine.hqFontPresetNames(slot);
                if (presetNames != null) {
                    externalSf2PresetCache.put(file.uri, presetNames);
                }
                activeExternalLayerPreset[layer] = presetIndex;
                setLayerPreset(layer, EXTERNAL_LAYER_PREFIX + file.uri);
                engine.setLayerFontSlot(channel, slot);
                ensureLayerAudible(layer);
                saveLayers();
                prefs.edit().putInt(
                        "external_sf2_layer_" + layer + "_preset", presetIndex).apply();
                applyLayers();
                if (callback != null) callback.complete(true);
            });
        });
    }

    private void applyExternalLayerFont(int layer, boolean enabled) {
        int channel = LAYER_CH[layer];
        String uri = externalLayerUri(layer);
        if (uri == null) {
            engine.setLayerFontSlot(channel, -1);
            return;
        }
        if (uri.equals(loadedExternalLayerUri[layer])) {
            engine.setHqFontPreset(externalLayerSlot(layer),
                    activeExternalLayerPreset[layer]);
            engine.setLayerFontSlot(channel, externalLayerSlot(layer));
            return;
        }
        if (!enabled) return;
        engine.setLayerFontSlot(channel, -1);
        if (!uri.equals(loadingExternalLayerUri[layer])) {
            loadExternalLayerSf2(findExternalSf2(uri), layer, null);
        }
    }

    private static int reggaeKitSourceNote(int note) {
        switch (note) {
            case 50: case 48: return 61;
            case 47: return 63;
            case 45: return 64;
            case 43: case 41: return 65;
            default: return note;
        }
    }

    private static int drumFontSlot(int kitProgram) {
        int kit = kitProgram >= 10000 ? kitProgram - 10000 : kitProgram;
        return kit >= 1000 ? (kit - 1000) / 100 : -1;
    }

    private static String extraDrumAsset(int slot) {
        switch (slot) {
            case 11: return "drums_giant_studio.sf2";
            case 12: return "drums_hard_rock_classic.sf2";
            case 13: return "drums_hard_rock_v3.sf2";
            case 14: return "drums_melotti_studio.sf2";
            case 15: return "drums_real_acoustic_5.sf2";
            case 16: return "drums_roland_canvas.sf2";
            case 17: return "drums_charlie_standard.sf2";
            case 18: return "drums_tama_rockstar_classic.sf2";
            case 19: return "drums_tama_rockstar_2.sf2";
            case 20: return "drums_ultimate_cm.sf2";
            case 21: return "drums_congas.sf2";    // MEINL congas/tumbas (Latin)
            case 22: return "drums_reggae.sf2";    // ColomboGMGS2 Reggae kit (real samples)
            case 23: return "drums_beatbox.sf2";   // ColomboGMGS2 CR-78 drum machine
            case 24: return "drums_jungle.sf2";    // ColomboGMGS2 Jungle kit
            case 25: return "drums_trap.sf2";      // ColomboGMGS2 Trap kit
            case 26: return "drums_reggaeton.sf2"; // ColomboGMGS2 Reggaeton kit
            case 27: return "drums_tr707.sf2";     // ColomboGMGS2 drum machines
            case 28: return "drums_tr606.sf2";
            case 29: return "drums_simmons.sf2";
            case 30: return "drums_electro.sf2";
            case 31: return "drums_djent.sf2";
            case 32: return "drums_dance.sf2";
            case 33: return "drums_slam.sf2";
            case 34: return "drums_snes.sf2";
            case 35: return "drums_funk2.sf2";     // ColomboGMGS2 acoustic kits
            case 36: return "drums_pop2.sf2";
            case 37: return "drums_metal3.sf2";
            case 38: return "drums_jazz2.sf2";
            case 39: return "drums_brush2.sf2";
            // Sample-library category fonts (per-piece custom kit). Each sample
            // sits on note 36+i; see DrumSampleLib.
            case 40: return "drumlib_kick.sf2";
            case 41: return "drumlib_snare.sf2";
            case 42: return "drumlib_toms.sf2";
            case 43: return "drumlib_cym.sf2";
            case 44: return "drumlib_clap.sf2";
            case 45: return "drumlib_perc.sf2";
            default: return null;
        }
    }

    private static float extraDrumGainDb(int slot) {
        switch (slot) {
            case 11: return -11.0f;
            case 12: return -10.5f;
            case 13: return -10.0f;
            case 14: return -9.5f;
            case 15: return -8.5f;
            case 16: return -7.5f;
            case 17: return -10.5f;
            case 18: return -10.0f;
            case 19: return -10.0f;
            case 20: return -9.0f;
            case 21: return -6.0f;    // congas
            case 22: return -11.0f;   // reggae (hot samples, peak ~2.6)
            case 23: return -10.0f;   // beatbox / CR-78 machine (peak ~2.2)
            case 24: return -10.0f;   // jungle
            case 25: return -10.0f;   // trap
            case 26: return -10.0f;   // reggaeton
            case 27: return -10.0f;   // tr-707
            case 28: return -10.0f;   // tr-606
            case 29: return -10.0f;   // simmons
            case 30: return -10.0f;   // electro
            case 31: return -10.0f;   // djent
            case 32: return -10.0f;   // dance
            case 33: return -10.0f;   // slam
            case 34: return -10.0f;   // snes
            case 35: return -10.0f;   // funk studio
            case 36: return -10.0f;   // pop studio
            case 37: return -10.0f;   // metal studio
            case 38: return -10.0f;   // jazz club
            case 39: return -10.0f;   // brush studio
            case 40: return -2.8f;    // sample lib: kick  (measured peak ~1.04)
            case 41: return -2.7f;    // sample lib: snare (~1.02)
            case 42: return -2.5f;    // sample lib: toms  (~1.00)
            case 43: return -3.1f;    // sample lib: cymbals (~1.07)
            case 44: return -2.7f;    // sample lib: claps (~1.03)
            case 45: return -4.1f;    // sample lib: percussion (~1.21)
            default: return -9.0f;
        }
    }

    // Long-press a cymbal pad → its volume. The slam-velocity (ride↔crash)
    // settings live on the MIDI Assignment screen instead, so adjusting them
    // never means holding a pad and interrupting play.
    private void cymbalVolumeSlider(int group) {
        final String name = group == 0 ? "Hi-Hat" : group == 1 ? "Ride" : "Crash";
        float cur = group == 0 ? cymGainHat : group == 1 ? cymGainRide : cymGainCrash;
        int p0 = Math.round((cur - 0.4f) / 2.0f * 100f);
        levelDialog(name + " volume", 100, Math.max(0, Math.min(100, p0)), p -> {
            float g = 0.4f + p / 100f * 2.0f;
            if (group == 0) { cymGainHat = g; prefs.edit().putFloat("cym_gain_hat", g).apply(); }
            else if (group == 1) { cymGainRide = g; prefs.edit().putFloat("cym_gain_ride", g).apply(); }
            else { cymGainCrash = g; prefs.edit().putFloat("cym_gain_crash", g).apply(); }
            engine.setCymbalGain(group, g);
            return String.format(Locale.US, "×%.2f", g);
        });
    }

    private boolean isChokeCymbal(int gm) {
        return gm == 49 || gm == 51 || gm == 52 || gm == 53
                || gm == 55 || gm == 57 || gm == 59;
    }

    // Labels for the slam-velocity sliders on the MIDI Assignment screen.
    private String cymChokeLabel() {
        return "Cymbal choke (soft hit):  "
                + (cymChokeVel <= 0.005f ? "off"
                        : "below velocity " + Math.round(cymChokeVel * 127));
    }

    private String rideCrashLabel() {
        int p = Math.round((rideCrashVel - 0.5f) / 0.5f * 100f);
        return "Ride → Crash slam:  "
                + (p >= 99 ? "never" : "velocity " + Math.round(rideCrashVel * 127) + "+");
    }

    private String crashRideLabel() {
        return "Crash → Ride (soft):  "
                + (crashRideVel <= 0.005f ? "off (always crash)"
                        : "below velocity " + Math.round(crashRideVel * 127));
    }

    private void onDrumPad(int note, float velocity) {
        if (note == 84) {   // Kit Mode "Chimes" piece → the chimes.wav one-shot
            engine.triggerChimes();
            return;
        }
        if (drumKitView != null) {
            int swell = drumKitView.swellForTrigger(note);
            if (swell >= 0) {
                engine.triggerSwell(swell);
                return;
            }
        }
        engine.noteOn(note, velocity);
    }

    // One-shot bar-chime / mark-tree cascade. Tap plays the ~9s shimmer sweep;
    // it can't be retriggered while it's still ringing (native ignores the tap).
    private View buildChimesButton() {
        TextView b = new TextView(this);
        b.setText("🎐  Chimes");
        b.setTextSize(13);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(14), dp(9), dp(14), dp(9));
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setTextColor(COLOR_TEXT);
        b.setBackground(pillBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        b.setOnClickListener(v -> {
            if (!engine.isRunning()) {
                Toast.makeText(this, "Start the engine first", Toast.LENGTH_SHORT).show();
                return;
            }
            engine.triggerChimes();
        });
        return b;
    }

    // Toggle (below the pads) switching the snare pad between Snare and Rim Shot.
    private View buildSnareRimToggle() {
        snareRimToggle = new TextView(this);
        snareRimToggle.setTextSize(13);
        snareRimToggle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        snareRimToggle.setPadding(dp(14), dp(9), dp(14), dp(9));
        snareRimToggle.setGravity(Gravity.CENTER);
        snareRimToggle.setClickable(true);
        snareRimToggle.setOnClickListener(v -> {
            padSnareRim = !padSnareRim;
            if (drumPadsView != null) {
                drumPadsView.setSnareRim(padSnareRim);
            }
            saveDrumAssignments();
            updateSnareRimToggle();
        });
        updateSnareRimToggle();
        return snareRimToggle;
    }

    private void updateSnareRimToggle() {
        if (snareRimToggle == null) {
            return;
        }
        snareRimToggle.setText(padSnareRim ? "Snare pad  ▸  Rim Shot" : "Snare pad  ▸  Snare");
        snareRimToggle.setTextColor(padSnareRim ? COLOR_GREEN : COLOR_TEXT);
        snareRimToggle.setBackground(pillBackground(COLOR_SURFACE_RAISED,
                padSnareRim ? COLOR_GREEN : COLOR_BORDER));
    }

    // ---- Drum MIDI assignment model ----

    private static final class DrumPiece {
        final String name;
        final String badge;
        final int gmNote;   // note the soundfont plays for this piece
        int inNote;         // incoming MIDI note that triggers it (default = gmNote)
        int channel;        // 1..16 (used when ALL CHANNELS is off)
        int kitSlot;        // custom-kit: which drum font this piece sounds from
        int sourceNote = -1; // WAV-library sample note; -1 uses this piece's GM note
        float level = 1.0f; // custom-kit: per-piece level trim (0..1.4)
        float pan = 0.5f;   // custom-kit: per-piece pan (0=L, 0.5=C, 1=R)

        DrumPiece(String name, String badge, int gmNote) {
            this.name = name;
            this.badge = badge;
            this.gmNote = gmNote;
            this.inNote = gmNote;
            this.channel = 10;
            this.kitSlot = 6;   // Rock (Tama) by default
        }
    }

    // Legacy source list retained for Kit Mode's individual sample chooser.
    // MIDI Assignment itself uses the complete TonePreset drum-kit catalog.
    //   0..11      = HQ drum-font slot (clean)
    //   200+slot   = HQ slot with metal drive
    //   100+i      = GM percussion kit i (Std/Room/Power/Elec/Jazz/Brush)
    private static final int[] CUSTOM_KIT_SLOTS = {
            6, 2, 1, 0, 3, 4, 5, 7, 8, 9, 10,
            206, 207,
            100, 101, 102, 103, 104, 105};
    private static final String[] CUSTOM_KIT_NAMES = {
            "Rock (Tama)", "DJ / House (909)", "808", "Studio", "Natural",
            "Funk", "Lo-Fi", "JD Rock", "LinnDrum", "Roland R8", "Techno",
            "Metal (Tama)", "Metal (JD Rock)",
            "GM Standard", "GM Room", "GM Power", "GM Electronic", "GM Jazz", "GM Brush"};

    private String panText(float pan) {
        int off = Math.round((pan - 0.5f) * 200);   // -100 (L) .. +100 (R)
        if (off == 0) return "Center";
        return (off < 0 ? "L" : "R") + Math.abs(off);
    }

    private String kitNameForSlot(int slot) {
        for (TonePreset preset : TonePreset.forMode(InstrumentMode.DRUMS)) {
            if (pieceCodeForProgram(preset.program) == slot) {
                return preset.label;
            }
        }
        for (int i = 0; i < CUSTOM_KIT_SLOTS.length; i++) {
            if (CUSTOM_KIT_SLOTS[i] == slot) {
                return CUSTOM_KIT_NAMES[i];
            }
        }
        return "Rock (Tama)";
    }

    private DrumPiece drumPieceForNote(int note) {
        if (drumPieces == null) return null;
        for (DrumPiece piece : drumPieces) {
            if (piece.gmNote == note) return piece;
        }
        return null;
    }

    private void initDrumPieces() {
        drumPieces = new DrumPiece[]{
                new DrumPiece("Kick", "KICK", 36),
                new DrumPiece("Acoustic Kick", "KICK", 35),
                new DrumPiece("Snare", "SNR", 38),
                new DrumPiece("Rim Shot", "RS", 40),
                new DrumPiece("Side Stick", "RIM", 37),
                new DrumPiece("Hand Clap", "CLAP", 39),
                new DrumPiece("Closed Hat", "CH", 42),
                new DrumPiece("Pedal Hat", "PH", 44),
                new DrumPiece("Open Hat", "OH", 46),
                new DrumPiece("Crash", "CR", 49),
                new DrumPiece("Crash 2", "CR2", 57),
                new DrumPiece("Ride", "RD", 51),
                new DrumPiece("Ride Bell", "BELL", 53),
                new DrumPiece("Splash", "SPL", 55),
                new DrumPiece("China", "CHN", 52),
                new DrumPiece("Tom 1 (High)", "T1", 50),
                new DrumPiece("Tom 2 (Mid)", "T2", 47),
                new DrumPiece("Tom 3 (Floor)", "T3", 43),
                new DrumPiece("Tambourine", "TAMB", 54),
                new DrumPiece("Cowbell", "COW", 56),
                new DrumPiece("Chimes", "CHM", CHIMES_MIDI_NOTE),
                new DrumPiece("Swell Cymbal", "SWL", SWELL_FIRST_MIDI_NOTE),
        };
        drumAllChannels = prefs.getBoolean("drum_all_ch", true);
        drumMidiIn = prefs.getBoolean("drum_midi_in", true);
        drumCustomKit = prefs.getBoolean("drum_custom", false);
        drumRoomLevel = prefs.getFloat("drum_room", 0.12f);
        padSnareRim = prefs.getBoolean("pad_snare_rim", false);
        cymGainHat = prefs.getFloat("cym_gain_hat", 1.15f);
        cymGainRide = prefs.getFloat("cym_gain_ride", 1.40f);
        cymGainCrash = prefs.getFloat("cym_gain_crash", 1.30f);
        rideCrashVel = prefs.getFloat("cym_ride_crash_vel", 0.92f);
        crashRideVel = prefs.getFloat("cym_crash_ride_vel", 0.35f);
        cymChokeVel = prefs.getFloat("cym_choke_vel", 0.0f);
        drumSwellVariant = Math.max(0, Math.min(5,
                prefs.getInt("drum_swell_variant", 0)));
        for (DrumPiece p : drumPieces) {
            p.inNote = prefs.getInt("drum_in_" + p.gmNote, p.gmNote);
            p.channel = prefs.getInt("drum_ch_" + p.gmNote, 10);
            p.kitSlot = prefs.getInt("drum_kit_" + p.gmNote, 6);
            p.sourceNote = prefs.getInt("drum_src_" + p.gmNote, -1);
            p.level = prefs.getFloat("drum_gain_" + p.gmNote, 1.0f);
            p.pan = prefs.getFloat("drum_pan_" + p.gmNote, 0.5f);
        }
    }

    private void saveDrumAssignments() {
        SharedPreferences.Editor e = prefs.edit();
        e.putBoolean("drum_all_ch", drumAllChannels);
        e.putBoolean("drum_midi_in", drumMidiIn);
        e.putBoolean("drum_custom", drumCustomKit);
        e.putFloat("drum_room", drumRoomLevel);
        e.putBoolean("pad_snare_rim", padSnareRim);
        e.putInt("drum_swell_variant", drumSwellVariant);
        for (DrumPiece p : drumPieces) {
            e.putInt("drum_in_" + p.gmNote, p.inNote);
            e.putInt("drum_ch_" + p.gmNote, p.channel);
            e.putInt("drum_kit_" + p.gmNote, p.kitSlot);
            e.putInt("drum_src_" + p.gmNote, p.sourceNote);
            e.putFloat("drum_gain_" + p.gmNote, p.level);
            e.putFloat("drum_pan_" + p.gmNote, p.pan);
        }
        e.apply();
    }

    private void resetDrumAssignments() {
        drumAllChannels = true;
        drumSwellVariant = 0;
        for (DrumPiece p : drumPieces) {
            p.inNote = p.gmNote;
            p.channel = 10;
            p.sourceNote = -1;
        }
        saveDrumAssignments();
    }

    // Map an incoming MIDI note (channel 0-15) to a soundfont drum note, or -1 if filtered out.
    private int mapDrumNote(int note, int channel0) {
        if (!drumMidiIn) {
            return -1;
        }
        boolean channelOk = drumAllChannels;
        if (!channelOk) {
            for (DrumPiece p : drumPieces) {
                if (p.channel - 1 == channel0) {
                    channelOk = true;
                    break;
                }
            }
        }
        if (!channelOk) {
            return -1;
        }
        for (DrumPiece p : drumPieces) {
            if (p.inNote == note && (drumAllChannels || p.channel - 1 == channel0)) {
                return coerceSnare(p.gmNote);
            }
        }
        return coerceSnare(note);
    }

    // Snare and rim shot are ONE voice over MIDI: the pad's Snare/Rim toggle
    // decides which sound every snare-family hit (side stick 37, snare 38,
    // electric snare 40) plays — rim when the toggle is on, snare when off.
    private int coerceSnare(int gm) {
        if (gm == 37 || gm == 38 || gm == 40) {
            return padSnareRim ? 37 : 38;
        }
        return gm;
    }

    // A hard hit on the ride (velocity ≥ threshold) fires Crash 2 instead —
    // the on-screen pad and MIDI share one rule. 0.85 matches the pad.
    private int coerceRide(int gm, float vel) {
        if (gm == 51 && vel >= rideCrashVel) {
            return 57;
        }
        return gm;
    }

    // Only a genuinely soft hit on Crash 1 rides — a unique second ride
    // (Ride 2). Normal-to-hard hits stay a real crash.
    private int coerceCrash(int gm, float vel) {
        if (gm == 49 && vel < crashRideVel) {
            return 59;
        }
        return gm;
    }

    // ---- MIDI Assignment screen ----

    private void showMidiAssignment() {
        onMidiAssignScreen = true;
        armedPieceIndex = -1;
        assignNoteField = null;

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(24));
        addRootContent(scrollView, root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(backArrowButton(this::closeMidiAssignment),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView title = new TextView(this);
        title.setText("MIDI Assignment");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(21);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(8);
        header.addView(title, titleLp);
        header.addView(iconButton("↺", () -> {
            resetDrumAssignments();
            showMidiAssignment();
        }), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(header, matchWrap());

        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.HORIZONTAL);
        opts.setGravity(Gravity.CENTER_VERTICAL);
        opts.addView(checkRow("ALL CHANNELS", drumAllChannels, () -> {
            drumAllChannels = !drumAllChannels;
            saveDrumAssignments();
            showMidiAssignment();
        }), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        opts.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        opts.addView(checkRow("MIDI IN", drumMidiIn, () -> {
            drumMidiIn = !drumMidiIn;
            saveDrumAssignments();
            showMidiAssignment();
        }), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(opts, topMargin(matchWrap(), 22));

        root.addView(checkRow("CUSTOM KIT  ·  mix a kit per piece", drumCustomKit, () -> {
            drumCustomKit = !drumCustomKit;
            saveDrumAssignments();
            applyDrumKit();
            showMidiAssignment();
        }), topMargin(matchWrap(), 14));

        final TextView roomLabel = new TextView(this);
        roomLabel.setTextColor(COLOR_MUTED);
        roomLabel.setTextSize(13);
        roomLabel.setText("Room  " + Math.round(drumRoomLevel / 0.5f * 100) + "%");
        root.addView(roomLabel, topMargin(matchWrap(), 14));
        SeekBar room = new SeekBar(this);
        room.setMax(100);
        room.setProgress(Math.round(drumRoomLevel / 0.5f * 100));
        room.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                drumRoomLevel = prog / 100f * 0.5f;
                roomLabel.setText("Room  " + prog + "%");
                engine.setDrumRoom(drumRoomLevel);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {
                saveDrumAssignments();
            }
        });
        root.addView(room, topMargin(matchWrap(), 4));

        // Cymbal slam velocity (MIDI): lives here, not on a pad long-press, so
        // tuning it never interrupts on-screen playing.
        TextView slamHead = new TextView(this);
        slamHead.setText("CYMBAL SLAM  ·  MIDI velocity");
        slamHead.setTextColor(COLOR_AMBER);
        slamHead.setTextSize(11);
        slamHead.setLetterSpacing(0.08f);
        slamHead.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(slamHead, topMargin(matchWrap(), 18));

        final TextView rideLabel = new TextView(this);
        rideLabel.setTextColor(COLOR_MUTED);
        rideLabel.setTextSize(13);
        rideLabel.setText(rideCrashLabel());
        root.addView(rideLabel, topMargin(matchWrap(), 10));
        SeekBar rideSlam = new SeekBar(this);
        rideSlam.setMax(100);
        rideSlam.setProgress(Math.round((rideCrashVel - 0.5f) / 0.5f * 100f));
        rideSlam.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                rideCrashVel = 0.5f + prog / 100f * 0.5f;
                rideLabel.setText(rideCrashLabel());
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {
                prefs.edit().putFloat("cym_ride_crash_vel", rideCrashVel).apply();
            }
        });
        root.addView(rideSlam, topMargin(matchWrap(), 4));

        final TextView crashLabel = new TextView(this);
        crashLabel.setTextColor(COLOR_MUTED);
        crashLabel.setTextSize(13);
        crashLabel.setText(crashRideLabel());
        root.addView(crashLabel, topMargin(matchWrap(), 10));
        SeekBar crashSlam = new SeekBar(this);
        crashSlam.setMax(100);
        crashSlam.setProgress(Math.round(crashRideVel / 0.6f * 100f));
        crashSlam.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                crashRideVel = prog / 100f * 0.6f;
                crashLabel.setText(crashRideLabel());
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {
                prefs.edit().putFloat("cym_crash_ride_vel", crashRideVel).apply();
            }
        });
        root.addView(crashSlam, topMargin(matchWrap(), 4));

        final TextView chokeLabel = new TextView(this);
        chokeLabel.setTextColor(COLOR_MUTED);
        chokeLabel.setTextSize(13);
        chokeLabel.setText(cymChokeLabel());
        root.addView(chokeLabel, topMargin(matchWrap(), 10));
        SeekBar chokeSlam = new SeekBar(this);
        chokeSlam.setMax(100);
        chokeSlam.setProgress(Math.round(cymChokeVel / 0.4f * 100f));
        chokeSlam.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                cymChokeVel = prog / 100f * 0.4f;   // 0 .. 0.40 (velocity ~51)
                chokeLabel.setText(cymChokeLabel());
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {
                prefs.edit().putFloat("cym_choke_vel", cymChokeVel).apply();
            }
        });
        root.addView(chokeSlam, topMargin(matchWrap(), 4));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView listening = new TextView(this);
        listening.setText("Listening…");
        listening.setTextColor(COLOR_TEXT);
        listening.setTextSize(15);
        listening.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusRow.addView(listening, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        midiAssignStatus = new TextView(this);
        midiAssignStatus.setText(midiStatusLabel());
        midiAssignStatus.setTextColor(midiInputAvailable ? COLOR_GREEN : COLOR_MUTED);
        midiAssignStatus.setTextSize(14);
        midiAssignStatus.setGravity(Gravity.END);
        statusRow.addView(midiAssignStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(statusRow, topMargin(matchWrap(), 18));

        midiAssignList = new LinearLayout(this);
        midiAssignList.setOrientation(LinearLayout.VERTICAL);
        root.addView(midiAssignList, topMargin(matchWrap(), 12));
        populateAssignList();

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        enablePadInsets(screen, root);
        paintStage(screen);
        setContentView(screen);
    }

    private void closeMidiAssignment() {
        onMidiAssignScreen = false;
        armedPieceIndex = -1;
        assignNoteField = null;
        midiAssignStatus = null;
        midiAssignList = null;
        showInstrumentScreen();
    }

    private void populateAssignList() {
        if (midiAssignList == null) {
            return;
        }
        midiAssignList.removeAllViews();
        for (int i = 0; i < drumPieces.length; i++) {
            midiAssignList.addView(assignRow(i), topMargin(matchWrap(), 10));
        }
    }

    private View assignRow(final int index) {
        final DrumPiece p = drumPieces[index];
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(moduleBackground(COLOR_SURFACE, COLOR_BORDER, COLOR_GREEN, true));

        row.addView(drumBadge(p), new LinearLayout.LayoutParams(dp(46), dp(46)));

        TextView name = new TextView(this);
        name.setText(currentPreset.label + " — " + p.name);
        name.setTextColor(COLOR_TEXT);
        name.setTextSize(16);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.leftMargin = dp(14);
        row.addView(name, nameLp);

        TextView test = iconButton("▶", () -> previewAssignedDrumPiece(p));
        test.setContentDescription("Test " + p.name);
        row.addView(test, new LinearLayout.LayoutParams(dp(42), dp(42)));

        TextView note = assignChip(p.inNote + "   ✎", false);
        note.setOnClickListener(v -> beginAssign(index));
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(dp(82),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        noteLp.leftMargin = dp(8);
        row.addView(note, noteLp);

        if (p.gmNote == SWELL_FIRST_MIDI_NOTE) {
            TextView variant = assignChip("Swell " + (drumSwellVariant + 1), false);
            variant.setOnClickListener(v -> swellVariantDialog());
            LinearLayout.LayoutParams variantLp = new LinearLayout.LayoutParams(dp(132),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            variantLp.leftMargin = dp(8);
            row.addView(variant, variantLp);
            if (!drumAllChannels) {
                TextView chan = assignChip("Ch " + p.channel, false);
                chan.setOnClickListener(v -> channelDialog(p));
                LinearLayout.LayoutParams chanLp = new LinearLayout.LayoutParams(dp(78),
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                chanLp.leftMargin = dp(8);
                row.addView(chan, chanLp);
            }
        } else if (drumCustomKit) {
            // In custom mode the right chip picks which kit this piece sounds from.
            String kitLabel = kitNameForPiece(p);
            if (Math.round(p.level * 100) != 100) {
                kitLabel += "  ·  " + Math.round(p.level * 100) + "%";
            }
            boolean directWav = p.gmNote >= CHIMES_MIDI_NOTE && p.gmNote < SWELL_FIRST_MIDI_NOTE + 4;
            TextView kit = assignChip(kitLabel, directWav);
            if (!directWav) kit.setOnClickListener(v -> kitDialog(p));
            LinearLayout.LayoutParams kitLp = new LinearLayout.LayoutParams(dp(150),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            kitLp.leftMargin = dp(8);
            row.addView(kit, kitLp);
        } else {
            TextView chan = assignChip(drumAllChannels ? "All ch" : "Channel " + p.channel, drumAllChannels);
            if (!drumAllChannels) {
                chan.setOnClickListener(v -> channelDialog(p));
            }
            LinearLayout.LayoutParams chanLp = new LinearLayout.LayoutParams(dp(116),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            chanLp.leftMargin = dp(8);
            row.addView(chan, chanLp);
        }
        return row;
    }

    private void kitDialog(final DrumPiece p) {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(12));
        TextView title = new TextView(this);
        title.setText("Kit for " + p.name);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        final EditText search = new EditText(this);
        searchIme(search);
        search.setHint("Search kits");
        search.setHintTextColor(COLOR_DIM);
        search.setTextColor(COLOR_TEXT);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        search.setBackground(panelBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        content.addView(search, topMargin(matchWrap(), 10));

        ScrollView scroll = new ScrollView(this);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        populateCustomKitList(list, "", p, dialog);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                populateCustomKitList(list, s.toString(), p, dialog);
            }
        });
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min((int) (getResources().getDisplayMetrics().heightPixels * 0.40f), dp(300))), 12));

        final TextView levelLabel = new TextView(this);
        levelLabel.setTextColor(COLOR_MUTED);
        levelLabel.setTextSize(13);
        levelLabel.setText("Level  " + Math.round(p.level * 100) + "%");
        content.addView(levelLabel, topMargin(matchWrap(), 14));

        SeekBar level = new SeekBar(this);
        level.setMax(140);
        level.setProgress(Math.round(p.level * 100));
        level.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                p.level = prog / 100f;
                levelLabel.setText("Level  " + prog + "%");
                engine.setDrumPieceGain(p.gmNote, p.level);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {
                saveDrumAssignments();
                populateAssignList();
            }
        });
        content.addView(level, topMargin(matchWrap(), 4));

        final TextView panLabel = new TextView(this);
        panLabel.setTextColor(COLOR_MUTED);
        panLabel.setTextSize(13);
        panLabel.setText("Pan  " + panText(p.pan));
        content.addView(panLabel, topMargin(matchWrap(), 12));

        SeekBar pan = new SeekBar(this);
        pan.setMax(100);
        pan.setProgress(Math.round(p.pan * 100));
        pan.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                p.pan = prog / 100f;
                panLabel.setText("Pan  " + panText(p.pan));
                engine.setDrumPiecePan(p.gmNote, p.pan);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {
                saveDrumAssignments();
            }
        });
        content.addView(pan, topMargin(matchWrap(), 4));

        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.8f, 460), LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    // Mirrors the full Drum Kit picker so Custom Kit assignments never fall
    // behind the main drum catalog as new SoundFonts are added.
    private View midiKitChoiceRow(String label, boolean selected,
                                  Runnable onPreview, Runnable onSelect) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(8), dp(8));
        row.setClickable(true);
        row.setBackground(moduleBackground(
                selected ? COLOR_SKY_CONTROL_STRONG : COLOR_SURFACE_RAISED,
                selected ? COLOR_GREEN : COLOR_BORDER, COLOR_GREEN, true));
        row.setOnClickListener(v -> onPreview.run());

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(selected ? COLOR_GREEN : COLOR_TEXT);
        name.setTextSize(15);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView play = iconButton("▶", onPreview);
        play.setContentDescription("Preview " + label);
        row.addView(play, new LinearLayout.LayoutParams(dp(42), dp(42)));

        TextView use = iconButton(selected ? "✓" : "+", onSelect);
        use.setTextColor(selected ? COLOR_GREEN : COLOR_TEXT);
        use.setContentDescription("Select " + label);
        LinearLayout.LayoutParams useLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        useLp.leftMargin = dp(4);
        row.addView(use, useLp);
        return row;
    }

    private void swellVariantDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));
        TextView title = new TextView(this);
        title.setText("Swell Cymbal Sound");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());
        for (int i = 0; i < 6; ++i) {
            final int variant = i;
            Runnable preview = () -> {
                if (engine.isRunning()) engine.triggerSwell(variant);
                else Toast.makeText(this, "Start the engine first",
                        Toast.LENGTH_SHORT).show();
            };
            Runnable select = () -> {
                drumSwellVariant = variant;
                saveDrumAssignments();
                populateAssignList();
                dialog.dismiss();
            };
            content.addView(midiKitChoiceRow("Swell Cymbal " + (i + 1),
                    drumSwellVariant == i, preview, select),
                    topMargin(matchWrap(), i == 0 ? 12 : 8));
        }
        presentMenu(dialog, content, dialogWidth(0.78f, 440));
    }

    private void previewAssignedDrumPiece(DrumPiece piece) {
        if (!engine.isRunning()) {
            Toast.makeText(this, "Start the engine first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (piece.gmNote == CHIMES_MIDI_NOTE) {
            engine.triggerChimes();
        } else if (piece.gmNote == SWELL_FIRST_MIDI_NOTE) {
            engine.triggerSwell(drumSwellVariant);
        } else if (drumCustomKit) {
            previewDrumChoice(piece.kitSlot,
                    piece.sourceNote >= 0 ? piece.sourceNote : piece.gmNote);
        } else {
            engine.noteOn(piece.gmNote, 0.85f);
        }
    }

    private void previewDrumChoice(int code, int note) {
        if (!engine.isRunning()) {
            Toast.makeText(this, "Start the engine first", Toast.LENGTH_SHORT).show();
            return;
        }
        int slot = code >= 200 ? code - 200 : (code < 100 ? code : -1);
        if (slot >= FIRST_EXTRA_DRUM_SLOT && slot < TOTAL_DRUM_FONT_SLOTS) {
            synchronized (extraDrumFontLoading) {
                if (extraDrumFontLoaded[slot]) {
                    engine.previewDrum(code, note);
                    return;
                }
            }
            ensureDrumSlotForCode(code);
            Toast.makeText(this, "Loading sound for preview…", Toast.LENGTH_SHORT).show();
            waitForDrumPreview(code, note, slot, 0);
            return;
        }
        engine.previewDrum(code, note);
    }

    private void waitForDrumPreview(int code, int note, int slot, int attempt) {
        handler.postDelayed(() -> {
            boolean loaded;
            boolean loading;
            synchronized (extraDrumFontLoading) {
                loaded = extraDrumFontLoaded[slot];
                loading = extraDrumFontLoading[slot];
            }
            if (loaded) {
                if (engine.isRunning()) engine.previewDrum(code, note);
            } else if (loading && attempt < 60) {
                waitForDrumPreview(code, note, slot, attempt + 1);
            } else {
                Toast.makeText(this, "Could not load this sound", Toast.LENGTH_SHORT).show();
            }
        }, 100);
    }

    private void populateCustomKitList(LinearLayout list, String filter, final DrumPiece piece,
                                       final Dialog dialog) {
        list.removeAllViews();
        String f = filter.trim().toLowerCase(Locale.US);
        int libraryCategory = drumLibraryCategory(piece);
        if (libraryCategory >= 0) {
            TextView heading = detailText("WAV SAMPLE LIBRARY");
            heading.setTextColor(COLOR_AMBER);
            list.addView(heading, topMargin(matchWrap(), 4));
            String[] names = DrumSampleLib.NAMES[libraryCategory];
            for (int i = 0; i < names.length; i++) {
                final int sourceNote = 36 + i;
                final int slot = DrumSampleLib.SLOT[libraryCategory];
                final String name = names[i];
                if (!f.isEmpty() && !name.toLowerCase(Locale.US).contains(f)
                        && !"wav sample library".contains(f)) continue;
                boolean selected = piece.kitSlot == slot && piece.sourceNote == sourceNote;
                Runnable select = () -> {
                    piece.kitSlot = slot;
                    piece.sourceNote = sourceNote;
                    saveDrumAssignments();
                    applyDrumKit();
                    populateAssignList();
                    dialog.dismiss();
                };
                View item = midiKitChoiceRow(name + "  ·  WAV", selected,
                        () -> previewDrumChoice(slot, sourceNote), select);
                list.addView(item, topMargin(matchWrap(), 8));
            }
        }
        for (final TonePreset preset : TonePreset.forMode(InstrumentMode.DRUMS)) {
            if (!f.isEmpty()
                    && !preset.label.toLowerCase(Locale.US).contains(f)
                    && !preset.detail.toLowerCase(Locale.US).contains(f)
                    && !preset.category.toLowerCase(Locale.US).contains(f)) {
                continue;
            }
            final int slot = pieceCodeForProgram(preset.program);
            boolean selected = piece.kitSlot == slot && piece.sourceNote < 0;
            Runnable select = () -> {
                piece.kitSlot = slot;
                piece.sourceNote = -1;
                saveDrumAssignments();
                applyDrumKit();
                populateAssignList();
                dialog.dismiss();
            };
            View item = midiKitChoiceRow(preset.label, selected,
                    () -> previewDrumChoice(slot, piece.gmNote), select);
            list.addView(item, topMargin(matchWrap(), 8));
        }
        if (list.getChildCount() == 0) {
            list.addView(detailText("No matching kits."), topMargin(matchWrap(), 8));
        }
    }

    private String kitNameForPiece(DrumPiece piece) {
        if (piece.gmNote == CHIMES_MIDI_NOTE) return "WAV Chimes";
        if (piece.gmNote == SWELL_FIRST_MIDI_NOTE) {
            return "WAV Swell " + (drumSwellVariant + 1) + " · x5";
        }
        if (piece.sourceNote >= 36 && piece.kitSlot >= DrumSampleLib.SLOT_BASE
                && piece.kitSlot < DrumSampleLib.SLOT_BASE + DrumSampleLib.SLOT.length) {
            int category = piece.kitSlot - DrumSampleLib.SLOT_BASE;
            int index = piece.sourceNote - 36;
            if (index < DrumSampleLib.NAMES[category].length) {
                return DrumSampleLib.NAMES[category][index] + " · WAV";
            }
        }
        return kitNameForSlot(piece.kitSlot);
    }

    private int drumLibraryCategory(DrumPiece piece) {
        int note = piece.gmNote;
        if (note == 35 || note == 36) return 0;
        if (note == 37 || note == 38 || note == 40) return 1;
        if (note == 41 || note == 43 || note == 45 || note == 47 || note == 48 || note == 50) return 2;
        if (note == 42 || note == 44 || note == 46 || note == 49 || note == 51 || note == 52
                || note == 53 || note == 55 || note == 57 || note == 59) return 3;
        if (note == 39) return 4;
        if (note == 54 || note == 56) return 5;
        return -1;
    }

    private TextView drumBadge(DrumPiece p) {
        TextView b = new TextView(this);
        b.setText(p.badge);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(COLOR_GREEN);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(10);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(COLOR_SKY_CONTROL);
        g.setStroke(dp(2), COLOR_GREEN);
        b.setBackground(g);
        return b;
    }

    private TextView assignChip(String text, boolean dim) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(dim ? COLOR_DIM : COLOR_TEXT);
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.setBackground(panelBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        t.setClickable(true);
        return t;
    }

    private TextView iconButton(String glyph, Runnable onClick) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextColor(contrastTextColor(COLOR_SURFACE_RAISED));
        t.setTextSize(22);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setBackground(animatedButtonBackground(
                COLOR_SURFACE_RAISED, dp(999), COLOR_TEAL));
        t.setClickable(true);
        t.setOnClickListener(v -> onClick.run());
        return t;
    }

    private View checkRow(String label, boolean checked, Runnable onToggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setPadding(dp(2), dp(4), dp(2), dp(4));
        TextView box = new TextView(this);
        box.setText(checked ? "✓" : "");
        box.setGravity(Gravity.CENTER);
        box.setTextColor(COLOR_BACKGROUND);
        box.setTypeface(Typeface.DEFAULT_BOLD);
        box.setTextSize(13);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(5));
        g.setColor(checked ? COLOR_TEAL : Color.TRANSPARENT);
        g.setStroke(dp(2), checked ? COLOR_TEAL : COLOR_BORDER_STRONG);
        box.setBackground(g);
        row.addView(box, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(COLOR_TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextSize(13);
        t.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = dp(8);
        row.addView(t, tp);
        row.setOnClickListener(v -> onToggle.run());
        return row;
    }

    private void beginAssign(int index) {
        armedPieceIndex = index;
        editNoteDialog(drumPieces[index]);
    }

    private void editNoteDialog(final DrumPiece p) {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(16));

        TextView title = new TextView(this);
        title.setText("Assign MIDI note");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(19);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        TextView sub = new TextView(this);
        sub.setText(p.name + "  ·  plays kit note " + p.gmNote);
        sub.setTextColor(COLOR_MUTED);
        sub.setTextSize(13);
        content.addView(sub, topMargin(matchWrap(), 4));

        LinearLayout stepper = new LinearLayout(this);
        stepper.setOrientation(LinearLayout.HORIZONTAL);
        stepper.setGravity(Gravity.CENTER_VERTICAL);

        final EditText field = new EditText(this);
        textIme(field);
        field.setText(String.valueOf(p.inNote));
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        field.setTextColor(COLOR_TEXT);
        field.setTextSize(22);
        field.setGravity(Gravity.CENTER);
        field.setBackground(panelBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        field.setPadding(dp(10), dp(10), dp(10), dp(10));
        assignNoteField = field;

        Button minus = stepButton("−");
        Button plus = stepButton("+");
        minus.setOnClickListener(v -> field.setText(
                String.valueOf(clamp127(parseInt(field.getText().toString()) - 1))));
        plus.setOnClickListener(v -> field.setText(
                String.valueOf(clamp127(parseInt(field.getText().toString()) + 1))));

        stepper.addView(minus, new LinearLayout.LayoutParams(dp(54), dp(50)));
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        fieldLp.leftMargin = dp(10);
        fieldLp.rightMargin = dp(10);
        stepper.addView(field, fieldLp);
        stepper.addView(plus, new LinearLayout.LayoutParams(dp(54), dp(50)));
        content.addView(stepper, topMargin(matchWrap(), 16));

        TextView hint = new TextView(this);
        hint.setText(midiInputAvailable
                ? "Or play a note on your MIDI device to capture it."
                : "No MIDI device — type the note number (0–127).");
        hint.setTextColor(COLOR_DIM);
        hint.setTextSize(12);
        content.addView(hint, topMargin(matchWrap(), 10));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        Button cancel = textButton("Cancel");
        cancel.setOnClickListener(v -> dialog.dismiss());
        Button ok = textButton("Save");
        ok.setOnClickListener(v -> {
            p.inNote = clamp127(parseInt(field.getText().toString()));
            saveDrumAssignments();
            populateAssignList();
            dialog.dismiss();
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        okLp.leftMargin = dp(8);
        actions.addView(ok, okLp);
        content.addView(actions, topMargin(matchWrap(), 14));

        dialog.setOnDismissListener(d -> {
            assignNoteField = null;
            armedPieceIndex = -1;
        });
        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    dialogWidth(0.86f, 460),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void channelDialog(final DrumPiece p) {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(12));
        TextView title = new TextView(this);
        title.setText("MIDI channel for " + p.name);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int ch = 1; ch <= 16; ch++) {
            final int channel = ch;
            TextView item = new TextView(this);
            item.setText("Channel " + ch);
            item.setTextColor(ch == p.channel ? COLOR_GREEN : COLOR_TEXT);
            item.setTextSize(16);
            item.setPadding(dp(14), dp(12), dp(14), dp(12));
            item.setBackground(moduleBackground(COLOR_SURFACE_RAISED, COLOR_BORDER,
                    ch == p.channel ? COLOR_GREEN : COLOR_BORDER, true));
            item.setClickable(true);
            item.setOnClickListener(v -> {
                p.channel = channel;
                saveDrumAssignments();
                populateAssignList();
                dialog.dismiss();
            });
            list.addView(item, topMargin(matchWrap(), 8));
        }
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT), 12));

        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    dialogWidth(0.74f, 420),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void onMidiLearn(int note, int channel0) {
        if (assignNoteField != null) {
            assignNoteField.setText(String.valueOf(note));
        } else if (armedPieceIndex >= 0 && armedPieceIndex < drumPieces.length) {
            drumPieces[armedPieceIndex].inNote = note;
            saveDrumAssignments();
            populateAssignList();
        }
        if (midiAssignStatus != null) {
            midiAssignStatus.setText("Captured note " + note);
            midiAssignStatus.setTextColor(COLOR_GREEN);
        }
    }

    private Button textButton(String label) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextColor(COLOR_TEXT);
        b.setTextSize(15);
        b.setBackground(animatedButtonBackground(
                COLOR_SURFACE_RAISED, dp(8), COLOR_TEAL));
        return b;
    }

    private Button stepButton(String label) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextColor(COLOR_TEXT);
        b.setTextSize(22);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(animatedButtonBackground(
                COLOR_SURFACE_RAISED, dp(8), COLOR_TEAL));
        return b;
    }

    private String midiStatusLabel() {
        return midiInputAvailable ? midiDeviceLabel.replace("MIDI: ", "") : "No MIDI device found";
    }

    private static int clamp127(int v) {
        return v < 0 ? 0 : (v > 127 ? 127 : v);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // {tone (-1 dark .. +1 bright), drive, chorus, tremolo}
    private float[] pianoFx(TonePreset p) {
        switch (p) {
            case SIG_NORD_GRAND: return new float[]{0.45f, 0.12f, 0.0f, 0.0f};
            case SIG_CP80: return new float[]{0.30f, 0.10f, 0.35f, 0.0f};
            case SIG_SUITCASE: return new float[]{0.15f, 0.05f, 0.60f, 0.18f};
            case SIG_WURLI: return new float[]{0.10f, 0.30f, 0.0f, 0.55f};
            case SIG_DX7: return new float[]{0.50f, 0.0f, 0.40f, 0.0f};
            case SIG_B3: return new float[]{0.10f, 0.50f, 0.30f, 0.55f};
            case SIG_CLAV: return new float[]{0.45f, 0.35f, 0.0f, 0.0f};
            case SIG_M1: return new float[]{0.50f, 0.25f, 0.0f, 0.0f};
            case PIANO_MELLOW_GRAND: return new float[]{-0.50f, 0.0f, 0.0f, 0.0f};
            case PIANO_STUDIO_GRAND: return new float[]{0.35f, 0.12f, 0.0f, 0.0f};
            case PIANO_ROCK_GRAND: return new float[]{0.50f, 0.18f, 0.0f, 0.0f};
            case PIANO_HOUSE: return new float[]{0.50f, 0.25f, 0.0f, 0.0f};
            case PIANO_SUITCASE_73: return new float[]{0.15f, 0.05f, 0.55f, 0.15f};
            case PIANO_AMPED_REED: return new float[]{0.10f, 0.25f, 0.0f, 0.30f};
            case PIANO_TREMOLO_WURLY: return new float[]{0.10f, 0.15f, 0.0f, 0.60f};
            case PIANO_WAH_CLAV: return new float[]{0.50f, 0.30f, 0.0f, 0.0f};
            case PIANO_DX7: return new float[]{0.50f, 0.0f, 0.40f, 0.0f};
            // Bright and wide: the bell partial comes from the layer, the
            // chorus does the shimmer. Mod still overrides the chorus live.
            case FM_BELL_LIB: return new float[]{0.85f, 0.0f, 0.45f, 0.0f};
            // Warm analog synth bass: a touch dark + gentle chorus for that
            // vintage New Order width. Mod knob rides the chorus live.
            case PIANO_SYNTH_BASS: return new float[]{-0.10f, 0.0f, 0.28f, 0.0f};
            case PIANO_SYNTH_BASS_2: return new float[]{0.20f, 0.0f, 0.22f, 0.0f};
            // Lush chorused string machine — wide, slightly dark, for the 80s
            // string-pad drama. Mod knob rides the chorus for more/less width.
            case PIANO_80S_STRINGS: return new float[]{-0.05f, 0.0f, 0.55f, 0.0f};
            // Soft synth brass: rolled-off highs so it's mellow not blaring,
            // with gentle chorus width. Mod rides the chorus.
            case PIANO_SOFT_BRASS: return new float[]{-0.40f, 0.0f, 0.30f, 0.0f};
            // Bright Moroder saw arp: highs pushed for cut, light chorus width.
            case PIANO_ELECTRIC_DREAMS: return new float[]{0.75f, 0.0f, 0.30f, 0.0f};
            case PIANO_FM_RHODES: return new float[]{0.40f, 0.0f, 0.30f, 0.0f};
            case PIANO_GLASSY_EP: return new float[]{0.60f, 0.0f, 0.10f, 0.0f};
            case PIANO_DYNO: return new float[]{0.50f, 0.0f, 0.20f, 0.0f};
            case PIANO_ROCK_ORGAN: return new float[]{0.10f, 0.50f, 0.0f, 0.30f};
            case PIANO_GOSPEL_B3: return new float[]{0.0f, 0.20f, 0.20f, 0.50f};
            case PIANO_CLUB_ORGAN: return new float[]{0.20f, 0.30f, 0.0f, 0.0f};
            default: return new float[]{0.0f, 0.0f, 0.0f, 0.0f};
        }
    }

    private boolean isFavorite(TonePreset preset) {
        return favorites.contains(preset.name());
    }

    private void toggleFavorite(TonePreset preset) {
        if (!favorites.remove(preset.name())) {
            favorites.add(preset.name());
        }
        saveFavorites();
    }

    private void saveFavorites() {
        if (prefs != null) {
            prefs.edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
        }
    }

    private void openInstrument(InstrumentMode mode) {
        virtualGuitarMidiMode = false;
        openInstrumentWorkspace(mode);
    }

    private void openVirtualGuitarMidi() {
        virtualGuitarMidiMode = true;
        if (!prefs.getBoolean("virtual_guitar_initialized", false)) {
            pianoGuitarRigOn = true;
            prefs.edit()
                    .putBoolean("virtual_guitar_initialized", true)
                    .putBoolean("piano_guitar_rig_on", true)
                    .apply();
        }
        openInstrumentWorkspace(InstrumentMode.PIANO);
    }

    private void openInstrumentWorkspace(InstrumentMode mode) {
        // Leftover voices from the previous screen (a note-off lost in a
        // screen switch, a pending sustain timer) must never carry over —
        // looped sounds like strings or organ would ring forever.
        engine.allNotesOff();
        currentMode = mode;
        loadAudioPrefs();   // each instrument keeps its own input/output choice
        currentPreset = lastPreset(mode);   // restore the last sound picked here
        if (virtualGuitarMidiMode && !isGuitarPreset(currentPreset)) {
            currentPreset = TonePreset.VIRTUAL_GUITAR_STARTER;
        }
        currentRoute = defaultRouteFor(mode);
        currentError = null;
        feedbackTicks = 0;
        showFavoritesOnly = false;
        clearKeys();
        applyPianoProgram();
        applyDrumKit();
        showInstrumentScreen();
    }

    private Drawable landingButtonBackground(int accent) {
        return animatedButtonBackground(Color.rgb(222, 244, 255), dp(22), accent);
    }

    private void launchFromLanding(View source, Runnable destination) {
        source.setEnabled(false);
        int[] location = new int[2];
        source.getLocationOnScreen(location);
        BubblePopView pop = new BubblePopView(this);
        int side = dp(176);
        pop.measure(View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY));
        pop.layout(0, 0, side, side);
        pop.setX(location[0] + source.getWidth() * 0.5f - dp(88));
        pop.setY(location[1] + source.getHeight() * 0.5f - dp(88));
        ViewGroup decor = (ViewGroup) getWindow().getDecorView();
        decor.getOverlay().add(pop);
        source.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70)
                .withEndAction(() -> source.animate().scaleX(1f).scaleY(1f).setDuration(120).start())
                .start();
        pop.play(() -> {
            decor.getOverlay().remove(pop);
            destination.run();
        });
    }

    private static final class BubblePopView extends View {
        private static final int COUNT = 15;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] angle = new float[COUNT];
        private final float[] distance = new float[COUNT];
        private final float[] size = new float[COUNT];
        private long startedAt;
        private Runnable onFinished;

        BubblePopView(Context context) {
            super(context);
            float d = getResources().getDisplayMetrics().density;
            for (int i = 0; i < COUNT; i++) {
                angle[i] = (float) (Math.PI * 2.0 * i / COUNT + (i % 3) * 0.12);
                distance[i] = (34 + (i * 17) % 64) * d;
                size[i] = (5 + (i * 7) % 10) * d;
            }
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int side = Math.round(176 * getResources().getDisplayMetrics().density);
            setMeasuredDimension(side, side);
        }

        void play(Runnable finished) {
            onFinished = finished;
            startedAt = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }

        @Override protected void onDraw(Canvas canvas) {
            float elapsed = (SystemClock.uptimeMillis() - startedAt) / 260f;
            float t = Math.min(1f, elapsed);
            float ease = 1f - (1f - t) * (1f - t);
            float alpha = 1f - t;
            float cx = getWidth() * 0.5f, cy = getHeight() * 0.5f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb((int) (76 * alpha), 52, 176, 226));
            canvas.drawCircle(cx, cy, (20 + 30 * ease) * getResources().getDisplayMetrics().density, paint);
            for (int i = 0; i < COUNT; i++) {
                float x = cx + (float) Math.cos(angle[i]) * distance[i] * ease;
                float y = cy + (float) Math.sin(angle[i]) * distance[i] * ease;
                paint.setColor(Color.argb((int) (205 * alpha), 92, 194, 238));
                canvas.drawCircle(x, y, size[i] * (1f - 0.35f * t), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, size[i] * 0.16f));
                paint.setColor(Color.argb((int) (230 * alpha), 236, 252, 255));
                canvas.drawCircle(x - size[i] * 0.22f, y - size[i] * 0.22f, size[i] * 0.38f, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            if (t < 1f) postInvalidateOnAnimation();
            else if (onFinished != null) { Runnable done = onFinished; onFinished = null; done.run(); }
        }
    }

    private void goToPicker() {
        engine.stop();
        currentError = null;
        feedbackTicks = 0;
        clearKeys();
        showPicker();
    }

    // ---- Loop Mix (loop station: harmonizer + vocals loop + 3 overdub loops) ----

    private static final int LOOP_PINK = Color.rgb(240, 110, 190);

    private View buildLoopMixCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(landingButtonBackground(LOOP_PINK));
        card.setClickable(true);
        card.setOnClickListener(v -> launchFromLanding(card, this::showLoopMix));

        LoopsIconView icon = new LoopsIconView(this, LOOP_PINK);
        int sz = dpT(40, 54);
        card.addView(icon, new LinearLayout.LayoutParams(sz, sz));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText("Loop Mix");
        name.setTextColor(Color.WHITE);
        name.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        name.setTextSize(17);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(name, matchWrap());
        TextView desc = new TextView(this);
        desc.setText("Loop & overdub");
        desc.setTextColor(Color.WHITE);
        desc.setTextSize(11);
        desc.setMaxLines(2);
        desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(desc, topMargin(matchWrap(), 2));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(12);
        // Badge lives INSIDE the weighted text column, not as a sibling of it.
        // As a horizontal sibling its fixed width would, at high UI-scale (fewer
        // dp across the screen), survive while the text column collapsed to zero
        // and then overflow the card edge into the next one — the "bleed". In
        // the column it simply wraps/clips with the text and can never widen the
        // row.
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.topMargin = dp(4);
        text.addView(betaBadge(), badgeLp);
        card.addView(text, tlp);
        return card;
    }

    private static final int VOX_CYAN = Color.rgb(84, 200, 232);

    private View buildVocalsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(landingButtonBackground(VOX_CYAN));
        card.setClickable(true);
        card.setOnClickListener(v -> launchFromLanding(card, this::showVocals));

        TextView icon = new TextView(this);
        icon.setText("\ud83c\udfa4");
        icon.setTextSize(dpT(22, 28));
        icon.setGravity(Gravity.CENTER);
        icon.setIncludeFontPadding(false);
        card.addView(icon, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText("Vocals");
        name.setTextColor(Color.WHITE);
        name.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        name.setTextSize(17);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(name, matchWrap());
        TextView desc = new TextView(this);
        desc.setText("Live vocal FX");
        desc.setTextColor(Color.WHITE);
        desc.setTextSize(11);
        desc.setMaxLines(2);
        desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(desc, topMargin(matchWrap(), 2));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(12);
        // Badge lives INSIDE the weighted text column, not as a sibling of it.
        // As a horizontal sibling its fixed width would, at high UI-scale (fewer
        // dp across the screen), survive while the text column collapsed to zero
        // and then overflow the card edge into the next one — the "bleed". In
        // the column it simply wraps/clips with the text and can never widen the
        // row.
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.topMargin = dp(4);
        text.addView(betaBadge(), badgeLp);
        card.addView(text, tlp);
        return card;
    }

    // ---- Guitar Keys (guitar audio -> MIDI-style piano, monophonic) ----

    private View buildGuitarKeysCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(landingButtonBackground(COLOR_AMBER));
        card.setClickable(true);
        card.setOnClickListener(v -> launchFromLanding(card, this::showGuitarKeys));

        TextView icon = new TextView(this);
        icon.setText("🎸🎹");
        icon.setTextSize(dpT(15, 20));
        icon.setGravity(Gravity.CENTER);
        icon.setIncludeFontPadding(false);
        card.addView(icon, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText("Guitar Keys");
        name.setTextColor(Color.WHITE);
        name.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        name.setTextSize(17);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(name, matchWrap());
        TextView desc = new TextView(this);
        desc.setText("Guitar → piano");
        desc.setTextColor(Color.WHITE);
        desc.setTextSize(11);
        desc.setMaxLines(2);
        desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(desc, topMargin(matchWrap(), 2));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(12);
        // Badge lives INSIDE the weighted text column, not as a sibling of it.
        // As a horizontal sibling its fixed width would, at high UI-scale (fewer
        // dp across the screen), survive while the text column collapsed to zero
        // and then overflow the card edge into the next one — the "bleed". In
        // the column it simply wraps/clips with the text and can never widen the
        // row.
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeLp.topMargin = dp(4);
        text.addView(betaBadge(), badgeLp);
        card.addView(text, tlp);
        return card;
    }

    private void showGuitarKeys() {
        if (!hasRecordAudioPermission()) {
            requestAudioPermissionIfNeeded();
            Toast.makeText(this, "Microphone permission is needed for Guitar Keys", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean entering = !onGuitarKeys;
        onGuitarKeys = true;
        if (entering) {
            loadAudioPrefs();
        }

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);

        // Landscape: transport rail left, live note display + sound pane right.
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setPadding(dp(20), dp(14), dp(20), dp(14));
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        LinearLayout pane = paneColumn();

        rail.addView(railHeader(this::exitGuitarKeys, "GUITAR → MIDI · BETA", "Guitar Keys", COLOR_AMBER),
                matchWrap());

        final TextView startBtn = transportPill(engine.isRunning() ? "■  Stop engine" : "▶  Start engine");
        startBtn.setTextSize(16);
        startBtn.setPadding(dp(26), dp(12), dp(26), dp(12));
        startBtn.setTextColor(engine.isRunning() ? COLOR_GREEN : COLOR_TEXT);
        startBtn.setOnClickListener(v -> {
            if (engine.isRunning()) {
                engine.stop();
            } else {
                startGuitarKeysEngine();
            }
            boolean on = engine.isRunning();
            startBtn.setText(on ? "■  Stop engine" : "▶  Start engine");
            startBtn.setTextColor(on ? COLOR_GREEN : COLOR_TEXT);
        });
        LinearLayout startRow = new LinearLayout(this);
        startRow.setOrientation(LinearLayout.HORIZONTAL);
        startRow.setGravity(Gravity.CENTER_HORIZONTAL);
        startRow.addView(startBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        rail.addView(startRow, topMargin(matchWrap(), 16));

        // Detected note(s), big and live — the centerpiece of the right pane.
        LinearLayout notePanel = verticalPanel();
        notePanel.setGravity(Gravity.CENTER);
        gkNoteText = new TextView(this);
        gkNoteText.setText("--");
        gkNoteText.setTextColor(COLOR_AMBER);
        gkNoteText.setTextSize(34);
        gkNoteText.setGravity(Gravity.CENTER);
        gkNoteText.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        notePanel.addView(gkNoteText, matchWrap());
        vocalMeter = new VocalMeterView(this);
        notePanel.addView(vocalMeter, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(14)), 12));
        pane.addView(notePanel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Sound choice (full piano list) + tracking mode + reverb.
        final TextView soundBtn = transportPill("Sound: " + gkPreset.label + "  ▾");
        soundBtn.setOnClickListener(v -> gkSoundDialog(soundBtn));
        final TextView modeBtn = transportPill(gkPoly ? "Poly · chords" : "Mono · fast");
        modeBtn.setTextColor(gkPoly ? COLOR_GREEN : COLOR_TEXT);
        modeBtn.setOnClickListener(v -> {
            gkPoly = !gkPoly;
            prefs.edit().putBoolean("gk_poly", gkPoly).apply();
            engine.setGuitarKeysPoly(gkPoly);
            modeBtn.setText(gkPoly ? "Poly · chords" : "Mono · fast");
            modeBtn.setTextColor(gkPoly ? COLOR_GREEN : COLOR_TEXT);
        });
        LinearLayout soundRow = new LinearLayout(this);
        soundRow.setOrientation(LinearLayout.HORIZONTAL);
        soundRow.setGravity(Gravity.CENTER_HORIZONTAL);
        soundRow.addView(soundBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        modeLp.leftMargin = dp(10);
        soundRow.addView(modeBtn, modeLp);
        pane.addView(soundRow, topMargin(matchWrap(), 12));

        // Play-as-instrument extras: quick bass preset, octave shift, and
        // bend follow (the sound rides bends/vibrato instead of stepping).
        final TextView octBtn = transportPill(gkOctLabel());
        octBtn.setTextColor(gkOct != 0 ? COLOR_GREEN : COLOR_TEXT);
        octBtn.setOnClickListener(v -> {
            gkOct = gkOct >= 1 ? -1 : gkOct + 1;
            prefs.edit().putInt("gk_oct", gkOct).apply();
            engine.setGuitarKeysTranspose(gkOct * 12);
            octBtn.setText(gkOctLabel());
            octBtn.setTextColor(gkOct != 0 ? COLOR_GREEN : COLOR_TEXT);
        });
        final TextView bendBtn = transportPill("Bend follow");
        bendBtn.setTextColor(gkBendFollow ? COLOR_GREEN : COLOR_TEXT);
        bendBtn.setOnClickListener(v -> {
            gkBendFollow = !gkBendFollow;
            prefs.edit().putBoolean("gk_bend", gkBendFollow).apply();
            engine.setGuitarKeysBendFollow(gkBendFollow);
            bendBtn.setTextColor(gkBendFollow ? COLOR_GREEN : COLOR_TEXT);
        });
        final TextView bassBtn = transportPill("🎸→ Bass");
        bassBtn.setTextColor(gkBassMode ? COLOR_GREEN : COLOR_TEXT);
        bassBtn.setOnClickListener(v -> {
            gkBassMode = !gkBassMode;
            if (gkBassMode) {
                if (!gkIsBassPreset(gkPreset)) {
                    gkPreset = TonePreset.GM_034;   // Electric Bass (finger)
                }
                gkOct = -1;
                gkPoly = false;
                gkBendFollow = true;
                engine.setGuitarKeysPoly(false);
                engine.setGuitarKeysTranspose(-12);
                engine.setGuitarKeysBendFollow(true);
                gkApplySound();
                soundBtn.setText("Sound: " + gkPreset.label + "  ▾");
                modeBtn.setText("Mono · fast");
                modeBtn.setTextColor(COLOR_TEXT);
                octBtn.setText(gkOctLabel());
                octBtn.setTextColor(COLOR_GREEN);
                bendBtn.setTextColor(COLOR_GREEN);
                Toast.makeText(this, "Bass mode — bass sounds only, bends carry over",
                        Toast.LENGTH_SHORT).show();
            } else {
                gkOct = 0;
                engine.setGuitarKeysTranspose(0);
                octBtn.setText(gkOctLabel());
                octBtn.setTextColor(COLOR_TEXT);
                Toast.makeText(this, "Bass mode off — full sound list", Toast.LENGTH_SHORT).show();
            }
            bassBtn.setTextColor(gkBassMode ? COLOR_GREEN : COLOR_TEXT);
            prefs.edit().putBoolean("gk_bassmode", gkBassMode)
                    .putString("gk_preset", gkPreset.name()).putInt("gk_oct", gkOct)
                    .putBoolean("gk_poly", gkPoly).putBoolean("gk_bend", gkBendFollow).apply();
        });
        LinearLayout extraRow = new LinearLayout(this);
        extraRow.setOrientation(LinearLayout.HORIZONTAL);
        extraRow.setGravity(Gravity.CENTER_HORIZONTAL);
        extraRow.addView(bassBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams octLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        octLp.leftMargin = dp(10);
        extraRow.addView(octBtn, octLp);
        LinearLayout.LayoutParams bendLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bendLp.leftMargin = dp(10);
        extraRow.addView(bendBtn, bendLp);
        pane.addView(extraRow, topMargin(matchWrap(), 10));

        final TextView revLabel = new TextView(this);
        revLabel.setTextSize(13);
        revLabel.setTextColor(COLOR_TEXT);
        revLabel.setText("Reverb  " + gkRevAmount + "%");
        pane.addView(revLabel, topMargin(matchWrap(), 12));
        SeekBar revBar = new SeekBar(this);
        revBar.setMax(100);
        revBar.setProgress(gkRevAmount);
        revBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                gkRevAmount = progress;
                engine.setVocalReverb(gkRevAmount / 100f);
                revLabel.setText("Reverb  " + gkRevAmount + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) { }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                prefs.edit().putInt("gk_rev", gkRevAmount).apply();
            }
        });
        pane.addView(revBar, topMargin(matchWrap(), 2));

        // Routing: guitar input + output sink, per-screen like everywhere else.
        TextView outBtn = transportPill("🔊 " + currentOutputLabel());
        outBtn.setOnClickListener(v -> audioOutputDialog());
        TextView inBtn = transportPill("🎸 " + currentInputLabel());
        inBtn.setOnClickListener(v -> audioInputDialog());
        LinearLayout ioRow = new LinearLayout(this);
        ioRow.setOrientation(LinearLayout.HORIZONTAL);
        ioRow.setGravity(Gravity.CENTER_HORIZONTAL);
        ioRow.addView(outBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inLp.leftMargin = dp(10);
        ioRow.addView(inBtn, inLp);
        rail.addView(ioRow, topMargin(matchWrap(), 12));

        content.addView(railScroll(rail), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 5.0f));
        LinearLayout.LayoutParams paneLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 7.0f);
        paneLp.leftMargin = dp(16);
        content.addView(pane, paneLp);

        screen.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        enablePadInsets(screen, content);
        paintStage(screen);
        setContentView(screen);
        handler.removeCallbacks(gkPump);
        handler.post(gkPump);
    }

    private final Runnable gkPump = new Runnable() {
        @Override
        public void run() {
            if (!onGuitarKeys) return;
            if (vocalMeter != null) {
                vocalMeter.setDb(engine.isRunning() ? engine.outputLevel() : -120f);
            }
            if (gkNoteText != null) {
                String shown = null;
                if (gkPoly && engine.isRunning()) {
                    long mask = engine.guitarKeysNotes();
                    if (mask != 0) {
                        StringBuilder sb = new StringBuilder();
                        int count = 0;
                        for (int i = 0; i < 64 && count < 6; i++) {
                            if ((mask & (1L << i)) != 0) {
                                if (count > 0) sb.append("  ");
                                sb.append(noteName(Math.max(0, Math.min(127, 36 + i + gkOct * 12))));
                                count++;
                            }
                        }
                        shown = sb.toString();
                        gkNoteText.setTextSize(count > 2 ? 22 : 34);
                    }
                } else {
                    float hz = engine.isRunning() ? engine.pitchHz() : 0f;
                    if (hz >= 35f) {
                        int note = (int) Math.round(69 + 12 * Math.log(hz / 440.0) / Math.log(2.0))
                                + gkOct * 12;
                        shown = noteName(Math.max(0, Math.min(127, note)));
                        gkNoteText.setTextSize(34);
                    }
                }
                if (shown != null) {
                    gkNoteText.setText(shown);
                } else {
                    gkNoteText.setTextSize(34);
                    gkNoteText.setText("--");
                }
            }
            handler.postDelayed(this, 80);
        }
    };

    private String gkOctLabel() {
        return gkOct > 0 ? "Oct +1" : gkOct < 0 ? "Oct −1" : "Oct 0";
    }

    // Bass mode offers only actual bass-string sounds.
    private boolean gkIsBassPreset(TonePreset p) {
        return "GM Bass".equals(p.category)
                || p == TonePreset.M1_FRETLESS || p == TonePreset.M1_PICK_BASS;
    }

    private int glideRateToProgress(int rate) {
        return Math.max(0, Math.min(100, Math.round((200 - rate) / 1.85f)));
    }

    // Firefly Melody is a mono legato portamento lead on the record, so it
    // keeps slide on. Every other sound, including Stylophone presets, follows
    // the Slide control exactly.
    private void pushPianoGlide() {
        engine.setPianoGlide(pianoGlideOn || currentPreset == TonePreset.PIANO_FIREFLY);
        engine.setPianoGlideMono(pianoGlideOn && pianoGlideMono);
    }

    // Looper keys follow the user's Slide pill, except Firefly Melody. Slide is
    // off while Chord is on because a chord tap is three simultaneous keys.
    private void pushLoopKeysGlide() {
        engine.setLoopKeysGlide(
                (loopKeysSlide || loopKeysPreset == TonePreset.PIANO_FIREFLY)
                        && !loopKeysChord);
        engine.setLoopKeysGlideMono(
                (loopKeysSlide && loopKeysSlideMono) && !loopKeysChord);
    }

    private void startGuitarKeysEngine() {
        engine.stop();
        gkApplySound();
        engine.setGuitarKeysPoly(gkPoly);
        engine.setGuitarKeysTranspose(gkOct * 12);
        engine.setGuitarKeysBendFollow(gkBendFollow);
        engine.startGuitarKeys(resolvePreferredInput(-1), resolvePreferredOutput(-1));
        engine.setVocalReverb(gkRevAmount / 100f);
    }

    private void exitGuitarKeys() {
        onGuitarKeys = false;
        handler.removeCallbacks(gkPump);
        vocalMeter = null;
        gkNoteText = null;
        engine.stop();
        engine.setFontSlot(-1);
        showPicker();
    }

    // Push the Guitar Keys sound: GM program + HQ/library font when needed.
    private void gkApplySound() {
        engine.allNotesOff();
        engine.setMidiProgram(pianoProgram(gkPreset));
        if (gkPreset.asset != null && pianoFontSlot(gkPreset) == LAZY_PIANO_SLOT) {
            loadGkFont(gkPreset);
        } else {
            engine.setFontSlot(pianoFontSlot(gkPreset));   // preloaded slot (incl. Steinway)
        }
    }

    private void loadGkFont(final TonePreset preset) {
        final String asset = preset.asset;
        if (asset.equals(loadedLibraryAsset)) {
            engine.setFontSlot(LAZY_PIANO_SLOT);
            return;
        }
        engine.setFontSlot(-1);   // GM stands in while the font loads
        final float gainDb = libraryPianoGain(asset);
        new Thread(() -> {
            byte[] data = readAsset(asset);
            final boolean ok = data != null && engine.loadHqFont(LAZY_PIANO_SLOT, 0, gainDb, data);
            handler.post(() -> {
                if (ok) {
                    loadedLibraryAsset = asset;
                }
                if (onGuitarKeys && preset == gkPreset && ok) {
                    engine.setFontSlot(LAZY_PIANO_SLOT);
                }
            });
        }, "gk-lib-loader").start();
    }

    // Sound list for Guitar Keys: the full piano roster, grouped, at selection.
    private void gkSoundDialog(final TextView pill) {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));
        TextView title = new TextView(this);
        title.setText(gkBassMode ? "Bass Sound" : "Guitar Keys Sound");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        final EditText search = new EditText(this);
        searchIme(search);
        search.setHint("Search sounds");
        search.setHintTextColor(COLOR_DIM);
        search.setTextColor(COLOR_TEXT);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        search.setBackground(panelBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        content.addView(search, topMargin(matchWrap(), 10));

        final ScrollView sv = new ScrollView(this);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        content.addView(sv, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT), 6));

        populateGkSoundList(list, "", dialog, pill);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                populateGkSoundList(list, s.toString(), dialog, pill);
            }
        });

        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.85f, 460), LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        // Open at the current selection, not the top of the list.
        sv.post(() -> {
            if (pickerSelectedRow != null) {
                sv.scrollTo(0, Math.max(0, pickerSelectedRow.getTop() - dp(120)));
            }
        });
    }

    private void populateGkSoundList(LinearLayout list, String filter, final Dialog dialog,
                                     final TextView pill) {
        list.removeAllViews();
        pickerSelectedRow = null;
        String f = filter.trim().toLowerCase(Locale.US);
        String currentCategory = null;
        for (final TonePreset preset : TonePreset.forMode(InstrumentMode.PIANO)) {
            if (gkBassMode && !gkIsBassPreset(preset)) continue;   // bass mode: basses only
            if (!f.isEmpty()
                    && !preset.label.toLowerCase(Locale.US).contains(f)
                    && !preset.detail.toLowerCase(Locale.US).contains(f)
                    && !preset.category.toLowerCase(Locale.US).contains(f)) {
                continue;
            }
            if (!preset.category.equals(currentCategory)) {
                currentCategory = preset.category;
                list.addView(labelText(currentCategory.toUpperCase(Locale.US)),
                        topMargin(matchWrap(), list.getChildCount() == 0 ? 4 : 14));
            }
            boolean active = preset == gkPreset;
            TextView item = menuItem((active ? "●  " : "○  ") + preset.label, () -> {
                gkPreset = preset;
                prefs.edit().putString("gk_preset", preset.name()).apply();
                gkApplySound();
                if (pill != null) pill.setText("Sound: " + preset.label + "  ▾");
                dialog.dismiss();
            });
            if (active) {
                item.setTextColor(COLOR_GREEN);
                pickerSelectedRow = item;
            }
            list.addView(item, topMargin(matchWrap(), 8));
        }
        if (list.getChildCount() == 0) {
            list.addView(detailText("No matching sounds."), topMargin(matchWrap(), 8));
        }
    }

    // ---- Vocals (live vocal FX rig: mic -> autotune/harmonizer -> output) ----

    private void showVocals() {
        if (!hasRecordAudioPermission()) {
            requestAudioPermissionIfNeeded();
            Toast.makeText(this, "Microphone permission is needed for Vocals", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean entering = !onVocalsScreen;
        onVocalsScreen = true;
        if (entering) {
            loadAudioPrefs();
        }

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);

        // Landscape: mic/transport rail left, FX pane right.
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setPadding(dp(20), dp(14), dp(20), dp(14));
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        LinearLayout pane = paneColumn();

        rail.addView(railHeader(this::exitVocals, "LIVE VOCAL FX · BETA", "Vocals", VOX_CYAN), matchWrap());

        final TextView startBtn = transportPill(engine.isRunning() ? "\u25a0  Stop engine" : "\u25b6  Start engine");
        startBtn.setTextSize(16);
        startBtn.setPadding(dp(26), dp(12), dp(26), dp(12));
        startBtn.setTextColor(engine.isRunning() ? COLOR_GREEN : COLOR_TEXT);
        startBtn.setOnClickListener(v -> {
            if (engine.isRunning()) {
                engine.stop();
            } else {
                startVocalEngine();
            }
            boolean on = engine.isRunning();
            startBtn.setText(on ? "\u25a0  Stop engine" : "\u25b6  Start engine");
            startBtn.setTextColor(on ? COLOR_GREEN : COLOR_TEXT);
        });
        LinearLayout startRow = new LinearLayout(this);
        startRow.setOrientation(LinearLayout.HORIZONTAL);
        startRow.setGravity(Gravity.CENTER_HORIZONTAL);
        startRow.addView(startBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        rail.addView(startRow, topMargin(matchWrap(), 16));

        // Live output meter: keep it green; red means the channel is clipping.
        vocalMeter = new VocalMeterView(this);
        rail.addView(vocalMeter, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(14)), 14));

        // FX controls: the same harmonizer/autotune state the looper uses.
        final TextView harmBtn = transportPill("Harmonizer: " + (harmonizerOn ? "On" : "Off"));
        harmBtn.setTextColor(harmonizerOn ? COLOR_GREEN : COLOR_MUTED);
        harmBtn.setOnClickListener(v -> {
            harmonizerOn = !harmonizerOn;
            engine.setHarmonizer(harmonizerOn);
            harmBtn.setText("Harmonizer: " + (harmonizerOn ? "On" : "Off"));
            harmBtn.setTextColor(harmonizerOn ? COLOR_GREEN : COLOR_MUTED);
        });
        final TextView tuneBtn = transportPill("Autotune: " + (harmAutotune ? "On" : "Off"));
        tuneBtn.setTextColor(harmAutotune ? COLOR_GREEN : COLOR_MUTED);
        tuneBtn.setOnClickListener(v -> {
            harmAutotune = !harmAutotune;
            saveHarmonizerPrefs();
            engine.setAutotune(harmAutotune);
            tuneBtn.setText("Autotune: " + (harmAutotune ? "On" : "Off"));
            tuneBtn.setTextColor(harmAutotune ? COLOR_GREEN : COLOR_MUTED);
        });
        TextView fxBtn = transportPill("\u2699 FX Settings");
        fxBtn.setOnClickListener(v -> harmonizerDialog());
        // FX pane: everything that shapes the voice, in one panel.
        LinearLayout fxPanel = verticalPanel();
        fxPanel.addView(sectionTitle("Effects"), matchWrap());
        LinearLayout fxRow = new LinearLayout(this);
        fxRow.setOrientation(LinearLayout.HORIZONTAL);
        fxRow.addView(harmBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams tuneLp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tuneLp2.leftMargin = dp(10);
        fxRow.addView(tuneBtn, tuneLp2);
        LinearLayout.LayoutParams fxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fxLp.leftMargin = dp(10);
        fxRow.addView(fxBtn, fxLp);
        HorizontalScrollView fxScroll = new HorizontalScrollView(this);
        fxScroll.setHorizontalScrollBarEnabled(false);
        fxScroll.setFillViewport(true);
        fxScroll.addView(fxRow, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT, HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        fxPanel.addView(fxScroll, topMargin(matchWrap(), 12));

        // Reverb over the whole vocal channel (dry voice + harmony).
        final TextView revLabel = new TextView(this);
        revLabel.setTextSize(13);
        revLabel.setTextColor(COLOR_TEXT);
        revLabel.setText("Reverb  " + vocalRevAmount + "%");
        fxPanel.addView(revLabel, topMargin(matchWrap(), 16));
        SeekBar revBar = new SeekBar(this);
        revBar.setMax(100);
        revBar.setProgress(vocalRevAmount);
        revBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                vocalRevAmount = progress;
                engine.setVocalReverb(vocalRevAmount / 100f);
                revLabel.setText("Reverb  " + vocalRevAmount + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) { }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                prefs.edit().putInt("vocal_rev", vocalRevAmount).apply();
            }
        });
        fxPanel.addView(revBar, topMargin(matchWrap(), 2));
        pane.addView(fxPanel, matchWrap());

        // Routing: same selectors as everywhere else, pinned to the rail foot.
        TextView outBtn = transportPill("\ud83d\udd0a " + currentOutputLabel());
        outBtn.setOnClickListener(v -> audioOutputDialog());
        TextView inBtn = transportPill("\ud83c\udfa4 " + currentInputLabel());
        inBtn.setOnClickListener(v -> audioInputDialog());
        LinearLayout ioRow = new LinearLayout(this);
        ioRow.setOrientation(LinearLayout.HORIZONTAL);
        ioRow.setGravity(Gravity.CENTER_HORIZONTAL);
        ioRow.addView(outBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inLp.leftMargin = dp(10);
        ioRow.addView(inBtn, inLp);
        rail.addView(ioRow, topMargin(matchWrap(), 12));

        content.addView(railScroll(rail), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 5.4f));
        LinearLayout.LayoutParams paneLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 6.6f);
        paneLp.leftMargin = dp(16);
        content.addView(pane, paneLp);

        screen.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        enablePadInsets(screen, content);
        paintStage(screen);
        setContentView(screen);
        handler.removeCallbacks(vocalPump);
        handler.post(vocalPump);
    }

    private final Runnable vocalPump = new Runnable() {
        @Override
        public void run() {
            if (!onVocalsScreen) return;
            if (vocalMeter != null) {
                vocalMeter.setDb(engine.isRunning() ? engine.outputLevel() : -120f);
            }
            handler.postDelayed(this, 80);
        }
    };

    private void startVocalEngine() {
        engine.stop();
        engine.startVocals(resolvePreferredInput(-1), resolvePreferredOutput(-1));
        engine.setHarmonizer(harmonizerOn);
        applyHarmonizerParams();
        engine.setAutotune(harmAutotune);
        engine.setVocalReverb(vocalRevAmount / 100f);
    }

    private void exitVocals() {
        onVocalsScreen = false;
        handler.removeCallbacks(vocalPump);
        vocalMeter = null;
        engine.stop();
        showPicker();
    }

    // Horizontal output meter for the Vocals screen: green under -18 dB,
    // amber to -9, red above (the same zones as the instrument meters).
    private static final class VocalMeterView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float db = -120f;

        VocalMeterView(Context context) {
            super(context);
        }

        void setDb(float value) {
            db = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = h / 2f;
            paint.setColor(COLOR_SURFACE_RAISED);
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, r, r, paint);
            float norm = (db + 60f) / 60f;
            if (norm > 1f) norm = 1f;
            if (norm <= 0.02f) return;
            paint.setColor(db > -9f ? COLOR_RED : db > -18f ? COLOR_AMBER : COLOR_GREEN);
            rect.set(0, 0, w * norm, h);
            canvas.drawRoundRect(rect, r, r, paint);
        }
    }

    private final Runnable loopPump = new Runnable() {
        @Override
        public void run() {
            if (!onLoopMix) return;
            boolean anyRunning = false, anyPaused = false;
            for (int t = 0; t < 4; t++) {
                int raw = engine.loopState(t);
                int st = raw & 0xF;
                if (st == 2 || st == 3) anyRunning = true;
                if (st == 4) anyPaused = true;
                LoopRingView ring = loopRings[t + 1];
                if (ring != null) {
                    ring.setLenMs(engine.loopLenMs(t));
                    ring.setLoopState(st, engine.loopPos(t), (raw & 16) != 0);
                    engine.loopWave(t, ring.waveBins());
                    ring.waveUpdated();
                }
                TextView mc = loopMuteChips[t];
                if (mc != null) {
                    boolean m = (raw & 16) != 0;
                    mc.setTextColor(m ? Color.rgb(12, 12, 14) : COLOR_MUTED);
                    mc.setBackground(pillBackground(m ? COLOR_AMBER : COLOR_SURFACE_RAISED,
                            m ? COLOR_AMBER : COLOR_BORDER));
                }
            }
            if (loopPauseAllButton != null) {
                loopPauseAllButton.setText(anyRunning ? "\u23f8  Pause all" : "\u25b6  Resume all (from 0)");
                loopPauseAllButton.setTextColor(anyRunning || anyPaused ? COLOR_TEXT : COLOR_MUTED);
            }
            if (loopRings[0] != null) loopRings[0].setOn(harmonizerOn);
            handler.postDelayed(this, 100);
        }
    };

    private void showLoopMix() {
        if (!hasRecordAudioPermission()) {
            requestAudioPermissionIfNeeded();
            Toast.makeText(this, "Microphone permission is needed for Loop Mix", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!onLoopMix) {
            onLoopMix = true;
            loadAudioPrefs();
            engine.stop();
            startLoopEngine();
        }

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);

        // Landscape: control rail left; machines + pad surface right.
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        LinearLayout pane = paneColumn();

        rail.addView(railHeader(this::exitLoopMix, "LOOP STATION · BETA", "Loop Mix", LOOP_PINK),
                matchWrap());

        // Version tag so we can confirm which build is actually installed.
        TextView verTag = new TextView(this);
        verTag.setText("v" + appVersion());
        verTag.setTextColor(COLOR_DIM);
        verTag.setTextSize(10);
        rail.addView(verTag, topMargin(matchWrap(), 4));

        // ALL looper controls live in ONE group, pinned to the top of the rail —
        // keys controls and common controls together in a single block, never
        // split into two separate places. Built empty here, filled below once
        // every pill exists.
        final LinearLayout controlsHost = new LinearLayout(this);
        controlsHost.setOrientation(LinearLayout.VERTICAL);
        rail.addView(controlsHost, topMargin(matchWrap(), 10));

        // The five machines.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String[] names = {"VOICE FX", "VOCALS", "LOOP 1", "LOOP 2", "LOOP 3"};
        String[] glyphs = {"V~", "V", "1", "2", "3"};
        int[] accents = {LOOP_PINK, LOOP_PINK, COLOR_TEAL, COLOR_TEAL, COLOR_TEAL};
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(2), dp(8), dp(2), dp(8));
            cell.setBackground(panelBackground(COLOR_SURFACE, COLOR_BORDER));
            final LoopRingView ring = new LoopRingView(this, glyphs[i], accents[i], i == 0);
            loopRings[i] = ring;
            ring.setOnClickListener(v -> {
                if (idx == 0) {
                    harmonizerOn = !harmonizerOn;
                    engine.setHarmonizer(harmonizerOn);
                    ring.setOn(harmonizerOn);
                } else {
                    // While paused, tapping selects who plays on resume (mute toggle).
                    int st = engine.loopState(idx - 1) & 0xF;
                    engine.loopCommand(idx - 1, st == 4 ? 5 : 1);
                }
            });
            if (i > 0) {
                ring.setOnLongClickListener(v -> { loopTrackMenu(idx - 1, names[idx]); return true; });
            }
            cell.addView(ring, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));
            TextView name = new TextView(this);
            name.setText(names[i]);
            name.setTextColor(COLOR_MUTED);
            name.setTextSize(9);
            name.setSingleLine(true);
            name.setLetterSpacing(0.05f);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setGravity(Gravity.CENTER);
            cell.addView(name, topMargin(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT), 4));
            LinearLayout chips = new LinearLayout(this);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            chips.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView undo = loopChip("↶");
            chips.addView(undo, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView mute = loopChip("M");
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            mlp.leftMargin = dp(4);
            chips.addView(mute, mlp);
            if (i > 0) {
                undo.setOnClickListener(v -> engine.loopCommand(idx - 1, 4));
                mute.setOnClickListener(v -> engine.loopCommand(idx - 1, 5));
                loopMuteChips[idx - 1] = mute;
            } else {
                // Voice FX: ring = on/off toggle, chip = voicing settings.
                undo.setText("⚙");
                undo.setOnClickListener(v -> harmonizerDialog());
                mute.setVisibility(View.INVISIBLE);   // keeps all five cards equal height
            }
            cell.addView(chips, topMargin(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT), 5));
            row.addView(cell, chipParams(i < 4));
        }
        pane.addView(row, matchWrap());

        // Transport: pause/resume-all + drum kit choice on one row.
        loopPauseAllButton = transportPill("\u23f8  Pause all");
        loopPauseAllButton.setOnClickListener(v -> {
            boolean running = false;
            for (int t = 0; t < 4; t++) {
                int st = engine.loopState(t) & 0xF;
                if (st == 2 || st == 3) { running = true; break; }
            }
            engine.loopGlobal(running ? 1 : 2);
        });
        // One pill picks the pad sound: drum kit in Drums mode, GM sound in Keys mode.
        loopKitButton = transportPill("");
        updateLoopSoundPill();
        loopKitButton.setOnClickListener(v -> {
            if (loopPadsKeys) loopKeysSoundDialog(); else loopKitDialog();
        });
        // Metronome keeps loop takes in time: tap = on/off, hold = set BPM.
        loopMetroPill = transportPill(metronomeOn ? "♩ " + metronomeBpm : "♩ Off");
        loopMetroPill.setTextColor(metronomeOn ? COLOR_GREEN : COLOR_MUTED);
        loopMetroPill.setOnClickListener(v -> {
            metronomeOn = !metronomeOn;
            engine.setMetronome(metronomeOn, metronomeBpm, timeSigNum);
            if (loopMetroPill != null) {
                loopMetroPill.setText(metronomeOn ? "♩ " + metronomeBpm : "♩ Off");
                loopMetroPill.setTextColor(metronomeOn ? COLOR_GREEN : COLOR_MUTED);
            }
        });
        loopMetroPill.setOnLongClickListener(v -> {
            bpmDialog();
            return true;
        });
        // Pad area switch: drum pads (default) or the mini keyboard.
        final TextView padModePill = transportPill(loopPadsKeys ? "🎹 Keys" : "🥁 Drums");
        // Output sink + capture source + input monitoring choices.
        TextView outBtn = transportPill("\ud83d\udd0a " + currentOutputLabel());
        outBtn.setOnClickListener(v -> audioOutputDialog());
        TextView inBtn = transportPill("\ud83c\udfa4 " + currentInputLabel());
        inBtn.setOnClickListener(v -> audioInputDialog());
        final TextView monBtn = transportPill("Monitor: " + (loopMonitorOn ? "On" : "Off"));
        monBtn.setTextColor(loopMonitorOn ? COLOR_GREEN : COLOR_MUTED);
        monBtn.setOnClickListener(v -> {
            loopMonitorOn = !loopMonitorOn;
            prefs.edit().putBoolean("loop_monitor", loopMonitorOn).apply();
            engine.setLoopMonitor(loopMonitorOn);
            monBtn.setText("Monitor: " + (loopMonitorOn ? "On" : "Off"));
            monBtn.setTextColor(loopMonitorOn ? COLOR_GREEN : COLOR_MUTED);
        });
        // Loops 1-3 instrument source: its own device choice, separate from the mic.
        TextView instBtn = transportPill("🎸 " + currentInstInLabel());
        instBtn.setTextColor(instInType != -2 ? COLOR_GREEN : COLOR_MUTED);
        instBtn.setOnClickListener(v -> instInputDialog());
        TextView pedalBtn = transportPill("Pedals");
        pedalBtn.setOnClickListener(v -> pedalDialog());
        // Keys-mode toggles live on the rail so the keyboard keeps its space.
        final TextView chordPill = transportPill("Chord");
        final TextView slidePill = transportPill(
                loopKeysSlide && loopKeysSlideMono ? "Mono" : "Slide");
        chordPill.setTextColor(loopKeysChord ? COLOR_GREEN : COLOR_MUTED);
        chordPill.setOnClickListener(v -> {
            loopKeysChord = !loopKeysChord;
            prefs.edit().putBoolean("loop_keys_chord", loopKeysChord).apply();
            chordPill.setTextColor(loopKeysChord ? COLOR_GREEN : COLOR_MUTED);
            if (loopKeysView != null) loopKeysView.setChord(loopKeysChord);
            pushLoopKeysGlide();
        });
        // Slide on the looper keys, same legato bend as the piano's Slide chip.
        // Tap cycles Off → Slide → Mono (detached presses cut the last voice).
        slidePill.setTextColor(loopKeysSlide ? COLOR_GREEN : COLOR_MUTED);
        slidePill.setOnClickListener(v -> {
            if (!loopKeysSlide) {
                loopKeysSlide = true;
                loopKeysSlideMono = false;
            } else if (!loopKeysSlideMono) {
                loopKeysSlideMono = true;
                Toast.makeText(this, "Mono Slide — held keys slide, separate presses cut the last note",
                        Toast.LENGTH_SHORT).show();
            } else {
                loopKeysSlide = false;
                loopKeysSlideMono = false;
            }
            prefs.edit().putBoolean("loop_keys_slide", loopKeysSlide)
                    .putBoolean("loop_keys_slide_mono", loopKeysSlideMono).apply();
            slidePill.setText(loopKeysSlide && loopKeysSlideMono ? "Mono" : "Slide");
            slidePill.setTextColor(loopKeysSlide ? COLOR_GREEN : COLOR_MUTED);
            engine.allNotesOff();
            pushLoopKeysGlide();
            if (loopKeysSlide && loopKeysChord) {
                Toast.makeText(this, "Slide plays while Chord is off",
                        Toast.LENGTH_SHORT).show();
            }
        });
        final TextView splitPill = transportPill("Split");
        splitPill.setTextColor(loopKeysSplit ? COLOR_GREEN : COLOR_MUTED);
        splitPill.setOnClickListener(v -> {
            loopKeysSplit = !loopKeysSplit;
            prefs.edit().putBoolean("loop_keys_split", loopKeysSplit).apply();
            splitPill.setTextColor(loopKeysSplit ? COLOR_GREEN : COLOR_MUTED);
            if (loopKeysView != null) loopKeysView.setSplit(loopKeysSplit);
            if (loopKeysMelodyNav != null) {
                loopKeysMelodyNav.setVisibility(loopKeysSplit ? View.VISIBLE : View.GONE);
            }
            // Dual lives on the split keyboards: closing the split closes Dual.
            if (!loopKeysSplit && loopDualOn) {
                loopDualOn = false;
            }
            refreshLooperDualAvailability();
            // Split on/off changes how Dual routes (per board vs per key), so
            // always re-apply the routing after a split flip.
            applyLoopDual();
        });
        dualKeysPill = transportPill("Dual");
        refreshLooperDualAvailability();
        dualKeysPill.setOnClickListener(v -> {
            if (!loopDualOn && !loopKeysSplit) {
                Toast.makeText(this, "Turn on Split first — Dual plays Sound 2 on the upper board",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            loopDualOn = !loopDualOn;
            applyLoopDual();
        });
        // Sound 2 sits right of Sound 1 in the grid: tap it to pick the dual
        // sound directly (no long-press anywhere). Dim until Dual is on.
        loopSound2Pill = transportPill("Sound 2  ▾");
        loopSound2Pill.setOnClickListener(v -> {
            if (!loopDualOn) {
                Toast.makeText(this, "Turn on Dual first — Sound 2 plays with it",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            dualSoundDialog();
        });
        // Restore consistency on entry: Dual without Split isn't a valid state.
        if (loopDualOn && !loopKeysSplit) {
            loopDualOn = false;
            applyLoopDual();
        }
        padModePill.setOnClickListener(v -> {
            loopPadsKeys = !loopPadsKeys;
            prefs.edit().putBoolean("loop_pads_keys", loopPadsKeys).apply();
            padModePill.setText(loopPadsKeys ? "🎹 Keys" : "🥁 Drums");
            if (!loopPadsKeys) {
                engine.allNotesOff();
            }
            updateLoopSoundPill();
            buildLoopPadArea();
        });

        // ONE grid with every looper control together — keys performance toggles
        // (Split · Dual · Sound 2 · Chord · Slide) and the common controls, in a
        // single block, never in two separate places. Keys controls come first
        // and are always mounted, even in Drums mode.
        controlsHost.addView(pillGrid(2,
                splitPill, dualKeysPill,
                loopSound2Pill, chordPill,
                slidePill, padModePill,
                loopPauseAllButton, loopKitButton,
                loopMetroPill, pedalBtn,
                outBtn, inBtn,
                monBtn, instBtn), matchWrap());
        updateLoopSoundPill();   // now that Sound 2 exists, give it its label/color

        // Pad area to jam into the loops: drum pads or the mini keyboard.
        loopPadHost = new LinearLayout(this);
        loopPadHost.setOrientation(LinearLayout.VERTICAL);
        pane.addView(loopPadHost, topMargin(weight(1.0f), 10));
        buildLoopPadArea();

        content.addView(railScroll(rail), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 4.2f));
        LinearLayout.LayoutParams paneLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 7.8f);
        paneLp.leftMargin = dp(14);
        content.addView(pane, paneLp);

        screen.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        enablePadInsets(screen, content);
        paintStage(screen);
        setContentView(screen);
        handler.removeCallbacks(loopPump);
        handler.post(loopPump);
    }

    private void startLoopEngine() {
        engine.setCustomDrum(false);
        engine.setDrumKit(loopKitProgram != -1 ? loopKitProgram
                : TonePreset.defaultFor(InstrumentMode.DRUMS).program);
        engine.setDrumRemap(loopKitRemap);
        pushCymbalGains();
        applyLoopKeysSound();
        engine.setLoopInstDevice(resolveInstInput());
        engine.startLoopMix(resolvePreferredInput(-1), resolvePreferredOutput(-1));
        engine.setHarmonizer(harmonizerOn);
        applyHarmonizerParams();
        engine.setAutotune(harmAutotune);
        engine.setLoopMonitor(loopMonitorOn);
        engine.setMetronome(metronomeOn, metronomeBpm, timeSigNum);
        applyLoopRecBars();
    }

    // Instrument line-in for loops 1-3: its own device, separate from the mic.
    private int resolveInstInput() {
        if (instInType == -2) {
            return -1;
        }
        android.media.AudioDeviceInfo device = router.inputMatching(instInType, instInName);
        return device != null ? device.getId() : -1;
    }

    private String currentInstInLabel() {
        if (instInType == -2) {
            return "Off";
        }
        android.media.AudioDeviceInfo device = router.inputMatching(instInType, instInName);
        return device != null ? router.inputOptionLabel(device) : "Off (unplugged)";
    }

    private void selectInstInput(int type, String name) {
        instInType = type;
        instInName = name == null ? "" : name;
        prefs.edit().putInt("inst_in_type", instInType)
                .putString("inst_in_name", instInName).apply();
        if (onLoopMix) {
            engine.stop();
            startLoopEngine();
        }
        Toast.makeText(this, "Instrument: " + currentInstInLabel(), Toast.LENGTH_SHORT).show();
    }

    private void instInputDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("Instrument Input");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("What loops 1–3 record along with the pads. Line inputs only — the internal mic stays on vocals.");
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(12);
        content.addView(hint, topMargin(matchWrap(), 4));

        boolean offActive = instInType == -2;
        TextView off = menuItem((offActive ? "●  " : "○  ") + "Off (pads only)", () -> {
            selectInstInput(-2, "");
            dialog.dismiss();
        });
        if (offActive) off.setTextColor(COLOR_GREEN);
        content.addView(off, topMargin(matchWrap(), 12));

        int listed = 0;
        for (android.media.AudioDeviceInfo device : router.inputOptions()) {
            final int type = device.getType();
            if (type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC) continue;
            final boolean usb = router.isUsbType(type);
            final String name = usb ? router.productNameOf(device) : "";
            boolean active = type == instInType
                    && (!usb || instInName.isEmpty() || instInName.equals(name));
            TextView item = menuItem((active ? "●  " : "○  ") + router.inputOptionLabel(device), () -> {
                selectInstInput(type, name);
                dialog.dismiss();
            });
            if (active) item.setTextColor(COLOR_GREEN);
            content.addView(item, topMargin(matchWrap(), 8));
            listed++;
        }
        if (listed == 0) {
            TextView none = new TextView(this);
            none.setText("No line inputs detected (plug in USB-C or 3.5mm).");
            none.setTextColor(COLOR_MUTED);
            none.setTextSize(13);
            content.addView(none, topMargin(matchWrap(), 10));
        }

        presentMenu(dialog, content, dialogWidth(0.85f, 480));
    }

    // A bound loop pedal can stop takes hands-free, so it overrides the
    // bar auto-stop (per the pedal-first workflow).
    private void applyLoopRecBars() {
        for (int t = 1; t <= 3; t++) {
            engine.setLoopRecBars(t, pedalBind[t] >= 0 ? 0 : loopRecBars[t]);
        }
    }

    private String loopBarsLabel(int track) {
        int b = loopRecBars[track];
        return "⏱  Auto-stop: " + (b <= 0 ? "Off" : b + (b == 1 ? " bar" : " bars"));
    }

    // Pedal-style voicing table (Harmonier layout): 3rd = 4 semitones,
    // 5th = 7, octave = 12; two-voice combos from mode 7 up.
    private static final float[][] HARM_MODES = {
            {0.15f, 99f},   // 1: default (tight double)
            {-7f, 99f},     // 2: 5th lower
            {-4f, 99f},     // 3: 3rd lower
            {4f, 99f},      // 4: 3rd higher
            {7f, 99f},      // 5: 5th higher
            {12f, 99f},     // 6: oct+
            {-12f, 12f},    // 7: oct- & oct+
            {-7f, 4f},      // 8: 5th lower & 3rd higher
            {-7f, 7f},      // 9: 5th lower & 5th higher
            {-4f, 4f},      // 10: 3rd lower & 3rd higher
            {-4f, 7f},      // 11: 3rd lower & 5th higher
    };
    private static final String[] HARM_MODE_NAMES = {
            "default", "5th lower", "3rd lower", "3rd higher", "5th higher",
            "oct+", "oct- & oct+", "5th low & 3rd high", "5th low & 5th high",
            "3rd low & 3rd high", "3rd low & 5th high"};

    // Key is a literal semitone transpose of the harmony: 0..5 = +0..+5 st.
    private static final int[] HARM_KEY_OFFSETS = {0, 1, 2, 3, 4, 5};
    private static final String[] HARM_KEY_NAMES = {
            "off", "+1 st", "+2 st", "+3 st", "+4 st", "+5 st"};

    private void applyHarmonizerParams() {
        int m = Math.max(1, Math.min(11, harmMode)) - 1;
        int k = Math.max(0, Math.min(5, harmKey));
        float off = HARM_KEY_OFFSETS[k] + (harmSharp ? 1f : 0f);
        float s1 = HARM_MODES[m][0] + off;
        float s2 = HARM_MODES[m][1] < 90f ? HARM_MODES[m][1] + off : 99f;
        boolean choir = m == 0;   // the double gets the detuned pair for thickness
        engine.setHarmonizerParams(s1, s2, choir, harmLevel / 100f, harmTone, harmReverb);
    }

    private void saveHarmonizerPrefs() {
        prefs.edit().putInt("harm_mode", harmMode).putInt("harm_key", harmKey)
                .putBoolean("harm_sharp", harmSharp).putInt("harm_tone", harmTone)
                .putBoolean("harm_rev", harmReverb).putInt("harm_level", harmLevel)
                .putBoolean("harm_tune", harmAutotune).apply();
    }

    private String loopKitName() {
        int program = loopKitProgram != -1 ? loopKitProgram
                : TonePreset.defaultFor(InstrumentMode.DRUMS).program;
        for (TonePreset preset : TonePreset.forMode(InstrumentMode.DRUMS)) {
            if (preset.program == program && drumRemapFor(preset) == loopKitRemap) {
                return preset.label;
            }
        }
        return "Default";
    }

    private void loopKitDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));
        TextView title = new TextView(this);
        title.setText("Drum Kit");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        final EditText search = new EditText(this);
        searchIme(search);
        search.setHint("Search kits");
        search.setHintTextColor(COLOR_DIM);
        search.setTextColor(COLOR_TEXT);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        search.setBackground(panelBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        content.addView(search, topMargin(matchWrap(), 10));

        final ScrollView sv = new ScrollView(this);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        content.addView(sv, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT), 6));

        populateLoopKitList(list, "", dialog);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                populateLoopKitList(list, s.toString(), dialog);
            }
        });

        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.85f, 460), LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        // Open at the current kit, not the top of the list.
        sv.post(() -> {
            if (pickerSelectedRow != null) {
                sv.scrollTo(0, Math.max(0, pickerSelectedRow.getTop() - dp(72)));
            }
        });
    }

    private void populateLoopKitList(LinearLayout list, String filter, final Dialog dialog) {
        list.removeAllViews();
        pickerSelectedRow = null;
        String f = filter.trim().toLowerCase(Locale.US);
        int current = loopKitProgram != -1 ? loopKitProgram
                : TonePreset.defaultFor(InstrumentMode.DRUMS).program;
        for (final TonePreset preset : TonePreset.forMode(InstrumentMode.DRUMS)) {
            if (!f.isEmpty()
                    && !preset.label.toLowerCase(Locale.US).contains(f)
                    && !preset.detail.toLowerCase(Locale.US).contains(f)
                    && !preset.category.toLowerCase(Locale.US).contains(f)) {
                continue;
            }
            boolean active = preset.program == current && drumRemapFor(preset) == loopKitRemap;
            TextView item = menuItem((active ? "\u25cf  " : "\u25cb  ") + preset.label, () -> {
                loopKitProgram = preset.program;
                loopKitRemap = drumRemapFor(preset);
                prefs.edit().putInt("loop_kit", loopKitProgram)
                        .putInt("loop_kit_remap", loopKitRemap).apply();
                engine.setDrumKit(loopKitProgram);
                engine.setDrumRemap(loopKitRemap);
                if (loopKitButton != null) {
                    loopKitButton.setText("Kit: " + preset.label + "  \u25be");
                }
                dialog.dismiss();
            });
            if (active) {
                item.setTextColor(COLOR_GREEN);
                pickerSelectedRow = item;
            }
            list.addView(item, topMargin(matchWrap(), 8));
        }
        if (list.getChildCount() == 0) {
            list.addView(detailText("No matching kits."), topMargin(matchWrap(), 8));
        }
    }

    // Equal-width pill rows: every button aligned in a fixed grid instead of a
    // horizontally scrolling strip. Long labels ellipsize inside their cell.
    private LinearLayout pillGrid(int cols, TextView... pills) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < pills.length; i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, topMargin(matchWrap(), i == 0 ? 0 : 8));
            }
            pills[i].setSingleLine(true);
            pills[i].setEllipsize(android.text.TextUtils.TruncateAt.END);
            pills[i].setGravity(Gravity.CENTER);
            // WRAP_CONTENT height (not MATCH_PARENT): a partial last row used to
            // collapse to the 1px filler's height and vanish — that was the
            // recurring "missing pills / row 2 gone" bug.
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i % cols != 0) lp.leftMargin = dp(8);
            row.addView(pills[i], lp);
        }
        // Pad a short last row so its pills match the width of full rows.
        int rem = pills.length % cols;
        if (rem != 0 && row != null) {
            for (int i = rem; i < cols; i++) {
                View filler = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.leftMargin = dp(8);
                row.addView(filler, lp);
            }
        }
        return grid;
    }

    private void updateLoopSoundPill() {
        if (loopKitButton == null) return;
        loopKitButton.setText((loopPadsKeys ? "Sound: " + loopKeySoundName()
                : "Kit: " + loopKitName()) + "  ▾");
        if (loopSound2Pill != null) {
            // Always readable — green when live, muted (not near-invisible)
            // when Dual is off, so it never looks "missing".
            loopSound2Pill.setText("Sound 2: " + loopDualPreset.label + "  ▾");
            loopSound2Pill.setTextColor(loopDualOn ? COLOR_GREEN : COLOR_MUTED);
        }
    }

    // Bottom of the looper: drum pads (default) or the mini keyboard. Both play
    // on the same bus, so either one prints into loops 1-3 while recording.
    private void buildLoopPadArea() {
        if (loopPadHost == null) return;
        loopPadHost.removeAllViews();
        if (!loopPadsKeys) {
            loopKeysView = null;
            loopKeysRangeLabel = null;
            loopKeysMelodyNav = null;
            loopKeysMelodyLabel = null;
            drumPadsView = new DrumPadsView(this);
            drumPadsView.setAccent(COLOR_TEAL);
            drumPadsView.setListener(this::onDrumPad);
            drumPadsView.setHoldListener(this::cymbalVolumeSlider);
            drumPadsView.setChokeListener(engine::chokeCymbals);
            drumPadsView.setFullVelocity(true);   // looper pads: every hit full strength
            drumPadsView.setCompact(true);   // Loop Mix: just kick / snare / closed hat / ride
            loopPadHost.addView(drumPadsView, weight(1.0f));
            return;
        }
        drumPadsView = null;
        // Split mode: the melody (upper) keyboard gets its own ◀ ▶ row, so the
        // two halves move separately. Hidden while Split is off.
        loopKeysMelodyNav = new LinearLayout(this);
        loopKeysMelodyNav.setOrientation(LinearLayout.HORIZONTAL);
        loopKeysMelodyNav.setGravity(Gravity.CENTER_VERTICAL);
        TextView mLeft = transportPill("◀");
        mLeft.setOnClickListener(v -> shiftLoopKeysMelody(-1));
        mLeft.setOnLongClickListener(v -> { shiftLoopKeysMelody(-7); return true; });
        loopKeysMelodyNav.addView(mLeft, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        loopKeysMelodyLabel = new TextView(this);
        loopKeysMelodyLabel.setTextColor(COLOR_MUTED);
        loopKeysMelodyLabel.setTextSize(13);
        loopKeysMelodyLabel.setGravity(Gravity.CENTER);
        loopKeysMelodyNav.addView(loopKeysMelodyLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView mRight = transportPill("▶");
        mRight.setOnClickListener(v -> shiftLoopKeysMelody(1));
        mRight.setOnLongClickListener(v -> { shiftLoopKeysMelody(7); return true; });
        loopKeysMelodyNav.addView(mRight, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        loopKeysMelodyNav.setVisibility(loopKeysSplit ? View.VISIBLE : View.GONE);
        loopPadHost.addView(loopKeysMelodyNav, matchWrap());
        // Nav row: ◀ / ▶ walk ONE key at a time (A1 → B1 → C1 → ...), gliding
        // gently; hold the button to jump a whole octave.
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        TextView left = transportPill("◀");
        left.setOnClickListener(v -> shiftLoopKeys(-1));
        left.setOnLongClickListener(v -> { shiftLoopKeys(-7); return true; });
        nav.addView(left, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        loopKeysRangeLabel = new TextView(this);
        loopKeysRangeLabel.setTextColor(COLOR_MUTED);
        loopKeysRangeLabel.setTextSize(13);
        loopKeysRangeLabel.setGravity(Gravity.CENTER);
        nav.addView(loopKeysRangeLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        // Chord / Split / Dual toggles live on the left rail.
        TextView right = transportPill("▶");
        right.setOnClickListener(v -> shiftLoopKeys(1));
        right.setOnLongClickListener(v -> { shiftLoopKeys(7); return true; });
        nav.addView(right, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        loopPadHost.addView(nav, matchWrap());
        loopKeysView = new LoopKeysView(this);
        loopKeysView.setAccent(COLOR_TEAL);
        loopKeysView.setChord(loopKeysChord);
        loopKeysView.setSplit(loopKeysSplit);
        boolean boards = loopDualOn && loopKeysSplit;
        loopKeysView.setDualSplit(loopDualOn && !boards ? loopDualSplit : -1);
        loopKeysView.setDualSeparate(boards);
        loopPadHost.addView(loopKeysView, topMargin(weight(1.0f), 8));
        // Note bender under the keys: drag to bend, springs back to center.
        PitchBendView bend = new PitchBendView(this);
        bend.setListener(v -> engine.setLoopKeysBend(8192 + Math.round(v * 8191)));
        loopPadHost.addView(bendRow(bend), topMargin(matchWrap(), 6));
        applyLoopKeysRange();
    }

    private static boolean isWhiteKey(int note) {
        int pc = note % 12;
        return pc == 0 || pc == 2 || pc == 4 || pc == 5 || pc == 7 || pc == 9 || pc == 11;
    }

    // n-th white key from A0=21 (0-based; 51 = C8).
    private static int whiteNoteAt(int index) {
        int n = 21;
        while (index > 0) {
            n++;
            if (isWhiteKey(n)) index--;
        }
        return n;
    }

    private static int whiteIndexOf(int note) {
        int idx = 0;
        for (int n = 22; n <= note; n++) {
            if (isWhiteKey(n)) idx++;
        }
        return idx;
    }

    // Slide the window by whole white keys: tap = 1 (A1 → B1 → C1...), hold = 7.
    private void shiftLoopKeys(int whiteSteps) {
        int idx = whiteIndexOf(loopKeysBase) + whiteSteps;
        loopKeysBase = whiteNoteAt(Math.max(0, Math.min(idx, 44)));
        applyLoopKeysRange();
    }

    // Split mode: the melody (upper) keyboard steps independently.
    private void shiftLoopKeysMelody(int whiteSteps) {
        int idx = whiteIndexOf(loopKeysMelodyBase) + whiteSteps;
        loopKeysMelodyBase = whiteNoteAt(Math.max(0, Math.min(idx, 44)));
        applyLoopKeysRange();
    }

    // Octave-wide windows (8 whites) starting on ANY white key — D3-D4 is fine.
    private void applyLoopKeysRange() {
        int idx = Math.max(0, Math.min(whiteIndexOf(loopKeysBase), 44));
        loopKeysBase = whiteNoteAt(idx);
        int mIdx = Math.max(0, Math.min(whiteIndexOf(loopKeysMelodyBase), 44));
        loopKeysMelodyBase = whiteNoteAt(mIdx);
        prefs.edit().putInt("loop_keys_base", loopKeysBase)
                .putInt("loop_keys_mel_base", loopKeysMelodyBase).apply();
        if (loopKeysView != null) {
            loopKeysView.setRange(loopKeysBase, 8);
            loopKeysView.setMelodyRange(loopKeysMelodyBase);
        }
        updateLoopKeysRangeLabel();
    }

    private String dualLabel() {
        return "Dual @" + noteName(dualSplit);
    }

    // Fold program for Sound 2: its GM program, or 128 = font-based (full range).
    private int dualFoldProgram() {
        return activeExternalDualUri != null
                || dualPreset.asset != null || pianoFontSlot(dualPreset) != -1
                ? 128 : pianoProgram(dualPreset);
    }
    private int loopDualFoldProgram() {
        return loopDualPreset.asset != null || pianoFontSlot(loopDualPreset) != -1
                ? 128 : pianoProgram(loopDualPreset);
    }

    // Route Sound 2 to its font: preloaded HQ slot, lazy-loaded asset (slot 5),
    // or the GM channel-1 program (slot -1).
    private String loadedDualAsset;

    private void applyDualFontRouting() {
        if (dualOn && activeExternalDualUri != null) {
            if (activeExternalDualUri.equals(loadedExternalDualUri)) {
                engine.setHqFontPreset(EXTERNAL_DUAL_SLOT, activeExternalDualPreset);
                engine.setDualFontSlot(EXTERNAL_DUAL_SLOT);
            } else {
                engine.setDualFontSlot(-1);
                loadExternalSf2(findExternalSf2(activeExternalDualUri), true);
            }
        } else if (dualOn && dualPreset.asset != null
                && pianoFontSlot(dualPreset) == LAZY_PIANO_SLOT) {
            loadDualFont(dualPreset);
        } else {
            engine.setDualFontSlot(dualOn ? pianoFontSlot(dualPreset) : -1);   // preloaded slot (incl. Steinway)
        }
    }

    private void loadDualFont(final TonePreset preset) {
        final String asset = preset.asset;
        if (asset.equals(loadedDualAsset)) {
            engine.setDualFontSlot(DUAL_LAZY_SLOT);
            return;
        }
        engine.setDualFontSlot(-1);   // GM stands in while the font loads
        final float gainDb = libraryPianoGain(asset);
        new Thread(() -> {
            byte[] data = readAsset(asset);
            final boolean ok = data != null && engine.loadHqFont(DUAL_LAZY_SLOT, 0, gainDb, data);
            handler.post(() -> {
                if (ok) {
                    loadedDualAsset = asset;
                }
                if (dualOn && activeExternalDualUri == null
                        && preset == dualPreset && ok) {
                    engine.setDualFontSlot(DUAL_LAZY_SLOT);
                }
            });
        }, "dual-lib-loader").start();
    }

    // Bender strip + its range pill (±N semitones, tap to set 1..24).
    private View bendRow(PitchBendView bend) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(bend, new LinearLayout.LayoutParams(0, dp(38), 1f));
        final TextView range = transportPill("±" + bendRange);
        range.setOnClickListener(v -> bendRangeDialog(range));
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rLp.leftMargin = dp(8);
        row.addView(range, rLp);
        return row;
    }

    // Softness "Tone" knob: 0% = fully bright/open, 100% = dark. Tames a lead.
    private void toneKnobDialog(final TextView pill) {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(dialogSheet());
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(16));

        TextView title = new TextView(this);
        title.setText("Tone · Softness");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        TextView sub = new TextView(this);
        sub.setText("Rolls off the highs — up = darker/softer, down = brighter.");
        sub.setTextColor(COLOR_DIM);
        sub.setTextSize(12);
        sub.setGravity(Gravity.CENTER);
        content.addView(sub, topMargin(matchWrap(), 6));

        final TextView pct = new TextView(this);
        pct.setTextColor(COLOR_MUTED);
        pct.setTextSize(13);
        pct.setGravity(Gravity.CENTER);
        pct.setText(Math.round(fpSoft * 100) + "%");

        KnobView knob = new KnobView(this);
        knob.setAccent(COLOR_AMBER);
        knob.setValue(fpSoft);
        knob.setOnChange(v -> {
            fpSoft = v;
            engine.setPianoSoft(fpSoft);
            pct.setText(Math.round(fpSoft * 100) + "%");
            if (pill != null) {
                pill.setText("Tone " + Math.round(fpSoft * 100) + "%");
                styleTogglePill(pill, fpSoft > 0f);
            }
            prefs.edit().putFloat("fp_soft", fpSoft).apply();
        });
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(dp(96), dp(96));
        klp.topMargin = dp(12);
        klp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(knob, klp);
        content.addView(pct, topMargin(matchWrap(), 8));

        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.7f, 360), LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void bendRangeDialog(final TextView pill) {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));
        TextView title = new TextView(this);
        title.setText("Bend Range");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());
        final TextView label = new TextView(this);
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(14);
        label.setText(bendRange + " semitone" + (bendRange == 1 ? "" : "s") + " at full throw");
        content.addView(label, topMargin(matchWrap(), 10));
        SeekBar sb = new SeekBar(this);
        sb.setMax(23);
        sb.setProgress(bendRange - 1);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                bendRange = 1 + p;
                label.setText(bendRange + " semitone" + (bendRange == 1 ? "" : "s") + " at full throw");
                if (pill != null) pill.setText("±" + bendRange);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt("bend_range", bendRange).apply();
                engine.setBendRange(bendRange);
            }
        });
        content.addView(sb, topMargin(matchWrap(), 4));
        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.8f, 420), LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    // Push + persist the dual-sound setup wherever keys are currently live.
    // Push the extra-layer state to the engine: Sound 2 (ch1) level, and the two
    // GM layers (ch2/ch3) programs + levels. A layer set to Off (program -1) or
    // level 0 is silent. Layer 1 is always the main sound at full level.
    // Resolve a layer preset name to its GM program (the voice a layer renders),
    // or -1 when off / unknown.
    private int layerGm(String presetName) {
        if (presetName == null) return -1;
        if (isExternalLayerPreset(presetName)) return 0;
        try {
            TonePreset p = TonePreset.valueOf(presetName);
            return p.program >= 0 && p.program < 128 ? p.program : 0;
        } catch (RuntimeException e) { return -1; }
    }
    private String layerLabel(String presetName) {
        if (presetName == null) return "Off";
        if (isExternalLayerPreset(presetName)) {
            String uri = presetName.substring(EXTERNAL_LAYER_PREFIX.length());
            return externalSf2Name(uri);
        }
        try { return TonePreset.valueOf(presetName).label; } catch (RuntimeException e) { return "Off"; }
    }

    private static final String EXTERNAL_LAYER_PREFIX = "sf2:";

    private static boolean isExternalLayerPreset(String value) {
        return value != null && value.startsWith(EXTERNAL_LAYER_PREFIX);
    }

    private String externalLayerUri(int layer) {
        String preset = layerPreset(layer);
        return isExternalLayerPreset(preset)
                ? preset.substring(EXTERNAL_LAYER_PREFIX.length()) : null;
    }

    private static void putOrRemove(SharedPreferences.Editor e, String key, String val) {
        if (val == null) e.remove(key); else e.putString(key, val);
    }

    // Preset name for an added-layer slot (2/3/4 = Keyboard A, 6/7/8 = Keyboard B).
    private String layerPreset(int layer) {
        switch (layer) {
            case 2: return layer2Preset;
            case 3: return layer3Preset;
            case 4: return layer4Preset;
            case 6: return layer6Preset;
            case 7: return layer7Preset;
            case 8: return layer8Preset;
            default: return null;
        }
    }
    // A layer just given a sound should be heard: if its fader is at 0, lift it
    // to full so layers start equal and audible (the fader then trims from there).
    private void ensureLayerAudible(int layer) {
        switch (layer) {
            case 2: if (layer2Level <= 0.001f) layer2Level = 1.0f; break;
            case 3: if (layer3Level <= 0.001f) layer3Level = 1.0f; break;
            case 4: if (layer4Level <= 0.001f) layer4Level = 1.0f; break;
            case 6: if (layer6Level <= 0.001f) layer6Level = 1.0f; break;
            case 7: if (layer7Level <= 0.001f) layer7Level = 1.0f; break;
            case 8: if (layer8Level <= 0.001f) layer8Level = 1.0f; break;
        }
    }
    private void setLayerPreset(int layer, String name) {
        switch (layer) {
            case 2: layer2Preset = name; break;
            case 3: layer3Preset = name; break;
            case 4: layer4Preset = name; break;
            case 6: layer6Preset = name; break;
            case 7: layer7Preset = name; break;
            case 8: layer8Preset = name; break;
        }
    }

    // layer number (1..8) -> physical engine channel (0..7). Index 0 unused.
    private static final int[] LAYER_CH = {-1, 0, 2, 3, 4, 1, 5, 6, 7};

    private void applyLayers() {
        boolean on = layerMode;
        if (on && activeExternalMainUri != null
                && !activeExternalMainUri.equals(loadedExternalMainUri)) {
            loadExternalSf2(findExternalSf2(activeExternalMainUri), false);
        }
        if (on && activeExternalDualUri != null
                && !activeExternalDualUri.equals(loadedExternalDualUri)) {
            loadExternalSf2(findExternalSf2(activeExternalDualUri), true);
        }
        for (int layer : new int[]{2, 3, 4, 6, 7, 8}) {
            applyExternalLayerFont(layer, on);
        }
        // Keyboard A adds (L2/L3/L4) on gm ch2/3/4; Keyboard B adds (L6/L7/L8) on
        // gm ch5/6/7. Masters: L1 = main (ch0), L5 = Sound 2 (ch1).
        int l2 = layerGm(layer2Preset), l3 = layerGm(layer3Preset), l4 = layerGm(layer4Preset);
        int l6 = layerGm(layer6Preset), l7 = layerGm(layer7Preset), l8 = layerGm(layer8Preset);
        engine.setLayer3(on ? l2 : -1);
        engine.setLayer4(on ? l3 : -1);
        engine.setLayer5(on ? l4 : -1);
        engine.setLayer6(on ? l6 : -1);
        engine.setLayer7(on ? l7 : -1);
        engine.setLayer8(on ? l8 : -1);
        engine.setLayerVolume(1, on ? layer1Level : 1.0f);            // L1 A master (ch0)
        engine.setLayerVolume(2, on ? layer5Level : 1.0f);            // L5 B master / Sound 2 (ch1)
        engine.setLayerVolume(3, on && l2 >= 0 ? layer2Level : 0f);   // L2 (ch2)
        engine.setLayerVolume(4, on && l3 >= 0 ? layer3Level : 0f);   // L3 (ch3)
        engine.setLayerVolume(5, on && l4 >= 0 ? layer4Level : 0f);   // L4 (ch4)
        engine.setLayerVolume(6, on && l6 >= 0 ? layer6Level : 0f);   // L6 (ch5)
        engine.setLayerVolume(7, on && l7 >= 0 ? layer7Level : 0f);   // L7 (ch6)
        engine.setLayerVolume(8, on && l8 >= 0 ? layer8Level : 0f);   // L8 (ch7)
        // Attack-zap per layer -> physical channel. layer 1..8 map to channels
        // {0,2,3,4,1,5,6,7} (same order applyLayers uses for volume).
        int[] activeProg = {1, l2, l3, l4, 1, l6, l7, l8};   // layer1/5 masters always on
        for (int i = 1; i <= 8; i++) {
            int chnl = LAYER_CH[i];
            boolean live = on && activeProg[i - 1] >= 0;
            engine.setLayerZap(chnl, live ? layerZap[i] : 0);
        }
        // Blend routing: off / single (A only) / key-split (low=A,high=B) /
        // two-manual (chord manual=A, melody manual=B).
        if (!on) {
            engine.setLayerBlend(0, -1);
        } else if (onFullPiano && fullPianoSplit) {
            engine.setLayerBlend(3, -1);   // two boards: noteOn = A (Sound 1), note2On = B (Sound 2)
        } else {
            engine.setLayerBlend(1, -1);
        }
    }

    private void saveLayers() {
        SharedPreferences.Editor e = prefs.edit()
                .putFloat("layer1_lvl", layer1Level)
                .putFloat("layer2_lvl", layer2Level)
                .putFloat("layer3_lvl", layer3Level)
                .putFloat("layer4_lvl", layer4Level)
                .putFloat("layer5_lvl", layer5Level)
                .putFloat("layer6_lvl", layer6Level)
                .putFloat("layer7_lvl", layer7Level)
                .putFloat("layer8_lvl", layer8Level);
        putOrRemove(e, "layer2_preset", layer2Preset);
        putOrRemove(e, "layer3_preset", layer3Preset);
        putOrRemove(e, "layer4_preset", layer4Preset);
        putOrRemove(e, "layer6_preset", layer6Preset);
        putOrRemove(e, "layer7_preset", layer7Preset);
        putOrRemove(e, "layer8_preset", layer8Preset);
        for (int i = 1; i <= 8; i++) e.putInt("layer" + i + "_zap", layerZap[i]);
        e.apply();
    }

    // The layer-mix sheet: Layer 1 (main, fixed) + Sound 2 + Layer 3 + Layer 4,
    // each with a level fader. Turning a fader off leaves just the piano.
    private void layersDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(dialogSheet());
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("Layers");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        // Layer Mode master toggle — independent of Split. On = the layers blend
        // (Keyboard A across the keys, or A + B when a Split is active). It does
        // NOT turn Split on/off.
        TextView modeToggle = new TextView(this);
        modeToggle.setText(layerMode ? "◉  Layer Mode: ON" : "○  Layer Mode: OFF");
        modeToggle.setTextColor(layerMode ? COLOR_GREEN : COLOR_MUTED);
        modeToggle.setTextSize(15);
        modeToggle.setGravity(Gravity.CENTER);
        modeToggle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        modeToggle.setClickable(true);
        modeToggle.setPadding(dp(12), dp(12), dp(12), dp(12));
        modeToggle.setBackground(moduleBackground(
                layerMode ? darken(COLOR_GREEN) : COLOR_SURFACE_RAISED,
                layerMode ? COLOR_GREEN : COLOR_BORDER, COLOR_GREEN, true));
        modeToggle.setOnClickListener(v -> {
            // Toggle the blend only — never change the Split state. Layer Mode
            // solo just stacks Keyboard A's layers across the whole keyboard.
            layerMode = !layerMode;
            prefs.edit().putBoolean("layer_mode", layerMode).apply();
            applyPianoProgram();                // refreshes routing + the layer blend
            dialog.dismiss();
            if (onFullPiano) showFullPiano();   // refresh the Layers pill highlight
            layersDialog();
        });
        content.addView(modeToggle, topMargin(matchWrap(), 12));

        boolean splitOn = onFullPiano && fullPianoSplit;
        TextView sub = new TextView(this);
        sub.setText(layerMode
                ? (splitOn ? "Two keyboards, 4 layers each. ★ = master sound."
                           : "Keyboard A blends across the keys. Split to use Keyboard B.")
                : "Turn Layer Mode on to blend these layers.");
        sub.setTextColor(COLOR_DIM);
        sub.setTextSize(12);
        sub.setGravity(Gravity.CENTER);
        content.addView(sub, topMargin(matchWrap(), 8));

        // Keyboard A (layers 1-4): master L1 = Sound 1, adds L2/L3/L4.
        content.addView(layerRowLabel("KEYBOARD A", COLOR_TEAL), topMargin(matchWrap(), 12));
        LinearLayout colsA = new LinearLayout(this);
        colsA.setOrientation(LinearLayout.HORIZONTAL);
        colsA.addView(layerKnobColumn(dialog, 1, "1★",
                () -> pianoSoundName(false),
                () -> { dialog.dismiss(); pianoSoundPopup(false, this::layersDialog); },
                layer1Level, v -> { layer1Level = v; }));
        colsA.addView(layerKnobColumn(dialog, 2, "2",
                () -> layerLabel(layer2Preset),
                () -> { dialog.dismiss(); layerSoundPicker(2); },
                layer2Level, v -> { layer2Level = v; }));
        colsA.addView(layerKnobColumn(dialog, 3, "3",
                () -> layerLabel(layer3Preset),
                () -> { dialog.dismiss(); layerSoundPicker(3); },
                layer3Level, v -> { layer3Level = v; }));
        colsA.addView(layerKnobColumn(dialog, 4, "4",
                () -> layerLabel(layer4Preset),
                () -> { dialog.dismiss(); layerSoundPicker(4); },
                layer4Level, v -> { layer4Level = v; }));
        content.addView(colsA, topMargin(matchWrap(), 4));

        // Keyboard B (layers 5-8): master L5 = Sound 2, adds L6/L7/L8. Only sounds
        // when a split is active — labelled so when it's not.
        content.addView(layerRowLabel(splitOn ? "KEYBOARD B" : "KEYBOARD B · needs Split",
                COLOR_PURPLE), topMargin(matchWrap(), 14));
        LinearLayout colsB = new LinearLayout(this);
        colsB.setOrientation(LinearLayout.HORIZONTAL);
        colsB.addView(layerKnobColumn(dialog, 5, "5★",
                () -> dualOn ? pianoSoundName(true) : "Off",
                () -> { dialog.dismiss(); pianoSoundPopup(true, this::layersDialog); },
                layer5Level, v -> { layer5Level = v; }));
        colsB.addView(layerKnobColumn(dialog, 6, "6",
                () -> layerLabel(layer6Preset),
                () -> { dialog.dismiss(); layerSoundPicker(6); },
                layer6Level, v -> { layer6Level = v; }));
        colsB.addView(layerKnobColumn(dialog, 7, "7",
                () -> layerLabel(layer7Preset),
                () -> { dialog.dismiss(); layerSoundPicker(7); },
                layer7Level, v -> { layer7Level = v; }));
        colsB.addView(layerKnobColumn(dialog, 8, "8",
                () -> layerLabel(layer8Preset),
                () -> { dialog.dismiss(); layerSoundPicker(8); },
                layer8Level, v -> { layer8Level = v; }));
        content.addView(colsB, topMargin(matchWrap(), 4));

        presentMenu(dialog, content, dialogWidth(0.94f, 560));
    }

    private void applyZapChip(TextView zap, int depth) {
        boolean on = depth > 0;
        zap.setText(depth == 0 ? "⚡ off" : depth >= 24 ? "⚡ zap+" : "⚡ zap");
        zap.setTextColor(on ? COLOR_TEXT : COLOR_DIM);
        zap.setBackground(moduleBackground(on ? darken(COLOR_AMBER) : COLOR_SURFACE_RAISED,
                on ? COLOR_AMBER : COLOR_BORDER, COLOR_AMBER, true));
    }

    private View layerRowLabel(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(12);
        t.setLetterSpacing(0.06f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    // One layer column: number, tappable sound name, a knob, and a % readout.
    private View layerKnobColumn(final Dialog dialog, final int layerNum, String num,
            java.util.concurrent.Callable<String> label, Runnable onPick,
            float level, FloatSetter setField) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(4), dp(8), dp(4), dp(8));
        col.setBackground(moduleBackground(COLOR_SURFACE_RAISED, COLOR_BORDER, COLOR_TEAL, true));

        TextView n = new TextView(this);
        n.setText(num);
        n.setTextColor(COLOR_TEAL);
        n.setTextSize(14);
        n.setGravity(Gravity.CENTER);
        n.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        col.addView(n, matchWrap());

        TextView name = new TextView(this);
        try { name.setText(label.call()); } catch (Exception e) { name.setText(""); }
        name.setTextColor(COLOR_TEXT);
        name.setTextSize(11);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setClickable(true);
        name.setPadding(dp(2), dp(4), dp(2), dp(4));
        name.setOnClickListener(v -> onPick.run());
        col.addView(name, matchWrap());

        final TextView pct = new TextView(this);
        pct.setTextColor(COLOR_MUTED);
        pct.setTextSize(11);
        pct.setGravity(Gravity.CENTER);
        pct.setText(Math.round(level * 100) + "%");

        KnobView knob = new KnobView(this);
        knob.setAccent(COLOR_TEAL);
        knob.setValue(level);
        knob.setOnChange(v -> {
            setField.set(v);
            pct.setText(Math.round(v * 100) + "%");
            saveLayers();
            applyLayers();   // respects Layer Mode
        });
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(dp(56), dp(56));
        klp.topMargin = dp(6);
        klp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(knob, klp);
        col.addView(pct, topMargin(matchWrap(), 4));

        // Attack "zap": cycles Off -> Zap -> Zap+ (semitone drop on note attack).
        final TextView zap = new TextView(this);
        zap.setTextSize(10);
        zap.setGravity(Gravity.CENTER);
        zap.setPadding(dp(8), dp(3), dp(8), dp(3));
        zap.setClickable(true);
        applyZapChip(zap, layerZap[layerNum]);
        zap.setOnClickListener(v -> {
            int cur = 0;
            for (int i = 0; i < ZAP_STEPS.length; i++) if (ZAP_STEPS[i] == layerZap[layerNum]) cur = i;
            layerZap[layerNum] = ZAP_STEPS[(cur + 1) % ZAP_STEPS.length];
            applyZapChip(zap, layerZap[layerNum]);
            saveLayers();
            applyLayers();
        });
        col.addView(zap, topMargin(matchWrap(), 6));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        col.setLayoutParams(lp);
        return col;
    }

    private View layerRowFixed(String num, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackground(moduleBackground(COLOR_SURFACE_RAISED, COLOR_BORDER, COLOR_AMBER, true));
        TextView n = new TextView(this);
        n.setText(num);
        n.setTextColor(COLOR_AMBER);
        n.setTextSize(16);
        n.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        row.addView(n, new LinearLayout.LayoutParams(dp(22),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(COLOR_TEXT);
        t.setTextSize(15);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(8);
        row.addView(t, lp);
        return row;
    }

    private interface FloatSetter { void set(float v); }

    private View layerRow(String num, java.util.concurrent.Callable<String> label,
            Runnable onPick, float level, FloatSetter onLevel) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(10), dp(12), dp(10));
        col.setBackground(moduleBackground(COLOR_SURFACE_RAISED, COLOR_BORDER, COLOR_TEAL, true));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = new TextView(this);
        n.setText(num);
        n.setTextColor(COLOR_TEAL);
        n.setTextSize(16);
        n.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        top.addView(n, new LinearLayout.LayoutParams(dp(22),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        final TextView name = new TextView(this);
        try { name.setText(label.call()); } catch (Exception e) { name.setText(""); }
        name.setTextColor(COLOR_TEXT);
        name.setTextSize(15);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setClickable(true);
        name.setOnClickListener(v -> onPick.run());
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(8);
        top.addView(name, nlp);
        col.addView(top, matchWrap());

        final TextView pct = new TextView(this);
        pct.setTextColor(COLOR_MUTED);
        pct.setTextSize(11);
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(Math.round(level * 100));
        pct.setText("Level  " + Math.round(level * 100) + "%");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) {
                pct.setText("Level  " + p + "%");
                onLevel.set(p / 100f);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        col.addView(pct, topMargin(matchWrap(), 8));
        col.addView(bar, topMargin(matchWrap(), 2));
        return col;
    }

    // GM sound picker for an extra layer (3 or 4).
    // Full keyboard-roster picker for a layer — every piano/keyboard sound we
    // ship, grouped + searchable, plus an Off row. The layer renders that sound's
    // GM voice on its own channel.
    private void layerSoundPicker(final int layer) {
        final String cur = layerPreset(layer);
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(dialogSheet());
        dialog.setCanceledOnTouchOutside(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("◀  Back");
        back.setTextColor(COLOR_TEAL);
        back.setTextSize(15);
        back.setClickable(true);
        back.setPadding(dp(6), dp(6), dp(12), dp(6));
        back.setOnClickListener(v -> { dialog.dismiss(); if (afterLayerPick != null) afterLayerPick.run(); else layersDialog(); });
        head.addView(back, matchWrap());
        TextView title = new TextView(this);
        title.setText("Layer " + layer);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(17);
        title.setGravity(Gravity.END);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        head.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(head, matchWrap());

        final EditText search = new EditText(this);
        search.setHint("Search sounds…");
        search.setTextColor(COLOR_TEXT);
        search.setHintTextColor(COLOR_MUTED);
        search.setTextSize(14);
        search.setBackground(moduleBackground(COLOR_SURFACE_RAISED, COLOR_BORDER, COLOR_TEAL, true));
        search.setPadding(dp(12), dp(9), dp(12), dp(9));
        searchIme(search);
        content.addView(search, topMargin(matchWrap(), 10));

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        final ScrollView scroll = new ScrollView(this);
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        final View[] selRow = new View[1];

        Runnable rebuild = () -> {
            list.removeAllViews();
            selRow[0] = null;
            String q = search.getText().toString().trim().toLowerCase(Locale.US);
            // Off row first.
            if (q.isEmpty() || "off".contains(q)) {
                list.addView(layerPickRow(layer, "Off", null, cur == null, dialog, selRow), topMargin(matchWrap(), 6));
            }
            java.util.List<ExternalSf2File> externalMatches = matchingExternalSf2(q);
            if (!externalMatches.isEmpty()) {
                TextView external = chordCell(
                        "External SF2 (" + externalMatches.size() + ")", false);
                external.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                external.setOnClickListener(v -> {
                    dialog.dismiss();
                    showExternalSf2Picker(false, layer, q, () -> {
                        if (afterLayerPick != null) afterLayerPick.run();
                        else layersDialog();
                    });
                });
                list.addView(external, topMargin(matchWrap(), 6));
            }
            String lastCat = null;
            for (TonePreset p : TonePreset.values()) {
                if (p.mode != InstrumentMode.PIANO) continue;
                if (!q.isEmpty()
                        && !p.label.toLowerCase(Locale.US).contains(q)
                        && !p.category.toLowerCase(Locale.US).contains(q)) continue;
                if (!p.category.equals(lastCat)) {
                    lastCat = p.category;
                    TextView h = sectionTitle(p.category);
                    list.addView(h, topMargin(matchWrap(), 10));
                }
                boolean sel = p.name().equals(cur);
                list.addView(layerPickRow(layer, p.label, p.name(), sel, dialog, selRow),
                        topMargin(matchWrap(), 6));
            }
        };
        rebuild.run();
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { rebuild.run(); }
            public void afterTextChanged(android.text.Editable s) {}
        });
        int maxListH = (int) (getResources().getDisplayMetrics().heightPixels * 0.60f);
        content.addView(scroll, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxListH), 10));
        if (selRow[0] != null) {
            scroll.post(() -> scroll.scrollTo(0, Math.max(0, selRow[0].getTop() - dp(70))));
        }
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.86f, 460),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.setContentView(content);
        dialog.show();
    }

    private View layerPickRow(int layer, String label, String presetName, boolean sel,
            Dialog dialog, View[] selRow) {
        TextView item = new TextView(this);
        item.setText(label);
        item.setTextColor(sel ? COLOR_TEXT : COLOR_MUTED);
        item.setTextSize(15);
        item.setPadding(dp(12), dp(11), dp(12), dp(11));
        item.setClickable(true);
        item.setBackground(moduleBackground(sel ? darken(COLOR_TEAL) : COLOR_SURFACE_RAISED,
                sel ? COLOR_TEAL : COLOR_BORDER, COLOR_TEAL, true));
        item.setOnClickListener(v -> {
            engine.allNotesOff();
            setLayerPreset(layer, presetName);
            if (presetName != null) ensureLayerAudible(layer);   // silent (0%) layers → audible
            saveLayers();
            applyLayers();
            dialog.dismiss();
            if (afterLayerPick != null) afterLayerPick.run(); else layersDialog();
        });
        if (sel) selRow[0] = item;
        return item;
    }

    private void applyDualSound() {
        prefs.edit().putBoolean("dual_on", dualOn)
                .putBoolean("dual_separate", dualSeparate)
                .putInt("dual_split", dualSplit)
                .putString("dual_preset", dualPreset.name()).apply();
        applyDualFontRouting();
        applyPianoProgram();
        applyLoopKeysSound();
        applyLiveControlsB(liveControlValuesB);   // refresh B's baked FX for the new Sound 2
        if (dualButton != null) {
            dualButton.setText(dualLabel());
            styleChipButton(dualButton, dualOn);
        }
        refreshSound2Bar();
        refreshLooperDualAvailability();
        updateLoopSoundPill();
        refreshLiveControlTabs();
        if (loopKeysView != null) {
            // On the split keyboards Dual is ALWAYS per board (lower = Sound 1,
            // upper = Sound 2), exactly what the Dual pill promises. The
            // "Split key" mode only applies to single-keyboard dual.
            boolean boards = loopDualOn && loopKeysSplit;
            loopKeysView.setDualSplit(loopDualOn && !boards ? loopDualSplit : -1);
            loopKeysView.setDualSeparate(boards);
        }
        // Stretch Sound 2 into the fixed browser while Sound 1 compresses.
        // Only layout weights animate; the browser and its scroll state stay alive.
        if (pianoBrowserHost != null && pianoBrowserDualLayout != dualOn) {
            animatePianoBrowserDual(dualOn);
        } else if (pianoBrowserHost != null) {
            refreshPianoSoundBrowserSelection();
        }
    }

    // Apply the looper's OWN dual sound (independent of the piano keyboard).
    private void applyLoopDual() {
        prefs.edit().putBoolean("loop_dual_on", loopDualOn)
                .putInt("loop_dual_split", loopDualSplit)
                .putString("loop_dual_preset", loopDualPreset.name()).apply();
        applyLoopKeysSound();
        refreshLooperDualAvailability();
        updateLoopSoundPill();
        if (loopKeysView != null) {
            boolean boards = loopDualOn && loopKeysSplit;
            loopKeysView.setDualSplit(loopDualOn && !boards ? loopDualSplit : -1);
            loopKeysView.setDualSeparate(boards);
        }
    }

    // Looper dual is a second physical keyboard, so it is meaningful only
    // after the split keyboard has been enabled. Keep the control visible but
    // disabled until then, rather than hiding it or allowing a dead tap.
    private void refreshLooperDualAvailability() {
        if (dualKeysPill == null) {
            return;
        }
        boolean available = loopKeysSplit;
        dualKeysPill.setEnabled(available);
        dualKeysPill.setAlpha(available ? 1.0f : 0.42f);
        dualKeysPill.setTextColor(available && loopDualOn ? COLOR_GREEN : COLOR_MUTED);
    }

    private View dualSelectedRow;

    // Sound 2 list, styled like the main sound picker (two-line rows, category
    // headers, search over name/detail/category). Every piano sound qualifies —
    // GM programs on channel 1, HQ/custom fonts through the dual font slot.
    private void populateDualSoundList(LinearLayout list, String filter, final Dialog dialog) {
        list.removeAllViews();
        dualSelectedRow = null;
        String f = filter.trim().toLowerCase(Locale.US);
        String currentCategory = null;
        for (final TonePreset preset : TonePreset.forMode(InstrumentMode.PIANO)) {
            if (!f.isEmpty()
                    && !preset.label.toLowerCase(Locale.US).contains(f)
                    && !preset.detail.toLowerCase(Locale.US).contains(f)
                    && !preset.category.toLowerCase(Locale.US).contains(f)) {
                continue;
            }
            String category = pianoCategory(preset);
            if (!category.equals(currentCategory)) {
                currentCategory = category;
                TextView head = new TextView(this);
                head.setText(category.toUpperCase(Locale.US));
                head.setTextColor(COLOR_AMBER);
                head.setTextSize(11);
                head.setLetterSpacing(0.08f);
                head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                list.addView(head, topMargin(matchWrap(), list.getChildCount() == 0 ? 6 : 16));
            }
            boolean current = preset == loopDualPreset;
            int accent = toneAccentStatic(preset);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(12), dp(10), dp(12), dp(10));
            item.setClickable(true);
            item.setBackground(moduleBackground(
                    current ? darken(accent) : COLOR_SURFACE_RAISED,
                    current ? accent : COLOR_BORDER, accent, true));
            item.setOnClickListener(v -> {
                loopDualPreset = preset;
                loopDualOn = true;   // picking a sound turns Dual on
                applyLoopDual();
                dialog.dismiss();
            });
            if (current) {
                dualSelectedRow = item;
            }
            TextView nm = new TextView(this);
            nm.setText(preset.label);
            nm.setTextColor(current ? COLOR_TEXT : COLOR_MUTED);
            nm.setTextSize(14);
            item.addView(nm, matchWrap());
            TextView dt = new TextView(this);
            dt.setText(preset.detail);
            dt.setTextColor(COLOR_DIM);
            dt.setTextSize(11);
            item.addView(dt, topMargin(matchWrap(), 2));
            list.addView(item, topMargin(matchWrap(), 6));
        }
        if (list.getChildCount() == 0) {
            list.addView(detailText("No matching sounds."), topMargin(matchWrap(), 8));
        }
    }

    // Dual sound setup: where the keyboard splits and what Sound 2 plays.
    private void dualSoundDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));
        TextView title = new TextView(this);
        title.setText("Dual Sound");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());
        TextView hint = new TextView(this);
        hint.setText("With the Split keyboard on, the lower board plays Sound 1 and the upper board plays this Sound 2. (Independent of the piano's Dual.)");
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(12);
        content.addView(hint, topMargin(matchWrap(), 4));

        // Sound 2 list: same style as the main sound picker, with search.
        EditText search = new EditText(this);
        searchIme(search);
        search.setHint("Search sounds");
        search.setHintTextColor(COLOR_DIM);
        search.setTextColor(COLOR_TEXT);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        search.setBackground(panelBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        content.addView(search, topMargin(matchWrap(), 10));

        final ScrollView sv = new ScrollView(this);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        content.addView(sv, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT), 8));

        populateDualSoundList(list, "", dialog);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                populateDualSoundList(list, s.toString(), dialog);
            }
        });
        sv.post(() -> {
            if (dualSelectedRow != null) {
                sv.scrollTo(0, Math.max(0, dualSelectedRow.getTop() - dp(72)));
            }
        });

        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.85f, 460), LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void updateLoopKeysRangeLabel() {
        if (loopKeysRangeLabel != null) {
            loopKeysRangeLabel.setText(noteName(loopKeysBase) + " – "
                    + noteName(loopKeysBase + 12));
        }
        if (loopKeysMelodyLabel != null) {
            loopKeysMelodyLabel.setText("Melody  " + noteName(loopKeysMelodyBase) + " – "
                    + noteName(loopKeysMelodyBase + 12));
        }
    }

    private String loopKeySoundName() {
        return loopKeysPreset.label;
    }

    // Push the selected keys sound to the engine: GM program plus HQ font slot.
    // Library fonts load lazily (GM stands in until the font is ready).
    private void applyLoopKeysSound() {
        engine.allNotesOff();   // held keys can't survive the sound switch
        engine.setLoopKeysProgram(pianoProgram(loopKeysPreset));
        engine.setLoopKeysLayer(loopDualOn ? pianoProgram(loopDualPreset) : loopKeysPreset.layer);
        // Per-board mode (explicit "Two boards", or any dual on the split
        // keyboards): split point 128 = no key ever crosses it, so the lower
        // board stays pure Sound 1 and the upper board routes to Sound 2 directly.
        engine.setLoopKeysSplitConfig(loopDualOn && loopKeysSplit ? 128 : loopDualSplit,
                loopDualOn ? loopDualFoldProgram() : -1);
        engine.setLoopKeysFoldProgram(loopKeysPreset.asset == null
                && pianoFontSlot(loopKeysPreset) == -1 ? pianoProgram(loopKeysPreset) : -1);
        if (loopKeysPreset.asset != null) {
            loadLibraryKeysFont(loopKeysPreset);
        } else {
            engine.setLoopKeysSlot(pianoFontSlot(loopKeysPreset));
        }
        pushLoopKeysGlide();
    }

    private void loadLibraryKeysFont(final TonePreset preset) {
        final String asset = preset.asset;
        if (asset == null) {
            return;
        }
        if (asset.equals(loadedLibraryAsset)) {
            engine.setLoopKeysSlot(LAZY_PIANO_SLOT);
            return;
        }
        engine.setLoopKeysSlot(-1);   // GM piano stands in while the font loads
        final float gainDb = libraryPianoGain(asset);
        new Thread(() -> {
            byte[] data = readAsset(asset);
            final boolean ok = data != null && engine.loadHqFont(LAZY_PIANO_SLOT, 0, gainDb, data);
            handler.post(() -> {
                if (ok) {
                    loadedLibraryAsset = asset;
                }
                // Only route if this sound is still the selected one.
                if (onLoopMix && preset == loopKeysPreset && ok) {
                    engine.setLoopKeysSlot(LAZY_PIANO_SLOT);
                }
            });
        }, "keys-lib-loader").start();
    }

    // Full piano sound list — the same searchable picker as the Piano screen.
    private View loopKeysSelectedRow;

    private void loopKeysSoundDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));
        TextView title = new TextView(this);
        title.setText("Keys Sound");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        final EditText search = new EditText(this);
        searchIme(search);
        search.setHint("Search sounds");
        search.setHintTextColor(COLOR_DIM);
        search.setTextColor(COLOR_TEXT);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        search.setBackground(panelBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        content.addView(search, topMargin(matchWrap(), 12));

        final ScrollView sv = new ScrollView(this);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        content.addView(sv, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT), 12));

        populateLoopKeysSoundList(list, "", dialog);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                populateLoopKeysSoundList(list, s.toString(), dialog);
            }
        });

        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.92f, 620), LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        // Open at the current selection, not the top of a 200-sound list.
        sv.post(() -> {
            if (loopKeysSelectedRow != null) {
                sv.scrollTo(0, Math.max(0, loopKeysSelectedRow.getTop() - dp(72)));
            }
        });
    }

    private void populateLoopKeysSoundList(LinearLayout list, String filter, final Dialog dialog) {
        list.removeAllViews();
        loopKeysSelectedRow = null;
        String f = filter.trim().toLowerCase(Locale.US);
        String currentCategory = null;
        for (final TonePreset preset : TonePreset.forMode(InstrumentMode.PIANO)) {
            if (!f.isEmpty()
                    && !preset.label.toLowerCase(Locale.US).contains(f)
                    && !preset.detail.toLowerCase(Locale.US).contains(f)
                    && !preset.category.toLowerCase(Locale.US).contains(f)) {
                continue;
            }
            String category = pianoCategory(preset);
            if (!category.equals(currentCategory)) {
                currentCategory = category;
                TextView head = new TextView(this);
                head.setText(category.toUpperCase(Locale.US));
                head.setTextColor(COLOR_AMBER);
                head.setTextSize(11);
                head.setLetterSpacing(0.08f);
                head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                list.addView(head, topMargin(matchWrap(), list.getChildCount() == 0 ? 6 : 16));
            }
            boolean current = preset == loopKeysPreset;
            int accent = toneAccentStatic(preset);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(12), dp(10), dp(12), dp(10));
            item.setClickable(true);
            item.setBackground(moduleBackground(
                    current ? darken(accent) : COLOR_SURFACE_RAISED,
                    current ? accent : COLOR_BORDER, accent, true));
            item.setOnClickListener(v -> {
                loopKeysPreset = preset;
                prefs.edit().putString("loop_keys_preset", preset.name()).apply();
                applyLoopKeysSound();
                updateLoopSoundPill();
                dialog.dismiss();
            });
            if (current) {
                loopKeysSelectedRow = item;
            }
            TextView nm = new TextView(this);
            nm.setText(preset.label);
            nm.setTextColor(current ? COLOR_TEXT : COLOR_MUTED);
            nm.setTextSize(14);
            item.addView(nm, matchWrap());
            TextView dt = new TextView(this);
            dt.setText(preset.detail);
            dt.setTextColor(COLOR_DIM);
            dt.setTextSize(11);
            item.addView(dt, topMargin(matchWrap(), 2));
            list.addView(item, topMargin(matchWrap(), 6));
        }
    }

    // Voice FX settings: what the harmony sings (mode/key/duet), how it sounds
    // (tone, reverb) and how loud it sits (level). Everything applies live.
    private void harmonizerDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("Harmonizer");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("Mode picks the companion voices. Key 0–5 transposes the harmony up that many semitones. ♯ adds one more.");
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(11);
        content.addView(hint, topMargin(matchWrap(), 4));

        final TextView[] tonePills = new TextView[3];
        final TextView modeLabel = new TextView(this);
        final TextView keyLabel = new TextView(this);
        final TextView levelLabel = new TextView(this);
        final TextView sharpPill = transportPill("♯ Sharp: Off");
        final TextView tunePill = transportPill("Autotune: Off");
        final TextView revPill = transportPill("Reverb: " + (harmReverb ? "On" : "Off"));

        final Runnable refresh = () -> {
            for (int t = 0; t < 3; t++) {
                tonePills[t].setTextColor(harmTone == t ? COLOR_GREEN : COLOR_MUTED);
            }
            int m = Math.max(1, Math.min(11, harmMode));
            modeLabel.setText("Mode  " + m + " · " + HARM_MODE_NAMES[m - 1]);
            int k = Math.max(0, Math.min(5, harmKey));
            keyLabel.setText("Key  " + k + "  (" + HARM_KEY_NAMES[k] + ")");
            levelLabel.setText("Level  " + harmLevel);
            sharpPill.setText(harmSharp ? "♯ Sharp: On" : "♯ Sharp: Off");
            sharpPill.setTextColor(harmSharp ? COLOR_GREEN : COLOR_MUTED);
            tunePill.setText(harmAutotune ? "Autotune: On" : "Autotune: Off");
            tunePill.setTextColor(harmAutotune ? COLOR_GREEN : COLOR_MUTED);
            revPill.setText("Reverb: " + (harmReverb ? "On" : "Off"));
            revPill.setTextColor(harmReverb ? COLOR_GREEN : COLOR_MUTED);
        };

        modeLabel.setTextSize(13);
        modeLabel.setTextColor(COLOR_TEXT);
        content.addView(modeLabel, topMargin(matchWrap(), 14));
        SeekBar modeBar = new SeekBar(this);
        modeBar.setMax(10);
        modeBar.setProgress(Math.max(1, Math.min(11, harmMode)) - 1);
        modeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                harmMode = progress + 1;
                applyHarmonizerParams();
                refresh.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) { }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                saveHarmonizerPrefs();
            }
        });
        content.addView(modeBar, topMargin(matchWrap(), 2));

        keyLabel.setTextSize(13);
        keyLabel.setTextColor(COLOR_TEXT);
        content.addView(keyLabel, topMargin(matchWrap(), 10));
        SeekBar keyBar = new SeekBar(this);
        keyBar.setMax(5);
        keyBar.setProgress(Math.max(0, Math.min(5, harmKey)));
        keyBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                harmKey = progress;
                applyHarmonizerParams();
                refresh.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) { }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                saveHarmonizerPrefs();
            }
        });
        content.addView(keyBar, topMargin(matchWrap(), 2));

        sharpPill.setOnClickListener(v -> {
            harmSharp = !harmSharp;
            saveHarmonizerPrefs();
            applyHarmonizerParams();
            refresh.run();
        });
        tunePill.setOnClickListener(v -> {
            harmAutotune = !harmAutotune;
            saveHarmonizerPrefs();
            engine.setAutotune(harmAutotune);
            refresh.run();
        });
        LinearLayout sharpRow = new LinearLayout(this);
        sharpRow.setOrientation(LinearLayout.HORIZONTAL);
        sharpRow.setGravity(Gravity.CENTER_HORIZONTAL);
        sharpRow.addView(sharpPill, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams tuneLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tuneLp.leftMargin = dp(8);
        sharpRow.addView(tunePill, tuneLp);
        content.addView(sharpRow, topMargin(matchWrap(), 12));

        final String[] toneNames = {"Tone: Off", "Warm", "Bright"};
        LinearLayout toneRow = new LinearLayout(this);
        toneRow.setOrientation(LinearLayout.HORIZONTAL);
        toneRow.setGravity(Gravity.CENTER_HORIZONTAL);
        for (int t = 0; t < 3; t++) {
            final int tone = t;
            TextView pill = transportPill(toneNames[t]);
            pill.setOnClickListener(v -> {
                harmTone = tone;
                saveHarmonizerPrefs();
                applyHarmonizerParams();
                refresh.run();
            });
            tonePills[t] = pill;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (t > 0) lp.leftMargin = dp(8);
            toneRow.addView(pill, lp);
        }
        revPill.setOnClickListener(v -> {
            harmReverb = !harmReverb;
            saveHarmonizerPrefs();
            applyHarmonizerParams();
            refresh.run();
        });
        LinearLayout.LayoutParams revLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        revLp.leftMargin = dp(8);
        toneRow.addView(revPill, revLp);
        content.addView(toneRow, topMargin(matchWrap(), 14));

        levelLabel.setTextSize(13);
        levelLabel.setTextColor(COLOR_TEXT);
        content.addView(levelLabel, topMargin(matchWrap(), 12));
        SeekBar levelBar = new SeekBar(this);
        levelBar.setMax(100);
        levelBar.setProgress(harmLevel);
        levelBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                harmLevel = progress;
                applyHarmonizerParams();
                refresh.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) { }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                saveHarmonizerPrefs();
            }
        });
        content.addView(levelBar, topMargin(matchWrap(), 2));

        refresh.run();
        ScrollView sv = new ScrollView(this);
        sv.addView(content);
        dialog.setContentView(sv);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.9f, 520), LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    // ---- Foot pedals ------------------------------------------------------
    // Universal: any footswitch that types keys (USB/Bluetooth HID) or sends
    // MIDI notes/CCs. Pedals 1-4 drive the four loopers, pedal 5 is global
    // pause/resume. Tap = record/overdub/play, hold 1s = undo the last layer,
    // tap while paused = choose mute/play for the restart.

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean system = code == KeyEvent.KEYCODE_BACK
                || code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_HOME
                || code == KeyEvent.KEYCODE_POWER;
        if (!system && onLoopMix) {
            for (int i = 0; i < 5; i++) {
                if (pedalBind[i] == code) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                        pedalDown(i);
                    } else if (event.getAction() == KeyEvent.ACTION_UP) {
                        pedalUp(i);
                    }
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void pedalDown(final int i) {
        if (i == 4) {   // global pedal: pause everything / resume together from 0
            boolean running = false;
            for (int t = 0; t < 4; t++) {
                int st = engine.loopState(t) & 0xF;
                if (st == 1 || st == 2 || st == 3) { running = true; break; }
            }
            engine.loopGlobal(running ? 1 : 2);
            return;
        }
        int st = engine.loopState(i) & 0xF;
        if (st == 4) {              // paused: pedal picks play/mute, like a ring tap
            engine.loopCommand(i, 5);
            return;
        }
        engine.loopCommand(i, 1);   // tap: record -> overdub -> play
        pedalHoldFired[i] = false;
        Runnable hold = () -> {     // hold = drop the overdub this press started
            pedalHoldFired[i] = true;
            engine.loopCommand(i, 4);
        };
        pedalHoldRunnable[i] = hold;
        handler.postDelayed(hold, 1000);
    }

    private void pedalUp(int i) {
        if (i >= 4) return;
        if (pedalHoldRunnable[i] != null && !pedalHoldFired[i]) {
            handler.removeCallbacks(pedalHoldRunnable[i]);
        }
        pedalHoldRunnable[i] = null;
    }

    // MIDI pedal events (note = 1000+n, CC = 2000+n). Returns true when consumed
    // by learn mode or a binding; runs on the MIDI thread, so actions hop to UI.
    private boolean handlePedalMidi(final int code, boolean on) {
        if (pedalLearn >= 0) {
            if (on) handler.post(() -> bindPedal(code));
            return true;
        }
        if (!onLoopMix) return false;
        for (int i = 0; i < 5; i++) {
            if (pedalBind[i] == code) {
                final int p = i;
                final boolean down = on;
                handler.post(() -> { if (down) pedalDown(p); else pedalUp(p); });
                return true;
            }
        }
        return false;
    }

    private void bindPedal(int code) {
        int p = pedalLearn;
        if (p < 0) return;
        for (int i = 0; i < 5; i++) {
            if (i != p && pedalBind[i] == code) pedalBind[i] = -1;   // steal duplicates
        }
        pedalBind[p] = code;
        pedalLearn = -1;
        savePedalBinds();
        refreshPedalRows();
    }

    private void savePedalBinds() {
        SharedPreferences.Editor e = prefs.edit();
        for (int i = 0; i < 5; i++) {
            e.putInt("pedal_bind_" + i, pedalBind[i]);
        }
        e.apply();
        applyLoopRecBars();   // bound loop pedals supersede the bar auto-stop
    }

    private String pedalBindLabel(int code) {
        if (code < 0) return "not set";
        if (code >= 2000) return "MIDI CC " + (code - 2000);
        if (code >= 1000) return "MIDI note " + (code - 1000);
        return "Key " + KeyEvent.keyCodeToString(code).replace("KEYCODE_", "");
    }

    private void refreshPedalRows() {
        if (pedalRows == null) return;
        String[] names = {"Vocals", "Loop 1", "Loop 2", "Loop 3", "Pause all"};
        for (int i = 0; i < 5; i++) {
            if (pedalRows[i] == null) continue;
            String bind = pedalLearn == i ? "press a pedal…" : pedalBindLabel(pedalBind[i]);
            pedalRows[i].setText("Pedal " + (i + 1) + " · " + names[i] + "   —   " + bind);
            pedalRows[i].setTextColor(pedalLearn == i ? COLOR_AMBER
                    : pedalBind[i] >= 0 ? COLOR_TEXT : COLOR_MUTED);
        }
    }

    private void pedalDialog() {
        pedalLearn = -1;
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("Foot Pedals");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("Works with any footswitch that types keys (USB/Bluetooth) or sends MIDI. Tap a slot, then press the pedal once. Long-press a slot to unbind.");
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(11);
        content.addView(hint, topMargin(matchWrap(), 4));

        pedalRows = new TextView[5];
        for (int i = 0; i < 5; i++) {
            final int p = i;
            TextView row = menuItem("", () -> {
                pedalLearn = pedalLearn == p ? -1 : p;
                refreshPedalRows();
            });
            row.setOnLongClickListener(v -> {
                pedalBind[p] = -1;
                if (pedalLearn == p) pedalLearn = -1;
                savePedalBinds();
                refreshPedalRows();
                return true;
            });
            pedalRows[i] = row;
            content.addView(row, topMargin(matchWrap(), i == 0 ? 12 : 8));
        }
        refreshPedalRows();

        TextView legend = new TextView(this);
        legend.setText("Tap: record ▸ overdub ▸ play\nHold 1s: undo the last layer\nTap while paused: mute / unmute\nPedal 5: pause all / resume all from 0");
        legend.setTextColor(COLOR_DIM);
        legend.setTextSize(11);
        content.addView(legend, topMargin(matchWrap(), 12));

        // The dialog window owns key events while open, so learn happens here.
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (pedalLearn >= 0 && keyCode != KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    bindPedal(keyCode);
                }
                return true;
            }
            return false;
        });
        dialog.setOnDismissListener(d -> {
            pedalLearn = -1;
            pedalRows = null;
        });

        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.9f, 520), LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private TextView loopChip(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(contrastTextColor(COLOR_SURFACE_RAISED));
        t.setTextSize(12);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(8), dp(1), dp(8), dp(3));
        t.setBackground(pillBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        t.setClickable(true);
        return t;
    }

    private void exitLoopMix() {
        onLoopMix = false;
        engine.allNotesOff();   // held/sustained keys must not survive the exit
        // Pause every loop (pending command runs on the next engine start), so
        // nothing auto-plays when the looper is reopened.
        engine.loopGlobal(1);
        handler.removeCallbacks(loopPump);
        loopPauseAllButton = null;
        loopKitButton = null;
        loopSound2Pill = null;
        loopMetroPill = null;
        for (int i = 0; i < loopMuteChips.length; i++) loopMuteChips[i] = null;
        for (int i = 0; i < loopRings.length; i++) loopRings[i] = null;
        drumPadsView = null;
        drumKitView = null;
        loopKeysView = null;
        loopPadHost = null;
        loopKeysRangeLabel = null;
        loopKeysMelodyNav = null;
        loopKeysMelodyLabel = null;
        dualKeysPill = null;
        engine.stop();
        showPicker();
    }

    private void loopTrackMenu(final int track, String name) {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));
        TextView title = new TextView(this);
        int st = engine.loopState(track);
        float ms = engine.loopLenMs(track);
        title.setText(name + (ms > 0 ? String.format(Locale.US, "  \u00b7  %.1fs", ms / 1000f) : ""));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());
        content.addView(menuItem(st == 4 ? "\u25b6  Resume" : "\u23f8  Pause", () -> {
            engine.loopCommand(track, 2);
            dialog.dismiss();
        }), topMargin(matchWrap(), 12));
        content.addView(menuItem("\u21b6  Undo last overdub", () -> {
            engine.loopCommand(track, 4);
            dialog.dismiss();
        }), topMargin(matchWrap(), 8));
        if (track >= 1) {
            // Per-loop hands-free take length: tap cycles Off / 1..4 bars.
            final TextView auto = menuItem(loopBarsLabel(track), () -> { });
            auto.setTextColor(loopRecBars[track] > 0 ? COLOR_GREEN : COLOR_TEXT);
            auto.setOnClickListener(v -> {
                loopRecBars[track] = (loopRecBars[track] + 1) % 5;
                prefs.edit().putInt("loop_rec_bars_" + track, loopRecBars[track]).apply();
                applyLoopRecBars();
                auto.setText(loopBarsLabel(track));
                auto.setTextColor(loopRecBars[track] > 0 ? COLOR_GREEN : COLOR_TEXT);
            });
            content.addView(auto, topMargin(matchWrap(), 8));
        }
        content.addView(menuItem("\u2913  Save as WAV", () -> {
            dialog.dismiss();
            saveLoop(track);
        }), topMargin(matchWrap(), 8));
        TextView clear = menuItem("\u2715  Clear loop", () -> {
            engine.loopCommand(track, 3);
            dialog.dismiss();
        });
        clear.setTextColor(COLOR_RED);
        content.addView(clear, topMargin(matchWrap(), 8));
        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.78f, 400), LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void saveLoop(final int track) {
        File dir = new File(getExternalFilesDir(null), "recordings");
        dir.mkdirs();
        String base = track == 0 ? "loop_vocal_" : "loop_" + track + "_";
        final String fileName = base + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".wav";
        final String path = new File(dir, fileName).getAbsolutePath();
        new Thread(() -> {
            final boolean ok = engine.loopSave(track, path);
            runOnUiThread(() -> Toast.makeText(this,
                    ok ? "Saved " + fileName + " (see Recordings)" : "Nothing to save yet",
                    Toast.LENGTH_LONG).show());
        }, "loop-save").start();
    }

    // Loop station icon: three interlocking circles.
    private static final class LoopsIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int accent;

        LoopsIconView(Context context, int accent) {
            super(context);
            this.accent = accent;
        }

        @Override
        protected void onMeasure(int w, int h) {
            int s = Math.round(40 * getResources().getDisplayMetrics().density);
            setMeasuredDimension(resolveSize(s, w), resolveSize(s, h));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float s = Math.min(getWidth(), getHeight());
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.09f * s);
            paint.setColor(accent);
            paint.setAlpha(255);
            canvas.drawCircle(0.32f * s, 0.5f * s, 0.22f * s, paint);
            paint.setAlpha(170);
            canvas.drawCircle(0.58f * s, 0.5f * s, 0.22f * s, paint);
            paint.setAlpha(100);
            canvas.drawCircle(0.82f * s, 0.5f * s, 0.16f * s, paint);
        }
    }

    // One loop machine: a ring whose color shows state (empty / recording /
    // playing / overdub / paused) with a progress arc for the playhead, or an
    // on/off toggle ring for the harmonizer.
    // One loop machine. Self-animating: the playhead arc glides between state
    // polls, recording/armed rings breathe, taps pop, and the waveform shows
    // played spokes bright with upcoming spokes dimmed.
    private final class LoopRingView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rc = new RectF();
        private final String glyph;
        private final int accent;
        private final boolean toggle;
        private boolean on;
        private int state;
        private float pos;        // last polled playhead 0..1
        private float showPos;    // animated playhead
        private float lenMs;
        private boolean muted;
        private float pulse;
        private long lastT;
        private boolean animating;
        private final float[] wave = new float[64];

        LoopRingView(Context c, String glyph, int accent, boolean toggle) {
            super(c);
            this.glyph = glyph;
            this.accent = accent;
            this.toggle = toggle;
            setClickable(true);
        }

        float[] waveBins() {
            return wave;
        }

        void waveUpdated() {
            invalidate();
        }

        void setLenMs(float ms) {
            lenMs = ms;
        }

        void setOn(boolean v) {
            if (v != on) {
                on = v;
                ensureAnim();
                invalidate();
            }
        }

        void setLoopState(int st, float posNorm, boolean isMuted) {
            if (st != state) {
                pop();
            }
            state = st;
            muted = isMuted;
            pos = posNorm;
            // Resync the animated playhead if it drifted or playback jumped.
            if (state < 2 || state == 5 || Math.abs(posNorm - showPos) > 0.08f) {
                showPos = posNorm;
            }
            ensureAnim();
            invalidate();
        }

        // Small scale bounce on every state change (tap feedback).
        private void pop() {
            animate().scaleX(0.90f).scaleY(0.90f).setDuration(70)
                    .withEndAction(() -> animate().scaleX(1f).scaleY(1f).setDuration(180).start())
                    .start();
        }

        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!isAttachedToWindow()) {
                    animating = false;
                    return;
                }
                long now = System.nanoTime();
                float dt = lastT == 0 ? 0.016f : Math.min(0.05f, (now - lastT) / 1e9f);
                lastT = now;
                boolean need = false;
                if ((state == 2 || state == 3) && lenMs > 1f) {
                    showPos += dt * 1000f / lenMs;
                    if (showPos > 1f) showPos -= 1f;
                    need = true;
                }
                if (state == 1 || state == 3 || state == 5 || (toggle && on)) {
                    pulse += dt * (state == 1 ? 7f : 3.2f);
                    need = true;
                }
                if (need) {
                    invalidate();
                    postOnAnimationDelayed(this, 16);
                } else {
                    animating = false;
                }
            }
        };

        private void ensureAnim() {
            boolean need = state == 1 || state == 2 || state == 3 || state == 5 || (toggle && on);
            if (need && !animating) {
                animating = true;
                lastT = 0;
                postOnAnimation(tick);
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            ensureAnim();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            animating = false;
            removeCallbacks(tick);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    animate().scaleX(0.93f).scaleY(0.93f).setDuration(60).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    break;
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int s = Math.round(76 * getResources().getDisplayMetrics().density);
            setMeasuredDimension(resolveSize(s, w), resolveSize(s, h));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float stroke = Math.min(w, h) * 0.055f;
            float rad = Math.min(w, h) / 2f - stroke * 2.2f;
            float breathe = 0.5f + 0.5f * (float) Math.sin(pulse);
            int col;
            if (toggle) {
                col = on ? accent : Color.rgb(120, 120, 126);
            } else if (state == 1 || state == 3) {
                col = COLOR_RED;
            } else if (state == 2) {
                col = accent;
            } else if (state == 4) {
                col = COLOR_AMBER;
            } else if (state == 5) {
                col = COLOR_RED;
            } else {
                col = Color.rgb(110, 110, 116);
            }
            boolean active = (toggle && on) || state == 1 || state == 2 || state == 3;

            // machine face: raised sky-blue disc with a subtle inner plate
            p.setStyle(Paint.Style.FILL);
            p.setColor(COLOR_SKY_CONTROL_STRONG);
            canvas.drawCircle(cx, cy, rad + stroke * 1.4f, p);
            p.setColor(COLOR_SURFACE_RAISED);
            canvas.drawCircle(cx, cy, rad * 0.80f, p);

            // outer glow when the machine is alive (breathes while recording/armed)
            if (active || state == 5) {
                float g = (state == 1 || state == 5) ? 0.35f + 0.65f * breathe : 1f;
                p.setStyle(Paint.Style.STROKE);
                for (int k = 1; k <= 3; k++) {
                    p.setStrokeWidth(stroke * (1f + k * 1.05f));
                    int a = (int) ((k == 1 ? 58 : k == 2 ? 30 : 14) * g);
                    p.setColor(Color.argb(a, Color.red(col), Color.green(col), Color.blue(col)));
                    canvas.drawCircle(cx, cy, rad, p);
                }
            }

            // base ring
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            int baseAlpha = muted ? 80 : active ? 255 : 150;
            if (state == 5) baseAlpha = (int) (120 + 100 * breathe);
            p.setColor(Color.argb(baseAlpha, Color.red(col), Color.green(col), Color.blue(col)));
            canvas.drawCircle(cx, cy, rad, p);

            // circular waveform: spokes already played are bright, upcoming dim
            if (!toggle && state != 0 && state != 5) {
                float rBase = rad * 0.55f;
                float span = rad * 0.28f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1.5f, stroke * 0.32f));
                p.setStrokeCap(Paint.Cap.ROUND);
                boolean running = state == 2 || state == 3;
                for (int i = 0; i < wave.length; i++) {
                    float a = Math.min(1f, wave[i] * 2.2f) * span;
                    if (a < 1f) a = 1f;
                    boolean played = !running || (i / (float) wave.length) <= showPos;
                    int wa = muted ? 60 : played ? 225 : 95;
                    p.setColor(Color.argb(wa, Color.red(col), Color.green(col), Color.blue(col)));
                    double ang = Math.PI * 2.0 * i / wave.length - Math.PI / 2.0;
                    float dx = (float) Math.cos(ang), dy = (float) Math.sin(ang);
                    canvas.drawLine(cx + dx * (rBase - a), cy + dy * (rBase - a),
                            cx + dx * (rBase + a), cy + dy * (rBase + a), p);
                }
                p.setStrokeCap(Paint.Cap.BUTT);
            }

            // playhead arc + comet tip
            if (!toggle && (state == 2 || state == 3 || state == 4)) {
                float sweep = Math.max(8f, showPos * 360f);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(stroke * 1.5f);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(col);
                rc.set(cx - rad, cy - rad, cx + rad, cy + rad);
                canvas.drawArc(rc, -90f, sweep, false, p);
                p.setStrokeCap(Paint.Cap.BUTT);
                if (state == 2 || state == 3) {
                    double tip = Math.toRadians(sweep - 90f);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Color.rgb(245, 245, 245));
                    canvas.drawCircle(cx + (float) Math.cos(tip) * rad,
                            cy + (float) Math.sin(tip) * rad, stroke * 0.85f, p);
                }
            }

            // record dot: filled while capturing, hollow pulsing while armed
            if (!toggle && (state == 1 || state == 3 || state == 5)) {
                p.setColor(Color.argb(state == 5 ? (int) (110 + 145 * breathe) : 255,
                        Color.red(COLOR_RED), Color.green(COLOR_RED), Color.blue(COLOR_RED)));
                if (state == 5) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(stroke * 0.42f);
                } else {
                    p.setStyle(Paint.Style.FILL);
                }
                canvas.drawCircle(cx, cy - rad * 0.48f, stroke * 0.85f, p);
            }

            // glyph (M when muted)
            p.setStyle(Paint.Style.FILL);
            p.setColor(toggle ? (on ? accent : COLOR_MUTED)
                    : state == 0 ? COLOR_MUTED : muted ? COLOR_AMBER : COLOR_TEXT);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(toggle || state == 0 ? rad * 0.60f : rad * 0.38f);
            p.setFakeBoldText(true);
            canvas.drawText(muted && !toggle ? "M" : glyph, cx,
                    cy + rad * (toggle || state == 0 ? 0.21f : 0.13f), p);
        }
    }

    // ---- Tuner ----

    private void showTuner() {
        if (!hasRecordAudioPermission()) {
            requestAudioPermissionIfNeeded();
            Toast.makeText(this, "Microphone permission is needed for the tuner", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!onTunerScreen) {
            onTunerScreen = true;
            loadAudioPrefs();
            engine.startTuner(resolvePreferredInput(-1), resolvePreferredOutput(-1));
        }

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);

        // Landscape: readout rail left, big meter right.
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setPadding(dp(20), dp(14), dp(20), dp(14));
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);

        rail.addView(railHeader(this::exitTuner, "CHROMATIC", "Tuner", COLOR_TEAL), matchWrap());

        View railGap = new View(this);
        rail.addView(railGap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        tunerHzText = new TextView(this);
        tunerHzText.setTextColor(COLOR_MUTED);
        tunerHzText.setTextSize(15);
        tunerHzText.setGravity(Gravity.CENTER);
        tunerHzText.setText(engine.isRunning() ? "Listening… play a note" : "Couldn't start the tuner");
        rail.addView(tunerHzText, topMargin(matchWrap(), 8));

        TextView ref = new TextView(this);
        ref.setText("Guitar  E A D G B E\nBass  E A D G");
        ref.setTextColor(COLOR_DIM);
        ref.setTextSize(13);
        ref.setLineSpacing(dp(3), 1.0f);
        ref.setGravity(Gravity.CENTER);
        rail.addView(ref, topMargin(matchWrap(), 12));

        tunerMeter = new TunerMeterView(this);

        content.addView(rail, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 4.2f));
        LinearLayout.LayoutParams meterLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 7.8f);
        meterLp.leftMargin = dp(16);
        content.addView(tunerMeter, meterLp);

        screen.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        enablePadInsets(screen, content);
        paintStage(screen);
        setContentView(screen);

        tunerTick = new Runnable() {
            public void run() {
                if (tunerMeter == null) {
                    return;
                }
                updateTuner();
                handler.postDelayed(this, 80);
            }
        };
        handler.post(tunerTick);
    }

    private void exitTuner() {
        onTunerScreen = false;
        if (tunerTick != null) {
            handler.removeCallbacks(tunerTick);
            tunerTick = null;
        }
        tunerMeter = null;
        tunerHzText = null;
        engine.stop();
        showPicker();
    }

    private void updateTuner() {
        float hz = engine.pitchHz();
        if (hz < 25f || hz > 2000f) {
            tunerMeter.setReading(0f, false);
            tunerHzText.setText("Listening… play a single note");
            return;
        }
        double midi = 69.0 + 12.0 * (Math.log(hz / 440.0) / Math.log(2.0));
        int nearest = (int) Math.round(midi);
        int cents = (int) Math.round((midi - nearest) * 100.0);
        tunerMeter.setReading((float) midi, true);
        tunerHzText.setText(String.format(Locale.US, "%.1f Hz    %+d cents", hz, cents));
    }

    private void paintStage(View view) {
        // Called with the root of every screen right before setContentView.
        view.setBackground(stageBackground());
    }

    private GradientDrawable stageBackground() {
        // CSS equivalent:
        // linear-gradient(90deg, #21A5D9 0%, #217EC4 50%, #FFFFFF 100%)
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{
                        Color.rgb(33, 165, 217),
                        Color.rgb(33, 126, 196),
                        Color.WHITE
                });
        gradient.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return gradient;
    }

    private void enablePadInsets(final View screen, final View content) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        getWindow().setDecorFitsSystemWindows(false);
        final int cl = content.getPaddingLeft();
        final int ct = content.getPaddingTop();
        final int cr = content.getPaddingRight();
        final int cb = content.getPaddingBottom();
        screen.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            content.setPadding(cl + bars.left, ct + bars.top, cr + bars.right, cb + bars.bottom);
            return insets;
        });
    }

    private GradientDrawable dotDrawable(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private GradientDrawable bannerBackground(int accent) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.argb(36, Color.red(accent), Color.green(accent), Color.blue(accent)));
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), accent);
        return drawable;
    }

    private void updateStatusIndicator() {
        updateRouteChip();
        if (statusDot == null || statusText == null) {
            return;
        }
        boolean running = engine.isRunning();
        String readiness = checkReadiness();
        int dotColor;
        String label;
        String banner;
        if (running) {
            dotColor = COLOR_GREEN;
            label = "Running";
            banner = null;
        } else if (currentError != null) {
            dotColor = COLOR_RED;
            label = "Error";
            banner = currentError;
        } else if (readiness != null) {
            dotColor = COLOR_AMBER;
            label = "Not ready";
            banner = readiness;
        } else {
            dotColor = COLOR_DIM;
            label = "Ready";
            banner = null;
        }
        statusDot.setBackground(dotDrawable(dotColor));
        statusText.setText(label);
        statusText.setTextColor(dotColor == COLOR_DIM ? COLOR_MUTED : dotColor);
        if (errorBanner != null) {
            if (banner != null) {
                errorBanner.setText(banner);
                errorBanner.setTextColor(currentError != null ? COLOR_RED : COLOR_MUTED);
                errorBanner.setBackground(null);
                errorBanner.setVisibility(View.VISIBLE);
            } else {
                errorBanner.setVisibility(View.GONE);
            }
        }
    }

    private String checkReadiness() {
        if (currentMode == InstrumentMode.DRUMS) {
            return null;
        }
        if (currentMode == InstrumentMode.PIANO) {
            // A MIDI keyboard is optional — the engine still runs for MIDI-file
            // playback and sound output. Connection state is shown by the chip.
            return null;
        }
        if (!hasRecordAudioPermission()) {
            return "Microphone permission required to capture your instrument.";
        }
        if (!router.hasUsbInput()) {
            return "Please plug your audio port — connect a USB-C audio interface for your "
                    + currentMode.label.toLowerCase(Locale.US)
                    + ". The internal microphone is not used to avoid feedback.";
        }
        return null;
    }

    private void enableEdgeToEdge(final View screen, final View content, final LinearLayout footer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        getWindow().setDecorFitsSystemWindows(false);
        final int cl = content.getPaddingLeft();
        final int ct = content.getPaddingTop();
        final int cr = content.getPaddingRight();
        final int cb = content.getPaddingBottom();
        final int fl = footer.getPaddingLeft();
        final int ft = footer.getPaddingTop();
        final int fr = footer.getPaddingRight();
        final int fb = footer.getPaddingBottom();
        screen.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            content.setPadding(cl + bars.left, ct + bars.top, cr + bars.right, cb);
            footer.setPadding(fl + bars.left, ft, fr + bars.right, fb + bars.bottom);
            return insets;
        });
    }

    // Remember the sound each instrument was last on, keyed by mode, so
    // reopening it restores that pick instead of snapping back to the default.
    private void rememberPreset(InstrumentMode mode, TonePreset preset) {
        if (prefs != null && preset != null) {
            String key = virtualGuitarMidiMode
                    ? "last_virtual_guitar_preset" : "last_preset_" + mode.name();
            prefs.edit().putString(key, preset.name()).apply();
        }
    }

    private float layerLevel(int layer) {
        switch (layer) {
            case 1: return layer1Level;
            case 2: return layer2Level;
            case 3: return layer3Level;
            case 4: return layer4Level;
            case 5: return layer5Level;
            case 6: return layer6Level;
            case 7: return layer7Level;
            case 8: return layer8Level;
            default: return 0f;
        }
    }

    private void setLayerLevel(int layer, float value) {
        value = Math.max(0f, Math.min(1f, value));
        switch (layer) {
            case 1: layer1Level = value; break;
            case 2: layer2Level = value; break;
            case 3: layer3Level = value; break;
            case 4: layer4Level = value; break;
            case 5: layer5Level = value; break;
            case 6: layer6Level = value; break;
            case 7: layer7Level = value; break;
            case 8: layer8Level = value; break;
        }
    }

    private TonePreset lastPreset(InstrumentMode mode) {
        TonePreset fallback = virtualGuitarMidiMode
                ? TonePreset.VIRTUAL_GUITAR_STARTER : TonePreset.defaultFor(mode);
        if (prefs == null) return fallback;
        String key = virtualGuitarMidiMode
                ? "last_virtual_guitar_preset" : "last_preset_" + mode.name();
        String name = prefs.getString(key, null);
        if (name != null) {
            try {
                TonePreset p = TonePreset.valueOf(name);
                if (p.mode == mode && (!virtualGuitarMidiMode || isGuitarPreset(p))) {
                    return p;
                }
            } catch (IllegalArgumentException ignored) { }
        }
        return fallback;
    }

    private void selectPreset(TonePreset preset) {
        if (currentMode == InstrumentMode.PIANO) {
            clearExternalSf2Selection(false);
        }
        // Choosing a whole kit from Pad Mode must make that kit own every pad.
        // Otherwise a previously enabled Custom Kit keeps its per-piece sources
        // active while only the selected label changes. Kit Mode intentionally
        // keeps custom/default piece inheritance and is therefore excluded.
        if (currentMode == InstrumentMode.DRUMS && !onFullPads && drumCustomKit) {
            drumCustomKit = false;
            saveDrumAssignments();
        }
        currentPreset = preset;
        rememberPreset(currentMode, preset);
        applyPresetToRunningEngine(false);
        applyPianoProgram();
        applyDrumKit();
        updateSelectionStyles();
        if (onFullPads) {
            showFullPads();   // refresh the Kit Mode title + pad availability
        }
    }

    // ---- Song Presets: a named snapshot of an instrument's whole setup
    // (sound + every effect setting), so picking one for a song sets it all up
    // at once — no re-tweaking. Stored per instrument mode in prefs. ----

    private static float parseF(String s, float def) {
        try { return s == null ? def : Float.parseFloat(s); } catch (RuntimeException e) { return def; }
    }

    private static int parseI(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s); } catch (RuntimeException e) { return def; }
    }

    private static boolean parseB(String s, boolean def) {
        return s == null ? def : Boolean.parseBoolean(s);
    }

    private static String setupEncode(String value) {
        return value == null ? "" : Uri.encode(value);
    }

    private static String setupDecode(String value) {
        return value == null || value.isEmpty() ? null : Uri.decode(value);
    }

    private TonePreset presetByName(String s, TonePreset def) {
        if (s == null) return def;
        try { return TonePreset.valueOf(s); } catch (RuntimeException e) { return def; }
    }

    // Serialize the current instrument's full setup to a "k=v;k=v" string.
    private String captureSetup() {
        StringBuilder b = new StringBuilder("preset=").append(currentPreset.name());
        if (currentMode == InstrumentMode.ELECTRIC_GUITAR || currentMode == InstrumentMode.BASS) {
            for (int i = 0; i < 6; i++) b.append(";c").append(i).append('=').append(liveControlValues[i]);
            b.append(";wah=").append(wahOn).append(";wahpos=").append(wahPos);
            b.append(";gate=").append(gateOn).append(";gatea=").append(gateAmount);
            b.append(";cab=").append(cabOn).append(";cabt=").append(cabType);
        } else if (currentMode == InstrumentMode.PIANO) {
            b.append(";sus=").append(sustainOn).append(";sust=").append(sustainTime);
            b.append(";rev=").append(reverbOn).append(";revl=").append(reverbLevel);
            b.append(";dual=").append(dualOn).append(";dualp=").append(dualPreset.name());
            b.append(";dsep=").append(dualSeparate).append(";dsplit=").append(dualSplit);
            b.append(";gl=").append(pianoGlideOn).append(";glm=").append(pianoGlideMono);
            b.append(";glr=").append(pianoGlideRate).append(";bend=").append(bendRange);
            b.append(";ext1=").append(setupEncode(activeExternalMainUri));
            b.append(";ext1p=").append(activeExternalMainPreset);
            b.append(";ext2=").append(setupEncode(activeExternalDualUri));
            b.append(";ext2p=").append(activeExternalDualPreset);
            b.append(";layers=").append(layerMode);
            String[] layerSounds = {
                    null, null, layer2Preset, layer3Preset, layer4Preset,
                    null, layer6Preset, layer7Preset, layer8Preset
            };
            float[] layerLevels = {
                    0f, layer1Level, layer2Level, layer3Level, layer4Level,
                    layer5Level, layer6Level, layer7Level, layer8Level
            };
            for (int layer = 1; layer <= 8; layer++) {
                b.append(";ls").append(layer).append('=')
                        .append(setupEncode(layerSounds[layer]));
                b.append(";ll").append(layer).append('=').append(layerLevels[layer]);
                b.append(";lz").append(layer).append('=').append(layerZap[layer]);
                b.append(";lp").append(layer).append('=')
                        .append(activeExternalLayerPreset[layer]);
            }
        } else if (currentMode == InstrumentMode.DRUMS) {
            b.append(";room=").append(drumRoomLevel).append(";rim=").append(padSnareRim);
            b.append(";ch=").append(cymGainHat).append(";cr=").append(cymGainRide).append(";cc=").append(cymGainCrash);
            b.append(";rcv=").append(rideCrashVel).append(";crv=").append(crashRideVel);
            b.append(";chv=").append(cymChokeVel);
        }
        return b.toString();
    }

    // Parse a saved setup back into the live fields, then apply + rebuild the UI.
    private void applySetup(String data) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String kv : data.split(";")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(kv.substring(0, i), kv.substring(i + 1));
        }
        currentPreset = presetByName(m.get("preset"), currentPreset);
        if (currentMode == InstrumentMode.ELECTRIC_GUITAR || currentMode == InstrumentMode.BASS) {
            for (int i = 0; i < 6; i++) liveControlValues[i] = parseF(m.get("c" + i), liveControlValues[i]);
            wahOn = parseB(m.get("wah"), wahOn);
            wahPos = parseF(m.get("wahpos"), wahPos);
            gateOn = parseB(m.get("gate"), gateOn);
            gateAmount = parseF(m.get("gatea"), gateAmount);
            cabOn = parseB(m.get("cab"), cabOn);
            cabType = parseI(m.get("cabt"), cabType);
        } else if (currentMode == InstrumentMode.PIANO) {
            sustainOn = parseB(m.get("sus"), sustainOn);
            sustainTime = parseF(m.get("sust"), sustainTime);
            reverbOn = parseB(m.get("rev"), reverbOn);
            reverbLevel = parseF(m.get("revl"), reverbLevel);
            dualOn = parseB(m.get("dual"), dualOn);
            dualPreset = presetByName(m.get("dualp"), dualPreset);
            dualSeparate = parseB(m.get("dsep"), dualSeparate);
            dualSplit = parseI(m.get("dsplit"), dualSplit);
            pianoGlideOn = parseB(m.get("gl"), pianoGlideOn);
            pianoGlideMono = parseB(m.get("glm"), pianoGlideMono);
            pianoGlideRate = parseI(m.get("glr"), pianoGlideRate);
            bendRange = parseI(m.get("bend"), bendRange);
            activeExternalMainUri = setupDecode(m.get("ext1"));
            activeExternalMainPreset = parseI(m.get("ext1p"), 0);
            activeExternalDualUri = setupDecode(m.get("ext2"));
            activeExternalDualPreset = parseI(m.get("ext2p"), 0);
            layerMode = parseB(m.get("layers"), layerMode);
            for (int layer = 1; layer <= 8; layer++) {
                String sound = setupDecode(m.get("ls" + layer));
                if (layer != 1 && layer != 5) setLayerPreset(layer, sound);
                setLayerLevel(layer, parseF(m.get("ll" + layer), layerLevel(layer)));
                layerZap[layer] = parseI(m.get("lz" + layer), layerZap[layer]);
                activeExternalLayerPreset[layer] = parseI(m.get("lp" + layer), 0);
            }
            SharedPreferences.Editor scenePrefs = prefs.edit()
                    .putInt("external_sf2_main_preset", activeExternalMainPreset)
                    .putInt("external_sf2_dual_preset", activeExternalDualPreset)
                    .putBoolean("layer_mode", layerMode);
            putOrRemove(scenePrefs, "external_sf2_main", activeExternalMainUri);
            putOrRemove(scenePrefs, "external_sf2_dual", activeExternalDualUri);
            for (int layer = 1; layer <= 8; layer++) {
                scenePrefs.putInt("external_sf2_layer_" + layer + "_preset",
                        activeExternalLayerPreset[layer]);
            }
            scenePrefs.apply();
            saveLayers();
            saveEffectPrefs();
        } else if (currentMode == InstrumentMode.DRUMS) {
            drumRoomLevel = parseF(m.get("room"), drumRoomLevel);
            padSnareRim = parseB(m.get("rim"), padSnareRim);
            cymGainHat = parseF(m.get("ch"), cymGainHat);
            cymGainRide = parseF(m.get("cr"), cymGainRide);
            cymGainCrash = parseF(m.get("cc"), cymGainCrash);
            rideCrashVel = parseF(m.get("rcv"), rideCrashVel);
            crashRideVel = parseF(m.get("crv"), crashRideVel);
            cymChokeVel = parseF(m.get("chv"), cymChokeVel);
        }
        // Push the sound + settings, then rebuild the screen (its builders
        // re-apply every effect/knob/wah from the fields we just set).
        applyPianoProgram();
        applyDrumKit();
        if (currentMode == InstrumentMode.PIANO) applyDualSound();
        applyPresetToRunningEngine(false);   // push the tone if the engine is live
        showInstrumentScreen();
    }

    private java.util.List<String> songPresetNames() {
        java.util.List<String> names = new java.util.ArrayList<>(prefs.getStringSet(
                "songpresets_" + currentMode.name(), new HashSet<>()));
        java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private void saveSongPreset(String name) {
        Set<String> names = new HashSet<>(prefs.getStringSet(
                "songpresets_" + currentMode.name(), new HashSet<>()));
        names.add(name);
        prefs.edit()
                .putStringSet("songpresets_" + currentMode.name(), names)
                .putString("songpreset_" + currentMode.name() + "_" + name, captureSetup())
                .putLong("songpresetmtime_" + currentMode.name() + "_" + name, System.currentTimeMillis())
                .apply();
    }

    private void deleteSongPreset(String name) {
        Set<String> names = new HashSet<>(prefs.getStringSet(
                "songpresets_" + currentMode.name(), new HashSet<>()));
        names.remove(name);
        prefs.edit()
                .putStringSet("songpresets_" + currentMode.name(), names)
                .remove("songpreset_" + currentMode.name() + "_" + name)
                .remove("songpresetmtime_" + currentMode.name() + "_" + name)
                .apply();
    }

    private void songPresetsDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(16), dp(18), dp(14));

        TextView title = new TextView(this);
        title.setText("Song Presets");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());
        TextView sub = new TextView(this);
        sub.setText("Save this " + currentMode.label.toLowerCase(Locale.US)
                + " setup, then pick it later to load everything at once.");
        sub.setTextColor(COLOR_MUTED);
        sub.setTextSize(12);
        content.addView(sub, topMargin(matchWrap(), 4));

        // One-file sync of EVERY instrument's song presets + chord songs.
        TextView syncAll = new TextView(this);
        syncAll.setText("⇅  Export / import all (every instrument) →");
        syncAll.setTextColor(COLOR_TEAL);
        syncAll.setTextSize(13);
        syncAll.setClickable(true);
        syncAll.setPadding(dp(2), dp(8), dp(2), dp(4));
        syncAll.setOnClickListener(v -> { dialog.dismiss(); backupDialog(); });
        content.addView(syncAll, topMargin(matchWrap(), 2));

        final EditText nameField = new EditText(this);
        textIme(nameField);
        nameField.setHint("Song / preset name");
        nameField.setHintTextColor(COLOR_DIM);
        nameField.setTextColor(COLOR_TEXT);
        nameField.setTextSize(15);
        nameField.setSingleLine(true);
        nameField.setPadding(dp(12), dp(10), dp(12), dp(10));
        nameField.setBackground(panelBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        content.addView(nameField, topMargin(matchWrap(), 12));

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        Button save = chipButton("Save current setup");
        save.setOnClickListener(v -> {
            String name = nameField.getText().toString().trim().replace(";", " ").replace("=", " ");
            if (name.isEmpty()) {
                Toast.makeText(this, "Name the preset first", Toast.LENGTH_SHORT).show();
                return;
            }
            saveSongPreset(name);
            nameField.setText("");
            populateSongPresetList(list, dialog);
            Toast.makeText(this, "Saved “" + name + "”", Toast.LENGTH_SHORT).show();
        });
        content.addView(save, topMargin(matchWrap(), 10));

        ScrollView sv = new ScrollView(this);
        sv.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        content.addView(sv, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT), 12));
        populateSongPresetList(list, dialog);

        // Already has its own inner ScrollView — don't nest another.
        dialog.setContentView(content);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.85f, 480), LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void populateSongPresetList(LinearLayout list, final Dialog dialog) {
        list.removeAllViews();
        java.util.List<String> names = songPresetNames();
        if (names.isEmpty()) {
            list.addView(detailText("No saved setups yet — name one above and tap Save."),
                    topMargin(matchWrap(), 8));
            return;
        }
        for (final String name : names) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(12), dp(8), dp(12));
            row.setBackground(moduleBackground(COLOR_SURFACE_RAISED, COLOR_BORDER, COLOR_GREEN, true));

            TextView nm = new TextView(this);
            nm.setText(name);
            nm.setTextColor(COLOR_TEXT);
            nm.setTextSize(15);
            nm.setClickable(true);
            nm.setOnClickListener(v -> {
                String data = prefs.getString("songpreset_" + currentMode.name() + "_" + name, null);
                if (data != null) {
                    applySetup(data);
                    Toast.makeText(this, "Loaded “" + name + "”", Toast.LENGTH_SHORT).show();
                }
                dialog.dismiss();
            });
            row.addView(nm, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView del = new TextView(this);
            del.setText("✕");
            del.setTextColor(COLOR_MUTED);
            del.setTextSize(16);
            del.setPadding(dp(12), dp(4), dp(12), dp(4));
            del.setClickable(true);
            del.setOnClickListener(v -> {
                deleteSongPreset(name);
                populateSongPresetList(list, dialog);
            });
            row.addView(del, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            list.addView(row, topMargin(matchWrap(), 8));
        }
    }

    private void selectRoute(InputRoute route) {
        if (!isRouteAllowed(route)) {
            return;
        }
        if (currentRoute == route) {
            return;
        }
        currentRoute = route;
        updateSelectionStyles();
        restartForRouteChangeIfNeeded();
    }

    private InputRoute defaultRouteFor(InstrumentMode mode) {
        // Piano and drums are MIDI-only; guitar and bass use USB-C audio input.
        if (mode == InstrumentMode.DRUMS || mode == InstrumentMode.PIANO) {
            return InputRoute.MIDI;
        }
        return InputRoute.USB;
    }

    private boolean isRouteAllowed(InputRoute route) {
        if (currentMode == InstrumentMode.DRUMS || currentMode == InstrumentMode.PIANO) {
            return route == InputRoute.MIDI;
        }
        return route == InputRoute.USB;
    }

    private void toggleEngine() {
        if (engine.isRunning()) {
            engine.stop();
            currentError = null;
            feedbackTicks = 0;
            startButton.setText("Start Engine");
            updateSelectionStyles();
            return;
        }
        startEngine();
    }

    private void startEngine() {
        if (!isRouteAllowed(currentRoute)) {
            currentRoute = defaultRouteFor(currentMode);
            rebuildRouteButtons();
        }

        if (currentRoute != InputRoute.MIDI && !hasRecordAudioPermission()) {
            requestAudioPermissionIfNeeded();
            currentError = "Microphone permission required to capture your instrument.";
            updateSelectionStyles();
            return;
        }

        String readiness = checkReadiness();
        if (readiness != null) {
            currentError = readiness;
            updateSelectionStyles();
            return;
        }

        AudioDeviceRouter.DeviceSelection devices = router.select(currentRoute);
        boolean started = engine.start(
                currentMode,
                currentPreset,
                currentRoute,
                resolvePreferredInput(devices.inputDeviceId),
                resolvePreferredOutput(devices.outputDeviceId)
        );

        if (started) {
            currentError = null;
            feedbackTicks = 0;
            startButton.setText("Stop Engine");
            if (currentMode == InstrumentMode.DRUMS) {
                // Startup resets native applied-kit state. Reassert the complete
                // selected route after the stream is live, including custom kits.
                applyDrumKit();
            }
        } else {
            currentError = engine.status();
            startButton.setText("Start Engine");
        }
        updateSelectionStyles();
    }

    private void applyPresetToRunningEngine(boolean restart) {
        if (!engine.isRunning()) {
            return;
        }

        if (restart) {
            engine.stop();
            startButton.setText("Start Engine");
            startEngine();
            return;
        }

        AudioDeviceRouter.DeviceSelection devices = router.select(currentRoute);
        engine.setPreset(
                currentMode,
                currentPreset,
                currentRoute,
                resolvePreferredInput(devices.inputDeviceId),
                resolvePreferredOutput(devices.outputDeviceId)
        );
    }

    // If the user picked an output sink and it's currently present, use it;
    // otherwise fall back to the router's choice (auto).
    private int resolvePreferredOutput(int fallback) {
        if (preferredOutputType < 0) {
            return fallback;
        }
        android.media.AudioDeviceInfo device = router.outputOfType(preferredOutputType);
        return device != null ? device.getId() : fallback;
    }

    // Same for the capture side; falls back when the chosen device is unplugged.
    private int resolvePreferredInput(int fallback) {
        if (preferredInputType < 0) {
            return fallback;
        }
        android.media.AudioDeviceInfo device = router.inputMatching(preferredInputType, preferredInputName);
        return device != null ? device.getId() : fallback;
    }

    private void restartForRouteChangeIfNeeded() {
        applyPresetToRunningEngine(true);
    }

    private void restartForDeviceChangeIfNeeded() {
        if (!engine.isRunning()) {
            updateStatusIndicator();
            return;
        }
        String readiness = checkReadiness();
        if (readiness != null) {
            engine.stop();
            currentError = readiness;
            feedbackTicks = 0;
            if (startButton != null) {
                startButton.setText("Start Engine");
            }
            updateSelectionStyles();
            return;
        }
        applyPresetToRunningEngine(true);
    }

    private void applyLiveControls(float[] values) {
        if (values == null || values.length < 6) {
            return;
        }
        System.arraycopy(values, 0, liveControlValues, 0, 6);
        engine.setControls(
                liveControlValues[0],
                liveControlValues[1],
                liveControlValues[2],
                liveControlValues[3],
                liveControlValues[4],
                liveControlValues[5]
        );
        if (currentMode == InstrumentMode.PIANO) {
            // The "Mod" knob (index 2) is a live chorus control for the sampled
            // piano; tone/drive/trem stay at the current preset's baked values.
            float[] baked = pianoFx(currentPreset);
            engine.setPianoFx(baked[0], baked[1], liveControlValues[2], baked[3]);
            // Remember this as a hand-set Mod position for this sound, so
            // applyPianoProgram stops overwriting it with the baked value.
            modOverridePreset = currentPreset;
            modOverride = liveControlValues[2];
            // "Space" (index 4) is the reverb wet mix (engine clamps to 0..0.7).
            reverbLevel = liveControlValues[4] * MAX_REVERB_LEVEL;
            engine.setReverbLevel(reverbLevel);
        }
        if (liveControlView != null) {
            liveControlView.setValues(liveControlValues);
        }
    }

    // Keyboard B (Sound 2) live controls: an independent FX chain. Only Mod
    // (chorus, index 2) and Level (index 5) are per-side adjustable; tone/drive/
    // trem come from Sound 2's preset. Reverb stays the shared global send.
    private void applyLiveControlsB(float[] values) {
        if (values == null || values.length < 6) return;
        if (values != liveControlValuesB) System.arraycopy(values, 0, liveControlValuesB, 0, 6);
        float[] baked = pianoFx(dualPreset);
        engine.setPianoFxB(baked[0], baked[1], liveControlValuesB[2], baked[3]);
        engine.setLevelB(liveControlValuesB[5]);
        SharedPreferences.Editor e = prefs.edit();
        for (int i = 0; i < 6; i++) e.putFloat("lcb" + i, liveControlValuesB[i]);
        e.apply();
        if (liveControlViewB != null) liveControlViewB.setValues(liveControlValuesB);
    }

    private boolean isGuitarPreset(TonePreset preset) {
        if (preset == null) return false;
        return (preset.program >= 24 && preset.program <= 31)
                || preset.category.toLowerCase(Locale.US).contains("guitar")
                || preset.label.toLowerCase(Locale.US).contains("guitar");
    }

    private boolean externalLooksLikeGuitar(String uri) {
        if (uri == null) return false;
        if (guitarSf2Uris.contains(uri)) return true;
        String name = externalSf2Name(uri).toLowerCase(Locale.US)
                .replace(" ", "").replace("_", "").replace("-", "");
        return name.contains("guitar") || name.contains("gtr")
                || name.contains("strat") || name.contains("tele")
                || name.contains("lespaul") || name.contains("humbucker");
    }

    private boolean pianoGuitarVoice(boolean soundTwo) {
        String external = soundTwo ? activeExternalDualUri : activeExternalMainUri;
        if (external != null) {
            return virtualGuitarMidiMode || externalLooksLikeGuitar(external);
        }
        return isGuitarPreset(soundTwo ? dualPreset : currentPreset);
    }

    private String selectedExternalGuitarUri() {
        return dualOn && liveTab == 1 ? activeExternalDualUri : activeExternalMainUri;
    }

    private void savePianoGuitarRig() {
        prefs.edit()
                .putBoolean("piano_guitar_rig_on", pianoGuitarRigOn)
                .putInt("piano_guitar_amp", pianoGuitarAmp)
                .putInt("piano_guitar_cab", pianoGuitarCab)
                .putFloat("piano_guitar_drive", pianoGuitarDrive)
                .putFloat("piano_guitar_tone", pianoGuitarTone)
                .putFloat("piano_guitar_harmonics", pianoGuitarHarmonics)
                .putBoolean("virtual_guitar_player_on", virtualGuitarPlayerOn)
                .putString("piano_guitar_nam_uri", activeNamUri)
                .putBoolean("piano_guitar_nam_on", pianoGuitarNamOn)
                .putFloat("piano_guitar_nam_mix", pianoGuitarNamMix)
                .putFloat("piano_guitar_nam_input_db", pianoGuitarNamInputDb)
                .putFloat("piano_guitar_nam_output_db", pianoGuitarNamOutputDb)
                .putString("piano_guitar_nam_ir_uri", activeNamIrUri)
                .putBoolean("piano_guitar_nam_ir_on", pianoGuitarNamIrOn)
                .apply();
    }

    private void pushPianoGuitarRig() {
        boolean onA = virtualGuitarMidiMode && pianoGuitarRigOn && pianoGuitarVoice(false);
        boolean onB = virtualGuitarMidiMode && pianoGuitarRigOn
                && dualOn && pianoGuitarVoice(true);
        engine.setPianoGuitarRig(onA, onB, pianoGuitarAmp, pianoGuitarCab,
                pianoGuitarDrive, pianoGuitarTone, pianoGuitarHarmonics);
        engine.setVirtualGuitarPlayer(virtualGuitarMidiMode && virtualGuitarPlayerOn);
        engine.setNam(virtualGuitarMidiMode && pianoGuitarNamOn && engine.namReady(),
                pianoGuitarNamMix, dbToLinear(pianoGuitarNamInputDb),
                dbToLinear(pianoGuitarNamOutputDb));
        engine.setNamIr(virtualGuitarMidiMode && pianoGuitarNamIrOn
                && engine.namIrReady());
        if (virtualGuitarMidiMode && activeNamUri != null
                && !activeNamUri.equals(loadedNamUri) && !namLoading) {
            loadNamModel(findExternalNam(activeNamUri), false);
        }
        if (virtualGuitarMidiMode && activeNamIrUri != null
                && !activeNamIrUri.equals(loadedNamIrUri) && !namIrLoading) {
            loadNamIr(findExternalIr(activeNamIrUri), false);
        }
        if (virtualGuitarMidiMode && virtualGuitarPlayerOn) {
            ensureVirtualGuitarArticulations();
        }
    }

    private static float dbToLinear(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    private ExternalNamFile findExternalNam(String uri) {
        if (uri == null) return null;
        for (ExternalNamFile file : externalNamFiles) {
            if (uri.equals(file.uri)) return file;
        }
        Uri parsed = Uri.parse(uri);
        String name = parsed.getLastPathSegment();
        if (name == null || name.isEmpty()) name = "External model.nam";
        if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
        return new ExternalNamFile(parsed, name, "External", -1L);
    }

    private String activeNamName() {
        ExternalNamFile file = findExternalNam(activeNamUri);
        if (file == null) return "Choose model";
        String name = file.name;
        return name.toLowerCase(Locale.US).endsWith(".nam")
                ? name.substring(0, name.length() - 4) : name;
    }

    private void loadNamModel(final ExternalNamFile file, boolean enableWhenReady) {
        if (file == null || namLoading) return;
        namLoading = true;
        refreshPianoGuitarRig();
        beginSoundLoad("Loading NAM model...");
        namLoader.execute(() -> {
            byte[] bytes = readExternalNam(Uri.parse(file.uri), file.size);
            boolean ok = bytes != null && engine.loadNamModel(bytes);
            handler.post(() -> {
                namLoading = false;
                finishSoundLoad();
                if (ok) {
                    activeNamUri = file.uri;
                    loadedNamUri = file.uri;
                    if (enableWhenReady) {
                        pianoGuitarNamOn = true;
                        // A NAM capture replaces the built-in amp stage. Keeping
                        // both on would produce an unintended double-amp chain.
                        pianoGuitarRigOn = false;
                    }
                    savePianoGuitarRig();
                    pushPianoGuitarRig();
                } else {
                    float expected = engine.namExpectedRate();
                    String reason = expected > 0f
                            ? "Model needs " + Math.round(expected)
                                    + " Hz; this audio device uses another rate"
                            : "Unsupported or damaged NAM model";
                    Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
                }
                refreshPianoGuitarRig();
            });
        });
    }

    private void showNamModelPicker() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView title = new TextView(this);
        title.setText("NAM Amp Model");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(19);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        EditText search = new EditText(this);
        searchIme(search);
        search.setHint("Search .nam models");
        search.setHintTextColor(COLOR_DIM);
        search.setTextColor(COLOR_TEXT);
        search.setSingleLine(true);
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        search.setBackground(panelBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        content.addView(search, topMargin(matchWrap(), 12));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(340)), 10));

        Runnable populate = () -> {
            list.removeAllViews();
            String filter = search.getText().toString().trim().toLowerCase(Locale.US);
            int shown = 0;
            for (ExternalNamFile file : externalNamFiles) {
                String searchable = (file.name + " " + file.relativePath)
                        .toLowerCase(Locale.US);
                if (!filter.isEmpty() && !searchable.contains(filter)) continue;
                Button row = chipButton(file.name);
                row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                boolean selected = file.uri.equals(activeNamUri);
                styleChipButton(row, selected);
                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    loadNamModel(file, true);
                });
                list.addView(row, topMargin(matchWrap(), shown == 0 ? 0 : 6));
                shown++;
            }
            if (shown == 0) {
                TextView empty = labelText(externalSf2TreeUri == null
                        ? "Choose a sound library folder first"
                        : "No .nam models found in this folder");
                empty.setTextColor(COLOR_MUTED);
                list.addView(empty, matchWrap());
                Button folder = chipButton("Choose Folder");
                folder.setOnClickListener(v -> {
                    dialog.dismiss();
                    pickExternalSf2Folder();
                });
                list.addView(folder, topMargin(matchWrap(), 10));
            }
        };
        populate.run();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { populate.run(); }
        });

        dialog.setContentView(content);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.90f, 620),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
    }

    private ExternalIrFile findExternalIr(String uri) {
        if (uri == null) return null;
        for (ExternalIrFile file : externalIrFiles) {
            if (uri.equals(file.uri)) return file;
        }
        Uri parsed = Uri.parse(uri);
        String name = parsed.getLastPathSegment();
        if (name == null || name.isEmpty()) name = "External cabinet.wav";
        if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
        return new ExternalIrFile(parsed, name, "External", -1L);
    }

    private String activeNamIrName() {
        ExternalIrFile file = findExternalIr(activeNamIrUri);
        if (file == null) return "Choose IR";
        String name = file.name;
        return name.toLowerCase(Locale.US).endsWith(".wav")
                ? name.substring(0, name.length() - 4) : name;
    }

    private void loadNamIr(final ExternalIrFile file, boolean enableWhenReady) {
        if (file == null || namIrLoading) return;
        namIrLoading = true;
        refreshPianoGuitarRig();
        namLoader.execute(() -> {
            byte[] bytes = readExternalNam(Uri.parse(file.uri), file.size);
            WavPcm wav = decodeIrWav(bytes);
            boolean ok = wav != null && engine.loadNamIr(
                    wav.samples, wav.frames, wav.channels, wav.rate);
            handler.post(() -> {
                namIrLoading = false;
                if (ok) {
                    activeNamIrUri = file.uri;
                    loadedNamIrUri = file.uri;
                    if (enableWhenReady) pianoGuitarNamIrOn = true;
                    savePianoGuitarRig();
                    pushPianoGuitarRig();
                } else {
                    Toast.makeText(this,
                            "IR must be a PCM or 32-bit float WAV",
                            Toast.LENGTH_LONG).show();
                }
                refreshPianoGuitarRig();
            });
        });
    }

    private void showNamIrPicker() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(14));
        TextView title = new TextView(this);
        title.setText("Cabinet IR");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(19);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());

        EditText search = new EditText(this);
        searchIme(search);
        search.setHint("Search WAV impulse responses");
        search.setHintTextColor(COLOR_DIM);
        search.setTextColor(COLOR_TEXT);
        search.setSingleLine(true);
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        search.setBackground(panelBackground(COLOR_SURFACE_RAISED, COLOR_BORDER));
        content.addView(search, topMargin(matchWrap(), 12));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(340)), 10));
        Runnable populate = () -> {
            list.removeAllViews();
            String filter = search.getText().toString().trim().toLowerCase(Locale.US);
            int shown = 0;
            for (ExternalIrFile file : externalIrFiles) {
                String searchable = (file.name + " " + file.relativePath)
                        .toLowerCase(Locale.US);
                if (!filter.isEmpty() && !searchable.contains(filter)) continue;
                Button row = chipButton(file.name);
                row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                styleChipButton(row, file.uri.equals(activeNamIrUri));
                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    loadNamIr(file, true);
                });
                list.addView(row, topMargin(matchWrap(), shown++ == 0 ? 0 : 6));
            }
            if (shown == 0) {
                TextView empty = labelText(externalSf2TreeUri == null
                        ? "Choose a sound library folder first"
                        : "No WAV files found in this folder");
                empty.setTextColor(COLOR_MUTED);
                list.addView(empty, matchWrap());
                Button folder = chipButton("Choose Folder");
                folder.setOnClickListener(v -> {
                    dialog.dismiss();
                    pickExternalSf2Folder();
                });
                list.addView(folder, topMargin(matchWrap(), 10));
            }
        };
        populate.run();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { populate.run(); }
        });
        dialog.setContentView(content);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth(0.90f, 620),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
    }

    private ExternalSf2File findGuitarArticulation(boolean harmonic) {
        ExternalSf2File fallback = null;
        for (ExternalSf2File file : externalSf2Files) {
            String name = file.name.toLowerCase(Locale.US)
                    .replace(" ", "").replace("_", "").replace("-", "");
            if (harmonic) {
                if (name.contains("prsguitarharmsfrets")) return file;
                if (name.contains("guitarharmonic") || name.contains("guitarharms")) {
                    fallback = file;
                }
            } else {
                if (name.contains("palmmutedguitar")) return file;
                if (name.contains("palmmute") || name.contains("mutedguitar")) {
                    fallback = file;
                }
            }
        }
        return fallback;
    }

    private void ensureVirtualGuitarArticulations() {
        if (guitarArticulationsLoading) return;
        final ExternalSf2File palm = findGuitarArticulation(false);
        final ExternalSf2File harm = findGuitarArticulation(true);
        boolean needPalm = palm != null && !palm.uri.equals(loadedGuitarPalmUri);
        boolean needHarm = harm != null && !harm.uri.equals(loadedGuitarHarmUri);
        if (!needPalm && !needHarm) return;
        guitarArticulationsLoading = true;
        if (soundLoadingText != null) {
            soundLoadingText.setText("Loading guitar articulations...");
            soundLoadingText.setVisibility(View.VISIBLE);
        }
        guitarArticulationLoader.execute(() -> {
            boolean palmOk = !needPalm;
            boolean harmOk = !needHarm;
            if (needPalm) {
                byte[] bytes = readExternalSf2(Uri.parse(palm.uri), palm.size);
                palmOk = bytes != null && engine.loadHqFont(
                        GUITAR_PALM_SLOT, 0, -9.0f, bytes);
            }
            if (needHarm) {
                byte[] bytes = readExternalSf2(Uri.parse(harm.uri), harm.size);
                // PRS Harms/Frets stores "Harmonics 1" at preset index 2.
                int preset = harm.name.toLowerCase(Locale.US).contains("prs") ? 2 : 0;
                harmOk = bytes != null && engine.loadHqFont(
                        GUITAR_HARM_SLOT, preset, -11.0f, bytes);
            }
            final boolean loadedPalm = palmOk;
            final boolean loadedHarm = harmOk;
            handler.post(() -> {
                guitarArticulationsLoading = false;
                if (needPalm && loadedPalm) loadedGuitarPalmUri = palm.uri;
                if (needHarm && loadedHarm) loadedGuitarHarmUri = harm.uri;
                if (soundLoadingText != null) soundLoadingText.setVisibility(View.GONE);
                refreshPianoGuitarRig();
            });
        });
    }

    private View buildPianoGuitarRigPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        TextView heading = labelText("AMP · CABINET · ARTICULATION");
        heading.setTextColor(COLOR_AMBER);
        panel.addView(heading, matchWrap());

        pianoGuitarRigButton = chipButton("Rig");
        pianoGuitarRigButton.setOnClickListener(v -> {
            boolean selectedSideIsGuitar = pianoGuitarVoice(dualOn && liveTab == 1);
            if (!selectedSideIsGuitar) {
                Toast.makeText(this, "Mark this external SF2 as a guitar voice first",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            pianoGuitarRigOn = !pianoGuitarRigOn;
            savePianoGuitarRig();
            pushPianoGuitarRig();
            refreshPianoGuitarRig();
        });

        pianoGuitarAmpButton = chipButton("Amp");
        pianoGuitarAmpButton.setOnClickListener(v -> {
            pianoGuitarAmp = (pianoGuitarAmp + 1) % PIANO_GUITAR_AMP_NAMES.length;
            savePianoGuitarRig();
            pushPianoGuitarRig();
            refreshPianoGuitarRig();
        });

        pianoGuitarCabButton = chipButton("Cab");
        pianoGuitarCabButton.setOnClickListener(v -> {
            pianoGuitarCab = (pianoGuitarCab + 1) % CAB_NAMES.length;
            savePianoGuitarRig();
            pushPianoGuitarRig();
            refreshPianoGuitarRig();
        });

        pianoGuitarDriveButton = chipButton("Drive");
        pianoGuitarDriveButton.setOnClickListener(v -> levelDialog(
                "Preamp drive", 100, Math.round(pianoGuitarDrive * 100), p -> {
                    pianoGuitarDrive = p / 100f;
                    savePianoGuitarRig();
                    pushPianoGuitarRig();
                    refreshPianoGuitarRig();
                    return p + "%";
                }));

        pianoGuitarToneButton = chipButton("Tone");
        pianoGuitarToneButton.setOnClickListener(v -> levelDialog(
                "Amp tone", 100, Math.round(pianoGuitarTone * 100), p -> {
                    pianoGuitarTone = p / 100f;
                    savePianoGuitarRig();
                    pushPianoGuitarRig();
                    refreshPianoGuitarRig();
                    return p + "%";
                }));

        pianoGuitarHarmButton = chipButton("Harmonics");
        pianoGuitarHarmButton.setOnClickListener(v -> levelDialog(
                "Velocity harmonics", 100, Math.round(pianoGuitarHarmonics * 100), p -> {
                    pianoGuitarHarmonics = p / 100f;
                    savePianoGuitarRig();
                    pushPianoGuitarRig();
                    refreshPianoGuitarRig();
                    return p + "%";
                }));

        pianoGuitarNamButton = chipButton("NAM");
        pianoGuitarNamButton.setOnClickListener(v -> {
            if (!engine.namReady()) {
                showNamModelPicker();
                return;
            }
            pianoGuitarNamOn = !pianoGuitarNamOn;
            if (pianoGuitarNamOn) pianoGuitarRigOn = false;
            savePianoGuitarRig();
            pushPianoGuitarRig();
            refreshPianoGuitarRig();
        });

        panel.addView(pillGrid(4, pianoGuitarRigButton,
                pianoGuitarAmpButton, pianoGuitarCabButton, pianoGuitarNamButton),
                topMargin(matchWrap(), 6));

        pianoGuitarMarkButton = chipButton("Guitar SF");
        pianoGuitarMarkButton.setOnClickListener(v -> {
            String uri = selectedExternalGuitarUri();
            if (uri == null) return;
            if (guitarSf2Uris.contains(uri)) guitarSf2Uris.remove(uri);
            else guitarSf2Uris.add(uri);
            prefs.edit().putStringSet("guitar_sf2_uris",
                    new HashSet<>(guitarSf2Uris)).apply();
            pushPianoGuitarRig();
            refreshPianoGuitarRig();
        });
        virtualGuitarPlayerButton = chipButton("Player");
        virtualGuitarPlayerButton.setOnClickListener(v -> {
            virtualGuitarPlayerOn = !virtualGuitarPlayerOn;
            savePianoGuitarRig();
            pushPianoGuitarRig();
            refreshPianoGuitarRig();
        });
        panel.addView(pillGrid(5, pianoGuitarDriveButton, pianoGuitarToneButton,
                pianoGuitarHarmButton, virtualGuitarPlayerButton,
                pianoGuitarMarkButton), topMargin(matchWrap(), 6));

        pianoGuitarNamModelButton = chipButton("NAM Model");
        pianoGuitarNamModelButton.setSingleLine(true);
        pianoGuitarNamModelButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pianoGuitarNamModelButton.setOnClickListener(v -> showNamModelPicker());
        pianoGuitarNamMixButton = chipButton("NAM Mix");
        pianoGuitarNamMixButton.setOnClickListener(v -> levelDialog(
                "NAM wet mix", 100, Math.round(pianoGuitarNamMix * 100f), p -> {
                    pianoGuitarNamMix = p / 100f;
                    savePianoGuitarRig();
                    pushPianoGuitarRig();
                    refreshPianoGuitarRig();
                    return p + "%";
                }));
        pianoGuitarNamInputButton = chipButton("NAM Input");
        pianoGuitarNamInputButton.setOnClickListener(v -> levelDialog(
                "NAM input gain", 36, Math.round(pianoGuitarNamInputDb + 18f), p -> {
                    pianoGuitarNamInputDb = p - 18f;
                    savePianoGuitarRig();
                    pushPianoGuitarRig();
                    refreshPianoGuitarRig();
                    return String.format(Locale.US, "%+.0f dB", pianoGuitarNamInputDb);
                }));
        pianoGuitarNamOutputButton = chipButton("NAM Output");
        pianoGuitarNamOutputButton.setOnClickListener(v -> levelDialog(
                "NAM output gain", 36, Math.round(pianoGuitarNamOutputDb + 18f), p -> {
                    pianoGuitarNamOutputDb = p - 18f;
                    savePianoGuitarRig();
                    pushPianoGuitarRig();
                    refreshPianoGuitarRig();
                    return String.format(Locale.US, "%+.0f dB", pianoGuitarNamOutputDb);
                }));
        pianoGuitarNamIrModelButton = chipButton("IR Model");
        pianoGuitarNamIrModelButton.setSingleLine(true);
        pianoGuitarNamIrModelButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pianoGuitarNamIrModelButton.setOnClickListener(v -> showNamIrPicker());
        pianoGuitarNamIrButton = chipButton("IR");
        pianoGuitarNamIrButton.setOnClickListener(v -> {
            if (!engine.namIrReady()) {
                showNamIrPicker();
                return;
            }
            pianoGuitarNamIrOn = !pianoGuitarNamIrOn;
            savePianoGuitarRig();
            pushPianoGuitarRig();
            refreshPianoGuitarRig();
        });
        panel.addView(pillGrid(3, pianoGuitarNamModelButton, pianoGuitarNamMixButton,
                pianoGuitarNamIrModelButton),
                topMargin(matchWrap(), 6));
        panel.addView(pillGrid(3, pianoGuitarNamInputButton,
                pianoGuitarNamOutputButton, pianoGuitarNamIrButton),
                topMargin(matchWrap(), 6));
        refreshPianoGuitarRig();
        return panel;
    }

    private void refreshPianoGuitarRig() {
        boolean guitarA = pianoGuitarVoice(false);
        boolean guitarB = dualOn && pianoGuitarVoice(true);
        boolean hasExternal = activeExternalMainUri != null
                || (dualOn && activeExternalDualUri != null);
        if (pianoGuitarRigPanel != null) {
            pianoGuitarRigPanel.setVisibility(
                    guitarA || guitarB || hasExternal ? View.VISIBLE : View.GONE);
        }
        if (pianoGuitarRigButton != null) {
            pianoGuitarRigButton.setText(pianoGuitarRigOn ? "Rig On" : "Bypass");
            styleChipButton(pianoGuitarRigButton,
                    pianoGuitarRigOn && (guitarA || guitarB));
        }
        if (pianoGuitarAmpButton != null) {
            pianoGuitarAmpButton.setText("Amp: " + PIANO_GUITAR_AMP_NAMES[pianoGuitarAmp]);
            styleChipButton(pianoGuitarAmpButton, pianoGuitarRigOn);
        }
        if (pianoGuitarCabButton != null) {
            pianoGuitarCabButton.setText("Cab: " + CAB_NAMES[pianoGuitarCab]);
            styleChipButton(pianoGuitarCabButton, pianoGuitarRigOn);
        }
        if (pianoGuitarNamButton != null) {
            pianoGuitarNamButton.setText(namLoading ? "NAM Loading"
                    : pianoGuitarNamOn && engine.namReady() ? "NAM On" : "NAM Off");
            styleChipButton(pianoGuitarNamButton,
                    pianoGuitarNamOn && engine.namReady());
        }
        if (pianoGuitarDriveButton != null) {
            pianoGuitarDriveButton.setText("Drive " + Math.round(pianoGuitarDrive * 100) + "%");
        }
        if (pianoGuitarToneButton != null) {
            pianoGuitarToneButton.setText("Tone " + Math.round(pianoGuitarTone * 100) + "%");
        }
        if (pianoGuitarHarmButton != null) {
            pianoGuitarHarmButton.setText(
                    "Harm " + Math.round(pianoGuitarHarmonics * 100) + "%");
            styleChipButton(pianoGuitarHarmButton,
                    pianoGuitarRigOn && pianoGuitarHarmonics > 0f);
        }
        if (virtualGuitarPlayerButton != null) {
            boolean articulationReady =
                    loadedGuitarPalmUri != null || loadedGuitarHarmUri != null;
            virtualGuitarPlayerButton.setText(!virtualGuitarPlayerOn ? "Player Off"
                    : articulationReady ? "Player + Art" : "Player On");
            styleChipButton(virtualGuitarPlayerButton,
                    virtualGuitarMidiMode && virtualGuitarPlayerOn);
        }
        if (pianoGuitarMarkButton != null) {
            String uri = selectedExternalGuitarUri();
            pianoGuitarMarkButton.setVisibility(uri == null ? View.GONE : View.VISIBLE);
            if (uri != null) {
                boolean marked = guitarSf2Uris.contains(uri);
                pianoGuitarMarkButton.setText(marked ? "Guitar SF" : "Mark Guitar");
                styleChipButton(pianoGuitarMarkButton, marked);
            }
        }
        if (pianoGuitarNamModelButton != null) {
            pianoGuitarNamModelButton.setText(namLoading ? "Loading..."
                    : activeNamName());
            styleChipButton(pianoGuitarNamModelButton, engine.namReady());
        }
        if (pianoGuitarNamMixButton != null) {
            pianoGuitarNamMixButton.setText(
                    "Mix " + Math.round(pianoGuitarNamMix * 100f) + "%");
            styleChipButton(pianoGuitarNamMixButton,
                    pianoGuitarNamOn && pianoGuitarNamMix > 0f);
        }
        if (pianoGuitarNamInputButton != null) {
            pianoGuitarNamInputButton.setText(String.format(
                    Locale.US, "In %+.0f dB", pianoGuitarNamInputDb));
        }
        if (pianoGuitarNamOutputButton != null) {
            pianoGuitarNamOutputButton.setText(String.format(
                    Locale.US, "Out %+.0f dB", pianoGuitarNamOutputDb));
        }
        if (pianoGuitarNamIrButton != null) {
            pianoGuitarNamIrButton.setText(namIrLoading ? "IR Loading"
                    : pianoGuitarNamIrOn && engine.namIrReady() ? "IR On" : "IR Bypass");
            styleChipButton(pianoGuitarNamIrButton,
                    pianoGuitarNamIrOn && engine.namIrReady());
        }
        if (pianoGuitarNamIrModelButton != null) {
            pianoGuitarNamIrModelButton.setText(activeNamIrName());
            styleChipButton(pianoGuitarNamIrModelButton, engine.namIrReady());
        }
    }

    // The A/B tabbed Live Controls share one panel; Split reveals Keyboard B.
    private View buildLiveControlsSection(int accent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        if (!dualOn) liveTab = 0;
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        liveTabAButton = transportPill("KEYBOARD A");
        liveTabBButton = transportPill("KEYBOARD B");
        LinearLayout.LayoutParams tabALp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tabALp.rightMargin = dp(4);
        tabs.addView(liveTabAButton, tabALp);
        LinearLayout.LayoutParams tabBLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tabBLp.leftMargin = dp(4);
        tabs.addView(liveTabBButton, tabBLp);
        box.addView(tabs, matchWrap());

        liveControlView = new LiveControlView(this);
        liveControlView.setValues(liveControlValues);
        liveControlView.setControlsChangedListener(this::applyLiveControls);
        liveControlViewB = new LiveControlView(this);
        liveControlViewB.setValues(liveControlValuesB);
        liveControlViewB.setControlsChangedListener(this::applyLiveControlsB);

        FrameLayout controlHost = new FrameLayout(this);
        controlHost.addView(liveControlView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        controlHost.addView(liveControlViewB, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        box.addView(controlHost, topMargin(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(150)), 10));

        liveTabAButton.setOnClickListener(v -> {
            liveTab = 0;
            refreshLiveControlTabs();
        });
        liveTabBButton.setOnClickListener(v -> {
            if (!dualOn) return;
            liveTab = 1;
            refreshLiveControlTabs();
        });
        refreshLiveControlTabs();
        return box;
    }

    private void refreshLiveControlTabs() {
        if (!dualOn && liveTab == 1) liveTab = 0;
        boolean showB = dualOn && liveTab == 1;
        if (liveControlView != null) {
            liveControlView.setVisibility(showB ? View.GONE : View.VISIBLE);
        }
        if (liveControlViewB != null) {
            liveControlViewB.setVisibility(showB ? View.VISIBLE : View.GONE);
        }
        if (liveTabAButton != null) {
            styleTogglePill(liveTabAButton, !showB);
        }
        if (liveTabBButton != null) {
            liveTabBButton.setVisibility(dualOn ? View.VISIBLE : View.GONE);
            styleTogglePill(liveTabBButton, showB);
        }
        refreshPianoGuitarRig();
    }

    private void rebuildPresetButtons() {
        presetButtons.clear();
        if (presetGrid == null) {
            return;
        }
        presetGrid.removeAllViews();

        LinearLayout row = null;
        int shown = 0;
        for (TonePreset preset : TonePreset.forMode(currentMode)) {
            if (showFavoritesOnly && !isFavorite(preset)) {
                continue;
            }
            if (row == null) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                presetGrid.addView(row, topMargin(matchWrap(), presetGrid.getChildCount() == 0 ? 0 : 8));
            }
            Button button = toneButton(preset.displayText());
            button.setTag(preset);
            button.setOnClickListener(v -> selectPreset(preset));
            presetButtons.put(button, preset);
            row.addView(button, toneParams(row.getChildCount() == 0));
            if (row.getChildCount() >= 2) {
                row = null;
            }
            shown++;
        }
        if (shown == 0) {
            presetGrid.addView(detailText("No favorites yet — tap the star on the preset bar above."),
                    matchWrap());
        }
        styleTabs();
        updateSelectionStyles();
    }

    private String pianoCategory(TonePreset preset) {
        return preset.category == null || preset.category.isEmpty() ? "Other" : preset.category;
    }

    private void rebuildRouteButtons() {
        routeButtons.clear();
        if (routeRow == null) {
            return;
        }

        routeRow.removeAllViews();
        InputRoute[] routes = (currentMode == InstrumentMode.PIANO || currentMode == InstrumentMode.DRUMS)
                ? new InputRoute[]{InputRoute.MIDI}
                : new InputRoute[]{InputRoute.USB};
        for (int i = 0; i < routes.length; i++) {
            InputRoute route = routes[i];
            Button button = chipButton(routeChipLabel(route));
            button.setOnClickListener(v -> selectRoute(route));
            routeButtons.put(button, route);
            routeRow.addView(button, chipParams(i < routes.length - 1));
        }
        updateSelectionStyles();
    }

    private String routeChipLabel(InputRoute route) {
        if (route == InputRoute.MIDI && currentMode == InstrumentMode.DRUMS) {
            return "MIDI / Pads";
        }
        if (route == InputRoute.USB) {
            return currentMode == InstrumentMode.PIANO ? "USB-C Audio" : "USB-C Audio Port";
        }
        return route.label;
    }

    private void updateSelectionStyles() {
        for (Map.Entry<Button, TonePreset> entry : presetButtons.entrySet()) {
            styleToneButton(entry.getKey(), entry.getValue() == currentPreset);
        }
        for (Map.Entry<Button, InputRoute> entry : routeButtons.entrySet()) {
            styleChipButton(entry.getKey(), entry.getValue() == currentRoute);
        }

        if (startButton != null) {
            stylePrimaryButton(startButton, engine.isRunning());
        }
        if (liveControlView != null) {
            liveControlView.setRig(currentMode, currentPreset, currentRoute, engine.isRunning());
        }
        if (liveControlViewB != null) {
            liveControlViewB.setRig(currentMode, dualPreset, currentRoute, engine.isRunning());
        }
        if (signalChainView != null) {
            signalChainView.setAccent(toneAccentStatic(currentPreset));
        }
        if (pianoKeysView != null) {
            pianoKeysView.setAccent(toneAccentStatic(currentPreset));
        }
        if (drumPadsView != null) {
            drumPadsView.setAccent(toneAccentStatic(currentPreset));
        }
        if (favStar != null) {
            favStar.setTextColor(isFavorite(currentPreset) ? COLOR_AMBER : COLOR_DIM);
        }
        updateSummaryText();
        updateStatusIndicator();
    }

    private void updateSummaryText() {
        if (toneText != null) {
            toneText.setText(currentMode == InstrumentMode.PIANO
                    ? pianoSoundName(false) : currentPreset.label);
        }
        if (soundBarText != null) {
            soundBarText.setText(currentMode == InstrumentMode.PIANO
                    ? pianoSoundName(false) : currentPreset.label);
        }
    }

    private void refreshMeter() {
        if (!onInstrumentScreen) {
            return;
        }
        boolean running = engine.isRunning();
        float level = running ? engine.inputLevel() : -120.0f;
        float pitch = running ? engine.pitchHz() : 0.0f;

        if (running && currentRoute != InputRoute.MIDI && level > FEEDBACK_DB) {
            feedbackTicks++;
            if (feedbackTicks >= FEEDBACK_TICKS) {
                engine.stop();
                feedbackTicks = 0;
                currentError = "Feedback detected — engine stopped. Lower the input gain or monitor with headphones.";
                if (startButton != null) {
                    startButton.setText("Start Engine");
                }
                updateSelectionStyles();
                return;
            }
        } else {
            feedbackTicks = 0;
        }

        if (inMeter != null) {
            inMeter.setLevelDb(level);
        }
        if (outMeter != null) {
            outMeter.setLevelDb(running ? engine.outputLevel() : -120.0f);
        }
        // Live measured output latency on the status label — makes a slow
        // route (Bluetooth, a bad adapter) visible instead of guessed at.
        if (running && statusText != null) {
            float lm = engine.outputLatencyMs();
            statusText.setText(lm > 0.5f
                    ? "Running · " + Math.round(lm) + " ms" : "Running");
        }
        if (meterDbText != null) {
            meterDbText.setText(running ? String.format(Locale.US, "%.1f dB", level) : "-inf dB");
        }
        if (currentMode == InstrumentMode.PIANO) {
            int detected = running && currentRoute == InputRoute.USB ? nearestMidiNote(pitch) : -1;
            if (pianoKeysView != null) {
                pianoKeysView.setDetectedNote(detected);
            }
            if (pianoNotesText != null) {
                String held = heldNotesSummary();
                if (held != null) {
                    pianoNotesText.setText(held);
                } else if (detected >= 0) {
                    pianoNotesText.setText(noteName(detected) + "   (audio)");
                } else {
                    pianoNotesText.setText("--");
                }
            }
        }
        if (liveControlView != null) {
            liveControlView.setMeter(level, pitch, running);
        }
        if (liveControlViewB != null) {
            liveControlViewB.setMeter(level, pitch, running);
        }
        updateSelectionStyles();
    }

    private void refreshDeviceStatus() {
        if (usbText != null) {
            usbText.setText(router.usbSummary() + "\n" + midiDeviceLabel);
        }
        if (deviceText != null) {
            deviceText.setText(router.capabilitySummary() + "\n" + router.outputsDebugSummary());
        }
        updateRouteChip();
    }

    private void setupMidiInput() {
        if (midiManager == null) {
            midiDeviceLabel = "MIDI: unavailable on this device";
            return;
        }

        midiDeviceCallback = new MidiManager.DeviceCallback() {
            @Override
            public void onDeviceAdded(MidiDeviceInfo device) {
                openMidiDevices();
            }

            @Override
            public void onDeviceRemoved(MidiDeviceInfo device) {
                openMidiDevices();
            }
        };
        midiManager.registerDeviceCallback(midiDeviceCallback, handler);

        openMidiDevices();
    }

    // Open every detected MIDI keyboard (up to two). The first one is
    // player 1; a second keyboard becomes player 2 and plays Sound 2
    // whenever Dual is on — two players, two sounds, one phone.
    private void openMidiDevices() {
        closeMidiPorts();
        if (midiManager == null) {
            midiInputAvailable = false;
            midiDeviceLabel = "MIDI: unavailable on this device";
            refreshDeviceStatus();
            return;
        }

        java.util.List<MidiDeviceInfo> found = new java.util.ArrayList<>();
        for (MidiDeviceInfo info : midiManager.getDevices()) {
            if (info.getOutputPortCount() > 0) {
                found.add(info);
                if (found.size() == 2) break;
            }
        }

        if (found.isEmpty()) {
            midiInputAvailable = false;
            midiDeviceLabel = "MIDI: not detected";
            refreshDeviceStatus();
            return;
        }

        final StringBuilder names = new StringBuilder();
        for (int i = 0; i < found.size(); i++) {
            final MidiDeviceInfo deviceInfo = found.get(i);
            final int playerIdx = i;
            if (names.length() > 0) names.append(" + ");
            names.append(midiName(deviceInfo));
            midiManager.openDevice(deviceInfo, device -> {
                if (device == null) {
                    if (playerIdx == 0) {
                        midiInputAvailable = false;
                        midiDeviceLabel = "MIDI: failed to open";
                        refreshDeviceStatus();
                    }
                    return;
                }
                MidiOutputPort port = device.openOutputPort(0);
                if (port == null) {
                    try {
                        device.close();
                    } catch (IOException ignored) {
                    }
                    if (playerIdx == 0) {
                        midiInputAvailable = false;
                        midiDeviceLabel = "MIDI: no output port";
                        refreshDeviceStatus();
                    }
                    return;
                }
                midiDevices.add(device);
                midiOutputPorts.add(port);
                // Each keyboard gets its own receiver so notes carry their
                // player number into the routing below.
                port.connect(new MidiReceiver() {
                    @Override
                    public void onSend(byte[] msg, int offset, int count, long timestamp) {
                        handleMidiMessage(msg, offset, count, playerIdx);
                    }
                });
                midiInputAvailable = true;
                midiDeviceLabel = "MIDI: " + names;
                if (currentMode == InstrumentMode.PIANO) {
                    currentRoute = InputRoute.MIDI;
                    rebuildRouteButtons();
                }
                refreshDeviceStatus();
            }, handler);
        }
    }

    private void closeMidiInput() {
        if (midiManager != null && midiDeviceCallback != null) {
            midiManager.unregisterDeviceCallback(midiDeviceCallback);
            midiDeviceCallback = null;
        }
        closeMidiPorts();
    }

    private void closeMidiPorts() {
        for (MidiOutputPort port : midiOutputPorts) {
            try {
                port.close();
            } catch (IOException ignored) {
            }
        }
        midiOutputPorts.clear();
        for (MidiDevice device : midiDevices) {
            try {
                device.close();
            } catch (IOException ignored) {
            }
        }
        midiDevices.clear();
        // The keyboard may vanish mid-hold: without its CC64 release the
        // pedal flag would keep sustained notes ringing forever.
        engine.setSustainPedal(false);
        midiPedalDown = false;
        java.util.Arrays.fill(midiRunStatus, 0);
        java.util.Arrays.fill(midiHasData, false);
        java.util.Arrays.fill(midiInSysEx, false);
    }

    // Piano's MIDI input settings: which keyboards are connected, who is
    // player 1 / player 2, and the swap between them.
    private void midiKeyboardsDialog() {
        final Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(dialogSheet());
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(16), dp(18), dp(14));
        TextView title = new TextView(this);
        title.setText("MIDI input");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(17);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        content.addView(title, matchWrap());
        int n = Math.min(midiDevices.size(), midiOutputPorts.size());
        if (n == 0) {
            content.addView(detailText("No MIDI keyboard detected — plug one in over USB-C and it connects automatically."),
                    topMargin(matchWrap(), 10));
        } else {
            for (int i = 0; i < n; i++) {
                int player = midiSwapPlayers && n > 1 && i <= 1 ? 1 - i : i;
                TextView row = new TextView(this);
                row.setText("Player " + (player + 1) + "  ·  " + midiName(midiDevices.get(i).getInfo()));
                row.setTextColor(COLOR_TEXT);
                row.setTextSize(13);
                content.addView(row, topMargin(matchWrap(), 8));
            }
            content.addView(detailText(n > 1
                    ? "With Dual on, player 2's whole keyboard plays Sound 2."
                    : "Connect a second keyboard for two players — with Dual on it plays Sound 2."),
                    topMargin(matchWrap(), 8));
            if (n > 1) {
                content.addView(menuItem("⇄  Swap players" + (midiSwapPlayers ? "  ●" : ""), () -> {
                    dialog.dismiss();
                    toggleMidiSwap();
                }), topMargin(matchWrap(), 10));
            }
        }
        presentMenu(dialog, content, dialogWidth(0.82f, 460));
    }

    private void toggleMidiSwap() {
        midiSwapPlayers = !midiSwapPlayers;
        prefs.edit().putBoolean("midi_swap", midiSwapPlayers).apply();
        engine.allNotesOff();   // roles changed mid-hold: nothing may keep ringing
        Toast.makeText(this, midiSwapPlayers
                ? "Players swapped — keyboard 2 is now player 1"
                : "Players back to plug-in order", Toast.LENGTH_SHORT).show();
    }

    private void handleMidiMessage(byte[] message, int offset, int count, int playerIdx) {
        // Parser state is keyed by the physical port; the player swap is
        // applied only when a complete message is dispatched.
        int port = Math.min(Math.max(playerIdx, 0), midiRunStatus.length - 1);
        int player = playerIdx;
        if (midiSwapPlayers && playerIdx <= 1 && midiOutputPorts.size() > 1) {
            player = 1 - playerIdx;
        }
        int end = offset + count;
        for (int index = offset; index < end; index++) {
            int b = message[index] & 0xFF;
            if (b >= 0xF8) {
                continue;              // real-time byte: never disturbs a message
            }
            if (b >= 0xF0) {           // system common cancels running status
                midiRunStatus[port] = 0;
                midiHasData[port] = false;
                midiInSysEx[port] = b == 0xF0;
                continue;
            }
            if (b >= 0x80) {           // new channel status
                midiRunStatus[port] = b;
                midiHasData[port] = false;
                midiInSysEx[port] = false;
                continue;
            }
            // Data byte: belongs to the last status seen, even if that status
            // arrived in an earlier callback or was omitted (running status).
            if (midiInSysEx[port]) {
                continue;
            }
            int status = midiRunStatus[port];
            if (status == 0) {
                continue;
            }
            int command = status & 0xF0;
            if (command == 0xC0 || command == 0xD0) {
                continue;              // 1-data-byte messages: nothing to do
            }
            if (!midiHasData[port]) {
                midiPendData[port] = b;
                midiHasData[port] = true;
                continue;
            }
            midiHasData[port] = false;
            dispatchMidi(status, midiPendData[port], b, player);
        }
    }

    private void dispatchMidi(int status, int data1, int data2, int playerIdx) {
        int command = status & 0xF0;
        if (command == 0x90 || command == 0x80) {
            int channel0 = status & 0x0F;
            int note = data1;
            int velocity = data2;
            boolean on = command == 0x90 && velocity > 0;
            {
                if (handlePedalMidi(1000 + note, on)) {
                    return;
                }
                if (onMidiAssignScreen) {
                    if (on) {
                        handler.post(() -> onMidiLearn(note, channel0));
                    }
                    return;
                }
                if (onLoopMix) {
                    // A MIDI keyboard drives the looper keys: notes play the
                    // melodic key channel (and record into loops 1-3), not drums.
                    // Dual: routed explicitly here so hardware always splits —
                    // notes from the split key up go straight to Sound 2 (in
                    // separate mode too, where the split key still divides MIDI).
                    // A second keyboard is player 2: its whole range is Sound 2.
                    boolean player2 = playerIdx == 1 && loopDualOn;
                    if (on) {
                        // Chord mode covers MIDI too: one key sounds the triad.
                        // The exact notes are remembered so the release always
                        // matches — nothing can ring on after the key lifts.
                        int[] chord = !player2 && loopKeysChord && loopKeysView != null
                                ? loopKeysView.chordNotes(note, true) : null;
                        if (chord != null && chord.length > 1) {
                            midiChordHeld[note] = chord;
                            for (int i = 0; i < chord.length; i++) {
                                int n = chord[i];
                                if (n < 0 || n > 127) continue;
                                float v = (i == 0 ? 1.0f : 0.8f) * velocity / 127.0f;
                                if (loopDualOn && n >= loopDualSplit) {
                                    engine.loopKey2On(n, v);
                                } else {
                                    engine.loopKeyOn(n, v);
                                }
                            }
                        } else if (player2 || (loopDualOn && note >= loopDualSplit)) {
                            engine.loopKey2On(note, velocity / 127.0f);
                        } else {
                            engine.loopKeyOn(note, velocity / 127.0f);
                        }
                    } else {
                        int[] chord = midiChordHeld[note];
                        midiChordHeld[note] = null;
                        if (chord != null) {
                            for (int n : chord) {
                                if (n < 0 || n > 127) continue;
                                if (loopDualOn && n >= loopDualSplit) {
                                    engine.loopKey2Off(n);
                                } else {
                                    engine.loopKeyOff(n);
                                }
                            }
                        } else if (player2 || (loopDualOn && note >= loopDualSplit)) {
                            engine.loopKey2Off(note);
                        } else {
                            engine.loopKeyOff(note);
                        }
                    }
                    return;
                }
                if (currentMode == InstrumentMode.DRUMS) {
                    int mapped = mapDrumNote(note, channel0);
                    if (mapped >= 0) {
                        if (mapped == CHIMES_MIDI_NOTE) {
                            if (on) engine.triggerChimes();
                            setKeyPressed(mapped, on);
                            return;
                        }
                        if (mapped == SWELL_FIRST_MIDI_NOTE) {
                            if (on) engine.triggerSwell(drumSwellVariant);
                            setKeyPressed(mapped, on);
                            return;
                        }
                        if (on) {
                            float v = velocity / 127.0f;
                            if (cymChokeVel > 0f && v < cymChokeVel && isChokeCymbal(mapped)) {
                                // A very soft cymbal hit = choke (edge touch on
                                // an e-drum cymbal), not a strike.
                                engine.chokeCymbals();
                            } else {
                                // Velocity cymbals, same rule as the pads: a hard
                                // ride crashes, a soft crash rides.
                                int eff = coerceCrash(coerceRide(mapped, v), v);
                                engine.noteOn(eff, v);
                                setKeyPressed(eff, on);
                            }
                        } else {
                            engine.noteOff(mapped);
                            setKeyPressed(mapped, on);
                        }
                    }
                    return;
                }
                // Piano dual: route Sound 2 here in Java, exactly like the
                // looper — hardware keyboards then always split correctly.
                // A second keyboard is player 2: its whole range is Sound 2.
                boolean pianoSound2 = dualOn && currentMode == InstrumentMode.PIANO
                        && (playerIdx == 1 || note >= dualSplit);
                if (on) {
                    if (pianoSound2) {
                        engine.note2On(note, velocity / 127.0f);
                    } else {
                        engine.noteOn(note, velocity / 127.0f);
                    }
                } else {
                    if (pianoSound2) {
                        engine.note2Off(note);
                    } else {
                        engine.noteOff(note);
                    }
                }
                setKeyPressed(note, on);
            }
        } else if (command == 0xB0) {
            int controller = data1;
            int value = data2;
            if (handlePedalMidi(2000 + controller, value >= 64)) {
                return;
            }
            if (controller == 64) {        // sustain pedal
                final boolean pedalHeld = value >= 64;
                engine.setSustainPedal(pedalHeld);
                // Mirror the hardware pedal on the app's Sustain chip:
                // lit while held, back off when lifted (reverb untouched).
                handler.post(() -> {
                    midiPedalDown = pedalHeld;
                    refreshSustainChip();
                });
            } else if (controller == 7) {  // channel volume
                engine.setMidiVolume(value);
            } else if (controller == 11) { // expression pedal
                if (currentMode == InstrumentMode.ELECTRIC_GUITAR) {
                    // On guitar the expression pedal IS the wah pedal: sweep
                    // the manual wah's position and engage it, so both hands
                    // stay on the strings.
                    final float pos = value / 127.0f;
                    wahPos = pos;
                    wahOn = true;
                    engine.setWah(true);
                    engine.setWahPos(pos);
                    handler.post(() -> {
                        if (wahSlider != null) wahSlider.setProgress(Math.round(pos * 100));
                        if (wahButton != null) {
                            styleChipButton(wahButton, true);
                            updateEffectSlider(wahSlider, true);
                        }
                    });
                } else {
                    engine.setMidiExpression(value);
                }
            } else if (controller == 123 || controller == 120) {  // all notes / sound off
                engine.setSustainPedal(false);
                handler.post(() -> {
                    midiPedalDown = false;
                    refreshSustainChip();
                });
            }
        } else if (command == 0xE0) {
            int bend = data1 | (data2 << 7);
            // Full Keys supports +/-24 st. Keep a hardware wheel at its
            // conventional +/-2 semitone throw rather than expanding it too.
            engine.setPitchWheel(8192 + (bend - 8192) / 12);
        }
    }

    private String midiName(MidiDeviceInfo info) {
        Object name = info.getProperties().get(MidiDeviceInfo.PROPERTY_NAME);
        if (name == null) {
            name = info.getProperties().get(MidiDeviceInfo.PROPERTY_PRODUCT);
        }
        return name == null ? "USB MIDI #" + info.getId() : name.toString();
    }

    private void requestAudioPermissionIfNeeded() {
        if (!hasRecordAudioPermission()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        }
    }

    private boolean hasRecordAudioPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
        receiverRegistered = true;
    }

    private LinearLayout verticalPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        panel.setBackground(bubblyPanelBackground());
        return panel;
    }

    // A hardware-faceplate "stage" panel: a flat bordered module with an LED dot,
    // an uppercase engraved-style label, and a hairline rule under the header.
    // Body views are added by the caller after this returns.
    private LinearLayout stagePanel(String label, int accent) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(9), dp(12), dp(12));
        panel.setBackground(bubblyPanelBackground());

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View led = new View(this);
        led.setBackground(dotDrawable(accent));
        LinearLayout.LayoutParams ledLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        ledLp.rightMargin = dp(8);
        head.addView(led, ledLp);
        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(accent);
        lbl.setTextSize(10.5f);
        lbl.setLetterSpacing(0.16f);
        lbl.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(lbl, matchWrap());
        panel.addView(head, matchWrap());

        View rule = new View(this);
        rule.setBackgroundColor(COLOR_BORDER);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        rLp.topMargin = dp(8);
        rLp.bottomMargin = dp(6);
        panel.addView(rule, rLp);
        return panel;
    }

    // ---- Landscape scaffold ----
    // Every screen is a stage: a controls rail on the left and the play
    // surface (pads, keys, meters, FX) filling the right.

    private LinearLayout splitPane(View left, View right, float leftWeight, float rightWeight) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, leftWeight);
        row.addView(left, lLp);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, rightWeight);
        rLp.leftMargin = dp(14);
        row.addView(right, rLp);
        return row;
    }

    private LinearLayout paneColumn() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        return col;
    }

    // Rail column wrapped in its own scroll so short screens never clip it.
    private ScrollView railScroll(LinearLayout rail) {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        sv.addView(rail, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    // Compact screen header used at the top of every rail.
    private LinearLayout railHeader(Runnable onBack, String eyebrowText, String titleText, int accent) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(backArrowButton(onBack), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        if (eyebrowText != null) {
            TextView eyebrow = labelText(eyebrowText);
            eyebrow.setTextColor(accent);
            titleCol.addView(eyebrow, matchWrap());
        }
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(COLOR_TEXT);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextSize(20);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleCol.addView(title, matchWrap());
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = dp(12);
        bar.addView(titleCol, tLp);
        return bar;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_TEXT);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setTextSize(15);
        return view;
    }

    private TextView labelText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setTextSize(11);
        view.setLetterSpacing(0.08f);
        return view;
    }

    private TextView detailText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_MUTED);
        view.setTextSize(13);
        view.setLineSpacing(dp(2), 1.0f);
        return view;
    }

    private Button toneButton(String text) {
        Button button = baseButton(text);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setMinHeight(dp(72));
        button.setTextSize(12.5f);
        button.setPadding(dp(13), dp(9), dp(11), dp(9));
        return button;
    }

    private Button chipButton(String text) {
        Button button = baseButton(text);
        button.setMinHeight(dp(44));
        button.setTextSize(13);
        return button;
    }

    private Button primaryButton(String text) {
        Button button = baseButton(text);
        button.setMinHeight(dp(58));
        button.setTextSize(17);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return button;
    }

    private Button baseButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(false);
        button.setMinWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setTextColor(contrastTextColor(COLOR_SURFACE_RAISED));
        button.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        button.setStateListAnimator(null);
        button.setBackground(animatedButtonBackground(
                COLOR_SURFACE_RAISED, dp(8), COLOR_TEAL));
        return button;
    }

    private void styleToneButton(Button button, boolean selected) {
        int accent = COLOR_AMBER;
        Object tag = button.getTag();
        if (tag instanceof TonePreset) {
            accent = toneAccent((TonePreset) tag);
        }
        int fill = selected ? darken(accent) : COLOR_SURFACE_RAISED;
        button.setBackground(animatedButtonBackground(fill, dp(8), accent));
        button.setTextColor(contrastTextColor(fill));
    }

    private void styleChipButton(Button button, boolean selected) {
        int fill = selected ? COLOR_TEAL : COLOR_SURFACE_RAISED;
        button.setBackground(animatedButtonBackground(
                fill, dp(999), selected ? COLOR_GREEN : COLOR_TEAL));
        button.setTextColor(contrastTextColor(fill));
    }

    private void stylePrimaryButton(Button button, boolean running) {
        int fill = running ? Color.rgb(205, 78, 86) : Color.rgb(77, 205, 201);
        button.setBackground(animatedButtonBackground(
                fill, dp(8), running ? COLOR_RED : COLOR_GREEN));
        button.setTextColor(contrastTextColor(fill));
        // Keep the label in sync with the color so they can never disagree.
        button.setText(running ? "Stop Engine" : "Start Engine");
    }

    private GradientDrawable panelBackground(int color, int strokeColor) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(color);
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), strokeColor);
        return background;
    }

    private GradientDrawable moduleBackground(int color, int strokeColor, int accentColor, boolean insetAccent) {
        // Flat fill only. LEFT_RIGHT accent gradients glitch/bleed on target
        // devices, so accent is carried by the stroke, not a gradient wash.
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(color);
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), strokeColor);
        return background;
    }

    private Drawable dialogSheet() {
        return panelBackground(COLOR_SURFACE, COLOR_BORDER_STRONG);
    }

    private int darken(int color) {
        return Color.rgb(
                Math.max(0, Math.round(Color.red(color) * 0.30f)),
                Math.max(0, Math.round(Color.green(color) * 0.30f)),
                Math.max(0, Math.round(Color.blue(color) * 0.30f))
        );
    }

    private int toneAccent(TonePreset preset) {
        if (preset.mode == InstrumentMode.BASS) {
            if (preset.nativeId == 13) {
                return Color.rgb(213, 81, 91);
            }
            if (preset.nativeId == 12) {
                return Color.rgb(76, 166, 238);
            }
            return Color.rgb(98, 198, 128);
        }
        if (preset.mode == InstrumentMode.PIANO) {
            if (preset.nativeId == 22) {
                return Color.rgb(172, 119, 232);
            }
            if (preset.nativeId == 23) {
                return Color.rgb(87, 181, 227);
            }
            return Color.rgb(228, 170, 75);
        }
        if (preset.nativeId == 3) {
            return Color.rgb(213, 81, 91);
        }
        if (preset.nativeId == 4) {
            return Color.rgb(190, 94, 219);
        }
        if (preset.nativeId == 2) {
            return Color.rgb(238, 136, 59);
        }
        return Color.rgb(98, 198, 128);
    }

    private Drawable pillBackground(int color, int strokeColor) {
        return animatedButtonBackground(color, dp(999), strokeColor);
    }

    private int contrastTextColor(int background) {
        double r = Color.red(background) / 255.0;
        double g = Color.green(background) / 255.0;
        double b = Color.blue(background) / 255.0;
        r = r <= 0.04045 ? r / 12.92 : Math.pow((r + 0.055) / 1.055, 2.4);
        g = g <= 0.04045 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4);
        b = b <= 0.04045 ? b / 12.92 : Math.pow((b + 0.055) / 1.055, 2.4);
        double luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return luminance > 0.42 ? Color.rgb(14, 34, 48) : Color.rgb(248, 252, 255);
    }

    private Drawable animatedButtonBackground(int fillColor, float radius, int accent) {
        return new RotatingButtonDrawable(fillColor, radius, dp(2), accent);
    }

    private static final class RotatingButtonDrawable extends Drawable implements Runnable {
        private static final long FRAME_MS = 33L;
        private static final float TURN_MS = 2800f;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Matrix shaderMatrix = new Matrix();
        private final float radius;
        private final float strokeWidth;
        private final int accent;
        private SweepGradient edgeShader;
        private float shaderCx;
        private float shaderCy;

        RotatingButtonDrawable(int fillColor, float radius, float strokeWidth, int accent) {
            this.radius = radius;
            this.strokeWidth = Math.max(1f, strokeWidth);
            this.accent = Color.rgb(
                    Color.red(accent), Color.green(accent), Color.blue(accent));
            fillPaint.setStyle(Paint.Style.FILL);
            int r = Color.red(fillColor);
            int g = Color.green(fillColor);
            int b = Color.blue(fillColor);
            // Keep the requested opaque fill. Callers select black or
            // near-white labels from this same color's luminance.
            fillPaint.setColor(Color.rgb(r, g, b));
            edgePaint.setStyle(Paint.Style.STROKE);
            edgePaint.setStrokeWidth(this.strokeWidth);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float inset = strokeWidth * 0.5f;
            rect.set(bounds.left + inset, bounds.top + inset,
                    bounds.right - inset, bounds.bottom - inset);
            float corner = Math.min(radius, Math.min(rect.width(), rect.height()) * 0.5f);
            canvas.drawRoundRect(rect, corner, corner, fillPaint);

            float cx = rect.centerX();
            float cy = rect.centerY();
            if (edgeShader == null || cx != shaderCx || cy != shaderCy) {
                shaderCx = cx;
                shaderCy = cy;
                edgeShader = new SweepGradient(cx, cy,
                        new int[]{
                                Color.rgb(38, 224, 255),
                                Color.rgb(35, 104, 255),
                                accent,
                                Color.rgb(216, 56, 255),
                                Color.rgb(255, 52, 190),
                                Color.rgb(38, 224, 255)
                        },
                        new float[]{0f, 0.12f, 0.34f, 0.58f, 0.82f, 1f});
                edgePaint.setShader(edgeShader);
            }
            float angle = (SystemClock.uptimeMillis() % (long) TURN_MS) * 360f / TURN_MS;
            shaderMatrix.setRotate(angle, cx, cy);
            edgeShader.setLocalMatrix(shaderMatrix);
            canvas.drawRoundRect(rect, corner, corner, edgePaint);
            scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_MS);
        }

        @Override
        public void run() {
            invalidateSelf();
        }

        @Override
        public void setAlpha(int alpha) {
            fillPaint.setAlpha(alpha);
            edgePaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            fillPaint.setColorFilter(colorFilter);
            edgePaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams topMargin(LinearLayout.LayoutParams params, int topDp) {
        params.topMargin = dp(topDp);
        return params;
    }

    private LinearLayout.LayoutParams toneParams(boolean firstColumn) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        if (firstColumn) {
            params.rightMargin = dp(8);
        }
        return params;
    }

    private LinearLayout.LayoutParams chipParams(boolean hasRightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        if (hasRightMargin) {
            params.rightMargin = dp(8);
        }
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // The expanded ("tablet") layout is used in landscape; portrait keeps the phone design.
    private boolean wideLayout() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    // dp that grows on tablets.
    private int dpT(int phone, int tablet) {
        return dp(wideLayout() ? tablet : phone);
    }

    // Dialog list height: never taller than the landscape screen allows, so
    // long lists always scroll instead of getting clipped by the window.
    // NOTE: dialog list heights are WRAP_CONTENT on purpose (no precomputed
    // height): as the last child of the dialog column the ScrollView is measured
    // with exactly the screen space left after the title/search chrome, at ANY
    // UI scale — so the end of the list is always reachable. Never give a list
    // a fixed height; if content must sit BELOW the list, cap the list with a
    // screen fraction instead (see the kit-slot picker).

    // Dialog width: a fraction of the screen on phones, capped on tablets.
    private int dialogWidth(float phoneFraction, int maxTabletDp) {
        int full = (int) (getResources().getDisplayMetrics().widthPixels * phoneFraction);
        return wideLayout() ? Math.min(full, dp(maxTabletDp)) : full;
    }

    // Full-width child that fills leftover vertical space by weight (for no-scroll layouts).
    private LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, w);
    }

    // On tablets, cap the content column and center it so controls aren't stretched.
    private void addRootContent(ScrollView scroll, LinearLayout root) {
        if (wideLayout()) {
            int w = Math.min(getResources().getDisplayMetrics().widthPixels, dp(820));
            LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setGravity(Gravity.CENTER_HORIZONTAL);
            wrap.addView(root, new LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT));
            scroll.addView(wrap, new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        } else {
            scroll.addView(root, new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        }
    }

    // Compact rotary level knob (0..1). Drag up/down to turn. Used by the Layers
    // mixer — four side by side, one per layer.
    private final class KnobView extends View {
        private float value;
        private int accent = COLOR_TEAL;
        private FloatSetter onChange;
        private float startY, startVal;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        KnobView(Context c) { super(c); }
        void setValue(float v) { value = v < 0 ? 0 : (v > 1 ? 1 : v); invalidate(); }
        void setAccent(int a) { accent = a; }
        void setOnChange(FloatSetter s) { onChange = s; }
        @Override protected void onMeasure(int w, int h) {
            int sz = Math.round(64 * getResources().getDisplayMetrics().density);
            setMeasuredDimension(resolveSize(sz, w), resolveSize(sz, h));
        }
        @Override protected void onDraw(Canvas cv) {
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - 5 * d;
            float start = 135f, sweep = 270f;
            arc.set(cx - r, cy - r, cx + r, cy + r);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(4 * d);
            p.setColor(COLOR_SKY_TRACK);
            cv.drawArc(arc, start, sweep, false, p);
            p.setColor(value > 0.001f ? accent : COLOR_BORDER_STRONG);
            cv.drawArc(arc, start, sweep * value, false, p);
            // pointer
            double ang = Math.toRadians(start + sweep * value);
            float px = cx + (float) Math.cos(ang) * (r - 3 * d);
            float py = cy + (float) Math.sin(ang) * (r - 3 * d);
            p.setStrokeWidth(3 * d);
            p.setColor(value > 0.001f ? accent : COLOR_MUTED);
            cv.drawLine(cx, cy, px, py, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(COLOR_SKY_CONTROL);
            cv.drawCircle(cx, cy, r * 0.45f, p);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    startY = e.getY(); startVal = value; return true;
                case MotionEvent.ACTION_MOVE:
                    float d = getResources().getDisplayMetrics().density;
                    setValue(startVal + (startY - e.getY()) / (140f * d));
                    if (onChange != null) onChange.set(value);
                    return true;
            }
            return true;
        }
    }

    private static final class LiveControlView extends View {
        interface ControlsChangedListener {
            void onControlsChanged(float[] values);
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Faders start below the header+visualizer and fill the remaining height.
        private static final float FADER_TOP = 132.0f;
        private static final float PAD = 16.0f;

        // Short panes (landscape) get a one-line header and no visualizer, so
        // the faders never overlap the header text.
        private boolean compactLayout(float d) {
            return getHeight() < 250.0f * d;
        }

        private float faderTopPx(float d) {
            return compactLayout(d) ? 56.0f * d : FADER_TOP * d;
        }

        private float faderLenPx(float d) {
            return Math.max(64.0f * d, getHeight() - faderTopPx(d) - 34.0f * d);
        }

        private final RectF rect = new RectF();
        private final float[] values = new float[]{0.62f, 0.52f, 0.58f, 0.56f, 0.60f, 0.72f};
        private ControlsChangedListener listener;
        private InstrumentMode mode = InstrumentMode.ELECTRIC_GUITAR;
        private TonePreset preset = TonePreset.defaultFor(InstrumentMode.ELECTRIC_GUITAR);
        private InputRoute route = InputRoute.USB;
        private boolean running;
        private float levelDb = -120.0f;
        private float pitchHz = 0.0f;
        private int activeFader = -1;
        private float phase;

        LiveControlView(Context context) {
            super(context);
            setWillNotDraw(false);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        void setControlsChangedListener(ControlsChangedListener listener) {
            this.listener = listener;
            if (listener != null) {
                listener.onControlsChanged(values.clone());
            }
        }

        void setValues(float[] newValues) {
            if (newValues == null || newValues.length < 6) {
                return;
            }
            System.arraycopy(newValues, 0, values, 0, 6);
            invalidate();
        }

        void setRig(InstrumentMode mode, TonePreset preset, InputRoute route, boolean running) {
            this.mode = mode;
            this.preset = preset;
            this.route = route;
            this.running = running;
            invalidate();
        }

        void setMeter(float levelDb, float pitchHz, boolean running) {
            this.levelDb = levelDb;
            this.pitchHz = pitchHz;
            this.running = running;
            phase += running ? 0.23f : 0.04f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float width = getWidth();

            rect.set(0.0f, 0.0f, width, getHeight());
            paint.setColor(COLOR_SURFACE_RAISED);
            canvas.drawRoundRect(rect, 18.0f * d, 18.0f * d, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(d);
            paint.setColor(COLOR_BORDER);
            canvas.drawRoundRect(rect, 18.0f * d, 18.0f * d, paint);
            paint.setStyle(Paint.Style.FILL);

            if (compactLayout(d)) {
                drawCompactHeader(canvas, width, d);
            } else {
                drawHeader(canvas, width, d);
                drawVisualizer(canvas, width, d);
            }
            drawFaders(canvas, width, d);
        }

        private void drawCompactHeader(Canvas canvas, float width, float d) {
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setColor(COLOR_AMBER);
            textPaint.setTextSize(11.0f * d);
            canvas.drawText("LIVE CONTROLS · " + panelTitle().toUpperCase(Locale.US),
                    16.0f * d, 22.0f * d, textPaint);
            textPaint.setTextAlign(Paint.Align.RIGHT);
            textPaint.setColor(running ? COLOR_GREEN : COLOR_DIM);
            textPaint.setTextSize(11.0f * d);
            canvas.drawText(running ? "ON AIR" : "STANDBY", width - 16.0f * d, 22.0f * d, textPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float d = getResources().getDisplayMetrics().density;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    activeFader = findFader(event.getX(), event.getY(), getWidth(), d);
                    if (activeFader >= 0) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                        updateFader(event.getY(), d);
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (activeFader >= 0) {
                        updateFader(event.getY(), d);
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    activeFader = -1;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    break;
            }
            return false;
        }

        private void updateFader(float y, float d) {
            float top = faderTopPx(d);
            float len = faderLenPx(d);
            values[activeFader] = clamp01((top + len - y) / len);
            if (listener != null) {
                listener.onControlsChanged(values.clone());
            }
            invalidate();
        }

        private void drawHeader(Canvas canvas, float width, float d) {
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setColor(COLOR_AMBER);
            textPaint.setTextSize(11.0f * d);
            canvas.drawText("LIVE CONTROLS", 16.0f * d, 24.0f * d, textPaint);

            textPaint.setColor(COLOR_TEXT);
            textPaint.setTextSize(17.0f * d);
            canvas.drawText(panelTitle(), 16.0f * d, 48.0f * d, textPaint);

            textPaint.setTextAlign(Paint.Align.RIGHT);
            textPaint.setColor(running ? COLOR_GREEN : COLOR_DIM);
            textPaint.setTextSize(12.0f * d);
            canvas.drawText(running ? "ON AIR" : "STANDBY", width - 16.0f * d, 31.0f * d, textPaint);
            textPaint.setColor(COLOR_MUTED);
            canvas.drawText(route.label, width - 16.0f * d, 50.0f * d, textPaint);
        }

        private void drawVisualizer(Canvas canvas, float width, float d) {
            float left = 16.0f * d;
            float top = 66.0f * d;
            float barWidth = (width - 32.0f * d) / 22.0f;
            float normalized = clamp01((levelDb + 60.0f) / 60.0f);

            for (int i = 0; i < 22; i++) {
                float pulse = running
                        ? (float) (0.55 + 0.45 * Math.sin(phase + i * 0.65))
                        : 0.22f;
                float level = Math.max(normalized, running ? pulse * 0.30f : 0.0f);
                float height = (8.0f + level * 32.0f) * d;
                int color = i < 13 ? COLOR_GREEN : i < 18 ? COLOR_AMBER : COLOR_RED;
                paint.setColor(i / 22.0f <= level ? color : COLOR_SKY_CONTROL_STRONG);
                rect.set(left + i * barWidth, top + 40.0f * d - height, left + i * barWidth + barWidth * 0.72f, top + 40.0f * d);
                canvas.drawRoundRect(rect, 2.0f * d, 2.0f * d, paint);
            }

            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setColor(COLOR_MUTED);
            textPaint.setTextSize(11.0f * d);
            String pitchText = pitchHz > 0.0f ? String.format(Locale.US, "%.1f Hz", pitchHz) : "--";
            canvas.drawText(String.format(Locale.US, "%.1f dBFS", levelDb), left, top + 58.0f * d, textPaint);
            textPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(pitchText, width - 16.0f * d, top + 58.0f * d, textPaint);
        }

        private void drawFaders(Canvas canvas, float width, float d) {
            String[] labels = knobLabels();
            int accent = toneAccentStatic(preset);
            float top = faderTopPx(d);
            float len = faderLenPx(d);
            float bottom = top + len;
            float colW = (width - 2.0f * PAD * d) / 6.0f;
            float trackW = 6.0f * d;
            float thumbW = Math.min(34.0f * d, colW * 0.7f);
            float thumbH = 13.0f * d;
            float trackRadius = trackW / 2.0f;

            for (int i = 0; i < 6; i++) {
                float cx = PAD * d + colW * (i + 0.5f);
                float thumbY = bottom - values[i] * len;
                boolean active = i == activeFader;

                paint.setColor(COLOR_SKY_TRACK);
                rect.set(cx - trackRadius, top, cx + trackRadius, bottom);
                canvas.drawRoundRect(rect, trackRadius, trackRadius, paint);

                paint.setColor(accent);
                rect.set(cx - trackRadius, thumbY, cx + trackRadius, bottom);
                canvas.drawRoundRect(rect, trackRadius, trackRadius, paint);

                paint.setColor(COLOR_BORDER_STRONG);
                rect.set(cx - thumbW / 2.0f, thumbY - thumbH / 2.0f + 2.0f * d,
                        cx + thumbW / 2.0f, thumbY + thumbH / 2.0f + 2.0f * d);
                canvas.drawRoundRect(rect, 4.0f * d, 4.0f * d, paint);
                paint.setColor(active ? accent : COLOR_SKY_CONTROL);
                rect.set(cx - thumbW / 2.0f, thumbY - thumbH / 2.0f, cx + thumbW / 2.0f, thumbY + thumbH / 2.0f);
                canvas.drawRoundRect(rect, 4.0f * d, 4.0f * d, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.5f * d);
                paint.setColor(active ? COLOR_TEXT : COLOR_MUTED);
                canvas.drawRoundRect(rect, 4.0f * d, 4.0f * d, paint);
                paint.setStyle(Paint.Style.FILL);

                textPaint.setTextAlign(Paint.Align.CENTER);
                textPaint.setColor(active ? accent : COLOR_DIM);
                textPaint.setTextSize(10.0f * d);
                canvas.drawText(Math.round(values[i] * 100.0f) + "%", cx, top - 8.0f * d, textPaint);

                textPaint.setColor(COLOR_TEXT);
                textPaint.setTextSize(11.0f * d);
                canvas.drawText(labels[i], cx, bottom + 22.0f * d, textPaint);
            }
        }

        private String panelTitle() {
            if (mode == InstrumentMode.PIANO) {
                return "Piano Synth";
            }
            if (mode == InstrumentMode.BASS) {
                return "Bass Rig";
            }
            return "Guitar Rig";
        }

        private String[] knobLabels() {
            if (mode == InstrumentMode.PIANO) {
                return new String[]{"Attack", "Tone", "Mod", "Decay", "Space", "Level"};
            }
            if (mode == InstrumentMode.BASS) {
                return new String[]{"Gain", "Low", "Lo Mid", "Hi Mid", "Blend", "Level"};
            }
            return new String[]{"Gain", "Bass", "Mid", "Treble", "Presence", "Volume"};
        }

        private int findFader(float x, float y, float width, float d) {
            float top = faderTopPx(d);
            float bottom = top + faderLenPx(d);
            if (y < top - 30.0f * d || y > bottom + 34.0f * d) {
                return -1;
            }
            float colW = (width - 2.0f * PAD * d) / 6.0f;
            for (int i = 0; i < 6; i++) {
                float cx = PAD * d + colW * (i + 0.5f);
                if (Math.abs(x - cx) <= colW * 0.5f) {
                    return i;
                }
            }
            return -1;
        }

        private float clamp01(float value) {
            return Math.max(0.0f, Math.min(1.0f, value));
        }
    }

    private static final class PianoKeysView extends View {
        interface KeyListener {
            void onKey(int note, boolean down);
        }

        private static final int START = 21;  // A0
        private static final int END = 108;   // C8
        private float visibleWhites = 15.0f;
        private static final int[] WHITE_PC = {0, 2, 4, 5, 7, 9, 11};

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final boolean[] pressed = new boolean[128];
        private int detectedNote = -1;
        private int accent = Color.rgb(228, 170, 75);
        private KeyListener keyListener;
        private int touchedNote = -1;
        private float ww = 1.0f;
        private float scrollX = -1.0f;
        private float targetScrollX = 0.0f;
        private float downX;
        private float downScrollX;
        private boolean dragging;

        PianoKeysView(Context context) {
            super(context);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        void setKeyListener(KeyListener listener) {
            this.keyListener = listener;
        }

        void setVisibleWhites(float count) {
            this.visibleWhites = count;
            requestLayout();
            invalidate();
        }

        void setAccent(int accent) {
            this.accent = accent;
            invalidate();
        }

        // Display-only: the keyboard visualizes MIDI input; it is not touch-playable.
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return false;
        }

        private int noteAt(float px, float py) {
            float h = getHeight();
            float x = px + scrollX;
            if (py < h * 0.62f) {
                for (int n = START; n <= END; n++) {
                    if (isWhite(n)) {
                        continue;
                    }
                    float cx = noteCenterX(n);
                    float bw = ww * 0.62f;
                    if (x >= cx - bw / 2 && x <= cx + bw / 2) {
                        return n;
                    }
                }
            }
            for (int n = START; n <= END; n++) {
                if (!isWhite(n)) {
                    continue;
                }
                float x0 = whitesBelow(n) * ww;
                if (x >= x0 && x < x0 + ww) {
                    return n;
                }
            }
            return -1;
        }

        void setDetectedNote(int note) {
            if (note != detectedNote) {
                detectedNote = note;
                recenter();
                invalidate();
            }
        }

        void setPressedNotes(boolean[] src) {
            System.arraycopy(src, 0, pressed, 0, 128);
            recenter();
            invalidate();
        }

        private boolean isWhite(int note) {
            int pc = note % 12;
            for (int w : WHITE_PC) {
                if (w == pc) {
                    return true;
                }
            }
            return false;
        }

        private boolean lit(int note) {
            return (note >= 0 && note < 128 && pressed[note]) || note == detectedNote;
        }

        private int whitesBelow(int note) {
            int c = 0;
            for (int n = START; n < note; n++) {
                if (isWhite(n)) {
                    c++;
                }
            }
            return c;
        }

        private float noteCenterX(int note) {
            int wb = whitesBelow(note);
            return isWhite(note) ? (wb + 0.5f) * ww : wb * ww;
        }

        private float maxScroll() {
            int total = whitesBelow(END + 1);
            return Math.max(0.0f, total * ww - getWidth());
        }

        private float clampScroll(float s) {
            return Math.max(0.0f, Math.min(s, maxScroll()));
        }

        // Slide so the keys currently lit are centred in the view.
        private void recenter() {
            if (getWidth() <= 0 || ww <= 0) {
                return;
            }
            float sum = 0.0f;
            int count = 0;
            for (int n = START; n <= END; n++) {
                if (lit(n)) {
                    sum += noteCenterX(n);
                    count++;
                }
            }
            if (count == 0) {
                return;
            }
            targetScrollX = clampScroll(sum / count - getWidth() / 2.0f);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float h = getHeight();
            float w = getWidth();
            ww = w / visibleWhites;

            if (scrollX < 0.0f) {
                scrollX = clampScroll(noteCenterX(60) - w / 2.0f);
                targetScrollX = scrollX;
            }
            if (Math.abs(targetScrollX - scrollX) > 0.5f) {
                scrollX += (targetScrollX - scrollX) * 0.22f;
                postInvalidateOnAnimation();
            } else {
                scrollX = targetScrollX;
            }

            float gap = 1.5f * d;
            for (int n = START; n <= END; n++) {
                if (!isWhite(n)) {
                    continue;
                }
                float x0 = whitesBelow(n) * ww - scrollX;
                if (x0 + ww < 0 || x0 > w) {
                    continue;
                }
                rect.set(x0 + gap, 0, x0 + ww - gap, h);
                paint.setColor(lit(n) ? accent : COLOR_SURFACE_RAISED);
                canvas.drawRoundRect(rect, 3 * d, 3 * d, paint);
                if (n % 12 == 0) {
                    textPaint.setColor(lit(n) ? Color.rgb(8, 10, 14) : Color.rgb(120, 124, 130));
                    textPaint.setTextSize(9 * d);
                    canvas.drawText("C" + (n / 12 - 1), x0 + ww / 2, h - 6 * d, textPaint);
                }
            }

            float bw = ww * 0.62f;
            float bh = h * 0.62f;
            for (int n = START; n <= END; n++) {
                if (isWhite(n)) {
                    continue;
                }
                float cx = noteCenterX(n) - scrollX;
                if (cx + bw < 0 || cx - bw > w) {
                    continue;
                }
                rect.set(cx - bw / 2, 0, cx + bw / 2, bh);
                paint.setColor(lit(n) ? accent : COLOR_SKY_KEY_DARK);
                canvas.drawRoundRect(rect, 3 * d, 3 * d, paint);
            }
        }
    }

    // Full Keys controller: one physical lever carries both dimensions.
    // Vertical motion bends pitch through the selected range; rightward motion
    // adds vibrato. Leftward travel is intentionally clamped at zero modulation.
    private static final class PitchVibratoView extends View {
        interface Listener {
            void onChange(float bend, float vibrato);
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private Listener listener;
        private float bend;
        private float vibrato;
        private int pointer = -1;
        private int accent = COLOR_TEAL;
        private int range = 24;

        PitchVibratoView(Context context) {
            super(context);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        void setAccent(int color) {
            accent = color;
            invalidate();
        }

        void setRange(int semitones) {
            range = Math.max(1, Math.min(24, semitones));
            invalidate();
        }

        void setListener(Listener value) {
            listener = value;
        }

        private void send() {
            if (listener != null) {
                listener.onChange(bend, vibrato);
            }
        }

        private void blockParentTouch(boolean block) {
            android.view.ViewParent parent = getParent();
            while (parent != null) {
                parent.requestDisallowInterceptTouchEvent(block);
                parent = parent.getParent();
            }
        }

        private void update(MotionEvent event) {
            int index = event.findPointerIndex(pointer);
            if (index < 0) return;
            float d = getResources().getDisplayMetrics().density;
            // Keep pitch travel in the centered middle half of the control.
            // The unused 25% above and below makes full bends reachable without
            // dragging toward the edge of a tall landscape screen.
            float top = getHeight() * 0.25f;
            float bottom = getHeight() * 0.75f;
            float cy = (top + bottom) * 0.5f;
            float pitchTravel = Math.max(1f, (bottom - top) * 0.5f - 9f * d);
            bend = Math.max(-1f, Math.min(1f, (cy - event.getY(index)) / pitchTravel));

            float restX = Math.max(27f * d, getWidth() * 0.34f);
            float right = getWidth() - 13f * d;
            vibrato = Math.max(0f, Math.min(1f,
                    (event.getX(index) - restX) / Math.max(1f, right - restX)));
            send();
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    blockParentTouch(true);
                    pointer = event.getPointerId(0);
                    update(event);
                    break;
                case MotionEvent.ACTION_MOVE:
                    update(event);
                    break;
                case MotionEvent.ACTION_UP: {
                    pointer = -1;
                    blockParentTouch(false);
                    performClick();
                    invalidate();
                    break;
                }
                case MotionEvent.ACTION_CANCEL:
                    pointer = -1;
                    blockParentTouch(false);
                    invalidate();
                    break;
            }
            return true;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth();
            float h = getHeight();
            boolean returning = false;
            if (pointer < 0 && Math.abs(bend) > 0.01f) {
                bend *= 0.70f;
                returning = true;
            } else if (pointer < 0 && bend != 0f) {
                bend = 0f;
                returning = true;
            }
            if (pointer < 0 && vibrato > 0.01f) {
                vibrato *= 0.70f;
                returning = true;
            } else if (pointer < 0 && vibrato != 0f) {
                vibrato = 0f;
                returning = true;
            }
            if (returning) {
                send();
                postInvalidateOnAnimation();
            }

            float inset = 3f * d;
            float corner = 10f * d;
            rect.set(inset, 0f, w - inset, h);
            paint.setColor(COLOR_SKY_CONTROL);
            canvas.drawRoundRect(rect, corner, corner, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(d);
            paint.setColor(COLOR_BORDER_STRONG);
            rect.set(inset, 0f, w - inset, h);
            canvas.drawRoundRect(rect, corner, corner, paint);
            paint.setStyle(Paint.Style.FILL);

            textPaint.setTextSize(8f * d);
            textPaint.setColor(COLOR_TEXT);
            canvas.drawText("PITCH  /  VIB MOD →", w * 0.5f, 13f * d, textPaint);

            float top = h * 0.25f;
            float bottom = h * 0.75f;
            float cy = (top + bottom) * 0.5f;
            float restX = Math.max(27f * d, w * 0.34f);
            float right = w - 13f * d;
            float trackW = Math.min(15f * d, w * 0.18f);
            rect.set(restX - trackW * 0.5f, top, restX + trackW * 0.5f, bottom);
            paint.setColor(COLOR_SKY_KEY_DARK);
            canvas.drawRoundRect(rect, trackW * 0.42f, trackW * 0.42f, paint);

            // The modulation guide starts at the pitch lever's rest column.
            float guideH = 5f * d;
            rect.set(restX, cy - guideH * 0.5f, right, cy + guideH * 0.5f);
            paint.setColor(COLOR_SKY_TRACK);
            canvas.drawRoundRect(rect, guideH * 0.5f, guideH * 0.5f, paint);
            if (vibrato > 0f) {
                rect.set(restX, cy - guideH * 0.5f,
                        restX + (right - restX) * vibrato, cy + guideH * 0.5f);
                paint.setColor(COLOR_AMBER);
                canvas.drawRoundRect(rect, guideH * 0.5f, guideH * 0.5f, paint);
            }

            float pitchTravel = Math.max(1f, (bottom - top) * 0.5f - 9f * d);
            float py = cy - bend * pitchTravel;
            float px = restX + (right - restX) * vibrato;
            float thumbW = Math.min(w * 0.38f, 38f * d);
            float thumbH = Math.min(15f * d, Math.max(9f * d, h * 0.11f));
            rect.set(px - thumbW * 0.5f, py - thumbH * 0.5f,
                    px + thumbW * 0.5f, py + thumbH * 0.5f);
            paint.setColor(accent);
            canvas.drawRoundRect(rect, 5f * d, 5f * d, paint);
            paint.setColor(Color.argb(120, 255, 255, 255));
            for (int i = -1; i <= 1; i++) {
                float ly = py + i * 3f * d;
                canvas.drawRect(px - thumbW * 0.28f, ly - 0.5f * d,
                        px + thumbW * 0.28f, ly + 0.5f * d, paint);
            }

            textPaint.setTextSize(7f * d);
            textPaint.setColor(COLOR_MUTED);
            canvas.drawText("+" + range, 12f * d, top + 5f * d, textPaint);
            canvas.drawText("0", 12f * d, cy + 2.5f * d, textPaint);
            canvas.drawText("-" + range, 12f * d, bottom, textPaint);
            canvas.drawText("MAX", right, bottom, textPaint);
        }
    }

    private GradientDrawable bubblyPanelBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(Color.rgb(231, 247, 255));
        background.setCornerRadius(dp(20));
        background.setStroke(dp(1), COLOR_BORDER);
        return background;
    }

    // Note bender: drag right to bend up, left to bend down (±24 semitones at
    // full throw — enough to scream). Springs back to center when released.
    private static final class PitchBendView extends View {
        interface BendListener {
            void onBend(float v);   // -1 .. 1
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private BendListener listener;
        private float value = 0f;
        private boolean held = false;
        private final boolean vertical;
        private final boolean spring;    // spring back to rest when released
        private final boolean unipolar;  // 0..1 from bottom (else -1..1 from center)
        private String label = "BEND";
        private int accent = COLOR_TEAL;

        PitchBendView(Context context) { this(context, false); }
        PitchBendView(Context context, boolean vertical) { this(context, vertical, true, false); }
        PitchBendView(Context context, boolean vertical, boolean spring, boolean unipolar) {
            super(context);
            this.vertical = vertical;
            this.spring = spring;
            this.unipolar = unipolar;
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        void setListener(BendListener l) {
            this.listener = l;
        }

        void setAccent(int c) { this.accent = c; invalidate(); }
        void setLabel(String s) { this.label = s; invalidate(); }
        void setValue(float v) { this.value = v; invalidate(); }

        private void send() {
            if (listener != null) listener.onBend(value);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE: {
                    held = true;
                    if (unipolar) {          // bottom = 0, top = 1
                        value = Math.max(0f, Math.min(1f,
                                (getHeight() - e.getY()) / (getHeight() * 0.94f)));
                    } else if (vertical) {
                        float half = getHeight() / 2f;   // up = raise pitch
                        value = Math.max(-1f, Math.min(1f, (half - e.getY()) / (half * 0.92f)));
                    } else {
                        float half = getWidth() / 2f;
                        value = Math.max(-1f, Math.min(1f, (e.getX() - half) / (half * 0.92f)));
                    }
                    send();
                    invalidate();
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    held = false;   // spring back (if enabled) happens in onDraw
                    invalidate();
                    break;
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth();
            float h = getHeight();
            if (spring && !held && Math.abs(value) > 0.01f) {
                value *= 0.72f;   // spring back to center
                send();
                postInvalidateOnAnimation();
            } else if (spring && !held && value != 0f) {
                value = 0f;
                send();
            }
            if (unipolar) {
                float cx = w / 2f;
                float tw = Math.min(w * 0.5f, 16 * d);
                rect.set(cx - tw / 2, 0, cx + tw / 2, h);
                paint.setColor(COLOR_SKY_TRACK);
                canvas.drawRoundRect(rect, tw * 0.5f, tw * 0.5f, paint);
                // fill from the bottom up to the current level
                float py = h - value * h * 0.94f;
                rect.set(cx - tw / 2, py, cx + tw / 2, h);
                paint.setColor(accent);
                canvas.drawRoundRect(rect, tw * 0.5f, tw * 0.5f, paint);
                paint.setColor(Color.rgb(232, 230, 222));
                canvas.drawCircle(cx, Math.max(tw * 0.62f, Math.min(h - tw * 0.62f, py)), tw * 0.62f, paint);
                textPaint.setColor(Color.argb(150, 200, 206, 218));
                textPaint.setTextSize(8 * d);
                canvas.drawText(label, cx, h - 6 * d, textPaint);
                return;
            }
            if (vertical) {
                float cx = w / 2f, cy = h / 2f;
                float tw = Math.min(w * 0.5f, 16 * d);
                rect.set(cx - tw / 2, 0, cx + tw / 2, h);
                paint.setColor(COLOR_SKY_TRACK);
                canvas.drawRoundRect(rect, tw * 0.5f, tw * 0.5f, paint);
                // fill from center toward the current bend (up = positive)
                float py = cy - value * (h / 2f) * 0.92f;
                rect.set(cx - tw / 2, Math.min(cy, py), cx + tw / 2, Math.max(cy, py));
                paint.setColor(accent);
                canvas.drawRoundRect(rect, tw * 0.5f, tw * 0.5f, paint);
                // center notch + thumb
                paint.setColor(Color.argb(140, 200, 206, 218));
                canvas.drawRect(cx - tw * 0.6f, cy - 1 * d, cx + tw * 0.6f, cy + 1 * d, paint);
                paint.setColor(Color.rgb(232, 230, 222));
                canvas.drawCircle(cx, py, tw * 0.62f, paint);
                textPaint.setColor(Color.argb(150, 200, 206, 218));
                textPaint.setTextSize(8 * d);
                canvas.drawText(label, cx, h - 6 * d, textPaint);
                return;
            }
            // track
            rect.set(0, h * 0.22f, w, h * 0.78f);
            paint.setColor(COLOR_SKY_TRACK);
            canvas.drawRoundRect(rect, h * 0.28f, h * 0.28f, paint);
            // fill from center toward the current bend
            float cx = w / 2f;
            float px = cx + value * (w / 2f) * 0.92f;
            rect.set(Math.min(cx, px), h * 0.22f, Math.max(cx, px), h * 0.78f);
            paint.setColor(accent);
            canvas.drawRoundRect(rect, h * 0.28f, h * 0.28f, paint);
            // center notch + thumb
            paint.setColor(Color.argb(140, 200, 206, 218));
            canvas.drawRect(cx - 1 * d, h * 0.14f, cx + 1 * d, h * 0.86f, paint);
            paint.setColor(Color.rgb(232, 230, 222));
            canvas.drawCircle(px, h * 0.5f, h * 0.32f, paint);
            textPaint.setColor(Color.argb(150, 200, 206, 218));
            textPaint.setTextSize(9 * d);
            canvas.drawText("BEND", 26 * d, h * 0.5f + 3.5f * d, textPaint);
        }
    }

    // A mixer channel strip: a vertical fader on the left and a segmented LED
    // level meter on the right (green → amber → red near the top).
    private static final class ChannelStripView extends View {
        interface FaderListener { void onChange(float v); }
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float value = 0.8f;   // fader 0..1
        private float meter = 0f;     // live level 0..1
        private int accent = COLOR_TEAL;
        private FaderListener listener;

        ChannelStripView(Context context) { super(context); }
        void setAccent(int c) { accent = c; }
        void setValue(float v) { value = v; invalidate(); }
        float getValue() { return value; }
        void setOnChange(FaderListener l) { listener = l; }
        void setMeter(float m) {
            if (Math.abs(m - meter) < 0.004f) return;
            meter = m; invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE: {
                    android.view.ViewParent p = getParent();
                    while (p != null) { p.requestDisallowInterceptTouchEvent(true); p = p.getParent(); }
                    float pad = getHeight() * 0.06f;
                    value = Math.max(0f, Math.min(1f,
                            (getHeight() - pad - e.getY()) / (getHeight() - 2 * pad)));
                    if (listener != null) listener.onChange(value);
                    invalidate();
                    break;
                }
            }
            return true;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight();
            float pad = h * 0.06f;
            float travel = h - 2 * pad;
            // --- Fader (left ~55%) ---
            float fx = w * 0.30f;
            paint.setColor(COLOR_SKY_TRACK);
            rect.set(fx - 2.5f * d, pad, fx + 2.5f * d, h - pad);
            canvas.drawRoundRect(rect, 2 * d, 2 * d, paint);
            float hy = pad + (1f - value) * travel;      // handle center
            float capW = w * 0.42f, capH = Math.min(h * 0.12f, 26 * d);
            rect.set(fx - capW / 2, hy - capH / 2, fx + capW / 2, hy + capH / 2);
            paint.setColor(Color.rgb(214, 216, 220));
            canvas.drawRoundRect(rect, 3 * d, 3 * d, paint);
            paint.setColor(Color.rgb(150, 153, 160));   // grip line
            canvas.drawRect(fx - capW / 2 + 2 * d, hy - 0.8f * d, fx + capW / 2 - 2 * d, hy + 0.8f * d, paint);
            // --- LED meter (right) ---
            float mx0 = w * 0.62f, mx1 = w * 0.96f;
            int segs = 18;
            float gap = 1.6f * d;
            float segH = (travel - gap * (segs - 1)) / segs;
            for (int i = 0; i < segs; i++) {
                float frac = (i + 1) / (float) segs;
                boolean lit = meter >= frac - 0.0001f;
                float top = h - pad - (i + 1) * segH - i * gap;
                rect.set(mx0, top, mx1, top + segH);
                int col;
                if (!lit) col = COLOR_SKY_CONTROL_STRONG;
                else if (frac > 0.92f) col = Color.rgb(226, 74, 62);       // red
                else if (frac > 0.78f) col = Color.rgb(232, 176, 64);      // amber
                else col = Color.rgb(120, 214, 96);                        // green
                paint.setColor(col);
                canvas.drawRect(rect, paint);
            }
        }
    }

    // Looper keys: an octave-wide window that walks the keyboard one white key
    // at a time (A1 → B1 → C1 → ...) and glides gently between positions.
    // Multi-touch with glide; notes go to the looper key channel so whatever
    // is played prints into loops 1-3 like the drum pads.
    private final class LoopKeysView extends View {
        private static final int TOTAL_WHITES = 52;   // A0..C8
        // Android devices commonly expose up to 10 simultaneous touch points;
        // keep more pointer-ID slots than that because IDs can be sparse.
        private static final int MAX_TRACKED_POINTER_IDS = 32;
        private static final long MULTI_TOUCH_CANCEL_HOLD_MS = 3500L;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int[] pointerNote = new int[MAX_TRACKED_POINTER_IDS]; // per-pointer pressed root
        private final boolean[] pointerChord = new boolean[MAX_TRACKED_POINTER_IDS]; // shape used at press time
        private final boolean[] pointerAlt = new boolean[MAX_TRACKED_POINTER_IDS]; // pressed on the sound-2 board
        private final boolean[] pointerUpper = new boolean[MAX_TRACKED_POINTER_IDS]; // which board the finger is on
        // The exact notes fired at press time — releases use THIS set, never a
        // recompute, so a mode/split change mid-hold can't leave notes stuck.
        private final int[][] pointerNotes = new int[MAX_TRACKED_POINTER_IDS][];
        // Refcounts, not booleans: neighbouring chords share notes (C-E-G and
        // E-G-B both hold E and G), so a shared note stays sounding until the
        // last finger holding it lifts. Sound 2 keeps its own counts.
        private final int[] soundCount = new int[128];
        private final int[] soundCount2 = new int[128];
        // Highlights are tracked PER BOARD: a key pressed on the lower board
        // must not light its twin on the upper one.
        private final int[] pressLower = new int[128];
        private final int[] pressUpper = new int[128];
        private final int litLower = Color.rgb(88, 150, 240);    // blue below
        private final int litUpper = Color.rgb(176, 120, 235);   // purple above
        private int accent = COLOR_TEAL;
        private int count = 8;
        private boolean chord;
        private int dualSplitKey = -1;   // dual sound boundary; chords stay on one side
        private boolean dualSeparate;    // dual by keyboard half instead of by key
        private boolean split;        // double keys: bottom = chords, top = melody
        private float splitAnim;      // 0 = single keyboard, 1 = fully split (animated)
        private float splitTarget;
        private float scroll;         // leftmost visible white key, fractional index
        private float targetScroll;
        private float melScroll;      // split mode: the upper keyboard's own position
        private float melTargetScroll;
        // System multi-finger gesture detectors (3-finger screenshot & co.)
        // pilfer simultaneous touches: they CANCEL us milliseconds after the
        // fingers land. Give freshly pressed notes a short grace so the tap is
        // still audible instead of dying silently.
        private long lastPressMs;
        private boolean cancelPending;
        private final Runnable cancelRelease = () -> {
            cancelPending = false;
            releaseAll();
        };

        LoopKeysView(Context context) {
            super(context);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextAlign(Paint.Align.CENTER);
            java.util.Arrays.fill(pointerNote, -1);
        }

        void setAccent(int accent) {
            this.accent = accent;
            invalidate();
        }

        void setRange(int baseNote, int whiteCount) {
            releaseAll();
            count = whiteCount;
            targetScroll = clampScroll(whiteIndexOf(baseNote));
            if (getWidth() == 0) {
                scroll = targetScroll;   // first show: no slide-in animation
            }
            invalidate();
        }

        // Split mode: the melody (upper) keyboard slides independently.
        void setMelodyRange(int baseNote) {
            releaseAll();
            melTargetScroll = clampScroll(whiteIndexOf(baseNote));
            if (getWidth() == 0) {
                melScroll = melTargetScroll;
            }
            invalidate();
        }

        // Release with the old chord shape BEFORE switching, or notes stick.
        void setChord(boolean on) {
            releaseAll();
            chord = on;
            invalidate();
        }

        // Double keys: two keyboards — the lower plays chords, the upper plays
        // single melody notes one octave up. The melody board stretches in from
        // the top while the chord board eases down to make room (animated).
        void setSplit(boolean on) {
            releaseAll();
            split = on;
            splitTarget = on ? 1.0f : 0.0f;
            invalidate();
        }

        // Dual sound split point (-1 = off). Chords never reach across it.
        void setDualSplit(int note) {
            releaseAll();   // old holds released with the old chord shapes
            dualSplitKey = note;
        }

        // Separate dual mode: the upper (melody) keyboard plays Sound 2 whole.
        void setDualSeparate(boolean on) {
            releaseAll();
            dualSeparate = on;
        }

        private boolean altAt(float y) {
            return dualSeparate && split && y < getHeight() * 0.5f;
        }

        // Chord mode: a white key plays its diatonic triad on the white-key row
        // (third = two whites up, fifth = four whites up: C-E-G, D-F-A, ...).
        // B is the exception — stacked whites give B-D-F (diminished, sounds
        // sour), so it plays B major with D# and F# on the black keys.
        // A black key plays a plain major triad.
        private int[] chordNotes(int root, boolean chordOn) {
            if (!chordOn) return new int[]{root};
            int[] n;
            if (isWhiteKey(root) && root % 12 != 11) {
                n = new int[]{root, whiteAbove(root, 2), whiteAbove(root, 4)};
            } else {
                n = new int[]{root, root + 4, root + 7};
            }
            // Dual: keep the whole chord on the root's side of the split —
            // voices that would cross onto Sound 2 drop an octave (inversion).
            if (dualSplitKey >= 0 && root < dualSplitKey) {
                for (int i = 1; i < n.length; i++) {
                    if (n[i] >= dualSplitKey) n[i] -= 12;
                }
            }
            return n;
        }

        // Which shape a touch at this height plays: the Chord pill decides, and
        // in split mode only the bottom keyboard chords (top is always melody).
        private boolean chordAt(float y) {
            return chord && (!split || y >= getHeight() * 0.5f);
        }


        // The white key `steps` whites above `note` (clamps at the top).
        private int whiteAbove(int note, int steps) {
            int n = note;
            while (steps > 0 && n < 127) {
                n++;
                if (isWhiteKey(n)) steps--;
            }
            return n;
        }

        private float clampScroll(float s) {
            float max = TOTAL_WHITES - count;
            return s < 0 ? 0 : (s > max ? max : s);
        }

        // Key under a touch. In split mode each half is its own keyboard: the
        // top half reads from the melody window, the bottom from the chords one.
        private int noteAt(float x, float y) {
            float h = getHeight();
            float top = 0, bot = h;
            float sc = scroll;
            if (split) {
                if (y < h * 0.5f) {
                    bot = h * 0.5f;
                    sc = melScroll;
                } else {
                    top = h * 0.5f;
                }
            }
            float ww = getWidth() / (float) count;
            float xw = x / ww + sc;   // absolute position in white-key units
            // Black keys sit on the upper part and win the hit when touched.
            if (y - top < (bot - top) * 0.58f) {
                int first = (int) Math.floor(sc);
                for (int wi = Math.max(0, first); wi <= first + count && wi < TOTAL_WHITES - 1; wi++) {
                    int black = whiteNoteAt(wi) + 1;
                    if (isWhiteKey(black)) continue;
                    float bx = wi + 1;   // key boundary, in white units
                    if (xw >= bx - 0.29f && xw <= bx + 0.29f) return black;
                }
            }
            int wi = (int) Math.floor(xw);
            if (wi < 0) wi = 0;
            if (wi > TOTAL_WHITES - 1) wi = TOTAL_WHITES - 1;
            return whiteNoteAt(wi);
        }

        private void disallowTouchInterception(boolean disallow) {
            // Walk the complete hierarchy. The looper is nested under scroll
            // containers, and a single direct-parent request was not enough on
            // some Android builds once a third pointer landed.
            android.view.ViewParent parent = getParent();
            while (parent != null) {
                parent.requestDisallowInterceptTouchEvent(disallow);
                parent = parent.getParent();
            }
        }

        private int trackedTouchCount() {
            int count = 0;
            for (int note : pointerNote) {
                if (note >= 0) count++;
            }
            return count;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    // No ancestor may steal multi-finger touches as a scroll or
                    // gesture — that arrives as CANCEL and kills held notes.
                    disallowTouchInterception(true);
                    if (cancelPending) {
                        handler.removeCallbacks(cancelRelease);
                        cancelPending = false;
                        releaseAll();
                    }
                    // Press EVERY untracked finger on the keys, not only the one
                    // this event announces: when several land at once the system
                    // can swallow some POINTER_DOWNs (gesture detection) and
                    // only the survivors arrive.
                    for (int pi = 0; pi < e.getPointerCount(); pi++) {
                        int id = e.getPointerId(pi);
                        if (id < 0 || id >= pointerNote.length) continue;
                        if (pi == e.getActionIndex() && pointerNote[id] >= 0) release(id);
                        if (pointerNote[id] < 0) {
                            press(id, noteAt(e.getX(pi), e.getY(pi)),
                                    chordAt(e.getY(pi)), altAt(e.getY(pi)), upperAt(e.getY(pi)));
                        }
                    }
                    break;
                }
                case MotionEvent.ACTION_MOVE:
                    disallowTouchInterception(true);
                    for (int pi = 0; pi < e.getPointerCount(); pi++) {
                        int id = e.getPointerId(pi);
                        if (id < 0 || id >= pointerNote.length) continue;
                        int note = noteAt(e.getX(pi), e.getY(pi));
                        boolean ch = chordAt(e.getY(pi));
                        boolean alt = altAt(e.getY(pi));
                        boolean up = upperAt(e.getY(pi));
                        if (pointerNote[id] != note || pointerChord[id] != ch
                                || pointerAlt[id] != alt || pointerUpper[id] != up) {
                            release(id);
                            press(id, note, ch, alt, up);
                        }
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    release(e.getPointerId(e.getActionIndex()));
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    disallowTouchInterception(false);
                    if (e.getActionMasked() == MotionEvent.ACTION_CANCEL
                            && (e.getPointerCount() >= 3 || trackedTouchCount() >= 3)) {
                        // Some OEM three-finger screenshot gestures still send
                        // ACTION_CANCEL even after interception is disallowed.
                        // Android gives no later UP events in that case, so
                        // retain the captured multi-touch chord long enough to
                        // play/record instead of cutting it at 700 ms.
                        cancelPending = true;
                        handler.removeCallbacks(cancelRelease);
                        handler.postDelayed(cancelRelease, MULTI_TOUCH_CANCEL_HOLD_MS);
                    } else {
                        releaseAll();
                    }
                    break;
                }
            }
            return true;
        }

        // In split mode the top half of the view is the melody board.
        private boolean upperAt(float y) {
            return split && y < getHeight() * 0.5f;
        }

        private void press(int id, int note, boolean chordOn, boolean alt, boolean upper) {
            if (id < 0 || id >= pointerNote.length || note < 0 || note > 127) return;
            lastPressMs = SystemClock.uptimeMillis();
            pointerNote[id] = note;
            pointerChord[id] = chordOn;
            pointerAlt[id] = alt;
            pointerUpper[id] = upper;
            int[] notes = chordNotes(note, chordOn);
            pointerNotes[id] = notes;
            int[] board = upper ? pressUpper : pressLower;
            int[] counts = alt ? soundCount2 : soundCount;
            for (int i = 0; i < notes.length; i++) {
                int n = notes[i];
                if (n < 0 || n > 127) continue;
                board[n]++;
                counts[n]++;
                // Root leads; the added chord voices sit a touch under it.
                float v = i == 0 ? 0.9f : 0.72f;
                // Always restrike, even when another finger already sounds this
                // note (same key on the other board, shared chord voice) — a
                // tap must always be audible. The note-off stays refcounted.
                if (alt) {
                    engine.loopKey2On(n, v);
                } else {
                    engine.loopKeyOn(n, v);
                }
            }
            invalidate();
        }

        private void release(int id) {
            if (id < 0 || id >= pointerNote.length) return;
            int[] notes = pointerNotes[id];
            pointerNotes[id] = null;
            pointerNote[id] = -1;
            if (notes != null) {
                boolean alt = pointerAlt[id];
                int[] board = pointerUpper[id] ? pressUpper : pressLower;
                int[] counts = alt ? soundCount2 : soundCount;
                for (int n : notes) {
                    if (n < 0 || n > 127) continue;
                    if (board[n] > 0) board[n]--;
                    // A shared chord tone can be struck more than once (for
                    // example C-E-G, then E-G-B). TinySoundFont releases one
                    // note-on generation for each note-off, so every press
                    // above must get its own release here. Waiting until the
                    // refcount reaches zero leaves the retriggered voice held.
                    if (counts[n] > 0) counts[n]--;
                    if (alt) {
                        engine.loopKey2Off(n);
                    } else {
                        engine.loopKeyOff(n);
                    }
                }
            }
            invalidate();
        }

        private void releaseAll() {
            for (int id = 0; id < pointerNote.length; id++) release(id);
        }

        // Torn down mid-touch (pad mode switch, screen exit): no UP event will
        // ever come, so let go of everything now or sustained sounds stick.
        @Override
        protected void onDetachedFromWindow() {
            handler.removeCallbacks(cancelRelease);
            cancelPending = false;
            releaseAll();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth();
            float h = getHeight();
            // Gentle glide toward the target positions (each keyboard its own).
            if (Math.abs(targetScroll - scroll) > 0.004f) {
                scroll += (targetScroll - scroll) * 0.30f;
                postInvalidateOnAnimation();
            } else {
                scroll = targetScroll;
            }
            if (Math.abs(melTargetScroll - melScroll) > 0.004f) {
                melScroll += (melTargetScroll - melScroll) * 0.30f;
                postInvalidateOnAnimation();
            } else {
                melScroll = melTargetScroll;
            }
            // Split transition: the melody board stretches down from the top
            // edge while the chord board's top slides down to the middle.
            if (Math.abs(splitTarget - splitAnim) > 0.01f) {
                splitAnim += (splitTarget - splitAnim) * 0.18f;
                postInvalidateOnAnimation();
            } else {
                splitAnim = splitTarget;
            }
            float t = splitAnim;
            if (t > 0.001f) {
                float gap2 = 3 * d * t;
                float mid = h * 0.5f * t;
                if (mid - gap2 > 6 * d) {
                    drawKeyboard(canvas, 0, mid - gap2, melScroll, d, w, true);
                }
                drawKeyboard(canvas, mid + gap2, h, scroll, d, w, false);
            } else {
                drawKeyboard(canvas, 0, h, scroll, d, w, false);
            }
        }

        private void drawKeyboard(Canvas canvas, float top, float bot, float sc, float d,
                                  float w, boolean upperBoard) {
            float ww = w / count;
            float gap = 2.0f * d;
            float ts = split ? 10 * d : 12 * d;
            // Each board lights only its own presses: blue below, purple above.
            int[] pressed = upperBoard ? pressUpper : pressLower;
            int litColor = upperBoard ? litUpper : litLower;
            int first = (int) Math.floor(sc);
            for (int wi = Math.max(0, first); wi <= first + count && wi < TOTAL_WHITES; wi++) {
                int note = whiteNoteAt(wi);
                boolean lit = pressed[note] > 0;
                float x0 = (wi - sc) * ww;
                rect.set(x0 + gap, top, x0 + ww - gap, bot);
                paint.setColor(lit ? litColor : COLOR_SURFACE_RAISED);
                canvas.drawRoundRect(rect, 4 * d, 4 * d, paint);
                textPaint.setColor(lit ? Color.rgb(8, 10, 14) : Color.rgb(120, 124, 130));
                textPaint.setTextSize(ts);
                canvas.drawText(noteName(note), x0 + ww / 2, bot - 8 * d, textPaint);
            }
            float bw = ww * 0.58f;
            float bh = (bot - top) * 0.58f;
            for (int wi = Math.max(0, first); wi <= first + count && wi < TOTAL_WHITES - 1; wi++) {
                int black = whiteNoteAt(wi) + 1;
                if (isWhiteKey(black)) continue;
                float cx = (wi + 1 - sc) * ww;
                rect.set(cx - bw / 2, top, cx + bw / 2, top + bh);
                paint.setColor(pressed[black] > 0 ? litColor : COLOR_SKY_KEY_DARK);
                canvas.drawRoundRect(rect, 4 * d, 4 * d, paint);
            }
        }
    }

    // Full playable piano manual: touch-to-play, zoomable (all 88 keys down to a
    // handful) and horizontally scrollable. Optionally auto-chords (diatonic
    // triads) and routes to Sound 2. Notes refcount so overlaps release cleanly.
    private interface PianoBoardListener { void onNote(int note, float vel, boolean sound2, boolean down); }
    private interface PianoBoardStateSink { void onState(int visibleWhites, int baseWhite); }

    private final class PianoBoardView extends View {
        private static final int TOTAL_WHITES = 52;   // A0..C8
        private static final int MAX_IDS = 32;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int[] pointerNote = new int[MAX_IDS];
        private final int[][] pointerNotes = new int[MAX_IDS][];
        private final int[] soundCount = new int[128];
        private final int[] press = new int[128];
        private int accent = COLOR_TEAL;
        private boolean chord;
        private boolean sound2;
        private float visibleWhites = 14f;
        private float scroll;          // leftmost visible white index (fractional)
        private float targetScroll;
        private PianoBoardListener listener;
        private PianoBoardStateSink stateSink;
        private TextView rangeReadout;
        // Some Android builds reserve a three-finger gesture and send CANCEL
        // even after the keyboard has accepted all pointers. Keep the chord
        // audible briefly instead of cutting it the instant the OS steals it.
        private boolean multiTouchCancelPending;
        private final Runnable multiTouchCancelRelease = () -> {
            multiTouchCancelPending = false;
            releaseAll();
        };

        PianoBoardView(Context c) {
            super(c);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextAlign(Paint.Align.CENTER);
            java.util.Arrays.fill(pointerNote, -1);
        }

        void setListener(PianoBoardListener l) { listener = l; }
        void setStateSink(PianoBoardStateSink s) { stateSink = s; }
        void setRangeReadout(TextView t) { rangeReadout = t; updateReadout(); }
        void setAccent(int a) { accent = a; invalidate(); }
        void setChord(boolean on) { releaseAll(); chord = on; invalidate(); }
        void setSound2(boolean on) { releaseAll(); sound2 = on; }
        // Key-split marker: notes below play Keyboard A (accent), notes at/above
        // play Keyboard B (purple). -1 = no split shown. Colours the two regions
        // and draws a divider so the single board reads as split (like the looper).
        private int splitMark = -1;
        void setSplitMark(int note) { splitMark = note; invalidate(); }

        void setVisibleWhites(int n) {
            visibleWhites = clampZoom(n);
            targetScroll = clampScroll(targetScroll);
            scroll = clampScroll(scroll);
            emitState();
            invalidate();
        }

        // Keep the window centre roughly fixed while zooming.
        void zoomBy(int deltaWhites) {
            float centre = scroll + visibleWhites / 2f;
            visibleWhites = clampZoom((int) Math.round(visibleWhites) + deltaWhites);
            targetScroll = clampScroll(centre - visibleWhites / 2f);
            if (getWidth() == 0) scroll = targetScroll;
            emitState();
            invalidate();
        }

        void setScrollWhite(int idx) {
            targetScroll = clampScroll(idx);
            scroll = targetScroll;
            emitState();
            invalidate();
        }

        void scrollByWhites(int steps) {
            targetScroll = clampScroll(Math.round(scroll) + steps);
            emitState();
            invalidate();
        }

        private int clampZoom(int n) { return Math.max(4, Math.min(TOTAL_WHITES, n)); }

        private float clampScroll(float s) {
            float max = TOTAL_WHITES - visibleWhites;
            if (max < 0) max = 0;
            return s < 0 ? 0 : (s > max ? max : s);
        }

        private void emitState() {
            if (stateSink != null) {
                stateSink.onState(Math.round(visibleWhites), Math.round(clampScroll(targetScroll)));
            }
            updateReadout();
        }

        private void updateReadout() {
            if (rangeReadout == null) return;
            int lo = whiteNoteAt((int) Math.floor(clampScroll(targetScroll)));
            rangeReadout.setText(noteName(lo) + "  ·  " + Math.round(visibleWhites) + " keys");
        }

        private int chordType;   // 0 = fingered, 1 = diatonic, 2+ = fixed quality
        // Fingered mode: each held root latches its voicing. A modifier key only
        // CHANGES the quality while pressed; releasing it leaves the chord as set.
        private final int[][] rootChord = new int[128][];    // voicing per active root (null = none)
        private final boolean[] pointerIsRoot = new boolean[MAX_IDS];
        // A freshly pressed root waits this long before sounding, so a modifier
        // key struck almost together voices the chord directly (no major→min blip).
        private final boolean[] rootPending = new boolean[128];
        private final Runnable[] rootCommit = new Runnable[128];
        private static final int FINGER_WINDOW = 42;  // ms
        void setChordType(int t) { releaseAll(); chordType = t; invalidate(); }
        private boolean fingered() { return chord && chordType == 0; }

        // Fixed quality (2+): stack that shape on every key. Diatonic (1): triad
        // on white keys (C-E-G ...), major on black keys (B → B major). Fingered
        // (0) is handled in pressFingered(), not here — a lone key = major.
        private int[] notesFor(int root) {
            if (!chord) return new int[]{root};
            if (chordType >= 2 && chordType < CHORD_QUALITY_IV.length) {
                int[] iv = CHORD_QUALITY_IV[chordType];
                int[] out = new int[iv.length];
                for (int i = 0; i < iv.length; i++) out[i] = Math.min(127, root + iv[i]);
                return out;
            }
            if (chordType == 1 && isWhiteKey(root) && root % 12 != 11) {
                return new int[]{root, whiteAbove(root, 2), whiteAbove(root, 4)};
            }
            return new int[]{root, root + 4, root + 7};
        }

        // A fingered chord's notes: root alone = major; a modifier interval sets
        // the quality (♭3=min, 4=sus4, 2=sus2, ♭5=dim), fifth auto-added.
        private int[] fingerVoicing(int root, int modInterval) {
            if (modInterval < 0) return new int[]{root, root + 4, root + 7};
            if (modInterval == 6) return new int[]{root, root + 3, root + 6};
            return new int[]{root, root + modInterval, root + 7};
        }
        private boolean has(int[] a, int n) { for (int x : a) if (x == n) return true; return false; }
        private void soundNote(int n, float v, boolean on) {
            if (n < 0 || n > 127) return;
            if (on) { soundCount[n]++; if (soundCount[n] == 1) { press[n] = 1; if (listener != null) listener.onNote(n, v, sound2, true); } }
            else { if (soundCount[n] > 0) soundCount[n]--; if (soundCount[n] == 0) { press[n] = 0; if (listener != null) listener.onNote(n, 0f, sound2, false); } }
        }
        // Press in fingered mode: a key inside a held root (+2..+6) re-voices that
        // root to the new quality; otherwise the key starts a new major chord.
        private void pressFingered(int id, int k) {
            pointerNote[id] = k;
            if (k >= 0 && k < 128 && rootChord[k] != null) { pointerIsRoot[id] = false; return; }  // dup root
            int host = -1;
            for (int r = k - 2; r >= k - 6 && r >= 0; r--) { if (rootChord[r] != null) { host = r; break; } }
            if (host >= 0) {
                pointerIsRoot[id] = false;
                int[] nv = fingerVoicing(host, k - host);
                if (rootPending[host]) {
                    // Root not sounded yet: just set its voicing; the timer sounds it.
                    rootChord[host] = nv;
                } else {
                    int[] ov = rootChord[host];
                    for (int n : ov) if (!has(nv, n)) soundNote(n, 0f, false);
                    for (int i = 0; i < nv.length; i++) if (!has(ov, nv[i])) soundNote(nv[i], i == 0 ? 0.85f : 0.72f, true);
                    rootChord[host] = nv;
                }
            } else {
                pointerIsRoot[id] = true;
                final int rk = k;
                rootChord[rk] = fingerVoicing(rk, -1);
                rootPending[rk] = true;
                Runnable run = new Runnable() {
                    @Override public void run() {
                        if (!rootPending[rk] || rootChord[rk] == null) return;
                        rootPending[rk] = false;
                        rootCommit[rk] = null;
                        int[] v = rootChord[rk];
                        for (int i = 0; i < v.length; i++) soundNote(v[i], i == 0 ? 0.85f : 0.72f, true);
                        invalidate();
                    }
                };
                rootCommit[rk] = run;
                postDelayed(run, FINGER_WINDOW);
            }
            invalidate();
        }
        // Release in fingered mode: a root releases its (latched) chord; a modifier
        // release does nothing — the quality it set stays.
        private void releaseFingered(int id) {
            int k = pointerNote[id];
            pointerNote[id] = -1;
            if (k >= 0 && k < 128 && pointerIsRoot[id] && rootChord[k] != null) {
                if (rootPending[k]) {          // released before it ever sounded
                    if (rootCommit[k] != null) removeCallbacks(rootCommit[k]);
                    rootPending[k] = false; rootCommit[k] = null;
                } else {
                    for (int n : rootChord[k]) soundNote(n, 0f, false);
                }
                rootChord[k] = null;
            }
            pointerIsRoot[id] = false;
            invalidate();
        }

        private int whiteAbove(int note, int steps) {
            int n = note;
            while (steps > 0 && n < 127) { n++; if (isWhiteKey(n)) steps--; }
            return n;
        }

        private int noteAt(float x, float y) {
            float h = getHeight();
            float ww = getWidth() / visibleWhites;
            float xw = x / ww + scroll;
            if (y < h * 0.58f) {
                int first = (int) Math.floor(scroll);
                for (int wi = Math.max(0, first); wi <= first + visibleWhites && wi < TOTAL_WHITES - 1; wi++) {
                    int black = whiteNoteAt(wi) + 1;
                    if (isWhiteKey(black)) continue;
                    float bx = wi + 1;
                    if (xw >= bx - 0.29f && xw <= bx + 0.29f) return black;
                }
            }
            int wi = (int) Math.floor(xw);
            if (wi < 0) wi = 0;
            if (wi > TOTAL_WHITES - 1) wi = TOTAL_WHITES - 1;
            return whiteNoteAt(wi);
        }

        private void disallowIntercept(boolean d) {
            android.view.ViewParent p = getParent();
            while (p != null) { p.requestDisallowInterceptTouchEvent(d); p = p.getParent(); }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    disallowIntercept(true);
                    if (multiTouchCancelPending) {
                        removeCallbacks(multiTouchCancelRelease);
                        multiTouchCancelPending = false;
                    }
                    for (int pi = 0; pi < e.getPointerCount(); pi++) {
                        int id = e.getPointerId(pi);
                        if (id < 0 || id >= MAX_IDS) continue;
                        if (pi == e.getActionIndex() && pointerNote[id] >= 0) release(id);
                        if (pointerNote[id] < 0) press(id, noteAt(e.getX(pi), e.getY(pi)));
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    disallowIntercept(true);
                    for (int pi = 0; pi < e.getPointerCount(); pi++) {
                        int id = e.getPointerId(pi);
                        if (id < 0 || id >= MAX_IDS) continue;
                        int note = noteAt(e.getX(pi), e.getY(pi));
                        if (pointerNote[id] != note) { release(id); press(id, note); }
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    release(e.getPointerId(e.getActionIndex()));
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    disallowIntercept(false);
                    if (e.getActionMasked() == MotionEvent.ACTION_CANCEL
                            && (e.getPointerCount() >= 3 || trackedTouchCount() >= 3)) {
                        multiTouchCancelPending = true;
                        removeCallbacks(multiTouchCancelRelease);
                        postDelayed(multiTouchCancelRelease, 3500L);
                    } else {
                        releaseAll();
                    }
                    break;
            }
            return true;
        }

        private void press(int id, int root) {
            if (id < 0 || id >= MAX_IDS || root < 0 || root > 127) return;
            if (fingered()) { pressFingered(id, root); return; }
            pointerNote[id] = root;
            int[] notes = notesFor(root);
            pointerNotes[id] = notes;
            for (int i = 0; i < notes.length; i++) {
                int n = notes[i];
                if (n < 0 || n > 127) continue;
                press[n]++;
                soundCount[n]++;
                float v = i == 0 ? 0.9f : 0.72f;
                if (listener != null) listener.onNote(n, v, sound2, true);
            }
            invalidate();
        }

        private void release(int id) {
            if (id < 0 || id >= MAX_IDS) return;
            if (fingered()) { releaseFingered(id); return; }
            int[] notes = pointerNotes[id];
            pointerNotes[id] = null;
            pointerNote[id] = -1;
            if (notes == null) return;
            for (int n : notes) {
                if (n < 0 || n > 127) continue;
                if (press[n] > 0) press[n]--;
                if (soundCount[n] > 0) soundCount[n]--;
                if (listener != null) listener.onNote(n, 0f, sound2, false);
            }
            invalidate();
        }

        private int trackedTouchCount() {
            int count = 0;
            for (int note : pointerNote) if (note >= 0) count++;
            return count;
        }

        void releaseAll() {
            removeCallbacks(multiTouchCancelRelease);
            multiTouchCancelPending = false;
            for (int id = 0; id < MAX_IDS; id++) release(id);
        }

        @Override protected void onDetachedFromWindow() {
            releaseAll();
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight();
            if (Math.abs(targetScroll - scroll) > 0.004f) {
                scroll += (targetScroll - scroll) * 0.3f;
                postInvalidateOnAnimation();
            } else {
                scroll = targetScroll;
            }
            float ww = w / visibleWhites;
            float gap = 2f * d;
            float ts = Math.min(13f * d, ww * 0.34f);
            int first = (int) Math.floor(scroll);
            for (int wi = Math.max(0, first); wi <= first + visibleWhites && wi < TOTAL_WHITES; wi++) {
                int note = whiteNoteAt(wi);
                boolean lit = press[note] > 0;
                boolean hi = splitMark >= 0 && note >= splitMark;
                int keyAccent = hi ? COLOR_PURPLE : accent;
                float x0 = (wi - scroll) * ww;
                rect.set(x0 + gap, 0, x0 + ww - gap, h);
                // Unpressed keys get a faint wash per region so the split shows.
                int base = splitMark < 0 ? COLOR_SURFACE_RAISED
                        : (hi ? Color.rgb(236, 234, 252) : COLOR_SKY_CONTROL);
                paint.setColor(lit ? keyAccent : base);
                canvas.drawRoundRect(rect, 4 * d, 4 * d, paint);
                if (ww >= 18 * d || note % 12 == 0) {
                    textPaint.setColor(lit ? Color.rgb(8, 10, 14) : Color.rgb(120, 124, 130));
                    textPaint.setTextSize(ts);
                    canvas.drawText(noteName(note), x0 + ww / 2, h - 8 * d, textPaint);
                }
            }
            float bw = ww * 0.58f, bh = h * 0.58f;
            for (int wi = Math.max(0, first); wi <= first + visibleWhites && wi < TOTAL_WHITES - 1; wi++) {
                int black = whiteNoteAt(wi) + 1;
                if (isWhiteKey(black)) continue;
                boolean hi = splitMark >= 0 && black >= splitMark;
                float cx = (wi + 1 - scroll) * ww;
                rect.set(cx - bw / 2, 0, cx + bw / 2, bh);
                paint.setColor(press[black] > 0 ? (hi ? COLOR_PURPLE : accent) : COLOR_SKY_KEY_DARK);
                canvas.drawRoundRect(rect, 4 * d, 4 * d, paint);
            }
            // Divider at the split boundary.
            if (splitMark >= 0) {
                float sx = (whiteIndexOf(splitMark) - scroll) * ww;
                paint.setColor(COLOR_PURPLE);
                canvas.drawRect(sx - 1.5f * d, 0, sx + 1.5f * d, h, paint);
            }
        }
    }

    // Stylized per-instrument icon drawn on Canvas for the intro grid.
    private static final class InstrumentIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final InstrumentMode mode;
        private final int accent;

        InstrumentIconView(Context context, InstrumentMode mode, int accent) {
            super(context);
            this.mode = mode;
            this.accent = accent;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // Draw inside a safety inset: shapes and round stroke caps that
            // reach the unit edge were getting clipped by the view bounds.
            float s = Math.min(getWidth(), getHeight()) * 0.86f;
            float ox = (getWidth() - s) / 2f;
            float oy = (getHeight() - s) / 2f;
            canvas.translate(ox, oy);
            switch (mode) {
                case ELECTRIC_GUITAR: drawGuitar(canvas, s); break;
                case BASS: drawBass(canvas, s); break;
                case PIANO: drawPiano(canvas, s); break;
                case DRUMS: drawDrums(canvas, s); break;
            }
        }

        private void stroke(float w) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(w);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(accent);
        }

        private void fill(int color) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
        }

        private void drawGuitar(Canvas c, float s) {
            // Solid-body electric (double-cutaway): body + horns, no sound hole.
            fill(accent);
            c.drawCircle(0.50f * s, 0.74f * s, 0.215f * s, paint);   // lower bout
            c.drawCircle(0.50f * s, 0.56f * s, 0.165f * s, paint);   // upper bout
            c.drawCircle(0.33f * s, 0.52f * s, 0.085f * s, paint);   // left horn
            c.drawCircle(0.67f * s, 0.52f * s, 0.085f * s, paint);   // right horn
            // neck
            stroke(0.075f * s);
            c.drawLine(0.50f * s, 0.52f * s, 0.50f * s, 0.12f * s, paint);
            // pointed headstock
            fill(accent);
            Path hs = new Path();
            hs.moveTo(0.455f * s, 0.135f * s);
            hs.lineTo(0.545f * s, 0.135f * s);
            hs.lineTo(0.585f * s, 0.04f * s);
            hs.lineTo(0.415f * s, 0.04f * s);
            hs.close();
            c.drawPath(hs, paint);
            // tuning pegs
            fill(COLOR_BACKGROUND);
            c.drawCircle(0.455f * s, 0.075f * s, 0.017f * s, paint);
            c.drawCircle(0.545f * s, 0.075f * s, 0.017f * s, paint);
            // two pickups + bridge (electric hardware, where a hole would be)
            fill(COLOR_BACKGROUND);
            r.set(0.36f * s, 0.625f * s, 0.64f * s, 0.665f * s);
            c.drawRoundRect(r, 0.012f * s, 0.012f * s, paint);
            r.set(0.36f * s, 0.705f * s, 0.64f * s, 0.745f * s);
            c.drawRoundRect(r, 0.012f * s, 0.012f * s, paint);
            r.set(0.42f * s, 0.795f * s, 0.58f * s, 0.825f * s);
            c.drawRect(r, paint);                                    // bridge
            // tone/volume knobs
            c.drawCircle(0.40f * s, 0.86f * s, 0.018f * s, paint);
            c.drawCircle(0.56f * s, 0.86f * s, 0.018f * s, paint);
        }

        private void drawBass(Canvas c, float s) {
            // Bass guitar: longer neck, single fat pickup, 4 in-line tuning pegs.
            fill(accent);
            c.drawCircle(0.46f * s, 0.78f * s, 0.20f * s, paint);    // lower bout
            c.drawCircle(0.50f * s, 0.62f * s, 0.15f * s, paint);    // upper bout
            c.drawCircle(0.33f * s, 0.58f * s, 0.085f * s, paint);   // horn
            // long neck
            stroke(0.07f * s);
            c.drawLine(0.50f * s, 0.60f * s, 0.52f * s, 0.10f * s, paint);
            // headstock (4-in-line, angled)
            fill(accent);
            Path hs = new Path();
            hs.moveTo(0.49f * s, 0.13f * s);
            hs.lineTo(0.55f * s, 0.115f * s);
            hs.lineTo(0.65f * s, 0.03f * s);
            hs.lineTo(0.55f * s, 0.05f * s);
            hs.close();
            c.drawPath(hs, paint);
            // 4 tuning pegs along the headstock
            fill(COLOR_BACKGROUND);
            c.drawCircle(0.55f * s, 0.095f * s, 0.015f * s, paint);
            c.drawCircle(0.585f * s, 0.075f * s, 0.015f * s, paint);
            c.drawCircle(0.62f * s, 0.055f * s, 0.015f * s, paint);
            c.drawCircle(0.655f * s, 0.035f * s, 0.015f * s, paint);
            // single big pickup + bridge
            fill(COLOR_BACKGROUND);
            r.set(0.34f * s, 0.705f * s, 0.62f * s, 0.755f * s);
            c.drawRoundRect(r, 0.014f * s, 0.014f * s, paint);
            r.set(0.40f * s, 0.835f * s, 0.56f * s, 0.865f * s);
            c.drawRect(r, paint);                                    // bridge
        }

        private void drawPiano(Canvas c, float s) {
            fill(accent);
            r.set(0.10f * s, 0.30f * s, 0.90f * s, 0.74f * s);
            c.drawRoundRect(r, 0.04f * s, 0.04f * s, paint);
            // white keys
            float left = 0.13f * s, right = 0.87f * s, top = 0.345f * s, bot = 0.705f * s;
            float kw = (right - left) / 5f;
            fill(COLOR_TEXT);
            for (int i = 0; i < 5; i++) {
                r.set(left + i * kw + 0.008f * s, top, left + (i + 1) * kw - 0.008f * s, bot);
                c.drawRoundRect(r, 0.012f * s, 0.012f * s, paint);
            }
            // black keys (after keys 0,1,3)
            fill(COLOR_BACKGROUND);
            int[] gaps = {1, 2, 4};
            float bw = kw * 0.5f;
            for (int g : gaps) {
                float cx = left + g * kw;
                r.set(cx - bw / 2f, top, cx + bw / 2f, top + 0.21f * s);
                c.drawRoundRect(r, 0.01f * s, 0.01f * s, paint);
            }
        }

        private void drawDrums(Canvas c, float s) {
            // cymbal + stand
            stroke(0.05f * s);
            r.set(0.52f * s, 0.16f * s, 0.94f * s, 0.26f * s);
            c.drawOval(r, paint);
            c.drawLine(0.73f * s, 0.22f * s, 0.66f * s, 0.58f * s, paint);
            // bass drum
            stroke(0.06f * s);
            c.drawCircle(0.42f * s, 0.66f * s, 0.26f * s, paint);
            c.drawCircle(0.42f * s, 0.66f * s, 0.10f * s, paint);
            // legs
            c.drawLine(0.24f * s, 0.86f * s, 0.18f * s, 0.94f * s, paint);
            c.drawLine(0.60f * s, 0.86f * s, 0.66f * s, 0.94f * s, paint);
        }
    }

    // Tuning-fork icon for the picker's Tuner card.
    // Rising-note visualizer for the landscape full keyboard: each played note
    // grows a glowing bar up from the strike line (length = how long it's held),
    // releases into an upward float, and bursts sparkles. Aligned to the keyboard
    // below it (same A0..C8 / 52-white mapping). Self-animating (~60fps).
    private final class KeyVizView extends View {
        private static final int START = 21, WHITES = 52, MAXB = 80, MAXP = 140;   // A0..C8
        private final int[] WHITE_PC = {0, 2, 4, 5, 7, 9, 11};
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final boolean[] pressed = new boolean[128];
        private final float d;
        private boolean running = false;
        private long lastFrame = 0L;

        private final int[] bnote = new int[MAXB];
        private final float[] bx = new float[MAXB], bw = new float[MAXB];
        private final float[] btop = new float[MAXB], bbot = new float[MAXB], blife = new float[MAXB];
        private final boolean[] bheld = new boolean[MAXB];
        private final int[] bcol = new int[MAXB];
        private int bcount = 0;

        private final float[] px = new float[MAXP], py = new float[MAXP];
        private final float[] pvx = new float[MAXP], pvy = new float[MAXP];
        private final float[] plife = new float[MAXP], psz = new float[MAXP];
        private final int[] pcol = new int[MAXP];
        private int pcount = 0;

        private final int[] palette;

        private final Runnable frame = new Runnable() {
            public void run() {
                if (!running) return;
                invalidate();
                postOnAnimationDelayed(this, 16);
            }
        };

        KeyVizView(Context c) {
            super(c);
            d = getResources().getDisplayMetrics().density;
            palette = new int[]{COLOR_GREEN, COLOR_TEAL, 0xFF7CF2A0};
        }

        void setPressedNotes(boolean[] keys) {
            System.arraycopy(keys, 0, pressed, 0, 128);
        }

        void onNote(int note, boolean on) {
            if (getWidth() == 0) return;
            if (on) {
                float x = noteCenterX(note);
                float w = (isWhite(note) ? ww() * 0.86f : ww() * 0.62f);
                int col = palette[Math.floorMod(note, palette.length)];
                if (bcount < MAXB) {
                    int i = bcount++;
                    bnote[i] = note; bx[i] = x; bw[i] = w;
                    bbot[i] = getHeight(); btop[i] = getHeight();
                    bheld[i] = true; blife[i] = 1f; bcol[i] = col;
                }
                for (int k = 0; k < 12; k++) spawnP(x + rnd(-1, 1) * w * 0.5f, getHeight(), col);
            } else {
                for (int i = bcount - 1; i >= 0; i--) {
                    if (bheld[i] && bnote[i] == note) { bheld[i] = false; break; }
                }
            }
        }

        private float ww() { return (float) getWidth() / WHITES; }
        private boolean isWhite(int n) {
            int pc = n % 12;
            for (int w : WHITE_PC) if (w == pc) return true;
            return false;
        }
        private int whitesBelow(int note) {
            int c = 0;
            for (int n = START; n < note; n++) if (isWhite(n)) c++;
            return c;
        }
        private float noteCenterX(int note) {
            if (note < START) note = START;
            if (note > 108) note = 108;
            int wb = whitesBelow(note);
            return isWhite(note) ? (wb + 0.5f) * ww() : wb * ww();
        }
        private float rnd(float a, float b) { return a + (float) Math.random() * (b - a); }

        private void spawnP(float x, float y, int col) {
            if (pcount >= MAXP) return;
            int i = pcount++;
            px[i] = x; py[i] = y;
            pvx[i] = rnd(-45, 45) * d;
            pvy[i] = -rnd(70, 220) * d;
            plife[i] = 1f; psz[i] = rnd(1.4f, 3.6f) * d; pcol[i] = col;
        }
        private void killP(int i) {
            pcount--;
            px[i] = px[pcount]; py[i] = py[pcount]; pvx[i] = pvx[pcount]; pvy[i] = pvy[pcount];
            plife[i] = plife[pcount]; psz[i] = psz[pcount]; pcol[i] = pcol[pcount];
        }
        private void killB(int i) {
            bcount--;
            bnote[i] = bnote[bcount]; bx[i] = bx[bcount]; bw[i] = bw[bcount];
            btop[i] = btop[bcount]; bbot[i] = bbot[bcount]; blife[i] = blife[bcount];
            bheld[i] = bheld[bcount]; bcol[i] = bcol[bcount];
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            running = true; lastFrame = 0L;
            postOnAnimation(frame);
        }
        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            running = false; removeCallbacks(frame);
        }

        @Override protected void onDraw(Canvas canvas) {
            long now = System.nanoTime();
            float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1e9f);
            lastFrame = now;
            float H = getHeight();
            float grow = 230 * d, rise = 190 * d;

            p.setStyle(Paint.Style.FILL);
            for (int i = bcount - 1; i >= 0; i--) {
                if (bheld[i]) {
                    btop[i] -= grow * dt;
                    if (btop[i] < 0) btop[i] = 0;
                    bbot[i] = H;
                } else {
                    btop[i] -= rise * dt;
                    bbot[i] -= rise * dt;
                    blife[i] -= dt * 0.8f;
                    if (bbot[i] < -10 * d || blife[i] <= 0) { killB(i); continue; }
                }
                int base = bcol[i];
                int alpha = bheld[i] ? 235 : (int) (Math.max(0f, blife[i]) * 235);
                p.setColor(Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base)));
                r.set(bx[i] - bw[i] / 2f, btop[i], bx[i] + bw[i] / 2f, bbot[i]);
                canvas.drawRoundRect(r, 4 * d, 4 * d, p);
            }

            for (int i = pcount - 1; i >= 0; i--) {
                px[i] += pvx[i] * dt; py[i] += pvy[i] * dt; pvy[i] *= 0.99f; plife[i] -= dt * 1.1f;
                if (plife[i] <= 0 || py[i] < -10 * d) { killP(i); continue; }
                int a = (int) (Math.max(0f, plife[i]) * 230);
                p.setColor(Color.argb(a, Color.red(pcol[i]), Color.green(pcol[i]), Color.blue(pcol[i])));
                canvas.drawCircle(px[i], py[i], psz[i], p);
            }

            drawStrikeGlow(canvas, H);
        }

        private void drawStrikeGlow(Canvas canvas, float H) {
            int g = COLOR_GREEN;
            for (int k = 0; k < 5; k++) {
                int a = 24 - k * 4;
                if (a < 0) a = 0;
                p.setColor(Color.argb(a, Color.red(g), Color.green(g), Color.blue(g)));
                canvas.drawRect(0, H - (k + 1) * 9 * d, getWidth(), H, p);
            }
            p.setColor(Color.argb(230, Color.red(g), Color.green(g), Color.blue(g)));
            canvas.drawRect(0, H - 2.2f * d, getWidth(), H, p);
        }
    }

    // Transport icons: a record dot or a metronome, recolored by active state.
    private static final class TransportIconView extends View {
        static final int RECORD = 0, METRONOME = 1;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int kind;
        private int color;
        private boolean recording = false;

        TransportIconView(Context context, int kind, int color) {
            super(context);
            this.kind = kind;
            this.color = color;
        }

        void setColor(int c) { color = c; invalidate(); }
        void setRecording(boolean r) { recording = r; invalidate(); }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h) * 0.5f, cx = w / 2f, cy = h / 2f;
            paint.setColor(color);
            if (kind == RECORD) {
                if (recording) {
                    // square "stop" glyph while capturing
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRect(cx - s * 0.42f, cy - s * 0.42f, cx + s * 0.42f, cy + s * 0.42f, paint);
                } else {
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(cx, cy, s * 0.5f, paint);
                }
            } else {
                // metronome: trapezoid body + angled pendulum rod
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(s * 0.15f);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeCap(Paint.Cap.ROUND);
                float top = cy - s * 0.62f, bot = cy + s * 0.6f;
                path.reset();
                path.moveTo(cx - s * 0.2f, top);
                path.lineTo(cx - s * 0.52f, bot);
                path.lineTo(cx + s * 0.52f, bot);
                path.lineTo(cx + s * 0.2f, top);
                path.close();
                canvas.drawPath(path, paint);
                canvas.drawLine(cx, bot - s * 0.12f, cx + s * 0.3f, top + s * 0.18f, paint);
            }
        }
    }

    // Drawn hamburger menu icon (three horizontal bars).
    private static final class MenuIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;

        MenuIconView(Context context, int color) {
            super(context);
            this.color = color;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            // A bare View with WRAP_CONTENT would fill the parent; pin to ~40dp.
            int s = Math.round(40 * getResources().getDisplayMetrics().density);
            setMeasuredDimension(resolveSize(s, widthSpec), resolveSize(s, heightSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float l = getPaddingLeft(), t = getPaddingTop();
            float w = getWidth() - l - getPaddingRight(), h = getHeight() - t - getPaddingBottom();
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.min(w, h) * 0.14f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            float x0 = l, x1 = l + w;
            for (int i = 0; i < 3; i++) {
                float y = t + h * (0.22f + 0.28f * i);
                canvas.drawLine(x0, y, x1, y, paint);
            }
        }
    }

    // Drawn left-pointing back arrow (shaft + arrowhead), not a text glyph.
    private static final class BackIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;

        BackIconView(Context context, int color) {
            super(context);
            this.color = color;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            // A bare View with WRAP_CONTENT would fill the parent; pin to ~40dp.
            int s = Math.round(40 * getResources().getDisplayMetrics().density);
            setMeasuredDimension(resolveSize(s, widthSpec), resolveSize(s, heightSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float l = getPaddingLeft(), t = getPaddingTop();
            float w = getWidth() - l - getPaddingRight(), h = getHeight() - t - getPaddingBottom();
            float cy = t + h / 2f;
            float left = l + w * 0.08f, right = l + w * 0.92f;
            float head = Math.min(w, h) * 0.42f;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.min(w, h) * 0.13f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawLine(left, cy, right, cy, paint);          // shaft
            canvas.drawLine(left, cy, left + head, cy - head, paint);   // upper barb
            canvas.drawLine(left, cy, left + head, cy + head, paint);   // lower barb
        }
    }

    private static final class TunerIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final int accent;

        TunerIconView(Context context, int accent) {
            super(context);
            this.accent = accent;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float s = Math.min(getWidth(), getHeight());
            float ox = (getWidth() - s) / 2f, oy = (getHeight() - s) / 2f;
            canvas.translate(ox, oy);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(0.10f * s);
            paint.setColor(accent);
            canvas.drawLine(0.36f * s, 0.10f * s, 0.36f * s, 0.50f * s, paint);   // left prong
            canvas.drawLine(0.64f * s, 0.10f * s, 0.64f * s, 0.50f * s, paint);   // right prong
            r.set(0.36f * s, 0.36f * s, 0.64f * s, 0.64f * s);
            canvas.drawArc(r, 0f, 180f, false, paint);                            // U-bend
            canvas.drawLine(0.50f * s, 0.64f * s, 0.50f * s, 0.90f * s, paint);   // stem
        }
    }

    // Real-time tuner: a strip of note names that slides under a fixed center
    // pointer. The detected pitch eases toward the pointer; the nearest note
    // lines up with center when in tune.
    private static final class TunerMeterView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tri = new Path();
        private float targetMidi = 69f, displayMidi = 69f;
        private boolean active = false;

        TunerMeterView(Context context) {
            super(context);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        }

        void setReading(float midi, boolean a) {
            if (a) {
                if (!active) {
                    displayMidi = midi;   // snap when a note is (re)acquired
                }
                targetMidi = midi;
            }
            active = a;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight(), cx = w / 2f;
            float d = getResources().getDisplayMetrics().density;
            if (active) {
                displayMidi += (targetMidi - displayMidi) * 0.30f;   // smooth glide
            }

            int nearest = Math.round(displayMidi);
            float cents = (displayMidi - nearest) * 100f;
            boolean inTune = active && Math.abs(cents) <= 5f;
            int mark = !active ? COLOR_DIM : (inTune ? COLOR_GREEN : COLOR_AMBER);

            // big current note + octave (top)
            textPaint.setColor(active ? (inTune ? COLOR_GREEN : COLOR_TEXT) : COLOR_DIM);
            textPaint.setTextSize(Math.min(w, h) * 0.34f);
            canvas.drawText(active ? NOTE_NAMES[((nearest % 12) + 12) % 12] : "–", cx, h * 0.40f, textPaint);
            if (active) {
                textPaint.setTextSize(Math.min(w, h) * 0.11f);
                textPaint.setColor(COLOR_MUTED);
                canvas.drawText(String.valueOf(nearest / 12 - 1), cx + w * 0.16f, h * 0.28f, textPaint);
            }

            // sliding note strip
            float stripY = h * 0.76f;
            float px = w / 7.5f;   // pixels per semitone
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(1.5f * d);
            paint.setColor(COLOR_BORDER);
            canvas.drawLine(0, stripY, w, stripY, paint);
            for (int n = nearest - 5; n <= nearest + 5; n++) {
                float x = cx + (n - displayMidi) * px;
                if (x < -px || x > w + px) {
                    continue;
                }
                boolean isNearest = active && n == nearest;
                paint.setColor(isNearest ? mark : COLOR_BORDER);
                paint.setStrokeWidth((isNearest ? 2.5f : 1.5f) * d);
                canvas.drawLine(x, stripY - 7 * d, x, stripY + 7 * d, paint);
                textPaint.setColor(isNearest ? (inTune ? COLOR_GREEN : COLOR_TEXT)
                        : (active ? COLOR_MUTED : COLOR_DIM));
                textPaint.setTextSize((isNearest ? 18f : 14f) * d);
                canvas.drawText(NOTE_NAMES[((n % 12) + 12) % 12], x, stripY - 14 * d, textPaint);
            }

            // fixed center pointer (you-are-here)
            paint.setColor(mark);
            paint.setStrokeWidth(3f * d);
            canvas.drawLine(cx, stripY - 28 * d, cx, stripY + 28 * d, paint);
            tri.reset();
            tri.moveTo(cx - 7 * d, stripY - 34 * d);
            tri.lineTo(cx + 7 * d, stripY - 34 * d);
            tri.lineTo(cx, stripY - 26 * d);
            tri.close();
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(tri, paint);

            if (active && inTune) {
                textPaint.setColor(COLOR_GREEN);
                textPaint.setTextSize(14f * d);
                canvas.drawText("IN TUNE", cx, stripY + 46 * d, textPaint);
            }

            if (active && Math.abs(targetMidi - displayMidi) > 0.002f) {
                postInvalidateOnAnimation();
            }
        }
    }

    // Landscape "real kit": drums and cymbals drawn where they sit on an actual
    // kit, multi-touch playable. The hi-hat pedal chicks (44) on tap and, while
    // held, closes the hats so the hi-hat cymbal plays 42 instead of 46.
    private static final class DrumKitView extends View {
        interface PadListener {
            void onPad(int note, float velocity);
        }
        interface EditListener { void onLayoutChanged(String serialized); }

        // A realistic kit from the drummer's POV (matches the sketch), drawn FLAT
        // with the photo pads. Sizes track real drum sizes as seen from the
        // throne: kick biggest and up front, floor + ride large, rack toms small,
        // splash smallest.
        //   crash  splash  china  crash 2
        //   o hat  t1  t2                ride
        //   c hat        t3
        //   snare                  floor
        //   p hat  rim   kick
        static final int[] CAT_NOTES = {
                49, 52, 55, 57,      // 0-3  crash, china, splash, crash 2
                46, 48, 47, 51,      // 4-7  open hat, tom 1, tom 2, ride
                42, 38, 45,          // 8-10 closed hat, snare, tom 3
                44, 37, 36, 43,      // 11-14 pedal hat, rim, kick, floor
                84};                 // 15  chimes (bell tree)
        static final String[] CAT_LABELS = {
                "Crash", "China", "Splash", "Crash 2",
                "Open Hat", "Tom 1", "Tom 2", "Ride",
                "Closed Hat", "Snare", "Tom 3",
                "P Hat", "Rim", "Kick", "Floor",
                "Chimes"};
        // {centre x (of width), centre y (of height), radius (of height)}. Radius
        // is half the drawn size, so it doubles as the circular hit radius.
        private static final float[][] POS = {
                {0.20f,  0.18f, 0.150f},   // 0  crash
                {0.49f,  0.16f, 0.165f},   // 1  china
                {0.35f,  0.22f, 0.100f},   // 2  splash
                {0.70f,  0.19f, 0.160f},   // 3  crash 2
                {0.105f, 0.36f, 0.140f},   // 4  open hat
                {0.35f,  0.39f, 0.120f},   // 5  tom 1
                {0.50f,  0.40f, 0.130f},   // 6  tom 2
                {0.86f,  0.43f, 0.185f},   // 7  ride
                {0.115f, 0.57f, 0.130f},   // 8  closed hat
                {0.33f,  0.65f, 0.150f},   // 9  snare
                {0.64f,  0.47f, 0.145f},   // 10 tom 3
                {0.08f,  0.86f, 0.090f},   // 11 pedal hat
                {0.21f,  0.78f, 0.090f},   // 12 rim
                {0.47f,  0.80f, 0.200f},   // 13 kick
                {0.80f,  0.71f, 0.190f},   // 14 floor
                {0.60f,  0.10f, 0.075f},   // 15 chimes (bell tree)
        };
        private static final int EDIT_ACCENT = Color.rgb(45, 178, 168);
        private static android.graphics.Bitmap studioBg, cymbalBmp, kickBmp, floorBmp,
                snareBmp, tomBmp, percBmp;

        // Add-Piece categories (the photo kit's own categories, distinct from the
        // sample-library sub-categories). Each maps to a bitmap, a canonical GM
        // note (used when a piece just inherits the selected kit), a DrumSampleLib
        // category index for the "choose sound" list (-1 = none/WAV), and a size.
        static final String[] KCAT_NAME = {"Kick","Snare","Tom","Floor","Cymbal","Clap","Perc","Chimes"};
        static final int[]    KCAT_PNG  = {36,   38,     47,   43,     49,      38,    47,    55};   // bitmap key note
        static final int[]    KCAT_GM   = {36,   38,     47,   43,     49,      39,    47,    84};   // inherit-note
        static final int[]    KCAT_LIB  = {0,    1,      2,    2,      3,       4,     5,     -1};   // DrumSampleLib idx
        static final float[]  KCAT_R    = {.20f, .15f,   .13f, .19f,   .16f,    .14f,  .13f,  .10f};
        static final int CAT_CHIMES = 7;

        // A live, editable piece. `note` is the unique TRIGGER note (key for the
        // native per-piece tables + the pad). cat/name/soundCode/soundNote hold
        // its category and assigned sound; soundCode < 0 = inherit the selected
        // kit, soundNote < 0 = sound the trigger note itself.
        private static final class Piece {
            int note; float cx, cy, r, flash;
            float vol = 1.0f;      // per-piece loudness trim (0..1.4)
            int cat = -1;          // KCAT index, or -1 = legacy piece (derive from note)
            String name = "";      // custom label ("" = auto)
            int soundCode = -1;    // custom-kit source code, -1 = inherit selected kit
            int soundNote = -1;    // note within the source font, -1 = use `note`
            Piece(int n, float x, float y, float rr) { note = n; cx = x; cy = y; r = rr; }
        }

        private final java.util.ArrayList<Piece> pieces = new java.util.ArrayList<>();
        private final java.util.ArrayList<Piece> order = new java.util.ArrayList<>();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint imgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final RectF dst = new RectF();
        private final Rect studioSrc = new Rect();
        private final RectF studioDst = new RectF();
        private PadListener listener;
        private EditListener editListener;
        // Notes the current kit voices; missing pieces are ghosted + untappable.
        private final boolean[] noteAvail = new boolean[128];
        { java.util.Arrays.fill(noteAvail, true); }
        // Edit state
        private boolean editMode;
        private Piece selected;
        private int dragMode;           // 0 none · 1 move · 2 resize · 3 pinch
        private float dragDx, dragDy;   // finger->centre offset while moving
        private float pinchStartDist, pinchStartR;

        DrumKitView(Context context) {
            super(context);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextAlign(Paint.Align.CENTER);
            ensureBitmaps(context);
        }

        private static void ensureBitmaps(Context ctx) {
            android.content.res.Resources r = ctx.getResources();
            if (studioBg == null) {
                studioBg = android.graphics.BitmapFactory.decodeResource(
                        r, R.drawable.drum_studio_background);
            }
            if (cymbalBmp != null) return;
            cymbalBmp = android.graphics.BitmapFactory.decodeResource(r, R.drawable.drum_cymbal);
            kickBmp   = android.graphics.BitmapFactory.decodeResource(r, R.drawable.drum_kick);
            floorBmp  = android.graphics.BitmapFactory.decodeResource(r, R.drawable.drum_floor);
            snareBmp  = android.graphics.BitmapFactory.decodeResource(r, R.drawable.drum_snare);
            tomBmp    = android.graphics.BitmapFactory.decodeResource(r, R.drawable.drum_tom);
            percBmp   = android.graphics.BitmapFactory.decodeResource(r, R.drawable.drum_perc);
        }

        void setListener(PadListener l) {
            this.listener = l;
        }

        void setNoteAvailability(boolean[] a) {
            System.arraycopy(a, 0, noteAvail, 0, 128);
            invalidate();
        }

        void setEditListener(EditListener l) { this.editListener = l; }

        void setEditMode(boolean on) {
            editMode = on;
            if (!on) selected = null;
            invalidate();
        }

        // Serialised layout: "note,cx,cy,r,vol[,cat,soundCode,soundNote,nameEnc];..."
        // Fields 1-5 are the original format (older layouts still load); fields
        // 6-9 add the per-piece sound. nameEnc percent-escapes , ; and %.
        void setLayout(String s) {
            pieces.clear();
            if (s != null && !s.isEmpty()) {
                for (String tok : s.split(";")) {
                    if (tok.isEmpty()) continue;
                    String[] f = tok.split(",", -1);
                    if (f.length < 4) continue;
                    try {
                        Piece p = new Piece(Integer.parseInt(f[0]), Float.parseFloat(f[1]),
                                Float.parseFloat(f[2]), Float.parseFloat(f[3]));
                        if (f.length >= 5 && !f[4].isEmpty()) p.vol = Float.parseFloat(f[4]);
                        if (f.length >= 8) {
                            p.cat = Integer.parseInt(f[5]);
                            p.soundCode = Integer.parseInt(f[6]);
                            p.soundNote = Integer.parseInt(f[7]);
                        }
                        if (f.length >= 9) p.name = dec(f[8]);
                        pieces.add(p);
                    } catch (NumberFormatException ignored) { }
                }
            }
            if (pieces.isEmpty()) buildDefault();
            selected = null;
            invalidate();
        }

        private static String enc(String s) {
            if (s == null || s.isEmpty()) return "";
            return s.replace("%","%25").replace(",","%2C").replace(";","%3B");
        }
        private static String dec(String s) {
            return s.replace("%3B",";").replace("%2C",",").replace("%25","%");
        }

        private void buildDefault() {
            pieces.clear();
            for (int i = 0; i < CAT_NOTES.length; i++) {
                pieces.add(new Piece(CAT_NOTES[i], POS[i][0], POS[i][1], POS[i][2]));
            }
            // Seed the z-order back-to-front by vertical position (nearer = on top).
            java.util.Collections.sort(pieces, (a, b) -> Float.compare(a.cy, b.cy));
        }

        // z-order = list order; the ↑/↓ handles step the selected piece one place.
        private void bringForward(Piece p) {
            int i = pieces.indexOf(p);
            if (i >= 0 && i < pieces.size() - 1) {
                pieces.remove(i); pieces.add(i + 1, p);
                persist(); invalidate();
            }
        }
        private void sendBack(Piece p) {
            int i = pieces.indexOf(p);
            if (i > 0) {
                pieces.remove(i); pieces.add(i - 1, p);
                persist(); invalidate();
            }
        }

        private void persist() {
            if (editListener == null) return;
            editListener.onLayoutChanged(layoutString());
        }

        // The live arrangement in the same format setLayout reads — used by
        // persist and by the layout export.
        String layoutString() {
            StringBuilder sb = new StringBuilder();
            for (Piece p : pieces) {
                sb.append(p.note).append(',').append(fmt(p.cx)).append(',')
                        .append(fmt(p.cy)).append(',').append(fmt(p.r)).append(',')
                        .append(fmt(p.vol)).append(',')
                        .append(p.cat).append(',').append(p.soundCode).append(',')
                        .append(p.soundNote).append(',').append(enc(p.name)).append(';');
            }
            return sb.toString();
        }

        private static String fmt(float v) { return String.format(java.util.Locale.US, "%.4f", v); }

        boolean needsPieceRouting() {
            // Categorised pieces were added or edited. They still need the
            // custom routing table when their sound is Default because their
            // unique trigger note may not exist in the selected SoundFont.
            for (Piece p : pieces) if (p.soundCode >= 0 || p.cat >= 0) return true;
            return false;
        }

        // Pencil handle on a selected piece → host opens the "Change piece" sheet.
        private Runnable pieceEditListener;
        void setPieceEditListener(Runnable r) { pieceEditListener = r; }
        boolean hasSelection() { return selected != null; }
        int    selCat()       { return selected != null ? selected.cat : -1; }
        int    selNote()      { return selected != null ? selected.note : -1; }
        String selName()      { return selected != null ? selected.name : ""; }
        int    selSoundCode() { return selected != null ? selected.soundCode : -1; }
        int    selSoundNote() { return selected != null ? selected.soundNote : -1; }

        int swellForTrigger(int triggerNote) {
            for (Piece p : pieces) {
                if (p.note == triggerNote && isSwellSoundCode(p.soundCode)) {
                    return p.soundCode - KIT_SOUND_SWELL_BASE;
                }
            }
            return -1;
        }

        void markDirectWavAvailable(boolean[] available) {
            for (Piece p : pieces) {
                if (p.note >= 0 && p.note < available.length
                        && (p.cat == CAT_CHIMES || isSwellSoundCode(p.soundCode))) {
                    available[p.note] = true;
                }
            }
        }

        // Apply edited settings to the currently selected piece (keeps its
        // position, size, z-order and trigger note).
        void updateSelected(int cat, String name, int soundCode, int soundNote) {
            if (selected == null) return;
            selected.cat = cat;
            selected.name = name == null ? "" : name;
            selected.soundCode = soundCode;
            selected.soundNote = soundNote;
            persist();
            invalidate();
        }

        // Routing rows for the native custom kit, one per piece:
        // {triggerNote, soundCode(-1=inherit), soundNote, volPermille, isChimes, category}.
        int[][] pieceRouting() {
            int[][] out = new int[pieces.size()][];
            for (int i = 0; i < pieces.size(); i++) {
                Piece p = pieces.get(i);
                out[i] = new int[]{p.note, p.soundCode, p.soundNote,
                        Math.round(p.vol * 1000f), p.cat == CAT_CHIMES ? 1 : 0, p.cat};
            }
            return out;
        }

        // New add: a categorised piece with its own name + chosen sound.
        // soundCode < 0 inherits the selected kit; otherwise (code, srcNote)
        // picks an exact sample. The trigger note is unique so two pieces of the
        // same sound never collide in the native per-note tables.
        void addPiece(int cat, String name, int soundCode, int soundNote) {
            Piece p = new Piece(freeNote(cat), 0.5f, 0.45f, KCAT_R[cat]);
            p.cat = cat;
            p.name = name == null ? "" : name;
            p.soundCode = soundCode;
            p.soundNote = soundNote;
            pieces.add(p);
            selected = p;
            persist();
            invalidate();
        }

        // A MIDI note not already used as a trigger by any piece. Chimes always
        // trigger 84 (the WAV path in onDrumPad keys on it).
        private int freeNote(int cat) {
            if (cat == CAT_CHIMES) return 84;
            boolean[] used = new boolean[128];
            for (Piece p : pieces) if (p.note >= 0 && p.note < 128) used[p.note] = true;
            for (int n = 36; n < 100; n++) if (!used[n]) return n;
            for (int n = 0; n < 128; n++) if (!used[n]) return n;
            return 36;
        }

        private static float defR(int note) {
            for (int i = 0; i < CAT_NOTES.length; i++) if (CAT_NOTES[i] == note) return POS[i][2];
            return 0.14f;
        }
        // Display label: a piece's custom name wins; else its category name; else
        // the legacy note label.
        private String labelFor(Piece p) {
            if (p.name != null && !p.name.isEmpty()) return p.name;
            if (p.cat >= 0 && p.cat < KCAT_NAME.length) return KCAT_NAME[p.cat];
            for (int i = 0; i < CAT_NOTES.length; i++) if (CAT_NOTES[i] == p.note) return CAT_LABELS[i];
            return "";
        }
        private boolean available(int note) { return note >= 0 && note < 128 && noteAvail[note]; }

        // One photo per piece type; all cymbals + hats share the gold cymbal.
        private android.graphics.Bitmap imgForNote(int note) {
            switch (note) {
                case 36: return kickBmp;                    // kick
                case 43: return floorBmp;                   // floor tom
                case 38: case 37: return snareBmp;          // snare + rim
                case 48: case 47: case 45: return tomBmp;   // rack toms
                default:  return cymbalBmp;                 // cymbals + hi-hats
            }
        }
        private android.graphics.Bitmap imgFor(Piece p) {
            if (p.cat == 5 || p.cat == 6) return percBmp;   // Clap / Perc: the percussion drum
            return imgForNote(p.cat >= 0 ? KCAT_PNG[p.cat] : p.note);
        }

        // Kick/Floor/Snare carry a printed name already; the rest need a label.
        // A custom-named piece always shows its label.
        private boolean needsLabel(Piece p) {
            if (p.name != null && !p.name.isEmpty()) return true;
            if (p.cat == 5 || p.cat == 6) return true;   // percussion image is generic
            int key = p.cat >= 0 ? KCAT_PNG[p.cat] : p.note;
            return key != 36 && key != 43 && key != 38;
        }

        // Flat piece geometry. Tablets shrink a touch so neighbours keep a gap.
        private float scale() {
            float density = getResources().getDisplayMetrics().density;
            return Math.min(getWidth(), getHeight()) / density >= 600 ? 0.92f : 1.0f;
        }
        private float pxX(Piece p) { return p.cx * getWidth(); }
        private float pxY(Piece p) { return p.cy * getHeight(); }
        private float pxR(Piece p) { return p.r * getHeight() * scale(); }

        // Draw order = list order (back-to-front); the ↑/↓ handles reorder it,
        // and buildDefault seeds it by cy so the default kit still overlaps right.
        private void rebuildOrder() {
            order.clear();
            order.addAll(pieces);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            drawStudioBackground(canvas, w, h);
            rebuildOrder();
            boolean animating = false;
            for (Piece p : order) {
                float cx = pxX(p), cy = pxY(p), r = pxR(p);
                boolean en = editMode || available(p.note);   // edit mode shows all
                float f = en ? p.flash : 0f;
                if (f > 0f) { p.flash = Math.max(0f, f - 0.07f); animating = true; }
                float rr = r * (1f + f * 0.08f);   // a small punch on hit
                android.graphics.Bitmap bmp = imgFor(p);
                dst.set(cx - rr, cy - rr, cx + rr, cy + rr);
                imgPaint.setAlpha(en ? 255 : 64);   // ghost the unavailable
                if (bmp != null) canvas.drawBitmap(bmp, null, dst, imgPaint);
                if (f > 0f) {   // white pop clipped to the piece
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(Color.argb((int) (f * 95), 255, 255, 255));
                    canvas.drawCircle(cx, cy, rr * 0.92f, paint);
                }
                if (needsLabel(p)) drawLabel(canvas, labelFor(p), cx, cy + r * 0.60f, en);
            }
            if (editMode && selected != null) drawSelection(canvas, selected);
            if (animating) postInvalidateOnAnimation();
        }

        private void drawStudioBackground(Canvas canvas, float w, float h) {
            if (studioBg == null || studioBg.getWidth() <= 0 || studioBg.getHeight() <= 0) {
                canvas.drawColor(COLOR_BACKGROUND);
                return;
            }
            int bw = studioBg.getWidth();
            int bh = studioBg.getHeight();
            float targetRatio = w / h;
            float imageRatio = bw / (float) bh;
            if (imageRatio > targetRatio) {
                int cropW = Math.max(1, Math.round(bh * targetRatio));
                int left = (bw - cropW) / 2;
                studioSrc.set(left, 0, left + cropW, bh);
            } else {
                int cropH = Math.max(1, Math.round(bw / targetRatio));
                int top = (bh - cropH) / 2;
                studioSrc.set(0, top, bw, top + cropH);
            }
            studioDst.set(0f, 0f, w, h);
            imgPaint.setAlpha(255);
            canvas.drawBitmap(studioBg, studioSrc, studioDst, imgPaint);
            // A light cool wash keeps labels and selection handles readable
            // without hiding the real studio detail.
            paint.setColor(Color.argb(24, 220, 242, 253));
            canvas.drawRect(studioDst, paint);
        }

        // Accent box + resize handle (bottom-right) + remove X (top-right).
        private void drawSelection(Canvas canvas, Piece p) {
            float cx = pxX(p), cy = pxY(p), r = pxR(p);
            rect.set(cx - r, cy - r, cx + r, cy + r);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(EDIT_ACCENT);
            stroke.setStrokeWidth(dpPx(2));
            canvas.drawRoundRect(rect, dpPx(8), dpPx(8), stroke);
            float hr = dpPx(11), d = dpPx(4);
            // resize handle (bottom-right)
            float hx = cx + r, hy = cy + r;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(EDIT_ACCENT);
            canvas.drawCircle(hx, hy, hr, paint);
            stroke.setColor(Color.WHITE);
            canvas.drawLine(hx - d, hy, hx + d, hy, stroke);
            canvas.drawLine(hx, hy - d, hx, hy + d, stroke);
            // remove X (top-right)
            float xx = cx + r, xy = cy - r;
            paint.setColor(Color.rgb(220, 72, 72));
            canvas.drawCircle(xx, xy, hr, paint);
            canvas.drawLine(xx - d, xy - d, xx + d, xy + d, stroke);
            canvas.drawLine(xx - d, xy + d, xx + d, xy - d, stroke);
            // z-order: bring forward (top-left ↑) / send back (bottom-left ↓)
            paint.setColor(EDIT_ACCENT);
            float ux = cx - r, uy = cy - r;
            canvas.drawCircle(ux, uy, hr, paint);
            stroke.setColor(Color.WHITE);
            canvas.drawLine(ux, uy - d, ux, uy + d, stroke);
            canvas.drawLine(ux - d * 0.8f, uy - d * 0.2f, ux, uy - d, stroke);
            canvas.drawLine(ux + d * 0.8f, uy - d * 0.2f, ux, uy - d, stroke);
            float lx = cx - r, ly = cy + r;
            paint.setColor(EDIT_ACCENT);
            canvas.drawCircle(lx, ly, hr, paint);
            stroke.setColor(Color.WHITE);
            canvas.drawLine(lx, ly + d, lx, ly - d, stroke);
            canvas.drawLine(lx - d * 0.8f, ly + d * 0.2f, lx, ly + d, stroke);
            canvas.drawLine(lx + d * 0.8f, ly + d * 0.2f, lx, ly + d, stroke);
            // change piece (pencil, top-centre) → re-opens the piece settings
            float px = cx, py = cy - r;
            paint.setColor(EDIT_ACCENT);
            canvas.drawCircle(px, py, hr, paint);
            stroke.setColor(Color.WHITE);
            stroke.setStrokeWidth(dpPx(1.6f));
            canvas.drawLine(px - d, py + d, px + d, py - d, stroke);          // pencil body
            canvas.drawLine(px + d, py - d, px + d * 1.4f, py - d * 1.4f, stroke); // tip
            canvas.drawLine(px - d, py + d, px - d * 1.3f, py + d * 1.3f, stroke); // eraser
            stroke.setStrokeWidth(dpPx(2));
            drawVolume(canvas, p);
        }

        // Per-piece volume slider under (or above, near the bottom edge) the box.
        private float volSliderY(Piece p) {
            float below = pxY(p) + pxR(p) + dpPx(26);
            return below + dpPx(12) > getHeight() ? pxY(p) - pxR(p) - dpPx(26) : below;
        }
        private void setVolFromX(Piece p, float x) {
            float x0 = pxX(p) - pxR(p), x1 = pxX(p) + pxR(p);
            p.vol = Math.max(0f, Math.min(1.4f, (x - x0) / (x1 - x0 + 1e-3f) * 1.4f));
        }
        private void drawVolume(Canvas canvas, Piece p) {
            float x0 = pxX(p) - pxR(p), x1 = pxX(p) + pxR(p), ty = volSliderY(p);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dpPx(3));
            stroke.setColor(Color.argb(120, 150, 154, 162));
            canvas.drawLine(x0, ty, x1, ty, stroke);
            float kx = x0 + Math.min(1f, p.vol / 1.4f) * (x1 - x0);
            stroke.setColor(EDIT_ACCENT);
            canvas.drawLine(x0, ty, kx, ty, stroke);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(EDIT_ACCENT);
            canvas.drawCircle(kx, ty, dpPx(9), paint);
            textPaint.setTextSize(dpPx(9));
            textPaint.setColor(Color.rgb(206, 210, 218));
            canvas.drawText("VOL " + Math.round(p.vol * 100) + "%", (x0 + x1) / 2f, ty - dpPx(11), textPaint);
        }

        // Small dark pill + light text: reads over gold, black or white pads.
        private void drawLabel(Canvas canvas, String s, float cx, float baseline, boolean en) {
            textPaint.setTextSize(dpPx(10));
            float tw = textPaint.measureText(s);
            float px = dpPx(5);
            rect.set(cx - tw / 2 - px, baseline - dpPx(9), cx + tw / 2 + px, baseline + dpPx(3));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(en ? 165 : 90, 8, 10, 14));
            canvas.drawRoundRect(rect, dpPx(6), dpPx(6), paint);
            textPaint.setColor(en ? Color.rgb(232, 236, 244) : Color.argb(120, 200, 204, 212));
            canvas.drawText(s, cx, baseline, textPaint);
        }

        private float dpPx(float dp) {
            return dp * getResources().getDisplayMetrics().density;
        }

        // Alpha-precise hit: only OPAQUE pixels of the PNG count, so taps in a
        // piece's transparent corners fall through to whatever is behind (or to
        // nothing) — "the hit box is only within the png".
        private Piece alphaHit(float x, float y) {
            rebuildOrder();
            for (int i = order.size() - 1; i >= 0; i--) {   // front-most first
                Piece p = order.get(i);
                float cx = pxX(p), cy = pxY(p), r = pxR(p);
                if (x < cx - r || x > cx + r || y < cy - r || y > cy + r) continue;
                android.graphics.Bitmap bmp = imgFor(p);
                if (bmp == null) continue;
                int bx = (int) ((x - (cx - r)) / (2f * r) * bmp.getWidth());
                int by = (int) ((y - (cy - r)) / (2f * r) * bmp.getHeight());
                if (bx < 0 || by < 0 || bx >= bmp.getWidth() || by >= bmp.getHeight()) continue;
                if (Color.alpha(bmp.getPixel(bx, by)) > 40) return p;
            }
            return null;
        }

        private static float dist(float x1, float y1, float x2, float y2) {
            float dx = x1 - x2, dy = y1 - y2; return (float) Math.sqrt(dx * dx + dy * dy);
        }
        private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

        // ===== Edit mode: single-touch select / move / resize / remove =====
        private boolean handleEdit(MotionEvent event) {
            float x = event.getX(), y = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    if (selected != null) {
                        float cx = pxX(selected), cy = pxY(selected), r = pxR(selected), hr = dpPx(16);
                        if (dist(x, y, cx + r, cy - r) <= hr) {          // X = remove
                            pieces.remove(selected);
                            selected = null; dragMode = 0;
                            persist(); invalidate();
                            return true;
                        }
                        if (dist(x, y, cx, cy - r) <= hr) {              // ✎ = change piece
                            if (pieceEditListener != null) pieceEditListener.run();
                            return true;
                        }
                        if (dist(x, y, cx + r, cy + r) <= hr) { dragMode = 2; return true; }        // resize
                        if (dist(x, y, cx - r, cy - r) <= hr) { bringForward(selected); return true; } // ↑ front
                        if (dist(x, y, cx - r, cy + r) <= hr) { sendBack(selected); return true; }      // ↓ back
                        float vy = volSliderY(selected);   // volume slider track
                        if (Math.abs(y - vy) <= dpPx(20) && x >= cx - r - dpPx(14) && x <= cx + r + dpPx(14)) {
                            dragMode = 4; setVolFromX(selected, x); invalidate(); return true;
                        }
                    }
                    Piece p = alphaHit(x, y);
                    selected = p;
                    if (p != null) { dragMode = 1; dragDx = x - pxX(p); dragDy = y - pxY(p); }
                    else dragMode = 0;
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN:
                    // Second finger on a selected piece → pinch to resize.
                    if (selected != null && event.getPointerCount() >= 2) {
                        pinchStartDist = Math.max(1f, spacing(event));
                        pinchStartR = selected.r;
                        dragMode = 3;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (selected == null) return true;
                    if (dragMode == 3 && event.getPointerCount() >= 2) {   // pinch resize
                        float ratio = spacing(event) / pinchStartDist;
                        selected.r = Math.max(0.045f, Math.min(0.34f, pinchStartR * ratio));
                        invalidate();
                    } else if (dragMode == 1) {                            // move
                        selected.cx = clamp01((x - dragDx) / getWidth());
                        selected.cy = clamp01((y - dragDy) / getHeight());
                        invalidate();
                    } else if (dragMode == 2) {                            // resize (corner handle)
                        float d = dist(x, y, pxX(selected), pxY(selected));
                        float rFrac = d / 1.41421356f / (getHeight() * scale());
                        selected.r = Math.max(0.045f, Math.min(0.34f, rFrac));
                        invalidate();
                    } else if (dragMode == 4) {                            // volume slider
                        setVolFromX(selected, x);
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (dragMode == 3) { persist(); dragMode = 0; }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragMode != 0) persist();
                    dragMode = 0;
                    return true;
            }
            return true;
        }

        private static float spacing(MotionEvent e) {
            float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        // ===== Play mode: alpha-precise multi-touch strikes =====
        private final boolean[] kitPtrDown = new boolean[64];

        private void strike(int id, float x, float y) {
            Piece p = alphaHit(x, y);
            if (p != null && available(p.note)) {
                // Full-velocity strikes — no strike-height sensitivity (low
                // velocities sounded bad). The per-piece VOL trim still applies
                // (defaults to 100% = full).
                float vel = Math.min(1.0f, p.vol);
                p.flash = Math.max(0.5f, vel);
                if (listener != null) listener.onPad(p.note, vel);
                invalidate();
            }
        }

        private void strikeNewPointers(MotionEvent event) {
            for (int pi = 0; pi < event.getPointerCount(); pi++) {
                int id = event.getPointerId(pi);
                if (id < 0 || id >= kitPtrDown.length || kitPtrDown[id]) continue;
                kitPtrDown[id] = true;
                strike(id, event.getX(pi), event.getY(pi));
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (editMode) return handleEdit(event);
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
                    || action == MotionEvent.ACTION_MOVE) {
                if (action == MotionEvent.ACTION_DOWN) {
                    java.util.Arrays.fill(kitPtrDown, false);   // fresh gesture
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                strikeNewPointers(event);
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                int id = event.getPointerId(event.getActionIndex());
                if (id >= 0 && id < kitPtrDown.length) kitPtrDown[id] = false;
                if (action == MotionEvent.ACTION_CANCEL) java.util.Arrays.fill(kitPtrDown, false);
                return true;
            }
            return true;
        }
    }

    // Chord Mode board: the selected number of vertical strips, each a chord.
    // header shows the chord name (tap to change it); the bands below are the
    // chord's notes low->high, so a vertical drag strums and a tap plucks.
    // Nested interfaces are implicitly static, which a non-static inner class
    // cannot declare — so this lives at class level (same as PianoBoardListener).
    private interface ChordBoardListener {
        void onNote(int note, float vel, boolean down);
        int[] voicing(int slot);
        String name(int slot);
        void onPickChord(int slot);
    }

    private final class ChordBoardView extends View {
        private static final int MAX_IDS = 16;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int accent = COLOR_TEAL;
        private ChordBoardListener listener;
        // Which band each finger is on, and a refcount per note so overlapping
        // strums release cleanly.
        private final int[] ptrSlot = new int[MAX_IDS];
        private final int[] ptrBand = new int[MAX_IDS];
        private final int[][] ptrNotes = new int[MAX_IDS][];
        private final boolean[][] ptrStarted = new boolean[MAX_IDS][];
        private final int[] ptrGeneration = new int[MAX_IDS];
        private final int[] noteCount = new int[128];
        private final float[] bandFlash = new float[CHORD_SLOTS_MAX * CHORD_BANDS];
        private int playMode = CHORD_PLAY_BLOCK;
        private int strumDelayMs = 30;

        ChordBoardView(Context c) {
            super(c);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextAlign(Paint.Align.CENTER);
            java.util.Arrays.fill(ptrSlot, -1);
            java.util.Arrays.fill(ptrBand, -1);
        }

        void setListener(ChordBoardListener l) { listener = l; }
        void setAccent(int a) { accent = a; invalidate(); }
        void setPlayMode(int mode) {
            playMode = Math.max(CHORD_PLAY_BLOCK,
                    Math.min(CHORD_PLAY_LIVELY, mode));
        }
        void setStrumDelayMs(int delayMs) {
            strumDelayMs = Math.max(1, Math.min(1000, delayMs));
        }
        void setChords(int[] roots, int[] types) { invalidate(); }

        private float dpPx(float dp) {
            return dp * getResources().getDisplayMetrics().density;
        }

        private float headerH() { return Math.min(dpPx(46), getHeight() * 0.16f); }
        private float colW() { return (float) getWidth() / chordSlotCount; }
        private float bandH() { return (getHeight() - headerH()) / (float) CHORD_BANDS; }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0 || listener == null) return;
            float cw = colW(), hh = headerH(), bh = bandH();
            float pad = dpPx(3);
            boolean animating = false;
            for (int s = 0; s < chordSlotCount; s++) {
                float x0 = s * cw;
                // header: chord name (tap to change)
                rect.set(x0 + pad, pad, x0 + cw - pad, hh - pad);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(COLOR_SKY_CONTROL);
                canvas.drawRoundRect(rect, dpPx(7), dpPx(7), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dpPx(1.5f));
                paint.setColor(accent);
                canvas.drawRoundRect(rect, dpPx(7), dpPx(7), paint);
                textPaint.setColor(COLOR_TEXT);
                textPaint.setTextSize(Math.min(dpPx(17), cw * 0.32f));
                canvas.drawText(listener.name(s), x0 + cw / 2f,
                        hh / 2f + dpPx(6), textPaint);
                // bands: the chord's notes, low at the bottom
                for (int b = 0; b < CHORD_BANDS; b++) {
                    float y0 = hh + b * bh;
                    rect.set(x0 + pad, y0 + pad * 0.6f, x0 + cw - pad, y0 + bh - pad * 0.6f);
                    int flashIndex = s * CHORD_BANDS + b;
                    boolean active = bandActive(s, b);
                    float f = active ? 1f : bandFlash[flashIndex];
                    if (!active && f > 0f) {
                        bandFlash[flashIndex] = Math.max(0f, f - 0.08f);
                        animating = true;
                    }
                    // Band 0 is the full-chord band — tinted so it reads apart
                    // from the single-note bands below it.
                    int base = b == 0 ? Color.rgb(206, 214, 228) : Color.rgb(232, 236, 244);
                    paint.setStyle(Paint.Style.FILL);
                    if (f > 0f) {
                        int light = blend(base, Color.WHITE, 0.55f * f);
                        int strong = blend(base, accent, 0.88f * f);
                        paint.setShader(new LinearGradient(rect.left, rect.top,
                                rect.right, rect.bottom, light, strong,
                                Shader.TileMode.CLAMP));
                    } else {
                        paint.setShader(null);
                        paint.setColor(base);
                    }
                    canvas.drawRoundRect(rect, dpPx(4), dpPx(4), paint);
                    paint.setShader(null);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dpPx(1));
                    paint.setColor(Color.argb(60, 90, 96, 108));
                    canvas.drawRoundRect(rect, dpPx(4), dpPx(4), paint);
                }
            }
            if (animating) postInvalidateOnAnimation();
        }

        private boolean bandActive(int slot, int band) {
            for (int id = 0; id < MAX_IDS; id++) {
                if (ptrSlot[id] != slot || ptrNotes[id] == null) continue;
                if (ptrBand[id] == 0 || ptrBand[id] == band) return true;
            }
            return false;
        }

        private int blend(int a, int b, float t) {
            t = Math.max(0f, Math.min(1f, t));
            return Color.rgb(
                    Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                    Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                    Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
        }

        private int slotAt(float x) {
            int s = (int) (x / colW());
            return s < 0 ? 0 : (s >= chordSlotCount ? chordSlotCount - 1 : s);
        }

        // Band index from y, or -1 when the finger is on the header.
        private int bandAt(float y) {
            if (y < headerH()) return -1;
            int b = (int) ((y - headerH()) / bandH());
            return b < 0 ? 0 : (b >= CHORD_BANDS ? CHORD_BANDS - 1 : b);
        }

        // Band 0 = the whole chord. Bands 1..6 = one note each, lowest just
        // under the chord band (band 1) descending in pitch order to the
        // highest at the bottom (band 6), so a downward strum rises.
        private int[] notesFor(int slot, int band) {
            int[] v = listener.voicing(slot);
            if (band <= 0) return v;                       // full chord
            int idx = band - 1;
            return new int[]{v[Math.max(0, Math.min(v.length - 1, idx))]};
        }

        private void press(int id, int slot, int band) {
            if (id < 0 || id >= MAX_IDS || listener == null) return;
            release(id);
            int[] notes = notesFor(slot, band);
            int generation = ++ptrGeneration[id];
            ptrSlot[id] = slot;
            ptrBand[id] = band;
            ptrNotes[id] = notes;
            ptrStarted[id] = new boolean[notes.length];
            if (band == 0) {
                for (int b = 0; b < CHORD_BANDS; b++) {
                    bandFlash[slot * CHORD_BANDS + b] = 1f;
                }
            } else {
                bandFlash[slot * CHORD_BANDS + band] = 1f;
            }
            int[] order = new int[notes.length];
            for (int i = 0; i < order.length; i++) order[i] = i;
            if (band == 0 && playMode == CHORD_PLAY_REVERSE) {
                for (int i = 0; i < order.length; i++) order[i] = order.length - 1 - i;
            } else if (band == 0 && playMode == CHORD_PLAY_ARPEGGIO) {
                int[] pattern = {0, 2, 1, 3, 5, 4};
                for (int i = 0; i < order.length; i++) {
                    order[i] = pattern[i % pattern.length] % notes.length;
                }
            } else if (band == 0 && playMode == CHORD_PLAY_FUNKY) {
                int[] pattern = {0, 3, 1, 4, 2, 5};
                for (int i = 0; i < order.length; i++) {
                    order[i] = pattern[i % pattern.length] % notes.length;
                }
            } else if (band == 0 && playMode == CHORD_PLAY_LIVELY) {
                int[] pattern = {0, 2, 4, 1, 3, 5};
                for (int i = 0; i < order.length; i++) {
                    order[i] = pattern[i % pattern.length] % notes.length;
                }
            }
            final long humanSeed = System.nanoTime() ^ ((long) slot << 20);
            for (int position = 0; position < order.length; position++) {
                final int sequencePosition = position;
                final int noteIndex = order[position];
                final float noteVelocity = chordPlayVelocity(
                        noteIndex, notes.length, humanSeed);
                Runnable start = () -> {
                    if (ptrGeneration[id] != generation || ptrNotes[id] != notes
                            || ptrStarted[id] == null || ptrStarted[id][noteIndex]) return;
                    int n = notes[noteIndex];
                    if (n < 0 || n > 127) return;
                    ptrStarted[id][noteIndex] = true;
                    noteCount[n]++;
                    listener.onNote(n, noteVelocity, true);
                    if (band == 0 && noteIndex < CHORD_SINGLES) {
                        bandFlash[slot * CHORD_BANDS + noteIndex + 1] = 1f;
                        invalidate();
                    }
                };
                long delay = band == 0
                        ? chordPlayDelay(sequencePosition, humanSeed) : 0L;
                if (delay > 0L) {
                    postDelayed(start, delay);
                } else {
                    start.run();
                }
            }
            invalidate();
        }

        private long chordPlayDelay(int position, long seed) {
            switch (playMode) {
                case CHORD_PLAY_STUDIO:
                    return position == 0 ? 0L
                            : position * 2L + Math.abs((seed >> (position * 5)) % 4L);
                case CHORD_PLAY_ROLLED:
                case CHORD_PLAY_REVERSE:
                case CHORD_PLAY_ARPEGGIO:
                case CHORD_PLAY_STRUM:
                    return position * (long) strumDelayMs;
                case CHORD_PLAY_BALLAD:
                    return position == 0 ? 0L
                            : Math.max(18L, strumDelayMs * 2L)
                            + (position - 1L) * Math.max(3L, strumDelayMs / 4L);
                case CHORD_PLAY_JOYOUS:
                    return position == 0 ? 0L
                            : position * Math.max(4L, strumDelayMs / 3L);
                case CHORD_PLAY_FUNKY:
                    return position * Math.max(7L, strumDelayMs / 2L)
                            + (position >= 3 ? Math.max(4L, strumDelayMs / 3L) : 0L);
                case CHORD_PLAY_LIVELY:
                    return position * Math.max(3L, strumDelayMs / 4L);
                default:
                    return 0L;
            }
        }

        private float chordPlayVelocity(int noteIndex, int count, long seed) {
            float position = count <= 1 ? 0f : noteIndex / (float) (count - 1);
            float velocity;
            switch (playMode) {
                case CHORD_PLAY_STUDIO:
                    velocity = noteIndex == count - 1 ? 0.98f
                            : noteIndex == 0 ? 0.94f : 0.84f;
                    velocity += (((seed >> (noteIndex * 4)) & 7L) - 3L) * 0.008f;
                    break;
                case CHORD_PLAY_ROLLED:
                case CHORD_PLAY_STRUM:
                    velocity = 0.98f - position * 0.16f;
                    break;
                case CHORD_PLAY_REVERSE:
                    velocity = 0.82f + position * 0.16f;
                    break;
                case CHORD_PLAY_BALLAD:
                    velocity = noteIndex == 0 ? 1.0f
                            : noteIndex == count - 1 ? 0.96f : 0.82f;
                    break;
                case CHORD_PLAY_ARPEGGIO:
                    velocity = noteIndex == 0 || noteIndex == count - 1 ? 0.96f : 0.86f;
                    break;
                case CHORD_PLAY_JOYOUS:
                    velocity = 0.86f + position * 0.12f;
                    if (noteIndex == count - 1) velocity = 1.0f;
                    break;
                case CHORD_PLAY_FUNKY:
                    velocity = noteIndex == 0 ? 1.0f
                            : noteIndex == count - 1 ? 0.95f
                            : (noteIndex % 2 == 0 ? 0.78f : 0.90f);
                    break;
                case CHORD_PLAY_LIVELY:
                    velocity = 0.84f + (noteIndex % 3) * 0.06f;
                    if (noteIndex == count - 1) velocity = 0.98f;
                    break;
                default:
                    velocity = 0.92f;
            }
            return Math.max(0.55f, Math.min(1.0f, velocity));
        }

        private void release(int id) {
            if (id < 0 || id >= MAX_IDS) return;
            int[] notes = ptrNotes[id];
            boolean[] started = ptrStarted[id];
            ++ptrGeneration[id];   // cancel any pending 30 ms strum callbacks
            ptrNotes[id] = null;
            ptrStarted[id] = null;
            ptrSlot[id] = -1;
            ptrBand[id] = -1;
            if (notes == null) return;
            for (int i = 0; i < notes.length; i++) {
                if (started != null && (i >= started.length || !started[i])) continue;
                int n = notes[i];
                if (n < 0 || n > 127) continue;
                if (noteCount[n] > 0 && --noteCount[n] == 0 && listener != null) {
                    listener.onNote(n, 0f, false);
                }
            }
            invalidate();
        }

        void releaseAll() {
            for (int i = 0; i < MAX_IDS; i++) release(i);
            java.util.Arrays.fill(noteCount, 0);
        }

        @Override protected void onDetachedFromWindow() {
            releaseAll();
            super.onDetachedFromWindow();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    getParent().requestDisallowInterceptTouchEvent(true);
                    int pi = e.getActionIndex();
                    int id = e.getPointerId(pi);
                    int slot = slotAt(e.getX(pi));
                    int band = bandAt(e.getY(pi));
                    if (band < 0) {          // header tap -> change this chord
                        if (listener != null) listener.onPickChord(slot);
                        return true;
                    }
                    press(id, slot, band);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    // Strum, but only across the single-note bands (1..6) and
                    // only inside the column the finger started in: sliding can
                    // never spill into a neighbouring chord, and the full-chord
                    // band 0 stays tap/hold only.
                    for (int pi = 0; pi < e.getPointerCount(); pi++) {
                        int id = e.getPointerId(pi);
                        if (id < 0 || id >= MAX_IDS) continue;
                        int from = ptrBand[id];
                        if (from <= 0) continue;            // started on chord band/header
                        int slot = ptrSlot[id];             // locked to the origin column
                        if (slotAt(e.getX(pi)) != slot) continue;
                        int band = bandAt(e.getY(pi));
                        if (band <= 0 || band == from) continue;
                        release(id);
                        press(id, slot, band);
                    }
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    release(e.getPointerId(e.getActionIndex()));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    releaseAll();
                    return true;
            }
            return true;
        }
    }

    // Small indeterminate "shine" bar: a bright accent segment sweeps across a
    // dim track. Flat fills only (no gradient/alpha) to stay glitch-free on the
    // device. Shown while a kit's soundfont loads, hidden the moment it's ready.
    private static final class ShineBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int accent = Color.rgb(45, 178, 168);
        private final long start = SystemClock.uptimeMillis();

        ShineBar(Context c) { super(c); }

        void setAccent(int a) { accent = a; invalidate(); }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = h * 0.5f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(COLOR_SKY_TRACK);
            canvas.drawRoundRect(0, 0, w, h, r, r, paint);
            float segW = w * 0.32f;
            float t = ((SystemClock.uptimeMillis() - start) % 1100L) / 1100f;
            float x = -segW + t * (w + segW);
            float left = Math.max(0f, x);
            float right = Math.min(w, x + segW);
            if (right > left) {
                paint.setColor(accent);
                canvas.drawRoundRect(left, 0, right, h, r, r, paint);
            }
            postInvalidateOnAnimation();
        }
    }

    private static final class DrumPadsView extends View {
        interface PadListener {
            void onPad(int note, float velocity);
        }

        // 3×3 grid. Pad 0 = smart hi-hat (open/closed by pedal), pad 3 = foot pedal (hold),
        // pad 6 = snare with a corner switch between Snare and Rim Shot.
        private static final int[] FULL_NOTES = {46, 49, 51, 44, 50, 47, 38, 36, 43};
        private static final String[] FULL_LABELS = {
                "Hi-Hat", "Crash", "Ride", "Pedal", "Tom 1", "Tom 2", "Snare", "Kick", "Tom 3"};
        // Compact 2×2 kit for the Loop Mix screen: just the core groove pieces.
        private static final int[] COMPACT_NOTES = {42, 51, 38, 36};
        private static final String[] COMPACT_LABELS = {"Cl Hat", "Ride", "Snare", "Kick"};
        private boolean compact = false;

        void setCompact(boolean c) {
            compact = c;
            invalidate();
        }

        // Loop Mix pads hit at full strength every time; only the Drums
        // screen keeps the tap-position intensity.
        private boolean fullVelocity = false;

        void setFullVelocity(boolean f) {
            fullVelocity = f;
        }

        private int[] notes() { return compact ? COMPACT_NOTES : FULL_NOTES; }
        private String[] labels() { return compact ? COMPACT_LABELS : FULL_LABELS; }
        private int cols() { return compact ? 2 : 3; }
        private int rows() { return compact ? 2 : 3; }
        private int hihatPad() { return compact ? -1 : 0; }
        private int pedalPad() { return compact ? -1 : 3; }
        private int snarePad() { return compact ? -1 : 6; }
        private static final int NOTE_CLOSED = 42, NOTE_PEDAL = 44, NOTE_OPEN = 46;
        // Rim = GM side stick (37): the distinct cross-stick "rim" sound. Note 40 is
        // just "Electric Snare" on these kits, which sounds like the snare.
        private static final int NOTE_SNARE = 38, NOTE_RIM = 37;
        // MIDI velocity cymbals: a hard ride crashes (Crash 2), a soft crash
        // rides (Ride 2, a unique 2nd ride). These notes light their origin pad.
        private static final int NOTE_RIDE = 51, NOTE_CRASH2 = 57;
        private static final int NOTE_CRASH1 = 49, NOTE_RIDE2 = 59;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float[] flash = new float[FULL_NOTES.length];
        private int accent = Color.rgb(45, 178, 168);
        private PadListener listener;
        // Foot pedal: while a finger holds the Pedal pad, the hi-hat plays closed.
        private boolean pedalDown = false;
        private int pedalPointerId = -1;
        // Snare pad articulation: false = Snare (38), true = Rim Shot (40).
        private boolean snareIsRim = false;
        // Which GM notes the current kit actually voices; pads with no sound are
        // greyed out and can't be tapped. Defaults to all-available.
        private final boolean[] noteAvail = new boolean[128];

        DrumPadsView(Context context) {
            super(context);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextAlign(Paint.Align.CENTER);
            java.util.Arrays.fill(noteAvail, true);
        }

        void setNoteAvailability(boolean[] a) {
            System.arraycopy(a, 0, noteAvail, 0, 128);
            invalidate();
        }

        // A pad is enabled if any note it can fire has a sound in this kit.
        private boolean padEnabled(int i) {
            if (i == pedalPad()) return noteAvail[NOTE_PEDAL] || noteAvail[NOTE_CLOSED] || noteAvail[NOTE_OPEN];
            if (i == hihatPad()) return noteAvail[NOTE_OPEN] || noteAvail[NOTE_CLOSED];
            if (i == snarePad()) return noteAvail[NOTE_SNARE] || noteAvail[NOTE_RIM];
            return noteAvail[notes()[i]];
        }

        void setListener(PadListener l) {
            this.listener = l;
        }

        // Long-press on a cymbal pad opens its volume slider (group 0 = hat,
        // 1 = ride, 2 = crash). -1 = not a cymbal (no hold action).
        interface HoldListener { void onCymbalHold(int group); }
        private HoldListener holdListener;
        private int holdPad = -1;
        private float holdX, holdY;
        private final Runnable holdRun = () -> {
            if (holdPad < 0) return;
            int g = cymbalGroup(notes()[holdPad]);
            holdPad = -1;
            if (g >= 0 && holdListener != null) holdListener.onCymbalHold(g);
        };

        void setHoldListener(HoldListener l) {
            this.holdListener = l;
        }

        // Cymbal choke: a tap on the EDGE of a ride/crash pad grabs the cymbal
        // (stops it ringing) instead of striking it.
        private Runnable chokeListener;

        void setChokeListener(Runnable r) {
            this.chokeListener = r;
        }

        // True if the tap sits in the outer ~22% border of pad p's cell.
        private boolean isEdgeTap(int p, float x, float y) {
            float cell = cellSize();
            if (cell <= 0) return false;
            float lx = (x - gridLeft() - (p % cols()) * cell) / cell;
            float ly = (y - gridTop() - (p / cols()) * cell) / cell;
            return lx < 0.22f || lx > 0.78f || ly < 0.22f || ly > 0.78f;
        }

        private static int cymbalGroup(int note) {
            switch (note) {
                case 42: case 44: case 46: return 0;                       // hats
                case 51: case 59: return 1;                                // rides
                case 49: case 52: case 53: case 55: case 57: return 2;     // crashes
                default: return -1;
            }
        }

        private void cancelHold() {
            holdPad = -1;
            removeCallbacks(holdRun);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(holdRun);
        }

        void setSnareRim(boolean rim) {
            snareIsRim = rim;
            invalidate();
        }

        boolean isSnareRim() {
            return snareIsRim;
        }

        void setAccent(int a) {
            this.accent = a;
            invalidate();
        }

        void flashNote(int note) {
            // Rim shot (37) sounds from the snare pad, which is registered
            // under note 38 — map it so MIDI rim hits light the pad too.
            if (note == NOTE_RIM) note = NOTE_SNARE;
            // A hard ride hit fires Crash 2 (57) but is still the ride pad.
            if (note == NOTE_CRASH2) note = NOTE_RIDE;
            // A soft crash hit fires Ride 2 (59) but is still the crash pad.
            if (note == NOTE_RIDE2) note = NOTE_CRASH1;
            for (int i = 0; i < notes().length; i++) {
                if (notes()[i] == note) {
                    flash[i] = 1.0f;
                    invalidate();
                }
            }
        }

        // Which pointer ids have already struck a pad. Lets MOVE events strike
        // fingers whose POINTER_DOWN the system swallowed (multi-finger
        // gesture detection eats simultaneous landings).
        private final boolean[] ptrDown = new boolean[64];

        private void strikeNewPointers(MotionEvent event) {
            for (int pi = 0; pi < event.getPointerCount(); pi++) {
                int id = event.getPointerId(pi);
                if (id < 0 || id >= ptrDown.length || ptrDown[id]) continue;
                ptrDown[id] = true;
                onDown(id, event.getX(pi), event.getY(pi));
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    java.util.Arrays.fill(ptrDown, false);   // fresh gesture
                    // Arm a long-press on a cymbal pad → its volume slider.
                    // A second finger, a move, or a quick lift cancels it.
                    holdX = event.getX();
                    holdY = event.getY();
                    holdPad = padAt(holdX, holdY);
                    if (holdPad >= 0 && cymbalGroup(notes()[holdPad]) >= 0) {
                        // 5 s deliberate hold — long enough that it never opens
                        // the volume dialog by accident while drumming.
                        postDelayed(holdRun, 5000);
                    } else {
                        holdPad = -1;
                    }
                    // fall through
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (action == MotionEvent.ACTION_POINTER_DOWN) cancelHold();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    strikeNewPointers(event);
                    break;
                case MotionEvent.ACTION_MOVE:
                    float slop = getResources().getDisplayMetrics().density * 18f;
                    if (holdPad >= 0 && (Math.abs(event.getX() - holdX) > slop
                            || Math.abs(event.getY() - holdY) > slop)) {
                        cancelHold();
                    }
                    strikeNewPointers(event);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    cancelHold();
                    int pi = event.getActionIndex();
                    int id = event.getPointerId(pi);
                    if (id >= 0 && id < ptrDown.length) ptrDown[id] = false;
                    onUp(id);
                    break;
                }
                case MotionEvent.ACTION_CANCEL:
                    cancelHold();
                    java.util.Arrays.fill(ptrDown, false);
                    if (pedalPointerId != -1) {
                        pedalDown = false;
                        pedalPointerId = -1;
                        invalidate();
                    }
                    break;
            }
            return true;
        }

        private void onDown(int pointerId, float x, float y) {
            int p = padAt(x, y);
            if (p < 0) {
                return;
            }
            if (!padEnabled(p)) {
                return;   // pad has no sound in this kit — untappable
            }
            float vel = velAt(p, y);
            if (p == pedalPad()) {
                // Press = pedal down: closes the hat. Fire the foot "chick" (44),
                // which the engine uses to choke any ringing open hi-hat.
                pedalDown = true;
                pedalPointerId = pointerId;
                flash[p] = Math.max(0.55f, vel);
                if (listener != null) {
                    listener.onPad(NOTE_PEDAL, vel);
                }
                invalidate();
                return;
            }
            int note = notes()[p];
            // Edge tap on a ride/crash pad = choke: grab the ringing cymbal to
            // stop it, instead of striking. (Hats choke via the pedal already.)
            if (cymbalGroup(notes()[p]) >= 1 && isEdgeTap(p, x, y)) {
                if (chokeListener != null) chokeListener.run();
                flash[p] = 0.4f;   // brief dim tick as feedback
                invalidate();
                return;
            }
            if (p == hihatPad()) {
                note = pedalDown ? NOTE_CLOSED : NOTE_OPEN;
            } else if (p == snarePad()) {
                note = snareIsRim ? NOTE_RIM : NOTE_SNARE;
            }
            // Velocity cymbals (ride↔crash) are MIDI-only: on-screen "velocity"
            // is just tap position, not strike force, so the pads play straight.
            flash[p] = vel;
            if (listener != null) {
                listener.onPad(note, vel);
            }
            invalidate();
        }

        private void onUp(int pointerId) {
            if (pointerId == pedalPointerId) {
                // Release = pedal up: the hat opens again (no sound on release).
                pedalDown = false;
                pedalPointerId = -1;
                invalidate();
            }
        }

        // Pads are always SQUARE: the grid uses the largest square cell that
        // fits and sits centered, whatever shape the host gives the view.
        private float cellSize() {
            return Math.min(getWidth() / (float) cols(), getHeight() / (float) rows());
        }

        private float gridLeft() {
            return (getWidth() - cellSize() * cols()) * 0.5f;
        }

        private float gridTop() {
            return (getHeight() - cellSize() * rows()) * 0.5f;
        }

        private int padAt(float x, float y) {
            if (getWidth() <= 0 || getHeight() <= 0) {
                return -1;
            }
            float cell = cellSize();
            int col = Math.max(0, Math.min(cols() - 1, (int) ((x - gridLeft()) / cell)));
            int row = Math.max(0, Math.min(rows() - 1, (int) ((y - gridTop()) / cell)));
            return row * cols() + col;
        }

        private float velAt(int p, float y) {
            if (fullVelocity) {
                return 1.0f;
            }
            float cell = cellSize();
            float frac = Math.max(0f, Math.min(1f,
                    (y - gridTop() - (p / cols()) * cell) / cell));
            return 0.45f + 0.55f * frac;
        }

        private int blend(int a, int b, float t) {
            return Color.rgb(
                    Math.round(Color.red(a) * (1 - t) + Color.red(b) * t),
                    Math.round(Color.green(a) * (1 - t) + Color.green(b) * t),
                    Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
        }

        // --- pad icons: same visual language as the landscape kit view ---

        // Side-view drum: white head over a colored shell with a metal hoop.
        private void drawIconDrumSide(Canvas c, float cx, float cy, float hw, float hh, int shell, float d) {
            float topY = cy - hh * 0.5f;
            float botY = cy + hh * 0.7f;
            float ry = hh * 0.32f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(shell);
            rect.set(cx - hw, topY, cx + hw, botY);
            c.drawRect(rect, paint);
            rect.set(cx - hw, botY - ry, cx + hw, botY + ry);
            c.drawOval(rect, paint);
            paint.setColor(Color.rgb(237, 232, 220));
            rect.set(cx - hw, topY - ry, cx + hw, topY + ry);
            c.drawOval(rect, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * d);
            paint.setColor(Color.rgb(139, 147, 164));
            c.drawOval(rect, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawIconTom(Canvas c, float cx, float cy, float s, float d) {
            drawIconDrumSide(c, cx, cy, s * 1.05f, s * 0.85f, Color.rgb(96, 44, 50), d);
        }

        private void drawIconSnare(Canvas c, float cx, float cy, float s, float d, boolean rim) {
            drawIconDrumSide(c, cx, cy, s * 1.15f, s * 0.7f, Color.rgb(70, 76, 88), d);
            // Snare wires along the bottom edge.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f * d);
            paint.setColor(Color.argb(170, 200, 206, 218));
            c.drawLine(cx - s * 0.7f, cy + s * 0.62f, cx + s * 0.7f, cy + s * 0.62f, paint);
            if (rim) {
                // Rim shot: a stick laid across the hoop.
                paint.setStrokeWidth(2.2f * d);
                paint.setColor(Color.rgb(214, 180, 130));
                c.drawLine(cx - s * 1.3f, cy + s * 0.15f, cx + s * 1.15f, cy - s * 1.0f, paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(cx + s * 1.15f, cy - s * 1.0f, 2.2f * d, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        // Kick seen from the front: dark head, maroon hoop, port hole.
        private void drawIconKick(Canvas c, float cx, float cy, float r, float d) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(COLOR_SKY_KEY_DARK);
            c.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f * d);
            paint.setColor(Color.rgb(96, 44, 50));
            c.drawCircle(cx, cy, r, paint);
            paint.setStrokeWidth(1.0f * d);
            paint.setColor(Color.argb(120, 139, 147, 164));
            c.drawCircle(cx, cy, r * 0.7f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(COLOR_SKY_TRACK);
            c.drawCircle(cx + r * 0.4f, cy + r * 0.32f, r * 0.15f, paint);
        }

        // Gold cymbal plate; the ride gets a bigger bell. Crash tilts a little.
        private void drawIconCymbal(Canvas c, float cx, float cy, float s, float d, boolean ride) {
            float rx = s * 1.35f, ry = s * 0.45f;
            c.save();
            if (!ride) c.rotate(-9f, cx, cy);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ride ? Color.rgb(206, 172, 96) : Color.rgb(214, 180, 105));
            rect.set(cx - rx, cy - ry, cx + rx, cy + ry);
            c.drawOval(rect, paint);
            paint.setColor(Color.rgb(239, 215, 154));
            float bs = ride ? 0.30f : 0.17f;
            rect.set(cx - rx * bs, cy - ry * (bs + 0.18f), cx + rx * bs, cy + ry * (bs + 0.18f));
            c.drawOval(rect, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f * d);
            paint.setColor(Color.rgb(122, 96, 40));
            rect.set(cx - rx, cy - ry, cx + rx, cy + ry);
            c.drawOval(rect, paint);
            paint.setStyle(Paint.Style.FILL);
            c.restore();
        }

        // Two hat plates on a stand; the gap closes while the pedal is held.
        private void drawIconHiHat(Canvas c, float cx, float cy, float s, float d, boolean closed) {
            float gap = closed ? s * 0.10f : s * 0.34f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f * d);
            paint.setColor(Color.rgb(94, 103, 119));
            c.drawLine(cx, cy - s * 0.95f, cx, cy + s * 0.95f, paint);
            paint.setStyle(Paint.Style.FILL);
            float rx = s * 1.25f, ry = s * 0.36f;
            for (int k = 1; k >= 0; k--) {
                float py = k == 0 ? cy - gap / 2 : cy + gap / 2;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.rgb(214, 180, 105));
                rect.set(cx - rx, py - ry, cx + rx, py + ry);
                c.drawOval(rect, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.2f * d);
                paint.setColor(Color.rgb(122, 96, 40));
                c.drawOval(rect, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawIconPedal(Canvas c, float cx, float cy, float s, float d) {
            float hw = s * 0.55f, hh = s * 0.95f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(pedalDown ? Color.rgb(72, 92, 88) : Color.rgb(56, 62, 74));
            rect.set(cx - hw, cy - hh, cx + hw, cy + hh);
            c.drawRoundRect(rect, 3 * d, 3 * d, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * d);
            paint.setColor(pedalDown ? accent : Color.rgb(94, 103, 119));
            c.drawRoundRect(rect, 3 * d, 3 * d, paint);
            paint.setStrokeWidth(1.2f * d);
            c.drawLine(cx - hw * 0.6f, cy + hh * 0.5f, cx + hw * 0.6f, cy + hh * 0.5f, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float cell = cellSize();
            float gl = gridLeft(), gt = gridTop();
            float gap = 4 * d;
            boolean animating = false;
            for (int i = 0; i < notes().length; i++) {
                int row = i / cols();
                int col = i % cols();
                rect.set(gl + col * cell + gap, gt + row * cell + gap,
                        gl + (col + 1) * cell - gap, gt + (row + 1) * cell - gap);
                if (!padEnabled(i)) {
                    // Unavailable pad: quiet cloudy blue, visibly disabled.
                    paint.setColor(Color.rgb(218, 234, 243));
                    canvas.drawRoundRect(rect, 16 * d, 16 * d, paint);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1.0f * d);
                    paint.setColor(Color.rgb(184, 210, 224));
                    canvas.drawRoundRect(rect, 16 * d, 16 * d, paint);
                    paint.setStyle(Paint.Style.FILL);
                    continue;
                }
                boolean pedalHeld = (i == pedalPad() && pedalDown);
                float f = pedalHeld ? 1.0f : flash[i];
                int base = Color.rgb(207, 239, 253);
                paint.setColor(f > 0.01f ? blend(base, accent, f) : base);
                canvas.drawRoundRect(rect, 16 * d, 16 * d, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth((pedalHeld ? 2.0f : 1.2f) * d);
                paint.setColor(f > 0.01f ? accent : Color.rgb(126, 190, 220));
                canvas.drawRoundRect(rect, 16 * d, 16 * d, paint);
                paint.setStyle(Paint.Style.FILL);
                // Each pad shows its piece as a drawn icon instead of text.
                float ix = rect.centerX();
                float iy = rect.centerY();
                float s = Math.min(rect.width(), rect.height()) * 0.26f;
                int note = notes()[i];
                if (i == pedalPad()) {
                    drawIconPedal(canvas, ix, iy, s, d);
                } else if (note == 46) {
                    drawIconHiHat(canvas, ix, iy, s, d, pedalDown);
                } else if (note == 42) {
                    drawIconHiHat(canvas, ix, iy, s, d, true);
                } else if (note == 49 || note == 51) {
                    drawIconCymbal(canvas, ix, iy, s, d, note == 51);
                } else if (note == 38) {
                    drawIconSnare(canvas, ix, iy, s, d, i == snarePad() && snareIsRim);
                } else if (note == 36) {
                    drawIconKick(canvas, ix, iy, s * 1.15f, d);
                } else {
                    // Toms: high 50, mid 47, floor 43 — bigger as the pitch drops.
                    float ts = note == 50 ? s * 0.85f : (note == 47 ? s * 0.95f : s * 1.1f);
                    drawIconTom(canvas, ix, iy, ts, d);
                }
                if (!pedalHeld && flash[i] > 0.01f) {
                    flash[i] = flash[i] * 0.85f;
                    animating = true;
                } else if (!pedalHeld) {
                    flash[i] = 0;
                }
            }
            if (animating) {
                postInvalidateOnAnimation();
            }
        }
    }

    private static final class SignalChainView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private String[] labels = new String[0];
        private int accent = Color.rgb(45, 178, 168);
        private int highlight = -1;

        SignalChainView(Context context) {
            super(context);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        void setChain(String[] labels, int accent, int highlight) {
            this.labels = labels;
            this.accent = accent;
            this.highlight = highlight;
            invalidate();
        }

        void setAccent(int accent) {
            this.accent = accent;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (labels.length == 0) {
                return;
            }
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth();
            float pad = 6 * d;
            int n = labels.length;
            float colW = (w - 2 * pad) / n;
            float box = Math.min(40 * d, colW - 6 * d);
            float cy = 34 * d;

            paint.setStrokeWidth(2 * d);
            paint.setColor(Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)));
            canvas.drawLine(pad, cy, w - pad, cy, paint);

            for (int i = 0; i < n; i++) {
                float cx = pad + colW * (i + 0.5f);
                boolean hi = i == highlight;
                rect.set(cx - box / 2, cy - box / 2, cx + box / 2, cy + box / 2);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(COLOR_SKY_CONTROL);
                canvas.drawRoundRect(rect, 8 * d, 8 * d, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth((hi ? 2.2f : 1.2f) * d);
                paint.setColor(hi ? accent : COLOR_BORDER);
                canvas.drawRoundRect(rect, 8 * d, 8 * d, paint);
                paint.setStyle(Paint.Style.FILL);

                paint.setColor(hi ? accent : COLOR_GREEN);
                canvas.drawCircle(cx + box / 2 - 7 * d, cy - box / 2 + 7 * d, 3 * d, paint);

                textPaint.setColor(hi ? accent : COLOR_MUTED);
                textPaint.setTextSize(13 * d);
                canvas.drawText(labels[i].substring(0, 1), cx, cy + 5 * d, textPaint);

                textPaint.setColor(hi ? COLOR_TEXT : COLOR_MUTED);
                textPaint.setTextSize(10 * d);
                canvas.drawText(labels[i], cx, cy + box / 2 + 16 * d, textPaint);
            }

            float dy = 92 * d;
            for (int i = 0; i < 3; i++) {
                paint.setColor(i == 0 ? accent : COLOR_SKY_TRACK);
                canvas.drawCircle(w / 2 + (i - 1) * 10 * d, dy, 2.5f * d, paint);
            }
        }
    }

    private static int toneAccentStatic(TonePreset preset) {
        if (preset.mode == InstrumentMode.BASS) {
            if (preset.nativeId == 13) {
                return Color.rgb(213, 81, 91);
            }
            if (preset.nativeId == 12) {
                return Color.rgb(76, 166, 238);
            }
            return Color.rgb(98, 198, 128);
        }
        if (preset.mode == InstrumentMode.PIANO) {
            if (preset.nativeId == 22) {
                return Color.rgb(172, 119, 232);
            }
            if (preset.nativeId == 23) {
                return Color.rgb(87, 181, 227);
            }
            return Color.rgb(228, 170, 75);
        }
        if (preset.nativeId == 3) {
            return Color.rgb(213, 81, 91);
        }
        if (preset.nativeId == 4) {
            return Color.rgb(190, 94, 219);
        }
        if (preset.nativeId == 2) {
            return Color.rgb(238, 136, 59);
        }
        return Color.rgb(98, 198, 128);
    }

    private static final class LevelMeterView extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float levelDb = -120.0f;

        LevelMeterView(Context context) {
            super(context);
            trackPaint.setColor(COLOR_SURFACE_PRESSED);
            fillPaint.setColor(COLOR_TEAL);
            tickPaint.setColor(Color.argb(90, 245, 242, 235));
        }

        void setLevelDb(float levelDb) {
            this.levelDb = levelDb;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float width = getWidth();
            float height = getHeight();
            float radius = height / 2.0f;
            rect.set(0.0f, 0.0f, width, height);
            canvas.drawRoundRect(rect, radius, radius, trackPaint);

            float normalized = (levelDb + 60.0f) / 60.0f;
            normalized = Math.max(0.0f, Math.min(1.0f, normalized));

            // RMS sits below a signal's peak. Red now means the post-mix output
            // is truly near clipping, not merely that a musical transient is loud.
            if (levelDb > -1.5f) {
                fillPaint.setColor(COLOR_RED);
            } else if (levelDb > -6.0f) {
                fillPaint.setColor(COLOR_AMBER);
            } else {
                fillPaint.setColor(COLOR_TEAL);
            }

            rect.set(0.0f, 0.0f, width * normalized, height);
            canvas.drawRoundRect(rect, radius, radius, fillPaint);

            for (int i = 1; i < 4; i++) {
                float x = width * i / 4.0f;
                canvas.drawLine(x, height * 0.25f, x, height * 0.75f, tickPaint);
            }
        }
    }
}
