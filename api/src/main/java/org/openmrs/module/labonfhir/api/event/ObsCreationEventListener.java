package org.openmrs.module.labonfhir.api.event;

import javax.jms.Message;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import javax.jms.MapMessage;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.api.Include;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.param.TokenAndListParam;
import ca.uhn.fhir.rest.param.TokenParam;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Task;
import org.openmrs.EncounterRole;
import org.openmrs.Obs;
import org.openmrs.api.ObsService;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.event.EventListener;
import org.openmrs.module.DaemonToken;
import org.openmrs.module.fhir2.api.FhirLocationService;
import org.openmrs.module.fhir2.api.FhirTaskService;
import org.openmrs.module.fhir2.api.util.FhirUtils;
import org.openmrs.module.labonfhir.LabOnFhirConfig;
import org.openmrs.module.labonfhir.api.model.FailedTask;
import org.openmrs.module.labonfhir.api.service.LabOnFhirService;
import org.openmrs.parameter.EncounterSearchCriteria;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

public abstract class ObsCreationEventListener implements EventListener {

	private static final Logger log = LoggerFactory.getLogger(EncounterCreationListener.class);

	private DaemonToken daemonToken;
    
	private static final String HIV_VIRAL_LOAD_ID = "CI0050051AAAAAAAAAAAAAAAAAAAAAAAAAAA";

	private static final int REQUEST_EXAM_ID = 20;

	@Autowired
	@Qualifier("labOrderFhirClient")
	private IGenericClient client;

	@Autowired
	@Qualifier("fhirR4")
	private FhirContext ctx;

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

	private void processMessage(Message message) throws Exception {
		
		MapMessage mapMessage = (MapMessage) message;
		
		String uuid = mapMessage.getString("uuid");
		String userUuid = mapMessage.getString("userUuid");
		
		ObsService obsService = Context.getObsService();
		Obs obs = Context.getObsService().getObsByUuid(uuid);
		Double grossViralLoadInDouble = 0.0;
		boolean isDoubleValue = false;

		
		if ((obs.getConcept().getUuid().equalsIgnoreCase(HIV_VIRAL_LOAD_ID))
		        && (obs.getEncounter().getEncounterType().getEncounterTypeId() == REQUEST_EXAM_ID)) {
			System.out.println("********** START PROCESS MESSAGE ****************");
			

			
			try {
			
			}
			catch (Exception e) {
				System.out.println(e.getMessage());
				System.out.println("EXCEPTION OCCURED ::: " + obs.getValueText());
			}
			
			
	
		}
	}
	


}
