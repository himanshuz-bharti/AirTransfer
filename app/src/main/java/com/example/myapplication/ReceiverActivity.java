package com.example.myapplication;

import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.myapplication.Transfer.NSDTransferDiscovery;
import com.example.myapplication.Transfer.TransferWebSocketServer;

import java.io.File;

public class ReceiverActivity extends AppCompatActivity{
    private static final String TAG = "ReceiverActivity";
    private static final int PORT = 8080;
    private TransferWebSocketServer server;
    private NSDTransferDiscovery nsd;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstance){
        super.onCreate(savedInstance);

        status = new TextView(this);
        status.setText(String.format(java.util.Locale.US, "Receiver starting on Port %d", PORT));
        status.setPadding(32, 32, 32, 32);
        setContentView(status);

        nsd = new NSDTransferDiscovery(this,
                new NSDTransferDiscovery.Listener() {
                    @Override
                    public void onStatus(String message) {
                        status.setText(message);
                        Log.i(TAG,message);
                    }

                    @Override
                    public void onPeerFound(NSDTransferDiscovery.Peer peer) {
                        // since this is reciever it dont have to implement this
                    }

                    @Override
                    public void onPeerLost(NSDTransferDiscovery.Peer peer) {
                        // since this is reciever it dont have to implement this
                    }

                    @Override
                    public void onError(String message, int errorCode) {
                        status.setText(message + "(code:"+errorCode + ")");
                        Log.e(TAG,message + "; code = "+errorCode);
                    }
                });

        File outputDirectory = new File(getFilesDir(),"recieved");
        server = new TransferWebSocketServer(getApplicationContext(),PORT,outputDirectory.toPath());

        server.setServerStartedListener(()->{
            nsd.startAdvertising(Build.MODEL,PORT);
            runOnUiThread(()->{
                status.setText("Starting reciever advertising");
            });
        });
        server.start();

        Log.i(TAG,"server started on port"+PORT);
    }

    @Override
    protected void onDestroy(){
        if(nsd!=null){
            nsd.stopAll();
        }
        if(server!=null){
            try{
                server.stop(1000);
                Log.i(TAG,"Server successfully stopped");
            }catch(InterruptedException e){
                Log.e(TAG,"Error in stopping server"+e);
            }
        }
        super.onDestroy();
    }
}
