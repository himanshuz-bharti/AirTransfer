package com.example.myapplication;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
public class RecieveImageActivity extends AppCompatActivity {
    public static final String EXTRA_URI = "recieved_image_uri";
    public static final String EXTRA_MIME = "recieved_image_mime";
    public static final String EXTRA_NAME = "recieved_image_name";

    private Uri imageUri;
    private String mimeType;

    @Override
    protected void onCreate(Bundle savedInstance){
        super.onCreate(savedInstance);
        setContentView(R.layout.activity_recieved_image);

        ImageView imageView = findViewById(R.id.receivedImage);
        TextView title = findViewById(R.id.receivedImageTitle);
        Button openExternal = findViewById(R.id.openExternalButton);

        String uriValue = getIntent().getStringExtra(EXTRA_URI);
        if(uriValue == null){
            Toast.makeText(this,"Recieved Uri image is missing",Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        imageUri = Uri.parse(uriValue);
        mimeType = getIntent().getStringExtra(EXTRA_MIME);

        if(mimeType == null){
            mimeType = "image/*";
        }

        String name = getIntent().getStringExtra(EXTRA_NAME);
        title.setText(name == null ?"Image recieved":name);

        imageView.setImageURI(imageUri);
        openExternal.setOnClickListener(view -> openInAnotherApp());
    }

    private void openInAnotherApp(){
        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setDataAndType(imageUri,mimeType);
        viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try{
            startActivity(Intent.createChooser(viewIntent,"Open image with"));
        }catch (ActivityNotFoundException error) {
            Toast.makeText(this, "No compatible image app found.",
                    Toast.LENGTH_SHORT).show();
        }
    }
}
