/**
 * (c) Copyright [2015-2018] Micro Focus or one of its affiliates.
 */
package com.fortify.sample.bugtracker.bugzilla;

import com.fortify.pub.bugtracker.plugin.AbstractBatchBugTrackerPlugin;
import com.fortify.pub.bugtracker.plugin.BatchBugTrackerPlugin;
import com.fortify.pub.bugtracker.plugin.BugTrackerPluginImplementation;
import com.fortify.pub.bugtracker.support.Bug;
import com.fortify.pub.bugtracker.support.BugParam;
import com.fortify.pub.bugtracker.support.BugParamChoice;
import com.fortify.pub.bugtracker.support.BugParamText;
import com.fortify.pub.bugtracker.support.BugParamTextArea;
import com.fortify.pub.bugtracker.support.BugSubmission;
import com.fortify.pub.bugtracker.support.BugTrackerAuthenticationException;
import com.fortify.pub.bugtracker.support.BugTrackerConfig;
import com.fortify.pub.bugtracker.support.BugTrackerException;
import com.fortify.pub.bugtracker.support.IssueDetail;
import com.fortify.pub.bugtracker.support.MultiIssueBugSubmission;
import com.fortify.pub.bugtracker.support.UserAuthenticationStore;

import com.j2bugzilla.base.BugFactory;
import com.j2bugzilla.base.BugzillaConnector;
import com.j2bugzilla.base.BugzillaException;
import com.j2bugzilla.base.BugzillaMethod;
import com.j2bugzilla.base.BugzillaTransportException;
import com.j2bugzilla.base.ConnectionException;
import com.j2bugzilla.base.Product;
import com.j2bugzilla.rpc.BugzillaVersion;
import com.j2bugzilla.rpc.CommentBug;
import com.j2bugzilla.rpc.GetAccessibleProducts;
import com.j2bugzilla.rpc.GetBug;
import com.j2bugzilla.rpc.GetLegalValues;
import com.j2bugzilla.rpc.GetProduct;
import com.j2bugzilla.rpc.LogIn;
import com.j2bugzilla.rpc.ReportBug;
import com.j2bugzilla.rpc.UpdateBug;
import org.apache.commons.lang.StringUtils;

import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.fortify.pub.bugtracker.support.BugTrackerPluginConstants.DISPLAY_ONLY_SUPPORTED_VERSION;
import static com.fortify.sample.bugtracker.bugzilla.BugzillaPluginConstants.*;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;

/**
 * Plugin is used for submitting bugs to the Bugzilla version 4.0 and higher.
 * Bugzilla web services are used for communicating with bug tracker.
 * <P>
 * Plugin uses modified j2bugzilla library (2.3.1-SNAPSHOT)
 * from https://github.com/TomRK1089/j2bugzilla/commit/07aaff6dfa3d99e8cea2e8e800c813ffee2eb49a
 * Note: Library j2bugzilla uses Apache XML-RPC client for communication with the corresponding Bugzilla RPC API
 *       and XML-RPC client uses for transport java.net.HttpURLConnection
 * <P>
 * Plugin accepts predefined proxy parameters if they are sent and configured from SSC, and uses a corresponding proxy for http/https requests.
 * <P>
 * A new instance of BugzillaConnector for further processing is returned from
 * {@link com.fortify.sample.bugtracker.bugzilla.Bugzilla4BugTrackerPlugin#connectToBugzilla(com.fortify.pub.bugtracker.support.UserAuthenticationStore)} method.
 * This instance must be used as a client for all other subsequent http requests during the whole top-level plugin API method processing.
 *
 * @author Evgeniy Semionov (evgeny.semionov@hp.com)
 * @version 1.0 06/10/2013
 */
@BugTrackerPluginImplementation
public class Bugzilla4BugTrackerPlugin extends AbstractBatchBugTrackerPlugin implements BatchBugTrackerPlugin {

	private URL bugzillaURL;	        // URL of the Bugzilla server.
	private String bugzillaProtocol;	// Protocol of the Bugzilla server
	private Map<String, String> config; // Full Bugzilla plugin configuration (including an optional proxy)

	/**
	 * List of high level Bugzilla API operation and associated minimum bugzilla version that has such feature.
	 */
	private enum ApiOperation {
		GET_PRIORITIES(3.6),
		GET_PRODUCTS(3.6),
		GET_COMPONENTS(3.6),
		GET_VERSIONS(3.6);

		/**
		 * Minimum Bugzilla version that has such feature.
		 */
		private double minBugzillaVersion;

		/**
		 * Create enum object and init minimum Bugzilla version that has such feature.
		 * @param minBugzillaVersion minimum Bugzilla version that has such feature
		 */
        ApiOperation(double minBugzillaVersion) {
			this.minBugzillaVersion = minBugzillaVersion;
		}

