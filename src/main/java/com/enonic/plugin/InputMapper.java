package com.enonic.plugin;

import com.enonic.cms.api.client.model.GetBinaryParams;
import com.enonic.cms.api.client.model.content.*;
import com.enonic.plugin.util.ResponseMessage;
import org.apache.commons.lang.StringUtils;
import org.jdom.Attribute;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;
import org.jdom.xpath.XPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;


public class InputMapper {

    Logger LOG = LoggerFactory.getLogger(InputMapper.class);
    XMLOutputter xmlOutputter = new XMLOutputter(Format.getRawFormat());

    ExistingContentHandler existingContentHandler;
    ClientProvider clientProvider;

    public InputMapper(ClientProvider clientProvider, ExistingContentHandler existingContentHandler){
        this.existingContentHandler = existingContentHandler;
        this.clientProvider = clientProvider;
    }

    public Input getInput(MappingObjectHolder mappingObjectHolder) {
        if (MappingRules.hasSpecialHandling(mappingObjectHolder)) {
            ResponseMessage.addInfoMessage("Handling special input " + mappingObjectHolder.getInputMapping().getAttributeValue("dest") + " for contenttype " + mappingObjectHolder.getTargetContenttype().getName());
            ResponseMessage.addInfoMessage(mappingObjectHolder.toString());
            return MappingRules.getInput(mappingObjectHolder);
        } else if ("text".equals(mappingObjectHolder.getSourceInputType())) {
            return getTextInput(mappingObjectHolder);
        } else if ("url".equals(mappingObjectHolder.getSourceInputType())) {
            return getUrlInput(mappingObjectHolder);
        } else if ("checkbox".equals(mappingObjectHolder.getSourceInputType())) {
            return getBooleanInput(mappingObjectHolder);
        } else if ("radiobutton".equals(mappingObjectHolder.getSourceInputType())) {
            return getSelectorInput(mappingObjectHolder);
        } else if ("dropdown".equals(mappingObjectHolder.getSourceInputType())) {
            return getSelectorInput(mappingObjectHolder);
        } else if ("file".equals(mappingObjectHolder.getSourceInputType())) {
            return getFileInput(mappingObjectHolder);
        } else if ("files".equals(mappingObjectHolder.getSourceInputType())) {
            return getDeprecatedFilesInput(mappingObjectHolder);
        } else if ("uploadfile".equals(mappingObjectHolder.getSourceInputType())) {
            return getBinaryInput(mappingObjectHolder);
        } else if ("date".equals(mappingObjectHolder.getSourceInputType())) {
            return getDateInput(mappingObjectHolder);
        } else if ("textarea".equals(mappingObjectHolder.getSourceInputType())) {
            return getTextAreaInput(mappingObjectHolder);
        } else if ("htmlarea".equals(mappingObjectHolder.getSourceInputType())) {
            return getHTMLInput(mappingObjectHolder);
        } else if ("relatedcontent".equals(mappingObjectHolder.getSourceInputType())) {
            return getRelatedContentsInput(mappingObjectHolder);
        } else if ("image".equals(mappingObjectHolder.getSourceInputType())) {
            return getImageInput(mappingObjectHolder);
        }else{
            ResponseMessage.addWarningMessage(mappingObjectHolder.getSourceInputType() + " is not a valid input type, aborting..");
            return null;
        }
    }

    private Input getImageInput(MappingObjectHolder mappingObjectHolder) {
        ResponseMessage.addInfoMessage("Get image content " + mappingObjectHolder.getInputMappingSrc());
        Attribute imageKeyAttr = null;
        try {
            imageKeyAttr = (Attribute) XPath.selectSingleNode(mappingObjectHolder.getContentInputElement(), mappingObjectHolder.getInputMappingSrcXpath() + mappingObjectHolder.getInputMappingSrc() + "/@key");
        } catch (Exception e) {
            ResponseMessage.addErrorMessage("Error when getting image key from content");
        }
        if (imageKeyAttr == null) {
            return null;
        }

        Integer imageKey = null;

        try {
            imageKey = imageKeyAttr.getIntValue();
        } catch (Exception e) {
            ResponseMessage.addErrorMessage("Error. Image key har non-integer format");
            return null;
        }

        if (imageKey == null) {
            return null;
        }

        ResponseMessage.addInfoMessage("Old image key" + imageKey);
        Element existingMigratedContent = null;
        try {
            existingMigratedContent = existingContentHandler.getExistingMigratedContentOrCategory(imageKey, "image");
        } catch (Exception e) {
            ResponseMessage.addErrorMessage("Error when getting image related content, image key = " + imageKey);
        }
        if (existingMigratedContent == null) {
            return null;
        }

        Integer newKey = null;
        try {
            newKey = Integer.parseInt(((Element) XPath.selectSingleNode(existingMigratedContent, "contentdata/newkey")).getValue());
        } catch (Exception e) {
            ResponseMessage.addErrorMessage("Error when getting newKey from existing migrated content, key = " + newKey);
            return null;
        }

        if (newKey == null) {
            return null;
        }
        ResponseMessage.addInfoMessage("New image key" + newKey);
        return new ImageInput(mappingObjectHolder.getInputMappingDest(), newKey);
    }

