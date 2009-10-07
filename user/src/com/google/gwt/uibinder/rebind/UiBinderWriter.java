/*
 * Copyright 2008 Google Inc.
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
package com.google.gwt.uibinder.rebind;

import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.core.ext.typeinfo.JClassType;
import com.google.gwt.core.ext.typeinfo.JMethod;
import com.google.gwt.core.ext.typeinfo.JPackage;
import com.google.gwt.core.ext.typeinfo.JParameter;
import com.google.gwt.core.ext.typeinfo.TypeOracle;
import com.google.gwt.dom.client.TagName;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.parsers.AttributeMessageParser;
import com.google.gwt.uibinder.parsers.AttributeParser;
import com.google.gwt.uibinder.parsers.BeanParser;
import com.google.gwt.uibinder.parsers.BundleAttributeParser;
import com.google.gwt.uibinder.parsers.ElementParser;
import com.google.gwt.uibinder.parsers.StrictAttributeParser;
import com.google.gwt.uibinder.rebind.messages.MessagesWriter;
import com.google.gwt.uibinder.rebind.model.ImplicitClientBundle;
import com.google.gwt.uibinder.rebind.model.ImplicitCssResource;
import com.google.gwt.uibinder.rebind.model.OwnerClass;
import com.google.gwt.uibinder.rebind.model.OwnerField;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Writer for UiBinder generated classes.
 *
 * TODO(rdamazio): Refactor this, extract model classes, improve ordering
 * guarantees, etc.
 *
 * TODO(rjrjr): Improve error messages
 */
@SuppressWarnings("deprecation")
public class UiBinderWriter {
  private static final String BINDER_URI = "urn:ui:com.google.gwt.uibinder";
  private static final String BUNDLE_URI_SCHEME = "urn:with:";
  private static final String PACKAGE_URI_SCHEME = "urn:import:";

  private static int domId = 0;

  // TODO(rjrjr) Another place that we need a general anonymous field
  // mechanism
  private static final String CLIENT_BUNDLE_FIELD = "clientBundleFieldNameUnlikelyToCollideWithUserSpecifiedFieldOkay";

  public static String asCommaSeparatedList(String... args) {
    StringBuilder b = new StringBuilder();
    for (String arg : args) {
      if (b.length() > 0) {
        b.append(", ");
      }
      b.append(arg);
    }

    return b.toString();
  }

  /**
   * Escape text that will be part of a string literal to be interpreted at
   * runtime as an HTML attribute value.
   */
  public static String escapeAttributeText(String text) {
    text = escapeText(text, false);

    /*
     * Escape single-quotes to make them safe to be interpreted at runtime as an
     * HTML attribute value (for which we by convention use single quotes).
     */
    text = text.replaceAll("'", "&#39;");
    return text;
  }

  /**
   * Escape text that will be part of a string literal to be interpreted at
   * runtime as HTML, optionally preserving whitespace.
   */
  public static String escapeText(String text, boolean preserveWhitespace) {
    // Replace reserved XML characters with entities. Note that we *don't*
    // replace single- or double-quotes here, because they're safe in text
    // nodes.
    text = text.replaceAll("&", "&amp;");
    text = text.replaceAll("<", "&lt;");
    text = text.replaceAll(">", "&gt;");

    if (!preserveWhitespace) {
      text = text.replaceAll("\\s+", " ");
    }

    return escapeTextForJavaStringLiteral(text);
  }

  /**
   * Escape characters that would mess up interpretation of this string as a
   * string literal in generated code (that is, protect \n and " ).
   */
  public static String escapeTextForJavaStringLiteral(String text) {
    text = text.replaceAll("\"", "\\\\\"");
    text = text.replaceAll("\n", "\\\\n");

    return text;
  }

  private static String capitalizePropName(String propName) {
    return propName.substring(0, 1).toUpperCase() + propName.substring(1);
  }