		private double getMinBugzillaVersion() {
			return minBugzillaVersion;
		}
	}

	/**
	 * Predefined list of priorities that can be used if it's impossible to query this list from Bugzilla.
	 */
	private static final String[] PREDEFINED_PRIORITIES = new String[]{"P1", "P2", "P3", "P4", "P5"};


	/**
	 * Retrieve list of the parameters that should be filled bu user for bug submission.
	 *
	 * @param credentials bug tracker credentials supplied by the user
	 * @return list of the parameters that should be filled bu user for bug submission.
	 */
	@Override
	public List<BugParam> getBatchBugParameters(UserAuthenticationStore credentials) {
		return getBugParameters(null, credentials);
	}

	/**
	 * Retrieve list of the parameters that should be filled bu user for bug submission.
	 *
	 * @param issueDetail
	 *            an object encompassing the details of the issue for which the
	 *            user is attempting to log a bug. Defaults to various bug
	 *            parameters like description, summary can be extracted from
	 *            this object.
	 * @param credentials bug tracker credentials supplied by the user
	 *
	 * @return list of the parameters that should be filled bu user for bug submission.
	 */
	@Override
	public List<BugParam> getBugParameters(IssueDetail issueDetail, UserAuthenticationStore credentials) {

		try {
            final BugzillaConnector conn = connectToBugzilla(credentials);
            final double bugzillaVersion = getBugzillaVersion(conn);

            final BugParam summaryParam = getSummaryParamText(issueDetail);
            final BugParam descriptionParam = getDescriptionParamText(issueDetail);

            final BugParam productParam;
            final BugParam componentParam;
            final BugParam versionParam;

			if (canUseBugzillaApi(ApiOperation.GET_PRODUCTS, bugzillaVersion)) {
				final List<String> products = getProductsFromBugzilla(conn);
				productParam = getProductParamChoice(products);
				componentParam = getComponentParamChoice(new ArrayList());
				versionParam = getVersionParamChoice(new ArrayList());
			} else {
				productParam = getProductParamText();
				componentParam = getComponentParamText();
				versionParam = getVersionParamText();
			}
			final List<String> priorities = getValidPriorityList(conn, bugzillaVersion);
			final BugParam severityParam = getSeverityParamChoice(priorities);

			return Arrays.asList(summaryParam, descriptionParam, productParam, componentParam, versionParam, severityParam);

		} catch (BugTrackerException e) {
			throw e;
		} catch (Exception e) {
			throw new BugTrackerException("Error while setting Bugzilla bug fields configuration: " + e.getMessage(), e);
		}
	}

	private BugParam getSummaryParamText(final IssueDetail issueDetail) {
		final BugParam summaryParam = new BugParamText()
				.setIdentifier(SUMMARY_PARAM_NAME)
				.setDisplayLabel("Bug Summary")
				.setRequired(true)
				.setDescription("Title of the bug to be logged");
		if (issueDetail == null) {
			summaryParam.setValue("Fix $ATTRIBUTE_CATEGORY$ in $ATTRIBUTE_FILE$");
		} else {
			summaryParam.setValue(issueDetail.getSummary());
		}
		return summaryParam;
	}

	private BugParam getDescriptionParamText(final IssueDetail issueDetail) {
		final BugParam descriptionParam = new BugParamTextArea()
				.setIdentifier(DESCRIPTION_PARAM_NAME)
				.setDisplayLabel("Bug Description")
				.setRequired(true);
		if (issueDetail == null) {
			descriptionParam.setValue("Issue Ids: $ATTRIBUTE_INSTANCE_ID$\n$ISSUE_DEEPLINK$");
		} else {
			descriptionParam.setValue(pluginHelper.buildDefaultBugDescription(issueDetail, true));
		}
		return descriptionParam;
	}

	/**
	 * Return list of the products defined in Bugzilla.
	 * ATTENTION: This method works very slow if there are many products in Bugzilla. The main problem here is that we cannot load all products at once,
	 * bug have to query for each product separately.
	 *
	 * @param connector BugzillaConnector object.
	 * @return list of the product names defined in Bugzilla.
	 */
	private List<String> getProductsFromBugzilla(BugzillaConnector connector) {
		final List<String> result = new LinkedList<>();
		try {
			final GetAccessibleProducts productRequest = new GetAccessibleProducts();
			executeMethod(connector, productRequest);
			final int[] productIds = productRequest.getProductIDs();
			if (productIds != null) {
				final GetProduct getProduct = new GetProduct(productIds);
				executeMethod(connector, getProduct);
				for (final Product product : getProduct.getProducts()) {
					result.add(product.getName());
				}
			}
		} catch (BugzillaException e) {
			throw new BugTrackerException("Cannot obtain the list of products from Bugzilla server " + bugzillaURL, e);
		}
		return result;
	}

