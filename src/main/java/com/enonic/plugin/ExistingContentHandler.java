package com.enonic.plugin;


import com.enonic.cms.api.client.model.GetContentByCategoryParams;
import com.enonic.cms.api.plugin.PluginEnvironment;
import com.enonic.plugin.util.ResponseMessage;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.xpath.XPath;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

public class ExistingContentHandler {

    @Autowired
    PluginEnvironment pluginEnvironment;
    ClientProvider clientProvider = new ClientProvider();


    public Element getExistingMigratedContentOrCategory(Integer sourceKey, String type) throws Exception {
        try {
            Integer migratedContentCategoryKey = Integer.parseInt((String) pluginEnvironment.getSharedObject("context_migratedcontentcategory"));
            GetContentByCategoryParams getContentByCategoryParams1 = new GetContentByCategoryParams();
            getContentByCategoryParams1.categoryKeys = new int[]{migratedContentCategoryKey};
            Document document1 = null;
            if (type.equals("relatedcontent")) {//look for all kinds of relatedcontent
                String[] relatedContentTypes = new String[]{"content", "file", "image"};
                for (String relatedContentType : relatedContentTypes) {
                    getContentByCategoryParams1.query = "data/oldkey='" + sourceKey + "' and data/type='" + relatedContentType + "'";
                    document1 = clientProvider.getTargetserverClient().getContentByCategory(getContentByCategoryParams1);
                    if (XPath.selectSingleNode(document1, "contents/content") != null) {
                        ResponseMessage.addInfoMessage("Migrating one " + relatedContentType + " link from old key " + sourceKey);
                        break;
                    }
                }
            } else {
                getContentByCategoryParams1.query = "data/oldkey='" + sourceKey + "' and data/type='" + type + "'";
                document1 = clientProvider.getTargetserverClient().getContentByCategory(getContentByCategoryParams1);
            }


            Element existingContent = (Element) XPath.selectSingleNode(document1, "contents/content");
            if (existingContent == null) {
                return null;
            } else {
                return existingContent;
            }

        } catch (Exception e) {
            ResponseMessage.addErrorMessage("Exception while getting existing migratedContentOrCategory");
        }
        return null;
    }

    public Integer getExistingMigratedContentOrCategoryKey(Integer sourceKey, String type) throws Exception {
        try {

            Element existingMigratedContent = getExistingMigratedContentOrCategory(sourceKey, type);
            if (existingMigratedContent != null) {
                ResponseMessage.addInfoMessage("Found existing migrated content..");
                Element newKeyEl = ((Element) XPath.selectSingleNode(existingMigratedContent, "contentdata/newkey"));
                if (newKeyEl != null && newKeyEl.getValue() != null) {
                    Integer newKey = Integer.parseInt(newKeyEl.getValue());
                    ResponseMessage.addInfoMessage("..with key " + newKey);
                    return newKey;
                }
            } else {
                Integer replacementKey = Integer.parseInt(pluginEnvironment.getSharedObject("context_missing" + type + "key").toString());
                if (replacementKey != null) {
                    return replacementKey;
                }
            }
        } catch (Exception e) {
            ResponseMessage.addErrorMessage("Exception in getExistingMigratedContentOrCategoryKey");
        }
        return null;

    }

    public List<Element> getExistingMigratedContents(Integer sourceKey, String type) throws Exception {
        Integer migratedContentCategoryKey = Integer.parseInt((String) pluginEnvironment.getSharedObject("context_migratedcontentcategory"));
        GetContentByCategoryParams getContentByCategoryParams1 = new GetContentByCategoryParams();
        getContentByCategoryParams1.categoryKeys = new int[]{migratedContentCategoryKey};
        getContentByCategoryParams1.query = "data/oldkey='" + sourceKey + "' and data/type='" + type + "'";
        Document document1 = clientProvider.getTargetserverClient().getContentByCategory(getContentByCategoryParams1);

        List<Element> existingContent = XPath.selectNodes(document1, "contents/content");
        if (existingContent == null) {
            return null;
        } else {
            return existingContent;
        }
    }
}
