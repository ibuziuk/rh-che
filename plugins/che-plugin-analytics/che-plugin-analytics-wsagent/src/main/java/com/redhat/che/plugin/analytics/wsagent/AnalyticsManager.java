/*
 * Copyright (c) 2016-2018 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.che.plugin.analytics.wsagent;

import static com.google.common.collect.ImmutableMap.builder;
import static com.redhat.che.plugin.analytics.wsagent.AnalyticsEvent.WORKSPACE_INACTIVE;
import static com.redhat.che.plugin.analytics.wsagent.AnalyticsEvent.WORKSPACE_STARTED;
import static com.redhat.che.plugin.analytics.wsagent.AnalyticsEvent.WORKSPACE_STOPPED;
import static com.redhat.che.plugin.analytics.wsagent.AnalyticsEvent.WORKSPACE_USED;
import static com.redhat.che.plugin.analytics.wsagent.EventProperties.WORKSPACE_ID;
import static com.redhat.che.plugin.analytics.wsagent.EventProperties.WORKSPACE_NAME;
import static java.lang.Long.parseLong;
import static java.util.AbstractMap.SimpleImmutableEntry;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.slf4j.LoggerFactory.getLogger;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.segment.analytics.Analytics;
import com.segment.analytics.messages.TrackMessage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import org.eclipse.che.api.core.model.workspace.Workspace;
import org.eclipse.che.api.core.rest.HttpJsonRequestFactory;
import org.eclipse.che.api.factory.shared.dto.FactoryDto;
import org.eclipse.che.api.workspace.shared.Constants;
import org.eclipse.che.api.workspace.shared.dto.WorkspaceDto;
import org.slf4j.Logger;

/**
 * Send event to Segment.com and regularly ping Woopra. For now the events are provided by the
 * {@link UrlToEventFilter}.
 *
 * @author David Festal
 */
@Singleton
public class AnalyticsManager {
  private static final Logger LOG = getLogger(AnalyticsManager.class);

  private static final String pingRequestFormat =
      "http://www.woopra.com/track/ping?host={0}&cookie={1}&timeout={2}";

  private final Analytics analytics;

  @VisibleForTesting static long pingTimeoutSeconds = 30;
  private long pingTimeout = pingTimeoutSeconds * 1000;

  @VisibleForTesting long noActivityTimeout = 60000 * 3;

  private final String workspaceId;

  @VisibleForTesting final String workspaceName;
  @VisibleForTesting final String factoryId;
  @VisibleForTesting final String stackId;
  @VisibleForTesting final String factoryName;
  @VisibleForTesting final String factoryOwner;
  @VisibleForTesting final String createdOn;
  @VisibleForTesting final String updatedOn;
  @VisibleForTesting final String stoppedOn;
  @VisibleForTesting final Long age;
  @VisibleForTesting final Long returnDelay;
  @VisibleForTesting final Boolean firstStart;

  private String segmentWriteKey;
  private String woopraDomain;

  private ScheduledExecutorService checkActivityExecutor =
      Executors.newSingleThreadScheduledExecutor(
          new ThreadFactoryBuilder().setNameFormat("Analytics Activity Checker").build());

  private ScheduledExecutorService networkExecutor =
      Executors.newSingleThreadScheduledExecutor(
          new ThreadFactoryBuilder().setNameFormat("Analytics Network Request Submitter").build());

  @VisibleForTesting LoadingCache<String, EventDispatcher> dispatchers;

  @VisibleForTesting String workspaceStartingUserId = null;
  @VisibleForTesting HttpUrlConnectionProvider httpUrlConnectionProvider = null;

