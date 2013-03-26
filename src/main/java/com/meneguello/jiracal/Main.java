package com.meneguello.jiracal;

import static org.apache.commons.lang.StringUtils.isBlank;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import javax.xml.rpc.ServiceException;

import org.apache.log4j.PropertyConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dolby.jira.net.soap.jira.JiraSoapService;
import com.dolby.jira.net.soap.jira.JiraSoapServiceServiceLocator;
import com.dolby.jira.net.soap.jira.RemoteIssue;
import com.dolby.jira.net.soap.jira.RemotePermissionException;
import com.dolby.jira.net.soap.jira.RemoteValidationException;
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
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.Calendar;
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Event.ExtendedProperties;
import com.google.api.services.calendar.model.Events;
import com.google.common.collect.Lists;

public class Main {
	
	static {
		PropertyConfigurator.configure(Main.class.getResourceAsStream("/log4j.properties"));
	}
	
	private static final Logger logger = LoggerFactory.getLogger(Main.class);

	private static final String APPLICATION_NAME = "Calendar Jira";

	private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();

	private static final JsonFactory JSON_FACTORY = new JacksonFactory();

	private static com.google.api.services.calendar.Calendar client;

	static final java.util.List<Calendar> addedCalendarsUsingBatch = Lists.newArrayList();

	private static JiraSoapService service;

	private static String token;

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

	private static void jiraLogin(String server, String username, String password) throws ServiceException, RemoteException {
		final JiraSoapServiceServiceLocator serviceLocator = new JiraSoapServiceServiceLocator();
		serviceLocator.setJirasoapserviceV2EndpointAddress(server);
		service = serviceLocator.getJirasoapserviceV2();
		token = service.login(username, password);
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
		final String server = "https://jira.codeitsolutions.com.br/rpc/soap/jirasoapservice-v2";
		final int daysToLoad = 7;
		
		if (isBlank(targetCalendar)) {
			System.err.println("Calendário de origem é obrigatório");
			System.exit(1);
		}
		
		if (isBlank(username) || isBlank(password)) {
			System.err.println("Jira user/password not supplied");
			System.exit(1);
		}
		
		jiraLogin(server, username, password);
		
		final CalendarList calendarList = client.calendarList().list().execute();
		if (calendarList.getItems() != null) {
			for (CalendarListEntry calendar : calendarList.getItems()) {
				logger.debug("Calendar {} listed", calendar.getSummary());
				
				if (targetCalendar.equals(calendar.getSummary())) {
					logger.info("Parsing calendar {}", calendar.getSummary());
					
					final java.util.Calendar now = java.util.Calendar.getInstance();
					final DateTime timeMax = new DateTime(now.getTime());
					now.add(java.util.Calendar.DATE, -daysToLoad);
					final DateTime timeMin = new DateTime(now.getTime());
					
					final Events events = client.events()
							.list(calendar.getId())
							.setTimeMin(timeMin)
							.setTimeMax(timeMax)
							.setSingleEvents(true)
							.setOrderBy("starttime")
							.execute();
					if (events.getItems() != null) {
						for (Event event : events.getItems()) {
							final String summary = event.getSummary();
							logger.debug("Parsing event {}", summary);
							
							if (event.getEnd().getDateTime().getValue() > timeMax.getValue()) {
								logger.debug("Ignore running event {}", summary);
								return;
							}
							
							if (!summary.startsWith("* ")) {
								submitWorklog(calendar, event);
							} else {
								updateWorklogIfChanged(event);
							}
						}
					}
				}
			}
		}
	}
	
	private static void submitWorklog(CalendarListEntry calendar, Event event) {
		final String summary = event.getSummary();
		
		if (!Pattern.matches("^[A-Z]+-[0-9]+.*", summary)) {
			logger.info("Event '{}' don't matches", summary);
			return;
		}
		
		logger.debug("Processing event {}", summary);
		
		final String issueKey = summary.indexOf(' ') >= 0 ? summary.substring(0, summary.indexOf(' ')) : summary;
		
		final long eventStartMillis = event.getStart().getDateTime().getValue();
		final long eventEndMillis = event.getEnd().getDateTime().getValue();
		
		final RemoteWorklog remoteWorklog = new RemoteWorklog();
		java.util.Calendar startDate = java.util.Calendar.getInstance();
		startDate.setTimeInMillis(eventStartMillis);
		remoteWorklog.setStartDate(startDate);
		remoteWorklog.setTimeSpent(((eventEndMillis - eventStartMillis) / 60000) + "m");
		remoteWorklog.setComment(event.getDescription());
		
		logger.info("Sending event to Jira");
		
		final RemoteWorklog sentWorklog;
		try {
			sentWorklog = service.addWorklogAndAutoAdjustRemainingEstimate(token, issueKey, remoteWorklog);
		} catch (RemoteException e) {
			logger.error("Failed to send worklog to Jira", e);
			return;
		}
		
		logger.info("Sending event to Google's Calendar");
		
		try {
			addExtendedProperties(event, issueKey, sentWorklog);
			event.setSummary("* " + summary);
			client.events().update(calendar.getId(), event.getId(), event).execute();
		} catch (IOException e) {
			logger.error("Failed to send worklog to Google", e);
			return;
		}
		
		logger.info("Event successfully sent");
	}

	private static void addExtendedProperties(Event event, String issueKey, RemoteWorklog sentWorklog) {
		Map<String, String> prop = new HashMap<String, String>();
		prop.put("issueKey", issueKey);
		prop.put("worklogId", sentWorklog.getId());
		
		ExtendedProperties extendedProperties = new ExtendedProperties();
		extendedProperties.setPrivate(prop);
		event.setExtendedProperties(extendedProperties);		
	}

	private static void updateWorklogIfChanged(Event event) throws RemotePermissionException, RemoteValidationException, com.dolby.jira.net.soap.jira.RemoteException, RemoteException {
		if (event.getExtendedProperties() != null) {
			logger.debug("Event '{}' has extended properties", event.getSummary());
			
			final String issueKey = event.getExtendedProperties().getPrivate().get("issueKey");
			if (issueKey != null) {
				final String worklogId = event.getExtendedProperties().getPrivate().get("worklogId");
				
				logger.debug("Event '{}' has issueKey '{}' and worklogId '{}'", event.getSummary(), issueKey, worklogId);
				
				final RemoteIssue issue = service.getIssue(token, issueKey);
				
				for (RemoteWorklog worklog : service.getWorklogs(token, issue.getKey())) {
					if (!worklog.getId().equals(worklogId)) continue;
				
					final long eventStartMillis = event.getStart().getDateTime().getValue();
					final long eventEndMillis = event.getEnd().getDateTime().getValue();
				
					java.util.Calendar startDate = java.util.Calendar.getInstance();
					startDate.setTimeInMillis(eventStartMillis);
					
					if (startDate.getTimeInMillis() != worklog.getStartDate().getTimeInMillis() || worklog.getTimeSpentInSeconds() != (eventEndMillis - eventStartMillis)/1000) {
						final RemoteWorklog updatedWorklog = new RemoteWorklog();
						updatedWorklog.setId(worklog.getId());
						updatedWorklog.setStartDate(startDate);
						updatedWorklog.setTimeSpent(((eventEndMillis - eventStartMillis) / 60000) + "m");
						
						logger.info("Updating event {}", event.getSummary());
						
						service.updateWorklogAndAutoAdjustRemainingEstimate(token, updatedWorklog);
						
						logger.info("Event successfully updated");
					}
				}
			}
		}
	}
}