	private BugParam getProductParamChoice(List<String> products) {
		return new BugParamChoice()
				.setChoiceList(products)
				.setHasDependentParams(true)
				.setIdentifier(PRODUCT_PARAM_NAME)
				.setDisplayLabel(PRODUCT_LABEL)
				.setRequired(true)
				.setDescription(PRODUCT_DESCRIPTION);
	}

	private BugParam getComponentParamChoice(List<String> components) {
		return new BugParamChoice()
			.setChoiceList(components)
			.setHasDependentParams(true)
			.setIdentifier(COMPONENT_PARAM_NAME)
			.setDisplayLabel(COMPONENT_LABEL)
			.setRequired(true)
			.setDescription(COMPONENT_DESCRIPTION);
	}

	private BugParam getVersionParamChoice(List<String> versions) {
		return new BugParamChoice()
			.setChoiceList(versions)
			.setHasDependentParams(false)
			.setIdentifier(VERSION_PARAM_NAME)
			.setDisplayLabel(VERSION_LABEL)
			.setRequired(true)
			.setDescription(VERSION_DESCRIPTION);
	}

	private BugParam getProductParamText() {
		return new BugParamText()
			.setIdentifier(PRODUCT_PARAM_NAME)
			.setDisplayLabel(PRODUCT_LABEL)
			.setRequired(true)
			.setDescription(PRODUCT_DESCRIPTION);
	}

	private BugParam getComponentParamText() {
		return new BugParamText()
			.setIdentifier(COMPONENT_PARAM_NAME)
			.setDisplayLabel(COMPONENT_LABEL)
			.setRequired(true)
			.setDescription(COMPONENT_DESCRIPTION);
	}

	private BugParam getVersionParamText() {
		return new BugParamText()
			.setIdentifier(VERSION_PARAM_NAME)
			.setDisplayLabel("Version")
			.setRequired(true)
			.setDescription("Version against which bug needs to be logged");
	}

	/**
	 * Return list of all possible bug priorities. Application load this list from Bugzilla only if bugzilla version is higher than 3.6.
	 * Otherwise predefined list of priories is returned.
	 *
	 * @param connector BugzillaConnector  object.
	 * @return list of all possible bug priorities.
	 */
	private List<String> getValidPriorityList(BugzillaConnector connector, double bugzillaVer) {

		GetLegalValues get = new GetLegalValues(GetLegalValues.Fields.PRIORITY);
		try {
			if (canUseBugzillaApi(ApiOperation.GET_PRIORITIES, bugzillaVer)) {
				return getSortedPriorities(executeGetLegalValues(connector, get));
			} else {
				return Arrays.asList(PREDEFINED_PRIORITIES);
			}
		} catch (BugzillaException e) {
			throw new BugTrackerException("Cannot obtain the list of valid priories from Bugzilla server " + bugzillaURL, e);
		}
	}

	/* It is not possible to obtain the sortLevel dynamically from Bugzilla xml api
	 * but for now handle the default set of priorities a bit better.
	 * Assign the sortLevels of known priority values and assign any unknown priorities to default sortLevel
	 * and then sort the list */
	private List<String> getSortedPriorities(final List<String> prioritiesList) {
		return prioritiesList.stream()
				.map( s-> KNOWN_PRIORITIES.getOrDefault(s.toLowerCase(), new PriorityWithSortLevel(s)) )
				.sorted(Comparator.comparingInt(PriorityWithSortLevel::getSortLevel))
				.map(PriorityWithSortLevel::getPriority)
				.collect(collectingAndThen(toList(), Collections::unmodifiableList));
	}
	
	private boolean canUseBugzillaApi(ApiOperation operation, double bugzillaVer) {
		return bugzillaVer >= operation.getMinBugzillaVersion();
	}

	private BugParam getSeverityParamChoice(List<String> priorities) {
		return new BugParamChoice()
					.setChoiceList(priorities)
					.setHasDependentParams(false)
					.setIdentifier(PRIORITY_PARAM_NAME)
					.setDisplayLabel("Priority")
					.setRequired(true)
					.setDescription("Bug Priority");
	}

	@Override
	public List<BugParam> onBatchBugParameterChange(String changedParamIdentifier, List<BugParam> currentValues, UserAuthenticationStore credentials) {
        return innerOnParameterChange(changedParamIdentifier, currentValues, credentials);
	}

	@Override
	public Bug fileMultiIssueBug(MultiIssueBugSubmission bug, UserAuthenticationStore credentials)
			throws BugTrackerException {

		final BugzillaConnector connector = connectToBugzilla(credentials);
		return fileBugInternal(connector, getPostDataStrMap(bug.getParams()));
	}

	@Override
	public Bug fileBug(BugSubmission bug, UserAuthenticationStore credentials)
			throws BugTrackerException {

		final BugzillaConnector connector = connectToBugzilla(credentials);
		return fileBugInternal(connector, getPostDataStrMap(bug.getParams()));
	}

