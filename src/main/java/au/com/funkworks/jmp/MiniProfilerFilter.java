/**
 * Copyright (C) 2011 by Jim Riecken, Alvin Singh
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package au.com.funkworks.jmp;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.util.StringUtils;

import au.com.funkworks.jmp.interfaces.CacheProfilerService;
import au.com.funkworks.jmp.interfaces.UserProfilerService;


/**
 * A Servlet filter that enables the {@link MiniProfiler} under certain
 * conditions (which are configurable)
 */
public class MiniProfilerFilter implements Filter {
	
	
	public static final String CACHE_KEY_FORMAT_STRING = "mini_profile_request_%s";

	private static final String REQUEST_ID_HEADER = "X-Mini-Profile-Request-Id";
	private static final String REQUEST_ID_ATTRIBUTE = "mini_profile_request_id";

	private static final String INCLUDES_ATTRIBUTE = "mini_profile_includes";

	private static final String PROFILE_SERVLET_URL_KEY = "servletURL";
	private static final String RESTRICT_TO_ADMINS_KEY = "restrictToAdmins";
	private static final String RESTRICT_TO_EMAILS_KEY = "restrictToEmails";
	private static final String RESTRICT_TO_URLS_KEY = "restrictToURLs";
	
	private static final String HTML_ID_PREFIX_KEY = "htmlIdPrefix";
	
	//private static final String DATA_EXPIRY_KEY = "dataExpiry";

	/** Whether this filter has been restricted to some sort of logged-in user. */
	private boolean restricted = false;

	/** Whether this filter is restricted to app admins only. */
	private boolean restrictedToAdmins = false;

	/**
	 * The set of users app users that this filter should be restricted to. If
	 * empty, there are no restrictions.
	 */
	private Set<String> restrictedEmails = new HashSet<String>();

	/**
	 * The set of regex patterns that the filter will be restricted to. Note
	 * that the filter's mapping in the web.xml will also affect the set of URLs
	 * that the filter will run on.
	 */
	private Set<Pattern> restrictedURLs = new HashSet<Pattern>();

	/**
	 * The URL that the {@link MiniProfilerServlet} is mapped to.
	 */
	private String servletURL = "/java_mini_profile/";

	/**
	 * The number of seconds that profiling data will stick around for in
	 * memcache.
	 */
	//private int dataExpiry = 30;

	/**
	 * The prefix for all HTML element ids/classes used in the profiler UI. This
	 * must be the same value as the {@code htmlIdPrefix} field in
	 * {@link MiniProfilerServlet}.
	 */
	private String htmlIdPrefix = "mp";		

	/**
	 * The loader that will load the UI includes (scripts/css) for the profiler
	 * UI from a file in the classpath.
	 */
	private MiniProfilerResourceLoader resourceLoader;

	/** Map of string replacements that will be done on loaded resources. */
	private Map<String, String> resourceReplacements = new HashMap<String, String>();

	private UserProfilerService userProfilerService;
	private CacheProfilerService cacheProfilerService;

	/**
	 * A counter used to generate request ids that are then used to construct
	 * memcache keys for the profiling data.
	 */
	private AtomicLong counter;
	
