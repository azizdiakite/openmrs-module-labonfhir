package org.openmrs.module.labonfhir;

import static org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.util.Enumeration;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.ssl.SSLContextBuilder;
import org.hl7.fhir.r4.model.Practitioner;
import org.openmrs.api.AdministrationService;
import org.openmrs.module.fhir2.api.FhirPractitionerService;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Component
@Configuration
public class LabOnFhirConfig implements ApplicationContextAware {

	public static final String GP_OPENELIS_URL = "labonfhir.openElisUrl";

	public static final String GP_OPENELIS_USER_UUID = "labonfhir.openelisUserUuid";

	public static final String GP_KEYSTORE_PATH = "labonfhir.keystorePath";

	public static final String GP_KEYSTORE_PASS = "labonfhir.keystorePass";

	public static final String GP_TRUSTSTORE_PATH = "labonfhir.truststorePath";

	public static final String GP_TRUSTSTORE_PASS = "labonfhir.truststorePass";

	public static final String GP_ACTIVATE_FHIR_PUSH = "labonfhir.activateFhirPush";

	private static final String TEMP_DEFAULT_OPENELIS_URL = "https://testapi.openelisci.org:8444/hapi-fhir-jpaserver/fhir";

	public static final String GP_AUTH_TYPE = "labonfhir.authType";

	public static final String GP_USER_NAME = "labonfhir.userName";

	public static final String GP_PASSWORD = "labonfhir.password";

	public enum AuthType{
		SSL,
		BASIC
	}

	private static Log log = LogFactory.getLog(LabOnFhirConfig.class);

	private static ApplicationContext applicationContext;

	@Autowired
	@Qualifier("adminService")
	AdministrationService administrationService;

	@Autowired
	FhirPractitionerService practitionerService;

	public SSLConnectionSocketFactory sslConnectionSocketFactory() throws Exception {
		return new SSLConnectionSocketFactory(sslContext());
	}

	public SSLContext sslContext() throws Exception {
		SSLContextBuilder sslContextBuilder =  SSLContextBuilder.create();
		try {
			if(administrationService.getGlobalProperty(GP_KEYSTORE_PATH) != null && !administrationService.getGlobalProperty(GP_KEYSTORE_PATH).isEmpty()) {
				String keyPassword = administrationService.getGlobalProperty(GP_KEYSTORE_PASS);
				File truststoreFile = new File(administrationService.getGlobalProperty(GP_TRUSTSTORE_PATH));
				String truststorePassword = administrationService.getGlobalProperty(GP_TRUSTSTORE_PASS);

				KeyStore keystore = loadKeystore(administrationService.getGlobalProperty(GP_KEYSTORE_PATH));

				sslContextBuilder.loadKeyMaterial(keystore, keyPassword.toCharArray())
						.loadTrustMaterial(truststoreFile, truststorePassword.toCharArray());
			}
		} catch (Exception e) {
			log.error("ERROR creating SSL Context: " + e.toString() + getStackTrace(e));
		}

		return sslContextBuilder.build();
	}

	public String getOpenElisUrl() {
		//return GP_OPENELIS_URL
		String url = administrationService.getGlobalProperty(GP_OPENELIS_URL);

		if(StringUtils.isBlank(url)) {
			url = TEMP_DEFAULT_OPENELIS_URL;
		}

		return url;
	}

	public Boolean getActivateFhirPush() {
		String activatePush = administrationService.getGlobalProperty(GP_ACTIVATE_FHIR_PUSH, "true");
		return Boolean.valueOf(activatePush);
	}

	public String getOpenElisUserUuid() {
		return administrationService.getGlobalProperty(GP_OPENELIS_USER_UUID);
	}

	public String getOpenElisUserName() {
		return administrationService.getGlobalProperty(GP_USER_NAME);
	}

	public String getOpenElisPassword() {
		return administrationService.getGlobalProperty(GP_PASSWORD);
	}

	public AuthType getAuthType() {
		String authTypeGp = administrationService.getGlobalProperty(GP_AUTH_TYPE);
		switch (authTypeGp.toUpperCase()) {
			case "BASIC":
				return AuthType.BASIC;
			case "SSL":
				return AuthType.SSL;
			default:
				return AuthType.BASIC;
		}
	}

	public boolean isOpenElisEnabled() {
		return StringUtils.isNotBlank(getOpenElisUrl());
	}

	public Practitioner getOpenElisPractitioner() {
		return practitionerService.get(getOpenElisUserUuid());
	}

	private KeyStore loadKeystore(String filePath) {
		InputStream is = null;
		KeyStore keystore = null;

		try {
			File file = new File(administrationService.getGlobalProperty(GP_KEYSTORE_PATH));
			is = new FileInputStream(file);
			keystore = KeyStore.getInstance(KeyStore.getDefaultType());

			String password = administrationService.getGlobalProperty(GP_KEYSTORE_PASS);

			keystore.load(is, password.toCharArray());

			Enumeration<String> enumeration = keystore.aliases();
			while(enumeration.hasMoreElements()) {
				String alias = enumeration.nextElement();
				log.info("alias name: " + alias);
				Certificate certificate = keystore.getCertificate(alias);
				log.info(certificate.toString());
			}

		} catch (java.security.cert.CertificateException e) {
			e.printStackTrace();
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (KeyStoreException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if(null != is)
				try {
					is.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
		}

		return keystore;
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}
}
