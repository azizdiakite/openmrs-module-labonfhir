package org.openmrs.module.labonfhir.api.scheduler;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.Type;
import org.hl7.fhir.r4.model.codesystems.TaskStatus;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.Location;
import org.openmrs.Obs;
import org.openmrs.Order;
import org.openmrs.Order.FulfillerStatus;
import org.openmrs.api.ObsService;
import org.openmrs.api.OrderService;
import org.openmrs.api.context.Context;
import org.openmrs.module.fhir2.FhirConstants;
import org.openmrs.module.fhir2.api.FhirDiagnosticReportService;
import org.openmrs.module.fhir2.api.FhirObservationService;
import org.openmrs.module.fhir2.api.FhirTaskService;
import org.openmrs.module.labonfhir.LabOnFhirConfig;
import org.openmrs.module.labonfhir.api.model.TaskRequest;
import org.openmrs.module.labonfhir.api.service.LabOnFhirService;
import org.openmrs.scheduler.tasks.AbstractTask;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.TokenClientParam;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import lombok.AccessLevel;
import lombok.Setter;


@Component
@Setter(AccessLevel.PACKAGE)
public class FetchTaskUpdates extends AbstractTask implements ApplicationContextAware {

	private static Log log = LogFactory.getLog(FetchTaskUpdates.class);

	private static final String RECEPTION_DATE = "165284AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	private static final String TECHNICAL_VALIDATION_DATE = "165283AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	private static ApplicationContext applicationContext;

	private static final String LOINC_SYSTEM = "http://loinc.org";

	private static final Integer FETCH_TASK_LIMIT = 20;

	private static final Integer MAX_PAGES_PER_EXECUTION = 5;

	private static final Integer STALE_ORDER_THRESHOLD_MINUTES = 30;

	private static final String[] TASK_ELEMENTS = {
	        "id", "identifier", "status", "statusReason", "output","ServiceRequest", "DiagnosticReport",
	        "basedOn", "encounter", "authoredOn", "lastModified", "owner", "meta"
	};

	@Autowired
	private LabOnFhirConfig config;

	@Autowired
	@Qualifier("labOrderFhirClient")
	private IGenericClient client;

	@Autowired
	private FhirTaskService taskService;

	@Autowired
	private FhirDiagnosticReportService diagnosticReportService;

	@Autowired
	FhirObservationService observationService;

	@Autowired
	OrderService orderService;

	@Autowired
	private LabOnFhirService labOnFhirService;

	@Override
	public void execute() {
		try {
			applicationContext.getAutowireCapableBeanFactory().autowireBean(this);
		}
		catch (Exception e) {
			// continue
		}

		if (!config.isLisEnabled()) {
			return;
		}

		try {
			log.info("******* START EXECUTING FetchTaskUpdatesOptimized Task ****** ");
			Date newDate = new Date();
			Calendar calendar = Calendar.getInstance();
			calendar.setTime(newDate);
			calendar.add(Calendar.YEAR, -5);
			Date fiveYearsAgo = calendar.getTime();
			String practitionerId = config.getLisUserUuid();

			TaskRequest lastRequest = labOnFhirService.getLastTaskRequest();
			Date lastRequestDate = (lastRequest != null) ? lastRequest.getRequestDate() : fiveYearsAgo;

			DateRangeParam lastUpdated = new DateRangeParam().setLowerBoundInclusive(lastRequestDate);

			Bundle taskBundle = client.search().forResource(Task.class)
			        .lastUpdated(lastUpdated)
					.where(Task.OWNER.hasId(practitionerId))
			        .count(FETCH_TASK_LIMIT)
			        .elementsSubset(TASK_ELEMENTS)
			        .returnBundle(Bundle.class).execute();

			int pagesProcessed = 0;
			do {
				processBundle(taskBundle);
				pagesProcessed++;
				if (pagesProcessed >= MAX_PAGES_PER_EXECUTION) {
					log.info("FetchTaskUpdates: reached max pages per execution (" + MAX_PAGES_PER_EXECUTION + "), will resume next cycle.");
					break;
				}
				if (taskBundle.getLink(IBaseBundle.LINK_NEXT) != null) {
					taskBundle = client.loadPage().next(taskBundle).execute();
				} else {
					break;
				}
			} while (true);

			TaskRequest request = new TaskRequest();
			request.setRequestDate(newDate);
			labOnFhirService.saveOrUpdateTaskRequest(request);
			declineStaleOrders();

		}
		catch (Exception e) {
			log.error("ERROR executing FetchTaskUpdatesOptimized : " + e.toString() + getStackTrace(e));
		}

		super.startExecuting();
	}