	private Bug fileBugInternal(final BugzillaConnector connector, final Map<String, Object> bugParams) {
		BugFactory factory = new BugFactory();
		com.j2bugzilla.base.Bug dtoBug = factory.createBug(bugParams);
		final ReportBug reportBug = new ReportBug(dtoBug);
		try {
			executeMethod(connector, reportBug);
			return new Bug(String.valueOf(reportBug.getID()), STATUS_NEW);
		} catch (BugzillaException e) {
			throw new BugTrackerException(e.getMessage(), e);
		}
	}

	@Override
	public boolean isBugOpen(Bug bug, UserAuthenticationStore credentials) {
		return !isBugClosed(bug, credentials);
	}

	@Override
	public boolean isBugClosed(Bug bug, UserAuthenticationStore credentials) {
		return isBugClosed(bug);
	}

	@Override
	public boolean isBugClosedAndCanReOpen(Bug bug, UserAuthenticationStore credentials) {
		return isBugClosed(bug) && canReOpenBug(bug);
	}

	private boolean isBugClosed(Bug bug) {
		String status = bug.getBugStatus();
		boolean retval = false;
		for (CLOSED_STATUS cs : CLOSED_STATUS.values()){
			if (cs.name().equals(status)) {
				retval = true;
				break;
			}
		}
		return retval;
	}

	@Override
	public void reOpenBug(Bug bug, String comment, UserAuthenticationStore credentials) {
		if (!canReOpenBug(bug)) {
			throw new BugTrackerException("Bug " + bug.getBugId() + " cannot be reopened.");
		}
		final BugzillaConnector connector = connectToBugzilla(credentials);
		try {
			final GetBug getBug = new GetBug(Integer.parseInt(bug.getBugId()));
			executeMethod(connector, getBug);

			com.j2bugzilla.base.Bug dtoBug = getBug.getBug();
			sanitizeEmptyDtoBugFields2(dtoBug);
			dtoBug.setStatus(STATUS_REOPENED);
			dtoBug.clearResolution();

			final UpdateBug reportBug = new UpdateBug(dtoBug);
			executeMethod(connector, reportBug);
			final CommentBug commentBug = new CommentBug(Integer.parseInt(bug.getBugId()), comment);
			executeMethod(connector, commentBug);
		} catch (BugzillaException e) {
			throw new BugTrackerException(e.getMessage(), e);
		}
	}

	/**
	 * This mix of hack and workaround fixes D-127579 "BUGZILLA: error processing with bug state management..."
	 * <p/>Bug properties received from Bugzilla by j2bugzilla can be of a simple Object type (if the property value is empty).
	 * When updating a bug back to Bugzilla (executing a {@link com.j2bugzilla.rpc.UpdateBug} method) these properties
	 * are processed by {@link com.j2bugzilla.rpc.UpdateBug#getParameterMap()} where property getters want
	 * to cast property values to String. But this casting fails if input values are simple Objects as mentioned above.
	 * <P/>
	 * Property values returned by following getters are set to null if they cannot be casted to String in getters:<BR/>
	 *   getAlias()<BR/>
	 *   getSummary()<BR/>
	 *   getProduct()<BR/>
	 *   getComponent()<BR/>
	 *   getVersion()<BR/>
	 *   getStatus()<BR/>
	 *   getResolution()<BR/>
	 *   getOperatingSystem()<BR/>
	 *   getPlatform()<BR/>
	 * <P/>
	 * Properties related to following two getters cannot be sanitized, because their counter part setter does not exist
	 * in j2bugzilla (nevertheless these properties seems to have their String walues always set, so it should not matter):<BR/>
	 *   getSeverity()<BR/>
	 *   getPriority()<BR/>
	 * <P/>
	 * Note: It could theoretically happen that some other j2bugzilla methods might get in to similar problems as well.
	 * But because the reported bug was caused only by UpdateBug method and no other similar problems are known,
	 * the fix has been made only for this specific case.
	 *
	 * @param bug a {@link com.j2bugzilla.base.Bug} object where empty properties are to be sanitized
	 */

	private void sanitizeEmptyDtoBugFields2(com.j2bugzilla.base.Bug bug) {
		sanitizeEmptyBugField(bug::getAlias, bug::setAlias);
		sanitizeEmptyBugField(bug::getProduct, bug::setProduct);
		sanitizeEmptyBugField(bug::getComponent, bug::setComponent);
		sanitizeEmptyBugField(bug::getVersion, bug::setVersion);
		sanitizeEmptyBugField(bug::getStatus, bug::setStatus);
		sanitizeEmptyBugField(bug::getResolution, bug::setResolution);
		sanitizeEmptyBugField(bug::getOperatingSystem, bug::setOperatingSystem);
		sanitizeEmptyBugField(bug::getPlatform, bug::setPlatform);
		sanitizeEmptyBugField(bug::getSummary, bug::setSummary);
	}

