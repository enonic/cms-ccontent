package com.enonic.plugin;

import com.enonic.cms.api.client.Client;
import com.enonic.cms.api.client.ClientException;
import com.enonic.cms.api.client.ClientFactory;
import com.enonic.cms.api.client.RemoteClient;
import com.enonic.cms.api.client.model.*;
import com.enonic.cms.api.client.model.content.*;
import com.enonic.cms.api.client.model.content.file.*;
import com.enonic.cms.api.client.model.content.image.*;
import com.enonic.cms.api.plugin.PluginEnvironment;
import com.enonic.cms.api.plugin.ext.http.HttpController;
import com.enonic.plugin.util.ResponseMessage;
import com.enonic.plugin.view.TemplateEngineProvider;
import com.google.common.base.Strings;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.codehaus.jackson.map.annotate.JsonSerialize;
import org.jdom.Attribute;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;
import org.jdom.xpath.XPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.WebContext;

import javax.net.ssl.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.*;

@Component
public class CopyContentController extends HttpController {

    Logger LOG = LoggerFactory.getLogger(getClass());

    HashMap<Contenttype, Contenttype> contenttypeMap = new HashMap<Contenttype, Contenttype>();
    List<Contenttype> sourceContenttypes = new ArrayList<Contenttype>();

    boolean overwriteWhenExistingMigratedContentIsModified = false;
    Category sourceCategory = null;
    Category targetCategory = null;

    boolean purgeMigratedContentBeforeCopy = false;
    boolean purgeTargetFolderBeforeCopy = false;
    boolean copyContent = true;

    boolean readyForCopying = false;
    int fileCopyProgressCounter = 0;
    boolean abortCopy = false;

    XMLOutputter xmlOutputter = new XMLOutputter(Format.getPrettyFormat());

    @Autowired
    PluginEnvironment pluginEnvironment;

    String url = "";

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TemplateEngineProvider templateEngineProvider;

    public CopyContentController() throws Exception {
        System.out.println("CopyContentController");
        setDisplayName("cContent - copy content between installations");
        setUrlPatterns(new String[]{"/site/[\\d]*/ccontent.*", "/admin/site/[\\d]*/ccontent.*"});
        setPriority(0);
        //disableSslVerification();
    }


    private void setupAuthentication() {

        String cmd = pluginEnvironment.getCurrentRequest().getParameter("cmd");

        if (!"setupAuthentication".equalsIgnoreCase(cmd)) {
            return;
        }

        String sourceserverUrl = null;
        String sourceserverUsername = null;
        String sourceserverPassword = null;

        String targetserverUrl = null;
        String targetserverUsername = null;
        String targetserverPassword = null;

        if (pluginEnvironment.getCurrentRequest().getParameter("sourceserverUrl") != null) {
            sourceserverUrl = pluginEnvironment.getCurrentRequest().getParameter("sourceserverUrl");
            pluginEnvironment.setSharedObject("context_sourceserverUrl", sourceserverUrl);
        }
        if (pluginEnvironment.getCurrentRequest().getParameter("sourceserverUsername") != null) {
            sourceserverUsername = pluginEnvironment.getCurrentRequest().getParameter("sourceserverUsername");
            pluginEnvironment.setSharedObject("context_sourceserverUsername", sourceserverUsername);
        }
        if (pluginEnvironment.getCurrentRequest().getParameter("sourceserverPassword") != null) {
            sourceserverPassword = pluginEnvironment.getCurrentRequest().getParameter("sourceserverPassword");
            pluginEnvironment.setSharedObject("context_sourceserverPassword", sourceserverPassword);
        }

        if (pluginEnvironment.getCurrentRequest().getParameter("targetserverUrl") != null) {
            targetserverUrl = pluginEnvironment.getCurrentRequest().getParameter("targetserverUrl");
            pluginEnvironment.setSharedObject("context_targetserverUrl", targetserverUrl);
        }

        if (pluginEnvironment.getCurrentRequest().getParameter("targetserverUsername") != null) {
            targetserverUsername = pluginEnvironment.getCurrentRequest().getParameter("targetserverUsername");
            pluginEnvironment.setSharedObject("context_targetserverUsername", targetserverUsername);
        }
        if (pluginEnvironment.getCurrentRequest().getParameter("targetserverPassword") != null) {
            targetserverPassword = pluginEnvironment.getCurrentRequest().getParameter("targetserverPassword");
            pluginEnvironment.setSharedObject("context_targetserverPassword", targetserverPassword);
        }

        try {
            Client remoteClient = ClientFactory.getRemoteClient(sourceserverUrl);
            remoteClient.logout();
            remoteClient.login(sourceserverUsername, sourceserverPassword);
            ResponseMessage.addInfoMessage("Authentication for remote client successful");
        } catch (Exception e) {
            ResponseMessage.addErrorMessage("Authentication for remote client failed");
        }

        try {
            Client localClient = ClientFactory.getRemoteClient(targetserverUrl);
            localClient.logout();
            localClient.login(targetserverUsername, targetserverPassword);
            ResponseMessage.addInfoMessage("Authentication for local client successful");
        } catch (Exception e) {
            ResponseMessage.addErrorMessage("Authentication for local client failed");
        }
    }

    @Override
    public void handleRequest(HttpServletRequest request, HttpServletResponse response) throws Exception {

        setupAuthentication();

        //Set parameters on context to make them available in Thymeleaf html view
        WebContext context = new WebContext(request, response, request.getSession().getServletContext());

        String requestPath = StringUtils.substringAfterLast(request.getRequestURI(), "/ccontent/");

        String methodName = resolveMethodNameFromRequestPath(requestPath);
        addRequestPathContext(methodName, context);
        if (methodName.contains("ajax")) {
            return;
        }

        String url = extractUrlFromRequest();

        setContextVariables(context);
        response.setContentType("text/html");
        context.setVariable("requestPath", requestPath);
        context.setVariable("url", url);
        context.setVariable("messages", ResponseMessage.getResponseMessages());

        try {
            templateEngineProvider.setApplicationContext(applicationContext);
            templateEngineProvider.get().process("index", context, response.getWriter());
        } catch (Exception e) {
            ResponseMessage.addErrorMessage("Error when rendring thymeleaf template");
            templateEngineProvider.get().process("errors/404", context, response.getWriter());
        }
        ResponseMessage.clearResponseMessages();

    }

    private String extractUrlFromRequest() {
        String pathTranslated = pluginEnvironment.getCurrentRequest().getPathTranslated();
        if (pathTranslated == null && pluginEnvironment.getCurrentRequest().getAttribute("javax.servlet.forward.request_uri") != null) {
            pathTranslated = (String) pluginEnvironment.getCurrentRequest().getAttribute("javax.servlet.forward.request_uri");
        }

        if (pathTranslated == null) {
            pathTranslated = pluginEnvironment.getCurrentRequest().getRequestURI();
        }
        if ("".equals(url) && pathTranslated != null) {
            String appServerUrl = StringUtils.substringBeforeLast(pluginEnvironment.getCurrentRequest().getRequestURL().toString(), "/ccontent");
            String pathWithForwardSlashes = pathTranslated.replaceAll("\\\\", "/");
            String pathBeforeSite = StringUtils.substringBefore(pathWithForwardSlashes, "/site");
            String adminJunctionName = StringUtils.substringAfterLast(pathBeforeSite, "/");
            url = StringUtils.replace(appServerUrl, "admin", adminJunctionName);
        }
        return url;
    }

    private void setContextVariables(WebContext context) {
        Set<String> contextVariableNames = pluginEnvironment.getSharedObjectNames("context_");
        Iterator<String> contextVariableNamesIt = contextVariableNames.iterator();
        while (contextVariableNamesIt.hasNext()) {
            String contextVariable = contextVariableNamesIt.next();
            String contextVariableName = StringUtils.substringAfter(contextVariable, "context_");
            context.setVariable(contextVariableName, pluginEnvironment.getSharedObject(contextVariable));
        }
        if (sourceCategory != null) {
            context.setVariable("sourcecategory", sourceCategory);
        }
        if (targetCategory != null) {
            context.setVariable("targetcategory", targetCategory);
        }
        if (contenttypeMap != null) {
            context.setVariable("contenttypemap", contenttypeMap);
        }
        if (sourceContenttypes != null) {
            context.setVariable("sourceContenttypes", sourceContenttypes);
        }

    }

