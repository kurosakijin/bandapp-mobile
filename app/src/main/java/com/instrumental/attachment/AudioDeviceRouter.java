package com.instrumental.attachment;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import java.util.Locale;

final class AudioDeviceRouter {
    static final int NO_DEVICE = -1;

    private final Context context;
    private final AudioManager audioManager;

    AudioDeviceRouter(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    DeviceSelection select(InputRoute route) {
        AudioDeviceInfo input = null;
        AudioDeviceInfo output = null;

        if (route == InputRoute.USB || route == InputRoute.AUTO) {
            input = firstUsbDevice(AudioManager.GET_DEVICES_INPUTS);
            output = firstUsbDevice(AudioManager.GET_DEVICES_OUTPUTS);
        }

        if (route == InputRoute.MICROPHONE) {
            input = firstDeviceOfType(AudioManager.GET_DEVICES_INPUTS, AudioDeviceInfo.TYPE_BUILTIN_MIC);
        }

        return new DeviceSelection(
                input == null ? NO_DEVICE : input.getId(),
                output == null ? NO_DEVICE : output.getId(),
                input == null ? "Default input" : labelFor(input),
                output == null ? "Default output" : labelFor(output),
                input != null && isUsb(input),
                output != null && isUsb(output)
        );
    }

    boolean hasUsbInput() {
        return firstUsbDevice(AudioManager.GET_DEVICES_INPUTS) != null;
    }

    // Selectable output sinks: speaker, wired jack (3.5mm), USB-C audio, Bluetooth.
    // Some tablets report their built-in 3.5mm jack as LINE_ANALOG or AUX_LINE
    // instead of wired headphones — include those too.
    java.util.List<AudioDeviceInfo> outputOptions() {
        java.util.List<AudioDeviceInfo> list = new java.util.ArrayList<>();
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || type == AudioDeviceInfo.TYPE_LINE_ANALOG
                    || type == AudioDeviceInfo.TYPE_AUX_LINE
                    || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || isUsb(device)) {
                list.add(device);
            }
        }
        return list;
    }

    // Every current output with its raw type id — for diagnosing detection gaps.
    String outputsDebugSummary() {
        StringBuilder sb = new StringBuilder("Outputs: ");
        boolean first = true;
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (!first) sb.append(", ");
            first = false;
            String n = productNameOf(device);
            sb.append(n.length() == 0 ? typeName(device.getType()) : n)
                    .append('(').append(device.getType()).append(')');
        }
        return first ? "Outputs: none" : sb.toString();
    }

    // First current output of the given type, or null if it's unplugged.
    AudioDeviceInfo outputOfType(int type) {
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getType() == type) {
                return device;
            }
        }
        return null;
    }

    // Friendly name for the output picker ("Speaker", "3.5mm headphones", USB product name).
    String outputOptionLabel(AudioDeviceInfo device) {
        int type = device.getType();
        if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            return "Phone speaker";
        }
        if (type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
            return "Wired jack (3.5mm)";
        }
        if (type == AudioDeviceInfo.TYPE_LINE_ANALOG || type == AudioDeviceInfo.TYPE_AUX_LINE) {
            return "Line out (3.5mm)";
        }
        CharSequence product = device.getProductName();
        String name = product == null || product.length() == 0 ? typeName(type) : product.toString();
        if (isUsb(device)) {
            return "USB-C · " + friendlyGear(name);
        }
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
            return "Bluetooth · " + name;
        }
        return name;
    }

    // Recognize known guitar interfaces so they read by their real name in the
    // pickers instead of a generic "USB Audio". The M-Vave Tank-G presents as a
    // USB audio interface (its Bluetooth is editor-only), which is the path that
    // lets this app capture and re-process its signal.
    private String friendlyGear(String rawName) {
        String u = rawName.toUpperCase(Locale.US);
        if (u.contains("TANK-G") || u.contains("TANK G")
                || (u.contains("MVAVE") || u.contains("M-VAVE")) && u.contains("TANK")) {
            return "M-Vave Tank-G";
        }
        if (u.contains("MVAVE") || u.contains("M-VAVE")) {
            return "M-Vave " + rawName;
        }
        return rawName;
    }

    // Selectable capture sources: internal mic, wired headset mic, every USB device
    // individually (several type-C boxes can be attached at once via a hub).
    java.util.List<AudioDeviceInfo> inputOptions() {
        java.util.List<AudioDeviceInfo> list = new java.util.ArrayList<>();
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC
                    || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || type == AudioDeviceInfo.TYPE_LINE_ANALOG
                    || isUsb(device)) {
                list.add(device);
            }
        }
        return list;
    }

    // Current input matching a saved choice. USB devices are told apart by product
    // name so a type-C mic and a type-C instrument interface stay separate picks.
    AudioDeviceInfo inputMatching(int type, String name) {
        AudioDeviceInfo first = null;
        for (AudioDeviceInfo device : inputOptions()) {
            if (device.getType() != type) {
                continue;
            }
            if (first == null) {
                first = device;
            }
            if (name != null && name.length() > 0 && name.equals(productNameOf(device))) {
                return device;
            }
        }
        return first;
    }

    String productNameOf(AudioDeviceInfo device) {
        CharSequence product = device.getProductName();
        return product == null ? "" : product.toString();
    }

    String inputOptionLabel(AudioDeviceInfo device) {
        int type = device.getType();
        if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
            return "Internal mic";
        }
        if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
            return "Headset mic (3.5mm)";
        }
        String name = productNameOf(device);
        if (name.length() == 0) {
            name = typeName(type);
        }
        if (isUsb(device)) {
            return "USB-C · " + friendlyGear(name);
        }
        return name;
    }

    boolean isUsbType(int type) {
        return type == AudioDeviceInfo.TYPE_USB_DEVICE
                || type == AudioDeviceInfo.TYPE_USB_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }

    // Names EVERY attached USB device, not just the first — with a hub, two
    // (mic + sound card) can be present at once and both must show up here.
    String usbSummary() {
        StringBuilder ins = new StringBuilder();
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (isUsb(device)) {
                if (ins.length() > 0) ins.append(" + ");
                String n = productNameOf(device);
                ins.append(n.length() == 0 ? typeName(device.getType()) : n);
            }
        }
        StringBuilder outs = new StringBuilder();
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (isUsb(device)) {
                if (outs.length() > 0) outs.append(" + ");
                String n = productNameOf(device);
                outs.append(n.length() == 0 ? typeName(device.getType()) : n);
            }
        }
        if (ins.length() == 0 && outs.length() == 0) {
            return "USB-C audio: not detected";
        }
        return "USB in: " + (ins.length() == 0 ? "none" : ins)
                + "  |  out: " + (outs.length() == 0 ? "none" : outs);
    }

    String capabilitySummary() {
        PackageManager packageManager = context.getPackageManager();
        boolean lowLatency = packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY);
        boolean proAudio = packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO);

        String sampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
        String frames = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);

        return String.format(
                Locale.US,
                "Device audio: %s Hz, %s frames, low latency %s, pro audio %s",
                emptyToUnknown(sampleRate),
                emptyToUnknown(frames),
                lowLatency ? "yes" : "no",
                proAudio ? "yes" : "no"
        );
    }

    private AudioDeviceInfo firstUsbDevice(int flag) {
        for (AudioDeviceInfo device : audioManager.getDevices(flag)) {
            if (isUsb(device)) {
                return device;
            }
        }
        return null;
    }

    private AudioDeviceInfo firstDeviceOfType(int flag, int type) {
        for (AudioDeviceInfo device : audioManager.getDevices(flag)) {
            if (device.getType() == type) {
                return device;
            }
        }
        return null;
    }

    private boolean isUsb(AudioDeviceInfo device) {
        int type = device.getType();
        return type == AudioDeviceInfo.TYPE_USB_DEVICE
                || type == AudioDeviceInfo.TYPE_USB_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }

    private String labelFor(AudioDeviceInfo device) {
        CharSequence productName = device.getProductName();
        String name = productName == null || productName.length() == 0
                ? typeName(device.getType())
                : productName.toString();
        return name + " #" + device.getId();
    }

    private String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "Built-in mic";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return "USB audio";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB headset";
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                return "USB accessory";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return "Wired headphones";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "Wired headset";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return "Phone speaker";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "Bluetooth audio";
            default:
                return "Audio device";
        }
    }

    private String emptyToUnknown(String value) {
        return value == null || value.length() == 0 ? "unknown" : value;
    }

    static final class DeviceSelection {
        final int inputDeviceId;
        final int outputDeviceId;
        final String inputLabel;
        final String outputLabel;
        final boolean usbInput;
        final boolean usbOutput;

        DeviceSelection(
                int inputDeviceId,
                int outputDeviceId,
                String inputLabel,
                String outputLabel,
                boolean usbInput,
                boolean usbOutput
        ) {
            this.inputDeviceId = inputDeviceId;
            this.outputDeviceId = outputDeviceId;
            this.inputLabel = inputLabel;
            this.outputLabel = outputLabel;
            this.usbInput = usbInput;
            this.usbOutput = usbOutput;
        }
    }
}