	private void sanitizeEmptyBugField(Supplier<String> getterSupplier, Consumer<String> setterConsumer) {
		try {
			getterSupplier.get();
		} catch (ClassCastException cce) {
			setterConsumer.accept(null);
		} catch (Exception  e) {
			throw new BugTrackerException("Error when converting bug properties data from Bugzilla to SSC", e);
		}
	}

	private boolean canReOpenBug(Bug bug) {
		String resolution = bug.getBugResolution();
		boolean retval = true;
		for (NON_REOPENABLE_RESOLUTION rr : NON_REOPENABLE_RESOLUTION.values()){
			if (rr.name().equals(resolution)) {
				retval = false;
				break;
			}
		}
		return retval;
	}

	@Override
	public void addCommentToBug(Bug bug, String comment, UserAuthenticationStore credentials) {

		final BugzillaConnector connector = connectToBugzilla(credentials);
		final CommentBug commentBug = new CommentBug(Integer.parseInt(bug.getBugId()), comment);
		try {
			executeMethod(connector, commentBug);
		} catch (BugzillaException e) {
			throw new BugTrackerException(e.getMessage(), e);
		}
	}

	@Override
	public List<BugTrackerConfig> getConfiguration() {

        final BugTrackerConfig supportedVersions = new BugTrackerConfig()
                .setIdentifier(DISPLAY_ONLY_SUPPORTED_VERSION)
                .setDisplayLabel("Supported Versions")
                .setDescription("Bug Tracker versions supported by the plugin")
                .setValue(SUPPORTED_VERSIONS)
                .setRequired(false);

		BugTrackerConfig bugTrackerConfig = new BugTrackerConfig()
				.setIdentifier(BUGZILLA_URL_NAME)
				.setDisplayLabel("Bugzilla URL Prefix")
				.setDescription("Bugzilla URL prefix")
				.setRequired(true);

		List<BugTrackerConfig> configs = new ArrayList<>(Arrays.asList(supportedVersions, bugTrackerConfig));
		configs.addAll(buildSscProxyConfiguration());
		pluginHelper.populateWithDefaultsIfAvailable(configs);
		return configs;
	}

	private List<BugTrackerConfig> buildSscProxyConfiguration() {
		List<BugTrackerConfig> proxyConfigs = new ArrayList<>();
		for (ProxyField fld : EnumSet.allOf(ProxyField.class)) {
			proxyConfigs.add(new BugTrackerConfig()
					.setIdentifier(fld.getFieldName())
					.setDisplayLabel(fld.getDisplayLabel())
					.setDescription(fld.getDisplayLabel() + " for bug tracker plugin")
					.setRequired(false));
		}
		return proxyConfigs;
	}

	@Override
	public void setConfiguration(Map<String, String> config) {

		this.config = config;

		if (config.get(BUGZILLA_URL_NAME) == null) {
			throw new IllegalArgumentException("Invalid configuration passed");
		}

		String bugzillaUrlPrefix = config.get(BUGZILLA_URL_NAME);
		if (!bugzillaUrlPrefix.startsWith(HTTP_PROTOCOL + "://") && !bugzillaUrlPrefix.startsWith(HTTPS_PROTOCOL + "://")) {
			throw new BugTrackerException(String.format("Bugzilla URL protocol should be either %s or %s", HTTP_PROTOCOL, HTTPS_PROTOCOL));
		}
		if (bugzillaUrlPrefix.endsWith("/")) {
			bugzillaUrlPrefix = bugzillaUrlPrefix.substring(0, bugzillaUrlPrefix.length() - 1);
		}

		try {
			bugzillaURL = new URL(bugzillaUrlPrefix);
			bugzillaURL.toURI();
			if (bugzillaURL.getHost().length() == 0) {
				throw new BugTrackerException("Bugzilla host name cannot be empty.");
			}
			bugzillaProtocol = bugzillaURL.getProtocol();
		} catch (URISyntaxException | MalformedURLException e) {
			throw new BugTrackerException("Invalid Bugzilla URL: " + bugzillaUrlPrefix);
		}
	}

	@Override
	public void testConfiguration(com.fortify.pub.bugtracker.support.UserAuthenticationStore credentials) {
		validateCredentials(credentials);
	}

	@Override
	public void validateCredentials(UserAuthenticationStore credentials) {
		// TODO: stopClock ?
		connectToBugzilla(credentials);
	}

	@Override
	public String getShortDisplayName() {
		return "Bugzilla";
	}

	@Override
	public String getLongDisplayName() {
		return "Bugzilla at " + bugzillaURL;
	}

	@Override
	public boolean requiresAuthentication() {
		return true;
	}

