package org.sakaiproject.contentreview.dao;

import org.hibernate.Query;
import org.hibernate.Transaction;
import org.hibernate.classic.Session;
import org.sakaiproject.contentreview.model.UwriteItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.hibernate3.support.HibernateDaoSupport;

import java.util.Objects;

public class UwriteItemDaoImpl extends HibernateDaoSupport implements UwriteItemDao {

	private static final Logger logger = LoggerFactory.getLogger(UwriteItemDaoImpl.class);

	@Override
	public void saveItem(UwriteItem item) {

		Objects.requireNonNull(item);

		Session session = getSessionFactory().openSession();
		Transaction tx = session.beginTransaction();
		try {
			session.saveOrUpdate(item);
			tx.commit();
		} catch (Exception e) {
			logger.error(e.getMessage());
			tx.rollback();
		} finally {
			session.close();
		}
	}

	@Override
	public UwriteItem getByContentId(String contentId) {
		Objects.requireNonNull(contentId);

		Session session = getSessionFactory().openSession();
		UwriteItem item = null;
		try {
			Query query = session.createQuery("from UwriteItem u where u.contentId = :contentId");
			query.setParameter("contentId", contentId);
			item = (UwriteItem) query.uniqueResult();
		} catch (Exception e) {
			logger.error(e.getMessage());
		} finally {
			session.close();
		}
		return item;
	}
}