	@Override
	public void shutdown() {
		log.debug("shutting down FetchTaskUpdatesOptimized Task");
		this.stopExecuting();
	}

	/**
	 * Dispatches each task in the bundle to the appropriate handler based on its status.
	 */
	private boolean processBundle(Bundle taskBundle) {
		boolean tasksUpdated = false;

		for (Bundle.BundleEntryComponent entry : taskBundle.getEntry()) {
			String openmrsTaskUuid = null;
			try {
				Task openelisTask = (Task) entry.getResource();
				openmrsTaskUuid = openelisTask.getIdentifierFirstRep().getValue();
				Task openmrsTask;
				try {
					openmrsTask = taskService.get(openmrsTaskUuid);
				} catch (ResourceNotFoundException e) {
					log.debug("Task " + openmrsTaskUuid + " not found locally, skipping.");
					continue;
				}
				if (openmrsTask == null) {
					continue;
				}

				Task.TaskStatus status = openelisTask.getStatus();

				if (Task.TaskStatus.COMPLETED.equals(status) || Task.TaskStatus.REJECTED.equals(status)) {
					tasksUpdated |= processCompletedOrRejected(openelisTask, openmrsTask, openmrsTaskUuid);
				} else {
					tasksUpdated |= processSimpleStatus(status, openmrsTask);
				}
			}
			catch (Exception e) {
				log.error("Could not process task " + openmrsTaskUuid + ": " + e.toString() + getStackTrace(e));
			}
		}

		return tasksUpdated;
	}

	/**
	 * Handles COMPLETED and REJECTED tasks using the data already present in the bundle entry.
	 * No additional FHIR request is needed.
	 */
	private boolean processCompletedOrRejected(Task openelisTask, Task openmrsTask, String openmrsTaskUuid) {
		Task.TaskStatus status = openelisTask.getStatus();

		if (Task.TaskStatus.COMPLETED.equals(status)) {
			openmrsTask.setStatus(status);
			Order currentOrder = null;
			if (openmrsTask.hasBasedOn()) {
				currentOrder = setOrderNumberFromLIS(openmrsTask.getBasedOn());
			}
			if (openelisTask.hasOutput()) {
				setLabDates(openelisTask.getOutput(), currentOrder);
				boolean outputUpdated = updateOutput(openelisTask.getOutput(), openmrsTask);
				if (outputUpdated) {
					taskService.update(openmrsTaskUuid, openmrsTask);
					return true;
				}
			}
			return false;
		}

		if (Task.TaskStatus.REJECTED.equals(status)) {
			openmrsTask.setStatus(status);
			String commentText = "Update Order with remote fhir status";
			if (openelisTask.hasStatusReason() && openelisTask.getStatusReason().hasText()
			        && !openelisTask.getStatusReason().getText().isEmpty()) {
				commentText = openelisTask.getStatusReason().getText();
			}
			setOrderStatus(openmrsTask.getBasedOn(), Order.FulfillerStatus.EXCEPTION, commentText);
			return true;
		}

		return false;
	}

	/**
	 * Handles simple status transitions (REQUESTED, ACCEPTED, INPROGRESS, CANCELLED).
	 * All needed data comes from the local openmrsTask – no remote field is required.
	 */
	private boolean processSimpleStatus(Task.TaskStatus status, Task openmrsTask) {
		if (Task.TaskStatus.REQUESTED.equals(status)) {
			openmrsTask.setStatus(status);
			setOrderStatus(openmrsTask.getBasedOn(), Order.FulfillerStatus.RECEIVED,
			    TaskStatus.REQUESTED.toString());
			return true;
		}
		if (Task.TaskStatus.ACCEPTED.equals(status)) {
			openmrsTask.setStatus(status);
			setOrderStatus(openmrsTask.getBasedOn(), Order.FulfillerStatus.RECEIVED,
			    TaskStatus.ACCEPTED.toString());
			return true;
		}
		if (Task.TaskStatus.INPROGRESS.equals(status)) {
			openmrsTask.setStatus(status);
			setOrderStatus(openmrsTask.getBasedOn(), Order.FulfillerStatus.IN_PROGRESS,
			    TaskStatus.INPROGRESS.toString());
			return true;
		}
		if (Task.TaskStatus.CANCELLED.equals(status)) {
			openmrsTask.setStatus(status);
			setOrderStatus(openmrsTask.getBasedOn(), Order.FulfillerStatus.EXCEPTION,
			    TaskStatus.CANCELLED.toString());
			return true;
		}
		return false;
	}