    public void setupOthersettings(WebContext context) throws Exception {

        if (pluginEnvironment.getCurrentRequest().getParameter("missingpagekey") != null) {
            pluginEnvironment.setSharedObject("context_missingpagekey", pluginEnvironment.getCurrentRequest().getParameter("missingpagekey"));
        }
        if (pluginEnvironment.getCurrentRequest().getParameter("missingimagekey") != null) {
            pluginEnvironment.setSharedObject("context_missingimagekey", pluginEnvironment.getCurrentRequest().getParameter("missingimagekey"));
        }
        if (pluginEnvironment.getCurrentRequest().getParameter("missingcontentkey") != null) {
            pluginEnvironment.setSharedObject("context_missingcontentkey", pluginEnvironment.getCurrentRequest().getParameter("missingcontentkey"));
        }
        if (pluginEnvironment.getCurrentRequest().getParameter("missingattachmentkey") != null) {
            pluginEnvironment.setSharedObject("context_missingattachmentkey", pluginEnvironment.getCurrentRequest().getParameter("missingattachmentkey"));
        }
        if (pluginEnvironment.getCurrentRequest().getParameter("missingfilekey") != null) {
            pluginEnvironment.setSharedObject("context_missingfilekey", pluginEnvironment.getCurrentRequest().getParameter("missingfilekey"));
        }
    }

    public void setupSourceserver(WebContext context) throws Exception {
        String migratedContentCategory = null;

        if (pluginEnvironment.getCurrentRequest().getParameter("migratedcontentcategory") != null) {
            migratedContentCategory = pluginEnvironment.getCurrentRequest().getParameter("migratedcontentcategory");
            pluginEnvironment.setSharedObject("context_migratedcontentcategory", migratedContentCategory);
        }
        if (pluginEnvironment.getCurrentRequest().getParameter("purgeMigratedContentBeforeCopy") != null) {
            this.purgeMigratedContentBeforeCopy = Boolean.parseBoolean(pluginEnvironment.getCurrentRequest().getParameter("purgeMigratedContentBeforeCopy"));
            pluginEnvironment.setSharedObject("context_purgeMigratedContentBeforeCopy", purgeMigratedContentBeforeCopy);
        }
        if (pluginEnvironment.getCurrentRequest().getParameter("copyContent") != null) {
            this.copyContent = Boolean.parseBoolean(pluginEnvironment.getCurrentRequest().getParameter("copyContent"));
            pluginEnvironment.setSharedObject("context_copyContent", copyContent);
        }


    }

    public void setupSourcecategory(WebContext context) throws Exception {
        Integer categoryKey = null;
        if (pluginEnvironment.getCurrentRequest().getParameter("sourcecategorykey") != null) {
            categoryKey = Integer.parseInt(pluginEnvironment.getCurrentRequest().getParameter("sourcecategorykey").toString());
            sourceCategory = null;
        } else if (sourceCategory != null) {
            categoryKey = sourceCategory.getKey();
        }

        if (categoryKey != null) {
            sourceCategory = getCategoryFolder(categoryKey, getSourceserverClient(), true);
        }
        //New source category, clear contenttype map
        contenttypeMap.clear();
    }

    public void setupContenttypes(WebContext context) throws Exception {
        Integer sourceCategoryKey = sourceCategory.getKey();
        RemoteClient remoteClient = getSourceserverClient();

        Enumeration<String> parameterNames = pluginEnvironment.getCurrentRequest().getParameterNames();
        while (parameterNames.hasMoreElements()) {
            String parameterName = parameterNames.nextElement();
            if (parameterName.startsWith("targetcontenttypekey_")) {
                String parameterValue = pluginEnvironment.getCurrentRequest().getParameter(parameterName);
                if (Strings.isNullOrEmpty(parameterValue)) {
                    continue;
                }
                LOG.info("setupContenttypes for {} - {}", parameterName, parameterValue);

                Contenttype sourceContenttype = new Contenttype();

                sourceContenttype.setKey(Integer.parseInt(StringUtils.substringAfter(parameterName, "targetcontenttypekey_")));
                sourceContenttype = getContenttype(sourceContenttype.getKey(), null, remoteClient);
                if (sourceContenttype == null || sourceContenttype.getKey() == null || sourceContenttype.getName() == null) {
                    ResponseMessage.addWarningMessage("Source contenttype not correct!");
                }

                Contenttype targetContenttype = new Contenttype();

                if (StringUtils.isNumeric(parameterValue)) {
                    targetContenttype.setKey(Integer.parseInt(parameterValue));
                    targetContenttype = getContenttype(targetContenttype.getKey(), null, getTargetserverClient());
                } else {
                    targetContenttype.setName(parameterValue);
                    targetContenttype = getContenttype(null, targetContenttype.getName(), getTargetserverClient());
                }
                if (sourceContenttype != null && targetContenttype != null) {
                    LOG.info("Create mapping between {} and {}", sourceContenttype.getName(), targetContenttype.getName());
                    LOG.info("Create mapping between {} and {}", sourceContenttype.getKey(), targetContenttype.getKey());
                    addContenttypeKey(sourceContenttype, targetContenttype);
                } else {
                    ResponseMessage.addWarningMessage("Could not create contenttype mapping!");
                }

            }
        }

        if (sourceCategoryKey == null) {
            ResponseMessage.addWarningMessage("Source category key is not set!");
            return;
        }

        Category topCategory = sourceCategory;//getCategoryFolder(sourceCategoryKey, remoteClient, true);

        if (topCategory == null) {
            ResponseMessage.addWarningMessage("Given source category key yielded no results!");
            return;
        }
        addSourceContenttypeFromCategory(topCategory);
    }

    private void addSourceContenttypeFromCategory(Category category) throws Exception {

        if (!Strings.isNullOrEmpty(category.getContenttype()) || category.getContenttypeKey() != null) {
            Contenttype contenttype = getContenttype(category.getContenttypeKey(), category.getContenttype(), getSourceserverClient());
            if (!sourceContenttypes.contains(contenttype)) {
                sourceContenttypes.add(contenttype);
            }
        }
        if (category.categories != null && !category.categories.isEmpty()) {
            Iterator<Category> categoriesIt = category.categories.iterator();
            while (categoriesIt.hasNext()) {
                addSourceContenttypeFromCategory(categoriesIt.next());
            }
        }
    }

    private void addContenttypeKey(Contenttype sourceContenttype, Contenttype targetContenttype) throws Exception {
        if (sourceContenttype.getKey() == null) {
            return;
        }
        if (targetContenttype.getKey() == null) {
            return;
        }
        if (contenttypeMap.containsKey(sourceContenttype)) {
            contenttypeMap.remove(sourceContenttype);
        }
        contenttypeMap.put(sourceContenttype, targetContenttype);
    }

    private Document getContenttypeDoc(Integer contenttypeKey, String contenttypeName, Client client) throws Exception {
        GetContentTypeConfigXMLParams getContentTypeConfigXMLParams = new GetContentTypeConfigXMLParams();

        if (contenttypeKey == null && contenttypeName == null) {
            ResponseMessage.addWarningMessage("Returning null from getContenttypeDoc");
            return null;
        }

        if (contenttypeKey != null) {
            getContentTypeConfigXMLParams.key = contenttypeKey;
        } else if (contenttypeName != null) {
            getContentTypeConfigXMLParams.name = contenttypeName;
        }

        Document result = null;
        try {
            result = client.getContentTypeConfigXML(getContentTypeConfigXMLParams);
        } catch (Exception e) {
            LOG.info("Could not get contenttype by key {} or name {}", contenttypeKey, contenttypeName);
        }
        return result;
    }

