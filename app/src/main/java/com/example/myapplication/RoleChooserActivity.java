package com.example.myapplication;


import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
public class RoleChooserActivity extends AppCompatActivity{

    @Override
    public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_role_chooser);

        findViewById(R.id.btnSender).setOnClickListener(view->{
            startActivity(new Intent(this,SenderDiscoveryActivity.class));
        });

        findViewById(R.id.btnReceiver).setOnClickListener(view->{
            startActivity(new Intent(this,ReceiverActivity.class));
        });


    }
}