	private Order setOrderNumberFromLIS(List<Reference> basedOn) {
		for (Reference ref : basedOn) {
			if (!ref.hasReferenceElement()) {
				continue;
			}
			IIdType referenceElement = ref.getReferenceElement();
			if (!"ServiceRequest".equals(referenceElement.getResourceType())) {
				continue;
			}
			String serviceRequestUuid = referenceElement.getIdPart();
			try {
				ServiceRequest serviceRequest = client.read().resource(ServiceRequest.class)
				        .withId(serviceRequestUuid).execute();
				if (serviceRequest.hasRequisition()) {
					Order order = orderService.getOrderByUuid(serviceRequestUuid);
					if (order != null) {
						String accessionNumber = serviceRequest.getRequisition().getValue();
						orderService.updateOrderFulfillerStatus(order, Order.FulfillerStatus.COMPLETED,
						    "Update Order with Accession Number From LIS", accessionNumber);
						return order;
					}
				}
			}
			catch (ResourceNotFoundException e) {
				log.error("Could not fetch ServiceRequest/" + serviceRequestUuid + ": " + e.toString() + getStackTrace(e));
			}
		}
		return null;
	}

	private boolean updateOutput(List<Task.TaskOutputComponent> output, Task openmrsTask) {
		Reference encounterReference = openmrsTask.getEncounter();
		List<Reference> basedOn = openmrsTask.getBasedOn();
		List<String> allExistingLoincCodes = new ArrayList<>();

		openmrsTask.getOutput().forEach(out -> {
			out.getType().getCoding().stream()
			        .filter(coding -> coding.hasSystem())
			        .filter(coding -> coding.getSystem().equals(LOINC_SYSTEM))
			        .forEach(coding -> allExistingLoincCodes.add(coding.getCode()));
		});

		if (output.isEmpty()) {
			return false;
		}

		Task.TaskOutputComponent outputRef = output.get(0);
		if (!(outputRef.getValue() instanceof Reference)) {
			log.warn("Task output value is not a Reference, skipping.");
			return false;
		}

		String openelisDiagnosticReportUuid = ((Reference) outputRef.getValue()).getReferenceElement().getIdPart();

		Bundle diagnosticReportBundle = client.search().forResource(DiagnosticReport.class)
		        .where(new TokenClientParam("_id").exactly().code(openelisDiagnosticReportUuid))
		        .include(DiagnosticReport.INCLUDE_RESULT).include(DiagnosticReport.INCLUDE_SUBJECT)
		        .returnBundle(Bundle.class).execute();

		if (diagnosticReportBundle.getEntry().isEmpty()) {
			return false;
		}

		DiagnosticReport diagnosticReport = (DiagnosticReport) diagnosticReportBundle.getEntryFirstRep().getResource();
		Coding diagnosticReportCode = diagnosticReport.getCode().getCodingFirstRep();

		if (!LOINC_SYSTEM.equals(diagnosticReportCode.getSystem())) {
			return false;
		}

		if (allExistingLoincCodes.contains(diagnosticReportCode.getCode())) {
			return false;
		}

		List<Reference> results = new ArrayList<>();
		for (Bundle.BundleEntryComponent entry : diagnosticReportBundle.getEntry()) {
			if (entry.hasResource() && ResourceType.Observation.equals(entry.getResource().getResourceType())) {
				Observation newObs = (Observation) entry.getResource();
				newObs.setEncounter(encounterReference);
				newObs.setBasedOn(basedOn);
				try {
					System.err.println("Creating observation for code " + newObs.getCode().getCodingFirstRep().getCode() + " with ID " + newObs.getIdElement().getIdPart());
					newObs = observationService.create(newObs);
					results.add(new Reference(ResourceType.Observation + "/" + newObs.getIdElement().getIdPart()));
				}
				catch (Exception e) {
					log.warn("Skipping observation that failed validation (DiagnosticReport " + openelisDiagnosticReportUuid
					        + "): " + e.getMessage());
				}
			}
		}

		if (results.isEmpty()) {
			log.warn("No valid observations could be created for DiagnosticReport " + openelisDiagnosticReportUuid
			        + " – skipping output update.");
			return false;
		}

		diagnosticReport.setResult(results);
		diagnosticReport.setEncounter(encounterReference);
		diagnosticReport = diagnosticReportService.create(diagnosticReport);
		openmrsTask.addOutput()
		        .setValue(new Reference().setType(FhirConstants.DIAGNOSTIC_REPORT)
		                .setReference(diagnosticReport.getIdElement().getIdPart()))
		        .setType(diagnosticReport.getCode());
		return true;
	}

