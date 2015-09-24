package com.enonic.plugin.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;


public class ResponseMessage {

    static List<ResponseMessage> responseMessages = new ArrayList<ResponseMessage>();
    static Logger LOG = LoggerFactory.getLogger(ResponseMessage.class);

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

    public static void addInfoMessage(String message) {
        addMessage(message, "info");
    }

    public static void addWarningMessage(String message) {
        addMessage(message, "warning");
    }

    public static void addErrorMessage(String message) {
        addMessage(message, "error");
    }

    public static void addMessage(String message, String messageType) {
        if ("error".equals(messageType.toLowerCase())) {
            LOG.error(message);
        } else if ("warning".equals(messageType.toLowerCase())) {
            LOG.warn(message);
        } else if ("info".equals(messageType.toLowerCase())) {
            LOG.info(message);
        }
        ResponseMessage responseMessage = new ResponseMessage(message, messageType);
        try {
            responseMessages.add(responseMessage);
        } catch (Exception e) {
            LOG.error("Error getting messages, and adding response message");
        }
    }

    public static List<ResponseMessage> getResponseMessages() {
        return responseMessages;
    }

    public static void clearResponseMessages() {
        responseMessages.clear();
    }
}
