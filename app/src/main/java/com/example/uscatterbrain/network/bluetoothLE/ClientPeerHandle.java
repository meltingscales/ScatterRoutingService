package com.example.uscatterbrain.network.bluetoothLE;


import android.util.Log;

import com.example.uscatterbrain.ScatterProto;
import com.example.uscatterbrain.network.AckPacket;
import com.example.uscatterbrain.network.AdvertisePacket;
import com.example.uscatterbrain.network.UpgradePacket;
import com.example.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import com.polidea.rxandroidble2.RxBleConnection;

import java.util.Random;

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.subjects.BehaviorSubject;

import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.ADVERTISE_CHARACTERISTIC;
import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.UPGRADE_CHARACTERISTIC;
import static com.example.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl.UUID_ADVERTISE;

public class ClientPeerHandle implements PeerHandle {
    public static final String TAG = "ClientPeerHandle";
    private final RxBleConnection connection;
    private final AdvertisePacket advertisePacket;
    private final CompositeDisposable disposable = new CompositeDisposable();
    private final BehaviorSubject<BluetoothLEModule.UpgradeRequest> upgradeSubject;
    public ClientPeerHandle(
            RxBleConnection connection,
            AdvertisePacket advertisePacket,
            BehaviorSubject<BluetoothLEModule.UpgradeRequest> upgradeSubject
    ) {
        this.connection = connection;
        this.advertisePacket = advertisePacket;
        this.upgradeSubject = upgradeSubject;
    }

    private UpgradePacket getUpgradePacket() {
        int seqnum = Math.abs(new Random().nextInt());

        UpgradePacket upgradePacket = UpgradePacket.newBuilder()
                .setProvides(ScatterProto.Advertise.Provides.WIFIP2P)
                .setSessionID(seqnum)
                .setMetadata(WifiDirectRadioModule.UPGRADE_METADATA)
                .build();

        upgradeSubject.onNext(BluetoothLEModule.UpgradeRequest.create(
                BluetoothLEModule.ConnectionRole.ROLE_UKE,
                upgradePacket
        ));
        return upgradePacket;
    }

    @Override
    public Single<Boolean> handshake() {
        return connection.
                setupNotification(UUID_ADVERTISE)
                .doOnNext(notificationSetup -> {
                    Log.v(TAG, "client successfully set up notifications");
                })
                .flatMapSingle(AdvertisePacket::parseFrom)
                .flatMapSingle(packet -> {
                    byte[] b = packet.getBytes();
                    if (b == null) {
                        Log.e(TAG, "getBytes returned null");
                        return Single.error(new IllegalStateException("advertise packet corrupt"));
                    }
                    Log.v(TAG, "client successfully retreived advertisepacket from notification");
                    return connection.createNewLongWriteBuilder()
                            .setBytes(advertisePacket.getBytes())
                            .setCharacteristicUuid(ADVERTISE_CHARACTERISTIC.getUuid())
                            .build()
                            .ignoreElements()
                            .toSingleDefault(connection);
                })
                .flatMapSingle(connection -> {
                    UpgradePacket upgradePacket = getUpgradePacket();
                    return connection.writeCharacteristic(
                            UPGRADE_CHARACTERISTIC.getUuid(),
                            upgradePacket.getBytes()
                    )
                            .ignoreElement()
                            .toSingleDefault(connection);
                })
                .flatMapSingle(connection -> connection.setupNotification(UPGRADE_CHARACTERISTIC.getUuid())
                        .flatMapSingle(AckPacket::parseFrom)
                        .firstOrError()
                        .flatMap(ackPacket -> {
                            if (ackPacket.getStatus() == AckPacket.Status.OK) {
                                return Single.just(true);
                            } else {
                                Log.e(TAG, "received ackpacket with invalid status");
                                return Single.just(false);
                            }
                        })).first(false);

    }

    public RxBleConnection getConnection() {
        return connection;
    }

    public void close() {
        disposable.dispose();
    }
}