  @Inject
  public AnalyticsManager(
      @Named("env.CHE_WORKSPACE_ID") String workspaceId,
      HttpJsonRequestFactory requestFactory,
      @Named("che.api") String apiEndpoint,
      AnalyticsProvider analyticsProvider,
      HttpUrlConnectionProvider httpUrlConnectionProvider) {
    try {
      String endpoint = apiEndpoint + "/fabric8-che-analytics/segment-write-key";
      segmentWriteKey = requestFactory.fromUrl(endpoint).request().asString();

      endpoint = apiEndpoint + "/fabric8-che-analytics/woopra-domain";
      woopraDomain = requestFactory.fromUrl(endpoint).request().asString();
    } catch (Exception e) {
      throw new RuntimeException("Can't get Che analytics settings from wsmaster", e);
    }

    try {
      String endpoint = apiEndpoint + "/workspace/" + workspaceId;

      Workspace workspace = requestFactory.fromUrl(endpoint).request().asDto(WorkspaceDto.class);

      createdOn = workspace.getAttributes().get(Constants.CREATED_ATTRIBUTE_NAME);
      updatedOn = workspace.getAttributes().get(Constants.UPDATED_ATTRIBUTE_NAME);
      stoppedOn = workspace.getAttributes().get(Constants.STOPPED_ATTRIBUTE_NAME);

      Long createDate = null;
      Long updateDate = null;
      Long stopDate = null;
      try {
        createDate = parseLong(createdOn);
      } catch (NumberFormatException nfe) {
        LOG.warn("the create timestamp ( " + createdOn + " ) has invalid format", nfe);
      }
      try {
        updateDate = parseLong(updatedOn);
      } catch (NumberFormatException nfe) {
        LOG.warn("the update timestamp ( " + updatedOn + " ) has invalid format", nfe);
      }
      if (stoppedOn != null) {
        try {
          stopDate = parseLong(stoppedOn);
        } catch (NumberFormatException nfe) {
          LOG.warn("the stop timestamp ( " + stoppedOn + " ) has invalid format", nfe);
        }
      }

      if (updateDate != null && createDate != null) {
        age = (updateDate - createDate) / 1000;
      } else {
        age = null;
      }
      if (updateDate != null && stopDate != null) {
        returnDelay = (updateDate - stopDate) / 1000;
      } else {
        returnDelay = null;
      }
      if (updateDate != null) {
        firstStart = stopDate == null;
      } else {
        firstStart = null;
      }

      stackId = workspace.getAttributes().get("stackId");
      factoryId = workspace.getAttributes().get("factoryId");
      if (factoryId != null) {
        endpoint = apiEndpoint + "/factory/" + factoryId;

        FactoryDto factory = requestFactory.fromUrl(endpoint).request().asDto(FactoryDto.class);
        factoryName = factory.getName();
        factoryOwner = factory.getCreator().getName();
      } else {
        factoryName = null;
        factoryOwner = null;
      }

      workspaceName = workspace.getConfig().getName();
    } catch (Exception e) {
      throw new RuntimeException("Can't get workspace informations for Che analytics", e);
    }

    if (!segmentWriteKey.isEmpty() && woopraDomain.isEmpty()) {
      throw new RuntimeException(
          "The Woopra domain should be set to provide better visit tracking and duration calculation");
    }

    if (isEnabled()) {
      this.httpUrlConnectionProvider = httpUrlConnectionProvider;

      analytics = analyticsProvider.getAnalytics(segmentWriteKey, networkExecutor);
    } else {
      analytics = null;
    }

    this.workspaceId = workspaceId;

    long checkActivityPeriod = pingTimeoutSeconds * 2 / 3;

    LOG.debug("CheckActivityPeriod: {}", checkActivityPeriod);

    checkActivityExecutor.scheduleAtFixedRate(
        this::checkActivity, checkActivityPeriod, checkActivityPeriod, SECONDS);

    dispatchers =
        CacheBuilder.newBuilder()
            .build(CacheLoader.<String, EventDispatcher>from(userId -> newEventDispatcher(userId)));
  }

  private EventDispatcher newEventDispatcher(String userId) {
    return new EventDispatcher(userId, this);
  }

  public String getWorkspaceId() {
    return workspaceId;
  }

  public boolean isEnabled() {
    return !segmentWriteKey.isEmpty();
  }

  public Analytics getAnalytics() {
    return analytics;
  }

  private void checkActivity() {
    LOG.debug("In checkActivity");
    long inactiveLimit = System.currentTimeMillis() - noActivityTimeout;
    dispatchers
        .asMap()
        .values()
        .forEach(
            dispatcher -> {
              LOG.debug("Checking activity of dispatcher for user: {}", dispatcher.getUserId());
              if (dispatcher.getLastActivityTime() < inactiveLimit) {
                LOG.debug(
                    "Sending 'WORKSPACE_INACTIVE' event for user: {}", dispatcher.getUserId());
                if (dispatcher.sendTrackEvent(
                        WORKSPACE_INACTIVE,
                        Collections.emptyMap(),
                        dispatcher.getLastIp(),
                        dispatcher.getLastUserAgent())
                    != null) {
                  LOG.debug("Sent 'WORKSPACE_INACTIVE' event for user: {}", dispatcher.getUserId());
                  return;
                }
                LOG.debug(
                    "Skip sending 'WORKSPACE_INACTIVE' event for user: {} since it is the same event as the previous one",
                    dispatcher.getUserId());
              } else {
                synchronized (dispatcher) {
                  AnalyticsEvent lastEvent = dispatcher.getLastEvent();
                  if (lastEvent == null) {
                    return;
                  }

                  long expectedDuration = lastEvent.getExpectedDurationSeconds() * 1000;
                  if (lastEvent == WORKSPACE_INACTIVE
                      || (expectedDuration >= 0
                          && System.currentTimeMillis()
                              > expectedDuration + dispatcher.getLastEventTime())) {
                    if (dispatcher.sendTrackEvent(
                            WORKSPACE_USED,
                            Collections.emptyMap(),
                            dispatcher.getLastIp(),
                            dispatcher.getLastUserAgent())
                        != null) {
                      return;
                    }
                  }
                }
              }

              networkExecutor.submit(dispatcher::sendPingRequest);
            });
  }

