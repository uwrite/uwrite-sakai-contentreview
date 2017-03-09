# Sakai ContentReview Uwrite plugin

**Supported Sakai versions:** 11.x

Author: Jonathan Ward <developer@uwrite.proctoru.com>  
Site: https://uwrite.proctoru.com

INSTALL  
==============

#### 1. Build plugin and deploy
```bash
cd SAKAI_SRC_HOME
mvn install
cd SAKAI_SRC_HOME/content-review
git clone https://github.com/uwrite/uwrite-sakai-contentreview.git contentreview-impl-uwrite
cd contentreview-impl-uwrite
mvn clean install sakai:deploy -Dmaven.tomcat.home=/path/to/your/tomcat
```  

#### 2. Add Uwrite to contentReviewProviders list:
Open */tomcat/components/sakai-content-review-pack-federated/WEB-INF/components.xml* and add:
```xml
<util:list id="contentReviewProviders">            
    <ref bean="org.sakaiproject.contentreview.service.ContentReviewServiceUwrite"/>       
</util:list>
```  

#### 3. Add settings to *tomcat/sakai/sakai.properties*
```ini
# Uwrite settings
uwrite.key=<your-key>
uwrite.secret=<your-secret>
uwrite.checkType=1
```  

Possible check types (depends on your Uwrite account plan):  
 - **0** = My Library
 - **1** = Internet
 - **2** = External DB
 - **4** = Internet + Library



#### 4. Enable content review for assignments in *tomcat/sakai/sakai.properties*
```ini
assignment.useContentReview=true
```  

If you have  more than one enabled provider, add to Site properties *(admin account -> sites -> Add / Edit Properties)*:
```
contentreview.provider:Uwrite
```

## OPTIONAL PROPERTIES
```ini
uwrite.poolSize=8
uwrite.maxFileSize=20971520
uwrite.allowAnyFileType=false
uwrite.exclude.citations=true
uwrite.exclude.references=true
```

