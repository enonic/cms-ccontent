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
import org.jdom.JDOMException;
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
import java.util.*;

@Component
public class CopyContentController extends HttpController {

    Logger LOG = LoggerFactory.getLogger(getClass());

    HashMap<Contenttype, Contenttype> contenttypeMap = new HashMap<Contenttype, Contenttype>();
    List<Contenttype> sourceContenttypes = new ArrayList<Contenttype>();
    HashMap<String, Element> importConfigs = new HashMap<String, Element>();

    boolean overwriteWhenExistingMigratedContentIsModified = false;

    Category sourceCategory = null;
    Category targetCategory = null;

    boolean purgeMigratedContentBeforeCopy = false;
    boolean purgeTargetFolderBeforeCopy = false;
    boolean copyContent = true;
    boolean updateContent = true;
    boolean includeVersionsAndDrafts = false;

    boolean readyForCopying = false;
    int fileCopyProgressCounter = 0;
    boolean abortCopy = false;

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

        HttpServletRequest req = pluginEnvironment.getCurrentRequest();
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
            ResponseMessage.addErrorMessage(e.getMessage());
        }

        try {
            Client localClient = ClientFactory.getRemoteClient(targetserverUrl);
            localClient.logout();
            localClient.login(targetserverUsername, targetserverPassword);
            ResponseMessage.addInfoMessage("Authentication for local client successful");
        } catch (Exception e) {
            ResponseMessage.addErrorMessage("Authentication for local client failed");
            ResponseMessage.addErrorMessage(e.getMessage());
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

        //Clear cached import configurations
        importConfigs.clear();

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
        try {
            Integer key = ((Attribute) XPath.selectSingleNode(resultDoc, "contents/content/@contenttypekey")).getIntValue();
            String name = ((Attribute) XPath.selectSingleNode(resultDoc, "contents/content/@contenttype")).getValue();

            result.setKey(key);
            result.setName(name);
        } catch (NullPointerException e) {
            ResponseMessage.addUniqueMessage("No content exists of contenttype" + contenttypeName != null ? contenttypeName : "" + " key:" + contenttypeKey + ". This is a prerequisite for creating a mapping!", "warning", "contenttype-without-content-" + contenttypeKey);
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

        if (pluginEnvironment.getCurrentRequest().getParameter("updateContent") != null) {
            this.updateContent = Boolean.parseBoolean(pluginEnvironment.getCurrentRequest().getParameter("updateContent"));
        }

        if (pluginEnvironment.getCurrentRequest().getParameter("includeVersionsAndDrafts") != null) {
            this.includeVersionsAndDrafts = Boolean.parseBoolean(pluginEnvironment.getCurrentRequest().getParameter("includeVersionsAndDrafts"));
        }

        if (categoryKey != null) {
            targetCategory = getCategoryFolder(categoryKey, getTargetserverClient(), true);
        }
        if (pluginEnvironment.getCurrentRequest().getParameter("purgeTargetFolderBeforeCopy") != null) {
            this.purgeTargetFolderBeforeCopy = Boolean.parseBoolean(pluginEnvironment.getCurrentRequest().getParameter("purgeTargetFolderBeforeCopy"));
        }

        pluginEnvironment.setSharedObject("context_purgeTargetFolderBeforeCopy", purgeTargetFolderBeforeCopy);
        pluginEnvironment.setSharedObject("context_updateContent", updateContent);
        pluginEnvironment.setSharedObject("context_includeVersionsAndDrafts", includeVersionsAndDrafts);
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
        } catch (Exception e) {
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
                migratedContent.setSourceContentKey(category.getKey());
                migratedContent.setSourceContenttype(sourceContenttype);
                migratedContent.setTargetContenttype(targetContenttype);
                migratedContent.setTargetContentKey(targetCategoryKey);
                createMigratedContent(migratedContent);
                ResponseMessage.addInfoMessage("Created migrated-content contenttype for category");

            } catch (Exception e) {
                ResponseMessage.addErrorMessage("Exception while creating category");
            }
        } else {
            ResponseMessage.addInfoMessage("Get previously migrated category");
            targetCategoryKey = Integer.parseInt(((Element) XPath.selectSingleNode(existingCategory, "//newkey")).getValue());
        }

        if (targetContenttype != null && copyContent && !abortCopy) {
            ResponseMessage.addInfoMessage("Will copy content for category " + category.getTitle());
            copyContents(category.getKey(), targetCategoryKey);
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

    private void copyContents(Integer sourceCategoryKey, Integer targetCategoryKey) throws Exception {

        RemoteClient targetserverClient = getTargetserverClient();
        RemoteClient sourceserverClient = getSourceserverClient();

        GetContentByCategoryParams getContentByCategoryParams = new GetContentByCategoryParams();
        getContentByCategoryParams.includeOfflineContent = true;
        getContentByCategoryParams.includeData = true;
        getContentByCategoryParams.includeVersionsInfo = this.includeVersionsAndDrafts;
        getContentByCategoryParams.includeUserRights = true;
        getContentByCategoryParams.count = 9999999;
        getContentByCategoryParams.levels = 1;
        getContentByCategoryParams.categoryKeys = new int[]{sourceCategoryKey};

        Document contentInCategory = sourceserverClient.getContentByCategory(getContentByCategoryParams);

        if (hasNoContent(contentInCategory)) return;

        Integer sourceContenttypeKey;
        try {
            sourceContenttypeKey = ((Attribute) XPath.selectSingleNode(contentInCategory, "contents/content[1]/@contenttypekey")).getIntValue();
        } catch (Exception e) {
            ResponseMessage.addInfoMessage("Category with key " + sourceCategoryKey + " has no content to be copied, moving on");
            return;
        }

        MigratedContent migratedContent = new MigratedContent();
        migratedContent.setSourceContenttype(getContenttype(sourceContenttypeKey, null, sourceserverClient));
        migratedContent.setTargetCategoryKey(targetCategoryKey);
        migratedContent.setSourceCategoryKey(sourceCategoryKey);

        if (!isContenttypeMappingOk(migratedContent)) return;


        ResponseMessage.addInfoMessage("Get source and target contenttype doc for source and target keys "
                + migratedContent.getSourceContenttype().getKey() + " " + migratedContent.getTargetContenttype().getKey());

        migratedContent.setSourceContenttypeDoc(getContenttypeDoc(migratedContent.getSourceContenttype().getKey(), null, sourceserverClient));
        migratedContent.setTargetContenttypeDoc(getContenttypeDoc(migratedContent.getTargetContenttype().getKey(), null, targetserverClient));

        if (!migratedContent.isSourceAndTargetContenttypeDocOk()) return;

        List<Element> contentElements = XPath.selectNodes(contentInCategory, "contents/content");
        ResponseMessage.addInfoMessage("Copy " + contentElements.size() + " content..");

        if (contentElements == null || contentElements.isEmpty()) {
            return;
        }

        Iterator<Element> contentElementsIt = contentElements.iterator();

        while (contentElementsIt.hasNext()) {
            if (abortCopy) {
                break;
            }
            migratedContent.setSourceContentElement(contentElementsIt.next());
            try {
                copyContent(migratedContent);
            } catch (Exception e) {
                ResponseMessage.addErrorMessage("Failed to copy content " + e);
                LOG.error("Failed to copy content ", e);
            }

            fileCopyProgressCounter++;
        }
    }

    private void copyContent(MigratedContent migratedContent) throws Exception {

        String displayName = null;

        int count = 0;

        if (migratedContent.getSourceContentElement() == null) {
            return;
        }

        Content sourceContent = new Content();
        sourceContent.parseContent(migratedContent.getSourceContentElement());

        boolean isImage = XPath.selectSingleNode(migratedContent.getSourceContentElement(), "contentdata/sourceimage") != null;
        boolean isFile = XPath.selectSingleNode(migratedContent.getSourceContentElement(), "contentdata/filesize") != null;
        boolean isCustomContent = !isFile && !isImage;

        if (isImage) {
            copyImage(migratedContent, sourceContent);
        } else if (isFile) {
            copyFile(migratedContent, sourceContent);
        } else if (isCustomContent) {
            copyCustomContent(migratedContent, sourceContent);
        } else {
            LOG.error("Content is neither file, image or custom content. This should not happen");
        }

    }

    private void copyCustomContent(MigratedContent migratedContent, Content sourceContent) {

        RemoteClient targetserverClient = getTargetserverClient();
        RemoteClient sourceserverClient = getSourceserverClient();

        Element importConfig = getImportConfig(migratedContent);


        if (importConfig == null) {
            return;
        }

        List<Element> inputElementMappings = importConfig.getChildren("mapping");
        List<Element> blockGroupElementMappings = importConfig.getChildren("block");

        if (inputElementMappings == null && blockGroupElementMappings == null) {
            ResponseMessage.addErrorMessage("No import config mapping, nothing to copy, cancelling copy..");
            return;
        }
        migratedContent.setInputElementMappings(inputElementMappings);
        migratedContent.setBlockGroupElementMappings(blockGroupElementMappings);

        ResponseMessage.addInfoMessage("Copy content " + sourceContent.getDisplayName());
        ContentDataInput contentDataInput = new ContentDataInput(migratedContent.getTargetContenttype().getName());

        Element existingMigratedContent = null;
        Document migratedContentDoc = null;
        boolean isAlreadyMigrated = false;

        try {
            existingMigratedContent = getExistingContentHandler().getExistingMigratedContentOrCategory(sourceContent.getKey(), "content");
            if (existingMigratedContent != null) {
                isAlreadyMigrated = true;
            }
        } catch (Exception e) {
            ResponseMessage.addWarningMessage("Exception while trying to fetch existing migrated content for '" + sourceContent.getDisplayName() + "'");
        }

        Content targetContent = null;

        if (isAlreadyMigrated) {
            if (!updateContent) {
                ResponseMessage.addInfoMessage("aborting because content is already migrated and 'updateContent' is " + updateContent);
                return;
            }
            targetContent = getTargetContent(existingMigratedContent);
        }

        if (targetContent != null) {
            if (updateContent) {
                updateMigratedCustomContent(migratedContent, sourceContent, targetContent);
            }
        } else {
            createNewMigratedCustomContent(migratedContent, sourceContent);
        }

    }

    private Content getTargetContent(Element existingMigratedContent) {
        RemoteClient targetserverClient = getTargetserverClient();

        Content targetContent = null;
        try {
            Element newKeyEl = ((Element) XPath.selectSingleNode(existingMigratedContent, "contentdata/newkey"));

            if (newKeyEl == null || Strings.isNullOrEmpty(newKeyEl.getValue())) {
                return null;
            }

            GetContentParams getContentParams = new GetContentParams();
            getContentParams.contentKeys = new int[]{Integer.parseInt(newKeyEl.getValue())};
            getContentParams.includeData = true;
            getContentParams.includeOfflineContent = true;
            Document migratedContentDoc = targetserverClient.getContent(getContentParams);
            Element migratedContentElement = (Element) XPath.selectSingleNode(migratedContentDoc, "contents/content");

            if (migratedContentElement == null) {
                return null;
            }
            targetContent = new Content();
            targetContent.parseContent(migratedContentElement);

        } catch (JDOMException e) {
            ResponseMessage.addErrorMessage("Error when getting existing migrated content");
        }
        return targetContent;
    }

    private void updateMigratedCustomContent(MigratedContent migratedContent, Content sourceContent, Content targetContent) {
        RemoteClient targetserverClient = getTargetserverClient();
        LOG.info("Content exists, update it");

        UpdateContentParams updateContentParams = new UpdateContentParams();
        updateContentParams.contentKey = targetContent.getKey();
        updateContentParams.changeComment = "ccontent plugin updated content from content with key " + sourceContent.getKey();

        updateContentParams.updateStrategy = ContentDataInputUpdateStrategy.REPLACE_NEW;

        if (sourceContent.getPublishfrom() != null) {
            updateContentParams.publishFrom = sourceContent.getPublishfrom();
        }

        if (sourceContent.getPublishto() != null) {
            updateContentParams.publishTo = sourceContent.getPublishto();
        }

        if (sourceContent.getStatus() != null) {
            updateContentParams.status = sourceContent.getStatus();
        }

        updateContentParams.contentData = getMigratedContentData(migratedContent);
        updateMigratedCustomContentWithImpersonation(sourceContent, updateContentParams);
    }

    private Integer updateMigratedCustomContentWithImpersonation(Content sourceContent, UpdateContentParams updateContentParams) {

        RemoteClient targetserverClient = getTargetserverClient();
        try {
            if (!sourceContent.isModifierDeleted() && isImpersonationAllowed(sourceContent.getModifierQN(), targetserverClient)) {
                targetserverClient.impersonate("#" + sourceContent.getModifierKey());
            }
        } catch (Exception e) {
            ResponseMessage.addErrorMessage("Error when impersonating src owner " + sourceContent.getOwnerKey());
            LOG.error("Error when impersonating src owner", e);
        }

        Integer contentVersionKey = targetserverClient.updateContent(updateContentParams);
        targetserverClient.removeImpersonation();
        return contentVersionKey;
    }

    private ContentDataInput getMigratedContentData(MigratedContent migratedContent) {
        ContentDataInput contentDataInput = new ContentDataInput(migratedContent.getTargetContenttype().getName());
        Iterator<Element> inputMappingListElIt = migratedContent.getInputElementMappings().iterator();
        Iterator<Element> blockGroupElementsIt = migratedContent.getBlockGroupElementMappings().iterator();

        //Iterate every top level block element in the import config
        while (blockGroupElementsIt.hasNext()) {
            try {
                Element blockImportEl = blockGroupElementsIt.next();
                String groupName = blockImportEl.getAttributeValue("dest");
                String groupBase = blockImportEl.getAttributeValue("base");

                //mapping elements below current block
                List<Element> blockGroupInputMappingElements = blockImportEl.getChildren("mapping");

                //Get content from configured block group base
                List<Element> blockGroupContents = XPath.selectNodes(migratedContent.getSourceContentElement(), groupBase);
                Iterator<Element> blockGroupContentsIt = blockGroupContents.iterator();

                MappingObjectHolder mappingObjectHolder = new MappingObjectHolder();
                mappingObjectHolder.setSourceContenttype(migratedContent.getSourceContenttype());
                mappingObjectHolder.setTargetContenttype(migratedContent.getTargetContenttype());

                //TODO: Some temporary hardcoding stuff here, add image into block group 'Bilder' from logo source input
                if (mappingObjectHolder.getSourceContenttype().getName().equals("artikkel-pasientinformasjon")) {
                    if ("Bilder".equals(groupName)) {
                        //Add a new group input to our migrated content
                        try {
                            Element logo = (Element) XPath.selectSingleNode(migratedContent.getSourceContentElement(), "contentdata/logo/logo");
                            if (logo != null) {
                                Element sourceInputElement = ((Element) XPath.selectSingleNode(migratedContent.getSourceContenttypeDoc(), "//input[xpath='contentdata/logo/logo']"));
                                mappingObjectHolder.setSourceInputElement(sourceInputElement);
                                mappingObjectHolder.setContentInputElement(logo);
                                //InputMapper get dest element from this, so we need to set (hack) it
                                Element destEl = new Element("mapping");
                                destEl.setAttribute("dest","image-binary");
                                //don't think this is used for anything but logging though..
                                destEl.setAttribute("src", "contentdata/logo/logo");
                                mappingObjectHolder.setInputMapping(destEl);
                                InputMapper inputMapper = new InputMapper(getClientProvider(), getExistingContentHandler(), pluginEnvironment);
                                Input input = inputMapper.getInput(mappingObjectHolder);
                                if (input != null) {
                                    Input sizeInput = new SelectorInput("image-size","small");
                                    Input textInput = new TextInput("image-description","Logo");
                                    GroupInput groupInput = contentDataInput.addGroup(groupName);
                                    groupInput.add(textInput);
                                    groupInput.add(input);
                                    groupInput.add(sizeInput);
                                    mappingObjectHolder.setSourceInputElement(null);
                                    mappingObjectHolder.setTargetInputElement(null);
                                    mappingObjectHolder.setContentInputElement(null);
                                }
                            }
                        } catch (Exception e) {
                            ResponseMessage.addWarningMessage("Exception in special handling of logo -> Bilder/ in artikkel-pasientinformasjon contenttype");
                            LOG.warn("Exception in special handling of subtheme texts -> body-text in artikkel-pasientinformasjon contenttype", e);
                        }
                    }
                }

                //Iterate content in each block group base
                while (blockGroupContentsIt.hasNext()) {
                    try {
                        Element blockGroupContent = blockGroupContentsIt.next();

                        //Add a new group input to our migrated content
                        GroupInput groupInput =  contentDataInput.addGroup(groupName);

                        //Iterate every mapping element in the import config
                        Iterator<Element> blockGroupInputElementsIt = blockGroupInputMappingElements.iterator();

                        while (blockGroupInputElementsIt.hasNext()) {
                            Element currentInputMappingElement = blockGroupInputElementsIt.next();
                            //TODO: Somewhat cluncky design here. Setters dependent on order.
                            mappingObjectHolder.setInputMapping(currentInputMappingElement);
                            mappingObjectHolder.setSourceInputElement(
                                    ((Element) XPath.selectSingleNode(migratedContent.getSourceContenttypeDoc(), "//input[xpath='" + mappingObjectHolder.getInputMappingSrc() + "']")));
                            mappingObjectHolder.setTargetInputElement(
                                    ((Element) XPath.selectSingleNode(migratedContent.getTargetContenttypeDoc(), "//input[@name='" + mappingObjectHolder.getInputMappingDest() + "']")));
                            String inputMappingChildName = mappingObjectHolder.getInputMappingSrc();
                            Element inputMappingChild = blockGroupContent.getChild(inputMappingChildName);
                            mappingObjectHolder.setContentInputElement(inputMappingChild);

                            ResponseMessage.addInfoMessage("Add " + mappingObjectHolder.getSourceInputType() + " " + mappingObjectHolder.getInputMappingSrc() + " to " + mappingObjectHolder.getTargetInputType() + " " + mappingObjectHolder.getInputMappingDest());
                            ResponseMessage.addInfoMessage(mappingObjectHolder.toString());
                            InputMapper inputMapper = new InputMapper(getClientProvider(), getExistingContentHandler(), pluginEnvironment);
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
                Element sourceInputEl = ((Element) XPath.selectSingleNode(migratedContent.getSourceContenttypeDoc(), "//input[xpath='contentdata/" + sourceInput + "']"));
                Element targetInputEl = ((Element) XPath.selectSingleNode(migratedContent.getTargetContenttypeDoc(), "//input[@name='" + destInput + "']"));

                if (sourceInputEl == null || targetInputEl == null) {
                    ResponseMessage.addWarningMessage("Could not get source and target input elements from contenttype document. Source/target=" + sourceInput + "/" + destInput);
                    continue;
                }
                sourceInputType = sourceInputEl.getAttributeValue("type");
                targetInputType = targetInputEl.getAttributeValue("type");
                //addInfoMessage(sourceInput + " is of type " + sourceInputType);
                Element contentInputEl = (Element) XPath.selectSingleNode(migratedContent.getSourceContentElement(), "contentdata/" + sourceInput);
                if (contentInputEl == null) {
                    continue;
                }

                MappingObjectHolder mappingObjectHolder = new MappingObjectHolder();
                mappingObjectHolder.setSourceContenttype(migratedContent.getSourceContenttype());
                mappingObjectHolder.setTargetContenttype(migratedContent.getTargetContenttype());
                mappingObjectHolder.setTargetInputElement(targetInputEl);
                mappingObjectHolder.setSourceInputElement(sourceInputEl);
                mappingObjectHolder.setInputMapping(inputMapping);
                mappingObjectHolder.setContentInputElement(contentInputEl);

                LOG.info(mappingObjectHolder.toString());

                InputMapper inputMapper = new InputMapper(getClientProvider(), getExistingContentHandler(), pluginEnvironment);
                Input i = inputMapper.getInput(mappingObjectHolder);
                if (i != null) {
                    //TODO: Some temporary hardcoding stuff here
                    if (mappingObjectHolder.getSourceContenttype().getName().equals("artikkel-pasientinformasjon")) {
                        if ("body-text".equals(destInput)) {
                            List<Element> subthemeTexts = XPath.selectNodes(migratedContent.getSourceContentElement(), "contentdata//subtheme/text");
                            if (subthemeTexts != null && !subthemeTexts.isEmpty()) {
                                //Keep existing body-text
                                StringBuffer existingBodyTextHtml = new StringBuffer();
                                existingBodyTextHtml.append(((HtmlAreaInput) i).getValueAsString());

                                //Append all subtheme texts to body-text
                                Iterator<Element> subthemeTextsIt = subthemeTexts.iterator();
                                while (subthemeTextsIt.hasNext()) {
                                    try {
                                        Element subthemeTextEl = subthemeTextsIt.next();
                                        Element sourceInputElement = ((Element) XPath.selectSingleNode(migratedContent.getSourceContenttypeDoc(), "//input[xpath='text']"));
                                        mappingObjectHolder.setSourceInputElement(sourceInputElement);
                                        mappingObjectHolder.setContentInputElement(subthemeTextEl);
                                        Input input = inputMapper.getInput(mappingObjectHolder);
                                        if (input != null) {
                                            //Add some spacing
                                            existingBodyTextHtml.append("\n\n");
                                            existingBodyTextHtml.append(((HtmlAreaInput) input).getValueAsString());
                                        }
                                    } catch (Exception e) {
                                        ResponseMessage.addWarningMessage("Exception in special handling of subtheme texts -> body-text in artikkel-pasientinformasjon contenttype");
                                        LOG.warn("Exception in special handling of subtheme texts -> body-text in artikkel-pasientinformasjon contenttype", e);
                                    }

                                }
                                i = new HtmlAreaInput("body-text", existingBodyTextHtml.toString());
                            }
                        }
                    }
                    ResponseMessage.addInfoMessage("adding input name:" + i.getName() + " type: + " + i.getType());
                    contentDataInput.add(i);
                }

            } catch (Exception e) {
                ResponseMessage.addWarningMessage("Exception when copying input '" + sourceInput + "'");
                LOG.error("{]", e);
            }
        }
        return contentDataInput;
    }

    private void createNewMigratedCustomContent(MigratedContent migratedContent, Content sourceContent) {
        RemoteClient targetserverClient = getTargetserverClient();
        LOG.info("Content does not exist, create it and create a migrated-content entry");

        Integer targetContentKey = null;
        if (includeVersionsAndDrafts) {
            targetContentKey = createNewMigratedContentWithVersionsAndDrafts(migratedContent, sourceContent);
        } else {
            targetContentKey = createNewMigratedContent(migratedContent, sourceContent);
        }


        migratedContent.setTitle(sourceContent.getDisplayName());
        migratedContent.setType("content");
        migratedContent.setSourceContentKey(sourceContent.getKey());
        migratedContent.setTargetContentKey(targetContentKey);
        migratedContent.setSourceContenttype(migratedContent.getSourceContenttype());
        migratedContent.setTargetContenttype(migratedContent.getTargetContenttype());
        migratedContent.setSourceContent(sourceContent);
        createMigratedContent(migratedContent);
    }

    private Integer createNewMigratedContent(MigratedContent migratedContent, Content sourceContent) {
        RemoteClient targetserverClient = getTargetserverClient();
        CreateContentParams createContentParams = new CreateContentParams();
        createContentParams.categoryKey = migratedContent.getTargetCategoryKey();
        createContentParams.changeComment = "ccontent plugin copied content with key = " + migratedContent.getSourceContentKey();
        createContentParams.contentData = getMigratedContentData(migratedContent);

        if (sourceContent.getStatus() != null) {
            createContentParams.status = sourceContent.getStatus();
        }
        if (sourceContent.getPublishfrom() != null) {
            createContentParams.publishFrom = sourceContent.getPublishfrom();
        }
        if (sourceContent.getPublishto() != null) {
            createContentParams.publishTo = sourceContent.getPublishto();
        }

        return createContentWithImpersonation(sourceContent, createContentParams);
    }

    private Integer createContentWithImpersonation(Content sourceContent, CreateContentParams createContentParams) {
        RemoteClient targetserverClient = getTargetserverClient();
        try {
            if (!sourceContent.isModifierDeleted() && isImpersonationAllowed(sourceContent.getModifierQN(), targetserverClient)) {
                targetserverClient.impersonate("#" + sourceContent.getModifierKey());
            }
        } catch (Exception e) {
            ResponseMessage.addErrorMessage("Error when impersonating src owner " + sourceContent.getOwnerKey());
            LOG.error("Error when impersonating src owner", e);
        }

        Integer targetContentKey = targetserverClient.createContent(createContentParams);
        targetserverClient.removeImpersonation();
        return targetContentKey;
    }

    private Integer createNewMigratedContentWithVersionsAndDrafts(MigratedContent migratedContent, Content sourceContent) {
        RemoteClient sourceServerClient = getSourceserverClient();
        RemoteClient targetServerClient = getTargetserverClient();

        Integer newContentKey = null;
        Integer lastVersionKey = null;
        int lastVersionStatus = -1;

        List<Element> versions = null;
        try {
            versions = XPath.selectNodes(migratedContent.getSourceContentElement(), "versions/version");
            List<Attribute> versionKeysEl = XPath.selectNodes(migratedContent.getSourceContentElement(), "versions/version/@key");
            Iterator<Attribute> versionKeysElIt = versionKeysEl.iterator();
            int[] versionKeys = new int[versionKeysEl.size()];
            for (int i = 0; i < versionKeysEl.size(); i++) {
                versionKeys[i] = versionKeysEl.get(i).getIntValue();
            }
            Document versionsDoc = null;
            versionsDoc = getVersionDoc(versionKeys);

            Iterator<Element> versionsElIt = versions.iterator();
            while (versionsElIt.hasNext()) {
                Element versionEl = versionsElIt.next();
                boolean isCurrentVersion = versionEl.getAttribute("current") != null ? (versionEl.getAttribute("current").getBooleanValue()) : false;
                boolean isLastVersion = !versionsElIt.hasNext();

                Integer versionKey = versionEl.getAttribute("key").getIntValue();
                Element versionContentEl = (Element) XPath.selectSingleNode(versionsDoc, "contents/content[@versionkey=" + versionKey + "]");
                if (newContentKey == null) {
                    Content firstVersionContent = new Content();
                    firstVersionContent.parseContent(versionContentEl);
                    migratedContent.setSourceContent(firstVersionContent);
                    migratedContent.setSourceContentElement(versionContentEl);

                    CreateContentParams createContentParams = new CreateContentParams();
                    createContentParams.categoryKey = migratedContent.getTargetCategoryKey();
                    createContentParams.changeComment = versionEl.getChildText("comment");
                    Integer statusKey = firstVersionContent.getStatus();

                    //NB! API does not allow to create snapshots with createContent
                    if (statusKey == 1) {
                        statusKey = ContentStatus.STATUS_APPROVED;
                    } else {
                        createContentParams.status = statusKey;
                    }
                    lastVersionStatus = createContentParams.status;
                    createContentParams.contentData = getMigratedContentData(migratedContent);
                    if (firstVersionContent.getPublishfrom() != null) {
                        createContentParams.publishFrom = firstVersionContent.getPublishfrom();
                    }
                    if (firstVersionContent.getPublishto() != null) {
                        createContentParams.publishTo = firstVersionContent.getPublishto();
                    }
                    newContentKey = createContentWithImpersonation(firstVersionContent, createContentParams);
                    GetContentParams getContentParams = new GetContentParams();
                    getContentParams.includeData = false;
                    getContentParams.includeVersionsInfo = true;
                    getContentParams.includeOfflineContent = true;
                    getContentParams.contentKeys = new int[]{newContentKey};
                    getContentParams.childrenLevel = 0;
                    Document newContentDoc = targetServerClient.getContent(getContentParams);
                    lastVersionKey = ((Attribute) XPath.selectSingleNode(newContentDoc, "contents/content/@versionkey")).getIntValue();
                    ResponseMessage.addInfoMessage("Created migrated content: " + firstVersionContent.getDisplayName());
                } else {
                    Content newVersionContent = new Content();
                    newVersionContent.parseContent(versionContentEl);
                    migratedContent.setSourceContent(newVersionContent);
                    migratedContent.setSourceContentElement(versionContentEl);
                    UpdateContentParams updateContentParams = new UpdateContentParams();
                    updateContentParams.contentData = getMigratedContentData(migratedContent);
                    updateContentParams.changeComment = versionEl.getChildText("comment");

                    Integer statusKey = newVersionContent.getStatus();
                    updateContentParams.setAsCurrentVersion = isCurrentVersion;

                    if (isLastVersion || isCurrentVersion) {
                        updateContentParams.status = statusKey;
                    } else {
                        updateContentParams.status = ContentStatus.STATUS_ARCHIVED;
                    }

                    /*
                    updateContentParams.status = statusKey;
                    if (statusKey == ContentStatus.STATUS_ARCHIVED || statusKey == ContentStatus.STATUS_APPROVED){
                        updateContentParams.createNewVersion = true;
                    }else if(lastVersionStatus != ContentStatus.STATUS_DRAFT){
                        //API only allowes overwriting draft content version
                        //https://github.com/enonic/cms-ce/blob/e4fd1d8b3eb56cbc096037481ee19d141b22431d/modules/cms-core/src/main/java/com/enonic/cms/core/client/InternalClientContentService.java#L820
                        updateContentParams.createNewVersion = true;
                    }else{
                        updateContentParams.createNewVersion = false;
                        updateContentParams.contentVersionKey = lastVersionKey;
                    }
                    lastVersionStatus = statusKey;
                    */

                    updateContentParams.updateStrategy = ContentDataInputUpdateStrategy.REPLACE_ALL;
                    updateContentParams.contentKey = newContentKey;

                    if (newVersionContent.getPublishfrom() != null) {
                        updateContentParams.publishFrom = newVersionContent.getPublishfrom();
                    }
                    if (newVersionContent.getPublishto() != null) {
                        updateContentParams.publishTo = newVersionContent.getPublishto();
                    }
                    try {
                        lastVersionKey = updateMigratedCustomContentWithImpersonation(newVersionContent, updateContentParams);
                        ResponseMessage.addInfoMessage("Updated migrated content: " + newVersionContent.getDisplayName());
                    } catch (Exception e) {
                        ResponseMessage.addWarningMessage("Exception when updating migrated content version");
                    }
                }

            }
        } catch (JDOMException e) {
            ResponseMessage.addWarningMessage("could not get versions for content");
        }
        return newContentKey;
    }

    private Document getVersionDoc(int[] versionKeys) {
        RemoteClient sourceserverClient = getSourceserverClient();
        GetContentVersionsParams getContentVersionsParams = new GetContentVersionsParams();
        getContentVersionsParams.contentVersionKeys = versionKeys;
        getContentVersionsParams.contentRequiredToBeOnline = false;
        getContentVersionsParams.childrenLevel = 0;
        return sourceserverClient.getContentVersions(getContentVersionsParams);
    }

    //Fetch the import config named 'ccontent' or 'ccontent-{source-contenttype}'
    private Element getImportConfig(MigratedContent migratedContent) {
        if (importConfigs.containsKey(migratedContent.getTargetContenttype().getName())) {
            return importConfigs.get(migratedContent.getTargetContenttype().getName());
        }

        Element importConfig = null;
        try {
            importConfig = (Element) XPath.selectSingleNode(migratedContent.getTargetContenttypeDoc(),
                    "//imports/import[@name='ccontent']");

            if (importConfig == null) {
                ResponseMessage.addInfoMessage("No generic import config fount. Check if contenttype has an import config named 'ccontent-" +
                        migratedContent.getSourceContenttype().getName() + "'");
                importConfig = (Element) XPath.selectSingleNode(migratedContent.getTargetContenttypeDoc(),
                        "//imports/import[@name='ccontent-" + migratedContent.getSourceContenttype().getName() + "']");
            }
        } catch (Exception e) {
            ResponseMessage.addErrorMessage("Skipping. No import config for contenttype " + migratedContent.getTargetContenttype().getName());
        }
        importConfigs.put(migratedContent.getTargetContenttype().getName(), importConfig);
        return importConfig;
    }

    private void copyFile(MigratedContent migratedContent, Content sourceContent) throws Exception {
        ResponseMessage.addInfoMessage("Copy file: " + sourceContent.getDisplayName());

        RemoteClient targetserverClient = getTargetserverClient();
        RemoteClient sourceserverClient = getSourceserverClient();

        GetContentBinaryParams getContentBinaryParams = new GetContentBinaryParams();
        getContentBinaryParams.contentKey = sourceContent.getKey();
        Document contentBinary = null;

        //contentBinary = sourceserverClient.getContentBinary(getContentBinaryParams);
        contentBinary = getContentBinaryWithFix4ClientExceptionWhenArchived(sourceserverClient, getContentBinaryParams);

        if (contentBinary == null) {
            ResponseMessage.addWarningMessage("File binary not found, skipping file");
            return;
        }

        final String binaryString = ((Element) XPath.selectSingleNode(contentBinary, "binary/data")).getText();
        final byte[] binaryData = Base64.decodeBase64(binaryString);
        String binaryName = ((Element) XPath.selectSingleNode(contentBinary, "binary/filename")).getValue();

        FileBinaryInput fileBinaryInput = new FileBinaryInput(binaryData, binaryName);
        FileNameInput fileNameInput = new FileNameInput(sourceContent.getDisplayName());
        FileDescriptionInput fileDescriptionInput = new FileDescriptionInput(((Element) XPath.selectSingleNode(migratedContent.getSourceContentElement(), "contentdata/description")).getValue());
        FileKeywordsInput fileKeywordsInput = new FileKeywordsInput();
        List<Element> fileKeywords = XPath.selectNodes(migratedContent.getSourceContentElement(), "contentdata/keywords");
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
        Element existingMigratedContent = getExistingContentHandler().getExistingMigratedContentOrCategory(sourceContent.getKey(), "file");

        //TODO: Implement updating of files?
        if (existingMigratedContent != null) {
            ResponseMessage.addInfoMessage("File already exists, skipping..");
            return;
        }

        CreateFileContentParams createFileContentParams = new CreateFileContentParams();
        createFileContentParams.categoryKey = migratedContent.getTargetCategoryKey();

        if (sourceContent.getPublishfrom() != null) {
            createFileContentParams.publishFrom = sourceContent.getPublishfrom();
        }
        if (sourceContent.getPublishto() != null) {
            createFileContentParams.publishTo = sourceContent.getPublishto();
        }

        if (sourceContent.getStatus() != null) {
            createFileContentParams.status = sourceContent.getStatus();
        }
        createFileContentParams.fileContentData = fileContentDataInput;

        try {
            if (!sourceContent.isModifierDeleted() && isImpersonationAllowed(sourceContent.getModifierQN(), targetserverClient)) {
                targetserverClient.impersonate("#" + sourceContent.getModifierKey());
            }
        } catch (Exception e) {
            ResponseMessage.addErrorMessage("Error when impersonating " + sourceContent.getModifierKey());
            LOG.error("Error when impersonating src owner", e);
        }

        targetContentKey = targetserverClient.createFileContent(createFileContentParams);
        migratedContent.setTitle(sourceContent.getDisplayName());
        migratedContent.setType("file");
        migratedContent.setSourceContentKey(sourceContent.getKey());
        migratedContent.setTargetContentKey(targetContentKey);
        migratedContent.setSourceContent(sourceContent);
        createMigratedContent(migratedContent);
        targetserverClient.removeImpersonation();

    }

    private void copyImage(MigratedContent migratedContent, Content sourceContent) throws Exception {

        RemoteClient targetserverClient = getTargetserverClient();
        RemoteClient sourceserverClient = getSourceserverClient();

        ResponseMessage.addInfoMessage("Copy image " + sourceContent.getDisplayName());
        Integer imageBinaryKey = ((Attribute) XPath.selectSingleNode(migratedContent.getSourceContentElement(), "contentdata/sourceimage/binarydata/@key")).getIntValue();

        GetContentBinaryParams getContentBinaryParams = new GetContentBinaryParams();
        getContentBinaryParams.contentKey = sourceContent.getKey();
        getContentBinaryParams.label = "source";
        Document contentBinary = null;

        contentBinary = getContentBinaryWithFix4ClientExceptionWhenArchived(sourceserverClient, getContentBinaryParams);

        if (contentBinary == null) {
            ResponseMessage.addWarningMessage("Image binary not found, skipping image");
            return;
        }

        final String binaryString = ((Element) XPath.selectSingleNode(contentBinary, "binary/data")).getText();

        byte[] binaryData = Base64.decodeBase64(binaryString);

        String binaryName = ((Element) XPath.selectSingleNode(contentBinary, "binary/filename")).getValue();

        ImageBinaryInput imageBinaryInput = new ImageBinaryInput(binaryData, binaryName);
        ImageNameInput imageNameInput = new ImageNameInput(sourceContent.getDisplayName());
        ImageDescriptionInput imageDescriptionInput = new ImageDescriptionInput(((Element) XPath.selectSingleNode(migratedContent.getSourceContentElement(), "contentdata/description")).getValue());
        ImageKeywordsInput imageKeywordsInput = new ImageKeywordsInput();
        List<Element> imageKeywords = XPath.selectNodes(migratedContent.getSourceContentElement(), "contentdata/keywords");
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
        Element existingMigratedContent = getExistingContentHandler().getExistingMigratedContentOrCategory(sourceContent.getKey(), "image");

        if (existingMigratedContent != null) {
            //TODO: Implement updating of images?
            ResponseMessage.addInfoMessage("Image already exists, skipping..");
            return;
        }
        CreateImageContentParams createImageContentParams = new CreateImageContentParams();
        createImageContentParams.contentData = imageContentDataInput;
        createImageContentParams.categoryKey = migratedContent.getTargetCategoryKey();
        createImageContentParams.changeComment = "Copy image from old installation";
        if (sourceContent.getPublishfrom() != null) {
            createImageContentParams.publishFrom = sourceContent.getPublishfrom();
        }
        if (sourceContent.getPublishto() != null) {
            createImageContentParams.publishTo = sourceContent.getPublishto();
        }
        if (sourceContent.getStatus() != null) {
            createImageContentParams.status = sourceContent.getStatus();
        }
        try {
            if (!sourceContent.isModifierDeleted() && isImpersonationAllowed(sourceContent.getModifierQN(), targetserverClient)) {
                targetserverClient.impersonate("#" + sourceContent.getModifierKey());
            }
        } catch (Exception e) {
            LOG.error("Error when impersonating", e);
        }

        targetContentKey = targetserverClient.createImageContent(createImageContentParams);
        migratedContent.setTitle(sourceContent.getDisplayName());
        migratedContent.setType("image");
        migratedContent.setSourceContentKey(sourceContent.getKey());
        migratedContent.setTargetContentKey(targetContentKey);
        migratedContent.setSourceContent(sourceContent);
        createMigratedContent(migratedContent);
        targetserverClient.removeImpersonation();
    }

    private Document getContentBinaryWithFix4ClientExceptionWhenArchived(RemoteClient sourceserverClient, GetContentBinaryParams getContentBinaryParams) {
        Document contentBinary = null;
        try {
            contentBinary = sourceserverClient.getContentBinary(getContentBinaryParams);
        } catch (ClientException cx) {
            if (contentBinary == null && cx.getMessage() != null && cx.getMessage().contains("Attachment not found")) ;
            {
                ResponseMessage.addInfoMessage("Known bug encountered. A binary could not be fetched because it is archived");
                //Approve binary content temporarily, so it can be fetched
                UpdateContentParams updateContentParams = new UpdateContentParams();
                updateContentParams.contentKey = getContentBinaryParams.contentKey;
                updateContentParams.status = ContentStatus.STATUS_APPROVED;
                updateContentParams.publishFrom = new Date();
                updateContentParams.updateStrategy = ContentDataInputUpdateStrategy.REPLACE_NEW;
                sourceserverClient.updateContent(updateContentParams);
                //Fetch it
                contentBinary = sourceserverClient.getContentBinary(getContentBinaryParams);
                //Set the status of content back to 'archived' and log result
                updateContentParams.status = ContentStatus.STATUS_ARCHIVED;
                sourceserverClient.updateContent(updateContentParams);
                if (contentBinary != null) {
                    ResponseMessage.addInfoMessage("Applied fix for known bug with 'attachment not found'. Approved it; migrated it; re-archived it");
                } else {
                    ResponseMessage.addWarningMessage("Applied bug-fix code for 'attachment not found', but it is still null!");
                }
            }
        }
        return contentBinary;
    }


    private boolean isContenttypeMappingOk(MigratedContent migratedContent) {

        if (!contenttypeMap.containsKey(migratedContent.getSourceContenttype())) {
            ResponseMessage.addInfoMessage("No contenttype mapping exists for contenttype " + migratedContent.getSourceContenttype().getName() + "(" + migratedContent.getSourceContenttype().getKey() + ")");
            return false;
        }
        migratedContent.setTargetContenttype(contenttypeMap.get(migratedContent.getSourceContenttype()));

        if (!migratedContent.isContenttypeMappingOk()) {
            ResponseMessage.addWarningMessage("Contenttypes are not correctly mapped, aborting copy of content");
            return false;
        }
        ResponseMessage.addInfoMessage("Migrate source contenttype " + migratedContent.getSourceContenttype().getName() + " to target contenttype " + migratedContent.getTargetContenttype().getName());
        return true;
    }

    private boolean hasNoContent(Document document) throws JDOMException {
        if (XPath.selectSingleNode(document, "contents/content") == null) {
            return true;
        }
        return false;
    }

    private boolean isImpersonationAllowed(String qName, RemoteClient targetserverClient) {
        if ("admin".equalsIgnoreCase(qName)) {
            return false;
        }
        if ("anonymous".equalsIgnoreCase(qName)) {
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
        } catch (Exception e) {
            ResponseMessage.addErrorMessage("User does not exist, impersonation is not allowed for \" + qName");
            return false;
        }
        if (userDoc != null && userDoc.hasRootElement()) {
            ResponseMessage.addInfoMessage("User exist, impersonation is allowed for " + qName);
            return true;
        }
        ResponseMessage.addInfoMessage("User does not exist, impersonation is not allowed for " + qName);
        return false;
    }


    private void createMigratedContent(MigratedContent migratedContent) {
        CreateContentParams createMigratedContentParams = new CreateContentParams();
        createMigratedContentParams.categoryKey = Integer.parseInt((String) pluginEnvironment.getSharedObject("context_migratedcontentcategory"));
        ContentDataInput migratedContentData = new ContentDataInput("migrated-content");
        migratedContentData.add(new TextInput("oldkey", String.valueOf(migratedContent.getSourceContentKey())));
        migratedContentData.add(new TextInput("newkey", String.valueOf(migratedContent.getTargetContentKey())));
        migratedContentData.add(new TextInput("oldcontenttype", migratedContent.getSourceContenttype() != null ? migratedContent.getSourceContenttype().getName() : ""));
        migratedContentData.add(new TextInput("newcontenttype", migratedContent.getTargetContenttype() != null ? migratedContent.getTargetContenttype().getName() : ""));
        migratedContentData.add(new TextInput("title", migratedContent.getTitle()));
        migratedContentData.add(new TextInput("type", migratedContent.getType()));
        migratedContentData.add(new XmlInput("oldownerxml", migratedContent.getSourceOwnerXml()));
        migratedContentData.add(new XmlInput("oldmodifierxml", migratedContent.getSourceModifierXml()));
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
