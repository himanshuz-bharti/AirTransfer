package com.example.myapplication.Transfer;


import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NSDTransferDiscovery {
    private static final String TAG = "NSDTransferDiscovery";
    private static final long DISCOVERY_RETRY_DELAY_MS = 2000L;
    public static final String RECIEVER_SERVICE_TYPE = "_airtransfer._tcp.";
    public static final String OFFER_SERVICE_TYPE = "_airoffer._tcp.";

    private final String serviceType;

    public interface AdvertisingCallback{
        void onStarted(String actualServiceName);
        void onFailed(String reason);
    }
    public interface Listener{
        void onStatus(String message);
        void onPeerFound(Peer peer);
        void onPeerLost(Peer peer);
        void onError(String message, int errorCode);
    }

    public static final class Peer{
        public final String serviceName;
        public final String deviceName;
        public final String hostAddress;
        public final int port;

        public Peer(String serviceName,String deviceName,String hostAddress,int port){
            this.serviceName = serviceName;
            this.deviceName = deviceName;
            this.hostAddress = hostAddress;
            this.port = port;
        }

        @Override
        public String toString(){
            return deviceName + "(" + hostAddress + ":" + port +")" ;
        }
    }

    private final Context appContext;
    private final NsdManager nsdManager;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final String localInstanceId = UUID.randomUUID().toString();
    private final Map<String,Peer> resolvedPeers = new ConcurrentHashMap<>();
    private final Set<String> resolvingNames = ConcurrentHashMap.newKeySet();

    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;

    private volatile boolean advertisingRequested;
    private volatile boolean discoveryRequested;
    private boolean serviceRegistered;
    private boolean unregistrationRequested;
    private boolean discoveryStarted;
    private String registeredServiceName;

    private WifiManager.MulticastLock multicastLock;

    private final Runnable discoveryRetry = this::startDiscoveryInternal;

    public NSDTransferDiscovery(Context context, String serviceType, Listener listener){
        this.appContext = context.getApplicationContext();
        this.nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
        this.serviceType = serviceType;
        if(nsdManager == null){
            throw new IllegalStateException("Android NSD service is not available");
        }
    }

    public void startAdvertising(String deviceName,int port){
        startAdvertising(deviceName,port,null);
    }

    public void startAdvertising(String deviceName,int port,AdvertisingCallback callback){
        if(advertisingRequested || serviceRegistered){
            String reason = "An advertising is already requested or still unregistering";
            Log.e(TAG, reason);
            if(callback!=null){
                callback.onFailed(reason);
            }
            return;
        }
        if(port<1 || port>65535){
            String reason = "Invalid port received. Cannot execute startAdvertising";
            Log.e(TAG,reason);
            if(callback!=null){
                callback.onFailed(reason);
            }
            return;
        }
        advertisingRequested = true;
        String safeName = sanitizeName(deviceName);
        String advertisementId = UUID.randomUUID().toString().substring(0, 8);
        String requestedServiceName = "Airtransfer-" + safeName + "-" + advertisementId;

        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(requestedServiceName);
        serviceInfo.setServiceType(serviceType);
        serviceInfo.setAttribute("device",sanitizeDeviceName(deviceName));
        serviceInfo.setAttribute("instance", localInstanceId);
        serviceInfo.setPort(port);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo nsdServiceInfo, int i) {
                if (registrationListener != this) {
                    return;
                }
                serviceRegistered = false;
                advertisingRequested = false;
                String reason = "Registration advertisement failed" + i;
                postError("Registration advertisement failed", i);
                if(callback!=null){
                    callback.onFailed(reason);
                }
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo nsdServiceInfo) {
                if (registrationListener != this) {
                    Log.i(TAG, "Unregistering a late service registration from an old listener");
                    try {
                        nsdManager.unregisterService(this);
                    } catch (IllegalArgumentException ignored) {
                        Log.i(TAG, "Late service registration was already unregistered");
                    }
                    return;
                }
                serviceRegistered = true;
                unregistrationRequested = false;
                registeredServiceName = nsdServiceInfo.getServiceName();

                Log.i(TAG, "NSD_ADVERTISED name=" + registeredServiceName
                        + " type=" + nsdServiceInfo.getServiceType()
                        + " port=" + nsdServiceInfo.getPort());

                if(!advertisingRequested){
                    unregisterRegisteredService();
                    return;
                }
                postStatus("Discoverable as:"+registeredServiceName);
                if(callback!=null){
                    callback.onStarted(registeredServiceName);
                }
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo nsdServiceInfo) {
                if (registrationListener != this) {
                    return;
                }
                serviceRegistered = false;
                unregistrationRequested = false;
                registeredServiceName = null;
                postStatus("Service is no longer discoverable");
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo nsdServiceInfo, int i) {
                if (registrationListener != this) {
                    return;
                }
                serviceRegistered = true;
                unregistrationRequested = false;
                postStatus("Service unregistration failed. Error code " + i);
            }
        };

        try {
            nsdManager.registerService(serviceInfo,NsdManager.PROTOCOL_DNS_SD,registrationListener);
        } catch (RuntimeException error) {
            advertisingRequested = false;
            registrationListener = null;
            Log.e(TAG, "Could not request service registration", error);
            if (callback != null) {
                callback.onFailed("Could not request service registration: "
                        + error.getMessage());
            }
        }
    }

    public void startDiscovery(){
        discoveryRequested = true;
        mainHandler.removeCallbacks(discoveryRetry);
        startDiscoveryInternal();
    }

    private void startDiscoveryInternal() {
        if (!discoveryRequested || discoveryStarted || discoveryListener != null) {
            return;
        }

        resolvedPeers.clear();
        resolvingNames.clear();
        acquireMultiCastLock();

        NsdManager.DiscoveryListener newListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String s) {
                if (!discoveryRequested || discoveryListener != this) {
                    if (discoveryListener == this) {
                        discoveryListener = null;
                    }
                    try {
                        nsdManager.stopServiceDiscovery(this);
                    } catch (IllegalArgumentException ignored) {
                        Log.i(TAG, "Discovery was already stopped during startup");
                    }
                    return;
                }
                discoveryStarted = true;
                Log.i(TAG,"reciever discovery started");
                postStatus("Discovery started..");
            }

            @Override
            public void onDiscoveryStopped(String s) {
                if (discoveryListener != this) {
                    return;
                }
                discoveryStarted = false;
                if (discoveryListener == this) {
                    discoveryListener = null;
                }
                Log.i(TAG,"reciever discovery stopped");
                postStatus("Discovery stopped..");
                scheduleDiscoveryRetry();
            }

            @Override
            public void onServiceFound(NsdServiceInfo nsdServiceInfo) {
                if (!discoveryRequested || discoveryListener != this) {
                    return;
                }
                if(!serviceType.equalsIgnoreCase(nsdServiceInfo.getServiceType())){
                    Log.i(TAG,"the discovered service type is different than the expected service type");
                    return;
                }
                String name = nsdServiceInfo.getServiceName();
                if (isLocalService(nsdServiceInfo, name)) {
                    Log.i(TAG, "Ignoring this device's own advertised service: " + name);
                    return;
                }

                if(name == null || !resolvingNames.add(name)){
                    Log.i(TAG,"discovered service has already been added to resolvingNames");
                    return;
                }

                Log.i(TAG, "NSD_ADVERTISED name=" + nsdServiceInfo.getServiceName()
                        + " type=" + nsdServiceInfo.getServiceType()
                        + " port=" + nsdServiceInfo.getPort());

                Log.i(TAG,"Service found");
                postStatus("Found recievers, getting the addresses..");
                resolveService(nsdServiceInfo, this);
            }

            @Override
            public void onServiceLost(NsdServiceInfo nsdServiceInfo) {
                if (!discoveryRequested || discoveryListener != this) {
                    return;
                }
                String name = nsdServiceInfo.getServiceName();

                if(name == null){
                    Log.w(TAG,"Lost service name is null");
                    return;
                }

                resolvingNames.remove(name);
                Peer oldPeer = resolvedPeers.remove(name);
                if(oldPeer!=null){
                    postPeerLost(oldPeer, this);
                }

            }

            @Override
            public void onStartDiscoveryFailed(String s, int i) {
                if (discoveryListener != this) {
                    return;
                }
                discoveryStarted = false;
                if (discoveryListener == this) {
                    discoveryListener = null;
                }
                releaseMulticastLock();
                Log.e(TAG,"Could not start the reciever discovery");
                postError("Could not start discovery",i);
                scheduleDiscoveryRetry();
            }

            @Override
            public void onStopDiscoveryFailed(String s, int i) {
                if (discoveryListener != this) {
                    return;
                }
                Log.e(TAG,"Could not stop the reciever discovery");
                postError("Could not stop discovery",i);
            }
        };

        discoveryListener = newListener;

        try {
            nsdManager.discoverServices(serviceType,NsdManager.PROTOCOL_DNS_SD,discoveryListener);
        } catch (RuntimeException error) {
            discoveryListener = null;
            discoveryStarted = false;
            releaseMulticastLock();
            Log.e(TAG, "Could not request service discovery", error);
            postError("Could not start discovery", -1);
            scheduleDiscoveryRetry();
        }
    }

    private boolean isLocalService(NsdServiceInfo serviceInfo, String serviceName) {
        if (serviceName != null && registeredServiceName != null
                && serviceName.equalsIgnoreCase(registeredServiceName)) {
            return true;
        }

        Map<String, byte[]> attributes = serviceInfo.getAttributes();
        byte[] instanceBytes = attributes == null ? null : attributes.get("instance");
        if (instanceBytes != null
                && localInstanceId.equals(new String(instanceBytes, StandardCharsets.UTF_8))) {
            return true;
        }

        InetAddress host = serviceInfo.getHost();
        if (host != null) {
            try {
                if (NetworkInterface.getByInetAddress(host) != null) {
                    return true;
                }
            } catch (SocketException error) {
                Log.w(TAG, "Could not compare resolved NSD host to local interfaces", error);
            }
        }
        return false;
    }

    private void scheduleDiscoveryRetry() {
        if (discoveryRequested) {
            mainHandler.removeCallbacks(discoveryRetry);
            mainHandler.postDelayed(discoveryRetry, DISCOVERY_RETRY_DELAY_MS);
        }
    }

    @SuppressWarnings("deprecation")
    private void resolveService(
            NsdServiceInfo discoveredserviceInfo,
            NsdManager.DiscoveryListener sourceListener
    ){
        nsdManager.resolveService(discoveredserviceInfo,
                new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo nsdServiceInfo, int i) {
                        if (discoveryListener != sourceListener) {
                            return;
                        }
                        resolvingNames.remove(nsdServiceInfo.getServiceName());
                        Log.w(TAG,"Could not resolve the service name, error code"+i);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo nsdServiceInfo) {
                        if (discoveryListener != sourceListener) {
                            return;
                        }
                        String name = nsdServiceInfo.getServiceName();
                        resolvingNames.remove(name);
                        Map<String, byte[]> attributes = nsdServiceInfo.getAttributes();
                        byte[] deviceBytes = attributes == null ? null : attributes.get("device");

                        String deviceName = deviceBytes == null ? null :
                                new String(deviceBytes, StandardCharsets.UTF_8);
                        if (deviceName == null || deviceName.trim().isEmpty()) {
                            deviceName = name;
                        }

                        if(name == null){
                            return;
                        }
                        if (isLocalService(nsdServiceInfo, name)) {
                            Log.i(TAG, "Ignoring delayed resolution of this device's own service: "
                                    + name);
                            return;
                        }

                        InetAddress host = nsdServiceInfo.getHost();
                        int port = nsdServiceInfo.getPort();

                        if(host == null || port<=0){
                            Log.w(TAG,"Resolved service has invalid host or port");
                            return;
                        }

                        Log.i(TAG, "NSD_RESOLVED name=" + name
                                + " address=" + host.getHostAddress()
                                + " port=" + port);

                        Peer peer = new Peer(name,deviceName,host.getHostAddress(),port);
                        resolvedPeers.put(name,peer);
                        Log.i(TAG,"Peer found");
                        postPeerFound(peer, sourceListener);
                    }
                });
    }

    public void stopDiscovery(){
        discoveryRequested = false;
        mainHandler.removeCallbacks(discoveryRetry);
        NsdManager.DiscoveryListener oldListener = discoveryListener;
        discoveryListener = null;

        resolvingNames.clear();
        resolvedPeers.clear();

        if(oldListener!=null){
            try{
                nsdManager.stopServiceDiscovery(oldListener);
            } catch(IllegalArgumentException e){
                Log.w(TAG,"error in stopping the discovery");
            }
        }

        discoveryStarted = false;
        releaseMulticastLock();
    }

    public void stopAdvertising(){
        advertisingRequested = false;

        if(serviceRegistered){
            unregisterRegisteredService();
        }
    }

    public void stopAll(){
        stopAdvertising();
        stopDiscovery();
    }

    private void acquireMultiCastLock(){
        if(multicastLock != null && multicastLock.isHeld()){
            return;
        }
        WifiManager wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        if(wifiManager == null){
            Log.w(TAG,"The wifi manager is null");
            return;
        }
        multicastLock = wifiManager.createMulticastLock(TAG);
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();
    }

    private void releaseMulticastLock(){
        if(multicastLock != null && multicastLock.isHeld()){
            multicastLock.release();
        }
        multicastLock = null;
    }

    private String sanitizeName(String deviceName){
        if(deviceName == null || deviceName.trim().isEmpty()){
            deviceName="Android";
        }
        String safe = deviceName.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        return safe.length() > 30 ? safe.substring(0, 30) : safe;

    }

    private String sanitizeDeviceName(String deviceName) {
        if (deviceName == null || deviceName.trim().isEmpty()) {
            return "Android";
        }
        String safeName = deviceName.trim().replaceAll("[^A-Za-z0-9 _.-]", "");
        if (safeName.isEmpty()) {
            return "Android";
        }
        return safeName.length() > 80 ? safeName.substring(0, 80) : safeName;
    }

    private void unregisterRegisteredService(){
        if (registrationListener == null || !serviceRegistered || unregistrationRequested) {
            return;
        }

        unregistrationRequested = true;
        try{
            nsdManager.unregisterService(registrationListener);
        } catch(IllegalArgumentException e){
            Log.w(TAG, "Service was already unregistered or no longer registered", e);
            serviceRegistered = false;
            unregistrationRequested = false;
            registeredServiceName = null;
        }
    }

    private void postStatus(String message){
        if(listener != null){
            mainHandler.post(()->listener.onStatus(message));
        }
    }

    private void postPeerFound(Peer peer, NsdManager.DiscoveryListener sourceListener){
        if(listener != null){
            mainHandler.post(()->{
                if (discoveryListener == sourceListener) {
                    listener.onPeerFound(peer);
                }
            });
        }
    }

    private void postPeerLost(Peer peer, NsdManager.DiscoveryListener sourceListener){
        if(listener != null){
            mainHandler.post(()->{
                if (discoveryListener == sourceListener) {
                    listener.onPeerLost(peer);
                }
            });
        }
    }

    private void postError(String message,int errorCode){
        if(listener != null){
            mainHandler.post(()->listener.onError(message,errorCode));
        }
    }
}
