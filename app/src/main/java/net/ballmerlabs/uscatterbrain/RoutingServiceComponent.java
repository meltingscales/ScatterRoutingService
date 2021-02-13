package net.ballmerlabs.uscatterbrain;


import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.PowerManager;

import androidx.room.Room;

import net.ballmerlabs.uscatterbrain.db.Datastore;
import net.ballmerlabs.uscatterbrain.db.RouterPreferencesImpl;
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastore;
import net.ballmerlabs.uscatterbrain.db.ScatterbrainDatastoreImpl;
import net.ballmerlabs.uscatterbrain.db.file.DatastoreImportProvider;
import net.ballmerlabs.uscatterbrain.db.file.DatastoreImportProviderImpl;
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLEModule;
import net.ballmerlabs.uscatterbrain.network.bluetoothLE.BluetoothLERadioModuleImpl;
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModule;
import net.ballmerlabs.uscatterbrain.network.wifidirect.WifiDirectRadioModuleImpl;
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainScheduler;
import net.ballmerlabs.uscatterbrain.scheduler.ScatterbrainSchedulerImpl;
import com.polidea.rxandroidble2.RxBleClient;
import com.polidea.rxandroidble2.RxBleServer;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Binds;
import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import io.reactivex.Scheduler;
import io.reactivex.plugins.RxJavaPlugins;

@Singleton
@Component(modules = RoutingServiceComponent.RoutingServiceModule.class)
public interface RoutingServiceComponent {

    String SHARED_PREFS = "scatterbrainprefs";

    class NamedSchedulers {
        public static final String DATABASE = "executor_database";
        public static final String BLE_CLIENT = "scheduler-ble-client";
        public static final String WIFI_DIRECT_READ = "wifi-direct-read";
        public static final String WIFI_DIRECT_WRITE = "wifi-direct-write";
        public static final String WIFI_DIRECT_OPERATIONS = "wifi-direct-operations";
        public static final String DEFAULT_SCHEDULER = "default";
        private NamedSchedulers() {

        }
    }

    class NamedRadioModules {
        public static final String BLUETOOTH_LE = "bluetooth-le";
    }

    @Component.Builder
    interface Builder {
        @BindsInstance
        Builder applicationContext(Context context);

        RoutingServiceComponent build();
    }

    @Module
    abstract class RoutingServiceModule {

        @Singleton
        @Provides
        static Datastore provideDatastore(Context ctx) {
            return Room.databaseBuilder(ctx, Datastore.class, ScatterbrainDatastore.DATABASE_NAME)
                    .build();
        }

        @Provides
        static WifiP2pManager providesWifiP2pManager(Context ctx) {
            return (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
        }

        @Provides
        @Named(NamedSchedulers.DATABASE)
        static Scheduler provideDatabaseScheduler() {
            return RxJavaPlugins.createIoScheduler(new ScatterbrainThreadFactory());
        }

        @Provides
        @Named(NamedSchedulers.WIFI_DIRECT_READ)
        static Scheduler provideWifiDirectReadScheduler() {
            return RxJavaPlugins.createSingleScheduler(new ScatterbrainThreadFactory());
        }

        @Provides
        @Named(NamedSchedulers.WIFI_DIRECT_WRITE)
        static Scheduler provideWifiDirectWriteScheduler() {
            return RxJavaPlugins.createSingleScheduler(new ScatterbrainThreadFactory());
        }

        @Provides
        @Named(NamedSchedulers.WIFI_DIRECT_OPERATIONS)
        static Scheduler provideWifiDirectOperationsScheduler() {
            return RxJavaPlugins.createIoScheduler(new ScatterbrainThreadFactory());
        }

        @Provides
        @Named(NamedSchedulers.DEFAULT_SCHEDULER)
        static Scheduler provideDefaultScheduler() {
            return RxJavaPlugins.createComputationScheduler(new ScatterbrainThreadFactory());
        }

        @Provides
        static RxBleClient provideRxBleClient(Context ctx) {
            return RxBleClient.create(ctx);
        }

        @Provides
        static RxBleServer providesRxBleServer(Context context) {
            return RxBleServer.create(context);
        }

        @Provides
        @Named(NamedSchedulers.BLE_CLIENT)
        static Scheduler provideBleClientScheduler() {
            return RxJavaPlugins.createSingleScheduler(new ScatterbrainThreadFactory());
        }

        @Provides
        static BluetoothLeAdvertiser provideLeAdvertiser() {
            return BluetoothAdapter.getDefaultAdapter().getBluetoothLeAdvertiser();
        }

        @Provides
        static SharedPreferences providesSharedPreferences(Context context) {
            return context.getSharedPreferences(SHARED_PREFS, Context.MODE_PRIVATE);
        }

        @Provides
        static PowerManager providesPowerManager(Context context) {
            return (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        }

        @Binds
        @Singleton
        abstract RoutingServiceBackend bindsRoutingServiceBackend(RoutingServiceBackendImpl impl);

        @Binds
        @Singleton
        abstract ScatterbrainScheduler bindScatterbrainScheduler(ScatterbrainSchedulerImpl impl);

        @Binds
        @Singleton
        abstract ScatterbrainDatastore bindDatastore(ScatterbrainDatastoreImpl impl);

        @Binds
        @Singleton
        abstract WifiDirectRadioModule bindWifiDirectRadioModule(WifiDirectRadioModuleImpl impl);

        @Binds
        @Singleton
        abstract RouterPreferences bindRouterPreferences(RouterPreferencesImpl impl);

        @Binds
        @Singleton
        abstract DatastoreImportProvider bindsDatastoreImportProvider(DatastoreImportProviderImpl impl);

        @Binds
        @Singleton
        abstract BluetoothLEModule bindRadioModuleInternal(BluetoothLERadioModuleImpl impl);
    }

    RoutingServiceBackend scatterRoutingService();

    void inject(DatastoreImportProviderImpl provider);
}