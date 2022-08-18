/**
 * (c) Copyright [2015-2018] Micro Focus or one of its affiliates.
 */
package com.fortify.sample.bugtracker.bugzilla;

import java.util.*;

/**
 * Constants for using inside the Bugzilla plugins.
 *
 * @author Evgeniy Semionov (evgeny.semionov@hp.com)
 * @version 1.0 06/10/2013
 */
final class BugzillaPluginConstants {

	/**
	 * SSC proxy field definitions
	 *
	 * Names of the SSC proxy fields are part of a bug tracker plugin API and cannot be changed.
	 * All fields must be sent by getConfiguration() in order to make SSC to respond with proxy fields config values back.
	*/
	enum ProxyField {
		HTTP_PROXY_HOST("httpProxyHost", "HTTP Proxy Host")
		, HTTP_PROXY_PORT("httpProxyPort", "HTTP Proxy Port")
		, HTTP_PROXY_USERNAME("httpProxyUsername", "HTTP Proxy Username")
		, HTTP_PROXY_PASSWORD("httpProxyPassword", "HTTP Proxy Password")
		, HTTPS_PROXY_HOST("httpsProxyHost", "HTTPS Proxy Host")
		, HTTPS_PROXY_PORT("httpsProxyPort", "HTTPS Proxy Port")
		, HTTPS_PROXY_USERNAME("httpsProxyUsername", "HTTPS Proxy Username")
		, HTTPS_PROXY_PASSWORD("httpsProxyPassword", "HTTPS Proxy Password")
		;

		static final String PROXY_EMPTY_VALUE = null;
		final private String fieldName;
		final private String displayLabel;

		String getFieldName() {
			return fieldName;
		}
		String getDisplayLabel() {
			return displayLabel;
		}

		ProxyField(final String fieldName, final String displayLabel) {
			this.fieldName = fieldName;
			this.displayLabel = displayLabel;
		}
	}

	public static final String BUGZILLA_COOKIE = "Bugzilla_login";
	public static final String BUGZILLA_LOGIN = "Bugzilla_login";
	public static final String BUGZILLA_PASSWORD = "Bugzilla_password";
	public static final String BUGZILLA_URL_NAME = "bugzillaUrl";
	public static final String COMMENT = "comment";
	public static final String COMPONENT_PARAM_NAME = "component";

	//#############################
	//HTTP Connection Utility Methods
	//#############################
	public static final int CONNNECT_TIMEOUT = 5 * 1000;
	public static final String CONTENT_TYPE = "Content-Type";
	static final String HTTP_PROTOCOL = "http";
	static final String HTTPS_PROTOCOL = "https";
	static final int HTTP_PORT = 80;
	static final int HTTPS_PORT = 443;
	public static final int SOCKET_TIMEOUT = 10 * 1000;

	public static final String LOGIN_CGI = "/index.cgi";
	public static final String POST_BUG_CGI = "/post_bug.cgi";
	public static final String ENTER_BUG = "/enter_bug.cgi";
	public static final String PROCESS_BUG_CGI = "/process_bug.cgi";
	public static final String PRIORITY_PARAM_NAME = "priority";
	public static final String PRODUCT_PARAM_NAME = "product";
	public static final String SUMMARY = "short_desc";
	public static final String VERSION_PARAM_NAME = "version";
	public static final String SUMMARY_PARAM_NAME = "summary";
	public static final String DESCRIPTION_PARAM_NAME = "description";
	public static final String BUG_STATUS_PARAM_NAME = "bug_status";
	public static final String BUG_ID_PARAM_NAME = "id";
	public static final String BUG_UPDATE_TOKEN_PARAM_NAME = "token";

	public static final String STATUS_NEW = "UNCONFIRMED"; // NEW was removed from default workflow in Bugzilla4 and replaced with UNCONFIRMED
	public static final String STATUS_REOPENED = "CONFIRMED"; // REOPENED was removed from default workflow in Bugzilla4
	public enum CLOSED_STATUS { RESOLVED, CLOSED, VERIFIED };
	public enum NON_REOPENABLE_RESOLUTION { WONTFIX, DUPLICATE, INVALID, WORKSFORME };

	public static final String PRODUCT_LABEL = "Product";
	public static final String PRODUCT_DESCRIPTION = "Name of Product against which bug needs to be logged";
	public static final String COMPONENT_LABEL = "Component";
	public static final String COMPONENT_DESCRIPTION = "Name of Component against which bug needs to be logged";
	public static final String VERSION_LABEL = "Version";
	public static final String VERSION_DESCRIPTION = "Version against which bug needs to be logged";

	public static final Map<String, String> hiddenBugParams = new HashMap<String, String>();
	static {
		hiddenBugParams.put("bug_status",STATUS_NEW);
		hiddenBugParams.put("rep_platform", "All");
		hiddenBugParams.put("op_sys", "All");
		hiddenBugParams.put("bug_severity","normal");
	}

	public static final double MIN_BUGZILLA_VER_THAT_CAN_RETURN_PRIORITIES = 3.6;

	public static final String SHOW_BUG_CGI_URL = "/show_bug.cgi?id=";

    public static final String SUPPORTED_VERSIONS = "5.0";

    /**
     * Maximum length for bug summary string.
     */
    public static final int MAX_SUMMARY_LENGTH = 255;

	private BugzillaPluginConstants() {
		// No implementation.
	}
}
