package com.instrumental.attachment;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Minimal GE100 Pro Li transport. It deliberately excludes firmware, factory
 * reset, delete, rename and capture-write commands.
 */
final class Ge100ProController {
    static final int VENDOR_ID = 0x34DB;
    static final int PRODUCT_ID = 0x0011;
    private static final String USB_PERMISSION =
            "com.instrumental.attachment.GE100_USB_PERMISSION";
    private static final UUID CLIENT_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    interface Listener {
        void onStatus(String message, boolean connected, String transport);
        void onPresetNames(List<String> names);
        void onCurrentPreset(int index, String name);
        void onGlobalParameters(GlobalParameters parameters);
        void onEffectChain(List<EffectModule> modules);
    }

    static final class GlobalParameters {
        final byte[] raw;
        final int presetIndex;
        final int volume;
        final int inputLevel;
        final int otgLevel;

        GlobalParameters(byte[] data) {
            raw = Arrays.copyOf(data, 44);
            presetIndex = u16(raw, 0);
            volume = u16(raw, 2);
            inputLevel = u16(raw, 4);
            otgLevel = u16(raw, 12);
        }
    }

    static final class EffectModule {
        final int chainIndex;
        final int valid;
        final int type;
        final int enabled;
        final int serial;
        final int[] parameters;
        final int memoryStatus;
        final int memoryIndex;
        final String memoryName;

        EffectModule(int chainIndex, int valid, int type, int enabled, int serial,
                     int[] parameters, int memoryStatus, int memoryIndex, String memoryName) {
            this.chainIndex = chainIndex;
            this.valid = valid;
            this.type = type;
            this.enabled = enabled;
            this.serial = serial;
            this.parameters = parameters;
            this.memoryStatus = memoryStatus;
            this.memoryIndex = memoryIndex;
            this.memoryName = memoryName;
        }

