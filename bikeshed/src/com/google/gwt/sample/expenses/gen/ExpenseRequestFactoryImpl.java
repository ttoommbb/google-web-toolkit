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
package com.google.gwt.sample.expenses.gen;

import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.requestfactory.shared.SyncRequest;
import com.google.gwt.sample.expenses.shared.ExpenseRequestFactory;
import com.google.gwt.user.client.ui.HasValue;
import com.google.gwt.user.client.ui.HasValueList;
import com.google.gwt.valuestore.client.ValuesImpl;
import com.google.gwt.valuestore.shared.DeltaValueStore;
import com.google.gwt.valuestore.shared.Property;
import com.google.gwt.valuestore.shared.ValueStore;
import com.google.gwt.valuestore.shared.Values;

import java.util.List;
import java.util.Set;

/**
 * "Generated" factory for requests against
 * com.google.gwt.sample.expenses.domain.
 * <p>
 * IRL would be an interface that was generated by a JPA-savvy script, and the
 * following implementation would in turn be generated by a call to
 * GWT.create(ExpenseRequestFactory.class)
 */
public class ExpenseRequestFactoryImpl implements ExpenseRequestFactory {
  public final ValueStore values = new ValueStore() {

    public void addValidation() {
      // TODO Auto-generated method stub
    }

    public DeltaValueStore edit() {
      // TODO Auto-generated method stub
      return null;
    }

    public <T, V> void subscribe(HasValue<V> watcher, T propertyOwner,
        Property<T, V> property) {
      // TODO Auto-generated method stub
    }

    public <T, V> void subscribe(HasValueList<Values<T>> watcher,
        T propertyOwner, Set<Property<T, ?>> properties) {
      // TODO Auto-generated method stub
    }

  };

  public EmployeeRequest employeeRequest() {
    return new EmployeeRequestImpl(values);
  }

  public EmployeeRequest employeeRequest(DeltaValueStore deltas) {
    return new EmployeeRequestImpl(deltas);
  }

  public ValueStore getValueStore() {
    return values;
  }

  public ReportRequest reportRequest() {
    return new ReportRequestImpl(values);
  }

  public ReportRequest reportRequest(DeltaValueStore deltas) {
    return new ReportRequestImpl(deltas);
  }

  /**
   * @param deltaValueStore
   * @return
   */
  public SyncRequest syncRequest(final List<Values<?>> deltaValueStore) {
    return new SyncRequest() {

      public void fire() {

        // TODO: need some way to track that this request has been issued so
        // that we don't issue another request that arrives while we are
        // waiting for the response.
        RequestBuilder builder = new RequestBuilder(RequestBuilder.POST,
            "/expenses/data?methodName=" + MethodName.SYNC.name());

        StringBuilder requestData = new StringBuilder("[");
        boolean first = true;
        for (Values<?> v : deltaValueStore) {
          ValuesImpl<?> impl = (ValuesImpl<?>) v;
          if (first) {
            first = false;
          } else {
            requestData.append(",");
          }
          requestData.append(impl.toJson());
        }
        requestData.append("]");

        builder.setRequestData(requestData.toString());
        builder.setCallback(new RequestCallback() {

          public void onError(Request request, Throwable exception) {
            // shell.error.setInnerText(SERVER_ERROR);
          }

          public void onResponseReceived(Request request, Response response) {
            if (200 == response.getStatusCode()) {
              // String text = response.getText();
              // parse the return value.

              // publish this value to all subscribers that are interested.
            } else {
              // shell.error.setInnerText(SERVER_ERROR + " ("
              // + response.getStatusText() + ")");
            }
          }
        });

        try {
          builder.send();
        } catch (RequestException e) {
          // shell.error.setInnerText(SERVER_ERROR + " (" + e.getMessage() +
          // ")");
        }
        // values.subscribe(watcher, future, properties);
      }

    };
  }
}
