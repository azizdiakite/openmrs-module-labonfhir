package org.openmrs.module.labonfhir.api.dao;

import java.util.Date;
import java.util.List;

import org.hibernate.criterion.Restrictions;
import org.openmrs.api.APIException;
import org.openmrs.api.db.hibernate.DbSession;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.labonfhir.api.model.FailedTask;
import org.openmrs.module.labonfhir.api.model.TaskRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository("labOnFhirDao")
public class LabOnFhirDao {
    
    @Autowired
    DbSessionFactory sessionFactory;
    
    private DbSession getSession() {
        return sessionFactory.getCurrentSession();
    }
    
    public FailedTask getFailedTaskByUuid(String uuid) {
        return (FailedTask) getSession().createCriteria(FailedTask.class).add(Restrictions.eq("uuid", uuid)).uniqueResult();
    }
    
    public FailedTask saveOrUpdateFailedTask(FailedTask failedTask) {
        getSession().saveOrUpdate(failedTask);
        return failedTask;
    }
    
    public List<FailedTask> getAllFailedTasks(Boolean isSent) {
        if (isSent != null) {
            return getSession().createCriteria(FailedTask.class).add(Restrictions.eq("isSent", isSent)).list();
        } else {
            return getSession().createCriteria(FailedTask.class).list();
        }
    }

    
    public TaskRequest saveOrUpdateTaskRequest(TaskRequest taskRequest) throws APIException {
        getSession().createQuery("DELETE FROM TaskRequest").executeUpdate();
        getSession().saveOrUpdate(taskRequest);
        return taskRequest;
    }

    public TaskRequest getLastTaskRequest() throws APIException {
        String hql = "FROM TaskRequest tr ORDER BY tr.requestDate DESC";
        return (TaskRequest) getSession().createQuery(hql).setMaxResults(1).uniqueResult();
    }

    @SuppressWarnings("unchecked")
    public List<org.openmrs.Order> getStaleOrders(Date cutoffDate) {
        String hql = "FROM Order o WHERE o.fulfillerStatus IS NULL AND o.fulfillerComment IS NULL "
                + "AND o.dateActivated <= :cutoffDate AND o.voided = false";
        return getSession().createQuery(hql).setParameter("cutoffDate", cutoffDate).list();
    }

    @SuppressWarnings("unchecked")
    public List<org.openmrs.Order> getOrdersWithAccessionNumberAndNoScheduledDate() {
        String hql = "FROM Order o WHERE o.accessionNumber IS NOT NULL AND o.accessionNumber <> '' "
                + "AND o.scheduledDate IS NULL AND o.voided = false";
        return getSession().createQuery(hql).list();
    }

    public int updateOrderScheduledDate(String accessionNumber, Date scheduledDate) {
        String hql = "UPDATE Order SET scheduledDate = :scheduledDate WHERE accessionNumber = :accessionNumber AND voided = false";
        return getSession().createQuery(hql)
                .setParameter("scheduledDate", scheduledDate)
                .setParameter("accessionNumber", accessionNumber)
                .executeUpdate();
    }

}
