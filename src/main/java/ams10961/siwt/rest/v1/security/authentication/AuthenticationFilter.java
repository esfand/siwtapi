package ams10961.siwt.rest.v1.security.authentication;

import java.io.IOException;
import java.lang.reflect.Method;

import javax.annotation.Priority;
import javax.annotation.security.PermitAll;
import javax.ejb.EJB;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;

import org.jboss.resteasy.core.ResourceMethodInvoker;
import org.slf4j.Logger;

import ams10961.siwt.Constants;
import ams10961.siwt.entities.Authentication;
import ams10961.siwt.entities.persistence.AuthenticationPersistence;
import ams10961.siwt.rest.v1.security.OriginFilter;
import ams10961.siwt.rest.v1.security.authentication.AuthenticationException.AuthenticationExceptionType;

/*
 * Check the Authorization(authentication) header on requests, add any session object to the request 
 * 
 * TODO: include token generated by client in authentication process
 *
 */

@Provider
@Priority(3)
public class AuthenticationFilter implements ContainerRequestFilter {
	
	public static final String AUTHENTICATION_DATA = "AUTHENTICATION";

	@Context
	HttpServletRequest httpRequest;
	
	@Context
	HttpServletResponse httpResponse;
	
	
	@Inject
	private transient Logger logger;
	
	@EJB
	private AuthenticationPersistence authenticationPersistence;

	@Override
	public void filter(ContainerRequestContext requestContext) throws IOException {

	    ResourceMethodInvoker methodInvoker = (ResourceMethodInvoker) requestContext.getProperty(Constants.RESOURCE_METHOD_INVOKER);
	    Method method = methodInvoker.getMethod();
	    
	    // Access allowed for all
	    if(method.isAnnotationPresent(PermitAll.class)) {
	    	if (logger.isDebugEnabled()) {
	    		logger.debug("No authentication necessary for method");
	    	}
	    	return;
	    }
		
		String authenticationHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
		
		// reject empty values
		if ((authenticationHeader == null) || (authenticationHeader.trim().length() == 0)) {
			requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).entity("Authorization (authentication) header missing").build());
		}
		
		/* parse authentication header */
		
		
		Authentication authentication = null;
		try {
			// client IP address
			String originIp = (String) httpRequest.getAttribute(OriginFilter.ORIGIN_IP);

			// validate authentication, do not allow expired sessions
			authentication = validate(authenticationHeader, originIp, false);
			
			if (logger.isDebugEnabled()) {
				logger.debug("authentication validated: {}",authentication.toString());
			}
			
			// pass retrieved object for later processing
			httpRequest.setAttribute(AUTHENTICATION_DATA, authentication);
		
		} catch (AuthenticationException e) {
			
			// TODO: update response to include additional failure headers
			switch (e.getType()) {
			case NONEXISTENT:
				requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).entity("not authenticated").build());
				break;
			case EXPIRED:
				requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).entity("authentication has expired, reauthenticate").build());
				break;
			case SUSPCIOUS:
				requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).entity("authentication suspicious").build());
				break;
			case UNKNOWN:
				logger.warn("Uncategorised authentication failure");
				requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).entity("not authenticated").build());
				break;
			default:
				logger.warn("should not arrive here");
				requestContext.abortWith(Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("unknown error, check logs").build());
			}
		}

	}
	
	/*
	 * 
	 */
	public Authentication validate(String uuid, String ipAddress, boolean allowExpired) throws AuthenticationException {

		// Search for Authentication
		Authentication authentication = authenticationPersistence.findByUuid(uuid);

		/* non existent */
		if (authentication == null) {
			logger.error("authentication uuid >{}< not found", uuid);
			AuthenticationException e = new AuthenticationException("authentication not found");
			e.setType(AuthenticationExceptionType.NONEXISTENT);
			throw e;
		}

		/* exists, but suspicious */
		if (!authentication.getIpAddress().equalsIgnoreCase(ipAddress)) {
			logger.error("authentication found >{}<, but query ip address differs >{}<", authentication.toString(), ipAddress);
			AuthenticationException e = new AuthenticationException("existing session but with different ip address");
			e.setType(AuthenticationExceptionType.SUSPCIOUS);
			throw e;
		}

		/* exists, check status */
		AuthenticationException e = null;
		switch (authentication.getStatus()) {

		case ACTIVE:
			long timeNow = System.currentTimeMillis();
			
			/* check whether too old */
			long creationTime = authentication.getCreationTime().getTime();
			long ageMs = timeNow - creationTime;
			if (ageMs > Constants.AUTHENTICATION_MAX_AGE_MS) {
				logger.error("authentication has exceed max age of {} ms, {}",Constants.AUTHENTICATION_MAX_AGE_MS , authentication.toString());
				e = new AuthenticationException("authentication has expired");
				e.setType(AuthenticationExceptionType.EXPIRED);
				throw e;			
			}
	
			/* check whether inactive too long */
			long lastValidatedTime = authentication.getLastValidatedTime().getTime();
			long inactiveMs = timeNow - lastValidatedTime;
			if (logger.isDebugEnabled()) {
				logger.debug("last validated {} ms ago", inactiveMs);
			}

			// TODO: timeout logic too simple - guests really have indefinite
			// sessions?
			if (authentication.getInactivityTimeout() > 0) {
				if (inactiveMs > authentication.getInactivityTimeout()) {
					/* expired */
					if (allowExpired) {
						/* allow, but don't update validation time */
						logger.warn("allowing expired authentication without exception");
					} else {
						logger.error("authentication has exceed limit of {} ms, {}", authentication.getInactivityTimeout(), authentication.toString());
						e = new AuthenticationException("authentication has expired");
						e.setType(AuthenticationExceptionType.EXPIRED);
						throw e;
					}
				} /* else nothing to do */
			} else {
				// TODO: check this logic
				logger.warn("no timeout set for authentication {}", authentication.toString());
			}
			break;

		case EXPIRED:
			logger.warn("authentication expired {}", authentication.toString());
			e = new AuthenticationException("authentication expired");
			e.setType(AuthenticationExceptionType.EXPIRED);
			throw e;
			
		case ABANDONED:
			logger.warn("authentication abandoned {}", authentication.toString());
			e = new AuthenticationException("authentication no longer valid");
			e.setType(AuthenticationExceptionType.SUSPCIOUS);
			throw e;

		default:
			logger.warn("shouldn't arrive here {}", authentication.toString());
			e = new AuthenticationException("shouldn't arrive here");
			e.setType(AuthenticationExceptionType.UNKNOWN);
			throw e;
		}

		return authentication;
	}
}