    private Input getRelatedContentsInput(MappingObjectHolder mappingObjectHolder) {

        RelatedContentsInput relatedContentsInput = null;
        String multiple = mappingObjectHolder.getTargetInputElement().getAttributeValue("multiple");
        if ((multiple == null || "true".equals(multiple)) && "relatedcontent".equals(mappingObjectHolder.getTargetInputType())) {
            List<Element> relatedContentKeys = null;
            try {
                relatedContentKeys = XPath.selectNodes(mappingObjectHolder.getContentInputElement(), "content");
            } catch (JDOMException e) {
                ResponseMessage.addWarningMessage("Could not find relatedContents, " + mappingObjectHolder.toString());
            }

            if (relatedContentKeys == null || relatedContentKeys.isEmpty()) {
                return null;
            }

            relatedContentsInput = new RelatedContentsInput(mappingObjectHolder.getInputMappingDest());
            Iterator<Element> relatedContentKeysIt = relatedContentKeys.iterator();
            while (relatedContentKeysIt.hasNext()) {
                Attribute oldRelatedContentKey = relatedContentKeysIt.next().getAttribute("key");
                if (oldRelatedContentKey != null) {
                    Element existingMigratedContent = null;
                    try {
                        existingMigratedContent = existingContentHandler.getExistingMigratedContentOrCategory(Integer.parseInt(oldRelatedContentKey.getValue()), "relatedcontent");
                    } catch (Exception e) {
                        LOG.warn("Error while loogin for existing migrated content");
                    }
                    if (existingMigratedContent != null) {
                        Element newKeyEl = null;
                        try {
                            newKeyEl = ((Element) XPath.selectSingleNode(existingMigratedContent, "contentdata/newkey"));
                        } catch (Exception e) {
                            LOG.warn("Error while getting key from existing migrated content, key =  " + newKeyEl);
                        }
                        if (newKeyEl != null) {
                            relatedContentsInput.addRelatedContent(Integer.parseInt(newKeyEl.getValue()));
                        }
                    }
                }
            }
            if (relatedContentsInput.getRelatedContents().isEmpty()) {
                return null;
            }
            return relatedContentsInput;
        } else {
            Attribute relatedContentKeyAttr = null;
            try {
                relatedContentKeyAttr = (Attribute) XPath.selectSingleNode(mappingObjectHolder.getContentInputElement(), mappingObjectHolder.getInputMappingSrcXpath() + mappingObjectHolder.getInputMappingSrc() + "/@key");
            } catch (Exception e) {
                ResponseMessage.addWarningMessage("Error while getting relatedcontent");
            }
            if (relatedContentKeyAttr == null) {
                try {
                    relatedContentKeyAttr = (Attribute) XPath.selectSingleNode(mappingObjectHolder.getContentInputElement(), mappingObjectHolder.getInputMappingSrcXpath() + mappingObjectHolder.getInputMappingSrc() + "/content/@key");
                } catch (Exception e) {
                    ResponseMessage.addWarningMessage("Error while getting relatedcontent");
                }

            }
            if (relatedContentKeyAttr == null) {
                return null;
            }

            Integer relatedContentKey = null;

            try {
                relatedContentKey = relatedContentKeyAttr.getIntValue();
            } catch (Exception e) {
                ResponseMessage.addWarningMessage("Relatedcontentkey on wrong format, aborting migrating this relatedcontent");
                return null;
            }

            Element existingMigratedContent = null;
            try {
                existingMigratedContent = existingContentHandler.getExistingMigratedContentOrCategory(relatedContentKey, "content");
            } catch (Exception e) {
                ResponseMessage.addErrorMessage("Error when finding existing migrated related content");
                return null;
            }

            if (existingMigratedContent == null) {
                return null;
            }
            Element newKeyEl = null;
            try {
                newKeyEl = ((Element) XPath.selectSingleNode(existingMigratedContent, "contentdata/newkey"));
            } catch (Exception e) {
                ResponseMessage.addErrorMessage("Error when fetching existing migrated content newKey");
                return null;
            }

            if (newKeyEl == null) {
                return null;
            }

            //handle special case with conversion from relatedcontent to text
            if ("text".equals(mappingObjectHolder.getTargetInputType())) {
                mappingObjectHolder.setContentInputElement(existingMigratedContent);
                return getTextInput(mappingObjectHolder);
            }

            return new RelatedContentInput(mappingObjectHolder.getInputMappingDest(), Integer.parseInt(newKeyEl.getValue()));
        }
    }