	public void init(FilterConfig config) throws ServletException {
		
		String configServletURL = config.getInitParameter(PROFILE_SERVLET_URL_KEY);
		if (StringUtils.hasLength(configServletURL)) {
			servletURL = configServletURL;
		}
		String configRestrictToAdmins = config.getInitParameter(RESTRICT_TO_ADMINS_KEY);
		if (StringUtils.hasLength(configRestrictToAdmins) && Boolean.parseBoolean(configRestrictToAdmins)) {
			restricted = true;
			restrictedToAdmins = true;
		}
		String configRestrictToEmails = config.getInitParameter(RESTRICT_TO_EMAILS_KEY);
		if (StringUtils.hasLength(configRestrictToEmails)) {
			restricted = true;
			String[] emails = configRestrictToEmails.split(",");
			for (String email : emails) {
				restrictedEmails.add(email.trim());
			}
		}
		//String configDataExpiry = config.getInitParameter(DATA_EXPIRY_KEY);
		//if (!isEmpty(configDataExpiry)) {
		//	dataExpiry = Integer.parseInt(configDataExpiry);
		//}
		String configRestrictToURLs = config.getInitParameter(RESTRICT_TO_URLS_KEY);
		if (StringUtils.hasLength(configRestrictToURLs)) {
			for (String urlPattern : configRestrictToURLs.split(",")) {
				urlPattern = urlPattern.trim();
				if (StringUtils.hasLength(urlPattern)) {
					restrictedURLs.add(Pattern.compile(urlPattern));
				}
			}
		}
		String configHtmlIdPrefix = config.getInitParameter(HTML_ID_PREFIX_KEY);
		if (StringUtils.hasLength(configHtmlIdPrefix)) {
			htmlIdPrefix = configHtmlIdPrefix.trim();
		}
		
		
		try {
			cacheProfilerService = JMPFactory.getCacheProfilerService(config);
		} catch (Exception e) {
			throw new ServletException(e);
		}
		
		// only required if restricted
		if (restricted) {		
			try {				
				userProfilerService = JMPFactory.getUserProfilerService(config);								
			} catch (Exception e) {
				throw new ServletException(e);
			}			
		}
	
		counter = new AtomicLong(1);
		resourceLoader = new MiniProfilerResourceLoader();
		resourceReplacements.put("@@baseURL@@", servletURL);
		resourceReplacements.put("@@prefix@@", htmlIdPrefix);
	}

	
	public void destroy() {
		// Nothing to destroy
	}

	/**
	 * If profiling is supposed to occur for the current request, profile the
	 * request. Otherwise this filter does nothing.
	 */	
	public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain) throws IOException, ServletException {
		HttpServletRequest req = (HttpServletRequest) servletRequest;
		HttpServletResponse res = (HttpServletResponse) servletResponse;
		
		if (shouldProfile(req.getRequestURI())) {
			String requestId = String.valueOf(counter.incrementAndGet());


			req.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
			res.addHeader(REQUEST_ID_HEADER, requestId);

			addIncludes(req);

			MiniProfiler.Profile profile = null;
			long startTime = System.currentTimeMillis();
			MiniProfiler.start();
			try {
				chain.doFilter(servletRequest, res);
			} finally {
				profile = MiniProfiler.stop();
			}

			Map<String, Object> requestData = new HashMap<String, Object>();
			requestData.put("requestURL", req.getRequestURI() + ((req.getQueryString() != null) ? "?" + req.getQueryString() : ""));
			requestData.put("timestamp", startTime);
			requestData.put("profile", profile);
			cacheProfilerService.put(String.format(CACHE_KEY_FORMAT_STRING, requestId), requestData);
		} else {
			chain.doFilter(servletRequest, servletResponse);
		}
	}

	/**
	 * Adds the UI includes to a request attribute (named
	 * {@link #REQUEST_ID_ATTRIBUTE})
	 * 
	 * @param req
	 *            The current HTTP request.
	 */
	private void addIncludes(HttpServletRequest req) {
		String result = null;
		String requestId = (String) req.getAttribute(MiniProfilerFilter.REQUEST_ID_ATTRIBUTE);
		if (requestId != null) {
			String includesTemplate = resourceLoader.getResource("mini_profiler.html", resourceReplacements);
			if (includesTemplate != null) {
				result = includesTemplate.replace("@@requestId@@", requestId);
			}
		}
		if (StringUtils.hasLength(result)) {
			req.setAttribute(INCLUDES_ATTRIBUTE, result);
		}
	}

	/**
	 * Whether the specified URL should be profiled given the current
	 * configuration of the filter.
	 * 
	 * @param url
	 *            The URL to check.
	 * @return Whether the URL should be profiled.
	 */
	public boolean shouldProfile(String url) {
		// Don't profile requests to to results servlet
		if (url.startsWith(servletURL)) {
			return false;
		}

		if (!restrictedURLs.isEmpty()) {
			boolean matches = false;
			for (Pattern p : restrictedURLs) {
				if (p.matcher(url).find()) {
					matches = true;
				}
			}
			if (!matches) {
				return false;
			}
		}

		if (restricted) {
			if (userProfilerService.isUserLoggedIn()) {
				
				// restricted to admins
				if (restrictedToAdmins) {
					return userProfilerService.isUserAdmin();
				}			
				
				// restricted to emails
				String email = userProfilerService.getLoggedInUserEmail();
				if (email == null || !restrictedEmails.contains(email)) {
					return false;
				}
				
			} else {
				return false;
			}
		}
		return true;
	}

}
