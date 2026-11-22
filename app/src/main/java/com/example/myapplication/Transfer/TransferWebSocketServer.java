package com.example.myapplication.Transfer;

import android.util.Log;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;


import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import java.io.InputStream;



public class TransferWebSocketServer extends WebSocketServer{
    private static String TAG = "TransferWebSocketServer";
    private static final long MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024;

    private final Path recieveDirectory;

    private final Context appContext;

    private OutputStream outputStream;
    private volatile WebSocket activeConnection;
    private volatile boolean transferCommitting;

    private String chunkTransferId;
    private long chunkFileSize;
    private long chunkExpectedFileSize;
    private int chunkIndex;

    private Path finalFile;
    private Path chunkFile;

    private Runnable serverStartedListener;
    private TransferCompletedListener transferCompletedListener;
    private ClientConnectionListener clientConnectionListener;

    public interface TransferCompletedListener{
        void onTransferCompleted(Uri uri,String mimeType,String diplayName);
    }

    public interface ClientConnectionListener {
        void onConnected(String remoteHost, int remotePort);
        void onDisconnected(String remoteHost, int remotePort);
        void onTransferActivity(long bytesReceived, long totalBytes);
        void onTransferCommitting();
        void onTransferCommitFailed(String reason);
    }

    public TransferWebSocketServer(Context appContext,int port, Path recieveDirectory){
        super(new InetSocketAddress(port));
        this.appContext = appContext.getApplicationContext();
        this.recieveDirectory = recieveDirectory;
    }

    @Override
    public synchronized void onOpen(WebSocket conn,ClientHandshake handshake){
        String remoteHost = remoteHost(conn);
        int remotePort = remotePort(conn);
        Log.i(TAG,"Websocket client connected " + remoteHost + ":" + remotePort);
        WebSocket previousConnection = activeConnection;
        if (previousConnection != null && previousConnection != conn
                && previousConnection.isOpen()) {
            if (outputStream != null || transferCommitting) {
                Log.w(TAG, "Rejecting additional WebSocket client while a transfer is active");
                conn.close(1008, "Receiver is already transferring an image");
                return;
            }

            // A stale NSD result or abandoned connection may occupy the receiver
            // without ever starting a transfer. Let the newer client take over.
            Log.i(TAG, "Replacing idle WebSocket connection from "
                    + remoteHost(previousConnection) + ":" + remotePort(previousConnection));
        }
        activeConnection = conn;
        if (previousConnection != null && previousConnection != conn
                && previousConnection.isOpen()) {
            previousConnection.close(1000, "Replaced by a new transfer connection");
        }
        if (clientConnectionListener != null) {
            clientConnectionListener.onConnected(remoteHost, remotePort);
        }
    }

