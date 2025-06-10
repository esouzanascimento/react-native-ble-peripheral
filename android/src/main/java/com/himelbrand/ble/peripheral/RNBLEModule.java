package com.himelbrand.ble.peripheral;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.logging.Handler;
import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;

import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.modules.core.DeviceEventManagerModule;



/**
 * {@link NativeModule} that allows JS to open the default browser
 * for an url.
 */
public class RNBLEModule extends ReactContextBaseJavaModule{

    private static final long INITIAL_RETRY_DELAY = 2000;   // 2 seconds
    private static final long MAX_RETRY_DELAY = 30000;      // 30 seconds

    ReactApplicationContext reactContext;
    HashMap<String, BluetoothGattService> servicesMap;
    BluetoothDevice mBluetoothDevice;
    BluetoothManager mBluetoothManager;
    BluetoothAdapter mBluetoothAdapter;
    BluetoothGattServer mGattServer;
    BluetoothLeAdvertiser advertiser;
    AdvertiseCallback advertisingCallback;
    private long currentDelay = INITIAL_RETRY_DELAY;
    private Promise startPromise;
    String name;
    boolean advertising;
    private String invalidDeviceAddress = null;
    private Context context;
    private boolean serverIsReady = false;

    public RNBLEModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        this.context = reactContext;
        this.servicesMap = new HashMap<String, BluetoothGattService>();
        this.advertising = false;
        this.name = "RN_BLE";
    }

    @Override
    public String getName() {
        return "BLEPeripheral";
    }

    @ReactMethod
    public void setName(String name) {
        this.name = name;
        Log.i("RNBLEModule", "name set to " + name);
    }

    @ReactMethod
    public void addService(String uuid, Boolean primary) {
        UUID SERVICE_UUID = UUID.fromString(uuid);
        int type = primary ? BluetoothGattService.SERVICE_TYPE_PRIMARY : BluetoothGattService.SERVICE_TYPE_SECONDARY;
        BluetoothGattService tempService = new BluetoothGattService(SERVICE_UUID, type);
        if(!this.servicesMap.containsKey(uuid))
            this.servicesMap.put(uuid, tempService);
    }

    @ReactMethod
    public void addCharacteristicToService(String serviceUUID, String uuid, Integer permissions, Integer properties) {
        UUID CHAR_UUID = UUID.fromString(uuid);
        BluetoothGattCharacteristic tempChar = new BluetoothGattCharacteristic(CHAR_UUID, properties, permissions);

        if (this.servicesMap.containsKey(serviceUUID)) {
            this.servicesMap.get(serviceUUID).addCharacteristic(tempChar);
        } else {
            UUID SERVICE_UUID = UUID.fromString(serviceUUID);
            int type = BluetoothGattService.SERVICE_TYPE_SECONDARY;
    
            BluetoothGattService newService = new BluetoothGattService(SERVICE_UUID, type);
            newService.addCharacteristic(tempChar);
            // Secondary service must be included in a primary service
            if (this.servicesMap.isEmpty()) {
                Log.e("RNBLEModule", "Cannot add secondary service - no primary service exists");
                return;
            }
    
            // Get the FIRST primary service
            BluetoothGattService primaryService = null;
            for (BluetoothGattService service : this.servicesMap.values()) {
                if (service.getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY) {
                    primaryService = service;
                    break;
                }
            }
    
            if (primaryService != null) {
                // Add secondary service AS INCLUDED SERVICE to the primary
                primaryService.addService(newService);
            } else {
                Log.e("RNBLEModule", "No valid primary service found to include secondary service");
            }
        }
    }

    private void scheduleRetry() {
        // Use fully-qualified Handler to avoid abstract import
        new android.os.Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                currentDelay = Math.min(currentDelay * 2, MAX_RETRY_DELAY);
                startPeripheral();
            }
        }, currentDelay);
    }

    /**
     * Add a characteristic to a service. If the characteristic already exists, update its value.
     * @param serviceUUID The uuid of the service to add the characteristic to.
     * @param uuid The uuid of the characteristic to add.
     * @param permissions The permissions for the characteristic.
     * @param properties The properties for the characteristic.
     * @param value The value for the characteristic.
     */
    @ReactMethod
    public void addCharacteristicToServiceWithValue(String serviceUUID, String uuid, Integer permissions, Integer properties, String value) {
        UUID CHAR_UUID = UUID.fromString(uuid);
        if (this.servicesMap.containsKey(serviceUUID)) {
            BluetoothGattCharacteristic tempChar = this.servicesMap.get(serviceUUID).getCharacteristic(CHAR_UUID);
            if (tempChar == null) {
                tempChar = new BluetoothGattCharacteristic(CHAR_UUID, properties, permissions);
                this.servicesMap.get(serviceUUID).addCharacteristic(tempChar);
            }
            if (mGattServer != null) {
                BluetoothGattService service = mGattServer.getService(UUID.fromString(serviceUUID));
                if (service != null) {
                    BluetoothGattCharacteristic characteristic = service.getCharacteristic(CHAR_UUID);
                    if (characteristic != null) {
                        characteristic.setValue(value.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            tempChar.setValue(value.getBytes(StandardCharsets.UTF_8));
        } else {
            BluetoothGattService primaryService = null;
            for (BluetoothGattService service : this.servicesMap.values()) {
                if (service.getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY) {
                    primaryService = service;
                    break;
                }
            }
            
            if (primaryService != null) {
                UUID SERVICE_UUID = UUID.fromString(serviceUUID);
                int type = BluetoothGattService.SERVICE_TYPE_SECONDARY;

                List<BluetoothGattService> includedServices = primaryService.getIncludedServices();
                BluetoothGattService secondaryService = null;
                for (BluetoothGattService includedService : includedServices) {
                    if (includedService.getUuid().equals(SERVICE_UUID)) {
                        secondaryService = includedService;
                        break;
                    }
                }

                if (secondaryService != null) {
                    BluetoothGattCharacteristic tempChar = secondaryService.getCharacteristic(CHAR_UUID);
                    if (tempChar == null) {
                        tempChar = new BluetoothGattCharacteristic(CHAR_UUID, properties, permissions);
                        secondaryService.addCharacteristic(tempChar);
                    }
                    tempChar.setValue(value.getBytes(StandardCharsets.UTF_8));
                } else {
                    BluetoothGattService newService = new BluetoothGattService(SERVICE_UUID, type);
                    BluetoothGattCharacteristic tempChar = new BluetoothGattCharacteristic(CHAR_UUID, properties, permissions);
                    newService.addCharacteristic(tempChar);
                    tempChar.setValue(value.getBytes(StandardCharsets.UTF_8));
                    primaryService.addService(newService);
                }
            }
        }
    }

    private final BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, final int status, int newState) {
            super.onConnectionStateChange(device, status, newState);
            
            if (!serverIsReady) {
                invalidDeviceAddress = device.toString();
                return;
            }

            if (invalidDeviceAddress != null && invalidDeviceAddress == device.toString()) {
                return;
            }

            boolean connected = status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothGatt.STATE_CONNECTED;
            if (connected) {
                mBluetoothDevice = device;
            } else {
                mBluetoothDevice = null;
            }
            WritableMap map = Arguments.createMap();
            map.putString("connected", String.valueOf(connected));
            map.putString("device", device.toString());
            map.putString("invalidDeviceAddress", invalidDeviceAddress);
            reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit("BleStatusChangeEvent", map);
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
            if (offset != 0) {
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset,
                        /* value (optional) */ null);
                return;
            }
            mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,
                    offset, characteristic.getValue());
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            super.onNotificationSent(device, status);
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite,
                    responseNeeded, offset, value);
            characteristic.setValue(value);
            WritableMap map = Arguments.createMap();
            WritableArray data = Arguments.createArray();
            for (byte b : value) {
                data.pushInt((int) b);
            }
            map.putArray("data", data);
            map.putString("device", device.toString());
            if (responseNeeded) {
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
            map.putString("characteristicUUID", characteristic.getUuid().toString());
            reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit("WriteEvent", map);
        }
    };

    @ReactMethod
    public void reset() {
        if (mGattServer != null) {
            mGattServer.close();
            mGattServer = null;
        }
        mBluetoothDevice = null;
        servicesMap.clear();
        advertising = false;
        serverIsReady = false;
        if (advertiser != null && mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()) {
            advertiser.stopAdvertising(advertisingCallback);
            advertiser = null;
        }
        advertisingCallback = null;
    }

    @ReactMethod
    public void start(final Promise promise) {
        this.startPromise = promise;
        this.currentDelay = INITIAL_RETRY_DELAY;
        serverIsReady = false;
        startPeripheral();
    }

    private void startPeripheral() {
        BluetoothManager mgr = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (mgr == null) {
            scheduleRetry();
            return;
        }

        BluetoothAdapter adapter = mgr.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            scheduleRetry();
            return;
        }

        mBluetoothManager = mgr;
        mBluetoothAdapter = adapter;
        mBluetoothAdapter.setName(this.name);

        BluetoothGattServer gattServer = mgr.openGattServer(reactContext, mGattServerCallback);
        if (gattServer == null) {
            scheduleRetry();
            return;
        }

        mGattServer = gattServer;
        for (BluetoothGattService service : servicesMap.values()) {
            mGattServer.addService(service);
        }

        advertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            scheduleRetry();
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build();

        AdvertiseData.Builder dataBuilder = new AdvertiseData.Builder()
                .setIncludeDeviceName(true);
        List<ParcelUuid> primaryServiceUuids = new ArrayList<>();
        for (BluetoothGattService service : servicesMap.values()) {
            if (service.getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY) {
                primaryServiceUuids.add(new ParcelUuid(service.getUuid()));
            }
        }
        for (ParcelUuid uuid : primaryServiceUuids) {
            dataBuilder.addServiceUuid(uuid);
        }
        AdvertiseData data = dataBuilder.build();

        if (advertisingCallback == null) {
            advertisingCallback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    super.onStartSuccess(settingsInEffect);
                    serverIsReady = true;
                    advertising = true;
                    if (startPromise != null) {
                        startPromise.resolve("Success, Started Advertising");
                        startPromise = null;
                    }
                }

                @Override
                public void onStartFailure(int errorCode) {
                    super.onStartFailure(errorCode);
                    advertising = false;
                    if (startPromise != null) {
                        startPromise.reject("Advertising failure", "Error code: " + errorCode);
                        startPromise = null;
                    }
                }
            };
        }

        advertiser.startAdvertising(settings, data, advertisingCallback);
    }

    @ReactMethod
    public void stop(){
        if (mBluetoothAdapter !=null && mBluetoothAdapter.isEnabled() && advertiser != null) {
            // If stopAdvertising() gets called before close() a null
            // pointer exception is raised.
            advertiser.stopAdvertising(advertisingCallback);
        }
        advertising = false;
    }
    @ReactMethod
    public void sendNotificationToDevices(String serviceUUID,String charUUID,ReadableArray message) {
        byte[] decoded = new byte[message.size()];
        for (int i = 0; i < message.size(); i++) {
            decoded[i] = new Integer(message.getInt(i)).byteValue();
        }
        BluetoothGattCharacteristic characteristic = servicesMap.get(serviceUUID).getCharacteristic(UUID.fromString(charUUID));
        characteristic.setValue(decoded);
        boolean indicate = (characteristic.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_INDICATE)
                == BluetoothGattCharacteristic.PROPERTY_INDICATE;

        mGattServer.notifyCharacteristicChanged(mBluetoothDevice, characteristic, indicate);
    }
    @ReactMethod
    public void isAdvertising(Promise promise){
        promise.resolve(this.advertising);
    }

}
