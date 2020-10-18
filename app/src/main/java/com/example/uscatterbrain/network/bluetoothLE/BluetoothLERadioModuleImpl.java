package com.example.uscatterbrain.network.bluetoothLE;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.uscatterbrain.ScatterCallback;
import com.example.uscatterbrain.ScatterRoutingServiceImpl;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.InputStreamObserver;
import com.example.uscatterbrain.network.ScatterPeerHandler;
import com.example.uscatterbrain.network.ScatterRadioModule;
import com.polidea.rxandroidble2.RxBleServer;
import com.polidea.rxandroidble2.RxBleServerConnection;
import com.polidea.rxandroidble2.ServerConfig;
import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
public class BluetoothLERadioModule implements ScatterPeerHandler {
    public static final String TAG = "BluetoothLE";
    public static final UUID SERVICE_UUID = UUID.fromString("9a21e79f-4a6d-4e28-95c6-257f5e47fd90");
    public static final UUID UUID_ADVERTISE = UUID.fromString("9a22e79f-4a6d-4e28-95c6-257f5e47fd90");
    public static final UUID UUID_UPGRADE =  UUID.fromString("9a24e79f-4a6d-4e28-95c6-257f5e47fd90");
    private final BluetoothGattService mService = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
    private final BluetoothGattCharacteristic mAdvertiseCharacteristic = new BluetoothGattCharacteristic(
            UUID_ADVERTISE,
            BluetoothGattCharacteristic.PROPERTY_READ |
                    BluetoothGattCharacteristic.PROPERTY_WRITE |
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE |
                    BluetoothGattCharacteristic.PERMISSION_READ
    );

    private final BluetoothGattCharacteristic mUpgradeCharacteristic = new BluetoothGattCharacteristic(
            UUID_UPGRADE,
            BluetoothGattCharacteristic.PROPERTY_READ |
                    BluetoothGattCharacteristic.PROPERTY_WRITE |
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE |
                    BluetoothGattCharacteristic.PERMISSION_READ
    );
    private final CompositeDisposable mGattServerDisposable = new CompositeDisposable();
    private final Context mContext;
    private final Map<BluetoothDevice, PeerHandle> mPeers = new ConcurrentHashMap<>();
    private final AdvertiseCallback mAdvertiseCallback =  new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            Log.v(TAG, "successfully started advertise");