  private static AttributeParser getAttributeParserByClassName(
      String parserClassName) {
    try {
      Class<? extends AttributeParser> parserClass = Class.forName(
          parserClassName).asSubclass(AttributeParser.class);
      return parserClass.newInstance();
    } catch (ClassNotFoundException e) {
      throw new RuntimeException("Unable to instantiate parser", e);
    } catch (ClassCastException e) {
      throw new RuntimeException(parserClassName
          + " must extend AttributeParser");
    } catch (InstantiationException e) {
      throw new RuntimeException("Unable to instantiate parser", e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException("Unable to instantiate parser", e);
    }
  }

  /**
   * Returns a list of the given type and all its superclasses and implemented
   * interfaces in a breadth-first traversal.
   *
   * @param type the base type
   * @return a breadth-first collection of its type hierarchy
   */
  private static Iterable<JClassType> getClassHierarchyBreadthFirst(
      JClassType type) {
    LinkedList<JClassType> list = new LinkedList<JClassType>();
    LinkedList<JClassType> q = new LinkedList<JClassType>();

    q.add(type);
    while (!q.isEmpty()) {
      // Pop the front of the queue and add it to the result list.
      JClassType curType = q.removeFirst();
      list.add(curType);

      // Add the superclass and implemented interfaces to the back of the queue.
      JClassType superClass = curType.getSuperclass();
      if (superClass != null) {
        q.add(superClass);
      }
      for (JClassType intf : curType.getImplementedInterfaces()) {
        q.add(intf);
      }
    }

    return list;
  }

  private final MortalLogger logger;

  /**
   * Class names of parsers for values of attributes with no namespace prefix,
   * keyed by method parameter signatures.
   *
   * TODO(rjrjr) Seems like the attribute parsers belong in BeanParser, which is
   * the only thing that uses them.
   */
  private final Map<String, String> attributeParsers = new HashMap<String, String>();
  /**
   * Class names of parsers for various ui types, keyed by the classname of the
   * UI class they can build.
   */
  private final Map<String, String> elementParsers = new HashMap<String, String>();

  /**
   * Map of bundle parsers, keyed by bundle class name.
   */
  private final Map<String, BundleAttributeParser> bundleParsers = new HashMap<String, BundleAttributeParser>();

  private final List<String> initStatements = new ArrayList<String>();
  private final List<String> statements = new ArrayList<String>();
  private final HandlerEvaluator handlerEvaluator;
  private final MessagesWriter messages;
  private final Tokenator tokenator = new Tokenator();

  private final String templatePath;
  private final TypeOracle oracle;
  /**
   * The type we have been asked to generated, e.g. MyUiBinder
   */
  private final JClassType baseClass;
  /**
   * The name of the class we're creating, e.g. MyUiBinderImpl
   */
  private final String implClassName;

  private final JClassType uiOwnerType;

  private final JClassType uiRootType;

  private final OwnerClass ownerClass;

  private final FieldManager fieldManager;

  private final ImplicitClientBundle bundleClass;

  private int fieldIndex;

  private String gwtPrefix;

  private String rendered;

  /**
   * Stack of element variable names that have been attached.
   */
  private final LinkedList<String> attachSectionElements = new LinkedList<String>();
  /**
   * Maps from field element name to the temporary attach record variable name.
   */
  private final Map<String, String> attachedVars = new HashMap<String, String>();
  private int nextAttachVar = 0;

  /**
   * Stack of statements to be executed after we detach the current attach
   * section.
   */
  private final LinkedList<List<String>> detachStatementsStack = new LinkedList<List<String>>();

  UiBinderWriter(JClassType baseClass, String implClassName,
      String templatePath, TypeOracle oracle, MortalLogger logger)
      throws UnableToCompleteException {
    this.baseClass = baseClass;
    this.implClassName = implClassName;
    this.oracle = oracle;
    this.logger = logger;
    this.templatePath = templatePath;

    this.messages = new MessagesWriter(BINDER_URI, logger, templatePath,
        baseClass.getPackage().getName(), this.implClassName);

    JClassType uiBinderType = baseClass.getImplementedInterfaces()[0];
    JClassType[] typeArgs = uiBinderType.isParameterized().getTypeArgs();
    uiRootType = typeArgs[0];
    uiOwnerType = typeArgs[1];

    ownerClass = new OwnerClass(uiOwnerType, logger);
    bundleClass = new ImplicitClientBundle(baseClass.getPackage().getName(),
        this.implClassName, CLIENT_BUNDLE_FIELD, logger);
    handlerEvaluator = new HandlerEvaluator(ownerClass, logger, oracle);
    fieldManager = new FieldManager(logger);
  }

  /**
   * Statements to be excuted right after the current attached element is
   * detached. This is useful for doing things that might be expensive while the
   * element is attached to the DOM.
   *
   * @param format
   * @param args
   */
  public void addDetachStatement(String format, Object... args) {
    detachStatementsStack.getFirst().add(String.format(format, args));
  }

  /**
   * Add a statement to be run after everything has been instantiated, in the
   * style of {@link String#format}
   */
  public void addInitStatement(String format, Object... params) {
    initStatements.add(formatCode(format, params));
  }

  /**
   * Adds a statement to the block run after fields are declared, in the style
   * of {@link String#format}
   */
  public void addStatement(String format, Object... args) {
    statements.add(formatCode(format, args));
  }

  /**
   * Begin a section where a new attachable element is being parsed. Note that
   * attachment is only done when actually needed.
   *
   * @param element to be attached for this section
   */
  public void beginAttachedSection(String element) {
    attachSectionElements.addFirst(element);
    detachStatementsStack.addFirst(new ArrayList<String>());
  }

  /**
   * Declare a field that will hold an Element instance. Returns a token that
   * the caller must set as the id attribute of that element in whatever
   * innerHTML expression will reproduce it at runtime.
   * <P>
   * In the generated code, this token will be replaced by an expression to
   * generate a unique dom id at runtime. Further code will be generated to be
   * run after widgets are instantiated, to use that dom id in a getElementById
   * call and assign the Element instance to its field.
   *
   * @param fieldName The name of the field being declared
   * @param parentElementExpression an expression to be evaluated at runtime,
   *          which will return an Element that is an ancestor of this one
   *          (needed by the getElementById call mentioned above).
   */
  public String declareDomField(String fieldName, String parentElementExpression)
      throws UnableToCompleteException {
    ensureAttached(parentElementExpression);
    String name = declareDomIdHolder();
    setFieldInitializer(fieldName, "null");
    addInitStatement(
        "%s = com.google.gwt.dom.client.Document.get().getElementById(%s).cast();",
        fieldName, name);
    addInitStatement("%s.removeAttribute(\"id\");", fieldName);
    return tokenForExpression(name);
  }

  /**
   * Declare a variable that will be filled at runtime with a unique id, safe
   * for use as a dom element's id attribute.
   *
   * @return that variable's name.
   */
  public String declareDomIdHolder() throws UnableToCompleteException {
    String domHolderName = "domId" + domId++;
    FieldWriter domField = fieldManager.registerField(
        oracle.findType(String.class.getName()), domHolderName);
    domField.setInitializer("com.google.gwt.dom.client.Document.get().createUniqueId()");
    return domHolderName;
  }

  /**
   * Declares a field of the given type name, returning the name of the declared
   * field. If the element has a field or id attribute, use its value.
   * Otherwise, create and return a new, private field name for it.
   */
  public String declareField(String typeName, XMLElement elem)
      throws UnableToCompleteException {
    JClassType type = oracle.findType(typeName);
    if (type == null) {
      die("In %s, unknown type %s", elem, typeName);
    }

    String fieldName = getFieldName(elem);
    if (fieldName == null) {
      // TODO(rjrjr) could collide with user declared name, as is
      // also a worry in HandlerEvaluator. Need a general scheme for
      // anonymous fields. See the note in HandlerEvaluator and do
      // something like that, but in FieldManager.
      fieldName = ("f_" + elem.getLocalName() + (++fieldIndex));
    }
    fieldName = normalizeFieldName(fieldName);
    fieldManager.registerField(type, fieldName);
    return fieldName;
  }

  /**
   * If this element has a gwt:field attribute, create a field for it of the
   * appropriate type, and return the field name. If no gwt:field attribute is
   * found, do nothing and return null
   *
   * @return The new field name, or null if no field is created
   */
  public String declareFieldIfNeeded(XMLElement elem)
      throws UnableToCompleteException {
    String fieldName = getFieldName(elem);
    if (fieldName != null) {
      fieldManager.registerField(findFieldType(elem), fieldName);
    }
    return fieldName;
  }

  /**
   * Given a string containing tokens returned by {@link #tokenForExpression} or
   * {@link #declareDomField}, return a string with those tokens replaced by the
   * appropriate expressions. (It is not normally necessary for an
   * {@link XMLElement.Interpreter} or {@link ElementParser} to make this call,
   * as the tokens are typically replaced by the TemplateWriter itself.)
   */
  public String detokenate(String betokened) {
    return tokenator.detokenate(betokened);
  }

  /**
   * Post an error message and halt processing. This method always throws an
   * {@link UnableToCompleteException}
   */
  public void die(String message) throws UnableToCompleteException {
    logger.die(message);
  }

  /**
   * Post an error message and halt processing. This method always throws an
   * {@link UnableToCompleteException}
   */
  public void die(String message, Object... params)
      throws UnableToCompleteException {
    logger.die(message, params);
  }

  /**
   * End the current attachable section. This will detach the element if it was
   * ever attached and execute any detach statements.
   */
  public void endAttachedSection() {
    String elementVar = attachSectionElements.removeFirst();
    List<String> detachStatements = detachStatementsStack.removeFirst();
    if (attachedVars.containsKey(elementVar)) {
      String attachedVar = attachedVars.remove(elementVar);
      addInitStatement("%s.detach();", attachedVar);
      for (String statement : detachStatements) {
        addInitStatement(statement);
      }
    }
  }

  /**
   * Ensure that the specified element is attached to the DOM.
   *
   * @param element variable name of element to be attached
   */
  public void ensureAttached(String element) {
    String attachSectionElement = attachSectionElements.getFirst();
    if (!attachedVars.containsKey(attachSectionElement)) {
      String attachedVar = "attachRecord" + nextAttachVar;
      addInitStatement(
          "UiBinderUtil.TempAttachment %s = UiBinderUtil.attachToDom(%s);",
          attachedVar, attachSectionElement);
      attachedVars.put(attachSectionElement, attachedVar);
      nextAttachVar++;
    }
  }

  /**
   * Ensure that the specified field is attached to the DOM.
   *
   * @param field variable name of the field to be attached
   */
  public void ensureFieldAttached(String field) {
    ensureAttached(field + ".getElement()");
  }

  /**
   * Finds the JClassType that corresponds to this XMLElement, which must be a
   * Widget or an Element.
   *
   * @throws UnableToCompleteException If no such widget class exists
   * @throws RuntimeException if asked to handle a non-widget, non-DOM element
   */
  public JClassType findFieldType(XMLElement elem)
      throws UnableToCompleteException {
    String tagName = elem.getLocalName();

    if (!isWidgetElement(elem)) {
      return findGwtDomElementTypeForTag(tagName);
    }

    String ns = elem.getNamespaceUri();

    JPackage pkg = parseNamespacePackage(ns);
    if (pkg == null) {
      throw new RuntimeException("No such package: " + ns);
    }

    JClassType rtn = null;
    if (pkg != null) {
      rtn = pkg.findType(tagName);
      if (rtn == null) {
        die("No class matching \"%s\" in %s", tagName, ns);
      }
    }

    return rtn;
  }

  /**
   * Generates the code to set a property value (assumes that 'value' is a valid
   * Java expression).
   */
  public void genPropertySet(String fieldName, String propName, String value) {
    addStatement("%1$s.set%2$s(%3$s);", fieldName,
        capitalizePropName(propName), value);
  }

  /**
   * Generates the code to set a string property.
   */
  public void genStringPropertySet(String fieldName, String propName,
      String value) {
    genPropertySet(fieldName, propName, "\"" + value + "\"");
  }

  /**
   * Find and return an appropriate attribute parser for a set of parameters, or
   * return null.
   */
  public AttributeParser getAttributeParser(JParameter... params) {
    String paramTypeNames = getParametersKey(params);
    String parserClassName = attributeParsers.get(paramTypeNames);

    if (parserClassName != null) {
      return getAttributeParserByClassName(parserClassName);
    }

    if (params.length == 1) {
      return new StrictAttributeParser();
    }

    return null;
  }

  /**
   * Find and return an appropriate attribute parser for an attribute and set of
   * parameters, or return null.
   * <p>
   * If params is of size one, a parser of some kind is guaranteed to be
   * returned.
   */
  public AttributeParser getAttributeParser(XMLAttribute attribute,
      JParameter... params) throws UnableToCompleteException {
    AttributeParser parser = getBundleAttributeParser(attribute);
    if (parser == null) {
      parser = getAttributeParser(params);
    }
    return parser;
  }

  /**
   * Finds an attribute {@link BundleAttributeParser} for the given xml
   * attribute, if any, based on its namespace uri.
   *
   * @return the parser or null
   * @deprecated exists only to support {@link BundleAttributeParser}, which
   *             will be leaving us soon.
   */
  @Deprecated
  public BundleAttributeParser getBundleAttributeParser(XMLAttribute attribute)
      throws UnableToCompleteException {
    if (attribute.getNamespaceUri() == null) {
      return null;
    }

    String attributePrefixUri = attribute.getNamespaceUri();
    if (!attributePrefixUri.startsWith(BUNDLE_URI_SCHEME)) {
      return null;
    }

    String bundleClassName = attributePrefixUri.substring(BUNDLE_URI_SCHEME.length());
    BundleAttributeParser parser = bundleParsers.get(bundleClassName);
    if (parser == null) {
      JClassType bundleClassType = getOracle().findType(bundleClassName);
      if (bundleClassType == null) {
        die("No such resource class: " + bundleClassName);
      }
      parser = createBundleParser(bundleClassType, attribute);
      bundleParsers.put(bundleClassName, parser);
    }

    return parser;
  }

  public ImplicitClientBundle getBundleClass() {
    return bundleClass;
  }

  /**
   * @return The logger, at least until we get get it handed off to parsers via
   *         constructor args.
   */
  public MortalLogger getLogger() {
    return logger;
  }

  /**
   * Get the {@link MessagesWriter} for this UI, generating it if necessary.
   */
  public MessagesWriter getMessages() {
    return messages;
  }

  /**
   * Gets the type oracle.
   */
  public TypeOracle getOracle() {
    return oracle;
  }

  public OwnerClass getOwnerClass() {
    return ownerClass;
  }

  public String getUiFieldAttributeName() {
    return gwtPrefix + ":field";
  }

  public boolean isBinderElement(XMLElement elem) {
    String uri = elem.getNamespaceUri();
    return uri != null && BINDER_URI.equals(uri);
  }

  public boolean isWidgetElement(XMLElement elem) {
    String uri = elem.getNamespaceUri();
    return uri != null && uri.startsWith(PACKAGE_URI_SCHEME);
  }

  /**
   * Parses the object associated with the specified element, and returns the
   * name of the field (possibly private) that will hold it. The element is
   * likely to make recursive calls back to this method to have its children
   * parsed.
   *
   * @param elem the xml element to be parsed
   * @return the name of the field containing the parsed widget
   */
  public String parseElementToField(XMLElement elem)
      throws UnableToCompleteException {
    if (elementParsers.isEmpty()) {
      registerParsers();
    }

    // Get the class associated with this element.
    JClassType type = findFieldType(elem);

    // Declare its field.
    String fieldName = declareField(type.getQualifiedSourceName(), elem);

    FieldWriter field = fieldManager.lookup(fieldName);

    // Push the field that will hold this widget on top of the parsedFieldStack
    // to ensure that fields registered by its parsers will be noted as
    // dependencies of the new widget. See registerField.
    fieldManager.push(field);

    // Give all the parsers a chance to generate their code.
    for (ElementParser parser : getParsersForClass(type)) {
      parser.parse(elem, fieldName, type, this);
    }
    fieldManager.pop();
    return fieldName;
  }

  /**
   * Gives the writer the initializer to use for this field instead of the
   * default GWT.create call.
   *
   * @throws IllegalStateException if an initializer has already been set
   */
  public void setFieldInitializer(String fieldName, String factoryMethod) {
    fieldManager.lookup(fieldName).setInitializer(factoryMethod);
  }

  /**
   * Instructs the writer to initialize the field with a specific contructor
   * invocaction, instead of the default GWT.create call.
   */
  public void setFieldInitializerAsConstructor(String fieldName,
      JClassType type, String... args) {
    setFieldInitializer(fieldName, formatCode("new %s(%s)",
        type.getQualifiedSourceName(), asCommaSeparatedList(args)));
  }

  /**
   * Returns a string token that can be used in place the given expression
   * inside any string literals. Before the generated code is written, the
   * expression will be stiched back into the generated code in place of the
   * token, surrounded by plus signs. This is useful in strings to be handed to
   * setInnerHTML() and setText() calls, to allow a unique dom id attribute or
   * other runtime expression in the string.
   *
   * @param expression
   */
  public String tokenForExpression(String expression) {
    return tokenator.nextToken(("\" + " + expression + " + \""));
  }

  /**
   * Post a warning message.
   */
  public void warn(String message) {
    logger.warn(message);
  }

  /**
   * Post a warning message.
   */
  public void warn(String message, Object... params) {
    logger.warn(message, params);
  }

  /**
   * Entry point for the code generation logic. It generates the
   * implementation's superstructure, and parses the root widget (leading to all
   * of its children being parsed as well).
   */
  void parseDocument(PrintWriter printWriter) throws UnableToCompleteException {
    Document doc = null;
    try {
      doc = parseXmlResource(templatePath);
    } catch (SAXParseException e) {
      die("Error parsing XML (line " + e.getLineNumber() + "): "
          + e.getMessage(), e);
    }

    JClassType uiBinderClass = getOracle().findType(UiBinder.class.getName());
    if (!baseClass.isAssignableTo(uiBinderClass)) {
      die(baseClass.getName() + " must implement UiBinder");
    }

    Element documentElement = doc.getDocumentElement();
    gwtPrefix = documentElement.lookupPrefix(BINDER_URI);

    XMLElement elem = new XMLElement(documentElement, this);
    this.rendered = tokenator.detokenate(parseDocumentElement(elem));
    printWriter.print(rendered);
  }

  private void addAttributeParser(String signature, String className) {
    attributeParsers.put(signature, className);
  }

  private void addElementParser(String gwtClass, String parser) {
    elementParsers.put(gwtClass, parser);
  }

  private void addWidgetParser(String className) {
    String gwtClass = "com.google.gwt.user.client.ui." + className;
    String parser = "com.google.gwt.uibinder.parsers." + className + "Parser";
    addElementParser(gwtClass, parser);
  }

  /**
   * Creates a parser for the given bundle class. This method will die soon.
   */
  private BundleAttributeParser createBundleParser(JClassType bundleClass,
      XMLAttribute attribute) throws UnableToCompleteException {

    final String templateResourceName = attribute.getName().split(":")[0];
    warn("The %1$s mechanism is deprecated. Instead, declare the following "
        + "%2$s:with element as a child of your %2$s:UiBinder element: "
        + "<%2$s:with field='%3$s' type='%4$s.%5$s' />", BUNDLE_URI_SCHEME,
        gwtPrefix, templateResourceName, bundleClass.getPackage().getName(),
        bundleClass.getName());

    // Try to find any bundle instance created with UiField.
    OwnerField field = getOwnerClass().getUiFieldForType(bundleClass);
    if (field != null) {
      if (!templateResourceName.equals(field.getName())) {
        die("Template %s has no \"xmlns:%s='urn:with:%s'\" for %s.%s#%s",
            templatePath, field.getName(),
            bundleClass.getQualifiedSourceName(),
            uiOwnerType.getPackage().getName(), uiOwnerType.getName(),
            field.getName());
      }

      if (field.isProvided()) {
        return new BundleAttributeParser(bundleClass, "owner."
            + field.getName(), false);
      }
    }

    // Try to find any bundle instance created with @UiFactory.
    JMethod method = getOwnerClass().getUiFactoryMethod(bundleClass);
    if (method != null) {
      return new BundleAttributeParser(bundleClass, "owner." + method.getName()
          + "()", false);
    }

    return new BundleAttributeParser(bundleClass, "my"
        + bundleClass.getName().replace('.', '_') + "Instance", true);
  }

  /**
   * Outputs a bundle resource for a given bundle attribute parser.
   */
  private String declareStaticField(BundleAttributeParser parser) {
    if (!parser.isBundleStatic()) {
      return null;
    }

    String fullBundleClassName = parser.fullBundleClassName();

    StringBuilder b = new StringBuilder();
    b.append("static ").append(fullBundleClassName).append(" ").append(
        parser.bundleInstance()).append(" = GWT.create(").append(
        fullBundleClassName).append(".class);");

    return b.toString();
  }

  /**
   * Ensures that all of the internal data structures are cleaned up correctly
   * at the end of parsing the document.
   *
   * @throws UnableToCompleteException
   */
  private void ensureAttachmentCleanedUp() throws UnableToCompleteException {
    if (!attachSectionElements.isEmpty()) {
      throw new IllegalStateException("Attachments not cleaned up: "
          + attachSectionElements);
    }
    if (!detachStatementsStack.isEmpty()) {
      throw new IllegalStateException("Detach not cleaned up: "
          + detachStatementsStack);
    }
  }

  /**
   * Given a DOM tag name, return the corresponding
   * {@link com.google.gwt.dom.client.Element} subclass.
   */
  private JClassType findGwtDomElementTypeForTag(String tag) {
    JClassType elementClass = oracle.findType("com.google.gwt.dom.client.Element");
    JClassType[] types = elementClass.getSubtypes();
    for (JClassType type : types) {
      TagName annotation = type.getAnnotation(TagName.class);
      if (annotation != null) {
        for (String annotationTag : annotation.value()) {
          if (annotationTag.equals(tag)) {
            return type;
          }
        }
      }
    }

    return elementClass;
  }

  /**
   * Use this method to format code. It forces the use of the en-US locale,
   * so that things like decimal format don't get mangled.
   */
  private String formatCode(String format, Object... params) {
    String r = String.format(Locale.US, format, params);
    return r;
  }

  /**
   * Inspects this element for a gwt:field attribute. If one is found, the
   * attribute is consumed and its value returned.
   *
   * @return The field name declared by an element, or null if none is declared
   */
  private String getFieldName(XMLElement elem) throws UnableToCompleteException {
    String fieldName = null;
    boolean hasOldSchoolId = false;
    if (elem.hasAttribute("id") && isWidgetElement(elem)) {
      hasOldSchoolId = true;
      // If an id is specified on the element, use that.
      fieldName = elem.consumeAttribute("id");
      warn("Deprecated use of id=\"%1$s\" for field name. "
          + "Please switch to gwt:field=\"%1$s\" instead. "
          + "This will soon be a compile error!", fieldName);
    }
    if (elem.hasAttribute(getUiFieldAttributeName())) {
      if (hasOldSchoolId) {
        die("Cannot declare both id and field on the same element: " + elem);
      }
      fieldName = elem.consumeAttribute(getUiFieldAttributeName());
    }
    return fieldName;
  }

  /**
   * Given a parameter array, return a key for the attributeParsers table.
   */
  private String getParametersKey(JParameter[] params) {
    String paramTypeNames = "";
    for (int i = 0; i < params.length; ++i) {
      paramTypeNames += params[i].getType().getParameterizedQualifiedSourceName();
      if (i != params.length - 1) {
        paramTypeNames += ",";
      }
    }
    return paramTypeNames;
  }

  private Class<? extends ElementParser> getParserForClass(JClassType uiClass) {
    // Find the associated parser.
    String uiClassName = uiClass.getQualifiedSourceName();
    String parserClassName = elementParsers.get(uiClassName);
    if (parserClassName == null) {
      return null;
    }

    // And instantiate it.
    try {
      return Class.forName(parserClassName).asSubclass(ElementParser.class);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException("Unable to instantiate parser", e);
    } catch (ClassCastException e) {
      throw new RuntimeException(parserClassName + " must extend ElementParser");
    }
  }

  /**
   * Find a set of element parsers for the given ui type.
   *
   * The list of parsers will be returned in order from most- to least-specific.
   */
  private Iterable<ElementParser> getParsersForClass(JClassType type) {
    List<ElementParser> parsers = new ArrayList<ElementParser>();

    /*
     * Let this non-widget parser go first (it finds <m:attribute/> elements).
     * Any other such should land here too.
     *
     * TODO(rjrjr) Need a scheme to associate these with a namespace uri or
     * something?
     */
    parsers.add(new AttributeMessageParser());

    for (JClassType curType : getClassHierarchyBreadthFirst(type)) {
      try {
        Class<? extends ElementParser> cls = getParserForClass(curType);
        if (cls != null) {
          ElementParser parser = cls.newInstance();
          parsers.add(parser);
        }
      } catch (InstantiationException e) {
        throw new RuntimeException(
            "Unable to instantiate " + curType.getName(), e);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(
            "Unable to instantiate " + curType.getName(), e);
      }
    }

    parsers.add(new BeanParser());

    return parsers;
  }

  /**
   * Writes a field setter if the field is not provided and the field class is
   * compatible with its respective template field.
   */
  private void maybeWriteFieldSetter(IndentedWriter niceWriter,
      OwnerField ownerField, JClassType templateClass, String templateField)
      throws UnableToCompleteException {
    JClassType fieldType = ownerField.getType().getRawType();

    if (!templateClass.isAssignableTo(fieldType)) {
      die("Template field and owner field types don't match: %s != %s",
          templateClass.getQualifiedSourceName(),
          fieldType.getQualifiedSourceName());
    }

    if (!ownerField.isProvided()) {
      niceWriter.write("owner.%1$s = %2$s;", ownerField.getName(),
          templateField);
    }
  }

  private String normalizeFieldName(String fieldName) {
    // If a field name has a '.' in it, replace it with '$' to make it a legal
    // identifier. This can happen with the field names associated with nested
    // classes.
    return fieldName.replace('.', '$');
  }

  /**
   * Parse the document element and return the source of the Java class that
   * will implement its UiBinder.
   */
  private String parseDocumentElement(XMLElement elem)
      throws UnableToCompleteException {
    fieldManager.registerFieldOfGeneratedType(bundleClass.getPackageName(),
        bundleClass.getClassName(), bundleClass.getFieldName());
    // Allow GWT.create() to init the field, the default behavior

    String rootField = new UiBinderParser(this, messages, fieldManager, oracle,
        bundleClass).parse(elem);

    StringWriter stringWriter = new StringWriter();
    IndentedWriter niceWriter = new IndentedWriter(
        new PrintWriter(stringWriter));
    writeBinder(niceWriter, rootField);

    ensureAttachmentCleanedUp();
    return stringWriter.toString();
  }

  /**
   * Parses a package uri (i.e. package://com.google...).
   *
   * @throws UnableToCompleteException on bad package name
   */
  private JPackage parseNamespacePackage(String ns)
      throws UnableToCompleteException {
    if (ns.startsWith(PACKAGE_URI_SCHEME)) {
      String pkgName = ns.substring(PACKAGE_URI_SCHEME.length());

      JPackage pkg = oracle.findPackage(pkgName);
      if (pkg == null) {
        die("Package not found: " + pkgName);
      }

      return pkg;
    }

    return null;
  }

  private Document parseXmlResource(final String resourcePath)
      throws SAXParseException, UnableToCompleteException {
    // Get the document builder. We need namespaces, and automatic expanding
    // of entity references (the latter of which makes life somewhat easier
    // for XMLElement).
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setExpandEntityReferences(true);
    DocumentBuilder builder;
    try {
      builder = factory.newDocumentBuilder();
    } catch (ParserConfigurationException e) {
      throw new RuntimeException(e);
    }

    try {
      ClassLoader classLoader = UiBinderGenerator.class.getClassLoader();
      URL url = classLoader.getResource(resourcePath);
      if (null == url) {
        die("Unable to find resource: " + resourcePath);
      }

      InputStream stream = url.openStream();
      InputSource input = new InputSource(stream);
      input.setSystemId(url.toExternalForm());

      builder.setEntityResolver(new GwtResourceEntityResolver());

      return builder.parse(input);
    } catch (SAXParseException e) {
      // Let SAXParseExceptions through.
      throw e;
    } catch (SAXException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void registerParsers() {
    // TODO(rjrjr): Allow third-party parsers to register themselves
    // automagically, according to http://b/issue?id=1867118

    addElementParser("com.google.gwt.dom.client.Element",
        "com.google.gwt.uibinder.parsers.DomElementParser");

    // Register widget parsers.
    addWidgetParser("UIObject");
    addWidgetParser("HasText");
    addWidgetParser("HasHTML");
    addWidgetParser("HasWidgets");
    addWidgetParser("HTMLPanel");
    addWidgetParser("DockPanel");
    addWidgetParser("StackPanel");
    addWidgetParser("DisclosurePanel");
    addWidgetParser("TabPanel");
    addWidgetParser("MenuItem");
    addWidgetParser("MenuBar");
    addWidgetParser("RadioButton");
    addWidgetParser("CellPanel");
    addWidgetParser("CustomButton");
    addWidgetParser("DockLayoutPanel");
    addWidgetParser("StackLayoutPanel");

    addAttributeParser("boolean",
        "com.google.gwt.uibinder.parsers.BooleanAttributeParser");

    addAttributeParser("java.lang.String",
        "com.google.gwt.uibinder.parsers.StringAttributeParser");

    addAttributeParser("int", "com.google.gwt.uibinder.parsers.IntParser");

    addAttributeParser("int,int",
        "com.google.gwt.uibinder.parsers.IntPairParser");

    addAttributeParser("com.google.gwt.user.client.ui.HasHorizontalAlignment."
        + "HorizontalAlignmentConstant",
        "com.google.gwt.uibinder.parsers.HorizontalAlignmentConstantParser");
  }

  /**
   * Write statements that parsers created via calls to {@link #addStatement}.
   * Such statements will assume that {@link #writeGwtFields} has already been
   * called.
   */
  private void writeAddedStatements(IndentedWriter niceWriter) {
    for (String s : statements) {
      niceWriter.write(s);
    }
  }

  /**
   * Writes the UiBinder's source.
   */
  private void writeBinder(IndentedWriter w, String rootField)
      throws UnableToCompleteException {
    writePackage(w);

    writeImports(w);
    w.newline();

    writeClassOpen(w);
    writeStatics(w);
    w.newline();

    // createAndBindUi method
    w.write("public %s createAndBindUi(final %s owner) {",
        uiRootType.getName(), uiOwnerType.getName());
    w.indent();
    w.newline();

    writeGwtFields(w);
    w.newline();

    writeAddedStatements(w);
    w.newline();

    writeInitStatements(w);
    w.newline();

    writeHandlers(w);
    w.newline();

    writeOwnerFieldSetters(w);

    writeCssInjectors(w);

    w.write("return %s;", rootField);
    w.outdent();
    w.write("}");

    // Close class
    w.outdent();
    w.write("}");
  }

  private void writeClassOpen(IndentedWriter w) {
    w.write("public class %s extends AbstractUiBinder<%s, %s> implements %s {",
        implClassName, uiRootType.getName(), uiOwnerType.getName(),
        baseClass.getName());
    w.indent();
  }

  private void writeCssInjectors(IndentedWriter w) {
    for (ImplicitCssResource css : bundleClass.getCssMethods()) {
      w.write("ensureCssInjected(%s.%s());", bundleClass.getFieldName(),
          css.getName());
    }
    w.newline();
  }

  /**
   * Write declarations for variables or fields to hold elements declared with
   * gwt:field in the template. For those that have not had constructor
   * generation suppressed, emit GWT.create() calls instantiating them (or die
   * if they have no default constructor).
   *
   * @throws UnableToCompleteException on constructor problem
   */
  private void writeGwtFields(IndentedWriter niceWriter)
      throws UnableToCompleteException {
    // For each provided field in the owner class, initialize from the owner
    Collection<OwnerField> ownerFields = getOwnerClass().getUiFields();
    for (OwnerField ownerField : ownerFields) {
      if (ownerField.isProvided()) {
        String fieldName = ownerField.getName();
        FieldWriter fieldWriter = fieldManager.lookup(fieldName);

        // TODO(hermes) This can be null due to http://b/1836504. If that
        // is fixed properly, a null fieldWriter will be an error
        // (would that be a user error or a runtime error? Not sure)
        if (fieldWriter != null) {
          fieldManager.lookup(fieldName).setInitializerMaybe(
              formatCode("owner.%1$s", fieldName));
        }
      }
    }

    // Write gwt field declarations.
    fieldManager.writeGwtFieldsDeclaration(niceWriter, uiOwnerType.getName());
  }

  private void writeHandlers(IndentedWriter w) throws UnableToCompleteException {
    handlerEvaluator.run(w, fieldManager, "owner");
  }

  private void writeImports(IndentedWriter w) {
    w.write("import com.google.gwt.core.client.GWT;");
    w.write("import com.google.gwt.uibinder.client.AbstractUiBinder;");
    w.write("import com.google.gwt.uibinder.client.UiBinderUtil;");
    w.write("import %s.%s;", uiRootType.getPackage().getName(),
        uiRootType.getName());
  }

  /**
   * Write statements created by {@link #addInitStatement}. This code must be
   * placed after all instantiation code.
   */
  private void writeInitStatements(IndentedWriter niceWriter) {
    for (String s : initStatements) {
      niceWriter.write(s);
    }
  }

  /**
   * Write the statements to fill in the fields of the UI owner.
   */
  private void writeOwnerFieldSetters(IndentedWriter niceWriter)
      throws UnableToCompleteException {
    for (OwnerField ownerField : getOwnerClass().getUiFields()) {
      String fieldName = ownerField.getName();
      FieldWriter fieldWriter = fieldManager.lookup(fieldName);

      BundleAttributeParser bundleParser = bundleParsers.get(ownerField.getType().getRawType().getQualifiedSourceName());

      if (bundleParser != null) {
        // ownerField is a bundle resource.
        maybeWriteFieldSetter(niceWriter, ownerField,
            bundleParser.bundleClass(), bundleParser.bundleInstance());

      } else if (fieldWriter != null) {
        // ownerField is a widget.
        JClassType type = fieldWriter.getType();
        if (type != null) {
          maybeWriteFieldSetter(niceWriter, ownerField, fieldWriter.getType(),
              fieldName);
        } else {
          // Must be a generated type
          if (!ownerField.isProvided()) {
            niceWriter.write("owner.%1$s = %1$s;", fieldName);
          }
        }

      } else {
        // ownerField was not found as bundle resource or widget, must die.
        die("Template %s has no %s attribute for %s.%s#%s", templatePath,
            getUiFieldAttributeName(), uiOwnerType.getPackage().getName(),
            uiOwnerType.getName(), fieldName);
      }
    }
  }

  private void writePackage(IndentedWriter w) {
    String packageName = baseClass.getPackage().getName();
    if (packageName.length() > 0) {
      w.write("package %1$s;", packageName);
      w.newline();
    }
  }

  /**
   * Generates instances of any bundle classes that have been referenced by a
   * namespace entry in the top level element. This must be called *after* all
   * parsing is through, as the bundle list is generated lazily as dom elements
   * are parsed.
   */
  private void writeStaticBundleInstances(IndentedWriter niceWriter) {
    // TODO(rjrjr) It seems bad that this method has special
    // knowledge of BundleAttributeParser, but that'll die soon so...
    for (String key : bundleParsers.keySet()) {
      String declaration = declareStaticField(bundleParsers.get(key));
      if (declaration != null) {
        niceWriter.write(declaration);
      }
    }
  }

  private void writeStaticMessagesInstance(IndentedWriter niceWriter) {
    if (messages.hasMessages()) {
      niceWriter.write(messages.getDeclaration());
    }
  }

  private void writeStatics(IndentedWriter w) {
    writeStaticMessagesInstance(w);
    writeStaticBundleInstances(w);
  }
}
