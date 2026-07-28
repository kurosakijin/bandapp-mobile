package com.instrumental.attachment;

enum InputRoute {
    AUTO(0, "Auto"),
    MICROPHONE(1, "Mic"),
    USB(2, "USB-C"),
    MIDI(3, "MIDI");

    final int nativeId;
    final String label;

    InputRoute(int nativeId, String label) {
        this.nativeId = nativeId;
        this.label = label;
    }
}
