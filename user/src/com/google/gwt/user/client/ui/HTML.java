/*
 * Copyright 2006 Google Inc.
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
package com.google.gwt.user.client.ui;

import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;
import com.google.gwt.i18n.client.BidiUtils;
import com.google.gwt.i18n.shared.BidiFormatter;
import com.google.gwt.i18n.shared.DirectionEstimator;
import com.google.gwt.safehtml.shared.SafeHtml;

/**
 * A widget that can contain arbitrary HTML.
 *
 * This widget uses a &lt;div&gt; element, causing it to be displayed with block
 * layout.
 *
 * <p>
 * If you only need a simple label (text, but not HTML), then the
 * {@link com.google.gwt.user.client.ui.Label} widget is more appropriate, as it
 * disallows the use of HTML, which can lead to potential security issues if not
 * used properly.
 * </p>
 *
 * <h3>CSS Style Rules</h3>
 * <ul class='css'>
 * <li>.gwt-HTML { }</li>
 * </ul>
 *
 * <p>
 * <h3>Example</h3>
 * {@example com.google.gwt.examples.HTMLExample}
 * </p>
 */
public class HTML extends Label 
    implements HasDirectionalHtml, HasDirectionalSafeHtml {

  /**
   * Creates an HTML widget that wraps an existing &lt;div&gt; or &lt;span&gt;
   * element.
   *
   * This element must already be attached to the document. If the element is
   * removed from the document, you must call
   * {@link RootPanel#detachNow(Widget)}.
   *
   * @param element the element to be wrapped
   */
  public static HTML wrap(Element element) {
    // Assert that the element is attached.
    assert Document.get().getBody().isOrHasChild(element);

    HTML html = new HTML(element);

    // Mark it attached and remember it for cleanup.
    html.onAttach();
    RootPanel.detachOnWindowClose(html);

    return html;
  }

  /**
   * Creates an empty HTML widget.
   */
  public HTML() {
    super(Document.get().createDivElement());
    setStyleName("gwt-HTML");
  }

  /**
   * Initializes the widget's HTML from a given {@link SafeHtml} object.
   *
   * @param html the new widget's HTML contents
   */
  public HTML(SafeHtml html) {
    this(html.asString());
  }

  /**
   * Creates an HTML widget with the specified HTML contents.
   *
   * @param html the new widget's HTML contents
   */
  public HTML(String html) {
    this();
    setHTML(html);
  }

  /**
   * Creates an HTML widget with the specified contents and with the
   * specified direction.
   *
   * @param html the new widget's SafeHtml contents
   * @param dir the content's direction. Note: {@code Direction.DEFAULT} means
   *        direction should be inherited from the widget's parent element.
   */
  public HTML(SafeHtml html, Direction dir) {
    this(html.asString(), dir);
  }

  /**
   * Creates an HTML widget with the specified HTML contents and with the
   * specified direction.
   *
   * @param html the new widget's HTML contents
   * @param dir the content's direction. Note: {@code Direction.DEFAULT} means
   *        direction should be inherited from the widget's parent element.
   */
  public HTML(String html, Direction dir) {
    this();
    setHTML(html, dir);
  }

  /**
   * Creates an HTML widget with the specified contents, optionally treating it
   * as HTML, and optionally disabling word wrapping.
   *
   * @param html the widget's contents
   * @param wordWrap <code>false</code> to disable word wrapping
   */
  public HTML(String html, boolean wordWrap) {
    this(html);
    setWordWrap(wordWrap);
  }

  /**
   * This constructor may be used by subclasses to explicitly use an existing
   * element. This element must be either a &lt;div&gt; or &lt;span&gt; element.
   *
   * @param element the element to be used
   */
  protected HTML(Element element) {
    // super(element) asserts that element is either a &lt;div&gt; or
    // &lt;span&gt;.
    super(element);
  }

  public String getHTML() {
    return getTextOrHtml(true);
  }

  /**
   * Sets the widget element's direction.
   * @deprecated Use {@link #setDirectionEstimator} and / or pass explicit
   * direction to {@link #setText} instead
   */
  @Deprecated
  public void setDirection(Direction direction) {
    BidiUtils.setDirectionOnElement(getElement(), direction);
    initialElementDir = direction;

    // For backwards compatibility, assure there's no span wrap, and update the
    // content direction.
    setInnerTextOrHtml(getTextOrHtml(true), true);
    isSpanWrapped = false;
    textDir = initialElementDir;
    updateHorizontalAlignment();
  }

  /**
   * {@inheritDoc}
   * <p>
   * Note: if the widget already has non-empty content, this will update
   * its direction according to the new estimator's result. This may cause
   * flicker, and thus should be avoided; DirectionEstimator should be set
   * before the widget has any content.
   */
  public void setDirectionEstimator(DirectionEstimator directionEstimator) {
    this.directionEstimator = directionEstimator;
    // Refresh appearance
    setHTML(getTextOrHtml(true));
  }

  /**
   * Sets the label's content to the given HTML.
   * See {@link #setText(String)} for details on potential effects on direction
   * and alignment.
   *
   * @param html the new widget's HTML content
   */
  public void setHTML(String html) {
    if (directionEstimator == null) {
      isSpanWrapped = false;
      getElement().setInnerHTML(html);

      // Preserves the initial direction of the widget. This is different from
      // passing the direction parameter explicitly as DEFAULT, which forces the
      // widget to inherit the direction from its parent.
      if (textDir != initialElementDir) {
        textDir = initialElementDir;
        BidiUtils.setDirectionOnElement(getElement(), initialElementDir);
        updateHorizontalAlignment();
      }
    } else {
      setHTML(html, directionEstimator.estimateDirection(html, true));
    }
  }

  /**
   * Sets the label's content to the given HTML, applying the given direction.
   * See
   * {@link #setText(String, com.google.gwt.i18n.client.HasDirection.Direction)
   * setText(String, Direction)} for details on potential effects on alignment.
   * 
   * @param html the new widget's HTML content
   * @param dir the content's direction. Note: {@code Direction.DEFAULT} means
   *          direction should be inherited from the widget's parent element.
   */
  public void setHTML(String html, Direction dir) {
    textDir = dir;

    // Set the text and the direction.
    if (isElementInline) {
      isSpanWrapped = true;
      getElement().setInnerHTML(BidiFormatter.getInstanceForCurrentLocale(
          true /* alwaysSpan */).spanWrapWithKnownDir(dir, html, true));
    } else {
      isSpanWrapped = false;
      BidiUtils.setDirectionOnElement(getElement(), dir);
      getElement().setInnerHTML(html);
    }

    // Update the horizontal alignment if needed.
    updateHorizontalAlignment();
  }

  /**
   * Sets this object's contents via known-safe HTML.
   * 
   * @see com.google.gwt.safehtml.client.HasSafeHtml#setHTML(SafeHtml)
   * @param html the html to set.
   */
  public void setHTML(SafeHtml html) {
    setHTML(html.asString());
  }

  public void setHTML(SafeHtml html, Direction dir) {
    setHTML(html.asString(), dir);
  }

  protected String getTextOrHtml(boolean isHtml) {
    Element element = isSpanWrapped ? getElement().getFirstChildElement()
        : getElement();
    return isHtml ? element.getInnerHTML() : element.getInnerText();
  }

  private void setInnerTextOrHtml(String content, boolean isHtml) {
    if (isHtml) {
      getElement().setInnerHTML(content);
    } else {
      getElement().setInnerText(content);
    }
  }
}
