package com.example.myapplication.Transfer;

import android.util.Log;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;

public class TransferWebSocketServer extends WebSocketServer{
    private WebSocket connectClient;
    private static String TAG = "TransferWebSocketServer";

    public TransferWebSocketServer(int port){
        super(new InetSocketAddress(port));
    }

    @Override
    public void onOpen(WebSocket conn,ClientHandshake handshake){
        connectClient = conn;
        Log.i(TAG,"Websocket client connected"+conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn,String message){
        Log.i(TAG,"Recieved:"+message);
    }

    @Override
    public void onClose(WebSocket conn,int code,String reason,boolean remote){
        if(connectClient == conn){
            connectClient=null;
        }
        Log.i(TAG,"websocket disconnected");
    }

    @Override
    public void onError(WebSocket conn,Exception ex){
        ex.printStackTrace();
    }

    @Override
    public void onStart(){
        System.out.println("Websocket server started");
    }

    public void sendMessage(String message){
        if(connectClient!=null && connectClient.isOpen()){
            connectClient.send(message);
        }
    }
}
