package com.enonic.plugin;

import com.enonic.plugin.util.ResponseMessage;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;

import java.util.List;

public class MigratedContent {

    private String title;
    private String type;

    //These are used to create the contenttype migrated-content
    private Integer sourceContentKey;
    private Contenttype sourceContenttype;
    private Contenttype targetContenttype;
    private Integer targetContentKey;

    //These are helper fields for when creating the content
    private Document targetContenttypeDoc;
    private Document sourceContenttypeDoc;
    private Element sourceContentElement;
    private Integer targetCategoryKey;
    private Integer sourceCategoryKey;

    private Content sourceContent;
    private List<Element> inputElementMappings;
    private List<Element> blockGroupElementMappings;


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

    public Integer getSourceContentKey() {
        return sourceContentKey;
    }

    public void setSourceContentKey(Integer sourceContentKey) {
        this.sourceContentKey = sourceContentKey;
    }

    public Contenttype getSourceContenttype() {
        return sourceContenttype;
    }

    public void setSourceContenttype(Contenttype sourceContenttype) {
        this.sourceContenttype = sourceContenttype;
    }

    public String getSourceOwnerXml() {
        if (sourceContent == null || sourceContent.getOwnerElement() == null){
            return "";
        }
        XMLOutputter xmlOutputter = new XMLOutputter(Format.getPrettyFormat());
        return xmlOutputter.outputString(sourceContent.getOwnerElement());
    }

    public String getSourceModifierXml() {
        if (sourceContent == null || sourceContent.getModifierElement() == null){
            return "";
        }
        XMLOutputter xmlOutputter = new XMLOutputter(Format.getPrettyFormat());
        return xmlOutputter.outputString(sourceContent.getModifierElement());
    }

    public Contenttype getTargetContenttype() {
        return targetContenttype;
    }

    public void setTargetContenttype(Contenttype targetContenttype) {
        this.targetContenttype = targetContenttype;
    }

    public Integer getTargetContentKey() {
        return targetContentKey;
    }

    public void setTargetContentKey(Integer targetContentKey) {
        this.targetContentKey = targetContentKey;
    }

    public boolean isContenttypeMappingOk() {
        if (getSourceContenttype() == null || getTargetContenttype() == null || getSourceContenttype().getKey() == null || getTargetContenttype().getKey() == null) {
            return false;
        }
        return true;
    }

    public Document getSourceContenttypeDoc() {
        return sourceContenttypeDoc;
    }

    public void setSourceContenttypeDoc(Document sourceContenttypeDoc) {
        this.sourceContenttypeDoc = sourceContenttypeDoc;
    }

    public Document getTargetContenttypeDoc() {
        return targetContenttypeDoc;
    }

    public void setTargetContenttypeDoc(Document targetContenttypeDoc) {
        this.targetContenttypeDoc = targetContenttypeDoc;
    }

    public boolean isSourceAndTargetContenttypeDocOk() {
        if (getSourceContenttypeDoc() == null) {
            ResponseMessage.addErrorMessage("Source contenttype doc is null, aborting");
            return false;
        }
        if (getTargetContenttypeDoc() == null) {
            ResponseMessage.addErrorMessage("Target contenttype doc is null, aborting");
            return false;
        }
        return true;
    }

    public Element getSourceContentElement() {
        return sourceContentElement;
    }

    public void setSourceContentElement(Element sourceContentElement) {
        this.sourceContentElement = sourceContentElement;
    }

    public Integer getTargetCategoryKey() {
        return targetCategoryKey;
    }

    public void setTargetCategoryKey(Integer targetCategoryKey) {
        this.targetCategoryKey = targetCategoryKey;
    }

    public Integer getSourceCategoryKey() {
        return sourceCategoryKey;
    }

    public void setSourceCategoryKey(Integer sourceCategoryKey) {
        this.sourceCategoryKey = sourceCategoryKey;
    }

    public Content getSourceContent() {
        return sourceContent;
    }

    public void setSourceContent(Content sourceContent) {
        this.sourceContent = sourceContent;
    }

    public void setInputElementMappings(List<Element> inputElementMappings) {
        this.inputElementMappings = inputElementMappings;
    }

    public void setBlockGroupElementMappings(List<Element> blockGroupElementMappings) {
        this.blockGroupElementMappings = blockGroupElementMappings;
    }

    public List<Element> getInputElementMappings() {
        return inputElementMappings;
    }

    public List<Element> getBlockGroupElementMappings() {
        return blockGroupElementMappings;
    }
}