	/**
	 * Method is executed if value of some of the parameters marked as HasDependentParams has been changed.
	 * @param issueDetail
	 *            an object encompassing the details of the issue for which the
	 *            user is attempting to log a bug
	 * @param changedParamIdentifier
	 *            the identifier of BugParam whose value changed on the UI
	 * @param currentValues
	 *            all of the BugParams and their current values
	 * @param credentials
	 *            bug tracker credentials supplied by the user
	 *
	 * @return list of BugParam objects. Current implementation just returns currentValues list.
	 */
	@Override
	public List<BugParam> onParameterChange(IssueDetail issueDetail, String changedParamIdentifier, List<BugParam> currentValues, UserAuthenticationStore credentials) {

        return innerOnParameterChange(changedParamIdentifier, currentValues, credentials);
	}

    private List<BugParam> innerOnParameterChange(
    		String modifiedParamId
			, List<BugParam> bugParams
			, UserAuthenticationStore credentials) {

        if (PRODUCT_PARAM_NAME.equals(modifiedParamId) || COMPONENT_PARAM_NAME.equals(modifiedParamId)) {
            final BugzillaConnector conn = connectToBugzilla(credentials);
			try {
				final double bugzillaVersion = getBugzillaVersion(conn);
				if (canUseBugzillaApi(ApiOperation.GET_COMPONENTS, bugzillaVersion)) {
					final BugParam productParam = pluginHelper.findParam(PRODUCT_PARAM_NAME, bugParams);
					final Product curProduct = new Product(productParam.getValue());

					if (PRODUCT_PARAM_NAME.equals(modifiedParamId)) {
						final BugParamChoice componentParam = (BugParamChoice)pluginHelper.findParam(COMPONENT_PARAM_NAME, bugParams);
						componentParam.setChoiceList(getValidComponentList(conn, curProduct));
						if (canUseBugzillaApi(ApiOperation.GET_VERSIONS, bugzillaVersion)) {
							final BugParamChoice versionsParam = (BugParamChoice)pluginHelper.findParam(VERSION_PARAM_NAME, bugParams);
							versionsParam.setChoiceList(getValidVersionsList(conn, curProduct));
						}
					}
				}
			} catch (BugTrackerException e) {
				throw e;
			} catch (Exception e) {
				throw new BugTrackerException("Error while changing Bugzilla bug fields configuration: " + e.getMessage(), e);
			}
        }
        return bugParams;
    }

    /**
	 * Return numeric representation of the Bugzilla version which URL was passed to plugin.
	 * @param connector BugzillaConnector object to communicate with Bugzilla.
	 * @return numeric version of Bugzilla.
	 */
	private double getBugzillaVersion(BugzillaConnector connector) {
		try {
			final BugzillaVersion versionCheck = new BugzillaVersion();
			executeMethod(connector, versionCheck);
			return stringVersionToDouble(versionCheck.getVersion());
		} catch (BugzillaException e) {
			throw new BugTrackerException("Cannot obtain Bugzilla version from Bugzilla server " + bugzillaURL, e);
		}
	}

	/**
	 * Convert string version representation to the double value.
	 * This function converts strings like this 3.6.7 into numbers like this 3.67.
	 *
	 * @param strVersion string representation of the version.
	 * @return double representation of the version.
	 */
	private static double stringVersionToDouble(String strVersion) {
		final char[] numbers = new char[]{'0','1','2','3','4','5','6','7','8','9'};
		final char[] chars = strVersion.toCharArray();
		final StringBuilder result = new StringBuilder();
		boolean wasPoint = false;
		for (final char curChar : chars) {
			if (Arrays.binarySearch(numbers, curChar) >= 0) {
				result.append(curChar);
			} else {
				if ((curChar == '.') && !wasPoint) {
					result.append(curChar);
					wasPoint = true;
				}
			}
		}
		if (result.length() > 0) {
			return Double.valueOf(result.toString());
		} else {
			return 0;
		}
	}


	private List<String> getValidComponentList(BugzillaConnector connector, Product product) {

		final GetLegalValues get = new GetLegalValues(GetLegalValues.Fields.COMPONENT, product);
		try {
			return executeGetLegalValues(connector, get);
		} catch (BugzillaException e) {
			throw new BugTrackerException("Cannot obtain the list of valid components from Bugzilla server " + bugzillaURL, e);
		}
	}


	private List<String> getValidVersionsList(BugzillaConnector connector, Product product) {

		final GetLegalValues get = new GetLegalValues(GetLegalValues.Fields.VERSION, product);
		try {
			return executeGetLegalValues(connector, get);
		} catch (BugzillaException e) {
			throw new BugTrackerException("Cannot obtain the list of valid versions from Bugzilla server " + bugzillaURL, e);
		}
	}


