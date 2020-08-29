
package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import no.nordicsemi.android.ble.BleServerManager;

import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModule.SERVICE_UUID;
import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModule.UUID_READ_ADVERTISE;
import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModule.UUID_READ_UPGRADE;
import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModule.UUID_WRITE_ADVERTISE;
import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModule.UUID_WRITE_UPGRADE;

public class BluetoothLEServerManager extends BleServerManager {

    public BluetoothLEServerManager(@NonNull final Context context) {
        super(context);
    }

    @NonNull
    @Override
    protected List<BluetoothGattService> initializeServer() {
        return Collections.singletonList(
                service(SERVICE_UUID,
                        characteristic(UUID_READ_ADVERTISE,
                                BluetoothGattCharacteristic.PROPERTY_READ // properties
                                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                                BluetoothGattCharacteristic.PERMISSION_READ, // permissions
                                cccd(),
                                reliableWrite(),
                                description("Read advertise packet", false) // descriptors
                        ),
                        characteristic(UUID_READ_UPGRADE,
                                BluetoothGattCharacteristic.PROPERTY_READ
                                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                                BluetoothGattCharacteristic.PERMISSION_READ,
                                cccd(),
                                reliableWrite(),
                                description("read upgrade packet", false)
                        ),
                        characteristic(UUID_WRITE_ADVERTISE,
                                BluetoothGattCharacteristic.PROPERTY_WRITE
                                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                                BluetoothGattCharacteristic.PERMISSION_WRITE,
                                cccd(),
                                reliableWrite(),
                                description("write advertise packet", false)
                        ),
                        characteristic(UUID_WRITE_UPGRADE,
                                BluetoothGattCharacteristic.PROPERTY_WRITE
                                        | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                                BluetoothGattCharacteristic.PERMISSION_WRITE,
                                cccd(),
                                reliableWrite(),
                                description("write upgrade packet", false))
                )
        );
    }

}