    private Contenttype getContenttype(Integer contenttypeKey, String contenttypeName, Client client) throws Exception {

        Contenttype result = new Contenttype();
        GetContentByQueryParams getContentByQueryParams = new GetContentByQueryParams();
        getContentByQueryParams.includeData = false;
        getContentByQueryParams.count = 1;
        getContentByQueryParams.childrenLevel = 0;

        if (contenttypeName != null) {
            getContentByQueryParams.query = "contenttype like '" + contenttypeName + "'";
        } else if (contenttypeKey != null) {
            getContentByQueryParams.query = "contenttypekey=" + contenttypeKey;
        }

        Document resultDoc = client.getContentByQuery(getContentByQueryParams);
        //Helper.prettyPrint(resultDoc);

        /*if (resultDoc == null || XPath.selectSingleNode(resultDoc, "contents/content") == null) {
            ResponseMessage.addWarningMessage("Contenttype " + contenttypeName);
            return null;
        }else{

        }*/
        try {
            Integer key = ((Attribute) XPath.selectSingleNode(resultDoc, "contents/content/@contenttypekey")).getIntValue();
            String name = ((Attribute) XPath.selectSingleNode(resultDoc, "contents/content/@contenttype")).getValue();

            result.setKey(key);
            result.setName(name);
        } catch (NullPointerException e) {
            ResponseMessage.addWarningMessage("No content exists of contenttype name:" + contenttypeName + " key:" + contenttypeKey + ". This is a prerequisite for creating a mapping!");
        }

        return result;
    }


    public void setupTargetcategory(WebContext context) throws Exception {

        Integer categoryKey = null;
        if (pluginEnvironment.getCurrentRequest().getParameter("targetcategorykey") != null) {
            categoryKey = Integer.parseInt(pluginEnvironment.getCurrentRequest().getParameter("targetcategorykey").toString());
            targetCategory = null;
        } else if (targetCategory != null) {
            categoryKey = targetCategory.getKey();
        }

        if (categoryKey != null) {
            targetCategory = getCategoryFolder(categoryKey, getTargetserverClient(), true);
        }
        if (pluginEnvironment.getCurrentRequest().getParameter("purgeTargetFolderBeforeCopy") != null) {
            this.purgeTargetFolderBeforeCopy = Boolean.parseBoolean(pluginEnvironment.getCurrentRequest().getParameter("purgeTargetFolderBeforeCopy"));
        }
        LOG.info("Set purgeTargetFolderBeforeCopy to {} in pluginenvironment", purgeTargetFolderBeforeCopy);
        pluginEnvironment.setSharedObject("context_purgeTargetFolderBeforeCopy", purgeTargetFolderBeforeCopy);
    }


    private Category getCategoryFolder(Integer categoryKey, Client client, boolean recursive) throws Exception {

        Category resultCategory = new Category();

        GetCategoriesParams getCategoriesParams = new GetCategoriesParams();
        getCategoriesParams.categoryKey = categoryKey;
        getCategoriesParams.includeTopCategory = true;
        getCategoriesParams.includeContentCount = true;
        getCategoriesParams.levels = recursive ? 0 : 1;

        Document result = client.getCategories(getCategoriesParams);

        if (result == null) {
            return null;
        }

        String totalContentCount = "-1";
        Attribute totalContentcountAttr = (Attribute) XPath.selectSingleNode(result, "categories/category/@totalcontentcount");
        if (totalContentcountAttr != null) {
            totalContentCount = totalContentcountAttr.getValue();
        }
        List<Element> categoriesEl = recursive ? XPath.selectNodes(result, "categories//category") : XPath.selectNodes(result, "categories/category");
        int pos = 0;

        Iterator<Element> categoriesElIt = categoriesEl.iterator();
        while (categoriesElIt.hasNext()) {
            Element categoryEl = categoriesElIt.next();
            Category category = new Category();
            category.setKey(((Attribute) XPath.selectSingleNode(categoryEl, "@key")).getIntValue());
            category.setTitle(((Element) XPath.selectSingleNode(categoryEl, "title")).getText());

            Attribute superKeyAttr = (Attribute) XPath.selectSingleNode(categoryEl, "@superkey");
            if (superKeyAttr != null) {
                category.setSuperKey(superKeyAttr.getIntValue());
            }
            Attribute contenttypeKeyAttr = (Attribute) XPath.selectSingleNode(categoryEl, "@contenttypekey");
            if (contenttypeKeyAttr != null) {
                category.setContenttypeKey(contenttypeKeyAttr.getIntValue());
            }
            Attribute contentCountAttribute = (Attribute) XPath.selectSingleNode(categoryEl, "@contentcount");
            if (contentCountAttribute != null) {
                category.setContentCount(contentCountAttribute.getIntValue());
            }

            Attribute totalCountAttribute = (Attribute) XPath.selectSingleNode(categoryEl, "@totalcontentcount");
            if (totalCountAttribute != null) {
                category.setTotalContentCount(totalCountAttribute.getIntValue());
            }

            //Cannot get contenttype name from getCategories method in API, so we need to get one content from category to get contenttype name...
            GetContentByCategoryParams getContentByCategoryParams = new GetContentByCategoryParams();
            getContentByCategoryParams.categoryKeys = new int[]{category.getKey()};
            getContentByCategoryParams.levels = 1;
            getContentByCategoryParams.includeData = false;
            getContentByCategoryParams.count = 1;
            getContentByCategoryParams.includeUserRights = false;
            getContentByCategoryParams.childrenLevel = 0;
            getContentByCategoryParams.includeOfflineContent = true;
            Document content = client.getContentByCategory(getContentByCategoryParams);
            if (content != null) {
                Attribute contenttypeAttribute = (Attribute) XPath.selectSingleNode(content, "contents/content/@contenttype");
                if (contenttypeAttribute != null) {
                    category.setContenttype(contenttypeAttribute.getValue());
                }
            }
            if (pos == 0) {
                resultCategory = category;
            } else {
                resultCategory.categories.add(category);
            }
            pos++;
        }
        return resultCategory;
    }

