#CCONTENT plugin

###What is ccontent plugin

This plugin allows you to copy / migrate content from one contenttype to another - on the same or between to separate
servers. It can be used for just copying, or to solve more complex migration needs. Here are some features / examples
of how the plugin works.
 
* renaming xpath from source to target contenttype
* combining 2 or more contenttypes into one
* migrating old content from an old server onto a new server, and cleaning up content at the same time
* re-mapping SelectorInput values from old to new while migrating
* keep existing relatedcontent by keeping a log of old-key / new-key in the migrate-content contenttype
* parse through htmlarea inputtype and migrate internal page://, content://, attachment:// and file:// urls
* converting between input types f.eks.
** text -> textarea
** text -> htmlarea
** htmlarea -> text

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

When migrating block groups, the base attribute in the import config should be the same as the base on the block
group that you are migrating from.

Example with block group:

    <imports>
        <import mode="xml" name="ccontent">
            <block base="contentdata/files/file" dest="Relaterte filer">
                <mapping dest="files" src="files" />
                <mapping dest="filedescription" src="filedescription" />
            </block>
        </import>
    </imports

###Migrating images and files

Migrating images and files is easy, they do not need a import configuration. 
TODO: Updating images and files is not implemented, only copying

###Migrating two contenttypes into one

The plugin will first look for a import config with name "ccontent", and then for a more specific one with prefix 
"ccontent" + the name of the source contentttype. In this way we allow multiple contenttypes to map to one single by
using multiple import configurations.

<imports>
    <import mode="xml" name="ccontent-kontakt">
        <!--mappings from old contenttype kontakt-->
        <mapping dest="name" src="heading"/>
        <mapping dest="abbreviation" src="departmentabbr"/>
        <mapping dest="position" src="position"/>
        <mapping dest="position-internal" src="internal"/>
        <mapping dest="department" src="department"/>
        <mapping dest="email-address" src="email"/>
        <mapping dest="phone" src="phone"/>
        <mapping dest="image" src="image"/>
        <mapping dest="description" src="description"/>
        <mapping dest="url" src="url"/>
    </import>
    <import mode="xml" name="ccontent-foretak">
        <mapping dest="name" src="name"/>
        <mapping dest="url" src="url"/>
        <mapping dest="abbreviation" src="shortname"/>
        <mapping dest="image" src="logo"/>
    </import>
</imports>

###Existing examples

For more examples, see actual contenttypes that hava been migrated in src/main/resources/examples

###Tips
1. Contenttype input fields with required="true" might have to be set to "false" temporarily during migration, if the
source content do not contain these data, if not the copy for that content will fail.
2. Copy all categories first with "copyContent=no", and create mapping between all contenttypes that shall be migrated.
3. For contenttypes that have dependencies on itself, f.eks. an article that have "related articles", you will have
to migrate the content twice so that all relatedcontent will be updated.

###Copy content

1. Deploy ccontent plugin as a local plugin on the server you wish to copy/migrate content to
2. Create the contenttype migrated-content (src/main/resources/migrated-content.xml) on the server
3. Create a new category for migrated content that contains this contenttype. This is used to keep track of which
content has been copied from source to target server. Se screenshot of example folder structure in src/main/resources/examples
4. All contenttypes that you shall migrate *to* have to have at least one existing content on the target server
5. Access the plugin in ICE mode on /site/[0-9]/ccontent
6. Setup the source server /rpc/bin endpoint and migrated content category key (from step 3)

###Metadata

The plugin will try to preserve things like 'timestamp', 'modified', 'publishfrom', 'owner', 'modifier' and so forth. 
The whole 'owner' and 'modifier' xml with added attributes will be added to migrated-content log for all content, and 
if the user that created / last-modified the content on the source server also exist on the target server with same
qualified-name and key, it will be preserved.

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
