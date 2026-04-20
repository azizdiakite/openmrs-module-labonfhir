package org.openmrs.module.labonfhir.api.service;

import java.util.Date;
import java.util.List;

import org.openmrs.Order;
import org.openmrs.api.APIException;
import org.openmrs.api.OpenmrsService;
import org.openmrs.module.labonfhir.api.model.FailedTask;
import org.openmrs.module.labonfhir.api.model.TaskRequest;
import org.springframework.transaction.annotation.Transactional;


public interface LabOnFhirService extends OpenmrsService{
    /**
	 * Returns FailedTask by uuid
	 * 
	 * @param uuid
	 * @return FailedTask
	 * @throws APIException
	 */
	//@Authorized()
	@Transactional(readOnly = true)
	FailedTask getFailedTaskByUuid(String uuid) throws APIException;
	
	/**
	 * Saves an FailedTask
	 * 
	 * @param failedTask
	 * @return FailedTask
	 * @throws APIException
	 */
	@Transactional
	FailedTask saveOrUpdateFailedTask(FailedTask failedTask) throws APIException;
	
	/**
	 * Returns Unsent or Sent FailedTask . if the isSent param equals null , it returns all Failed Tasks
     * 
	 * @param isSent
	 * @return List of FailedTasks
	 * @throws APIException
	 */
	@Transactional
	List<FailedTask> getAllFailedTasks(Boolean isSent) throws APIException;

	/**
	 * Saves an TaskRequest
	 * 
	 * @param taskRequest
	 * @return TaskRequest
	 * @throws APIException
	 */
	@Transactional
	TaskRequest saveOrUpdateTaskRequest(TaskRequest taskRequest) throws APIException;
	

	/**
	 * Returns the Last Task Request

	 * @throws APIException
	 */
	@Transactional(readOnly = true)
	TaskRequest getLastTaskRequest() throws APIException;

	/**
	 * Returns orders with null FulfillerStatus and null FulfillerComment activated before cutoffDate
	 *
	 * @param cutoffDate orders activated before this date are returned
	 * @return List of stale Orders
	 * @throws APIException
	 */
	@Transactional(readOnly = true)
	List<Order> getStaleOrders(Date cutoffDate) throws APIException;

	/**
	 * Returns orders that have an accession number but no scheduled date
	 *
	 * @return List of Orders
	 * @throws APIException
	 */
	@Transactional(readOnly = true)
	List<Order> getOrdersWithAccessionNumberAndNoScheduledDate() throws APIException;

	/**
	 * Updates the scheduled date of orders matching the given accession number
	 *
	 * @param accessionNumber the accession number to match
	 * @param scheduledDate the date to set
	 * @return number of updated rows
	 * @throws APIException
	 */
	@Transactional
	int updateOrderScheduledDate(String accessionNumber, Date scheduledDate) throws APIException;

}
