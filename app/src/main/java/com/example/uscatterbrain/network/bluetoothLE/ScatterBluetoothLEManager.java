package com.example.uscatterbrain.network.bluetoothLE;

import android.app.Activity;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.MacAddress;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;

import com.example.uscatterbrain.DeviceProfile;
import com.example.uscatterbrain.ScatterRoutingService;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.ArrayList;
import java.util.EmptyStackException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Stack;
import java.util.UUID;
import java.util.concurrent.Delayed;

public class ScatterBluetoothLEManager {
    public static final String TAG = "BluetoothLE";
    public static final int SERVICE_ID = 0xFEEF;
    public static final UUID SERVICE_UUID = UUID.fromString("9a21e79f-4a6d-4e28-95c6-257f5e47fd90");
    private Service mService;

    private final int REQUEST_ENABLE_BT = 1;

    private BluetoothLeAdvertiser mAdvertiser;
    private BluetoothLeScanner mScanner;
    private BluetoothAdapter mAdapter;
    private AdvertisingSet current;
    private BluetoothGatt mGatt;
    private ScanCallback leScanCallback;

    private final Handler mHandler = new Handler();

    private Map<String,BluetoothDevice> deviceList;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;
    private int connectionState = STATE_DISCONNECTED;


    private static final int STATE_DISCOVER = 0;
    private static final int STATE_CONNECT = 1;
    private static final int STATE_IDLE = 3;
    private int currentstate = STATE_IDLE;

    public static long DEFAULT_SCAN_TIME = 30 * 1000;
    public static long DEFAULT_CONNECT_TIME = 60 * 1000;
    public static long DEFAULT_COOLDOWN_TIMEOUT = 1 * 1000;

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectionState = STATE_CONNECTED;
                    Log.i(TAG, "Connected to GATT server " + gatt.getDevice().getAddress() + "discovering services");

