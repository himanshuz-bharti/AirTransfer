package com.example.myapplication.Transfer;


import android.content.Context;
import android.content.pm.ServiceInfo;
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
    public static final String RECIEVER_SERVICE_TYPE = "_airtransfer._tcp.";
    public static final String OFFER_SERVICE_TYPE = "_airoffer._tcp.";

    private final String serviceType;

    public interface Listener{
        void onStatus(String message);
        void onPeerFound(Peer peer);
        void onPeerLost(Peer peer);
        void onError(String message, int errorCode);
    }

    public static final class Peer{
        public final String serviceName;
        public final String hostAddress;
        public final int port;
        public final String sessionId;

        public Peer(String serviceName,String hostAddress,int port,String sessionId){
            this.serviceName = serviceName;
            this.hostAddress = hostAddress;
            this.port = port;
            this.sessionId = sessionId;
        }

        @Override
        public String toString(){
            return serviceName + "(" + hostAddress + ":" + port + ":" + sessionId +")" ;
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
    private boolean serviceRegistered;
    private boolean discoveryStarted;
    private String registeredServiceName;

    private WifiManager.MulticastLock multicastLock;

    public NSDTransferDiscovery(Context context, String serviceType, Listener listener){
        this.appContext = context.getApplicationContext();
        this.nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
        this.serviceType = serviceType;
        if(nsdManager == null){
            throw new IllegalStateException("Android NSD service is not available");
        }
    }

    public void startAdvertising(String deviceName,int port,String sessionId){
        if(advertisingRequested){
            Log.e(TAG,"An advertising is already requested");
            return;
        }
        if(sessionId==null || sessionId.isEmpty()){
            Log.e(TAG,"Cannpt advertise without a sessionId");
            return;
        }
        if(port<1 || port>65535){
            Log.e(TAG,"Invalid port received. Cannot execute startAdvertising");
            return;
        }
        advertisingRequested = true;
        String safeName = sanitizeName(deviceName);
        String shortSessionId = sessionId.substring(0, Math.min(8,sessionId.length()));
        String requestedServiceName = "Airtransfer-" +safeName + shortSessionId;

        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(requestedServiceName);
        serviceInfo.setServiceType(serviceType);
        serviceInfo.setAttribute("sid",sessionId);
        serviceInfo.setPort(port);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo nsdServiceInfo, int i) {
                serviceRegistered = false;
                advertisingRequested = false;
                postError("Registration advertisement failed", i);
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo nsdServiceInfo) {
                serviceRegistered = true;
                registeredServiceName = nsdServiceInfo.getServiceName();

                Log.i(TAG, "NSD_ADVERTISED name=" + registeredServiceName
                        + " type=" + nsdServiceInfo.getServiceType()
                        + " port=" + nsdServiceInfo.getPort());

                postStatus("registration advertisement done. Disocverable as "+registeredServiceName);
                if(!advertisingRequested){
                    unregisterRegisteredService();
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
        if(discoveryStarted){
            Log.e(TAG,"Discovery is already started");
            return;
        }

        resolvedPeers.clear();
        resolvingNames.clear();
        acquireMultiCastLock();

        NsdManager.DiscoveryListener newListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String s) {
                discoveryStarted = true;
                Log.i(TAG,"reciever discovery started");
                postStatus("Discovery started..");
            }

            @Override
            public void onDiscoveryStopped(String s) {
                discoveryStarted = false;
                Log.i(TAG,"reciever discovery stopped");
                postStatus("Discovery stopped..");
            }

            @Override
            public void onServiceFound(NsdServiceInfo nsdServiceInfo) {
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
                resolveService(nsdServiceInfo);
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
                    postPeerLost(oldPeer);
                }

            }

            @Override
            public void onStartDiscoveryFailed(String s, int i) {
                discoveryStarted = false;
                discoveryListener = null;
                releaseMulticastLock();
                Log.e(TAG,"Could not start the reciever discovery");
                postError("Could not start discovery",i);
            }

            @Override
            public void onStopDiscoveryFailed(String s, int i) {
                Log.e(TAG,"Could not stop the reciever discovery");
                postError("Could not stop discovery",i);
            }
        };

        discoveryListener = newListener;

        nsdManager.discoverServices(serviceType,NsdManager.PROTOCOL_DNS_SD,discoveryListener);
    }

    @SuppressWarnings("deprecation")
    private void resolveService(NsdServiceInfo discoveredserviceInfo){
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
                        byte[] sidBytes = nsdServiceInfo.getAttributes().get("sid");

                        String sessionId = sidBytes ==  null? null :
                                new String(sidBytes,StandardCharsets.UTF_8);

                        if(discoveryListener == null || name == null || sessionId == null){
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

                        Peer peer = new Peer(name,host.getHostAddress(),port,sessionId);
                        resolvedPeers.put(name,peer);
                        Log.i(TAG,"Peer found");
                        postPeerFound(peer);
                    }
                });
    }

    public void stopDiscovery(){
        NsdManager.DiscoveryListener oldListener = discoveryListener;
        discoveryListener = null;

        resolvingNames.clear();
        resolvedPeers.clear();

        if(oldListener!=null && discoveryStarted){
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

    private void postPeerFound(Peer peer){
        if(listener != null){
            mainHandler.post(()->listener.onPeerFound(peer));
        }
    }

    private void postPeerLost(Peer peer){
        if(listener != null){
            mainHandler.post(()->listener.onPeerLost(peer));
        }
    }

    private void postError(String message,int errorCode){
        if(listener != null){
            mainHandler.post(()->listener.onError(message,errorCode));
        }
    }
}
