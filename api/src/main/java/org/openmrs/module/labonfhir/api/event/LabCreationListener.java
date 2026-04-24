package org.openmrs.module.labonfhir.api.event;

import javax.jms.Message;
import java.util.HashSet;
import java.util.List;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.api.Include;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.param.TokenAndListParam;
import ca.uhn.fhir.rest.param.TokenParam;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Task;
import org.openmrs.Order;
import org.openmrs.api.OrderService;
import org.openmrs.api.context.Daemon;
import org.openmrs.event.EventListener;
import org.openmrs.module.DaemonToken;
import org.openmrs.module.fhir2.api.FhirLocationService;
import org.openmrs.module.fhir2.api.FhirTaskService;
import org.openmrs.module.fhir2.api.util.FhirUtils;
import org.openmrs.module.labonfhir.LabOnFhirConfig;
import org.openmrs.module.labonfhir.api.model.FailedTask;
import org.openmrs.module.labonfhir.api.service.LabOnFhirService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.openmrs.module.fhir2.api.search.param.TaskSearchParams;

public abstract class LabCreationListener implements EventListener {

	private static final Logger log = LoggerFactory.getLogger(EncounterCreationListener.class);

	private DaemonToken daemonToken;

	@Autowired
	@Qualifier("labOrderFhirClient")
	private IGenericClient client;

	@Autowired
	private LabOnFhirConfig config;

	@Autowired
	@Qualifier("fhirR4")
	private FhirContext ctx;

	@Autowired
	FhirLocationService fhirLocationService ;

	@Autowired
	private FhirTaskService fhirTaskService;

	@Autowired
	private LabOnFhirService labOnFhirService ;

	@Autowired
	private OrderService orderService;

	public DaemonToken getDaemonToken() {
		return daemonToken;
	}

	public void setDaemonToken(DaemonToken daemonToken) {
		this.daemonToken = daemonToken;
	}

	@Override
	public void onMessage(Message message) {
		log.trace("Received message {}", message);

		Daemon.runInDaemonThread(() -> {
			try {
				processMessage(message);
			}
			catch (Exception e) {
				log.error("Failed to update the user's last viewed patients property", e);
			}
		}, daemonToken);
	}

	public abstract void processMessage(Message message);

	public Bundle createLabBundle(Task task) {
		return createLabBundle(task, false);
	}

	/**
	 * Build a transaction bundle for the given Task.
	 *
	 * When {@code isRetry} is true, the Task entry uses POST + If-None-Exist so a retry
	 * cannot overwrite a Task that the hub has already progressed past REQUESTED. Other
	 * resources (Patient, Encounter, ServiceRequest, Location) stay as PUTs since they
	 * are safe to re-upsert.
	 */
	public Bundle createLabBundle(Task task, boolean isRetry) {
		TokenAndListParam uuid = new TokenAndListParam().addAnd(new TokenParam(task.getIdElement().getIdPart()));
		HashSet<Include> includes = new HashSet<>();
		includes.add(new Include("Task:patient"));
		includes.add(new Include("Task:owner"));
		includes.add(new Include("Task:encounter"));
		includes.add(new Include("Task:based-on"));

		IBundleProvider labBundle = fhirTaskService.searchForTasks(new TaskSearchParams(null, null, null, uuid, null, null, includes));

		Bundle transactionBundle = new Bundle();
		transactionBundle.setType(Bundle.BundleType.TRANSACTION);
		List<IBaseResource> labResources = labBundle.getAllResources();
		if (!task.getLocation().isEmpty() && config.getLabUpdateTriggerObject().equals("Encounter")) {
			labResources.add(fhirLocationService.get(FhirUtils.referenceToId(task.getLocation().getReference()).get()));
		}
		for (IBaseResource r : labResources) {
			Resource resource = (Resource) r;
			Bundle.BundleEntryComponent component = transactionBundle.addEntry();
			component.setResource(resource);

			if (resource instanceof Task && isRetry) {
				// Conditional create: the hub creates the Task only if no Task with this id
				// exists yet. Otherwise it returns the existing one untouched, which is what
				// we want for a retry (never regress a Task the hub already moved forward).
				component.getRequest()
						.setMethod(Bundle.HTTPVerb.POST)
						.setUrl(resource.fhirType())
						.setIfNoneExist("_id=" + resource.getIdElement().getIdPart());
			} else {
				component.getRequest()
						.setUrl(resource.fhirType() + "/" + resource.getIdElement().getIdPart())
						.setMethod(Bundle.HTTPVerb.PUT);
			}
		}
		return transactionBundle;
	}

	protected void sendTask(Task task) {
		if (task == null || !config.getActivateFhirPush()) {
			return;
		}

		Bundle labBundle = createLabBundle(task);
		try {
			client.transaction().withBundle(labBundle).execute();
			// 201 received. The Task is now live on the hub with status REQUESTED.
			// Reflect this on the Order right away so the UI shows "Envoyé" without
			// waiting for the next FetchTaskUpdates cycle.
			setOrderFulfillerStatus(task, Order.FulfillerStatus.RECEIVED, "REQUESTED");
			log.debug(ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(labBundle));
		}
		catch (Exception e) {
			saveFailedTask(task.getIdElement().getIdPart(), e.getMessage());
			log.error("Failed to send Task with UUID " + task.getIdElement().getIdPart(), e);
		}
	}

	/**
	 * Apply a fulfiller status / comment on every Order referenced in the Task's
	 * basedOn list. Silently skips references that don't resolve to a local Order.
	 */
	public void setOrderFulfillerStatus(Task task, Order.FulfillerStatus status, String comment) {
		if (task == null || task.getBasedOn() == null) {
			return;
		}
		for (Reference ref : task.getBasedOn()) {
			if (!ref.hasReferenceElement()) {
				continue;
			}
			IIdType refElement = ref.getReferenceElement();
			if (!"ServiceRequest".equals(refElement.getResourceType())) {
				continue;
			}
			Order order = orderService.getOrderByUuid(refElement.getIdPart());
			if (order != null) {
				orderService.updateOrderFulfillerStatus(order, status, comment,
						order.getAccessionNumber());
			}
		}
	}

	private void saveFailedTask(String taskUuid ,String error) {
		FailedTask failedTask = new FailedTask();
		failedTask.setError(error);
		failedTask.setIsSent(false);
		failedTask.setTaskUuid(taskUuid);
		labOnFhirService.saveOrUpdateFailedTask(failedTask);
	}
}
