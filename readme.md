#CCONTENT plugin

##Instructions

###Create import configurations
1. Create a new import definition with name="ccontent" inside the contenttype's <config><imports> element.
2. Mappings with "src" and "dest" attributes have to be created for inputs that should be copied from source to target
contenttype. Src attribute refers to the xpath name after "contenttype/" and dest attribute refers to the input name.

For example if this contenttype input field was defined on both source and target server:

    <input name="Forside interessevekker" required="false" type="textarea">
      <display>Forside interessevekker</display>
      <xpath>contentdata/interessevekker</xpath>
      <help>Teksten vises på forsider/oversikter og overstyrer evnt. ingress</help>
      <rows value="3" />
    </input>

The target contenttype would have to map up the field like this:

    <imports>
        <import mode="xml" name="ccontent">
            <mapping dest="Forside interessevekker" src="interessevekker" />
        </import>
    </imports

###Migrating block groups
see below "while (blockGroupElementsIt.hasNext()) {" in CopyContentController.
These are implemented:
-file
-checkbox
-image
-radiobutton
-text

TODO! work in progress:
-textarea/htmlarea

Example with block group:

    <imports>
        <import mode="xml" name="ccontent">
            <block base="contentdata/files/file" dest="Relaterte filer">
                <mapping dest="files" src="files" />
                <mapping dest="filedescription" src="filedescription" />
            </block>
        </import>
    </imports

For more examples, and also example with block group content, see article-example.xml

###Tips
1. Contenttype input fields with required="true" might have to be set to "false" temporarily during migration, if the
source content do not contain these data, if not the copy for that content will fail.
2. Copy all categories first with "copyContent=no", and create mapping between all contenttypes that shall be migrated.

###Copy content

1. Deploy ccontent plugin as a local plugin on the server you wish to copy/migrate content to
2. Create the contenttype migrated-content (src/main/resources/migrated-content.xml) on the server
3. Create a new category for migrated content that contains this contenttype. This is used to keep track of which
content has been copied from source to target server
4. Access the plugin in ICE mode on /site/[0-9]/ccontent
5. Setup the source server /rpc/bin endpoint and migrated content category key (from step 3)


## Building and deploying locally

###About '${env.CMS_HOME}' variable in pom.xml

For maven to deploy to your local plugin folder when running 'mvn install', add the settings below 
to your .m2/settings.xml. Offcourse you will have to change the cms.home path to your own location of cms.home, 
and make sure the profiles current-cmshome and deploy-plugins profiles are active.


    <settings>
        <profiles>
            <profile>
              <id>current-cmshome</id>
              <properties>
                <cms.home>/Users/rfo/development/server/current-apache-tomcat/current-server-config/cms.home</cms.home>
              </properties>
            </profile>
        </profiles>
        <activeProfiles>
            <activeProfile>current-cmshome</activeProfile>
        </activeProfiles>
    </settings>
