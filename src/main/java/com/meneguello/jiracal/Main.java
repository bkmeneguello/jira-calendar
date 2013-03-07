package com.meneguello.jiracal;

import static org.apache.commons.lang.StringUtils.isBlank;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Properties;
import java.util.regex.Pattern;

import javax.xml.rpc.ServiceException;

import com.dolby.jira.net.soap.jira.JiraSoapService;
import com.dolby.jira.net.soap.jira.JiraSoapServiceServiceLocator;
import com.dolby.jira.net.soap.jira.RemoteWorklog;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.java6.auth.oauth2.FileCredentialStore;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.Calendar;
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Event.ExtendedProperties;
import com.google.api.services.calendar.model.Events;
import com.google.common.collect.Lists;

public class Main {

	private static final String APPLICATION_NAME = "Calendar Jira";

	private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();

	private static final JsonFactory JSON_FACTORY = new JacksonFactory();

	private static com.google.api.services.calendar.Calendar client;

	static final java.util.List<Calendar> addedCalendarsUsingBatch = Lists.newArrayList();

	private static JiraSoapService service;

	private static Credential authorizeGoogleCalendar() throws Exception {
		// load client secrets
		final GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, Main.class.getResourceAsStream("/client_secrets.json"));
		if (clientSecrets.getDetails().getClientId().startsWith("Enter") || clientSecrets.getDetails().getClientSecret().startsWith("Enter ")) {
			System.out.println("Enter Client ID and Secret from https://code.google.com/apis/console/?api=calendar "
							+ "into calendar-cmdline-sample/src/main/resources/client_secrets.json");
			System.exit(1);
		}
		// set up file credential store
		final FileCredentialStore credentialStore = new FileCredentialStore(new File(System.getProperty("user.home"), ".credentials/calendar.json"), JSON_FACTORY);
		// set up authorization code flow
		final GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, Collections.singleton(CalendarScopes.CALENDAR)).setCredentialStore(credentialStore).build();
		// authorize
		final Credential credential = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");
		
		// set up global Calendar instance
		client = new com.google.api.services.calendar.Calendar.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential).setApplicationName(APPLICATION_NAME).build();
		
		return credential;
	}

	private static String jiraLogin(String username, String password) throws ServiceException, RemoteException {
		final JiraSoapServiceServiceLocator serviceLocator = new JiraSoapServiceServiceLocator();
		serviceLocator.setJirasoapserviceV2EndpointAddress("https://jira.codeitsolutions.com.br/rpc/soap/jirasoapservice-v2");
		service = serviceLocator.getJirasoapserviceV2();

		return service.login(username, password);
	}

	public static void main(String[] args) throws Exception {
		// authorization
		authorizeGoogleCalendar();
		
		Properties properties = new Properties();
		try {
			properties.load(new FileInputStream(new File(System.getProperty("user.home"), ".credentials/jira.properties")));
		} catch (IOException e) {}
		
		final String targetCalendar = args.length > 0 ? args[0] : System.getProperty("google.calendar", properties.getProperty("calendar"));
		final String username = args.length > 1 ? args[1] : System.getProperty("jira.username", properties.getProperty("username"));
		final String password = args.length > 2 ? args[2] : System.getProperty("jira.password", properties.getProperty("password"));
		
		if (isBlank(targetCalendar)) {
			System.err.println("Calendário de origem é obrigatório");
			System.exit(1);
		}
		
		if (isBlank(username) || isBlank(password)) {
			System.err.println("Jira user/password not supplied");
			System.exit(1);
		}
		
		final String token = jiraLogin(username, password);
		
		final CalendarList calendarList = client.calendarList().list().execute();
		if (calendarList.getItems() != null) {
			for (CalendarListEntry calendar : calendarList.getItems()) {
				if (targetCalendar.equals(calendar.getSummary())) {
					final Events events = client.events().list(calendar.getId()).execute();
					if (events.getItems() != null) {
						for (Event event : events.getItems()) {
							final String summary = event.getSummary();
							if (!summary.startsWith("* ")) {
								if (!Pattern.matches("^[A-Z]+-[0-9]+.*", summary)) continue;
								
								final String issueKey = summary.indexOf(' ') >= 0 ? summary.substring(0, summary.indexOf(' ')) : summary;
								
								final long eventStartMillis = event.getStart().getDateTime().getValue();
								final long eventEndMillis = event.getEnd().getDateTime().getValue();
								
								final RemoteWorklog remoteWorklog = new RemoteWorklog();
								java.util.Calendar startDate = java.util.Calendar.getInstance();
								startDate.setTimeInMillis(eventStartMillis);
								remoteWorklog.setStartDate(startDate);
								remoteWorklog.setTimeSpent(((eventEndMillis - eventStartMillis) / 60000) + "m");
								remoteWorklog.setComment(event.getDescription());
								
								RemoteWorklog remoteWorklog2 = service.addWorklogAndAutoAdjustRemainingEstimate(token, issueKey, remoteWorklog);
								
								final ExtendedProperties extendedProperties = new Event.ExtendedProperties();
								HashMap<String, String> privateProperties = new HashMap<String, String>();
								privateProperties.put("issueKey", issueKey);
								privateProperties.put("worklogId", remoteWorklog2.getId());
								extendedProperties.setPrivate(privateProperties);
								event.setExtendedProperties(extendedProperties);
								
								event.setSummary("* " + summary);
								client.events().update(calendar.getId(), event.getId(), event).execute();
							} else {
								final ExtendedProperties extendedProperties = event.getExtendedProperties();
								if (extendedProperties != null && extendedProperties.getPrivate() != null && extendedProperties.getPrivate().containsKey("worklogId")) {
									System.out.println(extendedProperties.getPrivate().get("worklogId"));
								}
							}
						}
					}
				}
			}
		}
	}
}