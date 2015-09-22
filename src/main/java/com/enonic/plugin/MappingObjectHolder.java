package com.enonic.plugin;


import org.jdom.Element;

public class MappingObjectHolder {

    private Element inputMapping;
    private Contenttype sourceContenttype;
    private Contenttype targetContenttype;
    private Element targetInputElement;
    private Element sourceInputElement;
    private Element contentInputElement;

    public void setSourceContenttype(Contenttype sourceContenttype) {
        this.sourceContenttype = sourceContenttype;
    }

    public void setTargetContenttype(Contenttype targetContenttype) {
        this.targetContenttype = targetContenttype;
    }

    public void setTargetInputElement(Element targetInputElement) {
        this.targetInputElement = targetInputElement;
    }

    public void setSourceInputElement(Element sourceInputElement) {
        this.sourceInputElement = sourceInputElement;
    }

    public void setInputMapping(Element mappingElement) {
        this.inputMapping = mappingElement;
    }

    public Element getInputMapping() {
        return inputMapping;
    }

    public Contenttype getSourceContenttype() {
        return sourceContenttype;
    }

    public Contenttype getTargetContenttype() {
        return targetContenttype;
    }

    public Element getTargetInputElement() {
        return targetInputElement;
    }

    public Element getSourceInputElement() {
        return sourceInputElement;
    }

    public void setContentInputElement(Element contentInputElement) {
        this.contentInputElement = contentInputElement;
    }

    public Element getContentInputElement() {
        return contentInputElement;
    }

    public String toString(){
        return "\nsourceContentType:"+sourceContenttype.getName()+
                "\ntargetContentType:"+targetContenttype.getName()+
                "\ninputMapping.src:"+inputMapping.getAttributeValue("src")+
                "\ninputMapping.dest:"+inputMapping.getAttributeValue("dest")+
                "\ncontentInputElement.getValue():"+contentInputElement.getValue();
    }
}