    @Override
    public void onMessage(WebSocket conn,String message){
        if (conn != activeConnection) {
            conn.close(1008, "Not the active transfer session");
            return;
        }
        Log.i(TAG,"Recieved message from:"+conn.getRemoteSocketAddress()+
                "of size:" + message.length());

        try{
            String[] parts = message.split("\\|",-1);
            if (parts.length <= 0){
                Log.e(TAG,"Message sent is is invalid format");
                conn.send(TransferProtocol.ERROR + "|" + "Invalid message format");
                return;
            }

            String messageType = parts[0];
            switch(messageType){
                case TransferProtocol.START:
                    handleStartMessage(conn,parts);
                    break;
                case TransferProtocol.END:
                    handleEndMessage(conn,parts);
                    break;
                default:
                    conn.send(TransferProtocol.ERROR + "|" + "Invalid messageType");
                    break;
            }
        } catch(Exception e){
            Log.e(TAG,"Error occurred in Server in handling the message");
            abortTransfer();
            conn.send(TransferProtocol.ERROR + "|" + e.getMessage());
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message){
        if (conn != activeConnection) {
            conn.close(1008, "Not the active transfer session");
            return;
        }
        Log.i(TAG,"Recieved binary message from:"+conn.getRemoteSocketAddress()+
                "of size:" + message.remaining());

        try{
            if(outputStream == null){
                throw new IOException("No active transfer is undergoing as outputstream is null");
            }

            byte[] data = new byte[message.remaining()];
            message.get(data);

            if(chunkFileSize + data.length > MAX_FILE_SIZE){
                throw new IOException("File size exceeded the maximum limit");
            }
            if(chunkFileSize + data.length > chunkExpectedFileSize){
                throw new IOException("Received more data than the declared file size");
            }

            outputStream.write(data);
            chunkFileSize += data.length;
            notifyTransferActivity(chunkFileSize, chunkExpectedFileSize);

            String replyClient = TransferProtocol.START_OK + "|" + chunkTransferId + "|" + chunkIndex;
            conn.send(replyClient);

            chunkIndex++;

            Log.i(TAG,"Recieved chunk:"+ chunkIndex + "of size:" + data.length);

        } catch(IOException e){
            Log.e(TAG,"Error in saaving buffers recieved",e);
            conn.send(TransferProtocol.ERROR + "|" + e.getMessage());
            abortTransfer();
        }
    }

    @Override
    public void onClose(WebSocket conn,int code,String reason,boolean remote){
        Log.i(TAG, "WebSocket closed code=" + code + ", remote=" + remote
                + ", reason=" + reason + ", peer=" + remoteHost(conn) + ":" + remotePort(conn));
        if (conn == activeConnection) {
            activeConnection = null;
            if (!transferCommitting) {
                abortTransfer();
            }
            notifyClientDisconnected(conn);
        }
    }

    @Override
    public void onError(WebSocket conn,Exception e){
        Log.e(TAG, "WebSocket error for peer=" + remoteHost(conn) + ":" + remotePort(conn), e);
        if (conn == activeConnection) {
            activeConnection = null;
            if (!transferCommitting) {
                abortTransfer();
            }
            notifyClientDisconnected(conn);
        }
        e.printStackTrace();
    }

    @Override
    public void onStart(){
        Log.i(TAG,"Websocket server started on port ]:" + getPort());
        if(serverStartedListener != null){
            serverStartedListener.run();
        }
    }

    public boolean hasActiveConnection() {
        WebSocket connection = activeConnection;
        return connection != null && connection.isOpen();
    }

    public void setTransferCompletedListener(TransferCompletedListener listener){
        this.transferCompletedListener = listener;
    }

    public void setServerStartedListener(Runnable listener){
        this.serverStartedListener = listener;
    }

    public void setClientConnectionListener(ClientConnectionListener listener) {
        this.clientConnectionListener = listener;
    }

    private void notifyClientDisconnected(WebSocket conn) {
        if (clientConnectionListener != null) {
            clientConnectionListener.onDisconnected(remoteHost(conn), remotePort(conn));
        }
    }

    private void notifyTransferActivity(long bytesReceived, long totalBytes) {
        if (clientConnectionListener != null) {
            clientConnectionListener.onTransferActivity(bytesReceived, totalBytes);
        }
    }

    private void notifyTransferCommitting() {
        if (clientConnectionListener != null) {
            clientConnectionListener.onTransferCommitting();
        }
    }

    private void notifyTransferCommitFailed(String reason) {
        if (clientConnectionListener != null) {
            clientConnectionListener.onTransferCommitFailed(reason);
        }
    }

    private String remoteHost(WebSocket conn) {
        InetSocketAddress remote = remoteSocketAddress(conn);
        if (remote == null) {
            return "unknown address";
        }
        return remote.getAddress() == null
                ? remote.getHostString()
                : remote.getAddress().getHostAddress();
    }

    private int remotePort(WebSocket conn) {
        InetSocketAddress remote = remoteSocketAddress(conn);
        return remote == null ? -1 : remote.getPort();
    }

    private InetSocketAddress remoteSocketAddress(WebSocket conn) {
        if (conn == null) {
            return null;
        }
        SocketAddress socketAddress = conn.getRemoteSocketAddress();
        return socketAddress instanceof InetSocketAddress
                ? (InetSocketAddress) socketAddress
                : null;
    }
    private void handleStartMessage(WebSocket conn,String[] parts) throws IOException{
        try{
            if(parts == null || parts.length<4){
                throw new IOException("Invalid start message size");
            }

            if(chunkTransferId != null || outputStream != null){
                throw new IOException("outputstream is not null. A transfer already in progress");
            }

            String transferId = parts[1];
            String fileSize = parts[2];
            String encodedName = parts[3];

            long expectedFileSize;
            try {
                expectedFileSize = Long.parseLong(fileSize);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid file size numerical value: " + fileSize, e);
            }

            TransferFileMetadata fileMetaData = new TransferFileMetadata(encodedName, expectedFileSize, "image/jpg", transferId);

            if(!fileMetaData.validFileSize(MAX_FILE_SIZE) || expectedFileSize == 0L){
                throw new IOException("Invalid file size");
            }

            String decodedName = fileMetaData.decodeFileName();
            Log.i(TAG,"Decoded file:"+decodedName);

            Files.createDirectories(recieveDirectory);

            chunkFileSize = 0;
            chunkExpectedFileSize = expectedFileSize;
            chunkTransferId = transferId;
            chunkIndex = 0;

            String uniquePrefix = TransferFileMetadata.newtransferId();
            finalFile = recieveDirectory.resolve(uniquePrefix + "_" + decodedName);
            chunkFile = recieveDirectory.resolve(finalFile.getFileName().toString()+".parts");

            outputStream = Files.newOutputStream(chunkFile);

            Log.i(TAG,"Started Transfer:" + chunkTransferId + "for file:" +
                    decodedName + "of size:"+fileSize);

            String reply = TransferProtocol.START_OK + "|" + transferId;
            conn.send(reply);
            notifyTransferActivity(chunkFileSize, chunkExpectedFileSize);
        } catch(IOException e){
            Log.e(TAG,"Error in handong start message",e);
            abortTransfer();
            conn.send(TransferProtocol.ERROR + "|" + e.getMessage());
        }
    }

    private void handleEndMessage(WebSocket conn,String[] parts){
        boolean committing = false;
        try{
            if(parts == null || parts.length<3){
                throw new IOException("Invalid end message format");
            }

            String id = parts[1];
            Long finalSize = Long.parseLong(parts[2]);

            if(chunkTransferId == null || !chunkTransferId.equals(id)){
                throw new IOException("Invalid transfer ID");
            }

            if(chunkFileSize != finalSize || chunkFileSize != chunkExpectedFileSize){
                throw new IOException("Size mismatch");
            }

            if(outputStream != null){
                outputStream.flush();
                outputStream.close();
                outputStream = null;
            }

            String displayName = finalFile.getFileName().toString();
            notifyTransferCommitting();
            committing = true;
            transferCommitting = true;
            Uri finalImageUri = publishImageToPictures(chunkFile,displayName);
            try {
                Files.deleteIfExists(chunkFile);
            } catch (IOException cleanupError) {
                // The gallery image is already published; temporary-file cleanup must not
                // turn a successful image transfer into a failed protocol transaction.
                Log.w(TAG, "Could not remove completed temporary image", cleanupError);
            }
            Log.i(TAG,"Transfer completed" + chunkTransferId+
                    "to gallery uri:"+ finalImageUri + "of size:" + finalSize);

            // Record receiver-side success before acknowledging the sender. The sender can
            // close immediately after receiving DONE, which may trigger onClose before any
            // later completion callback and otherwise look like a failed receive.
            if(transferCompletedListener != null){
                String mimeType = appContext.getContentResolver().getType(finalImageUri);
                transferCompletedListener.onTransferCompleted(finalImageUri,
                        mimeType==null?"image/*":mimeType,displayName);
            }

            String endMessage = TransferProtocol.DONE + "|" + chunkTransferId + "|" + chunkFileSize;
            conn.send(endMessage);
        } catch(Exception e){
            Log.e(TAG,"error occured in handleEndMessage"+e);
            if (committing) {
                notifyTransferCommitFailed(e.getMessage());
            }
            conn.send(TransferProtocol.ERROR + "|" + e.getMessage());
        } finally {
            transferCommitting = false;
            abortTransfer();
        }
    }

    private void abortTransfer(){
        if(outputStream  != null){
            try{
                outputStream.close();
            }catch(IOException e){
                Log.e(TAG,"error in closing outputStream");
            }
            outputStream = null;
        }

        if (chunkFile != null) {
            try {
                Files.deleteIfExists(chunkFile);
            } catch (IOException e) {
                Log.w(TAG, "Could not delete incomplete transfer file", e);
            }
        }

        chunkTransferId = null;
        chunkFileSize = 0;
        chunkExpectedFileSize = 0;
        chunkIndex = 0;

        finalFile = null;
        chunkFile = null;
    }

    private Uri publishImageToPictures(Path sourceFile,String requestedName) throws IOException{
        String displayName = new File(requestedName).getName().replaceAll("[\\\\/:*?\"<>|]", "_");
        if(displayName.trim().isEmpty()){
            displayName = "recieved_name.png";
        }

        String extension = "";
        int dot = displayName.lastIndexOf('.');
        if(dot>=0 && dot<displayName.length()-1){
            extension = displayName.substring(dot+1).toLowerCase();
        }

        String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);

        if(mimeType == null || !mimeType.startsWith("image/")){
            mimeType = "image/jpeg";
        }

        ContentResolver resolver = appContext.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME,displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE,mimeType);
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES+"/AirTransferReciever");
        values.put(MediaStore.Images.Media.IS_PENDING,1);

        Uri imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);

        if(imageUri == null){
            throw new IOException("Error in creating imageUri");
        }
        boolean published = false;
        try{
            try(InputStream input = Files.newInputStream(sourceFile);
                OutputStream output = resolver.openOutputStream(imageUri);){
                if(input == null || output == null){
                    throw new IOException("inputstream or outputstream is null");
                }
                byte[] buffer = new byte[16*1024];
                int count;

                while((count = input.read(buffer))!=-1){
                    output.write(buffer,0,count);
                }
                output.flush();
            }

            ContentValues completed = new ContentValues();
            completed.put(MediaStore.Images.Media.IS_PENDING,0);

            int updated = resolver.update(imageUri,completed,null,null);
            if(updated<=0){
                throw new IOException("Could not publish the image at the location");
            }
            published = true;
            return imageUri;
        } finally {
            if(!published){
                resolver.delete(imageUri,null, null);
            }
        }
    }
}
