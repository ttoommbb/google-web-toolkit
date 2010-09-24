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
package com.google.gwt.app.place.testplacemappers;

import com.google.gwt.app.place.PlaceHistoryMapperWithFactory;
import com.google.gwt.app.place.WithTokenizers;
import com.google.gwt.app.place.testplaces.Tokenizer4;
import com.google.gwt.app.place.testplaces.TokenizerFactory;

/**
 * Used by tests of {@link com.google.gwt.app.rebind.PlaceHistoryMapperGenerator}.
 */
@WithTokenizers(Tokenizer4.class)
public interface WithFactory extends
  PlaceHistoryMapperWithFactory<TokenizerFactory> {
}