                    gatt.discoverServices();


            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionState = STATE_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server " + gatt.getDevice().getAddress());
                processOneScanResults();
            }

        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if(characteristic.getUuid().equals(SERVICE_UUID)) {
                    byte[] value = characteristic.getValue();
                    try {
                        AdvertisePacket ad = new AdvertisePacket(value);
                        //TODO: create transaction class
                        Log.v(TAG, "Successfully read AdvertisePacket");
                    } catch(InvalidProtocolBufferException e) {
                        Log.w(TAG, "err: received invalid advertisepacket from GATT");
                    }
                } else {
                    Log.w(TAG, "err: received GATT characteristic with wrong UUID");
                }
            }

        }


        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {


         /*
            BluetoothGattCharacteristic ch = new BluetoothGattCharacteristic(SERVICE_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ);
            AdvertisePacket ap = new AdvertisePacket(((ScatterRoutingService) mService).getProfile());
            ch.setValue(ap.getBytes());
            gatt.writeCharacteristic(ch);
            Log.v(TAG, "Wrote AdvertisePacket");
*/

            for(BluetoothGattService b : gatt.getServices()) {
                Log.v(TAG, "Discovered service: " + b.getUuid());
            }
            //TODO: don't disconnect here, validate response and then disconnect.
            gatt.disconnect();
        }


    };

    private void multiPartMessage(BluetoothGattCharacteristic characteristic, byte[] data) {

    }

    public List<BluetoothGattService> getSupportedGattServices() {
        if (mGatt == null) return null;
        return mGatt.getServices();
    }

    public ScatterBluetoothLEManager(Service mService) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mAdvertiser = mAdapter.getBluetoothLeAdvertiser();
        mScanner = mAdapter.getBluetoothLeScanner();
        deviceList = new HashMap<>();

        this.mService = mService;

        leScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);
                BluetoothDevice device = result.getDevice();

                if(result.getScanRecord()== null) {
                    Log.e(TAG, "onScanResult called with null service record");
                    return;
                }

                for(ParcelUuid p : result.getScanRecord().getServiceUuids()) {
                    if (p.getUuid().equals(SERVICE_UUID)) {
                        Log.v(TAG, "found a scatterbrain UUID");
                        deviceList.put(device.getAddress(),device);
                        break;
                    } else {
                        Log.v(TAG, "found nonscatterbrain UUID " + p.getUuid().toString());
                    }

                }

                Log.v(TAG, "enqueued scan result " + device.getAddress());
            }

            @Override
            public void onScanFailed(int errorCode) {
                super.onScanFailed(errorCode);
                Log.v(TAG, "scan failed " + errorCode);
            }
        };
    }



    private boolean processOneScanResults() {
        if(currentstate != STATE_CONNECT)
            return false;
        try {
            String key = deviceList.entrySet().iterator().next().getKey();
            BluetoothDevice d = deviceList.remove(key);
            mGatt = d.connectGatt(mService, false, gattCallback);
        } catch(NoSuchElementException e) {
            return false;
        }
        return true;
    }

    public void processPeers(long timeoutmillis) {
        currentstate = STATE_CONNECT;
        processOneScanResults();

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if(mGatt != null) {
                    mGatt.disconnect();
                }
                currentstate = STATE_IDLE;
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        onConnectTimeout();
                    }
                }, DEFAULT_COOLDOWN_TIMEOUT);

            }
        }, timeoutmillis);
    }


    private void onDiscoverTimeout() {
        Log.v(TAG, "Discovery timed out, processing peers");
        processPeers(DEFAULT_CONNECT_TIME);
    }

    private void onConnectTimeout() {
        Log.v(TAG, "Connedt timed out, switching back to discovery");
        scanTime(DEFAULT_SCAN_TIME);
    }

    public void scanTime(long scantimemillis) {
        Log.v(TAG, "Starting LE scan");
        deviceList.clear();
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                ScanFilter s = new ScanFilter.Builder()
                        .setServiceUuid(new ParcelUuid(SERVICE_UUID))
                        .build();

                ScanSettings settings = new ScanSettings.Builder()
                        .build();

                List<ScanFilter> sflist = new ArrayList<>();
                sflist.add(s);

                currentstate = STATE_DISCOVER;
                mScanner.startScan(sflist, settings, leScanCallback);

            }
        });

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mScanner.stopScan(leScanCallback);
                currentstate = STATE_IDLE;

                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        onDiscoverTimeout();
                    }
                },DEFAULT_COOLDOWN_TIMEOUT);

            }
        }, scantimemillis);

    }

    public void startScan() {
        scanTime(DEFAULT_SCAN_TIME);
    }

    public void stopScan() {
        Log.v(TAG, "Stopping LE scan");
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                 mScanner.stopScan(leScanCallback);
            }
        });
    }

    public void enableBluetooth(Activity activity) {
        if (mAdapter == null || !mAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);

            activity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }

    }

    public void startLEAdvertise(byte[] data) {
        Log.v(TAG, "Starting LE advertise");

        if(data.length > 20) {
            Log.e(TAG, "err: data is longer than LE advertise frame");
            return;
        }


        if(Build.VERSION.SDK_INT >= 26) {
            AdvertisingSetParameters parameters = (new AdvertisingSetParameters.Builder())
                    .setLegacyMode(true) // True by default, but set here as a reminder.
                    .setConnectable(true)
                    .setScannable(true)
                    .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
                    .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                    .build();

            AdvertiseData addata = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .build();
            AdvertisingSetCallback callback = new AdvertisingSetCallback() {
                @Override
                public void onAdvertisingSetStarted(AdvertisingSet advertisingSet, int txPower, int status) {
                    super.onAdvertisingSetStarted(advertisingSet, txPower, status);
                    current = advertisingSet;
                }
            };

            mAdvertiser.startAdvertisingSet(parameters, addata, null,
                    null, null, callback);

        } else {
            Log.e(TAG, "err: could not start LE advertise due to wrong SDK version");
        }
    }

    public void stopLEAdvertise() {
        Log.v(TAG, "stopping LE advertise");
        AdvertiseCallback callback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                super.onStartSuccess(settingsInEffect);
            }

            @Override
            public void onStartFailure(int errorCode) {
                super.onStartFailure(errorCode);
            }
        };

        mAdvertiser.stopAdvertising(callback);
    }

    public boolean setAdvertisingData(byte[] data) {
        Log.v(TAG, "setting advertise data");
        if(current == null) {
            Log.e(TAG, "err: AdvertisingSet is null");
            return false;
        }

        if(data.length > 20) {
            Log.e(TAG, "err: data is longer than LE advertise frame");
            return false;
        }

        if(Build.VERSION.SDK_INT >= 26) {
            current.setAdvertisingData(new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceData(new ParcelUuid(SERVICE_UUID), data)
                    .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .build());
        } else {
            Log.e(TAG, "err: could not update advertising data due to wrong SDK version");
        }

        return true;
    }

}
