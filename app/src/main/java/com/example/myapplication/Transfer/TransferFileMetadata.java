package com.example.myapplication.Transfer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
public class TransferFileMetadata {

    private final String fileName;
    private final long fileSize;
    private final String mimeType;
    private final String transferId;

    public TransferFileMetadata(String fileName, long fileSize, String mimeType, String transferId) {
        if (fileName == null || fileName.isEmpty()) {
            throw new IllegalArgumentException("filename cannot be empty");
        }

        if (fileSize < 0) {
            throw new IllegalArgumentException("filesize must be >= 0");
        }

        if (transferId == null || transferId.isEmpty()) {
            throw new IllegalArgumentException("transferId cannot be emopty");
        }

        this.fileName = fileName;
        this.fileSize = fileSize;
        this.mimeType = mimeType;
        this.transferId = transferId;
    }

    public String getFileName(){
        return fileName;
    }

    public long getFileSize(){
        return fileSize;
    }

    public String getMimeType(){
        return mimeType;
    }

    public String getTransferId(){
        return transferId;
    }

    public String decodeFileName() {
        byte[] decoded = Base64.getUrlDecoder().decode(fileName);
        return new String(decoded, StandardCharsets.UTF_8);
    }

    public boolean validFileSize(long MAX_FILE_SIZE){
        return !(fileSize < 0 || fileSize > MAX_FILE_SIZE);
    }

    public static String newtransferId(){
        return UUID.randomUUID().toString();
    }

}
