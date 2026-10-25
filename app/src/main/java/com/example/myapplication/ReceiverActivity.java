package com.example.myapplication;

import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.myapplication.Transfer.TransferWebSocketServer;

import java.io.File;

public class ReceiverActivity extends AppCompatActivity{
    private static final String TAG = "ReceiverActivity";
    private static final int PORT = 8080;
    private TransferWebSocketServer server;
    @Override
    protected void onCreate(Bundle savedInstance){
        super.onCreate(savedInstance);

        TextView status = new TextView(this);
        status.setText(String.format(java.util.Locale.US, "Receiver starting on Port %d", PORT));
        status.setPadding(32, 32, 32, 32);
        setContentView(status);

        File outputDirectory = new File(getFilesDir(),"recieved");
        server = new TransferWebSocketServer(getApplicationContext(),PORT,outputDirectory.toPath());
        server.start();

        Log.i(TAG,"server started on port"+PORT);
    }

    @Override
    protected void onDestroy(){
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
