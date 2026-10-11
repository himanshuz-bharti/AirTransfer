package com.example.myapplication.Transfer;

import android.util.Log;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
public class TransferWebSocketClient extends WebSocketClient {

    private static String TAG = "TransferWebSocketClient";
    public interface Listener{
        void onConnected();
        void onDisconnected();
        void onError(Exception e);
        void onTextMessage(String message);
    }

    private final Listener listener;

    public TransferWebSocketClient(URI serverUri, Listener listener){
        super(serverUri);
        this.listener = listener;
    }

    @Override
    public void onOpen(ServerHandshake handshake){
        Log.i(TAG,"onOpen called in Client");
        if(listener != null){
            listener.onConnected();
        }
    }

    @Override
    public void onClose(int code,String reason,boolean remote){
        Log.i(TAG,"onClose called");
        if(listener != null){
            listener.onDisconnected();
        }
    }

    @Override
    public void onMessage(String message){
        Log.i(TAG,"Messahge recieved");
        if(listener != null){
            listener.onTextMessage(message);
        }
    }

    @Override
    public void onError(Exception ex){
        Log.i(TAG,"Onerror called");
        ex.printStackTrace();
        if(listener!= null){
            listener.onError(ex);
        }
    }

}
