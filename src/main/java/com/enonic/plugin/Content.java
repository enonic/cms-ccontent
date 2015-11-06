package com.enonic.plugin;


import com.enonic.plugin.util.ResponseMessage;
import org.jdom.Attribute;
import org.jdom.DataConversionException;
import org.jdom.Element;
import org.jdom.xpath.XPath;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Content {

    public void parseContent(Element contentElement){
        try {
            status = contentElement.getAttribute("status").getIntValue();
            approved = contentElement.getAttribute("approved").getBooleanValue();
            key = contentElement.getAttribute("key").getIntValue();
        }catch (DataConversionException e){
            ResponseMessage.addWarningMessage("Source content is missing some common attributes that should exist!");
        }

        contenttype = contentElement.getAttributeValue("contenttype");
        displayName = contentElement.getChildText("display-name");

        try {
            publishfrom = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).parse(((Attribute) XPath.selectSingleNode(contentElement, "@publishfrom")).getValue());
        } catch (Exception e) {
            //Not nescessarily set. Don't throw exception
        }

        try {
            publishto = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).parse(((Attribute) XPath.selectSingleNode(contentElement, "@publishto")).getValue());
        } catch (Exception e) {
            //Not nescessarily set. Don't throw exception
        }

        try{
            modifierElement = (Element) XPath.selectSingleNode(contentElement, "modifier");
            modifierElement.setAttribute(new Attribute("timestamp", contentElement.getAttributeValue("timestamp")));
            modifierElement.setAttribute(new Attribute("publishfrom", contentElement.getAttributeValue("publishfrom")));
            modifierQN = modifierElement.getAttributeValue("qualified-name");
            modifierKey = modifierElement.getAttributeValue("key");
            modifierName = modifierElement.getChildText("name");
            modifierIsDeleted = modifierElement.getAttribute("deleted").getBooleanValue();
        }catch (Exception e){
            ResponseMessage.addWarningMessage("Error while getting modifier for original content");
        }
        try{
            ownerElement = (Element) XPath.selectSingleNode(contentElement, "owner");
            ownerElement.setAttribute(new Attribute("created", contentElement.getAttributeValue("created")));
            ownerQN = ownerElement.getAttributeValue("qualified-name");
            ownerKey = ownerElement.getAttributeValue("key");
            ownerName = ownerElement.getChildText("name");
            ownerIsDeleted = ownerElement.getAttribute("deleted").getBooleanValue();
        }catch (Exception e){
            ResponseMessage.addWarningMessage("Error while getting owner for original content");
        }

    }

    private String displayName;
    private Integer status;
    private Boolean approved;
    private String contenttype;
    private Date created;
    private Integer key;
    private Date publishfrom;
    private Date publishto;
    private Date timestamp;

    private Element modifierElement;
    private Element ownerElement;

    private String modifierQN = null;
    private String modifierName = null;
    private String modifierKey = null;
    private Boolean modifierIsDeleted = null;

    private String ownerKey = null;
    private String ownerQN = null;
    private String ownerName = null;
    private Boolean ownerIsDeleted = null;

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Boolean getApproved() {
        return approved;
    }

    public void setApproved(Boolean approved) {
        this.approved = approved;
    }

    public String getContenttype() {
        return contenttype;
    }

    public void setContenttype(String contenttype) {
        this.contenttype = contenttype;
    }

    public Date getCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    public Integer getKey() {
        return key;
    }

    public void setKey(Integer key) {
        this.key = key;
    }

    public Date getPublishfrom() {
        return publishfrom;
    }

    public void setPublishfrom(Date publishfrom) {
        this.publishfrom = publishfrom;
    }

    public Date getPublishto() {
        return publishto;
    }

    public void setPublishto(Date publishto) {
        this.publishto = publishto;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    public Element getModifierElement() {
        return modifierElement;
    }

    public void setModifierElement(Element modifierElement) {
        this.modifierElement = modifierElement;
    }

    public Element getOwnerElement() {
        return ownerElement;
    }

    public void setOwnerElement(Element ownerElement) {
        this.ownerElement = ownerElement;
    }

    public String getModifierQN() {
        return modifierQN;
    }

    public void setModifierQN(String modifierQN) {
        this.modifierQN = modifierQN;
    }

    public String getModifierName() {
        return modifierName;
    }

    public void setModifierName(String modifierName) {
        this.modifierName = modifierName;
    }

    public String getModifierKey() {
        return modifierKey;
    }

    public void setModifierKey(String modifierKey) {
        this.modifierKey = modifierKey;
    }

    public String getOwnerKey() {
        return ownerKey;
    }

    public void setOwnerKey(String ownerKey) {
        this.ownerKey = ownerKey;
    }

    public String getOwnerQN() {
        return ownerQN;
    }

    public void setOwnerQN(String ownerQN) {
        this.ownerQN = ownerQN;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public Boolean isModifierDeleted() {
        return modifierIsDeleted==null?true:modifierIsDeleted;
    }

    public void setModifierIsDeleted(Boolean modifierIsDeleted) {
        this.modifierIsDeleted = modifierIsDeleted;
    }

    public Boolean getOwnerIsDeleted() {
        return ownerIsDeleted==null?true:ownerIsDeleted;
    }

    public void setOwnerIsDeleted(Boolean ownerIsDeleted) {
        this.ownerIsDeleted = ownerIsDeleted;
    }
}