        String typeName() {
            String[] names = {"FX", "DRIVE", "AMP", "CAB", "GATE", "EQ",
                    "MOD", "DELAY", "ROOM"};
            return type >= 0 && type < names.length ? names[type] : "MODULE";
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final UsbManager usbManager;
    private final BluetoothAdapter bluetoothAdapter;
    private final ByteArrayOutputStream incoming = new ByteArrayOutputStream();
    private final List<String> presets = new ArrayList<>(Collections.nCopies(150, "EMPTY"));
    private final Queue<byte[]> bleWrites = new ArrayDeque<>();

    private UsbDeviceConnection usbConnection;
    private UsbInterface usbInterface;
    private UsbEndpoint usbIn;
    private UsbEndpoint usbOut;
    private volatile boolean reading;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic bleWrite;
    private BluetoothGattCharacteristic bleNotify;
    private boolean bleWriting;
    private int blePayload = 20;
    private int presetBatchStart = 1;
    private byte[] globalRaw;
    private List<EffectModule> currentModules = Collections.emptyList();
    private boolean permissionReceiverRegistered;
    private boolean usbStateReceiverRegistered;
    private volatile boolean closed;
    private String transport = "";

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            UsbDevice device = getUsbDevice(intent);
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    && device != null) {
                openUsb(device);
            } else {
                status("OTG permission was not granted", false, "OTG");
            }
        }
    };

    private final BroadcastReceiver usbStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = getUsbDevice(intent);
                if (isGe100(device)) connectUsb();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = getUsbDevice(intent);
                if (isGe100(device)) closeUsb("GE100 Pro disconnected");
            }
        }
    };

    Ge100ProController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        BluetoothManager manager = (BluetoothManager)
                context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    void start() {
        closed = false;
        try {
            IntentFilter permissionFilter = new IntentFilter(USB_PERMISSION);
            IntentFilter usbStateFilter = new IntentFilter();
            usbStateFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            usbStateFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(permissionReceiver, permissionFilter,
                        Context.RECEIVER_NOT_EXPORTED);
                permissionReceiverRegistered = true;
                context.registerReceiver(usbStateReceiver, usbStateFilter,
                        Context.RECEIVER_EXPORTED);
                usbStateReceiverRegistered = true;
            } else {
                context.registerReceiver(permissionReceiver, permissionFilter);
                permissionReceiverRegistered = true;
                context.registerReceiver(usbStateReceiver, usbStateFilter);
                usbStateReceiverRegistered = true;
            }
        } catch (RuntimeException e) {
            unregisterReceivers();
            status("External pedal connection is unavailable on this device", false, "");
            return;
        }
        connectUsb();
    }

    void connectUsb() {
        if (closed || usbManager == null) {
            status("USB host control is unavailable on this device", false, "OTG");
            return;
        }
        java.util.Map<String, UsbDevice> devices;
        try {
            devices = usbManager.getDeviceList();
        } catch (RuntimeException e) {
            status("Could not access connected USB devices", false, "OTG");
            return;
        }
        for (UsbDevice device : devices.values()) {
            if (!isGe100(device)) continue;
            try {
                if (!usbManager.hasPermission(device)) {
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent permission = PendingIntent.getBroadcast(context, 7100,
                            new Intent(USB_PERMISSION).setPackage(context.getPackageName()), flags);
                    status("GE100 Pro found. Allow USB control.", false, "OTG");
                    usbManager.requestPermission(device, permission);
                } else {
                    openUsb(device);
                }
            } catch (RuntimeException e) {
                status("Could not request external pedal USB access", false, "OTG");
            }
            return;
        }
        status("Connect GE100 Pro Li with a data-capable OTG cable", false, "OTG");
    }

    @SuppressWarnings("MissingPermission")
    void scanBluetooth() {
        if (!hasBluetoothPermission()) {
            status("Bluetooth permission required", false, "Bluetooth");
            return;
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            status("Turn Bluetooth on, then scan again", false, "Bluetooth");
            return;
        }
        scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            status("Bluetooth LE is unavailable", false, "Bluetooth");
            return;
        }
        try {
            scanner.stopScan(scanCallback);
            status("Scanning for GE100 Pro Li...", false, "Bluetooth");
            scanner.startScan(scanCallback);
        } catch (RuntimeException e) {
            scanner = null;
            status("Bluetooth scan could not start", false, "Bluetooth");
            return;
        }
        main.postDelayed(() -> {
            if (scanner != null && gatt == null) {
                try { scanner.stopScan(scanCallback); } catch (SecurityException ignored) { }
                status("No GE100 Pro found. Enable Bluetooth on the pedal.", false, "Bluetooth");
            }
        }, 12000);
    }

    void refresh() {
        if (!isConnected()) return;
        Collections.fill(presets, "EMPTY");
        presetBatchStart = 1;
        sendCommand(0x11);
        main.postDelayed(() -> sendCommand(0x31), 80);
        main.postDelayed(() -> requestPresetBatch(1), 160);
    }

    void selectPreset(int oneBasedIndex) {
        if (globalRaw == null || globalRaw.length < 44) {
            status("Reading device state first...", true, transport);
            sendCommand(0x11);
            return;
        }
        int target = Math.max(1, Math.min(150, oneBasedIndex));
        byte[] updated = Arrays.copyOf(globalRaw, 44);
        putU16(updated, 0, target);
        sendCommand(0x13, updated);
        main.postDelayed(() -> sendCommand(0x31), 100);
    }

    void setGlobalLevel(int field, int value) {
        if (globalRaw == null || globalRaw.length < 44) return;
        int offset;
        if (field == 0) offset = 2;       // master volume
        else if (field == 1) offset = 4;  // input level
        else offset = 12;                 // OTG level
        byte[] updated = Arrays.copyOf(globalRaw, 44);
        int maximum = field == 0 ? 100 : 21;
        putU16(updated, offset, Math.max(0, Math.min(maximum, value)));
        sendCommand(0x13, updated);
        globalRaw = updated;
        main.postDelayed(() -> sendCommand(0x11), 80);
    }

    void setModuleEnabled(int chainIndex, boolean enabled) {
        if (chainIndex < 0 || chainIndex >= currentModules.size()) return;
        EffectModule m = currentModules.get(chainIndex);
        byte[] data = new byte[51];
        data[0] = (byte) chainIndex;
        putU16(data, 1, m.valid);
        putU16(data, 3, m.type);
        putU16(data, 5, enabled ? 1 : 0);
        putU16(data, 7, m.serial);
        for (int i = 0; i < 10; i++) putU16(data, 9 + i * 2, m.parameters[i]);
        data[29] = (byte) m.memoryStatus;
        data[30] = (byte) m.memoryIndex;
        byte[] name = m.memoryName.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(name, 0, data, 31, Math.min(20, name.length));
        sendCommand(0x25, data);
        main.postDelayed(() -> sendCommand(0x31), 100);
    }

    boolean isConnected() {
        return usbConnection != null || (gatt != null && bleWrite != null);
    }

    void close() {
        closed = true;
        reading = false;
        main.removeCallbacksAndMessages(null);
        closeUsb(null);
        stopBluetooth();
        unregisterReceivers();
        io.shutdownNow();
    }

    private void unregisterReceivers() {
        if (permissionReceiverRegistered) {
            try { context.unregisterReceiver(permissionReceiver); }
            catch (IllegalArgumentException ignored) { }
            permissionReceiverRegistered = false;
        }
        if (usbStateReceiverRegistered) {
            try { context.unregisterReceiver(usbStateReceiver); }
            catch (IllegalArgumentException ignored) { }
            usbStateReceiverRegistered = false;
        }
    }

    private static UsbDevice getUsbDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private static boolean isGe100(UsbDevice device) {
        return device != null && device.getVendorId() == VENDOR_ID
                && device.getProductId() == PRODUCT_ID;
    }

    private void openUsb(UsbDevice device) {
        if (closed || usbManager == null || device == null) return;
        closeUsb(null);
        UsbInterface found = null;
        UsbEndpoint in = null;
        UsbEndpoint out = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            if (candidate.getInterfaceClass() != UsbConstants.USB_CLASS_HID) continue;
            found = candidate;
            for (int e = 0; e < candidate.getEndpointCount(); e++) {
                UsbEndpoint endpoint = candidate.getEndpoint(e);
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) in = endpoint;
                else out = endpoint;
            }
            break;
        }
        if (found == null) {
            status("GE100 control interface was not found", false, "OTG");
            return;
        }
        UsbDeviceConnection connection;
        boolean claimed;
        try {
            connection = usbManager.openDevice(device);
            claimed = connection != null && connection.claimInterface(found, true);
        } catch (RuntimeException e) {
            status("Could not open the external pedal USB interface", false, "OTG");
            return;
        }
        if (connection == null || !claimed) {
            if (connection != null) connection.close();
            status("Could not claim GE100 control interface", false, "OTG");
            return;
        }
        usbConnection = connection;
        usbInterface = found;
        usbIn = in;
        usbOut = out;
        transport = "OTG";
        status("Connected to GE100 Pro Li", true, transport);
        reading = true;
        io.execute(this::readUsbLoop);
        refresh();
    }

    private void readUsbLoop() {
        byte[] report = new byte[64];
        while (reading && usbConnection != null && usbIn != null) {
            int count;
            try {
                count = usbConnection.bulkTransfer(usbIn, report, report.length, 300);
            } catch (RuntimeException e) {
                if (reading && !closed) status("External pedal USB connection stopped", false, "OTG");
                break;
            }
            if (count <= 0) continue;
            int frameLength = report[0] & 0xFF;
            if (frameLength > 0 && frameLength < count) {
                appendIncoming(Arrays.copyOfRange(report, 1, frameLength + 1));
            } else {
                appendIncoming(Arrays.copyOf(report, count));
            }
        }
    }

    private void closeUsb(String message) {
        reading = false;
        if (usbConnection != null) {
            try {
                if (usbInterface != null) usbConnection.releaseInterface(usbInterface);
                usbConnection.close();
            } catch (RuntimeException ignored) { }
        }
        usbConnection = null;
        usbInterface = null;
        usbIn = null;
        usbOut = null;
        if (message != null) status(message, false, "OTG");
    }

    private void sendCommand(int command, byte... data) {
        sendPacket(pack(command, data));
    }

    private void sendPacket(byte[] packet) {
        if (closed) return;
        if (usbConnection != null) {
            try {
                io.execute(() -> writeUsb(packet));
            } catch (RejectedExecutionException ignored) { }
        } else if (gatt != null && bleWrite != null) {
            queueBle(packet);
        }
    }

    private void writeUsb(byte[] packet) {
        for (int offset = 0; offset < packet.length; offset += 63) {
            if (closed || usbConnection == null || usbInterface == null) return;
            int length = Math.min(63, packet.length - offset);
            byte[] report = new byte[64];
            report[0] = (byte) length;
            System.arraycopy(packet, offset, report, 1, length);
            int sent = -1;
            try {
                if (usbOut != null) {
                    sent = usbConnection.bulkTransfer(usbOut, report, report.length, 300);
                }
                if (sent < 0) {
                    sent = usbConnection.controlTransfer(0x21, 0x09, 0x0200,
                            usbInterface.getId(), report, report.length, 300);
                }
            } catch (RuntimeException e) {
                status("External pedal OTG write stopped", false, "OTG");
                return;
            }
            if (sent < 0) {
                status("GE100 OTG write failed", false, "OTG");
                return;
            }
            if (offset + length < packet.length) {
                try { Thread.sleep(4); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static byte[] pack(int command, byte[] data) {
        int payloadLength = 1 + data.length;
        byte[] packet = new byte[2 + 2 + payloadLength + 2];
        packet[0] = (byte) 0xAA;
        packet[1] = 0x55;
        packet[2] = (byte) payloadLength;
        packet[3] = (byte) (payloadLength >> 8);
        packet[4] = (byte) command;
        System.arraycopy(data, 0, packet, 5, data.length);
        int crc = crc16(packet, 2, 2 + payloadLength);
        packet[packet.length - 2] = (byte) (crc >> 8);
        packet[packet.length - 1] = (byte) crc;
        return packet;
    }

    private static int crc16(byte[] bytes, int offset, int length) {
        int crc = 0;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (bytes[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return crc ^ 0xFFFF;
    }

    private synchronized void appendIncoming(byte[] bytes) {
        incoming.write(bytes, 0, bytes.length);
        byte[] all = incoming.toByteArray();
        int consumed = 0;
        while (consumed + 6 <= all.length) {
            int head = findHeader(all, consumed);
            if (head < 0) {
                consumed = Math.max(0, all.length - 1);
                break;
            }
            if (head + 6 > all.length) { consumed = head; break; }
            int length = u16(all, head + 2);
            int total = 2 + 2 + length + 2;
            if (head + total > all.length) { consumed = head; break; }
            int command = all[head + 4] & 0xFF;
            byte[] data = Arrays.copyOfRange(all, head + 5, head + 4 + length);
            handlePacket(command, data);
            consumed = head + total;
        }
        incoming.reset();
        if (consumed < all.length) incoming.write(all, consumed, all.length - consumed);
    }

    private static int findHeader(byte[] data, int start) {
        for (int i = start; i + 1 < data.length; i++) {
            if ((data[i] & 0xFF) == 0xAA && (data[i + 1] & 0xFF) == 0x55) return i;
        }
        return -1;
    }

    private void handlePacket(int command, byte[] data) {
        if (command == 0x12 && data.length >= 44) {
            globalRaw = Arrays.copyOf(data, 44);
            GlobalParameters globals = new GlobalParameters(globalRaw);
            main.post(() -> listener.onGlobalParameters(globals));
        } else if (command == 0x2A && data.length >= 18) {
            int count = (data.length - 2) / 16;
            for (int i = 0; i < count && presetBatchStart - 1 + i < presets.size(); i++) {
                presets.set(presetBatchStart - 1 + i,
                        decodeName(data, 2 + i * 16, 16, "EMPTY"));
            }
            int next = presetBatchStart + count;
            if (next <= 150) {
                main.postDelayed(() -> requestPresetBatch(next), 35);
            } else {
                List<String> copy = new ArrayList<>(presets);
                main.post(() -> listener.onPresetNames(copy));
            }
        } else if (command == 0x32 && data.length >= 17) {
            int index = data[0] & 0xFF;
            String name = decodeName(data, 1, 16, "Preset " + index);
            List<EffectModule> modules = parseModules(data);
            currentModules = modules;
            main.post(() -> {
                listener.onCurrentPreset(index, name);
                listener.onEffectChain(modules);
            });
        }
    }

    private List<EffectModule> parseModules(byte[] data) {
        List<EffectModule> modules = new ArrayList<>();
        for (int chain = 0; chain < 10; chain++) {
            int o = 17 + chain * 50;
            if (o + 50 > data.length) break;
            int valid = u16(data, o);
            int type = u16(data, o + 2);
            int enabled = u16(data, o + 4);
            int serial = u16(data, o + 6);
            int[] params = new int[10];
            for (int i = 0; i < 10; i++) params[i] = u16(data, o + 8 + i * 2);
            int memoryStatus = data[o + 28] & 0xFF;
            int memoryIndex = data[o + 29] & 0xFF;
            String memoryName = decodeName(data, o + 30, 20, "");
            modules.add(new EffectModule(chain, valid, type, enabled, serial,
                    params, memoryStatus, memoryIndex, memoryName));
        }
        return modules;
    }

    private void requestPresetBatch(int start) {
        presetBatchStart = start;
        int end = Math.min(150, start + 9);
        sendCommand(0x29, (byte) start, (byte) end);
    }

    @SuppressWarnings("MissingPermission")
    private void stopBluetooth() {
        if (scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (SecurityException ignored) { }
        }
        scanner = null;
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (SecurityException ignored) { }
        }
        gatt = null;
        bleWrite = null;
        bleNotify = null;
        synchronized (bleWrites) { bleWrites.clear(); bleWriting = false; }
    }

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isTargetBluetoothDevice(BluetoothDevice device, ScanResult result) {
        String name = null;
        try { name = device.getName(); } catch (SecurityException ignored) { }
        if (name == null && result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
        if (name == null) return false;
        String normalized = name.toLowerCase(Locale.US).replace(" ", "");
        return normalized.contains("ge100") || normalized.contains("mooer");
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @SuppressWarnings("MissingPermission")
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (!isTargetBluetoothDevice(device, result)) return;
            if (scanner != null) scanner.stopScan(this);
            status("GE100 Pro found. Connecting...", false, "Bluetooth");
            gatt = device.connectGatt(context, false, gattCallback,
                    Build.VERSION.SDK_INT >= 23 ? BluetoothDevice.TRANSPORT_LE : 0);
        }

        @Override public void onScanFailed(int errorCode) {
            status("Bluetooth scan failed (" + errorCode + ")", false, "Bluetooth");
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressWarnings("MissingPermission")
        @Override public void onConnectionStateChange(BluetoothGatt bluetoothGatt,
                                                       int statusCode, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                transport = "Bluetooth";
                bluetoothGatt.requestMtu(185);
                bluetoothGatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                bleWrite = null;
                bleNotify = null;
                status("GE100 Bluetooth disconnected", false, "Bluetooth");
            }
        }

        @Override public void onMtuChanged(BluetoothGatt bluetoothGatt, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) blePayload = Math.max(20, mtu - 3);
        }

        @SuppressWarnings("MissingPermission")
        @Override public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int statusCode) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status("Could not read GE100 Bluetooth services", false, "Bluetooth");
                return;
            }
            for (BluetoothGattService service : bluetoothGatt.getServices()) {
                BluetoothGattCharacteristic localWrite = null;
                BluetoothGattCharacteristic localNotify = null;
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    int p = c.getProperties();
                    if (localWrite == null && (p & (BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) {
                        localWrite = c;
                    }
                    if (localNotify == null && (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                            | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                        localNotify = c;
                    }
                }
                if (localWrite != null && localNotify != null) {
                    bleWrite = localWrite;
                    bleNotify = localNotify;
                    break;
                }
            }
            if (bleWrite == null || bleNotify == null) {
                status("GE100 Bluetooth control service is unsupported", false, "Bluetooth");
                return;
            }
            bluetoothGatt.setCharacteristicNotification(bleNotify, true);
            BluetoothGattDescriptor descriptor = bleNotify.getDescriptor(CLIENT_CONFIG);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                bluetoothGatt.writeDescriptor(descriptor);
            }
            status("Connected to GE100 Pro Li", true, "Bluetooth");
            main.postDelayed(Ge100ProController.this::refresh, 180);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt bluetoothGatt,
                                                       BluetoothGattCharacteristic characteristic) {
            appendIncoming(characteristic.getValue());
        }

        @Override public void onCharacteristicWrite(BluetoothGatt bluetoothGatt,
                                                     BluetoothGattCharacteristic characteristic,
                                                     int statusCode) {
            synchronized (bleWrites) { bleWriting = false; }
            writeNextBle();
        }
    };

    private void queueBle(byte[] packet) {
        synchronized (bleWrites) {
            for (int offset = 0; offset < packet.length; offset += blePayload) {
                bleWrites.add(Arrays.copyOfRange(packet, offset,
                        Math.min(packet.length, offset + blePayload)));
            }
        }
        writeNextBle();
    }

    @SuppressWarnings("MissingPermission")
    private void writeNextBle() {
        byte[] next;
        synchronized (bleWrites) {
            if (bleWriting || bleWrites.isEmpty() || gatt == null || bleWrite == null) return;
            next = bleWrites.poll();
            bleWriting = true;
        }
        int properties = bleWrite.getProperties();
        boolean noResponse = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        bleWrite.setWriteType(noResponse ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        bleWrite.setValue(next);
        if (!gatt.writeCharacteristic(bleWrite)) {
            synchronized (bleWrites) { bleWriting = false; }
            status("GE100 Bluetooth write failed", false, "Bluetooth");
        } else if (noResponse) {
            main.postDelayed(() -> {
                synchronized (bleWrites) { bleWriting = false; }
                writeNextBle();
            }, 10);
        }
    }

    private void status(String message, boolean connected, String link) {
        main.post(() -> listener.onStatus(message, connected, link));
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static void putU16(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >> 8);
    }

    private static String decodeName(byte[] data, int offset, int length, String fallback) {
        int end = offset;
        int limit = Math.min(data.length, offset + length);
        while (end < limit && data[end] != 0) end++;
        String value = new String(data, offset, Math.max(0, end - offset),
                StandardCharsets.UTF_8).trim();
        return value.isEmpty() ? fallback : value;
    }
}
