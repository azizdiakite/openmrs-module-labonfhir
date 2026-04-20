/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.labonfhir.api.scheduler;

import static org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.openmrs.Order;
import org.openmrs.module.labonfhir.LabOnFhirConfig;
import org.openmrs.module.labonfhir.api.service.LabOnFhirService;
import org.openmrs.scheduler.tasks.AbstractTask;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Scheduled task that:
 * 1. Fetches all orders with an accession number and no scheduled date.
 * 2. Sends those accession numbers to the VL server notification endpoint.
 * 3. Updates the scheduled date to now for every accession number in the response.
 *
 * Required global properties:
 *   labonfhir.vlServerBaseUrl     - base URL of the VL server (e.g. http://localhost:8085)
 *   labonfhir.vlServerUsername    - username for token authentication
 *   labonfhir.vlServerPassword    - password for token authentication
 */
@Component
public class SendVLRequestNotifications extends AbstractTask implements ApplicationContextAware {

	private static final Log log = LogFactory.getLog(SendVLRequestNotifications.class);

	/** POST {baseUrl}/auth/token */
	private static final String TOKEN_PATH = "/auth/token";

	/** POST {baseUrl}/api/v1/vl-requests/received-notification */
	private static final String NOTIFICATION_PATH = "/api/v1/vl-requests/received-notification";

	private static ApplicationContext applicationContext;

	@Autowired
	private LabOnFhirConfig config;

	@Autowired
	private LabOnFhirService labOnFhirService;

	// -------------------------------------------------------------------------
	// AbstractTask
	// -------------------------------------------------------------------------

	@Override
	public void execute() {
		try {
			applicationContext.getAutowireCapableBeanFactory().autowireBean(this);
		}
		catch (Exception e) {
			// continue - beans may already be wired
		}

		String baseUrl = config.getVlServerBaseUrl();
		if (StringUtils.isBlank(baseUrl)) {
			log.info("SendVLRequestNotifications: vlServerBaseUrl not configured, skipping.");
			return;
		}

		try {
			log.info("SendVLRequestNotifications: starting execution.");

			// 1. Collect orders that have an accession number but no scheduled date
			List<Order> orders = labOnFhirService.getOrdersWithAccessionNumberAndNoScheduledDate();
			if (orders.isEmpty()) {
				log.info("SendVLRequestNotifications: no eligible orders found.");
			} else {
				List<String> accessionNumbers = orders.stream()
				        .map(Order::getAccessionNumber)
				        .filter(a -> a != null && !a.trim().isEmpty())
				        .distinct()
				        .collect(Collectors.toList());

				if (accessionNumbers.isEmpty()) {
					log.info("SendVLRequestNotifications: no accession numbers to send.");
				} else {
					log.info("SendVLRequestNotifications: sending " + accessionNumbers.size() + " accession number(s).");

					// 2. Obtain bearer token
					String token = fetchToken(baseUrl);
					if (token == null) {
						log.error("SendVLRequestNotifications: could not obtain auth token, will retry next cycle.");
					} else {
						// 3. Notify VL server and collect acknowledged accession numbers
						List<String> acknowledged = sendNotification(baseUrl, token, accessionNumbers);
						log.info("SendVLRequestNotifications: " + acknowledged.size() + " accession number(s) acknowledged.");

						// 4. Update scheduled_date = now for every acknowledged order
						if (!acknowledged.isEmpty()) {
							Date now = new Date();
							for (String accessionNumber : acknowledged) {
								try {
									int updated = labOnFhirService.updateOrderScheduledDate(accessionNumber, now);
									log.debug("SendVLRequestNotifications: updated " + updated
									        + " order(s) for accession " + accessionNumber);
								}
								catch (Exception e) {
									log.error("SendVLRequestNotifications: failed to update scheduledDate for accession "
									        + accessionNumber + ": " + e.getMessage());
								}
							}
						}

						log.info("SendVLRequestNotifications: execution complete.");
					}
				}
			}
		}
		catch (Exception e) {
			log.error("SendVLRequestNotifications ERROR: " + e.toString() + getStackTrace(e));
		}

		super.startExecuting();
	}

	@Override
	public void shutdown() {
		log.debug("SendVLRequestNotifications: shutting down.");
		this.stopExecuting();
	}

	@Override
	public void setApplicationContext(ApplicationContext ctx) throws BeansException {
		SendVLRequestNotifications.applicationContext = ctx;
	}

	// -------------------------------------------------------------------------
	// HTTP helpers
	// -------------------------------------------------------------------------

	/**
	 * Obtains a bearer token from the VL server using HTTP Basic Authentication.
	 *
	 * Request : POST {baseUrl}/auth/token
	 *           Authorization: Basic base64(username:password)
	 *
	 * Response: {"accessToken":"...","expiresIn":3600}
	 *
	 * @return token string, or null on failure
	 */
	private String fetchToken(String baseUrl) {
		ObjectMapper mapper = new ObjectMapper();
		CloseableHttpClient httpClient = HttpClients.createDefault();
		try {
			String credentials = config.getVlServerUsername() + ":" + config.getVlServerPassword();
			String basicAuth = "Basic " + java.util.Base64.getEncoder()
			        .encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8));

			HttpPost post = new HttpPost(baseUrl + TOKEN_PATH);
			post.setHeader(HttpHeaders.AUTHORIZATION, basicAuth);

			HttpResponse response = httpClient.execute(post);
			int statusCode = response.getStatusLine().getStatusCode();
			String responseBody = EntityUtils.toString(response.getEntity());

			if (statusCode < 200 || statusCode >= 300) {
				log.error("SendVLRequestNotifications: token endpoint returned HTTP " + statusCode
				        + " - " + responseBody);
				return null;
			}

			JsonNode root = mapper.readTree(responseBody);
			for (String field : new String[] { "accessToken", "access_token", "token" }) {
				if (root.has(field) && !root.get(field).isNull()) {
					return root.get(field).asText();
				}
			}

			log.error("SendVLRequestNotifications: token not found in response: " + responseBody);
			return null;
		}
		catch (IOException e) {
			log.error("SendVLRequestNotifications: error fetching token: " + e.toString() + getStackTrace(e));
			return null;
		}
		finally {
			try {
				httpClient.close();
			}
			catch (IOException e) {
				log.warn("SendVLRequestNotifications: error closing HTTP client after token fetch: " + e.getMessage());
			}
		}
	}

	/**
	 * Sends accession numbers to the VL server and returns the acknowledged ones.
	 *
	 * Request : POST {baseUrl}/api/v1/vl-requests/received-notification
	 *           Authorization: Bearer {token}
	 *           Content-Type: application/json
	 *           {"accessionNumbers":["ACC1","ACC2",...]}
	 *
	 * Response: {"count":2,"updated":["ACC1","ACC2"]}
	 *
	 * @param baseUrl           VL server base URL
	 * @param token             bearer token
	 * @param accessionNumbers  accession numbers to send
	 * @return list of accession numbers in the "updated" field (never null)
	 */
	private List<String> sendNotification(String baseUrl, String token, List<String> accessionNumbers) {
		List<String> result = new ArrayList<>();
		ObjectMapper mapper = new ObjectMapper();
		CloseableHttpClient httpClient = HttpClients.createDefault();
		try {
			ArrayNode array = mapper.createArrayNode();
			accessionNumbers.forEach(array::add);

			HttpPost post = new HttpPost(baseUrl + NOTIFICATION_PATH);
			post.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
			post.setEntity(new StringEntity(mapper.writeValueAsString(array), ContentType.APPLICATION_JSON));

			HttpResponse response = httpClient.execute(post);
			int statusCode = response.getStatusLine().getStatusCode();
			String responseBody = EntityUtils.toString(response.getEntity());

			if (statusCode < 200 || statusCode >= 300) {
				log.error("SendVLRequestNotifications: notification endpoint returned HTTP " + statusCode
				        + " - " + responseBody);
				return result;
			}

			JsonNode root = mapper.readTree(responseBody);

			// Expected response: {"count": 2, "updated": ["ACC1", "ACC2"]}
			if (root.has("updated") && root.get("updated").isArray()) {
				for (JsonNode node : root.get("updated")) {
					if (!node.isNull() && !node.asText().trim().isEmpty()) {
						result.add(node.asText());
					}
				}
			} else {
				log.warn("SendVLRequestNotifications: unexpected response format: " + responseBody);
			}
		}
		catch (IOException e) {
			log.error("SendVLRequestNotifications: error sending notification: " + e.toString() + getStackTrace(e));
		}
		finally {
			try {
				httpClient.close();
			}
			catch (IOException e) {
				log.warn("SendVLRequestNotifications: error closing HTTP client after notification: " + e.getMessage());
			}
		}
		return result;
	}
}
