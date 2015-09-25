package com.enonic.plugin;

import org.jdom.Element;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;

public class MigratedContent {

    String title;
    String type;

    Integer oldContentKey;
    Contenttype oldContenttype;
    Element oldOwnerXml;
    Element oldModifierXml;

    Contenttype newContenttype;
    Integer newContentKey;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Integer getOldContentKey() {
        return oldContentKey;
    }

    public void setOldContentKey(Integer oldContentKey) {
        this.oldContentKey = oldContentKey;
    }

    public Contenttype getOldContenttype() {
        return oldContenttype;
    }

    public void setOldContenttype(Contenttype oldContenttype) {
        this.oldContenttype = oldContenttype;
    }

    public String getOldOwnerXml() {
        if (oldOwnerXml==null){
            return "";
        }
        XMLOutputter xmlOutputter = new XMLOutputter(Format.getPrettyFormat());
        return xmlOutputter.outputString(oldOwnerXml);
    }

    public void setOldOwnerXml(Element oldOwnerXml) {
        this.oldOwnerXml = oldOwnerXml;
    }

    public String getOldModifierXml() {
        if (oldModifierXml==null){
            return "";
        }
        XMLOutputter xmlOutputter = new XMLOutputter(Format.getPrettyFormat());
        return xmlOutputter.outputString(oldModifierXml);
    }

    public void setOldModifierXml(Element oldModifierXml) {
        this.oldModifierXml = oldModifierXml;
    }

    public Contenttype getNewContenttype() {
        return newContenttype;
    }

    public void setNewContenttype(Contenttype newContenttype) {
        this.newContenttype = newContenttype;
    }

    public Integer getNewContentKey() {
        return newContentKey;
    }

    public void setNewContentKey(Integer newContentKey) {
        this.newContentKey = newContentKey;
    }
}
