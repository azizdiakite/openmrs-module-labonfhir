package org.openmrs.module.labonfhir.api.dao;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.openmrs.api.APIException;
import org.openmrs.api.db.hibernate.DbSession;
import org.openmrs.api.db.hibernate.DbSessionFactory;
import org.openmrs.module.fhir2.model.FhirTask;
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

    /**
     * Returns the UUIDs of local FHIR tasks that are still in flight and whose status
     * should be polled from the FHIR hub. Used by FetchTaskUpdates to scope the remote
     * search to tasks this specific instance emitted, instead of scanning every task
     * the hub knows about (which on a 200+ instance shared hub is prohibitively large).
     *
     * Excludes terminal states (COMPLETED, REJECTED) and UNKNOWN — fhir2 2.2.0's
     * FhirTask.TaskStatus enum only exposes REQUESTED / REJECTED / ACCEPTED / COMPLETED
     * / UNKNOWN, and any FHIR status it cannot map (RECEIVED, INPROGRESS, CANCELLED)
     * is translated to UNKNOWN on write — so once a task lands on UNKNOWN the
     * authoritative state lives on the hub / Order.fulfillerStatus.
     */
    @SuppressWarnings("unchecked")
    public List<String> getActiveTaskUuids() {
        return getSession().createCriteria(FhirTask.class)
                .add(Restrictions.not(Restrictions.in("status", Arrays.asList(
                        FhirTask.TaskStatus.COMPLETED,
                        FhirTask.TaskStatus.REJECTED,
                        FhirTask.TaskStatus.UNKNOWN))))
                .setProjection(Projections.property("uuid"))
                .list();
    }
}
