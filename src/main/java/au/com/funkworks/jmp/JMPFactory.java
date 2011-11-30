package au.com.funkworks.jmp;

import javax.servlet.FilterConfig;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.springframework.util.StringUtils;
import org.springframework.web.context.support.WebApplicationContextUtils;

import au.com.funkworks.jmp.cache.NativeEhCacheCacheImpl;
import au.com.funkworks.jmp.interfaces.CacheProfilerService;
import au.com.funkworks.jmp.interfaces.UserProfilerService;

public class JMPFactory {
	
	private static final Object lock = new Object();
	private static CacheProfilerService cacheProfilerService;
	private static UserProfilerService userProfilerService;
	
	private static final String USER_LOOKUP_CLASS_PARAM = "userLookupClass";
	private static final String USER_LOOKUP_CLASS_SPRINGBEAN_PARAM = "userProfilerService-spring-bean-name";
	
	private static final String CACHE_LOOKUP_CLASS_PARAM = "cacheLookupClass";
	private static final String CACHE_LOOKUP_CLASS_SPRINGBEAN_PARAM = "cacheProfilerService-spring-bean-name";

	public static CacheProfilerService getCacheProfilerService(ServletConfig config) throws Exception {
		synchronized (lock) {
			if (cacheProfilerService == null) {				
				String cacheService = config.getInitParameter(CACHE_LOOKUP_CLASS_PARAM);
				String cacheSpringService = config.getInitParameter(CACHE_LOOKUP_CLASS_SPRINGBEAN_PARAM);
				
				setupCacheProfilerService(cacheService ,cacheSpringService, config.getServletContext());				
			}
			return cacheProfilerService;
		}
	}	
	public static CacheProfilerService getCacheProfilerService(FilterConfig config) throws Exception {
		synchronized (lock) {
			if (cacheProfilerService == null) {				
				String cacheService = config.getInitParameter(CACHE_LOOKUP_CLASS_PARAM);
				String cacheSpringService = config.getInitParameter(CACHE_LOOKUP_CLASS_SPRINGBEAN_PARAM);
				
				setupCacheProfilerService(cacheService ,cacheSpringService, config.getServletContext());
			}
			return cacheProfilerService;
		}		
	}	
	private static void setupCacheProfilerService(String cacheService, String cacheSpringService, ServletContext servletContext) throws Exception {
		if (StringUtils.hasLength(cacheService)) {
			Class<?> cls = Class.forName(cacheService);
			cacheProfilerService = (CacheProfilerService)cls.newInstance();						
		} else if (StringUtils.hasLength(cacheSpringService)) {
			cacheProfilerService  = (CacheProfilerService)WebApplicationContextUtils.getWebApplicationContext(servletContext).getBean(cacheSpringService);
		} else {
			cacheProfilerService = new NativeEhCacheCacheImpl();
		}			
	}
	
	public static UserProfilerService getUserProfilerService(FilterConfig config) throws Exception  {
		synchronized (lock) {
			if (userProfilerService == null) {																
				String userLookupService = config.getInitParameter(USER_LOOKUP_CLASS_PARAM);
				String userSpringLookupService = config.getInitParameter(USER_LOOKUP_CLASS_SPRINGBEAN_PARAM);
				
				if (StringUtils.hasLength(userLookupService)) {
					Class<?> cls = Class.forName(userLookupService);
					userProfilerService = (UserProfilerService)cls.newInstance();						
				} else if (StringUtils.hasLength(userSpringLookupService)) {
					userProfilerService  = (UserProfilerService)WebApplicationContextUtils.getWebApplicationContext(config.getServletContext()).getBean(userSpringLookupService);
				} else {
					throw new ServletException("JMP is restricted but no UserProfilerService class is defined.");
				}									
			}
			return userProfilerService;
		}		
	}
	
}