    private Input getHTMLInput(MappingObjectHolder mappingObjectHolder) {

        if (mappingObjectHolder.getTargetInputType() == null) {
            return null;
        }

        if ("textarea".equalsIgnoreCase(mappingObjectHolder.getTargetInputType())) {
            LOG.info("to textarea ");
            return getTextAreaInput(mappingObjectHolder);
        }
        String html = xmlOutputter.outputString(mappingObjectHolder.getContentInputElement());

        return new HtmlAreaInput(mappingObjectHolder.getInputMappingDest(), html);
    }

    private Input getTextAreaInput(MappingObjectHolder mappingObjectHolder) {
        ResponseMessage.addInfoMessage(("Migrate textarea " + mappingObjectHolder.getInputMappingSrc()));

        if (mappingObjectHolder.getTargetInputType() == null) {
            return null;
        }

        if ("htmlarea".equalsIgnoreCase(mappingObjectHolder.getTargetInputType())) {
            ResponseMessage.addInfoMessage("to htmlarea ");
            return getHTMLInput(mappingObjectHolder);
        }
        //Convert from htmlarea to textarea
        if ("htmlarea".equalsIgnoreCase(mappingObjectHolder.getSourceInputType())) {
            return new TextAreaInput(mappingObjectHolder.getInputMappingDest(), StringUtils.trim(mappingObjectHolder.getContentInputElement().getValue()));
        }

        return new TextAreaInput(mappingObjectHolder.getInputMappingDest(), mappingObjectHolder.getContentInputElement().getValue());
    }