  public void onActivity(String userId) {
    try {
      dispatchers.get(userId).onActivity();
    } catch (ExecutionException e) {
      LOG.warn("", e);
    }
  }

  public void onEvent(
      String userId,
      AnalyticsEvent event,
      Map<String, Object> properties,
      String ip,
      String userAgent) {
    if (event == WORKSPACE_STARTED) {
      workspaceStartingUserId = userId;
    }
    try {
      dispatchers.get(userId).sendTrackEvent(event, properties, ip, userAgent);
    } catch (ExecutionException e) {
      LOG.warn("", e);
    }
    ;
  }

  @VisibleForTesting
  class EventDispatcher {

    @VisibleForTesting String userId;
    @VisibleForTesting String cookie;

    private AnalyticsEvent lastEvent = null;
    @VisibleForTesting Map<String, Object> lastEventProperties = null;
    private long lastActivityTime;
    private long lastEventTime;
    private String lastIp = null;
    private String lastUserAgent = null;

    private Map<String, Object> commonProperties;

    EventDispatcher(String userId, AnalyticsManager manager) {
      this.userId = userId;
      ImmutableMap.Builder<String, Object> commonPropertiesBuilder = builder();

      commonPropertiesBuilder.put(WORKSPACE_ID, workspaceId);
      commonPropertiesBuilder.put(WORKSPACE_NAME, workspaceName);

      Arrays.asList(
              new SimpleImmutableEntry<>(EventProperties.CREATED, createdOn),
              new SimpleImmutableEntry<>(EventProperties.UPDATED, updatedOn),
              new SimpleImmutableEntry<>(EventProperties.STOPPED, stoppedOn),
              new SimpleImmutableEntry<>(EventProperties.AGE, age),
              new SimpleImmutableEntry<>(EventProperties.RETURN_DELAY, returnDelay),
              new SimpleImmutableEntry<>(EventProperties.FIRST_START, firstStart),
              new SimpleImmutableEntry<>(EventProperties.STACK_ID, stackId),
              new SimpleImmutableEntry<>(EventProperties.FACTORY_ID, factoryId),
              new SimpleImmutableEntry<>(EventProperties.FACTORY_NAME, factoryName),
              new SimpleImmutableEntry<>(EventProperties.FACTORY_OWNER, factoryOwner))
          .forEach(
              (entry) -> {
                if (entry.getValue() != null) {
                  commonPropertiesBuilder.put(entry.getKey(), entry.getValue());
                }
              });

      commonProperties = commonPropertiesBuilder.build();
      cookie =
          Hashing.md5()
              .hashString(workspaceId + userId + System.currentTimeMillis(), StandardCharsets.UTF_8)
              .toString();
      LOG.info(
          "Analytics Woopra Cookie for user {} and workspace {} : {}", userId, workspaceId, cookie);
    }

    void onActivity() {
      lastActivityTime = System.currentTimeMillis();
    }

    void sendPingRequest() {
      try {
        String uri =
            MessageFormat.format(
                pingRequestFormat,
                URLEncoder.encode(woopraDomain, "UTF-8"),
                URLEncoder.encode(cookie, "UTF-8"),
                Long.toString(pingTimeout));
        LOG.debug("Sending a PING request to woopra for user '{}': {}", getUserId(), uri);
        HttpURLConnection httpURLConnection = httpUrlConnectionProvider.getHttpUrlConnection(uri);

        String responseMessage;
        try (BufferedReader br =
                new BufferedReader(new InputStreamReader(httpURLConnection.getInputStream()));
            StringWriter sw = new StringWriter()) {
          String inputLine;

          while ((inputLine = br.readLine()) != null) {
            sw.write(inputLine);
          }
          responseMessage = sw.toString();
        }
        LOG.debug("Woopra PING response for user '{}': {}", userId, responseMessage);
        if (responseMessage == null || !responseMessage.toString().contains("success: true")) {
          LOG.warn("Cannot ping woopra: response message : {}", responseMessage);
        }
      } catch (Exception e) {
        LOG.warn("Cannot ping woopra", e);
      }
    }

