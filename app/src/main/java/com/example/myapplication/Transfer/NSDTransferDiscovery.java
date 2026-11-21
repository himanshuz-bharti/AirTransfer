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
import java.util.Map;
import java.util.Set;
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
    private final Map<String,Peer> resolvedPeers = new ConcurrentHashMap<>();
    private final Set<String> resolvingNames = ConcurrentHashMap.newKeySet();

    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;

    private volatile boolean advertisingRequested;
    private volatile boolean discoveryRequested;
    private boolean serviceRegistered;
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
        if(advertisingRequested){
            String reason = "An advertising is already requested";
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
        String requestedServiceName = "Airtransfer-" + safeName;

        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(requestedServiceName);
        serviceInfo.setServiceType(serviceType);
        serviceInfo.setAttribute("device",sanitizeDeviceName(deviceName));
        serviceInfo.setPort(port);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo nsdServiceInfo, int i) {
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
                serviceRegistered = true;
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
                serviceRegistered = false;
                registeredServiceName = null;
                postStatus("service unregistration."+registeredServiceName + "is no longer discoverable");
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo nsdServiceInfo, int i) {
                serviceRegistered = true;

                postStatus("service unregistered failed.Errorcode"+i);
            }
        };

        nsdManager.registerService(serviceInfo,NsdManager.PROTOCOL_DNS_SD,registrationListener);
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
                if (!discoveryRequested) {
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
                if (discoveryListener != null && discoveryListener != this) {
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
                if(name != null && name.equalsIgnoreCase(registeredServiceName)){
                    Log.i(TAG,"discovered service is equal to my advertised service");
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
                if (discoveryListener != null && discoveryListener != this) {
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
                        resolvingNames.remove(nsdServiceInfo.getServiceName());
                        Log.w(TAG,"Could not resolve the service name, error code"+i);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo nsdServiceInfo) {
                        String name = nsdServiceInfo.getServiceName();
                        resolvingNames.remove(name);
                        Map<String, byte[]> attributes = nsdServiceInfo.getAttributes();
                        byte[] deviceBytes = attributes == null ? null : attributes.get("device");

                        String deviceName = deviceBytes == null ? null :
                                new String(deviceBytes, StandardCharsets.UTF_8);
                        if (deviceName == null || deviceName.trim().isEmpty()) {
                            deviceName = name;
                        }

                        if(discoveryListener != sourceListener || name == null){
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
        if(registrationListener == null || !serviceRegistered){
            return;
        }

        try{
            nsdManager.unregisterService(registrationListener);
        } catch(IllegalArgumentException e){
            Log.e(TAG,"error in unregistering the registered service");
            serviceRegistered = false;
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
