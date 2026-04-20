package org.openmrs.module.labonfhir.api.scheduler;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Task;
import org.openmrs.module.fhir2.api.FhirTaskService;
import org.openmrs.module.fhir2.api.search.param.TaskSearchParams;
import org.openmrs.module.labonfhir.api.event.OrderCreationListener;
import org.openmrs.module.labonfhir.api.model.FailedTask;
import org.openmrs.module.labonfhir.api.service.LabOnFhirService;
import org.openmrs.scheduler.tasks.AbstractTask;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.param.TokenAndListParam;
import ca.uhn.fhir.rest.param.TokenParam;

@Component
public class RetryFailedTasks extends AbstractTask implements ApplicationContextAware {
    private static Log log = LogFactory.getLog(RetryFailedTasks.class);

    private static ApplicationContext applicationContext;

    @Autowired
    @Qualifier("labOrderFhirClient")
    private IGenericClient client;

    @Autowired
    private FhirTaskService fhirTaskService;

    @Autowired
    private LabOnFhirService labOnFhirService;

    @Autowired
    @Qualifier("labOrderListener")
    private  OrderCreationListener orderCreationListener;

    @Autowired
	@Qualifier("fhirR4")
	private FhirContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void execute() {
        log.info("Executing Retry Failed tasks");
        try {
            applicationContext.getAutowireCapableBeanFactory().autowireBean(this);
        }
        catch (Exception e) {}

        retrySendingFailedTasks();
    }

    private void retrySendingFailedTasks() {
        // Step 1: collect all UUIDs already registered in failed_task (sent or not)
        Set<String> knownFailedTaskUuids = labOnFhirService.getAllFailedTasks(null)
                .stream()
                .map(FailedTask::getTaskUuid)
                .collect(Collectors.toSet());

        // Step 2: find all local FHIR tasks with status REQUESTED not yet in failed_task
        TokenAndListParam requestedStatus = new TokenAndListParam()
                .addAnd(new TokenParam(Task.TaskStatus.REQUESTED.toCode()));
        IBundleProvider provider = fhirTaskService.searchForTasks(
                new TaskSearchParams(null, null, requestedStatus, null, null, null, null));

        for (IBaseResource resource : provider.getAllResources()) {
            Task task = (Task) resource;
            String uuid = task.getIdElement().getIdPart();
            if (!knownFailedTaskUuids.contains(uuid)) {
                FailedTask failedTask = new FailedTask();
                failedTask.setTaskUuid(uuid);
                failedTask.setIsSent(false);
                failedTask.setError("Task not sent - registered by RetryFailedTasks scheduler");
                labOnFhirService.saveOrUpdateFailedTask(failedTask);
                log.info("Registered unsent task as failed: " + uuid);
            }
        }

        // Step 3: retry all unsent failed tasks (including newly registered ones)
        List<FailedTask> failedTasks = labOnFhirService.getAllFailedTasks(false);

        failedTasks.forEach(failedTask -> {
            Task task = fhirTaskService.get(failedTask.getTaskUuid());
            if (task == null) {
                return;
            }
            try {
                Bundle labBundle = orderCreationListener.createLabBundle(task);
                client.transaction().withBundle(labBundle).execute();
                failedTask.setIsSent(true);
                labOnFhirService.saveOrUpdateFailedTask(failedTask);
                log.info("Resent Failed task:" + failedTask.getTaskUuid());
                log.debug(ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(labBundle));
            }
            catch (Exception e) {
                log.error(e);
            }
        });
    }
}
