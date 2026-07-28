package com.instrumental.attachment;

enum InstrumentMode {
    ELECTRIC_GUITAR(0, "Electric Guitar"),
    BASS(1, "Bass"),
    PIANO(2, "Piano"),
    DRUMS(3, "Drums");

    final int nativeId;
    final String label;

    InstrumentMode(int nativeId, String label) {
        this.nativeId = nativeId;
        this.label = label;
    }
}