	private void setOrderStatus(List<Reference> basedOn, FulfillerStatus fulfillerStatus, String commentText) {
		basedOn.forEach(ref -> {
			if (!ref.hasReferenceElement()) {
				return;
			}
			IIdType referenceElement = ref.getReferenceElement();
			if (!"ServiceRequest".equals(referenceElement.getResourceType())) {
				return;
			}
			String serviceRequestUuid = referenceElement.getIdPart();
			try {
				Order order = orderService.getOrderByUuid(serviceRequestUuid);
				if (order != null) {
					orderService.updateOrderFulfillerStatus(order, fulfillerStatus, commentText, "");
				}
			}
			catch (ResourceNotFoundException e) {
				log.error("Could not fetch ServiceRequest/" + serviceRequestUuid + ": " + e.toString() + getStackTrace(e));
			}
		});
	}

	private void setLabDates(List<Task.TaskOutputComponent> output, Order currentOrder) {
		if (currentOrder == null) {
			log.debug("setLabDates: currentOrder is null, skipping.");
			return;
		}
		try {
			log.debug("Setting lab dates for order: " + currentOrder.getAccessionNumber());
			ObsService obsService = Context.getObsService();
			Encounter orderEncounter = currentOrder.getEncounter();
			Location defaultLocation = Context.getLocationService().getDefaultLocation();

			Date dateReception = getValueDateByCode(output, RECEPTION_DATE);
			log.debug("Date reception: " + dateReception);
			if (dateReception != null) {
				Concept conceptDateReception = Context.getConceptService().getConceptByUuid(RECEPTION_DATE);
				Obs obsDateReception = new Obs(orderEncounter.getPatient(), conceptDateReception, null, null);
				obsDateReception.setValueDatetime(dateReception);
				obsDateReception.setLocation(defaultLocation);
				obsDateReception.setGroupMembers(null);
				obsDateReception.setEncounter(orderEncounter);
				obsDateReception.setObsDatetime(new Date());
				obsService.saveObs(obsDateReception, null);
			}

			Date technicalValidationDate = getValueDateByCode(output, TECHNICAL_VALIDATION_DATE);
			log.debug("Technical validation date: " + technicalValidationDate);
			if (technicalValidationDate != null) {
				Concept conceptTechnicalValidationDate = Context.getConceptService()
				        .getConceptByUuid(TECHNICAL_VALIDATION_DATE);
				Obs obsTechnicalValidationDate = new Obs(orderEncounter.getPatient(), conceptTechnicalValidationDate,
				        null, null);
				obsTechnicalValidationDate.setValueDatetime(technicalValidationDate);
				obsTechnicalValidationDate.setLocation(defaultLocation);
				obsTechnicalValidationDate.setGroupMembers(null);
				obsTechnicalValidationDate.setEncounter(orderEncounter);
				obsTechnicalValidationDate.setObsDatetime(new Date());
				obsService.saveObs(obsTechnicalValidationDate, null);
			}
			Context.refreshEntity(orderEncounter);
		}
		catch (Exception e) {
			log.error("ERROR setting lab dates: " + e.toString() + getStackTrace(e));
		}
	}

	public static Date getValueDateByCode(List<Task.TaskOutputComponent> outputs, String code) {
		if (outputs == null || outputs.isEmpty() || code == null) {
			return null;
		}
		for (Task.TaskOutputComponent output : outputs) {
			if (output.hasType() && output.getType().hasCoding()) {
				boolean codeMatch = output.getType().getCoding().stream()
				        .anyMatch(coding -> code.equals(coding.getCode()));
				if (codeMatch && output.hasValue()) {
					Type value = output.getValue();
					if (value instanceof DateType) {
						return ((DateType) value).getValue();
					}
				}
			}
		}
		log.warn("No valueDate found for code: " + code);
		return null;
	}

	private void declineStaleOrders() {
		try {
			Calendar cal = Calendar.getInstance();
			cal.add(Calendar.MINUTE, -STALE_ORDER_THRESHOLD_MINUTES);
			Date cutoffDate = cal.getTime();

			List<Order> staleOrders = labOnFhirService.getStaleOrders(cutoffDate);
			for (Order order : staleOrders) {
				try {
					orderService.updateOrderFulfillerStatus(order, Order.FulfillerStatus.EXCEPTION, "DECLINED", "");
					log.info("Marked stale order " + order.getUuid() + " as EXCEPTION/DECLINED");
				}
				catch (Exception e) {
					log.error("Could not decline stale order " + order.getUuid() + ": " + e.toString() + getStackTrace(e));
				}
			}
		}
		catch (Exception e) {
			log.error("ERROR in declineStaleOrders: " + e.toString() + getStackTrace(e));
		}
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		FetchTaskUpdates.applicationContext = applicationContext;
	}
}
