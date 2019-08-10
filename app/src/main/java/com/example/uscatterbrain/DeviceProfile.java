package com.example.uscatterbrain;


import java.util.UUID;

/**
 * General device information and settings storage.
 * Used to refer to a device
 */

public class DeviceProfile {

    private UUID luid;

    public enum HardwareServices {
        WIFIP2P, BLUETOOTHCLASSIC,
        BLUETOOTHLE, ULTRASOUND
    }
    private HardwareServices services;

    private final byte protocolVersion = 2;

    public DeviceProfile (HardwareServices services, UUID id) {
        this.services = services;
        this.luid = id;
    }

    public void  update(HardwareServices services) {
        this.services = services;
    }

    public HardwareServices getServices() {
        return services;
    }

    public byte getProtocolVersion() {
        return protocolVersion;
    }

    public UUID getLUID(){ return this.luid;}

    public void setLUID(UUID id){this.luid = id;}
}
