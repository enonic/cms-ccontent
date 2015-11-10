package com.enonic.plugin.util;

import com.enonic.cms.api.client.ClientException;
import com.enonic.cms.api.client.ClientFactory;
import com.enonic.cms.api.client.LocalClient;
import com.enonic.cms.api.client.RemoteClient;
import com.enonic.cms.api.client.model.*;
import com.enonic.cms.api.client.model.content.ContentDataInput;
import com.enonic.cms.api.client.model.content.ContentStatus;
import com.enonic.cms.api.client.model.content.HtmlAreaInput;
import com.enonic.cms.api.client.model.content.TextInput;
import org.jdom.Attribute;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;
import org.jdom.xpath.XPath;

import javax.net.ssl.*;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.*;

/*
* Convenience tool to f.eks. delete all categories recursively including content, when a migration needs to be undone f.x.
* Also a tool for installing a cert that I used when working against server with test ssl certificate
* */
public class HandyMigrationUtils {

    final static RemoteClient remoteClient = ClientFactory.getRemoteClient("http://localhost:8080/rpc/bin");

    final static XMLOutputter xmloutraw = new XMLOutputter(Format.getRawFormat());
    //final static Logger LOG = LoggerFactory.getLogger(HandyMigrationUtils.class);

    final static int[] CATEGORYKEYS = new int[]{3847};


    static {
        try {
            //disableSslVerification();
        } catch (Exception e) {
        }
    }


    public static void main(String args[]) throws Exception {
        String userName = remoteClient.login("admin", "password");
        System.out.println("Logged in " + userName);

        //deleteCategories(CATEGORYKEYS);
        //getListOfAllContenttypesWithContentForTopCategory(1298);
        //testWhitespaceBugRelatedToCMS2313();
        //testDraftsVersionsMigration();
        //testAttachmentNotFoundException();
        //testImageNotFoundException();
        //testBinaryNotFoundException();
        deleteEmptyCategories(CATEGORYKEYS);

    }

    private static void deleteEmptyCategories(int[] categoryKeys) throws Exception{

        GetCategoriesParams getCategoriesParams = new GetCategoriesParams();
        getCategoriesParams.includeContentCount=true;
        getCategoriesParams.includeTopCategory=true;
        getCategoriesParams.levels=0;
        for (int cKey : categoryKeys){
            getCategoriesParams.categoryKey = cKey;
            Document categories = remoteClient.getCategories(getCategoriesParams);
            //xmloutraw.setFormat(Format.getPrettyFormat());
            //xmloutraw.output(categories, System.out);
        }

        /*DeleteCategoryParams deleteCategoryParams = new DeleteCategoryParams();
        deleteCategoryParams.includeContent = false;
        deleteCategoryParams.recursive = true;

        for (int key : categoryKeys) {
            deleteCategoryParams.key = key;
            try {
                remoteClient.deleteCategory(deleteCategoryParams);
            } catch (ClientException e) {
                System.out.println(e);
            }
        }*/
    }

    private static void testBinaryNotFoundException() throws Exception{
        Document doc;
        xmloutraw.setFormat(Format.getPrettyFormat());
        GetBinaryParams getBinaryParams = new GetBinaryParams();
        getBinaryParams.binaryKey=82275;
        try {
            doc = remoteClient.getBinary(getBinaryParams);
            xmloutraw.output(doc, System.out);
        }catch (Exception cx){
            if (cx.getMessage()!=null && cx.getMessage().contains("Attachment not found"));{
                System.out.println("Attachment not found, try to approve it");
            }
        }

    }

    private static void testImageNotFoundException() throws Exception{
        Document doc;
        xmloutraw.setFormat(Format.getPrettyFormat());
        GetContentBinaryParams getContentBinaryParams = new GetContentBinaryParams();
        getContentBinaryParams.contentKey=195792;
        try {
            doc = remoteClient.getContentBinary(getContentBinaryParams);
            xmloutraw.output(doc, System.out);
        }catch (Exception cx){
            if (cx.getMessage()!=null && cx.getMessage().contains("Attachment not found"));{
                System.out.println("Attachment not found, try to approve it");
                UpdateContentParams updateContentParams = new UpdateContentParams();
                updateContentParams.contentKey=195792;
                updateContentParams.status=ContentStatus.STATUS_APPROVED;
                updateContentParams.publishFrom = new Date();
                updateContentParams.updateStrategy = ContentDataInputUpdateStrategy.REPLACE_NEW;
                remoteClient.updateContent(updateContentParams);
                doc = remoteClient.getContentBinary(getContentBinaryParams);
                xmloutraw.output(doc, System.out);
                updateContentParams.status=ContentStatus.STATUS_ARCHIVED;
                remoteClient.updateContent(updateContentParams);
                /*MoveContentParams moveContentParams = new MoveContentParams();
                moveContentParams.categoryKey =7376;
                moveContentParams.contentKey=195792;
                remoteClient.moveContent(moveContentParams);
                System.out.println("Should have moved content..");*/
            }
        }

    }