    public void ajaxstatus(WebContext context) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(SerializationConfig.Feature.INDENT_OUTPUT, true);
        mapper.setSerializationInclusion(JsonSerialize.Inclusion.NON_NULL);
        mapper.writerWithDefaultPrettyPrinter();
        ResponseMessage.addMessage("" + fileCopyProgressCounter, "progress");
        try {
            mapper.writeValue(context.getHttpServletResponse().getOutputStream(), ResponseMessage.getResponseMessages());
        }catch (Exception e){
            LOG.error("Error when creating log output", e);
        }
        ResponseMessage.clearResponseMessages();
    }

    public void copy(WebContext context) throws Exception {
        return;
    }

    public void ajaxstartcopy(WebContext context) throws Exception {
        abortCopy = false;
        fileCopyProgressCounter = 0;

        if (purgeTargetFolderBeforeCopy) {
            purgeTargetCategory();
        }

        if (purgeMigratedContentBeforeCopy) {
            Integer migratedContentCategoryKey = Integer.parseInt((String) pluginEnvironment.getSharedObject("context_migratedcontentcategory"));
            if (migratedContentCategoryKey != null) {
                deleteContentInCategory(migratedContentCategoryKey);
            }
        }
        if (sourceCategory != null && targetCategory != null && targetCategory.getKey() != null) {
            ResponseMessage.addInfoMessage("1. Create categories");
            createCategories(sourceCategory, targetCategory.getKey());
        } else {
            ResponseMessage.addInfoMessage("Source and target category are not set correctly! Cancelling..");
        }
    }

    public void ajaxabortcopy(WebContext context) throws Exception {
        abortCopy = true;
    }


    private void purgeTargetCategory() throws Exception {

        Iterator<Category> targetCategories = targetCategory.categories.iterator();
        if (!targetCategories.hasNext()) {
            ResponseMessage.addInfoMessage("Target category " + targetCategory.getTitle() + " has no subfolders");
        }
        ResponseMessage.addInfoMessage("Delete " + targetCategory.categories.size() + " categories");
        while (targetCategories.hasNext()) {
            Category category = targetCategories.next();
            ResponseMessage.addInfoMessage("Target category name, key = " + targetCategory.getTitle() + " " + targetCategory.getKey());
            ResponseMessage.addInfoMessage("SubCategory name, superkey = " + category.getTitle() + " " + category.getSuperKey());

            if (category.getSuperKey() == null || category.getSuperKey() != null && category.getSuperKey().intValue() == targetCategory.getKey().intValue()) {
                Integer categoryKey = category.getKey();
                deleteCategory(categoryKey);
                ResponseMessage.addInfoMessage("Deleted target category " + category.getTitle());
            }
        }
    }

    private void deleteCategory(Integer categoryKey) {
        DeleteCategoryParams deleteCategoryParams = new DeleteCategoryParams();
        deleteCategoryParams.recursive = true;
        deleteCategoryParams.includeContent = true;
        deleteCategoryParams.key = categoryKey;
        try {
            getTargetserverClient().deleteCategory(deleteCategoryParams);
        } catch (ClientException ce) {
            //TODO Find a smooth fix for this. Needed because superKey is sometimes null, solution might be to add levels to categories and only delete level 1
            LOG.info("Category already deleted recursively" + ce);
        }
    }

    private void deleteContentInCategory(Integer categoryKey) throws Exception {
        GetContentByCategoryParams getContentByCategoryParams = new GetContentByCategoryParams();
        getContentByCategoryParams.categoryKeys = new int[]{categoryKey};
        getContentByCategoryParams.includeOfflineContent = true;
        Document categoryContent = getTargetserverClient().getContentByCategory(getContentByCategoryParams);
        List<Element> categoryContentList = XPath.selectNodes(categoryContent, "//content");
        if (categoryContentList != null && !categoryContentList.isEmpty()) {
            DeleteContentParams deleteContentParams = new DeleteContentParams();
            Iterator<Element> categoryContentListIt = categoryContentList.iterator();
            while (categoryContentListIt.hasNext()) {
                Element contentEl = categoryContentListIt.next();
                deleteContentParams.contentKey = Integer.parseInt(contentEl.getAttributeValue("key"));
                try {
                    getTargetserverClient().deleteContent(deleteContentParams);
                } catch (Exception e) {
                    ResponseMessage.addWarningMessage("Could not delete mmigrated-content ");
                }
            }
        }
    }

    private void createCategories(Category category, Integer parentCategoryKey) throws Exception {
        Contenttype sourceContenttype = null;
        Contenttype targetContenttype = null;

        RemoteClient sourceserverClient = getSourceserverClient();

        if (category != null && (category.getContenttype() != null || category.getContenttypeKey() != null)) {
            sourceContenttype = getContenttype(category.getContenttypeKey(), category.getContenttype(), sourceserverClient);

            if (sourceContenttype != null) {
                targetContenttype = contenttypeMap.get(sourceContenttype);
                if (targetContenttype == null) {
                    ResponseMessage.addErrorMessage("No valid mapping exists for contenttype " + sourceContenttype.getName() + " (" + sourceContenttype.getKey() + "). Will skip content creation.");
                } else {
                    ResponseMessage.addInfoMessage("Source '" + category.getTitle() + "' (" + category.getContenttype() + ", " + category.getContenttypeKey() + "), Target: '" + category.getTitle() + "' (" + targetContenttype.getName() + ", " + targetContenttype.getKey() + ")");
                }
            } else {
                ResponseMessage.addInfoMessage("Source contenttype is null for category, will not create content");
            }
        } else {
            ResponseMessage.addWarningMessage("No contenttype mapping for category");
        }

        Integer targetCategoryKey = null;
        ResponseMessage.addInfoMessage("Check if category is already migrated");
        Element existingCategory = getExistingContentHandler().getExistingMigratedContentOrCategory(category.getKey(), "category");

        if (existingCategory == null) {
            ResponseMessage.addInfoMessage("Create category " + category.getTitle() + " from skratch");
            CreateCategoryParams createCategoryParams = new CreateCategoryParams();
            createCategoryParams.name = category.getTitle();

            if (targetContenttype != null && targetContenttype.getKey() != null) {
                createCategoryParams.contentTypeKey = targetContenttype.getKey();
            }
            ResponseMessage.addInfoMessage("Create new category under parent category key " + parentCategoryKey);
            createCategoryParams.parentCategoryKey = parentCategoryKey;

            try {
                targetCategoryKey = getTargetserverClient().createCategory(createCategoryParams);
                ResponseMessage.addInfoMessage("Created category " + category.getTitle() + " with id " + targetCategoryKey);

                MigratedContent migratedContent = new MigratedContent();
                migratedContent.setTitle(category.getTitle());
                migratedContent.setType("category");
                migratedContent.setOldContentKey(category.getKey());
                migratedContent.setOldContenttype(sourceContenttype);
                migratedContent.setNewContenttype(targetContenttype);
                migratedContent.setNewContentKey(targetCategoryKey);
                createMigratedContent(migratedContent);
                ResponseMessage.addInfoMessage("Created migrated-content contenttype for category");

            } catch (Exception e) {
                ResponseMessage.addErrorMessage("Exception while creating category");
            }
        } else {
            ResponseMessage.addInfoMessage("Get previously migrated category");
            targetCategoryKey = Integer.parseInt(((Element) XPath.selectSingleNode(existingCategory, "//newkey")).getValue());
        }

        if (targetContenttype != null && copyContent) {
            ResponseMessage.addInfoMessage("Will copy content for category " + category.getTitle());
            copyContent(category.getKey(), targetCategoryKey);
        } else {
            ResponseMessage.addInfoMessage("Will not copy content for category " + category.getTitle());
        }

        Iterator<Category> categoryIt = category.categories.iterator();
        while (categoryIt.hasNext()) {
            if (abortCopy) {
                break;
            }
            Category subCategory = categoryIt.next();
            if (category.getKey().intValue() == subCategory.getSuperKey().intValue()) {
                subCategory.categories = category.categories;
                createCategories(subCategory, targetCategoryKey);
            }
        }

    }

    private void copyContent(Integer sourceCategoryKey, Integer targetCategoryKey) throws Exception {

        RemoteClient targetserverClient = getTargetserverClient();
        RemoteClient sourceserverClient = getSourceserverClient();

        GetContentByCategoryParams getContentByCategoryParams = new GetContentByCategoryParams();
        getContentByCategoryParams.includeOfflineContent = true;
        getContentByCategoryParams.includeData = true;
        getContentByCategoryParams.includeVersionsInfo = false;
        getContentByCategoryParams.includeUserRights = true;
        getContentByCategoryParams.count = 9999999;
        getContentByCategoryParams.levels = 1;
        getContentByCategoryParams.categoryKeys = new int[]{sourceCategoryKey};
        Document document = sourceserverClient.getContentByCategory(getContentByCategoryParams);

        if (XPath.selectSingleNode(document, "contents/content") == null) {
            return;
        }

        Integer sourceContenttypeKey;
        try {
            sourceContenttypeKey = ((Attribute) XPath.selectSingleNode(document, "contents/content[1]/@contenttypekey")).getIntValue();
        } catch (Exception e) {
            ResponseMessage.addInfoMessage("Category with key " + sourceCategoryKey + " has no content to be copied, moving on");
            return;
        }

        Contenttype sourceContenttype = getContenttype(sourceContenttypeKey, null, sourceserverClient);

        if (!contenttypeMap.containsKey(sourceContenttype)) {
            ResponseMessage.addInfoMessage("No contenttype mapping exists for contenttype " + sourceContenttype.getName() + "(" + sourceContenttype.getKey() + ")");
            return;
        }

        Contenttype targetContenttype = contenttypeMap.get(sourceContenttype);


        if (sourceContenttype == null || targetContenttype == null || sourceContenttype.getKey() == null || targetContenttype.getKey() == null) {
            ResponseMessage.addWarningMessage("Contenttypes are not correctly mapped, aborting copy of content");
            return;
        } else {
            ResponseMessage.addInfoMessage("Migrate source contenttype " + sourceContenttype.getName() + " to target contenttype " + targetContenttype.getName());
        }
        ResponseMessage.addInfoMessage("Get source and target contenttype doc for source and target keys " + sourceContenttype.getKey() + " " + targetContenttype.getKey());

        Document sourceContenttypeDoc = getContenttypeDoc(sourceContenttype.getKey(), null, sourceserverClient);
        Document targetContenttypeDoc = getContenttypeDoc(targetContenttype.getKey(), null, targetserverClient);

        if (sourceContenttypeDoc == null) {
            ResponseMessage.addErrorMessage("Source contenttype doc is null, aborting");
            return;
        }
        if (targetContenttypeDoc == null) {
            ResponseMessage.addErrorMessage("Target contenttype doc is null, aborting");
            return;
        }

        List<Element> contentElements = XPath.selectNodes(document, "contents/content");
        int numberOfContentToCopy = contentElements.size();
        ResponseMessage.addInfoMessage("Copy " + numberOfContentToCopy + " content..");

        if (contentElements == null || contentElements.isEmpty()) {
            return;
        }

        //TODO:Fetch the import config named 'ccontent', only nescessary for content, can be optimized
        ResponseMessage.addInfoMessage("Check if contenttype has an import config named 'ccontent'");
        Element importConfig = null;
        importConfig = (Element) XPath.selectSingleNode(targetContenttypeDoc, "//imports/import[@name='ccontent']");
        if (importConfig == null) {
            ResponseMessage.addInfoMessage("No import config. Check if contenttype has an import config named 'ccontent-" + sourceContenttype.getName() + "'");
            //Fetch the import config especially for this sourceContentType, in case of n-to-1 mapping between contenttypes
            importConfig = (Element) XPath.selectSingleNode(targetContenttypeDoc, "//imports/import[@name='ccontent-" + sourceContenttype.getName() + "']");
        }

        Iterator<Element> contentElementsIt = contentElements.iterator();

        while (contentElementsIt.hasNext()) {
            if (abortCopy) {
                break;
            }
            String displayName = null;

            Element sourceContentModifierEl = null;
            Element sourceContentOwnerEl = null;

            String targetModifierQN = null;
            String targetModifierName = null;
            String targetModifierKey = null;

            String srcModifierQN = null;
            String srcModifierName = null;
            String srcModifierKey = null;

            String srcOwnerKey = null;
            String srcOwnerQN = null;
            String srcOwnerName = null;



            try {
                int count = 0;
                Element contentEl = contentElementsIt.next();
                if (contentEl == null) {
                    ResponseMessage.addWarningMessage("Content element is null, skipping content..");
                    continue;
                }
                Element displayNameEl = ((Element) XPath.selectSingleNode(contentEl, "display-name"));
                if (displayNameEl == null) {
                    ResponseMessage.addWarningMessage("Displayname is null, this should always be set, skipping content");
                    continue;
                }
                displayName = displayNameEl.getValue();

                Integer status = ((Attribute) XPath.selectSingleNode(contentEl, "@status")).getIntValue();
                Date publishFromDate = null;
                try {
                    publishFromDate = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).parse(((Attribute) XPath.selectSingleNode(contentEl, "@publishfrom")).getValue());
                } catch (Exception e) {
                    ResponseMessage.addWarningMessage("Exception when setting publishFromDate");
                }

                Integer sourceContentKey = ((Attribute) XPath.selectSingleNode(contentEl, "@key")).getIntValue();
                String contenttype = ((Attribute) XPath.selectSingleNode(contentEl, "@contenttype")).getValue();

                boolean isImage = XPath.selectSingleNode(contentEl, "contentdata/sourceimage") != null;
                boolean isFile = XPath.selectSingleNode(contentEl, "contentdata/filesize") != null;

                Document migratedContentDoc = null;
                boolean isAlreadyMigrated = false;
                boolean isMigratedContentModifiedByCustomer = false;

                try{
                    sourceContentModifierEl = (Element) XPath.selectSingleNode(contentEl, "modifier");
                    sourceContentModifierEl.setAttribute(new Attribute("timestamp", contentEl.getAttributeValue("timestamp")));
                    sourceContentModifierEl.setAttribute(new Attribute("publishfrom", contentEl.getAttributeValue("publishfrom")));
                    srcModifierQN = sourceContentModifierEl.getAttributeValue("qualified-name");
                    srcModifierKey = sourceContentModifierEl.getAttributeValue("key");
                    srcModifierName = sourceContentModifierEl.getChildText("name");
                }catch (Exception e){
                    ResponseMessage.addWarningMessage("Error while getting modifier for original content");
                }
                try{
                    sourceContentOwnerEl = (Element) XPath.selectSingleNode(contentEl, "owner");
                    sourceContentOwnerEl.setAttribute(new Attribute("created", contentEl.getAttributeValue("created")));
                    srcOwnerQN = sourceContentOwnerEl.getAttributeValue("qualified-name");
                    srcOwnerKey = sourceContentOwnerEl.getAttributeValue("key");
                    srcOwnerName = sourceContentOwnerEl.getChildText("name");
                }catch (Exception e){
                    ResponseMessage.addWarningMessage("Error while getting owner for original content");
                }


                if (!isImage && !isFile) {
                    List<Element> existingMigratedContents = getExistingContentHandler().getExistingMigratedContents(sourceContentKey, "content");
                    if (existingMigratedContents == null) {
                        break;
                    }
                    if (existingMigratedContents.size() == 1) {
                        ResponseMessage.addInfoMessage(displayName + " is already migrated once");
                        isAlreadyMigrated = true;
                    }
                    if (existingMigratedContents.size() > 1) {
                        ResponseMessage.addWarningMessage(displayName + " exists in more then one version on target server, something is wrong");
                        isAlreadyMigrated = true;
                    }

                    if (isAlreadyMigrated) {
                        Iterator<Element> migratedElementsIt = existingMigratedContents.iterator();
                        while (migratedElementsIt.hasNext()) {
                            Element migratedElement = migratedElementsIt.next();
                            Element newKeyEl = ((Element) XPath.selectSingleNode(migratedElement, "contentdata/newkey"));
                            if (newKeyEl != null && newKeyEl.getValue() != null) {
                                Integer newKey = Integer.parseInt(newKeyEl.getValue());
                                GetContentParams getContentParams = new GetContentParams();
                                getContentParams.contentKeys = new int[]{newKey};
                                getContentParams.includeData = true;
                                migratedContentDoc = targetserverClient.getContent(getContentParams);


                                try{
                                    Element targetContentModifierEl = (Element) XPath.selectSingleNode(migratedContentDoc, "modifier");
                                    targetModifierQN = targetContentModifierEl.getAttributeValue("qualified-name");
                                    targetModifierKey = targetContentModifierEl.getAttributeValue("key");
                                    targetModifierName = targetContentModifierEl.getChildText("name");
                                }catch (Exception e){
                                    ResponseMessage.addWarningMessage("Error while getting modifier for target content");
                                }
                            }
                        }
                    }
                    //TODO: Use targetModifierQN and sourceModifierQN to test for changes on target server.
                    /*if (overwriteWhenExistingMigratedContentIsModified && srcModifierQN != null && !srcModifierQN.equals(targetserverClient.getRunAsUserName())) {
                        LOG.warn("Content '" + displayName + "' is modified by customer " + targetserverClient.getRunAsUserName() + ". Skip migrating this content to prevent overwrite!");
                        continue;
                    }*/
                }

                if (isImage) {
                    ResponseMessage.addInfoMessage("Copy image " + displayName);
                    Integer imageBinaryKey = ((Attribute) XPath.selectSingleNode(contentEl, "contentdata/sourceimage/binarydata/@key")).getIntValue();

                    GetContentBinaryParams getContentBinaryParams = new GetContentBinaryParams();
                    getContentBinaryParams.contentKey = sourceContentKey;
                    getContentBinaryParams.label = "source";
                    Document contentBinary = null;
                    contentBinary = sourceserverClient.getContentBinary(getContentBinaryParams);
                    if (contentBinary == null) {
                        continue;
                    }

                    final String binaryString = ((Element) XPath.selectSingleNode(contentBinary, "binary/data")).getText();

                    byte[] binaryData = Base64.decodeBase64(binaryString);

                    String binaryName = ((Element) XPath.selectSingleNode(contentBinary, "binary/filename")).getValue();

                    ImageBinaryInput imageBinaryInput = new ImageBinaryInput(binaryData, binaryName);
                    ImageNameInput imageNameInput = new ImageNameInput(displayName);
                    ImageDescriptionInput imageDescriptionInput = new ImageDescriptionInput(((Element) XPath.selectSingleNode(contentEl, "contentdata/description")).getValue());
                    ImageKeywordsInput imageKeywordsInput = new ImageKeywordsInput();
                    List<Element> imageKeywords = XPath.selectNodes(contentEl, "contentdata/keywords");
                    Iterator<Element> imageKeywordsIt = imageKeywords.iterator();
                    while (imageKeywordsIt.hasNext()) {
                        imageKeywordsInput.addKeyword(imageKeywordsIt.next().getValue());
                    }

                    ImageContentDataInput imageContentDataInput = new ImageContentDataInput();
                    imageContentDataInput.binary = imageBinaryInput;
                    imageContentDataInput.description = imageDescriptionInput;
                    imageContentDataInput.keywords = imageKeywordsInput;
                    imageContentDataInput.name = imageNameInput;

                    Integer targetContentKey = null;
                    Element existingMigratedContent = getExistingContentHandler().getExistingMigratedContentOrCategory(sourceContentKey, "image");
                    if (existingMigratedContent == null) {
                        CreateImageContentParams createImageContentParams = new CreateImageContentParams();
                        createImageContentParams.contentData = imageContentDataInput;
                        createImageContentParams.categoryKey = targetCategoryKey;
                        createImageContentParams.changeComment = "Copy image from old installation";
                        if (publishFromDate != null) {
                            createImageContentParams.publishFrom = publishFromDate;
                        }
                        if (status != null) {
                            createImageContentParams.status = status;
                        }
                        try {
                            if (isImpersonationAllowed(srcOwnerQN, targetserverClient)){
                                targetserverClient.impersonate("#" + srcOwnerKey);
                                ResponseMessage.addInfoMessage("Impersonating #" + srcOwnerKey + " (" + srcOwnerQN + ")");
                            }
                        }catch (Exception e){
                            ResponseMessage.addErrorMessage("Error when impersonating src owner " + srcOwnerKey);
                            LOG.error("Error when impersonating src owner",e);
                        }

                        targetContentKey = targetserverClient.createImageContent(createImageContentParams);
                        MigratedContent migratedContent = new MigratedContent();
                        migratedContent.setTitle(displayName);
                        migratedContent.setType("image");
                        migratedContent.setOldContentKey(sourceContentKey);
                        migratedContent.setNewContentKey(targetContentKey);
                        migratedContent.setOldContenttype(sourceContenttype);
                        migratedContent.setNewContenttype(targetContenttype);
                        migratedContent.setOldModifierXml(sourceContentModifierEl);
                        migratedContent.setOldOwnerXml(sourceContentOwnerEl);
                        createMigratedContent(migratedContent);
                    } else {
                        //TODO: Implement updating of images?
                        ResponseMessage.addInfoMessage("Image already exists, skipping..");
                    }
                } else if (isFile) {
                    ResponseMessage.addInfoMessage("Copy file: " + displayName);

                    GetContentBinaryParams getContentBinaryParams = new GetContentBinaryParams();
                    getContentBinaryParams.contentKey = sourceContentKey;
                    Document contentBinary = null;

                    contentBinary = sourceserverClient.getContentBinary(getContentBinaryParams);

                    if (contentBinary == null) {
                        continue;
                    }

                    final String binaryString = ((Element) XPath.selectSingleNode(contentBinary, "binary/data")).getText();
                    final byte[] binaryData = Base64.decodeBase64(binaryString);
                    String binaryName = ((Element) XPath.selectSingleNode(contentBinary, "binary/filename")).getValue();

                    FileBinaryInput fileBinaryInput = new FileBinaryInput(binaryData, binaryName);
                    FileNameInput fileNameInput = new FileNameInput(displayName);
                    FileDescriptionInput fileDescriptionInput = new FileDescriptionInput(((Element) XPath.selectSingleNode(contentEl, "contentdata/description")).getValue());
                    FileKeywordsInput fileKeywordsInput = new FileKeywordsInput();
                    List<Element> fileKeywords = XPath.selectNodes(contentEl, "contentdata/keywords");
                    Iterator<Element> fileKeywordsIt = fileKeywords.iterator();
                    while (fileKeywordsIt.hasNext()) {
                        fileKeywordsInput.addKeyword(fileKeywordsIt.next().getValue());
                    }

                    FileContentDataInput fileContentDataInput = new FileContentDataInput();
                    fileContentDataInput.binary = fileBinaryInput;
                    fileContentDataInput.description = fileDescriptionInput;
                    fileContentDataInput.keywords = fileKeywordsInput;
                    fileContentDataInput.name = fileNameInput;

                    Integer targetContentKey = null;
                    Element existingMigratedContent = getExistingContentHandler().getExistingMigratedContentOrCategory(sourceContentKey, "file");
                    if (existingMigratedContent == null) {
                        CreateFileContentParams createFileContentParams = new CreateFileContentParams();
                        createFileContentParams.categoryKey = targetCategoryKey;
                        createFileContentParams.publishFrom = publishFromDate;
                        if (status != null) {
                            createFileContentParams.status = status;
                        }
                        createFileContentParams.fileContentData = fileContentDataInput;

                        try {
                            if (isImpersonationAllowed(srcOwnerQN, targetserverClient)){
                                targetserverClient.impersonate("#" + srcOwnerKey);
                                ResponseMessage.addInfoMessage("Impersonating #" + srcOwnerKey + " (" + srcOwnerQN + ")");
                            }
                        }catch (Exception e){
                            ResponseMessage.addErrorMessage("Error when impersonating src owner " + srcOwnerKey);
                            LOG.error("Error when impersonating src owner", e);
                        }

                        targetContentKey = targetserverClient.createFileContent(createFileContentParams);
                        MigratedContent migratedContent = new MigratedContent();
                        migratedContent.setTitle(displayName);
                        migratedContent.setType("file");
                        migratedContent.setOldContentKey(sourceContentKey);
                        migratedContent.setNewContentKey(targetContentKey);
                        migratedContent.setOldContenttype(sourceContenttype);
                        migratedContent.setNewContenttype(targetContenttype);
                        migratedContent.setOldModifierXml(sourceContentModifierEl);
                        migratedContent.setOldOwnerXml(sourceContentOwnerEl);

                        createMigratedContent(migratedContent);
                    } else {
                        //TODO: Implement updating of files?
                        ResponseMessage.addInfoMessage("File already exists, skipping..");
                        /*UpdateFileContentParams updateFileContentParams = new UpdateFileContentParams();
                        updateFileContentParams.contentKey = Integer.parseInt(((Element) XPath.selectSingleNode(existingMigratedContent, "//newkey")).getValue());
                        updateFileContentParams.fileContentData = fileContentDataInput;
                        if (publishFromDate != null) {
                            updateFileContentParams.publishFrom = publishFromDate;
                        }
                        if (status != null) {
                            updateFileContentParams.status = status;
                        }*/
                    }
                } else {
                    if (importConfig == null) {
                        ResponseMessage.addErrorMessage("No import config, cancelling copy..");
                        return;
                    }
                    ResponseMessage.addInfoMessage("Copy content " + displayName);
                    ContentDataInput contentDataInput = new ContentDataInput(targetContenttype.getName());

                    List<Element> inputMappingListEl = importConfig.getChildren("mapping");
                    List<Element> blockGroupElements = importConfig.getChildren("block");

                    if (inputMappingListEl == null && blockGroupElements == null) {
                        ResponseMessage.addErrorMessage("No import config mapping, nothing to copy, cancelling copy..");
                        return;
                    }
                    Iterator<Element> inputMappingListElIt = inputMappingListEl.iterator();
                    Iterator<Element> blockGroupElementsIt = blockGroupElements.iterator();

                    //Iterate every top level block element in the import config
                    while (blockGroupElementsIt.hasNext()) {
                        try {
                            Element blockImportEl = blockGroupElementsIt.next();
                            String groupName = blockImportEl.getAttributeValue("dest");
                            String groupBase = blockImportEl.getAttributeValue("base");

                            //mapping elements below current block
                            List<Element> blockGroupInputMappingElements = blockImportEl.getChildren("mapping");

                            //Get content from configured block group base
                            List<Element> blockGroupContents = XPath.selectNodes(contentEl, groupBase);
                            Iterator<Element> blockGroupContentsIt = blockGroupContents.iterator();

                            //Iterate content in each block group base
                            while (blockGroupContentsIt.hasNext()) {
                                try {
                                    Element blockGroupContent = blockGroupContentsIt.next();

                                    //Add a new group input to our migrated content
                                    GroupInput groupInput = contentDataInput.addGroup(groupName);

                                    //Iterate every mapping element in the import config
                                    Iterator<Element> blockGroupInputElementsIt = blockGroupInputMappingElements.iterator();
                                    while (blockGroupInputElementsIt.hasNext()) {
                                        Element currentInputMappingElement = blockGroupInputElementsIt.next();
                                        //TODO: Somewhat cluncky design here. Setters dependent on order.
                                        MappingObjectHolder mappingObjectHolder = new MappingObjectHolder();
                                        mappingObjectHolder.setInputMapping(currentInputMappingElement);
                                        mappingObjectHolder.setSourceContenttype(sourceContenttype);
                                        mappingObjectHolder.setTargetContenttype(targetContenttype);
                                        mappingObjectHolder.setSourceInputElement(
                                                ((Element) XPath.selectSingleNode(sourceContenttypeDoc, "//input[xpath='" + mappingObjectHolder.getInputMappingSrc() + "']")));
                                        mappingObjectHolder.setTargetInputElement(
                                                ((Element) XPath.selectSingleNode(targetContenttypeDoc, "//input[@name='" + mappingObjectHolder.getInputMappingDest() + "']")));
                                        mappingObjectHolder.setContentInputElement(blockGroupContent.getChild(mappingObjectHolder.getInputMappingSrc()));
                                        mappingObjectHolder.setBlockGroupBasePath(blockGroupContent.getAttributeValue("base"));

                                        ResponseMessage.addInfoMessage("Add " + mappingObjectHolder.getSourceInputType() + " " + mappingObjectHolder.getInputMappingSrc() + " to " + mappingObjectHolder.getTargetInputType() + " " + mappingObjectHolder.getInputMappingDest());
                                        ResponseMessage.addInfoMessage(mappingObjectHolder.toString());
                                        InputMapper inputMapper = new InputMapper(getClientProvider(), getExistingContentHandler());
                                        Input i = inputMapper.getInput(mappingObjectHolder);
                                        if (i != null) {
                                            ResponseMessage.addInfoMessage("Adding input " + i.getName() + " to group");
                                            groupInput.add(i);
                                        }
                                    }
                                } catch (Exception e) {
                                    ResponseMessage.addWarningMessage("Exception when trying to add input field in group " + groupName);
                                }
                            }
                        } catch (Exception e) {
                            ResponseMessage.addErrorMessage("Exception while handling block inputs" + e);
                        }
                    }

                    //Iterate every top level mapping element in the import config
                    while (inputMappingListElIt.hasNext()) {
                        Element inputMapping = inputMappingListElIt.next();
                        String sourceInput = inputMapping.getAttributeValue("src");
                        String destInput = inputMapping.getAttributeValue("dest");
                        String sourceInputType = "";
                        String targetInputType = "";
                        try {
                            Element sourceInputEl = ((Element) XPath.selectSingleNode(sourceContenttypeDoc, "//input[xpath='contentdata/" + sourceInput + "']"));
                            Element targetInputEl = ((Element) XPath.selectSingleNode(targetContenttypeDoc, "//input[@name='" + destInput + "']"));

                            if (sourceInputEl == null || targetInputEl == null) {
                                ResponseMessage.addWarningMessage("Could not get source and target input elements from contenttype document. Source/target=" + sourceInput + "/" + destInput);
                                continue;
                            }
                            sourceInputType = sourceInputEl.getAttributeValue("type");
                            targetInputType = targetInputEl.getAttributeValue("type");
                            //addInfoMessage(sourceInput + " is of type " + sourceInputType);
                            Element contentInputEl = (Element) XPath.selectSingleNode(contentEl, "contentdata/" + sourceInput);
                            if (contentInputEl == null) {
                                continue;
                            }

                            MappingObjectHolder mappingObjectHolder = new MappingObjectHolder();
                            mappingObjectHolder.setSourceContenttype(sourceContenttype);
                            mappingObjectHolder.setTargetContenttype(targetContenttype);
                            mappingObjectHolder.setTargetInputElement(targetInputEl);
                            mappingObjectHolder.setSourceInputElement(sourceInputEl);
                            mappingObjectHolder.setInputMapping(inputMapping);
                            mappingObjectHolder.setContentInputElement(contentInputEl);

                            LOG.info(mappingObjectHolder.toString());

                            InputMapper inputMapper = new InputMapper(getClientProvider(), getExistingContentHandler());
                            Input i = inputMapper.getInput(mappingObjectHolder);
                            if (i != null) {
                                ResponseMessage.addInfoMessage("adding input name:" + i.getName() + " type: + " + i.getType());
                                contentDataInput.add(i);
                            }

                        } catch (Exception e) {
                            ResponseMessage.addWarningMessage("Exception when copying input '" + sourceInput + "'");
                            LOG.error("{]", e);
                        }

                    }

                    Element existingMigratedContent = getExistingContentHandler().getExistingMigratedContentOrCategory(sourceContentKey, "content");

                    if (existingMigratedContent == null) {
                        LOG.info("Content does not exist, create it and create a migrated-content entry");
                        CreateContentParams createContentParams = new CreateContentParams();
                        createContentParams.categoryKey = targetCategoryKey;
                        createContentParams.changeComment = "ccontent plugin copied content with key = " + sourceContentKey;
                        createContentParams.contentData = contentDataInput;
                        if (status != null) {
                            createContentParams.status = status;
                        }
                        if (publishFromDate != null) {
                            createContentParams.publishFrom = publishFromDate;
                        }
                        try {
                            if (isImpersonationAllowed(srcOwnerQN, targetserverClient)){
                                targetserverClient.impersonate("#" + srcOwnerKey);
                                ResponseMessage.addInfoMessage("Impersonating #" + srcOwnerKey + " (" + srcOwnerQN + ")");
                            }
                        }catch (Exception e){
                            ResponseMessage.addErrorMessage("Error when impersonating src owner " + srcOwnerKey);
                            LOG.error("Error when impersonating src owner",e);
                        }

                        Integer targetContentKey = targetserverClient.createContent(createContentParams);
                        targetserverClient.removeImpersonation();

                        MigratedContent migratedContent = new MigratedContent();
                        migratedContent.setTitle(displayName);
                        migratedContent.setType("content");
                        migratedContent.setOldContentKey(sourceContentKey);
                        migratedContent.setNewContentKey(targetContentKey);
                        migratedContent.setOldContenttype(sourceContenttype);
                        migratedContent.setNewContenttype(targetContenttype);
                        migratedContent.setOldModifierXml(sourceContentModifierEl);
                        migratedContent.setOldOwnerXml(sourceContentOwnerEl);

                        createMigratedContent(migratedContent);
                    } else {
                        LOG.info("Content exists, update it");

                        UpdateContentParams updateContentParams = new UpdateContentParams();
                        Element newKeyEl = ((Element) XPath.selectSingleNode(existingMigratedContent, "contentdata/newkey"));
                        if (newKeyEl == null) {
                            ResponseMessage.addWarningMessage("Could not find newkey for existing content, aborting update..");
                            continue;
                        }
                        LOG.info("New key is " + newKeyEl.getValue());
                        updateContentParams.contentKey = Integer.parseInt((newKeyEl.getValue()));
                        updateContentParams.changeComment = "ccontent plugin updated content from content with key " + sourceContentKey;
                        updateContentParams.updateStrategy = ContentDataInputUpdateStrategy.REPLACE_NEW;

                        /*if (isMigratedContentModifiedByCustomer && srcModifierKey != null) {
                            try {
                                targetserverCkuebt.impersonate("#" + srcModifierKey);
                            } catch (Exception e) {
                            }
                        }*/
                        if (publishFromDate != null) {
                            updateContentParams.publishFrom = publishFromDate;
                        }
                        if (status != null) {
                            updateContentParams.status = status;
                        }

                        updateContentParams.contentData = contentDataInput;

                        try {
                            if (isImpersonationAllowed(srcModifierQN, targetserverClient)) {
                                targetserverClient.impersonate("#" + srcModifierKey);
                                ResponseMessage.addInfoMessage("Impersonating #" + srcModifierKey + " (" + srcModifierQN + ")");
                            }
                        }catch (Exception e){
                            ResponseMessage.addErrorMessage("Error when impersonating src modifier " + srcModifierKey);
                            LOG.error("Error when impersonating src modifier", e);
                        }

                        targetserverClient.updateContent(updateContentParams);
                        targetserverClient.removeImpersonation();
                        LOG.info("Content updated, publishdate is " + updateContentParams.publishFrom);


                    }
                }
            } catch (Exception e) {
                ResponseMessage.addErrorMessage("Exception!" + e.getMessage());
                LOG.error("Error when copying binary content!!!!! From sourcecategorykey = {}, {}", sourceCategoryKey, e);
            }
            fileCopyProgressCounter++;
        }
    }

    private boolean isImpersonationAllowed(String qName, RemoteClient targetserverClient) {
        if ("admin".equalsIgnoreCase(qName)){
            return false;
        }
        if ("anonymous".equalsIgnoreCase(qName)){
            return false;
        }

        GetUserParams getUserParams = new GetUserParams();
        getUserParams.includeCustomUserFields = false;
        getUserParams.includeMemberships = false;
        getUserParams.normalizeGroups = false;
        getUserParams.user = qName;
        Document userDoc = null;
        try {
            ResponseMessage.addInfoMessage("Check if user exists: " + qName);
            userDoc = targetserverClient.getUser(getUserParams);
            //xmlOutputter.output(userDoc, System.out);
        }catch (Exception e){
            ResponseMessage.addErrorMessage("User does not exist, impersonation is not allowed for \" + qName");
            LOG.error("User does not exist, impersonation is not allowed for \" + qName",e);
            return false;
        }
        if (userDoc!=null && userDoc.hasRootElement()){
            ResponseMessage.addInfoMessage("User exist, impersonation is allowed for " + qName);
            return true;
        }
        ResponseMessage.addInfoMessage("User does not exist, impersonation is not allowed for " + qName);
        return false;
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
                                Element existingMigratedContent = getExistingContentHandler().getExistingMigratedContentOrCategory(Integer.parseInt(guessedOldKey), internalLink.replace("://", ""));

                                if (existingMigratedContent == null && internalLink.contains("attachment")) {
                                    //TODO: Doing this because attachment links can be either images, files or content. Check if contentkeys are 100% unique across different types.
                                    existingMigratedContent = performExtraCheckForAttachments(guessedOldKey);
                                }
                                if (existingMigratedContent == null) {
                                    ResponseMessage.addWarningMessage("linked content not migrated. Replacing " + internalLink + " link with default 'missing key'");
                                    htmlAttributeValue = htmlAttributeValue.replace(guessedOldKey, pluginEnvironment.getSharedObject("context_missing" + internalLink.replace("://", "") + "key").toString());
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
        ExistingContentHandler existingContentHandler = getExistingContentHandler();
        Element existingMigratedContent = getExistingContentHandler().getExistingMigratedContentOrCategory(Integer.parseInt(guessedOldKey), "file");
        if (existingMigratedContent == null) {
            existingMigratedContent = getExistingContentHandler().getExistingMigratedContentOrCategory(Integer.parseInt(guessedOldKey), "image");
        }
        if (existingMigratedContent == null) {
            existingMigratedContent = getExistingContentHandler().getExistingMigratedContentOrCategory(Integer.parseInt(guessedOldKey), "content");
        }
        return existingMigratedContent;
    }


    private void createMigratedContent(MigratedContent migratedContent) {
        CreateContentParams createMigratedContentParams = new CreateContentParams();
        createMigratedContentParams.categoryKey = Integer.parseInt((String) pluginEnvironment.getSharedObject("context_migratedcontentcategory"));
        ContentDataInput migratedContentData = new ContentDataInput("migrated-content");
        migratedContentData.add(new TextInput("oldkey", String.valueOf(migratedContent.getOldContentKey())));
        migratedContentData.add(new TextInput("newkey", String.valueOf(migratedContent.getNewContentKey())));
        migratedContentData.add(new TextInput("oldcontenttype", migratedContent.getOldContenttype() != null ? migratedContent.getOldContenttype().getName() : ""));
        migratedContentData.add(new TextInput("newcontenttype", migratedContent.getNewContenttype() != null ? migratedContent.getNewContenttype().getName() : ""));
        migratedContentData.add(new TextInput("title", migratedContent.getTitle()));
        migratedContentData.add(new TextInput("type", migratedContent.getType()));
        migratedContentData.add(new XmlInput("oldownerxml",migratedContent.getOldOwnerXml()));
        migratedContentData.add(new XmlInput("oldmodifierxml",migratedContent.getOldModifierXml()));
        createMigratedContentParams.contentData = migratedContentData;
        createMigratedContentParams.status = ContentStatus.STATUS_APPROVED;
        createMigratedContentParams.publishFrom = new Date();
        getTargetserverClient().createContent(createMigratedContentParams);


    }


    public void addRequestPathContext(String methodName, WebContext context) {
        if (Strings.isNullOrEmpty(methodName) || methodName.equals("setup")) {
            return;
        }
        try {
            LOG.info("Invoke method {}", methodName);
            Method method = this.getClass().getMethod(methodName, WebContext.class);
            method.invoke(this, context);
        } catch (NoSuchMethodException e) {
            ResponseMessage.addWarningMessage("No method defined for requestPath '" + methodName + "'. Exception: " + e);
            LOG.error("Exception {}", e);
        } catch (IllegalAccessException e) {
            ResponseMessage.addErrorMessage("IllegalAccessException with reflection ");
            LOG.error("Exception {}", e);
        } catch (InvocationTargetException e) {
            ResponseMessage.addErrorMessage("InvocationTargetException with reflection: " + e);
            LOG.error("Exception {}", e);
        }
    }

    public String resolveMethodNameFromRequestPath(String requestPath) {
        String[] words = requestPath.split("/");
        String methodName = "";
        for (String word : words) {
            methodName += StringUtils.capitalize(word.toLowerCase());
        }
        methodName = StringUtils.uncapitalize(methodName);

        return methodName;
    }

    private static void disableSslVerification() throws Exception {
        try {
            // Create a trust manager that does not validate certificate chains
            TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() {
                    return null;
                }

                public void checkClientTrusted(X509Certificate[] certs, String authType) {
                }

                public void checkServerTrusted(X509Certificate[] certs, String authType) {
                }
            }
            };

            // Install the all-trusting trust manager
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

            // Create all-trusting host name verifier
            HostnameVerifier allHostsValid = new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            };

            // Install the all-trusting host verifier
            HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (KeyManagementException e) {
            e.printStackTrace();
        }
    }



    private RemoteClient getSourceserverClient() {
        ClientProvider clientProvider = new ClientProvider(pluginEnvironment);
        return clientProvider.getSourceserverClient();
    }

    private RemoteClient getTargetserverClient() {
        ClientProvider clientProvider = new ClientProvider(pluginEnvironment);
        return clientProvider.getTargetserverClient();
    }

    private ClientProvider getClientProvider() {
        return new ClientProvider(pluginEnvironment);
    }

    private ExistingContentHandler getExistingContentHandler() {
        return new ExistingContentHandler(getClientProvider());
    }
}
