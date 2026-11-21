package com.example.myapplication.Transfer;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.annotation.Nullable;

import org.java_websocket.client.WebSocketClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

public class TransferFileSender {

    private static final String TAG = "TransferFileSender";
    public static final int CHUNK_SIZE = 64*1024;

    private final TransferWebSocketClient webSocketClient;
    private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();


    public interface TransferCallback{
        void onProgress(long bytesSent,long totalBytes);
        void onSuccess();
        void onFailure(Exception e);
    }

    public TransferFileSender(@Nullable TransferWebSocketClient  webSocketClient){
        if(webSocketClient == null){
            throw new IllegalArgumentException("webSocketClient cannot be null");
        }
        this.webSocketClient = webSocketClient;
        Log.i(TAG,"File Sender created");
    }

    public void sendFile(ContentResolver resolver, Uri uri){
        sendFile(resolver,uri,null);

    }
    public void sendFile(ContentResolver resolver, Uri uri, @Nullable TransferCallback callback){
        if(resolver == null  || uri == null){
            throw new IllegalArgumentException("resolver or uri is null");
        }

        Log.i(TAG,"filesender sendFile called");

        executor.execute(()->{
            try{
                doSendFile(resolver,uri,callback);
                if(callback != null){
                    callback.onSuccess();
                }
            }catch(Exception e){
                Log.e(TAG, "File transfer failed", e);
                if(callback != null){
                    callback.onFailure(e);
                }
            }
        });
    }

    void doSendFile(ContentResolver resolver,Uri uri) throws Exception{
        doSendFile(resolver,uri,null);
    }

    private void doSendFile(
            ContentResolver resolver,
            Uri uri,
            @Nullable TransferCallback callback
    ) throws Exception {
        Log.i(TAG,"File Sender dosendfile called");
        if(!isOpen()){
            throw new IllegalArgumentException("websocket is not connected");
        }
        String filename = fileName(resolver,uri);
        Long fileSize = fileSize(resolver,uri);

        if(filename == null || filename.trim().isEmpty())  {
            throw new IllegalArgumentException("Filename is invalid");
        }
        if(fileSize == null || fileSize<=0){
            throw new IllegalArgumentException("Filesize is null");
        }

        String transferId = TransferFileMetadata.newtransferId();
        startTransfer(resolver,uri,filename,fileSize,transferId,callback);
    }

    private void startTransfer(
            ContentResolver resolver,
            Uri uri,
            String filename,
            Long fileSize,
            String transferId,
            @Nullable TransferCallback callback
    ) throws Exception {

        Log.i(TAG,"Start transfer called");
        String encodedName = encodeFilename(filename);

        //announce file
        String startmessage = TransferProtocol.START + "|" + transferId + "|" + fileSize + "|" + encodedName;
        webSocketClient.send(startmessage);

        String startexpectreply = TransferProtocol.START_OK + "|" + transferId;
        expectReply(startexpectreply);

        //stream file
        long totalsent = 0;
        long chunkidx = 0;

        try(InputStream input = resolver.openInputStream(uri)){
            if(input == null){
                throw new IOException("Cannot open selected file");
            }

            byte[] buffer = new byte[CHUNK_SIZE];
            int read;
            while((read = input.read(buffer))!=-1){
                if(!webSocketClient.isOpen()){
                    throw new IOException("websocketclient is not connected");
                }

                byte[] chunk = new byte[read];
                System.arraycopy(buffer,0,chunk,0,read);
                webSocketClient.send(chunk);

                String replyexpected = TransferProtocol.START_OK + "|" + transferId + "|" +chunkidx;
                expectReply(replyexpected);

                totalsent += read;
                chunkidx += 1;
                if (callback != null) {
                    callback.onProgress(totalsent, fileSize);
                }
            }
        }
        if(totalsent != fileSize){
            throw new IOException("File sent is" + fileSize + "but recieved " + totalsent);
        }

        String endmessage = TransferProtocol.END + "|" + transferId + "|" + totalsent;
        webSocketClient.send(endmessage);

        String endmessageexpected = TransferProtocol.DONE + "|" + transferId + "|" + totalsent;
        expectReply(endmessageexpected);
    }

    private void expectReply(String expected) throws Exception {
        String actual = replies.poll(30, TimeUnit.SECONDS);

        if(actual == null){
            throw new TimeoutException("Timed out waiting for:" + expected);
        }

        if(!expected.equals(actual)){
            throw new IOException("Unexpected reply. Expected:" + expected + "Actual:"+actual);
        }
    }



    private boolean isOpen(){
        Log.i(TAG,"file sender before isopen");
        return webSocketClient != null && webSocketClient.isOpen();
    }

    private String fileName(ContentResolver resolver,Uri uri){
        Log.i(TAG,"filename called");
        try(Cursor cursor = resolver.query(
                uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cursor!=null && cursor.moveToFirst()){
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if(idx>=0){
                    String name = cursor.getString(idx);
                    if(name!=null){
                        Log.i(TAG,"filename is:"+name);
                        return name;
                    }
                }
            }
        }
        return null;
    }

    private @Nullable Long fileSize(ContentResolver resolver, Uri uri){
        Log.i(TAG,"filesize called");
        try(Cursor cursor = resolver.query(
                uri,new String[]{OpenableColumns.SIZE},null,null,null)){
            if(cursor!=null && cursor.moveToFirst()){
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if(idx>=0){
                    Long size = cursor.getLong(idx);
                    Log.i(TAG,"filesize is:"+size);
                    return size;
                }
            }
        }
        return null;
    }

    private String encodeFilename(String name){
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(name.getBytes(StandardCharsets.UTF_8));
    }

    public void onTextMessage(String message){
        if(message != null){
            replies.offer(message);
        }
    }
    public void shutdown(){
        executor.shutdownNow();
    }
}