	private Map<String, Object> getPostDataStrMap(Map<String, String> paramList) {
		final Map<String, Object> result = new HashMap<>();
		for (Map.Entry<String, String> p : paramList.entrySet()) {
			result.put(p.getKey(), p.getValue());
		}
		for (Map.Entry<String,String> param : hiddenBugParams.entrySet()) {
			result.put(param.getKey(), param.getValue());
		}
		result.put(COMMENT, paramList.get(DESCRIPTION_PARAM_NAME));
        String summary = paramList.get(SUMMARY_PARAM_NAME);
        if (summary != null && summary.length() > MAX_SUMMARY_LENGTH) {
            summary = summary.substring(0, MAX_SUMMARY_LENGTH);
        }
		result.put(SUMMARY, summary);
		return result;
	}

	@Override
	public Bug fetchBugDetails(String bugId, UserAuthenticationStore credentials) {
		final BugzillaConnector connector = connectToBugzilla(credentials);
		final GetBug getBug = new GetBug(Integer.parseInt(bugId));
		try {
			executeMethod(connector, getBug);
			com.j2bugzilla.base.Bug dtoBug = getBug.getBug();
			return new Bug(String.valueOf(dtoBug.getID()), dtoBug.getStatus(), dtoBug.getResolution());
		} catch (BugzillaException e) {
			throw new BugTrackerException("The bug status could not be fetched correctly", e);
		}
	}

	private BugzillaConnector connectToBugzilla(final UserAuthenticationStore credentials) {

		UserAuthenticationStore proxyCreds = null;
		Proxy sscProxy = resolveSscProxy(config, bugzillaProtocol);
		if (sscProxy != null) {
			proxyCreds = resolveSscProxyCredentials(config, bugzillaProtocol);
            if ((proxyCreds != null) && (proxyCreds.getUserName() != null) && (bugzillaProtocol.equals(HTTPS_PROTOCOL))) {
                throw new BugTrackerException(
                        "Bugzilla plugin does not currently support using authenticated proxy for Bugzilla HTTPS requests."
                );
            }
		}

		final BugzillaConnector connector = new BugzillaConnector(CONNNECT_TIMEOUT, SOCKET_TIMEOUT);
		try {
			if (sscProxy == null) {
				connector.connectTo(bugzillaURL.toString());
			} else {
				if (proxyCreds == null) {
					connector.connectTo(bugzillaURL.toString(), null, null
							, sscProxy, null, null);
				} else {
					connector.connectTo(bugzillaURL.toString(), null, null
							, sscProxy, proxyCreds.getUserName(), proxyCreds.getPassword());
				}
			}
		} catch (ConnectionException e) {
			throw new BugTrackerAuthenticationException("Could not connect to Bugzilla server at " + bugzillaURL, e);
		} catch (Exception e) {
			throw new BugTrackerException("Could not connect to Bugzilla server at " + bugzillaURL, e);
		}
		final LogIn logIn = new LogIn(credentials.getUserName(), credentials.getPassword());
		try {
			executeMethod(connector, logIn);
		} catch (BugzillaException e) {
			throw new BugTrackerException("Could not login to Bugzilla server at " + bugzillaURL, e);
		}
		return connector;
	}


    private List<String> executeGetLegalValues(BugzillaConnector connector, GetLegalValues get)
            throws BugzillaException, BugTrackerException {

        executeMethod(connector, get);
        Set<String> values = get.getLegalValues();
        final List<String> result = new ArrayList<>(values);
        Collections.sort(result);
        return result;
    }

    private void executeMethod(final BugzillaConnector connector, final BugzillaMethod method)
            throws BugTrackerException, BugzillaException {

        try {
            connector.executeMethod(method);
        } catch (BugzillaException e) {
            if (e instanceof BugzillaTransportException) {
                switch (((BugzillaTransportException)e).getStatus()) {
                    case HttpURLConnection.HTTP_UNAUTHORIZED:
                        throw new BugTrackerAuthenticationException("Bugzilla server authentication failed");
                    case HttpURLConnection.HTTP_PROXY_AUTH:
                        throw new BugTrackerAuthenticationException("Http(s) proxy authentication for Bugzilla failed");
                    default:
                        throw new BugTrackerException(e.getMessage(), e);
                }
            } else {
                throw (e);
            }
        }
    }


    @Override
	public String getBugDeepLink(String bugId) {
		return bugzillaURL.toString() + SHOW_BUG_CGI_URL + bugId;
	}

	private Integer portToNumber (final String port) {
		Integer portNum;
		try {
			portNum = Integer.valueOf(port);
		} catch (NumberFormatException e) {
			throw new BugTrackerException(String.format("Port %s could not be converted to number - returning null", port));
		}
		return portNum;
	}

