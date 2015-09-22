package com.enonic.plugin.util;

/**
 * Created by rfo on 19/03/14.
 */
public class ResponseMessage {
    String messageType;
    String message;

    public ResponseMessage(){}

    public ResponseMessage(String message, String messageType){
        this.message = message;
        this.messageType = messageType;
    }

    public String getMessage(){
        return this.message;
    }
    public String getMessageType(){
        return this.messageType;
    }
}
