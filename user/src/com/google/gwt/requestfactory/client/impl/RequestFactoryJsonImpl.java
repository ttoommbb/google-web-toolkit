/*
 * Copyright 2010 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.requestfactory.client.impl;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.shared.HandlerManager;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.requestfactory.client.RequestFactoryLogHandler;
import com.google.gwt.requestfactory.shared.RequestEvent;
import com.google.gwt.requestfactory.shared.RequestFactory;
import com.google.gwt.requestfactory.shared.RequestObject;
import com.google.gwt.requestfactory.shared.RequestEvent.State;
import com.google.gwt.user.client.Window.Location;
import com.google.gwt.valuestore.shared.Record;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <p>
 * <span style="color:red">Experimental API: This class is still under rapid
 * development, and is very likely to be deleted. Use it at your own risk.
 * </span>
 * </p>
 * Base implementation of RequestFactory.
 */
public abstract class RequestFactoryJsonImpl implements RequestFactory {

  // TODO(amitmanjhi) Dump this and the one in DeltaValueStore in favor of
  // RecordImpl#isFuture
  static class FutureIdGenerator {
    Set<Long> idsInTransit = new HashSet<Long>();
    Long maxId = 1L;

    void delete(Long id) {
      idsInTransit.remove(id);
    }

    Long getFutureId() {
      Long futureId = maxId++;
      if (maxId == Long.MAX_VALUE) {
        maxId = 1L;
      }
      assert !idsInTransit.contains(futureId);
      return futureId;
    }
  }

  private static Logger logger = Logger.getLogger(RequestFactory.class.getName());

  // A separate logger for wire activity, which does not get logged by the
  // remote log handler, so we avoid infinite loops. All log messages that
  // could happen every time a request is made from the server should be logged
  // to this logger.
  private static Logger wireLogger = Logger.getLogger("WireActivityLogger");

  private static String SERVER_ERROR = "Server Error";

  private static final Integer INITIAL_VERSION = 1;

  final FutureIdGenerator futureIdGenerator = new FutureIdGenerator();

  final Map<RecordKey, RecordJsoImpl> creates = new HashMap<RecordKey, RecordJsoImpl>();

  private ValueStoreJsonImpl valueStore;

  private HandlerManager handlerManager;

  public com.google.gwt.valuestore.shared.Record create(
      Class<? extends Record> token, RecordToTypeMap recordToTypeMap) {

    RecordSchema<? extends Record> schema = recordToTypeMap.getType(token);
    if (schema == null) {
      throw new IllegalArgumentException("Unknown proxy type: " + token);
    }
    return createFuture(schema);
  }

  public void fire(final RequestObject<?> requestObject) {
    RequestBuilder builder = new RequestBuilder(RequestBuilder.POST,
        GWT.getHostPageBaseURL() + RequestFactory.URL);
    builder.setHeader("Content-Type", RequestFactory.JSON_CONTENT_TYPE_UTF8);
    builder.setHeader("pageurl", Location.getHref());
    builder.setRequestData(ClientRequestHelper.getRequestString(requestObject.getRequestData().getRequestMap(
        ((AbstractRequest) requestObject).deltaValueStore.toJson())));
    builder.setCallback(new RequestCallback() {

      public void onError(Request request, Throwable exception) {
        postRequestEvent(State.RECEIVED, null);
        wireLogger.log(Level.SEVERE, SERVER_ERROR, exception);
      }

      public void onResponseReceived(Request request, Response response) {
        wireLogger.finest("Response received");
        if (200 == response.getStatusCode()) {
          String text = response.getText();
          requestObject.handleResponseText(text);
        } else if (Response.SC_UNAUTHORIZED == response.getStatusCode()) {
          wireLogger.finest("Need to log in");
        } else if (response.getStatusCode() > 0) {
          // During the redirection for logging in, we get a response with no
          // status code, but it's not an error, so we only log errors with
          // bad status codes here.
          wireLogger.severe(SERVER_ERROR + " " + response.getStatusCode() + " "
              + response.getText());
        }
        postRequestEvent(State.RECEIVED, response);
      }

    });

    try {
      wireLogger.finest("Sending fire request");
      builder.send();
      postRequestEvent(State.SENT, null);
    } catch (RequestException e) {
      wireLogger.log(Level.SEVERE, SERVER_ERROR + " (" + e.getMessage() + ")",
          e);
    }
  }

  /**
   * @param handlerManager
   */
  public void init(HandlerManager handlerManager) {
    this.valueStore = new ValueStoreJsonImpl(handlerManager);
    this.handlerManager = handlerManager;
    Logger.getLogger("").addHandler(
        new RequestFactoryLogHandler(this, Level.WARNING, wireLogger.getName()));
    logger.fine("Successfully initialized RequestFactory");
  }

  ValueStoreJsonImpl getValueStore() {
    return valueStore;
  }

  private Record createFuture(
      RecordSchema<? extends Record> schema) {
    Long futureId = futureIdGenerator.getFutureId();
    RecordJsoImpl newRecord = RecordJsoImpl.create(futureId, INITIAL_VERSION,
        schema);
    RecordKey recordKey = new RecordKey(newRecord);
    creates.put(recordKey, newRecord);
    return schema.create(newRecord);
  }

  private void postRequestEvent(State received, Response response) {
    handlerManager.fireEvent(new RequestEvent(received, response));
  }
}
