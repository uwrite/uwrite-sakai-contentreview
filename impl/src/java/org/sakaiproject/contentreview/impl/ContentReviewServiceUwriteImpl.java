package org.sakaiproject.contentreview.impl;

import com.uwrite.Uwrite;
import com.uwrite.model.UCheck;
import com.uwrite.model.UFile;
import com.uwrite.model.UType;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.uri.internal.JerseyUriBuilder;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.content.api.ContentResource;
import org.sakaiproject.contentreview.dao.UwriteItemDao;
import org.sakaiproject.contentreview.exception.QueueException;
import org.sakaiproject.contentreview.exception.ReportException;
import org.sakaiproject.contentreview.exception.SubmissionException;
import org.sakaiproject.contentreview.exception.TransientSubmissionException;
import org.sakaiproject.contentreview.model.ContentReviewItem;
import org.sakaiproject.contentreview.model.UwriteItem;
import org.sakaiproject.contentreview.service.ContentReviewService;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.site.api.Site;
import org.apache.commons.io.FilenameUtils;
import org.sakaiproject.user.api.PreferencesService;

import javax.ws.rs.ClientErrorException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class ContentReviewServiceUwriteImpl implements ContentReviewService {

    private static final Map<String, SortedSet<String>> acceptFilesMap = new HashMap<>();
    private static final Map<String, SortedSet<String>> acceptFileTypesMap = new HashMap<>();

    static {
        acceptFilesMap.put(".docx", new TreeSet<>(Arrays.asList(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/zip"
        )));
        acceptFilesMap.put(".odt", new TreeSet<>(Arrays.asList(
                "application/vnd.oasis.opendocument.text"
        )));
        acceptFilesMap.put(".doc", new TreeSet<>(Arrays.asList(
                "application/msword"
        )));
        acceptFilesMap.put(".pdf", new TreeSet<>(Arrays.asList(
                "application/pdf"
        )));
        acceptFilesMap.put(".rtf", new TreeSet<>(Arrays.asList(
                "application/rtf",
                "text/rtf"
        )));
        acceptFilesMap.put(".txt", new TreeSet<>(Arrays.asList(
                "text/plain",
                "application/txt",
                "text/anytext",
                "application/octet-stream"
        )));
        acceptFilesMap.put(".html", new TreeSet<>(Arrays.asList(
                "text/html"
        )));
        acceptFilesMap.put(".pages", new TreeSet<>(Arrays.asList(
                "application/x-iwork-pages-sffpages"
        )));

        acceptFileTypesMap.put("Word", new TreeSet<>(Arrays.asList(".doc", ".docx")));
        acceptFileTypesMap.put("PDF", new TreeSet<>(Arrays.asList(".pdf")));
        acceptFileTypesMap.put("OpenOffice", new TreeSet<>(Arrays.asList(".odt")));
        acceptFileTypesMap.put("Apple Pages", new TreeSet<>(Arrays.asList(".pages")));
        acceptFileTypesMap.put("RTF", new TreeSet<>(Arrays.asList(".rtf")));
        acceptFileTypesMap.put("Text", new TreeSet<>(Arrays.asList(".txt", ".html")));
    }

    @Setter
    private PreferencesService preferencesService;
    @Setter
    private ServerConfigurationService serverConfigurationService;
    @Setter
    private UwriteItemDao uwriteItemDao;

    private Uwrite client;
    private ExecutorService pool;
    private UType uType;
    private int maxFileSize;
    private boolean allowAnyFileType;
    private boolean excludeCitations;
    private boolean excludeReferences;

    private static final String SERVICE_NAME = "Uwrite";

    public void init() {
        String key = serverConfigurationService.getString("uwrite.key", null);
        String secret = serverConfigurationService.getString("uwrite.secret", null);
        client = new Uwrite(key, secret);

        int threadsCount = serverConfigurationService.getInt("uwrite.poolSize", 4);
        pool = Executors.newFixedThreadPool(threadsCount);

        int checkType = serverConfigurationService.getInt("uwrite.checkType", 1); // default WEB
        uType = UType.values()[checkType];

        maxFileSize = serverConfigurationService.getInt("uwrite.maxFileSize", 20971520); //default 20MB
        allowAnyFileType = serverConfigurationService.getBoolean("uwrite.allowAnyFileType", false);
        excludeCitations = serverConfigurationService.getBoolean("uwrite.exclude.citations", true);
        excludeReferences = serverConfigurationService.getBoolean("uwrite.exclude.references", true);
    }

    public void destroy() {
        if (pool != null) {
            pool.shutdown();
        }
    }

    @Override
    public void queueContent(String userId, String siteId, String assignmentReference, List<ContentResource> content)
            throws QueueException {
        for (final ContentResource resource : content) {

            String id = resource.getId();
            UwriteItem item = new UwriteItem();
            item.setContentId(id);
            item.setUserId(userId);
            item.setSiteId(siteId);
            item.setAssignmentRef(assignmentReference);

            uwriteItemDao.saveItem(item);

            CompletableFuture.runAsync(() -> {

                log.info("Processing resource " + id);
                if (!checkContentResource(resource)) {
                    //ignore
                    item.setError("Unsupported file");
                    uwriteItemDao.saveItem(item);
                    return;
                }


                //upload
                UFile uFile;
                try (InputStream is = resource.streamContent()) {
                    String fileName = resource.getProperties().getProperty(ResourceProperties.PROP_DISPLAY_NAME);
                    uFile = client.uploadFile(is, FilenameUtils.getExtension(id), FilenameUtils.getBaseName(fileName));

                    // check
                    UCheck uCheck = client
                            .createCheck(uFile.getId(), uType, null, excludeCitations, excludeReferences);
                    long uCheckId = uCheck.getId();
                    uCheck = client.waitForCheckInfo(uCheckId);
                    item.setScore(Math.round(100f - uCheck.getReport().getSimilarity()));
                    item.setLink(uCheck.getReport().getViewUrl());
                    item.setEditLink(uCheck.getReport().getViewEditUrl());
                } catch (Exception e) {
                    String message = e.getMessage();
                    log.error(message, e);
                    item.setError(message);
                }

                uwriteItemDao.saveItem(item);
            }, pool)

                    .thenRun(() -> log.info(String.format("%s is completed", item.getContentId())));
        }
    }

    @Override
    public int getReviewScore(String contentId, String assignmentRef, String userId)
            throws Exception {
        UwriteItem item = uwriteItemDao.getByContentId(contentId);
        if (item != null) {
            if (item.getLink() != null)
                return item.getScore();
            else
                return -1; // in progress
        }
        throw new ReportException("UwriteItem with id " + contentId + " doesn't exist");
    }

    @Override
    public String getReviewReport(String contentId, String assignmentRef, String userId)
            throws QueueException, ReportException {
        return getReportLink(contentId, userId, false);
    }

    @Override
    public String getReviewReportStudent(String contentId, String assignmentRef, String userId)
            throws QueueException, ReportException {
        return getReportLink(contentId, userId, false);
    }

    @Override
    public String getReviewReportInstructor(String contentId, String assignmentRef, String userId)
            throws QueueException, ReportException {
        return getReportLink(contentId, userId, true);
    }

    @Override
    public Long getReviewStatus(String contentId) throws QueueException {
        UwriteItem item = uwriteItemDao.getByContentId(contentId);
        if (item == null) {
            return ContentReviewItem.NOT_SUBMITTED_CODE;
        } else if (item.getLink() != null) {
            return ContentReviewItem.SUBMITTED_REPORT_AVAILABLE_CODE;
        } else if (item.getError() != null) {
            return ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE;
        }
        return ContentReviewItem.SUBMITTED_AWAITING_REPORT_CODE;
    }

    @Override
    public Date getDateQueued(String contextId) throws QueueException {
        return null;
    }

    @Override
    public Date getDateSubmitted(String contextId) throws QueueException, SubmissionException {
        return null;
    }

    @Override
    public void processQueue() {
    }

    @Override
    public void checkForReports() {
    }

    @Override
    public List<ContentReviewItem> getReportList(String siteId, String taskId)
            throws QueueException, SubmissionException, ReportException {
        return null;
    }

    @Override
    public List<ContentReviewItem> getReportList(String siteId) throws QueueException, SubmissionException, ReportException {
        return null;
    }

    @Override
    public List<ContentReviewItem> getAllContentReviewItems(String siteId, String taskId)
            throws QueueException, SubmissionException, ReportException {
        return null;
    }

    @Override
    public String getServiceName() {
        return SERVICE_NAME;
    }

    @Override
    public void resetUserDetailsLockedItems(String userId) {
    }

    @Override
    public boolean allowAllContent() {
        return allowAnyFileType;
    }

    @Override
    public boolean isAcceptableContent(ContentResource resource) {
        return allowAnyFileType || checkContentResource(resource);
    }

    @Override
    public Map<String, SortedSet<String>> getAcceptableExtensionsToMimeTypes() {
        return acceptFilesMap;
    }

    @Override
    public Map<String, SortedSet<String>> getAcceptableFileTypesToExtensions() {
        return acceptFileTypesMap;
    }

    @Override
    public boolean isSiteAcceptable(Site site) {
        return true;
    }

    @Override
    public String getIconUrlforScore(Long score) {
        if (score > 80)
            return "/sakai-contentreview-tool-uwrite/images/green.gif";
        else if (score > 40)
            return "/sakai-contentreview-tool-uwrite/images/yellow.gif";
        else if (score >= 0)
            return "/sakai-contentreview-tool-uwrite/images/red.gif";
        else
            return "/sakai-contentreview-tool-uwrite/images/working.gif";
    }

    @Override
    public boolean allowResubmission() {
        return true;
    }

    @Override
    public void removeFromQueue(String ContentId) {
    }

    @Override
    public String getLocalizedStatusMessage(String messageCode, String userRef) {
        return null;
    }

    @Override
    public String getLocalizedStatusMessage(String messageCode) {
        return null;
    }

    @Override
    public String getReviewError(String contentId) {
        return uwriteItemDao.getByContentId(contentId).getError();
    }

    @Override
    public String getLocalizedStatusMessage(String messageCode, Locale locale) {
        return null;
    }

    @Override
    public Map getAssignment(String siteId, String taskId) throws SubmissionException, TransientSubmissionException {
        return null;
    }

    @Override
    public void createAssignment(String siteId, String taskId, Map extraAsnnOpts)
            throws SubmissionException, TransientSubmissionException {
    }

    private String injectLanguageInReportLink(String userId, String linkStr) {
        if(linkStr == null) {
            return null;
        }

        try {
            Locale loc = preferencesService.getLocale(userId);
            //the user has no preference set - get the system default
            if (loc == null) {
                loc = Locale.getDefault();
            }

            JerseyUriBuilder b = new JerseyUriBuilder();
            b.uri(linkStr);
            b.replaceQueryParam("lang", loc.toString());

            return b.toString();
        } catch (Exception e) {
            log.warn("Failed to inject language", e);
        }
        return linkStr;
    }

    private String getReportLink(String contentId, String userId, boolean editable) throws ReportException {
        UwriteItem item = uwriteItemDao.getByContentId(contentId);
        if (item == null) {
            return null;
        }

        if(item.getError() != null) {
            throw new ReportException(item.getError());
        }

        return injectLanguageInReportLink(userId, editable ? item.getEditLink() : item.getLink());
    }

    private boolean checkContentResource(ContentResource resource) {
        if (resource == null) {
            return false;
        }

        try {

            if (resource.getContentLength() == 0) {
                return false;
            }

            if (resource.getContentLength() > maxFileSize) {
                return false;
            }

            String ext = "." + FilenameUtils.getExtension(resource.getProperties().getProperty(ResourceProperties.PROP_DISPLAY_NAME));
            if (!acceptFilesMap.containsKey(ext)) {
                return false;
            }

            if (!acceptFilesMap.get(ext).contains(resource.getContentType())) {
                return false;
            }
        } catch (Exception e) {
            log.warn("Failed to check content resource", e);
            return false;
        }

        return true;
    }
}