            final BluetoothManager bm = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
        }

        @Override
        public void onStartFailure(int errorCode) {
            super.onStartFailure(errorCode);
            Log.e(TAG, "failed to start advertise");
        }
    };;
    private BluetoothLeAdvertiser mAdvertiser;
    private RxBleServer mServer;
    private AdvertisePacket mAdvertise;
    private UUID mModuleUUID;



    public BluetoothLERadioModule() {
        mContext = null;
        mPeers = new HashMap<>();
        mAdvertise = null;
        mCurrentResults = new HashMap<>();
    }

    private void processPeers(ScatterCallback<Void, Void> callback) {
        Log.v(TAG, "processing " + mCurrentResults.size() + " peers");
        if (mCurrentResults.size() == 0) {
            callback.call(null);
            return;
        }
        mProcessedPeerCount = 0;
        for (Map.Entry<String, ScanResult> result : mCurrentResults.entrySet()) {
            Log.v(TAG, "processing result " + result.getKey());
            mClientObserver.connect(result.getValue().getDevice(), success -> {
                if (success) {
                    Log.v(TAG, "successfully sent blockdata packet");
                } else {
                    Log.e(TAG, "failed to send blockdata packet");
                }
                mProcessedPeerCount++;
                if (mProcessedPeerCount >= mCurrentResults.size()) {
                    callback.call(null);
                }

                return null;
            });

        }
    }

    @Override
    public Observable<UUID> getOnPeersChanged() {
        return null;
    }

    @Override
    public void setAdvertisePacket(AdvertisePacket advertisePacket) {
        mAdvertise = advertisePacket;
    }

    @Override
    public AdvertisePacket getAdvertisePacket() {
        return mAdvertise;
    }

    @Override
    public void startAdvertise() throws AdvertiseFailedException {

    }

    @Override
    public void stopAdvertise() throws AdvertiseFailedException {

    }

    @Override
    public void startDiscover() throws AdvertiseFailedException {

    }

    @Override
    public void stopDiscover() throws AdvertiseFailedException {

    }

    @Override
    public UUID register(ScatterRoutingService service) {
        this.mContext = service;
        mModuleUUID = UUID.randomUUID();
        mAdvertiser = BluetoothAdapter.getDefaultAdapter().getBluetoothLeAdvertiser();
        mServer = RxBleServer.create(mContext);
        ServerConfig config = new ServerConfig();
        config.addService(new BluetoothGattService(UUID.randomUUID(), BluetoothGattService.SERVICE_TYPE_PRIMARY));
        Disposable flowDisplosable = mServer.openServer(config).subscribe(conection -> {
            Log.v(TAG, "device connected " + conection.getDevice().getAddress());
        });
        return mModuleUUID;
    }

    @Override
    public List<UUID> getPeers() {
        return mPeers;
    }

    @Override
    public UUID getModuleID() {
        return mModuleUUID;
    }

    @Override
    public boolean isRegistered() {
        return mModuleUUID != null;
    }


    public void startLEAdvertise(byte[] data) {
        Log.v(TAG, "Starting LE advertise");

        if(data.length > 20) {
            Log.e(TAG, "err: data is longer than LE advertise frame");
            return;
        }

        BluetoothGattService service = new BluetoothGattService(BluetoothLERadioModule.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic ssidCharacteristic = new
                BluetoothGattCharacteristic(BluetoothLERadioModule.UUID_READ_ADVERTISE, BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
        service.addCharacteristic(ssidCharacteristic);

        Log.v(TAG, "Starting LE advertise");
        if(Build.VERSION.SDK_INT >= 26) {

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setConnectable(true)
                    .setTimeout(0)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .build();

            AdvertiseData addata = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceUuid(new ParcelUuid(BluetoothLERadioModuleImpl.SERVICE_UUID))
                    .build();

            mAdvertiseCallback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    super.onStartSuccess(settingsInEffect);
                    Log.v(TAG, "successfully started advertise");

                    final BluetoothManager bm = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
                    mServerObserver.startServer();
                }

                @Override
                public void onAdvertisingSetStarted(AdvertisingSet advertisingSet, int txPower, int status) {
                    super.onAdvertisingSetStarted(advertisingSet, txPower, status);
                    mAdvertisingSet = advertisingSet;
                }
            };

            mAdvertiser.startAdvertising(settings, addata, mAdvertiseCallback);

        } else {
            throw new AdvertiseFailedException("wrong sdk version");
        }
    }

    @Override
    public void stopAdvertise() {
        Log.v(TAG, "stopping LE advertise");
        mAdvertiser.stopAdvertising(mAdvertiseCallback);
    }

    @Override
    public void startDiscover(discoveryOptions opts) {
        if (mScanCallback != null) {
            return;
        }

        if (opts != discoveryOptions.OPT_DISCOVER_ONCE && opts != discoveryOptions.OPT_DISCOVER_FOREVER) {
            return; //TODO: handle this
        }

        mCurrentResults.clear();

        mScanCallback = new ScanCallback() {

            @Override
            public void onBatchScanResults(@NonNull List<ScanResult> results) {
                super.onBatchScanResults(results);

                for( ScanResult result : results) {
                    Log.v(TAG, "scan " + result.getDevice().getAddress());
                    mCurrentResults.put(result.getDevice().getAddress(), result);
                }

                stopDiscover();
                processPeers(key -> {
                    Log.v(TAG, "finished processing peers");
                    List<UUID> list = new ArrayList<>(); //TODO: remove placeholder
                    for (int x=0;x<mProcessedPeerCount;x++) {
                        list.add(UUID.randomUUID());
                    }
                    mPeersChangedCallback.call(list);
                    if (opts == discoveryOptions.OPT_DISCOVER_FOREVER) {
                        startDiscover(opts);
                    }
                    return null;
                });

            }

            @Override
            public void onScanFailed(int errorCode) {
                super.onScanFailed(errorCode);
                Log.e(TAG, "scan failed: " + errorCode);
                mPeersChangedCallback.call(null);
            }
        };

        BluetoothLeScannerCompat scanner = BluetoothLeScannerCompat.getScanner();
        ScanSettings settings = new ScanSettings.Builder()
                .setLegacy(true)
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(1000) //TODO: make configurable
                .setUseHardwareBatchingIfSupported(true)
                .setUseHardwareFilteringIfSupported(true)
                .build();

        List<ScanFilter> filters = new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE_UUID)).build());
        scanner.startScan(filters, settings, mScanCallback);
    }

    @Override
    public void stopDiscover(){
        if (mScanCallback == null) {
            return;
        }
        BluetoothLeScannerCompat scanner = BluetoothLeScannerCompat.getScanner();
        scanner.stopScan(mScanCallback);
    }

    private void onConnected(PeerHandle handle) {
        Disposable notificationDisposable = handle.getConnection().setupNotifications(mAdvertiseCharacteristic, Observable.fromArray(mAdvertise.getBytes()))
                .subscribe(
                        oncomplete -> {

                            },
                        onerror -> {
                            Log.e(TAG, "failed to setup advertise characteristic notifications");
                        });
        mGattServerDisposable.add(notificationDisposable);

        handle.getConnection().getOnCharacteristicWriteRequest(mAdvertiseCharacteristic)
            .map(ServerResponseTransaction::getValue)
            .subscribe(handle.getInputStreamObserver());
    }

    private boolean startServer() {
        if (mServer == null) {
            return false;
        }

        Disposable d = mServer.openServer()
                .subscribe(
                        connection -> {
                            PeerHandle handle = new PeerHandle(connection);
                            mPeers.put(connection.getDevice(), handle);
                            Disposable disconnect = connection.observeDisconnect()
                                    .subscribe(dc -> mPeers.remove(connection.getDevice()), error -> {
                                        mPeers.remove(connection.getDevice());
                                        Log.e(TAG, "error when disconnecting device " + connection.getDevice());
                                    });
                            onConnected(handle);
                            mGattServerDisposable.add(disconnect);
                        },
                        error -> {
                            Log.e(TAG, "error starting server " + error.getMessage());
                        }
                );

        mGattServerDisposable.add(d);
        return true;
    }

    private void stopServer() {
        mGattServerDisposable.dispose();
        mServer.closeServer();
    }

    @Override
    public UUID register(ScatterRoutingServiceImpl service) {
        Log.v(BluetoothLERadioModuleImpl.TAG, "registered bluetooth LE radio module");
        this.mContext = service;
        mClientObserver = new BluetoothLEClientObserver(mContext, mAdvertise);

        mModuleUUID = UUID.randomUUID();
        mAdvertiser = BluetoothAdapter.getDefaultAdapter().getBluetoothLeAdvertiser();
        mServerObserver = new BluetoothLEServerObserver(mContext);
        try {
            startAdvertise();
        } catch (AdvertiseFailedException e) {
            Log.e(TAG, "failed to advertise");
        }
        return mModuleUUID;
    }

    @Override
    public List<UUID> getPeers() {
        return null;
    }

    @Override
    public UUID getModuleID() {
        return mModuleUUID;
    }

    @Override
    public boolean isRegistered() {
        return mModuleUUID != null;
    }


    private static class PeerHandle {
        private final InputStreamObserver inputStreamObserver = new InputStreamObserver();
        private final RxBleServerConnection connection;
        public PeerHandle(RxBleServerConnection connection) {
            this.connection = connection;
        }

        public InputStreamObserver getInputStreamObserver() {
            return inputStreamObserver;
        }

        public RxBleServerConnection getConnection() {
            return connection;
        }
    }
}