    private boolean areEventsEqual(AnalyticsEvent event, Map<String, Object> properties) {
      if (lastEvent == null || lastEvent != event) {
        return false;
      }

      if (lastEventProperties == null) {
        return false;
      }

      for (String propToCheck : event.getPropertiesToCheck()) {
        Object lastValue = lastEventProperties.get(propToCheck);
        Object newValue = properties.get(propToCheck);
        if (lastValue != null && newValue != null && lastValue.equals(newValue)) {
          continue;
        }
        if (lastValue == null && newValue == null) {
          continue;
        }
        return false;
      }

      return true;
    }

    String sendTrackEvent(
        AnalyticsEvent event, final Map<String, Object> properties, String ip, String userAgent) {
      String eventId;
      lastIp = ip;
      lastUserAgent = userAgent;
      final String theIp = ip != null ? ip : "0.0.0.0";
      synchronized (this) {
        lastEventTime = System.currentTimeMillis();
        if (areEventsEqual(event, properties)) {
          LOG.debug("Skipping event " + event.toString() + " since it is the same as the last one");
          return null;
        }

        eventId = UUID.randomUUID().toString();
        TrackMessage.Builder messageBuilder =
            TrackMessage.builder(event.toString()).userId(userId).messageId(eventId);

        ImmutableMap.Builder<String, Object> integrationBuilder =
            ImmutableMap.<String, Object>builder().put("cookie", cookie);
        if (event.getExpectedDurationSeconds() == 0) {
          integrationBuilder.put("duration", 0);
        }
        messageBuilder.integrationOptions("Woopra", integrationBuilder.build());

        ImmutableMap.Builder<String, Object> propertiesBuilder =
            ImmutableMap.<String, Object>builder().putAll(commonProperties).putAll(properties);
        messageBuilder.properties(propertiesBuilder.build());

        ImmutableMap.Builder<String, Object> contextBuilder =
            ImmutableMap.<String, Object>builder().put("ip", ip);
        if (userAgent != null) {
          contextBuilder.put("userAgent", userAgent);
        }
        if (event.getExpectedDurationSeconds() == 0) {
          contextBuilder.put("duration", 0);
        }
        messageBuilder.context(contextBuilder.build());

        LOG.debug(
            "sending "
                + event.toString()
                + " (ip="
                + theIp
                + " - userAgent="
                + userAgent
                + ") with properties: "
                + properties);
        analytics.enqueue(messageBuilder);

        lastEvent = event;
        lastEventProperties = properties;
      }
      return eventId;
    }

    long getLastActivityTime() {
      return lastActivityTime;
    }

    String getLastIp() {
      return lastIp;
    }

    String getLastUserAgent() {
      return lastUserAgent;
    }

    String getUserId() {
      return userId;
    }

    AnalyticsEvent getLastEvent() {
      return lastEvent;
    }

    long getLastEventTime() {
      return lastEventTime;
    }
  }

  @PreDestroy
  void destroy() {
    if (workspaceStartingUserId != null) {
      EventDispatcher dispatcher;
      try {
        dispatcher = dispatchers.get(workspaceStartingUserId);
        dispatcher.sendTrackEvent(
            WORKSPACE_STOPPED,
            Collections.emptyMap(),
            dispatcher.getLastIp(),
            dispatcher.getLastUserAgent());
      } catch (ExecutionException e) {
      }
    }
    shutdown();
  }

  void shutdown() {
    checkActivityExecutor.shutdown();
    if (analytics != null) {
      analytics.shutdown();
    }
  }
}

/**
 * Returns an {@link Analytics} object from a Segment write Key.
 *
 * @author David Festal
 */
class AnalyticsProvider {
  public Analytics getAnalytics(String segmentWriteKey, ExecutorService networkExecutor) {
    return Analytics.builder(segmentWriteKey)
        .networkExecutor(networkExecutor)
        .flushQueueSize(1)
        .build();
  }
}

/**
 * Returns a {@link HttpURLConnection} object from a {@link URI}.
 *
 * @author David Festal
 */
class HttpUrlConnectionProvider {
  public HttpURLConnection getHttpUrlConnection(String uri)
      throws MalformedURLException, IOException, URISyntaxException {
    return (HttpURLConnection) new URI(uri).toURL().openConnection();
  }
}
