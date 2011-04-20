/*
 * Copyright 2011 Google Inc.
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
package com.google.gwt.sample.mobilewebapp.client.activity;

import com.google.gwt.user.client.ui.IsWidget;
import com.google.gwt.sample.mobilewebapp.shared.TaskProxy;

import java.util.List;

/**
 * A view of a {@link TaskListActivity}.
 */
public interface TaskListView extends IsWidget {

  /**
   * The presenter for this view.
   */
  public static interface Presenter {

    /**
     * Select a task.
     * 
     * @param selected the select task
     */
    void selectTask(TaskProxy selected);
  }

  /**
   * Clear the list of tasks.
   */
  void clearList();

  /**
   * Set the {@link Presenter} for this view.
   * 
   * @param presenter the presenter
   */
  void setPresenter(Presenter presenter);

  /**
   * Set the list of tasks to display.
   * 
   * @param tasks the list of tasks
   */
  void setTasks(List<TaskProxy> tasks);
}
