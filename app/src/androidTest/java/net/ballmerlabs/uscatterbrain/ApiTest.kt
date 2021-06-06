package net.ballmerlabs.uscatterbrain

import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SmallTest
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.rule.ServiceTestRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import net.ballmerlabs.scatterbrainsdk.BinderWrapper
import net.ballmerlabs.scatterbrainsdk.ScatterMessage
import net.ballmerlabs.scatterbrainsdk.ScatterbrainAPI
import net.ballmerlabs.scatterbrainsdk.internal.MockBinderProvider
import net.ballmerlabs.scatterbrainsdk.internal.BinderProvider
import net.ballmerlabs.scatterbrainsdk.internal.BinderWrapperImpl
import net.ballmerlabs.scatterbrainsdk.internal.ScatterbrainBroadcastReceiverImpl
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.IllegalStateException
import java.util.concurrent.TimeoutException
import kotlin.jvm.Throws
import kotlin.math.exp
import kotlin.random.Random

@RunWith(AndroidJUnit4ClassRunner::class)
@SmallTest
class ApiTest {
    private val testCoroutineScope = CoroutineScope(Dispatchers.Default)
    private lateinit var binder: BinderWrapper
    private lateinit var regularBinder: ScatterbrainAPI

    @get:Rule
    val serviceRule = ServiceTestRule()

    suspend fun syncSendMessage(message: ScatterMessage) {
        regularBinder.sendMessage(message)
    }

    suspend fun syncSendMesssages(messages: List<ScatterMessage>) {
        regularBinder.sendMessages(messages)
    }

    @ExperimentalCoroutinesApi
    @Before
    fun init() {
        val bindIntet = Intent(
                ApplicationProvider.getApplicationContext(),
                ScatterRoutingService::class.java
        )

        val b: IBinder = serviceRule.bindService(bindIntet)
        val binderProvider: BinderProvider = MockBinderProvider(b)
        val broadcastReceiver= ScatterbrainBroadcastReceiverImpl()
        broadcastReceiver.coroutineScope = testCoroutineScope
        broadcastReceiver.context = ApplicationProvider.getApplicationContext()
        binder = BinderWrapperImpl(
                ApplicationProvider.getApplicationContext(),
                broadcastReceiver,
                binderProvider
        )
        runBlocking { binder.startService() }
        regularBinder = ScatterbrainAPI.Stub.asInterface(b)
        regularBinder.clearDatastore()
        broadcastReceiver.register()
    }

    @Test
    @Throws(TimeoutException::class)
    fun startTest() {
        runBlocking { binder.startService() }
    }

    @Test
    @Throws(TimeoutException::class)
    fun sendMessage() {
        val message = ScatterMessage.newBuilder()
                .setApplication("testing")
                .setBody(byteArrayOf(0))
                .build()
        runBlocking { binder.sendMessage(message) }
    }

    @Test
    @Throws(TimeoutException::class)
    fun sendMessageSync() {
        val message = ScatterMessage.newBuilder()
                .setApplication("testing")
                .setBody(byteArrayOf(0))
                .build()

        runBlocking { syncSendMessage(message) }
    }

    @Test
    @Throws(TimeoutException::class)
    fun sendMessages() {
        val list = ArrayList<ScatterMessage>()
        for (x in 0..1000) {
            val message = ScatterMessage.newBuilder()
                    .setApplication("testing")
                    .setBody(Random(0).nextBytes(128))
                    .build()
            list.add(message)
        }
        runBlocking { binder.sendMessage(list) }
    }

    @Test
    @Throws(TimeoutException::class)
    fun sendMessagesSync() {
        val list = ArrayList<ScatterMessage>()
        for (x in 0..1000) {
            val message = ScatterMessage.newBuilder()
                    .setApplication("testing")
                    .setBody(Random(0).nextBytes(128))
                    .build()
            list.add(message)
        }
        runBlocking { syncSendMesssages(list) }
    }

    @Test
    @Throws(TimeoutException::class)
    fun sendAndSignMessages() {
        val list = ArrayList<ScatterMessage>()
        for (x in 0..1000) {
            val message = ScatterMessage.newBuilder()
                    .setApplication("testing")
                    .setBody(Random(0).nextBytes(128))
                    .build()
            list.add(message)
        }
        runBlocking {
            val id = binder.generateIdentity("test")
            binder.sendMessage(list, id.fingerprint)
        }
    }

    @Test(expected = IllegalStateException::class)
    @Throws(TimeoutException::class)
    fun signMessageHandleErr() {
        val list = ArrayList<ScatterMessage>()
        for (x in 0..2) {
            val message = ScatterMessage.newBuilder()
                    .setApplication("testing")
                    .setBody(Random(0).nextBytes(128))
                    .build()
            list.add(message)
        }
        runBlocking {
            binder.sendMessage(list, "fmef_invalid")
        }
    }


}