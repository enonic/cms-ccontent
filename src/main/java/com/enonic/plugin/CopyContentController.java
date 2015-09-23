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
    List<ResponseMessage> responseMessages = new ArrayList<ResponseMessage>();
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
            addInfoMessage("Authentication for remote client successful");
        } catch (Exception e) {
            addErrorMessage("Authentication for remote client failed");
        }

        try {
            Client localClient = ClientFactory.getRemoteClient(targetserverUrl);
            localClient.logout();
            localClient.login(targetserverUsername, targetserverPassword);
            addInfoMessage("Authentication for local client successful");
        } catch (Exception e) {
            addErrorMessage("Authentication for local client failed");
        }


    }


    public RemoteClient getSourceserverClient() {
        if (Strings.isNullOrEmpty((String) pluginEnvironment.getSharedObject("context_sourceserverUrl"))) {
            return null;
        }
        RemoteClient remoteClient = ClientFactory.getRemoteClient((String) pluginEnvironment.getSharedObject("context_sourceserverUrl"));
        try {
            remoteClient.logout();
            String sourceserverUsername = (String) pluginEnvironment.getSharedObject("context_sourceserverUsername");
            String sourceserverPassword = (String) pluginEnvironment.getSharedObject("context_sourceserverPassword");
            remoteClient.login(sourceserverUsername, sourceserverPassword);
        } catch (Exception e) {
            addWarningMessage("Exception when getting remote client. " + e);
        }
        return remoteClient;
    }

    public RemoteClient getTargetserverClient() {
        if (Strings.isNullOrEmpty((String) pluginEnvironment.getSharedObject("context_targetserverUrl"))) {
            return null;
        }
        RemoteClient localClient = ClientFactory.getRemoteClient((String) pluginEnvironment.getSharedObject("context_targetserverUrl"));
        try {
            localClient.logout();
            String targetserverUsername = (String) pluginEnvironment.getSharedObject("context_targetserverUsername");
            String targetserverPassword = (String) pluginEnvironment.getSharedObject("context_targetserverPassword");
            localClient.login(targetserverUsername, targetserverPassword);
        } catch (Exception e) {
            addWarningMessage("Exception when getting remote client. " + e);
        }
        return localClient;
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
        context.setVariable("messages", responseMessages);

        try {
            templateEngineProvider.setApplicationContext(applicationContext);
            templateEngineProvider.get().process("index", context, response.getWriter());
        } catch (Exception e) {
            addErrorMessage("Error when rendring thymeleaf template");
            templateEngineProvider.get().process("errors/404", context, response.getWriter());
        }
        responseMessages.clear();

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
                    addWarningMessage("Source contenttype not correct!");
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
                    addWarningMessage("Could not create contenttype mapping!");
                }

            }
        }

        if (sourceCategoryKey == null) {
            addWarningMessage("Source category key is not set!");
            return;
        }

        Category topCategory = sourceCategory;//getCategoryFolder(sourceCategoryKey, remoteClient, true);

        if (topCategory == null) {
            addWarningMessage("Given source category key yielded no results!");
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
            addWarningMessage("Returning null from getContenttypeDoc");
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
            addWarningMessage("Contenttype " + contenttypeName);
            return null;
        }else{

        }*/
        try {
            Integer key = ((Attribute) XPath.selectSingleNode(resultDoc, "contents/content/@contenttypekey")).getIntValue();
            String name = ((Attribute) XPath.selectSingleNode(resultDoc, "contents/content/@contenttype")).getValue();

            result.setKey(key);
            result.setName(name);
        } catch (NullPointerException e) {
            addWarningMessage("No content exists of contenttype name:" + contenttypeName + " key:" + contenttypeKey + ". This is a prerequisite for creating a mapping!");
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
        responseMessages.add(new ResponseMessage("" + fileCopyProgressCounter, "progress"));
        mapper.writeValue(context.getHttpServletResponse().getOutputStream(), responseMessages);
        responseMessages.clear();
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
            addInfoMessage("1. Create categories");
            createCategories(sourceCategory, targetCategory.getKey());
        } else {
            addInfoMessage("Source and target category are not set correctly! Cancelling..");
        }
    }

    public void ajaxabortcopy(WebContext context) throws Exception {
        abortCopy = true;
    }


    private void purgeTargetCategory() throws Exception {

        Iterator<Category> targetCategories = targetCategory.categories.iterator();
        if (!targetCategories.hasNext()) {
            addInfoMessage("Target category " + targetCategory.getTitle() + " has no subfolders");
        }
        addInfoMessage("Delete " + targetCategory.categories.size() + " categories");
        while (targetCategories.hasNext()) {
            Category category = targetCategories.next();
            addInfoMessage("Target category name, key = " + targetCategory.getTitle() + " " + targetCategory.getKey());
            addInfoMessage("SubCategory name, superkey = " + category.getTitle() + " " + category.getSuperKey());

            if (category.getSuperKey() == null || category.getSuperKey() != null && category.getSuperKey().intValue() == targetCategory.getKey().intValue()) {
                Integer categoryKey = category.getKey();
                deleteCategory(categoryKey);
                addInfoMessage("Deleted target category " + category.getTitle());
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
                    addWarningMessage("Could not delete mmigrated-content ");
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
                    addErrorMessage("No valid mapping exists for contenttype " + sourceContenttype.getName() + " (" + sourceContenttype.getKey() + "). Will skip content creation.");
                } else {
                    addInfoMessage("Source '" + category.getTitle() + "' (" + category.getContenttype() + ", " + category.getContenttypeKey() + "), Target: '" + category.getTitle() + "' (" + targetContenttype.getName() + ", " + targetContenttype.getKey() + ")");
                }
            } else {
                addInfoMessage("Source contenttype is null for category, will not create content");
            }
        } else {
            addWarningMessage("No contenttype mapping for category");
        }

        Integer targetCategoryKey = null;
        addInfoMessage("Check if category is already migrated");
        Element existingCategory = getExistingMigratedContentOrCategory(category.getKey(), "category");

        if (existingCategory == null) {
            addInfoMessage("Create category " + category.getTitle() + " from skratch");
            CreateCategoryParams createCategoryParams = new CreateCategoryParams();
            createCategoryParams.name = category.getTitle();

            if (targetContenttype != null && targetContenttype.getKey() != null) {
                createCategoryParams.contentTypeKey = targetContenttype.getKey();
            }
            addInfoMessage("Create new category under parent category key " + parentCategoryKey);
            createCategoryParams.parentCategoryKey = parentCategoryKey;

            try {
                targetCategoryKey = getTargetserverClient().createCategory(createCategoryParams);
                addInfoMessage("Created category " + category.getTitle() + " with id " + targetCategoryKey);
                createMigratedContent(category.getTitle(), "category", category.getKey(), targetCategoryKey, sourceContenttype, targetContenttype);
                addInfoMessage("Created migrated-content contenttype for category");

            } catch (Exception e) {
                addErrorMessage("Exception while creating category");
            }
        } else {
            addInfoMessage("Get previously migrated category");
            targetCategoryKey = Integer.parseInt(((Element) XPath.selectSingleNode(existingCategory, "//newkey")).getValue());
        }

        if (targetContenttype != null && copyContent) {
            addInfoMessage("Will copy content for category " + category.getTitle());
            copyContent(category.getKey(), targetCategoryKey, sourceserverClient);
        } else {
            addInfoMessage("Will not copy content for category " + category.getTitle());
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

    private void copyContent(Integer sourceCategoryKey, Integer targetCategoryKey, RemoteClient sourceserverClient) throws Exception {

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
            addInfoMessage("Category with key " + sourceCategoryKey + " has no content to be copied, moving on");
            return;
        }

        Contenttype sourceContenttype = getContenttype(sourceContenttypeKey, null, sourceserverClient);

        if (!contenttypeMap.containsKey(sourceContenttype)) {
            addInfoMessage("No contenttype mapping exists for contenttype " + sourceContenttype.getName() + "(" + sourceContenttype.getKey() + ")");
            return;
        }

        Contenttype targetContenttype = contenttypeMap.get(sourceContenttype);


        if (sourceContenttype == null || targetContenttype == null || sourceContenttype.getKey() == null || targetContenttype.getKey() == null) {
            addWarningMessage("Contenttypes are not correctly mapped, aborting copy of content");
            return;
        } else {
            addInfoMessage("Migrate source contenttype " + sourceContenttype.getName() + " to target contenttype " + targetContenttype.getName());
        }
        addInfoMessage("Get source and target contenttype doc for source and target keys " + sourceContenttype.getKey() + " " + targetContenttype.getKey());

        Document sourceContenttypeDoc = getContenttypeDoc(sourceContenttype.getKey(), null, sourceserverClient);
        Document targetContenttypeDoc = getContenttypeDoc(targetContenttype.getKey(), null, getTargetserverClient());

        if (sourceContenttypeDoc == null) {
            addErrorMessage("Source contenttype doc is null, aborting");
            return;
        }
        if (targetContenttypeDoc == null) {
            addErrorMessage("Target contenttype doc is null, aborting");
            return;
        }

        List<Element> contentElements = XPath.selectNodes(document, "contents/content");
        int numberOfContentToCopy = contentElements.size();
        addInfoMessage("Copy " + numberOfContentToCopy + " content..");

        if (contentElements == null || contentElements.isEmpty()) {
            return;
        }

        //TODO:Fetch the import config named 'ccontent', only nescessary for content, can be optimized
        addInfoMessage("Check if contenttype has an import config named 'ccontent'");
        Element importConfig = null;
        importConfig = (Element) XPath.selectSingleNode(targetContenttypeDoc, "//imports/import[@name='ccontent']");
        if (importConfig == null) {
            addInfoMessage("No import config. Check if contenttype has an import config named 'ccontent-" + sourceContenttype.getName() + "'");
            //Fetch the import config especially for this sourceContentType, in case of n-to-1 mapping between contenttypes
            importConfig = (Element) XPath.selectSingleNode(targetContenttypeDoc, "//imports/import[@name='ccontent-" + sourceContenttype.getName() + "']");
        }

        Iterator<Element> contentElementsIt = contentElements.iterator();

        while (contentElementsIt.hasNext()) {
            if (abortCopy) {
                break;
            }
            String modifierName = null;
            String modifierKey = null;
            String displayName = null;
            try {
                int count = 0;
                Element contentEl = contentElementsIt.next();
                if (contentEl == null) {
                    addWarningMessage("Content element is null, skipping content..");
                    continue;
                }
                Element displayNameEl = ((Element) XPath.selectSingleNode(contentEl, "display-name"));
                if (displayNameEl == null) {
                    addWarningMessage("Displayname is null, this should always be set, skipping content");
                    continue;
                }
                displayName = displayNameEl.getValue();

                Integer status = ((Attribute) XPath.selectSingleNode(contentEl, "@status")).getIntValue();
                Date publishFromDate = null;
                try {
                    publishFromDate = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).parse(((Attribute) XPath.selectSingleNode(contentEl, "@publishfrom")).getValue());
                } catch (Exception e) {
                    addWarningMessage("Exception when setting publishFromDate");
                }

                Integer sourceContentKey = ((Attribute) XPath.selectSingleNode(contentEl, "@key")).getIntValue();
                String contenttype = ((Attribute) XPath.selectSingleNode(contentEl, "@contenttype")).getValue();

                boolean isImage = XPath.selectSingleNode(contentEl, "contentdata/sourceimage") != null;
                boolean isFile = XPath.selectSingleNode(contentEl, "contentdata/filesize") != null;

                Document migratedContentDoc = null;
                boolean isAlreadyMigrated = false;
                boolean isMigratedContentModifiedByCustomer = false;

                String lastModifiedByKeyNorwegianVersion = null;

                if (!isImage && !isFile) {
                    List<Element> existingMigratedContents = getExistingMigratedContents(sourceContentKey, "content");
                    if (existingMigratedContents == null) {
                        break;
                    }
                    if (existingMigratedContents.size() == 1) {
                        addInfoMessage(displayName + " is already migrated once");
                        isAlreadyMigrated = true;
                    }
                    if (existingMigratedContents.size() > 1) {
                        addWarningMessage(displayName + " exists in more then one version on target server, something is wrong");
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
                                migratedContentDoc = getTargetserverClient().getContent(getContentParams);
                                Element migratedContentModifierNameEl = (Element) XPath.selectSingleNode(migratedContentDoc, "contents/content/modifier/name");
                                if (migratedContentModifierNameEl != null) {
                                    modifierName = migratedContentModifierNameEl.getValue();
                                }
                                Attribute modifierKeyAttr = (Attribute) XPath.selectSingleNode(migratedContentDoc, "contents/content/modifier/@key");
                                if (modifierKeyAttr != null) {
                                    modifierKey = modifierKeyAttr.getValue();
                                }
                            }
                        }
                    }

                    if (overwriteWhenExistingMigratedContentIsModified && modifierName != null && !modifierName.equals(getTargetserverClient().getRunAsUserName())) {
                        LOG.warn("Content '" + displayName + "' is modified by customer " + getTargetserverClient().getRunAsUserName() + ". Skip migrating this content to prevent overwrite!");
                        continue;
                    }
                }

                if (isImage) {
                    addInfoMessage("Copy image " + displayName);
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
                    Element existingMigratedContent = getExistingMigratedContentOrCategory(sourceContentKey, "image");
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

                        targetContentKey = getTargetserverClient().createImageContent(createImageContentParams);
                        createMigratedContent(displayName, "image", sourceContentKey, targetContentKey, sourceContenttype, targetContenttype);
                    } else {
                        addInfoMessage("Image already exists, skipping..");
                    }
                } else if (isFile) {
                    addInfoMessage("Copy file: " + displayName);

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
                    Element existingMigratedContent = getExistingMigratedContentOrCategory(sourceContentKey, "file");
                    if (existingMigratedContent == null) {
                        CreateFileContentParams createFileContentParams = new CreateFileContentParams();
                        createFileContentParams.categoryKey = targetCategoryKey;
                        createFileContentParams.publishFrom = publishFromDate;
                        if (status != null) {
                            createFileContentParams.status = status;
                        }
                        createFileContentParams.fileContentData = fileContentDataInput;

                        targetContentKey = getTargetserverClient().createFileContent(createFileContentParams);
                        createMigratedContent(displayName, "file", sourceContentKey, targetContentKey, sourceContenttype, targetContenttype);
                    } else {
                        addInfoMessage("File already exists, skipping..");
                        UpdateFileContentParams updateFileContentParams = new UpdateFileContentParams();
                        updateFileContentParams.contentKey = Integer.parseInt(((Element) XPath.selectSingleNode(existingMigratedContent, "//newkey")).getValue());
                        updateFileContentParams.fileContentData = fileContentDataInput;
                        if (publishFromDate != null) {
                            updateFileContentParams.publishFrom = publishFromDate;
                        }
                        if (status != null) {
                            updateFileContentParams.status = status;
                        }
                    }
                } else {
                    if (importConfig == null) {
                        addErrorMessage("No import config, cancelling copy..");
                        return;
                    }
                    addInfoMessage("Copy content " + displayName);
                    ContentDataInput contentDataInput = new ContentDataInput(targetContenttype.getName());

                    List<Element> inputMappingListEl = importConfig.getChildren("mapping");
                    List<Element> blockGroupElements = importConfig.getChildren("block");

                    if (inputMappingListEl == null && blockGroupElements == null) {
                        addErrorMessage("No import config mapping, nothing to copy, cancelling copy..");
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
                                        //TODO: Somewhat cluncky design here. Setters dependent on order.
                                        MappingObjectHolder mappingObjectHolder = new MappingObjectHolder();
                                        mappingObjectHolder.setInputMapping(blockGroupInputElementsIt.next());
                                        mappingObjectHolder.setSourceContenttype(sourceContenttype);
                                        mappingObjectHolder.setTargetContenttype(targetContenttype);
                                        mappingObjectHolder.setSourceInputElement(
                                                ((Element) XPath.selectSingleNode(sourceContenttypeDoc, "//input[xpath='" + mappingObjectHolder.getInputMappingSrc() + "']")));
                                        mappingObjectHolder.setTargetInputElement(
                                                ((Element) XPath.selectSingleNode(targetContenttypeDoc, "//input[@name='" + mappingObjectHolder.getInputMappingDest() + "']")));
                                        mappingObjectHolder.setContentInputElement(blockGroupContent.getChild(mappingObjectHolder.getInputMappingSrc()));

                                        addInfoMessage("Add " + mappingObjectHolder.getSourceInputType() + " " + mappingObjectHolder.getInputMappingSrc() + " to " + mappingObjectHolder.getTargetInputType() + " " + mappingObjectHolder.getInputMappingDest());


                                        if (MappingRules.hasSpecialHandling(mappingObjectHolder)) {
                                            addInfoMessage("Handling special input " + mappingObjectHolder.getInputMappingDest() + " for contenttype " + mappingObjectHolder.getTargetContenttype().getName());
                                            addInfoMessage(mappingObjectHolder.toString());
                                            groupInput.add(MappingRules.getInput(mappingObjectHolder));
                                        } else if ("text".equals(mappingObjectHolder.getSourceInputType())) {
                                            groupInput.add(new TextInput(mappingObjectHolder.getInputMappingDest(), blockGroupContent.getChild(mappingObjectHolder.getInputMappingSrc()).getValue()));
                                        } else if ("image".equals(mappingObjectHolder.getSourceInputType())) {
                                            Attribute imageKeyAttr = blockGroupContent.getChild(mappingObjectHolder.getInputMappingSrc()).getAttribute("key");
                                            if (imageKeyAttr != null) {
                                                addInfoMessage("Old image key" + imageKeyAttr.getValue());
                                                Integer newImageKey = getExistingMigratedContentOrCategoryKey(imageKeyAttr.getIntValue(), "image");
                                                if (newImageKey != null) {
                                                    addInfoMessage("New image key" + newImageKey);
                                                    groupInput.add(new ImageInput(mappingObjectHolder.getInputMappingDest(), newImageKey));
                                                }
                                            }
                                        } else if ("checkbox".equals(mappingObjectHolder.getSourceInputType())) {
                                            groupInput.add(new BooleanInput(mappingObjectHolder.getInputMappingDest(), Boolean.parseBoolean(blockGroupContent.getValue())));
                                        } else if ("radiobutton".equals(mappingObjectHolder.getSourceInputType())) {
                                            groupInput.add(new SelectorInput(mappingObjectHolder.getInputMappingDest(), blockGroupContent.getValue()));
                                        } else if ("file".equals(mappingObjectHolder.getSourceInputType())) {
                                            Integer fileKey = null;
                                            try {
                                                fileKey = blockGroupContent.getChild(mappingObjectHolder.getInputMappingSrc()).getChild("file").getAttribute("key").getIntValue();
                                            } catch (Exception e) {
                                            }
                                            if (fileKey == null) {
                                                LOG.warn("File input key was null, ignoring");
                                                continue;
                                            }
                                            Integer newKey = getExistingMigratedContentOrCategoryKey(fileKey, "file");
                                            if (newKey != null) {
                                                addInfoMessage("Found migrated file content, adding to migrated content");
                                                groupInput.add(new FileInput(mappingObjectHolder.getInputMappingDest(), newKey));
                                            }
                                        } else if ("textarea".equals(mappingObjectHolder.getSourceInputType())) {
                                            LOG.warn("Not implemented! migration of text in block group");
                                            //TODO: implement migration of textarea in block group
                                            /*if (targetInputType!=null && targetInputType.equalsIgnoreCase("htmlarea")){
                                                LOG.info("to htmlarea ");
                                                HtmlAreaInput input = new HtmlAreaInput(groupInputDest, sourceInputEl.getValue());
                                                contentDataInput.add(input);
                                            }else{
                                                TextAreaInput input = new TextAreaInput(groupInputDest, sourceInputEl.getValue());
                                                contentDataInput.add(input);
                                            }*/
                                        } else if ("htmlarea".equals(mappingObjectHolder.getSourceInputType())) {
                                            //TODO: implement migration of htmlarea in block group
                                            LOG.warn("Not implemented! migration of htmlarea in block group");
                                            /*List<Element> htmlElements = blockGroupContent.getChild(groupInputSrc).getChildren();

                                            if (htmlElements != null && !htmlElements.isEmpty()) {
                                                try {
                                                    addInfoMessage("Scanning htmlArea " + groupInputDest + " for internal links..");
                                                    scanHtmlAreaForInternalLinks(htmlElements);
                                                } catch (Exception e) {
                                                    addErrorMessage("Error while scanning for internal links" + e);
                                                }
                                                groupInput.add(new HtmlAreaInput(groupInputDest, xmlOutputter.outputString(htmlElements)));
                                            } else {
                                                groupInput.add(new HtmlAreaInput(groupInputDest, xmlOutputter.outputString(blockGroupContent.getChild(groupInputSrc))));
                                            }*/
                                        }
                                    }
                                } catch (Exception e) {
                                    addWarningMessage("Exception when trying to add input field in group " + groupName);
                                }
                            }
                        } catch (Exception e) {
                            addErrorMessage("Exception while handling block inputs" + e);
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
                                addWarningMessage("Could not get source and target input elements from contenttype document. Source/target=" + sourceInput + "/" + destInput);
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

                            addInfoMessage("if/else - checking input type " + mappingObjectHolder.getTargetInputElement().getName());
                            if (MappingRules.hasSpecialHandling(mappingObjectHolder)) {
                                addInfoMessage("Handling special input " + inputMapping.getAttributeValue("dest") + " for contenttype " + targetContenttype.getName());
                                addInfoMessage(mappingObjectHolder.toString());
                                contentDataInput.add(MappingRules.getInput(mappingObjectHolder));
                            } else if ("text".equals(sourceInputType)) {
                                Input input = new TextInput(destInput, contentInputEl.getValue());
                                contentDataInput.add(input);
                            } else if ("url".equals(sourceInputType)) {
                                UrlInput input = new UrlInput(destInput, contentInputEl.getValue());
                                contentDataInput.add(input);
                            } else if ("checkbox".equals(sourceInputType)) {
                                BooleanInput input = new BooleanInput(destInput, Boolean.parseBoolean(contentInputEl.getValue()));
                                contentDataInput.add(input);
                            } else if ("radiobutton".equals(sourceInputType)) {
                                SelectorInput input = new SelectorInput(destInput, contentInputEl.getValue());
                                contentDataInput.add(input);
                            } else if ("dropdown".equals(sourceInputType)) {
                                SelectorInput input = new SelectorInput(destInput, contentInputEl.getValue());
                                contentDataInput.add(input);
                            } else if ("file".equals(sourceInputType)) {
                                Attribute fileKeyAttr = null;
                                Integer fileKey = null;
                                try {
                                    fileKeyAttr = (Attribute) XPath.selectSingleNode(contentInputEl, "file/@key");
                                    fileKey = fileKeyAttr.getIntValue();
                                } catch (Exception e) {
                                }
                                if (fileKeyAttr == null || fileKey == null) {
                                    LOG.warn("File input key was null, ignoring");
                                    continue;
                                }
                                Integer newKey = getExistingMigratedContentOrCategoryKey(fileKey, "file");
                                if (newKey != null) {
                                    addInfoMessage("Found migrated file content, adding to migrated content");
                                    FileInput input = new FileInput(destInput, newKey);
                                    contentDataInput.add(input);
                                }
                            } else if ("files".equals(sourceInputType)) {
                                DeprecatedFilesInput input = new DeprecatedFilesInput(destInput);
                                Attribute fileKeyAttr = null;
                                try {
                                    List<Attribute> fileKeys = XPath.selectNodes(contentInputEl, "file/@key");
                                    Iterator<Attribute> fileKeysIt = fileKeys.iterator();
                                    while (fileKeysIt.hasNext()) {
                                        Attribute fileKey = fileKeysIt.next();
                                        if (fileKey == null) {
                                            LOG.warn("File input key was null, ignoring");
                                            continue;
                                        }
                                        Integer newKey = getExistingMigratedContentOrCategoryKey(fileKey.getIntValue(), "file");
                                        if (newKey != null) {
                                            addInfoMessage("Found migrated file content, adding to migrated content");
                                            input.addFile(newKey);
                                        }
                                    }
                                    if (input.getFiles() != null && !input.getFiles().isEmpty()) {
                                        contentDataInput.add(input);
                                    }
                                } catch (Exception e) {
                                }
                            } else if ("uploadfile".equals(sourceInputType)) {
                                Attribute binaryKeyAttr = null;
                                try {
                                    binaryKeyAttr = (Attribute) XPath.selectSingleNode(contentInputEl, "binarydata/@key");
                                    if (binaryKeyAttr != null && binaryKeyAttr.getValue() != null) {
                                        Integer binaryKey = binaryKeyAttr.getIntValue();
                                        addInfoMessage("Found binarydata with source key " + binaryKey);

                                        GetBinaryParams getBinaryParams = new GetBinaryParams();
                                        getBinaryParams.binaryKey = binaryKey;
                                        Document binaryDoc = getTargetserverClient().getBinary(getBinaryParams);
                                        String fileName = ((Element) XPath.selectSingleNode(binaryDoc, "//filename")).getValue();
                                        String base64EncodedBinaryString = binaryDoc.getRootElement().getChild("data").getText().trim();

                                        LOG.info("destInput {}", destInput);
                                        LOG.info("fileName {}", fileName);

                                        BinaryInput binaryInput = new BinaryInput(destInput, base64EncodedBinaryString.getBytes(), fileName);
                                        contentDataInput.add(binaryInput);
                                    }
                                } catch (Exception e) {
                                    addWarningMessage("Exception when trying to get binary content" + e);
                                }

                            } else if ("date".equals(sourceInputType)) {
                                try {
                                    Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(contentInputEl.getValue());
                                    DateInput input = new DateInput(destInput, date);
                                    contentDataInput.add(input);
                                } catch (Exception e) {
                                    addWarningMessage("Exception when parsing date for input " + sourceInput + "/" + destInput);
                                }
                            } else if ("textarea".equals(sourceInputType)) {
                                LOG.info("Migrate textarea {}", sourceInput);
                                if (targetInputType != null && targetInputType.equalsIgnoreCase("htmlarea")) {
                                    LOG.info("to htmlarea ");
                                    HtmlAreaInput input = new HtmlAreaInput(destInput, contentInputEl.getValue());
                                    contentDataInput.add(input);
                                } else {
                                    TextAreaInput input = new TextAreaInput(destInput, contentInputEl.getValue());
                                    contentDataInput.add(input);
                                }
                            } else if ("htmlarea".equals(sourceInputType)) {
                                if (!(("keymessages".equals(destInput) || "summary".equals(destInput)) && isMigratedContentModifiedByCustomer)) {
                                    List<Element> htmlElements = contentInputEl.getChildren();

                                    if (htmlElements != null && !htmlElements.isEmpty()) {
                                        try {
                                            addInfoMessage("Scanning htmlArea " + destInput + " for internal links..");
                                            scanHtmlAreaForInternalLinks(htmlElements);
                                        } catch (Exception e) {
                                            addErrorMessage("Error while scanning for internal links" + e);
                                        }
                                        //addInfoMessage("Add htmlElements " + htmlElements + " to contentDataInput");

                                        contentDataInput.add(new HtmlAreaInput(destInput, xmlOutputter.outputString(htmlElements)));
                                    } else {
                                        contentDataInput.add(new HtmlAreaInput(destInput, xmlOutputter.outputString(contentInputEl)));
                                    }
                                } else {
                                    addInfoMessage("Content " + displayName + " has been modified by customer, don't overwrite manual changes for keymessages or summary for KS-publications!");
                                }
                            } else if ("relatedcontent".equals(sourceInputType)) {
                                String multiple = targetInputEl.getAttributeValue("multiple");
                                if ((multiple == null || "true".equals(multiple)) && "relatedcontent".equals(targetInputType)) {
                                    List<Element> relatedContentKeys = XPath.selectNodes(contentEl, "contentdata/" + sourceInput + "/content");
                                    if (relatedContentKeys != null && !relatedContentKeys.isEmpty()) {
                                        RelatedContentsInput relatedContentsInput = new RelatedContentsInput(destInput);
                                        Iterator<Element> relatedContentKeysIt = relatedContentKeys.iterator();
                                        while (relatedContentKeysIt.hasNext()) {
                                            Attribute oldRelatedContentKey = relatedContentKeysIt.next().getAttribute("key");
                                            if (oldRelatedContentKey != null) {
                                                Element existingMigratedContent = getExistingMigratedContentOrCategory(Integer.parseInt(oldRelatedContentKey.getValue()), "relatedcontent");
                                                if (existingMigratedContent != null) {
                                                    Element newKeyEl = ((Element) XPath.selectSingleNode(existingMigratedContent, "contentdata/newkey"));
                                                    if (newKeyEl != null) {
                                                        relatedContentsInput.addRelatedContent(Integer.parseInt(newKeyEl.getValue()));
                                                    }
                                                }
                                            }
                                        }
                                        if (!relatedContentsInput.getRelatedContents().isEmpty()) {
                                            contentDataInput.add(relatedContentsInput);
                                        }
                                    }
                                } else {
                                    Attribute relatedContentKeyAttr = (Attribute) XPath.selectSingleNode(contentEl, "contentdata/" + sourceInput + "/@key");
                                    if (relatedContentKeyAttr == null) {
                                        relatedContentKeyAttr = (Attribute) XPath.selectSingleNode(contentEl, "contentdata/" + sourceInput + "/content/@key");
                                    }
                                    if (relatedContentKeyAttr != null) {
                                        Element existingMigratedContent = getExistingMigratedContentOrCategory(relatedContentKeyAttr.getIntValue(), "content");
                                        if (existingMigratedContent != null && relatedContentKeyAttr != null) {
                                            Element newKeyEl = ((Element) XPath.selectSingleNode(existingMigratedContent, "contentdata/newkey"));
                                            if (newKeyEl != null) {
                                                //handle special case with conversion from relatedcontent to text
                                                if ("text".equals(targetInputType)) {
                                                    addInfoMessage("Convert relatedcontent entry to text entry!");
                                                    Element titleEl = (Element) XPath.selectSingleNode(existingMigratedContent, "display-name");
                                                    if (titleEl != null) {
                                                        contentDataInput.add(new TextInput(destInput, titleEl.getValue()));
                                                    }
                                                } else {
                                                    contentDataInput.add(new RelatedContentInput(destInput, Integer.parseInt(newKeyEl.getValue())));
                                                }
                                            }
                                        }
                                    }
                                }

                            } else if ("image".equals(sourceInputType)) {
                                addInfoMessage("Get image content " + sourceInput);
                                Attribute imageKeyAttr = (Attribute) XPath.selectSingleNode(contentEl, "contentdata/" + sourceInput + "/@key");
                                if (imageKeyAttr != null) {
                                    addInfoMessage("Old image key" + imageKeyAttr.getValue());
                                    Element existingMigratedContent = getExistingMigratedContentOrCategory(imageKeyAttr.getIntValue(), "image");
                                    if (existingMigratedContent != null) {
                                        Element newKeyEl = ((Element) XPath.selectSingleNode(existingMigratedContent, "contentdata/newkey"));
                                        if (newKeyEl != null) {
                                            addInfoMessage("New image key" + newKeyEl.getValue());
                                            ImageInput input = new ImageInput(destInput, Integer.parseInt(newKeyEl.getValue()));
                                            contentDataInput.add(input);
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            addWarningMessage("Exception when copying input '" + sourceInput + "'");
                            LOG.error("{]", e);
                        }

                    }

                    Element existingMigratedContent = getExistingMigratedContentOrCategory(sourceContentKey, "content");

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
                        Integer targetContentKey = getTargetserverClient().createContent(createContentParams);
                        createMigratedContent(displayName, "content", sourceContentKey, targetContentKey, sourceContenttype, targetContenttype);
                    } else {
                        LOG.info("Content exists, update it");

                        UpdateContentParams updateContentParams = new UpdateContentParams();
                        Element newKeyEl = ((Element) XPath.selectSingleNode(existingMigratedContent, "contentdata/newkey"));
                        if (newKeyEl == null) {
                            addWarningMessage("Could not find newkey for existing content, aborting update..");
                            continue;
                        }
                        LOG.info("New key is " + newKeyEl.getValue());
                        updateContentParams.contentKey = Integer.parseInt((newKeyEl.getValue()));
                        updateContentParams.changeComment = "ccontent plugin updated content from content with key " + sourceContentKey;
                        updateContentParams.updateStrategy = ContentDataInputUpdateStrategy.REPLACE_NEW;

                        if (isMigratedContentModifiedByCustomer && lastModifiedByKeyNorwegianVersion != null) {
                            try {
                                getTargetserverClient().impersonate("#" + lastModifiedByKeyNorwegianVersion);
                            } catch (Exception e) {
                            }
                        }
                        if (publishFromDate != null) {
                            updateContentParams.publishFrom = publishFromDate;
                        }
                        if (status != null) {
                            updateContentParams.status = status;
                        }

                        updateContentParams.contentData = contentDataInput;
                        getTargetserverClient().updateContent(updateContentParams);
                        getTargetserverClient().removeImpersonation();
                        LOG.info("Content updated, publishdate is " + updateContentParams.publishFrom);


                    }
                }
            } catch (Exception e) {
                addErrorMessage("Exception!" + e.getMessage());
                LOG.error("Error when copying binary content!!!!! From sourcecategorykey = {}, {}", sourceCategoryKey, e);
            }
            fileCopyProgressCounter++;
        }
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
                            addInfoMessage(htmlAttributeValue + " contains " + internalLink);
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

                            addInfoMessage("Guessed old key " + guessedOldKey);
                            if (StringUtils.isNumeric(guessedOldKey)) {
                                //check if content of type custom content, file, image exists
                                addInfoMessage("Check if content is migrated for " + internalLink.replace("://", ""));
                                Element existingMigratedContent = getExistingMigratedContentOrCategory(Integer.parseInt(guessedOldKey), internalLink.replace("://", ""));

                                if (existingMigratedContent == null && internalLink.contains("attachment")) {
                                    //TODO: Doing this because attachment links can be either images, files or content. Check if contentkeys are 100% unique across different types.
                                    existingMigratedContent = performExtraCheckForAttachments(guessedOldKey);
                                }
                                if (existingMigratedContent == null) {
                                    addWarningMessage("linked content not migrated. Replacing " + internalLink + " link with default 'missing key'");
                                    htmlAttributeValue = htmlAttributeValue.replace(guessedOldKey, pluginEnvironment.getSharedObject("context_missing" + internalLink.replace("://", "") + "key").toString());
                                    htmlAttribute.setValue(htmlAttributeValue);
                                } else {
                                    addInfoMessage("Get new key from migrated content");
                                    String newKey = ((Element) XPath.selectSingleNode(existingMigratedContent, "//newkey")).getValue();
                                    htmlAttributeValue = htmlAttributeValue.replace(guessedOldKey, newKey);
                                    htmlAttribute.setValue(htmlAttributeValue);
                                    addInfoMessage("Replaced old key " + guessedOldKey + " with new key " + newKey);
                                }
                            } else {

                                addWarningMessage("linked content not migrated as no old key could be found in string");
                                //htmlAttribute.setValue(internalLink + pluginEnvironment.getSharedObject("context_missing" + internalLink.replace("://", "") + "key").toString());
                            }
                            break;
                        } catch (Exception e) {
                            addErrorMessage("Failed to replace internal link key" + e);
                        }
                    }

                }
            }
            scanHtmlAreaForInternalLinks(htmlEl.getChildren());
        }
    }

    private Element performExtraCheckForAttachments(String guessedOldKey) throws Exception {
        Element existingMigratedContent = getExistingMigratedContentOrCategory(Integer.parseInt(guessedOldKey), "file");
        if (existingMigratedContent == null) {
            existingMigratedContent = getExistingMigratedContentOrCategory(Integer.parseInt(guessedOldKey), "image");
        }
        if (existingMigratedContent == null) {
            existingMigratedContent = getExistingMigratedContentOrCategory(Integer.parseInt(guessedOldKey), "content");
        }
        return existingMigratedContent;
    }

    private Element getExistingMigratedContentOrCategory(Integer sourceKey, String type) throws Exception {
        try {
            Integer migratedContentCategoryKey = Integer.parseInt((String) pluginEnvironment.getSharedObject("context_migratedcontentcategory"));
            GetContentByCategoryParams getContentByCategoryParams1 = new GetContentByCategoryParams();
            getContentByCategoryParams1.categoryKeys = new int[]{migratedContentCategoryKey};
            Document document1 = null;
            if (type.equals("relatedcontent")) {//look for all kinds of relatedcontent
                String[] relatedContentTypes = new String[]{"content", "file", "image"};
                for (String relatedContentType : relatedContentTypes) {
                    getContentByCategoryParams1.query = "data/oldkey='" + sourceKey + "' and data/type='" + relatedContentType + "'";
                    document1 = getTargetserverClient().getContentByCategory(getContentByCategoryParams1);
                    if (XPath.selectSingleNode(document1, "contents/content") != null) {
                        addInfoMessage("Migrating one " + relatedContentType + " link from old key " + sourceKey);
                        break;
                    }
                }
            } else {
                getContentByCategoryParams1.query = "data/oldkey='" + sourceKey + "' and data/type='" + type + "'";
                document1 = getTargetserverClient().getContentByCategory(getContentByCategoryParams1);
            }


            Element existingContent = (Element) XPath.selectSingleNode(document1, "contents/content");
            if (existingContent == null) {
                return null;
            } else {
                return existingContent;
            }

        } catch (Exception e) {
            addErrorMessage("Exception while getting existing migratedContentOrCategory");
        }
        return null;
    }

    private Integer getExistingMigratedContentOrCategoryKey(Integer sourceKey, String type) throws Exception {
        try {

            Element existingMigratedContent = getExistingMigratedContentOrCategory(sourceKey, type);
            if (existingMigratedContent != null) {
                addInfoMessage("Found existing migrated content..");
                Element newKeyEl = ((Element) XPath.selectSingleNode(existingMigratedContent, "contentdata/newkey"));
                if (newKeyEl != null && newKeyEl.getValue() != null) {
                    Integer newKey = Integer.parseInt(newKeyEl.getValue());
                    addInfoMessage("..with key " + newKey);
                    return newKey;
                }
            } else {
                Integer replacementKey = Integer.parseInt(pluginEnvironment.getSharedObject("context_missing" + type + "key").toString());
                if (replacementKey != null) {
                    return replacementKey;
                }
            }
        } catch (Exception e) {
            addErrorMessage("Exception in getExistingMigratedContentOrCategoryKey");
        }
        return null;

    }

    private List<Element> getExistingMigratedContents(Integer sourceKey, String type) throws Exception {
        Integer migratedContentCategoryKey = Integer.parseInt((String) pluginEnvironment.getSharedObject("context_migratedcontentcategory"));
        GetContentByCategoryParams getContentByCategoryParams1 = new GetContentByCategoryParams();
        getContentByCategoryParams1.categoryKeys = new int[]{migratedContentCategoryKey};
        getContentByCategoryParams1.query = "data/oldkey='" + sourceKey + "' and data/type='" + type + "'";
        Document document1 = getTargetserverClient().getContentByCategory(getContentByCategoryParams1);

        List<Element> existingContent = XPath.selectNodes(document1, "contents/content");
        if (existingContent == null) {
            return null;
        } else {
            return existingContent;
        }
    }

    private void createMigratedContent(String displayName, String type, Integer sourceContentKey, Integer targetContentKey, Contenttype sourceContenttype, Contenttype targetContenttype) {
        CreateContentParams createMigratedContentParams = new CreateContentParams();
        createMigratedContentParams.categoryKey = Integer.parseInt((String) pluginEnvironment.getSharedObject("context_migratedcontentcategory"));
        ContentDataInput migratedContentData = new ContentDataInput("migrated-content");
        migratedContentData.add(new TextInput("oldkey", String.valueOf(sourceContentKey)));
        migratedContentData.add(new TextInput("newkey", String.valueOf(targetContentKey)));
        migratedContentData.add(new TextInput("oldcontenttype", sourceContenttype != null ? sourceContenttype.getName() : ""));
        migratedContentData.add(new TextInput("newcontenttype", targetContenttype != null ? targetContenttype.getName() : ""));
        migratedContentData.add(new TextInput("title", displayName));
        migratedContentData.add(new TextInput("type", type));
        createMigratedContentParams.contentData = migratedContentData;
        createMigratedContentParams.status = ContentStatus.STATUS_APPROVED;
        createMigratedContentParams.publishFrom = new Date();
        getTargetserverClient().createContent(createMigratedContentParams);

    }

    private void addInfoMessage(String message) {
        addMessage(message, "info");
    }

    private void addWarningMessage(String message) {
        addMessage(message, "warning");
    }

    private void addErrorMessage(String message) {
        addMessage(message, "error");
    }

    private void addMessage(String message, String messageType) {
        if ("error".equals(messageType.toLowerCase())) {
            LOG.error(message);
        } else if ("warning".equals(messageType.toLowerCase())) {
            LOG.warn(message);
        } else if ("info".equals(messageType.toLowerCase())) {
            LOG.info(message);
        }
        ResponseMessage responseMessage = new ResponseMessage(message, messageType);
        try {
            responseMessages.add(responseMessage);
        } catch (Exception e) {
            LOG.error(this.getClass().getSimpleName(), "Error getting messages, and adding response message");
        }

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
            addWarningMessage("No method defined for requestPath '" + methodName + "'. Exception: " + e);
            LOG.error("Exception {}", e);
        } catch (IllegalAccessException e) {
            addErrorMessage("IllegalAccessException with reflection ");
            LOG.error("Exception {}", e);
        } catch (InvocationTargetException e) {
            addErrorMessage("InvocationTargetException with reflection: " + e);
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
}