    private static void testAttachmentNotFoundException() throws Exception{
        Document doc = null;
        xmloutraw.setFormat(Format.getPrettyFormat());

        GetContentParams getContentParams = new GetContentParams();
        getContentParams.contentKeys = new int[]{35865};
        getContentParams.includeOfflineContent=true;
        doc = remoteClient.getContent(getContentParams);
        xmloutraw.output(doc, System.out);

        GetContentBinaryParams getContentBinaryParams = new GetContentBinaryParams();
        getContentBinaryParams.contentKey = 35865;

        try{
            doc = remoteClient.getContentBinary(getContentBinaryParams);
            xmloutraw.output(doc, System.out);
        }catch (ClientException cx){
            System.out.println(cx.getMessage());
            if (cx.getMessage()!=null && cx.getMessage().contains("Attachment not found"));{
                System.out.println("Attachment not found, try to approve it");
                UpdateContentParams updateContentParams = new UpdateContentParams();
                updateContentParams.contentKey=35865;
                updateContentParams.status=2;
                updateContentParams.publishFrom = new Date();
                updateContentParams.updateStrategy = ContentDataInputUpdateStrategy.REPLACE_NEW;
                remoteClient.updateContent(updateContentParams);
                doc = remoteClient.getContentBinary(getContentBinaryParams);
                xmloutraw.output(doc, System.out);
                updateContentParams.status=ContentStatus.STATUS_ARCHIVED;
                remoteClient.updateContent(updateContentParams);
                MoveContentParams moveContentParams = new MoveContentParams();
                moveContentParams.categoryKey =7376;
                moveContentParams.contentKey=35865;
                //remoteClient.moveContent(moveContentParams);
                System.out.println("Should have moved content..");
            }
        }

    }

