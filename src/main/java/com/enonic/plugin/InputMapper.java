package com.enonic.plugin;

import com.enonic.cms.api.client.ClientException;
import com.enonic.cms.api.client.RemoteClient;
import com.enonic.cms.api.client.model.ContentDataInputUpdateStrategy;
import com.enonic.cms.api.client.model.GetBinaryParams;
import com.enonic.cms.api.client.model.GetContentBinaryParams;
import com.enonic.cms.api.client.model.UpdateContentParams;
import com.enonic.cms.api.client.model.content.*;
import com.enonic.cms.api.plugin.PluginEnvironment;
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
    PluginEnvironment pluginEnvironment;

    public InputMapper(ClientProvider clientProvider, ExistingContentHandler existingContentHandler, PluginEnvironment pluginEnvironment){
        this.existingContentHandler = existingContentHandler;
        this.clientProvider = clientProvider;
        this.pluginEnvironment = pluginEnvironment;
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
            //String imageKeyXpath = mappingObjectHolder.getInputMappingSrcXpath() + mappingObjectHolder.getInputMappingSrc() + "/@key";
            imageKeyAttr = (Attribute) XPath.selectSingleNode(mappingObjectHolder.getContentInputElement(), "@key");
        } catch (Exception e) {
            ResponseMessage.addErrorMessage("Error when getting image key from content");
        }
        if (imageKeyAttr == null) {
            ResponseMessage.addErrorMessage("Error. Image key is null");
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
        Integer newKey = null;
        Element existingMigratedContent = null;
        try {
            existingMigratedContent = existingContentHandler.getExistingMigratedContentOrCategory(imageKey, "image");
            newKey = Integer.parseInt(((Element) XPath.selectSingleNode(existingMigratedContent, "contentdata/newkey")).getValue());
        } catch (Exception e) {
            ResponseMessage.addErrorMessage("Error when getting image related content, image key = " + imageKey);
        }

        if (newKey==null){
            try {
                String missingKey = pluginEnvironment.getSharedObject("context_missingimagekey").toString();
                newKey = Integer.parseInt(missingKey);
                if (newKey!=null){
                    ResponseMessage.addWarningMessage("Replacing image with default missing image key " + newKey);
                }
            }catch (Exception e){
                ResponseMessage.addWarningMessage("Error while replacing image with default missing image key");
            }
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

                    Integer newKey = null;

                    if (existingMigratedContent != null) {
                        Element newKeyEl = null;
                        try {
                            newKeyEl = ((Element) XPath.selectSingleNode(existingMigratedContent, "contentdata/newkey"));
                            newKey = Integer.parseInt(newKeyEl.getValue());
                        } catch (Exception e) {
                            LOG.warn("Error while getting key from existing migrated content, key =  " + newKeyEl);
                        }
                    }

                    //TODO: Not implemented. Code below will fail if there is a relatedcontent filter in the contenttype.
                    /*if (newKey==null){
                        try {
                            String missingKey = pluginEnvironment.getSharedObject("context_missingcontentkey").toString();
                            newKey = Integer.parseInt(missingKey);
                        }catch (Exception e){
                            ResponseMessage.addWarningMessage("Error while replaceing relatedcontent with default missing content key");
                        }
                    }*/

                    if (newKey != null) {
                        relatedContentsInput.addRelatedContent(newKey);
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
                relatedContentKeyAttr = (Attribute) XPath.selectSingleNode(mappingObjectHolder.getContentInputElement(), "@key");
            } catch (Exception e) {
                ResponseMessage.addWarningMessage("Error while getting relatedcontent");
            }
            if (relatedContentKeyAttr == null) {
                try {
                    relatedContentKeyAttr = (Attribute) XPath.selectSingleNode(mappingObjectHolder.getContentInputElement(), "/content/@key");
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
        Element htmlEl = mappingObjectHolder.getContentInputElement();

        List<Element> htmlElements = htmlEl.getChildren();

        if (htmlElements != null && !htmlElements.isEmpty()) {
            try {
                scanHtmlAreaForInternalLinks(htmlElements);
            } catch (Exception e) {
                ResponseMessage.addWarningMessage("Error when scanning htmlArea for internal links");
            }
        }
        String html = htmlElements!=null?xmlOutputter.outputString(htmlElements):xmlOutputter.outputString(htmlEl);
        return new HtmlAreaInput(mappingObjectHolder.getInputMappingDest(), html);
    }
    private void scanHtmlAreaForInternalLinks(List<Element> htmlElements) throws Exception {
        String[] internalLinks = new String[]{"image://", "content://", "file://", "attachment://", "page://"};
        char[] internalLinksPostfixes = new char[]{'?', '"'};
        Iterator<Element> htmlElementsIt = htmlElements.iterator();
        while (htmlElementsIt.hasNext()) {
            Element htmlEl = htmlElementsIt.next();
            List<Attribute> htmlElAttr = htmlEl.getAttributes();
            Iterator<Attribute> htmlElAttrIt = htmlElAttr.iterator();
            while (htmlElAttrIt.hasNext()) {
                Attribute htmlAttribute = htmlElAttrIt.next();
                String htmlAttributeValue = htmlAttribute.getValue();
                for (String internalLink : internalLinks) {
                    if (htmlAttributeValue.contains(internalLink)) {
                        try {
                            ResponseMessage.addInfoMessage(htmlAttributeValue + " contains " + internalLink);
                            int beginIndex = htmlAttributeValue.indexOf(internalLink) + internalLink.length();
                            int endIndex = StringUtils.indexOfAny(htmlAttributeValue, internalLinksPostfixes);
                            if (endIndex == -1) {//string cointains only id, no parameters
                                endIndex = htmlAttributeValue.length();
                            }
                            String guessedOldKey = htmlAttributeValue.substring(beginIndex, endIndex);
                            //TODO: Quickfix
                            if (guessedOldKey.contains("/")) {
                                guessedOldKey = guessedOldKey.replaceAll("/", "");
                            }

                            ResponseMessage.addInfoMessage("Guessed old key " + guessedOldKey);
                            if (StringUtils.isNumeric(guessedOldKey)) {
                                //check if content of type custom content, file, image exists
                                ResponseMessage.addInfoMessage("Check if content is migrated for " + internalLink.replace("://", ""));
                                Element existingMigratedContent = existingContentHandler.getExistingMigratedContentOrCategory(Integer.parseInt(guessedOldKey), internalLink.replace("://", ""));

                                if (existingMigratedContent == null && internalLink.contains("attachment")) {
                                    //TODO: Doing this because attachment links can be either images, files or content. Check if contentkeys are 100% unique across different types.
                                    existingMigratedContent = performExtraCheckForAttachments(guessedOldKey);
                                }
                                if (existingMigratedContent == null) {
                                    ResponseMessage.addWarningMessage("linked content not migrated. Replacing " + internalLink + " link with default 'missing key'");
                                    String typeOfInternalLink = internalLink.replace("://", "");
                                    String missingKey = pluginEnvironment.getSharedObject("context_missing" + typeOfInternalLink + "key").toString();
                                    htmlAttributeValue = htmlAttributeValue.replace(guessedOldKey, missingKey);
                                    htmlAttribute.setValue(htmlAttributeValue);
                                } else {
                                    ResponseMessage.addInfoMessage("Get new key from migrated content");
                                    String newKey = ((Element) XPath.selectSingleNode(existingMigratedContent, "//newkey")).getValue();
                                    htmlAttributeValue = htmlAttributeValue.replace(guessedOldKey, newKey);
                                    htmlAttribute.setValue(htmlAttributeValue);
                                    ResponseMessage.addInfoMessage("Replaced old key " + guessedOldKey + " with new key " + newKey);
                                }
                            } else {

                                ResponseMessage.addWarningMessage("linked content not migrated as no old key could be found in string");
                                //htmlAttribute.setValue(internalLink + pluginEnvironment.getSharedObject("context_missing" + internalLink.replace("://", "") + "key").toString());
                            }
                            break;
                        } catch (Exception e) {
                            ResponseMessage.addErrorMessage("Failed to replace internal link key" + e);
                        }
                    }

                }
            }
            scanHtmlAreaForInternalLinks(htmlEl.getChildren());
        }
    }

    private Element performExtraCheckForAttachments(String guessedOldKey) throws Exception {
        Element existingMigratedContent = existingContentHandler.getExistingMigratedContentOrCategory(Integer.parseInt(guessedOldKey), "file");
        if (existingMigratedContent == null) {
            existingMigratedContent = existingContentHandler.getExistingMigratedContentOrCategory(Integer.parseInt(guessedOldKey), "image");
        }
        if (existingMigratedContent == null) {
            existingMigratedContent = existingContentHandler.getExistingMigratedContentOrCategory(Integer.parseInt(guessedOldKey), "content");
        }
        return existingMigratedContent;
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


                Document binaryDoc = getBinaryWithFix4ClientExceptionWhenArchived(clientProvider.getTargetserverClient(), binaryKey, null);
                //clientProvider.getTargetserverClient().getBinary(getBinaryParams);
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

    private Document getBinaryWithFix4ClientExceptionWhenArchived(RemoteClient client, Integer binaryKey, Integer contentKey) {
        GetBinaryParams getBinaryParams = new GetBinaryParams();
        getBinaryParams.binaryKey = binaryKey;
        Document binary = null;
        try {
            binary = client.getBinary(getBinaryParams);
        }catch (ClientException cx){
            if (binary==null && cx.getMessage()!=null && cx.getMessage().contains("Attachment not found"));{
                ResponseMessage.addInfoMessage("Known bug encountered. A binary could not be fetched because it is archived");
                //TODO: Implement fix for this. Get contentkey and approve content, then get binary, then archive content
                /*
                //Approve binary content temporarily, so it can be fetched
                UpdateContentParams updateContentParams = new UpdateContentParams();
                updateContentParams.contentKey=getBinaryParams.binaryKey;
                updateContentParams.status=ContentStatus.STATUS_APPROVED;
                updateContentParams.publishFrom = new Date();
                updateContentParams.updateStrategy = ContentDataInputUpdateStrategy.REPLACE_NEW;
                client.updateContent(updateContentParams);
                //Fetch it
                binary = client.getBinary(getBinaryParams);
                //Set the status of content back to 'archived' and log result
                updateContentParams.status=ContentStatus.STATUS_ARCHIVED;
                client.updateContent(updateContentParams);
                if (binary!=null){
                    ResponseMessage.addInfoMessage("Applied fix for known bug with 'attachment not found'. Approved it; migrated it; re-archived it");
                }else{
                    ResponseMessage.addWarningMessage("Applied bug-fix code for 'attachment not found', but it is still null!");
                }*/
            }
        }
        return binary;
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
                if (newKey==null){
                    try {
                        String missingKey = pluginEnvironment.getSharedObject("context_missingfilekey").toString();
                        newKey = Integer.parseInt(missingKey);
                        if (newKey!=null){
                            ResponseMessage.addWarningMessage("Replacing file with default missing file key " + newKey);
                        }
                    }catch (Exception e){
                        ResponseMessage.addWarningMessage("Error while replacing file with default missing file key");
                    }
                }

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
            if (newKey!=null){
                ResponseMessage.addInfoMessage("Found migrated file content, adding to migrated content " + newKey);
            }
        } catch (Exception e) {
            ResponseMessage.addErrorMessage("Error when trying to find existing content for file");
        }

        if (newKey==null){
            try {
                String missingKey = pluginEnvironment.getSharedObject("context_missingfilekey").toString();
                newKey = Integer.parseInt(missingKey);
                if (newKey!=null){
                    ResponseMessage.addWarningMessage("Replacing file with default missing file key " + newKey);
                }
            }catch (Exception e){
                ResponseMessage.addWarningMessage("Error while replacing file with default missing file key");
            }
        }

        if (newKey == null) {
            return null;
        }

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
