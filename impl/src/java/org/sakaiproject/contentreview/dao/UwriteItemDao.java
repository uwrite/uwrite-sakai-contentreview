package org.sakaiproject.contentreview.dao;

import org.sakaiproject.contentreview.model.UwriteItem;

public interface UwriteItemDao {

	void saveItem(UwriteItem item);
	UwriteItem getByContentId(String contentId);

}
