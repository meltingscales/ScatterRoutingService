// ScatterbrainAPI.aidl
package com.example.uscatterbrain;
import com.example.uscatterbrain.API.ScatterMessage;
import com.example.uscatterbrain.API.Identity;

interface ScatterbrainAPI {

    List<ScatterMessage> getByApplication(String application);

    List<Identity> getIdentities();

    Identity getIdentityByFingerprint(in byte[] fingerprint);

    void sendMessage(in ScatterMessage message);

    void sendMessages(in List<ScatterMessage> messages);

    void startDiscovery();

    void stopDiscovery();
}