    private static void testDraftsVersionsMigration() throws Exception{
        GetContentParams getContentParams = new GetContentParams();
        getContentParams.contentKeys = new int[]{9520};
        getContentParams.includeData=true;
        getContentParams.includeOfflineContent = true;
        getContentParams.includeVersionsInfo = true;
        Document doc = remoteClient.getContent(getContentParams);
        xmloutraw.setFormat(Format.getPrettyFormat());
        //xmloutraw.output(doc, System.out);

        CreateContentParams createContentParams = new CreateContentParams();
        createContentParams.categoryKey = 3943;
        createContentParams.status = ((Attribute)XPath.selectSingleNode(doc, "contents/content/@status")).getIntValue();
        createContentParams.publishFrom = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).parse(((Attribute) XPath.selectSingleNode(doc, "contents/content/@publishfrom")).getValue());
        createContentParams.publishTo = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).parse(((Attribute) XPath.selectSingleNode(doc, "contents/content/@publishto")).getValue());
        createContentParams.changeComment = "Create current content";
        ContentDataInput createContentDataInput = new ContentDataInput("HB-artikkel");
        createContentDataInput.add(new TextInput("title", ((Element)XPath.selectSingleNode(doc,"contents/content/title")).getValue()));
        createContentDataInput.add(new HtmlAreaInput("body-text","<h1>Original content</h1>"));
        createContentParams.contentData = createContentDataInput;

        int migratedContentKey = remoteClient.createContent(createContentParams);

        /*List<Element> versions = XPath.selectNodes(doc, "contents/content/versions/version");
        List<Integer> versionKeys = new ArrayList<>();
        UpdateContentParams updateContentParams = new UpdateContentParams();
        updateContentParams.createNewVersion = true;
        updateContentParams.setAsCurrentVersion = false;
        updateContentParams.updateStrategy = ContentDataInputUpdateStrategy.REPLACE_ALL;
        updateContentParams.contentKey = migratedContentKey;
        for (Element version : versions){
            ContentDataInput updateContentDataInput = new ContentDataInput("HB-artikkel");
            Integer versionKey = Integer.parseInt(version.getAttributeValue("key"));
            Document versionDoc =  getVersionDoc(versionKey);
            //xmloutraw.output(versionDoc, System.out);
            versionKeys.add(versionKey);
            updateContentParams.status = Integer.parseInt(version.getAttributeValue("status-key"));
            updateContentParams.changeComment = version.getChildText("comment");
            Date publishFromDate = null;
            try {
                publishFromDate = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).parse(((Attribute) XPath.selectSingleNode(versionDoc, "contents/content/@publishfrom")).getValue());
            } catch (Exception e) {
            }
            updateContentParams.publishFrom = publishFromDate;

            updateContentDataInput.add(new TextInput("title", version.getChildText("title")));
            updateContentDataInput.add(new HtmlAreaInput("body-text","<h1>Version "+ version.getAttributeValue("key") +"</h1>"));
            updateContentParams.contentData = updateContentDataInput;
            System.out.println("Create version");
            remoteClient.updateContent(updateContentParams);
        }
        */
    }

    private static Document getVersionDoc(Integer versionKey){
        GetContentVersionsParams getContentVersionsParams = new GetContentVersionsParams();
        getContentVersionsParams.contentVersionKeys = new int[]{versionKey};
        getContentVersionsParams.contentRequiredToBeOnline = false;
        return remoteClient.getContentVersions(getContentVersionsParams);
        /*
        int[] versionKeysP = new int[versionKeys.size()];
        for (int i=0;i<versionKeys.size();i++){versionKeysP[i] = versionKeys.get(i);}

        */
    }


    private static void testWhitespaceBugRelatedToCMS2313() throws Exception{

        //Fetch the problematic content
        GetContentParams getContentParams = new GetContentParams();
        getContentParams.contentKeys = new int[]{196499};
        getContentParams.includeData=true;
        Document doc = remoteClient.getContent(getContentParams);

        //Isolate the problematic html
        Element problematicHtmlEl = (Element)XPath.selectSingleNode(doc, "contents/content/contentdata//subtheme[title='Fremgangsmåte']/text");

        //test xmloutputter settings
        xmloutraw.output(problematicHtmlEl.getContent(), System.out);

        //Create a new content with the problematic html via api
        CreateContentParams createContentParams = new CreateContentParams();
        createContentParams.categoryKey = 3941;
        ContentDataInput contentDataInput = new ContentDataInput("HB-fagprosedyre");
        contentDataInput.add(new TextInput("title","_cms2313_test5"));

        contentDataInput.add(new HtmlAreaInput("description",xmloutraw.outputString(problematicHtmlEl.getContent())));
        createContentParams.contentData = contentDataInput;
        remoteClient.createContent(createContentParams);
    }

    private static void getListOfAllContenttypesWithContentForTopCategory(Integer categoryKey) throws IOException, JDOMException {
        Document doc = getCategories(categoryKey);
        Set<Integer> catKeys = new HashSet<Integer>();
        List<Element> catEl = XPath.selectNodes(doc, "//category");
        Iterator<Element> catElIt = catEl.iterator();
        while (catElIt.hasNext()) {
            try {
                Integer catKey = ((Attribute) (catElIt.next()).getAttribute("contenttypekey")).getIntValue();
                if (catKey == null) {
                    continue;
                }
                if (!catKeys.contains(catKey)) {
                    //LOG.info("new contenttypekey={}",catKey);
                    catKeys.add(catKey);
                }

            } catch (Exception e) {
                //LOG.warn("Category without contenttypekey..");
            }
        }

        Iterator<Integer> catKeysIt = catKeys.iterator();
        while (catKeysIt.hasNext()) {
            Integer contenttypekey = catKeysIt.next();

            GetContentByCategoryParams getContentByCategoryParams = new GetContentByCategoryParams();
            getContentByCategoryParams.count = 1;
            getContentByCategoryParams.levels = 0;
            getContentByCategoryParams.query = "contenttypekey = " + contenttypekey;
            getContentByCategoryParams.categoryKeys = new int[]{334};
            Document visContentDoc = remoteClient.getContentByCategory(getContentByCategoryParams);
            Attribute contenttypeEl = (Attribute) XPath.selectSingleNode(visContentDoc, "contents/content/@contenttype");
            //LOG.info(contenttypeEl!=null?contenttypeEl.getValue():"No contenttype");
        }
    }

    private static void deleteCategories(int[] categoryKeys) throws Exception {

        DeleteCategoryParams deleteCategoryParams = new DeleteCategoryParams();
        deleteCategoryParams.includeContent = true;
        deleteCategoryParams.recursive = true;
        for (int key : categoryKeys) {
            deleteCategoryParams.key = key;
            try {
                remoteClient.deleteCategory(deleteCategoryParams);
            } catch (ClientException e) {
                System.out.println(e);
            }

        }
    }

    private static Document getCategories(int categoryKey) throws IOException {
        GetCategoriesParams getCategoriesParams = new GetCategoriesParams();
        getCategoriesParams.categoryKey = categoryKey;
        getCategoriesParams.levels = 0;
        return remoteClient.getCategories(getCategoriesParams);
    }

    private static void printDoc(Document doc) throws Exception {
        XMLOutputter xmlOutputter = new XMLOutputter(Format.getPrettyFormat());
        xmlOutputter.output(doc, System.out);
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
