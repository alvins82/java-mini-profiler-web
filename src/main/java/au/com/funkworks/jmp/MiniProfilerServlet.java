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
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import au.com.funkworks.jmp.interfaces.CacheProfilerService;


/**
 * Servlet that:
 * <ul>
 * <li>Returns profile information for a set of requests (in JSON format).
 * <li>Serves the static resources that make up the profiler UI.
 * </ul>
 */
public class MiniProfilerServlet extends HttpServlet {
	
	private static final Logger logger = LoggerFactory.getLogger(MiniProfilerServlet.class);
	
	private static final long serialVersionUID = 7906645907029238585L;
	
	private static final String HTML_ID_PREFIX_KEY = "htmlIdPrefix";
	private static final String RESOURCE_CACHE_HOURS_KEY = "resourceCacheHours";	
	
	private static final String servletURL = "/java_mini_profile/";	

	/**
	 * The prefix for all HTML element ids/classes used in the profiler UI. This
	 * must be the same value as the {@code htmlIdPrefix} field in
	 * {@link MiniProfilerFilter}.
	 */
	private String htmlIdPrefix = "mp";

	/**
	 * The number of hours that the static resources should be cached in the
	 * browser for.
	 */
	private int resourceCacheHours = 0;

	/**
	 * The loader that will load the static resources for the profiler UI from
	 * files in the classpath.
	 */
	private MiniProfilerResourceLoader resourceLoader;	

	/** Map of string replacements that will be done on loaded resources. */
	private Map<String, String> resourceReplacements = new HashMap<String, String>();

	/** The cache Service */
	private CacheProfilerService cacheProfilerService;

	@Override
	public void init(ServletConfig config) throws ServletException {
		logger.debug("Init'ing mini-profiler servlet");
		super.init(config);
				
		String configHtmlIdPrefix = config.getInitParameter(HTML_ID_PREFIX_KEY);
		if (!isEmpty(configHtmlIdPrefix)) {
			htmlIdPrefix = configHtmlIdPrefix.trim();
		}
		String configResourceCacheHours = config.getInitParameter(RESOURCE_CACHE_HOURS_KEY);
		if (!isEmpty(configResourceCacheHours)) {
			resourceCacheHours = Integer.parseInt(configResourceCacheHours);
			logger.debug("Resource cache hours set to {}", resourceCacheHours);
		}		
				
		try {
			cacheProfilerService = JMPFactory.getCacheProfilerService(config);
		} catch (Exception e) {
			throw new ServletException(e);
		}		
				
		resourceLoader = new MiniProfilerResourceLoader();
		resourceReplacements.put("@@prefix@@", htmlIdPrefix);
		resourceReplacements.put("@@baseURL@@", servletURL);
		resourceReplacements.put("@@prefix@@", htmlIdPrefix);
		logger.debug("Init'ed mini-profiler servlet");
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		String requestURI = req.getRequestURI();
		if (requestURI.endsWith("results")) {
			doResults(req, resp);
		} else if (requestURI.endsWith("resource")) {
			doResource(req, resp);
		}
	}

	/**
	 * Serve one of the static resources for the profiler UI.
	 */
	private void doResource(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		boolean success = true;
		String resource = (String) req.getParameter("id");
		
		if (!isEmpty(resource)) {
			if (resource.endsWith(".js")) {
				resp.setContentType("text/javascript");
			} else if (resource.endsWith(".css")) {
				resp.setContentType("text/css");
			} else if (resource.endsWith(".html")) {
				resp.setContentType("text/html");
			} else {
				resp.setContentType("text/plain");
			}

			String contents = resourceLoader.getResource(resource, resourceReplacements);
			if (contents != null) {
				if (resourceCacheHours > 0) {
					Calendar c = Calendar.getInstance();
					c.add(Calendar.HOUR, resourceCacheHours);
					resp.setHeader("Cache-Control", "public, must-revalidate");
					resp.setHeader("Expires", new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz").format(c.getTime()));
				} else {
					resp.setHeader("Cache-Control", "no-cache");
				}
				
				PrintWriter w = resp.getWriter();
				w.print(contents);
			} else {
				success = false;
			}
		}
		if (!success) {
			resp.sendError(404);
		}
	}

	/**
	 * Generate the results for a set of requests in JSON format.
	 */
	private void doResults(HttpServletRequest req, HttpServletResponse resp) throws IOException, JsonGenerationException, JsonMappingException {
		Map<String, Object> result = new HashMap<String, Object>();

		String requestIds = req.getParameter("ids");
		if (!isEmpty(requestIds)) {
			List<Map<String, Object>> requests = new ArrayList<Map<String, Object>>();
			for (String requestId : requestIds.split(",")) {
				requestId = requestId.trim();
								
				Map<String, Object> requestData = cacheProfilerService.get(String.format(MiniProfilerFilter.CACHE_KEY_FORMAT_STRING, requestId));
				if (requestData != null) {
					Map<String, Object> request = new HashMap<String, Object>();
					request.put("id", requestId);
					request.put("redirect", requestData.get("redirect"));
					request.put("requestURL", requestData.get("requestURL"));
					request.put("timestamp", requestData.get("timestamp"));
					request.put("profile", requestData.get("profile"));					
					request.put("appstats", null);					
					requests.add(request);
				}
			}
			result.put("ok", true);
			result.put("requests", requests);
		} else {
			result.put("ok", false);
		}

		resp.setContentType("application/json");
		resp.setHeader("Cache-Control", "no-cache");

		ObjectMapper jsonMapper = new ObjectMapper();
		jsonMapper.writeValue(resp.getOutputStream(), result);
	}

	/**
	 * Get whether the specified string is null or empty.
	 * 
	 * @param str
	 *            The string to test.
	 * @return Whether the string is empty.
	 */
	private static boolean isEmpty(String str) {
		return str == null || str.trim().length() == 0;
	}
}