	private Proxy resolveSscProxy(final Map<String, String> config, final String targetProtocol) {
		if (HTTPS_PROTOCOL.equals(targetProtocol)) {
			return getSscProxy(config.get(ProxyField.HTTPS_PROXY_HOST.getFieldName())
					, config.get(ProxyField.HTTPS_PROXY_PORT.getFieldName()));
		} else
			return getSscProxy(config.get(ProxyField.HTTP_PROXY_HOST.getFieldName())
					, config.get(ProxyField.HTTP_PROXY_PORT.getFieldName()));
	}

	private UserAuthenticationStore resolveSscProxyCredentials(final Map<String, String> config, final String targetProtocol) {

		if (HTTPS_PROTOCOL.equals(targetProtocol)) {
			String userName = config.get(ProxyField.HTTPS_PROXY_USERNAME.getFieldName());
			if (userName == null) {
				return null;
			} else {
				return new SimpleUserAuthStore(config.get(ProxyField.HTTPS_PROXY_USERNAME.getFieldName())
						, config.get(ProxyField.HTTPS_PROXY_PASSWORD.getFieldName()));
			}
		} else {
			String userName = config.get(ProxyField.HTTP_PROXY_USERNAME.getFieldName());
			if (userName == null) {
				return null;
			} else {
				return new SimpleUserAuthStore(config.get(ProxyField.HTTP_PROXY_USERNAME.getFieldName())
						, config.get(ProxyField.HTTP_PROXY_PASSWORD.getFieldName()));
			}
		}
	}

	private Proxy getSscProxy(final String sscProxyHostname, final String sscProxyPort) {
		Proxy proxy = null;
		if (!StringUtils.isEmpty(sscProxyHostname) && !sscProxyHostname.equals(ProxyField.PROXY_EMPTY_VALUE)) {
			Integer sscProxyPortNum = portToNumber(sscProxyPort);
			if (sscProxyPortNum == null || sscProxyPortNum < 1) {
				throw new BugTrackerException(String.format(
						"Error in bug tracker proxy configuration - SSC proxy host is '%s' but port is '%s'", sscProxyHostname, sscProxyPort));
			} else {
				proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(sscProxyHostname, sscProxyPortNum));
			}
		}
		return proxy;
	}

	private static class PriorityWithSortLevel {
		private int sortLevel = 100;
		private String priority;

		PriorityWithSortLevel(String priority) {this.priority = priority;}
		PriorityWithSortLevel(int sortLevel, String priority) { this.priority = priority; this.sortLevel = sortLevel;}
		String getPriority() { return priority;}
		int getSortLevel() { return sortLevel;}
	}

	private static final PriorityWithSortLevel KNOWN_PRIORITY_IMMEDIATE = new PriorityWithSortLevel(0, "Immediate");
	private static final PriorityWithSortLevel KNOWN_PRIORITY_HIGHEST = new PriorityWithSortLevel(1, "Highest");
	private static final PriorityWithSortLevel KNOWN_PRIORITY_HIGH = new PriorityWithSortLevel(2, "High");
	private static final PriorityWithSortLevel KNOWN_PRIORITY_NORMAL = new PriorityWithSortLevel(3, "Normal");
	private static final PriorityWithSortLevel KNOWN_PRIORITY_LOW = new PriorityWithSortLevel(4, "Low");
	private static final PriorityWithSortLevel KNOWN_PRIORITY_LOWEST = new PriorityWithSortLevel(5, "Lowest");

	private static final Map<String, PriorityWithSortLevel> KNOWN_PRIORITIES = Stream.of(
			new SimpleImmutableEntry<>(KNOWN_PRIORITY_IMMEDIATE.getPriority().toLowerCase(), KNOWN_PRIORITY_IMMEDIATE),
			new SimpleImmutableEntry<>(KNOWN_PRIORITY_HIGHEST.getPriority().toLowerCase(), KNOWN_PRIORITY_HIGHEST),
			new SimpleImmutableEntry<>(KNOWN_PRIORITY_HIGH.getPriority().toLowerCase(), KNOWN_PRIORITY_HIGH),
			new SimpleImmutableEntry<>(KNOWN_PRIORITY_NORMAL.getPriority().toLowerCase(), KNOWN_PRIORITY_NORMAL),
			new SimpleImmutableEntry<>(KNOWN_PRIORITY_LOW.getPriority().toLowerCase(), KNOWN_PRIORITY_LOW),
			new SimpleImmutableEntry<>(KNOWN_PRIORITY_LOWEST.getPriority().toLowerCase(), KNOWN_PRIORITY_LOWEST)
	).collect(Collectors.toMap(SimpleImmutableEntry::getKey, SimpleImmutableEntry::getValue));

	private static class SimpleUserAuthStore implements UserAuthenticationStore {

		private String userName;
		private String password;

		SimpleUserAuthStore(String userName, String password) {
			this.userName = userName;
			this.password = password;
		}

		public String getUserName() {
			return userName;
		}
		public String getPassword() {
			return password;
		}

	}
}