    private Input getDateInput(MappingObjectHolder mappingObjectHolder) {
        DateInput dateInput = null;
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(mappingObjectHolder.getContentInputElement().getValue());
            dateInput = new DateInput(mappingObjectHolder.getInputMappingDest(), date);
        } catch (Exception e) {
            ResponseMessage.addWarningMessage("Exception when parsing date for input " + mappingObjectHolder.toString() + "/" + mappingObjectHolder.getInputMappingDest());
        }
        return dateInput;
    }

    private Input getBinaryInput(MappingObjectHolder mappingObjectHolder) {
        BinaryInput binaryInput = null;
        Attribute binaryKeyAttr = null;
        try {
            binaryKeyAttr = (Attribute) XPath.selectSingleNode(mappingObjectHolder.getContentInputElement(), "binarydata/@key");
            if (binaryKeyAttr != null && binaryKeyAttr.getValue() != null) {
                Integer binaryKey = binaryKeyAttr.getIntValue();
                ResponseMessage.addInfoMessage("Found binarydata with source key " + binaryKey);

                GetBinaryParams getBinaryParams = new GetBinaryParams();
                getBinaryParams.binaryKey = binaryKey;
                Document binaryDoc = clientProvider.getTargetserverClient().getBinary(getBinaryParams);
                String fileName = ((Element) XPath.selectSingleNode(binaryDoc, "//filename")).getValue();
                String base64EncodedBinaryString = binaryDoc.getRootElement().getChild("data").getText().trim();

                LOG.info("mappingObjectHolder.getInputMappingDest() {}", mappingObjectHolder.getInputMappingDest());
                LOG.info("fileName {}", fileName);

                binaryInput = new BinaryInput(mappingObjectHolder.getInputMappingDest(), base64EncodedBinaryString.getBytes(), fileName);
            }
        } catch (Exception e) {
            ResponseMessage.addWarningMessage("Exception when trying to get binary content" + e);
        }
        return binaryInput;
    }

    private Input getDeprecatedFilesInput(MappingObjectHolder mappingObjectHolder) {
        DeprecatedFilesInput filesInput = new DeprecatedFilesInput(mappingObjectHolder.getInputMappingDest());
        Attribute fileKeyAttr = null;
        try {
            List<Attribute> fileKeys = XPath.selectNodes(mappingObjectHolder.getContentInputElement(), "file/@key");
            Iterator<Attribute> fileKeysIt = fileKeys.iterator();
            while (fileKeysIt.hasNext()) {
                Attribute fileKey = fileKeysIt.next();
                if (fileKey == null) {
                    ResponseMessage.addWarningMessage("File input key was null, ignoring");
                    continue;
                }
                Integer newKey = existingContentHandler.getExistingMigratedContentOrCategoryKey(fileKey.getIntValue(), "file");
                if (newKey != null) {
                    ResponseMessage.addInfoMessage("Found migrated file content, adding to migrated content");
                    filesInput.addFile(newKey);
                }
            }
            if (filesInput.getFiles() != null || !filesInput.getFiles().isEmpty()) {
                return null;
            }

        } catch (Exception e) {
            ResponseMessage.addErrorMessage("Error while handling DeprecatedFilesInput");
        }
        return filesInput;
    }

    private Input getFileInput(MappingObjectHolder mappingObjectHolder) {
        Attribute fileKeyAttr = null;
        Integer fileKey = null;
        try {
            fileKeyAttr = (Attribute) XPath.selectSingleNode(mappingObjectHolder.getContentInputElement(), "file/@key");
            fileKey = fileKeyAttr.getIntValue();
        } catch (Exception e) {
        }
        if (fileKeyAttr == null || fileKey == null) {
            ResponseMessage.addWarningMessage("File input key was null, ignoring");
            return null;
        }
        Integer newKey = null;
        try {
            newKey = existingContentHandler.getExistingMigratedContentOrCategoryKey(fileKey, "file");
        } catch (Exception e) {
            ResponseMessage.addErrorMessage("Error when trying to find existing content for file");
        }

        if (newKey == null) {
            return null;
        }

        ResponseMessage.addInfoMessage("Found migrated file content, adding to migrated content");
        return new FileInput(mappingObjectHolder.getInputMappingDest(), newKey);
    }

    private Input getSelectorInput(MappingObjectHolder mappingObjectHolder) {
        return new SelectorInput(mappingObjectHolder.getInputMappingDest(), mappingObjectHolder.getContentInputElement().getValue());
    }

    private Input getBooleanInput(MappingObjectHolder mappingObjectHolder) {
        return new BooleanInput(mappingObjectHolder.getInputMappingDest(), Boolean.parseBoolean(mappingObjectHolder.getContentInputElement().getValue()));
    }

    private Input getUrlInput(MappingObjectHolder mappingObjectHolder) {
        String url = mappingObjectHolder.getContentInputElement().getValue();

        try {
            URL checkUrl = new URL(url);
        }catch (MalformedURLException mue){
            url = null;
        }

        UrlInput input = new UrlInput(mappingObjectHolder.getInputMappingDest(), url);
        return input;
    }

    private Input getTextInput(MappingObjectHolder mappingObjectHolder) {
        if (mappingObjectHolder.getTargetInputType() == null) {
            return null;
        }

        if ("textarea".equalsIgnoreCase(mappingObjectHolder.getTargetInputType())) {
            ResponseMessage.addInfoMessage("to textarea ");
            return getTextAreaInput(mappingObjectHolder);
        }

        //Try to convert from text to url
        if ("url".equalsIgnoreCase(mappingObjectHolder.getTargetInputType())) {
            ResponseMessage.addInfoMessage("to url ");
            return getUrlInput(mappingObjectHolder);
        }

        //Handle rare case of conversion from relatedcontent to textarea. Requires existing relatedcontent to be set as content input element
        if ("relatedcontent".equalsIgnoreCase(mappingObjectHolder.getSourceInputType())) {
            ResponseMessage.addInfoMessage("Convert relatedcontent entry to text entry!");
            Element titleEl = null;
            try {
                titleEl = (Element) XPath.selectSingleNode(mappingObjectHolder.getContentInputElement(), "display-name");
            } catch (Exception e) {
                ResponseMessage.addErrorMessage("Error when converting relatedcontent to textinput");
                return null;
            }
            if (titleEl == null) {
                return null;
            }
            return new TextInput(mappingObjectHolder.getInputMappingDest(), titleEl.getValue());
        }

        return new TextInput(mappingObjectHolder.getInputMappingDest(), mappingObjectHolder.getContentInputElement().getValue());
    }
}
