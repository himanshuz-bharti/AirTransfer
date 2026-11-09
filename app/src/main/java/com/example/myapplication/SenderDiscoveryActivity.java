package com.example.myapplication;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.myapplication.Transfer.NSDTransferDiscovery;

import java.util.ArrayList;
public class SenderDiscoveryActivity extends AppCompatActivity{
    public final String TAG = "SenderDiscoveryActivity";
    public static final String EXTRA_PEER_HOST = "com.example.myapplication.EXTRA_PEER_HOST";
    public static final String EXTRA_PEER_PORT = "com.example.myapplication.EXTRA_PEER_PORT";
    public static final String EXTRA_PEER_NAME = "com.example.myapplication.EXTRA_PEER_NAME";

    private TextView status;
    private ArrayAdapter<NSDTransferDiscovery.Peer> adapter;
    private ArrayList<NSDTransferDiscovery.Peer> peers = new ArrayList<>();

    private NSDTransferDiscovery nsd;
    private Boolean peerSelected;

    @Override
    protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,24,24,24);

        status = new TextView(this);
        Log.i(TAG,"SenderDiscovery onCreate started");
        status.setText("Searching for recievers");

        root.addView(
                status,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        ListView peerList = new ListView(this);
        adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                peers
        );

        peerList.setAdapter(adapter);

        root.addView(
                peerList,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        );

        setContentView(root);

        peerList.setOnItemClickListener((parent,view,position,id)->{
            NSDTransferDiscovery.Peer peer = peers.get(position);
            peerSelected = true;

            nsd.stopAll();

            Intent intent = new Intent(
                    SenderDiscoveryActivity.this,
                    MainActivity.class
            );

            intent.putExtra(EXTRA_PEER_HOST,peer.hostAddress);
            intent.putExtra(EXTRA_PEER_NAME,peer.serviceName);
            intent.putExtra(EXTRA_PEER_PORT,peer.port);

            startActivity(intent);
            finish();
        });

        nsd = new NSDTransferDiscovery(getApplicationContext(),
                new NSDTransferDiscovery.Listener() {
                    @Override
                    public void onStatus(String message) {
                        Log.i(TAG,message);
                        status.setText(message);
                    }

                    @Override
                    public void onPeerFound(NSDTransferDiscovery.Peer peer) {

                        int existingIndex = findPeerIndex(peer.serviceName);
                        if(existingIndex>=0){
                            peers.set(existingIndex,peer);
                        }
                        else{
                            peers.add(peer);
                        }

                        adapter.notifyDataSetChanged();
                        Log.i(TAG,"Peer found" + peer.serviceName);
                        status.setText("Select a reciever");
                    }

                    @Override
                    public void onPeerLost(NSDTransferDiscovery.Peer peer) {
                        int exisitngIndex = findPeerIndex(peer.serviceName);

                        if(exisitngIndex>=0){
                            Log.i(TAG,"Peer removed" + peer.serviceName);
                            peers.remove(exisitngIndex);
                            adapter.notifyDataSetChanged();
                        }

                        if(peers.isEmpty()){
                            Log.i(TAG,"No reciever found, searching..");
                            status.setText("No reciever found, searching..");
                        }
                    }

                    @Override
                    public void onError(String message, int errorCode) {
                        Log.i(TAG,"Error occured in SenderDiscovery");
                        status.setText(message + "(code:"+errorCode+")");
                        Toast.makeText(SenderDiscoveryActivity.this,message,Toast.LENGTH_SHORT).show();
                    }
                });
        nsd.startDiscovery();
    }

    private int findPeerIndex(String name){
        if(name == null){
            return -1;
        }

        for(int i=0;i<peers.size();i++){
            if(peers.get(i).serviceName.equals(name)){
                return i;
            }
        }

        return -1;
    }

    @Override
    protected void onDestroy(){
        if(nsd != null){
            nsd.stopAll();
        }
        super.onDestroy();
    }

}
