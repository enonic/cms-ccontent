package com.enonic.plugin;


import com.google.common.base.Strings;
import org.jdom.Element;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;

public class MappingObjectHolder {
    XMLOutputter xmlOutputter = new XMLOutputter(Format.getPrettyFormat());


    private Element inputMapping;
    private Contenttype sourceContenttype;
    private Contenttype targetContenttype;
    private Element targetInputElement;
    private Element sourceInputElement;
    private Element contentInputElement;
    private String blockGroupBasePath = "";

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

    public Element getSourceInputElement() {return sourceInputElement;}

    public void setContentInputElement(Element contentInputElement) {
        this.contentInputElement = contentInputElement;
    }

    public Element getContentInputElement() {
        return contentInputElement;
    }

    public String toString() {
        try{
            return "\nsourceContentType:"+sourceContenttype.getName()+
                    "\ntargetContentType:"+targetContenttype.getName()+
                    "\ninputMapping.src:"+inputMapping.getAttributeValue("src")+
                    "\ninputMapping.dest:"+inputMapping.getAttributeValue("dest")+
                    "\ncontentInputElement.getValue():"+contentInputElement.getValue();
        }catch (Exception e){
            return "MappingObjectHolder.toString(): Values not initiated";
        }

    }

    public String getSourceInputType() {
        return getSourceInputElement().getAttributeValue("type");
    }
    public String getTargetInputType() {
        return getTargetInputElement().getAttributeValue("type");
    }
    public String getInputMappingSrc(){
        return getInputMapping().getAttributeValue("src");
    }
    public String getInputMappingDest(){
        return getInputMapping().getAttributeValue("dest");
    }

    public void setBlockGroupBasePath(String blockGroupBasePath) {
        this.blockGroupBasePath = blockGroupBasePath;
    }

    public String getInputMappingSrcXpath(){
        //Should be set set explicitly if f.eks. block groupd migration with base attribute
        if (!Strings.isNullOrEmpty(blockGroupBasePath)){
            return blockGroupBasePath+"/";
        }
        return "contentdata/";
    }
}
