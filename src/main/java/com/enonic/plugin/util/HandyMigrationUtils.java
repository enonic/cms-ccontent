package com.enonic.plugin.util;

import com.enonic.cms.api.client.ClientException;
import com.enonic.cms.api.client.ClientFactory;
import com.enonic.cms.api.client.RemoteClient;
import com.enonic.cms.api.client.model.DeleteCategoryParams;
import com.enonic.cms.api.client.model.GetCategoriesParams;
import com.enonic.cms.api.client.model.GetContentByCategoryParams;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/*
* Convenience tool to f.eks. delete all categories recursively including content, when a migration needs to be undone f.x.
* Also a tool for installing a cert that I used when working against server with test ssl certificate
* */
public class HandyMigrationUtils {

    final static RemoteClient client = ClientFactory.getRemoteClient("http://localhost:8080/rpc/bin");
    final static XMLOutputter xmlout = new XMLOutputter(Format.getPrettyFormat());
    //final static Logger LOG = LoggerFactory.getLogger(HandyMigrationUtils.class);

    final static int[] CATEGORYKEYS = new int[]{3398};

    static {
        try {
            //disableSslVerification();
        } catch (Exception e) {
        }
    }


    public static void main(String args[]) throws Exception {
        String userName = client.login("admin", "password");
        System.out.println("Logged in " + userName);

        deleteCategories(CATEGORYKEYS);
        //getListOfAllContenttypesWithContentForTopCategory(1298);
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
            Document visContentDoc = client.getContentByCategory(getContentByCategoryParams);
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
                client.deleteCategory(deleteCategoryParams);
            } catch (ClientException e) {
                System.out.println(e);
            }

        }
    }

    private static Document getCategories(int categoryKey) throws IOException {
        GetCategoriesParams getCategoriesParams = new GetCategoriesParams();
        getCategoriesParams.categoryKey = categoryKey;
        getCategoriesParams.levels = 0;
        return client.getCategories(getCategoriesParams);
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
