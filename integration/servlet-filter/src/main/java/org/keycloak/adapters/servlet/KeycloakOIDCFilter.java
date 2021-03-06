package org.keycloak.adapters.servlet;

import org.keycloak.adapters.AdapterDeploymentContext;
import org.keycloak.adapters.spi.AuthChallenge;
import org.keycloak.adapters.spi.AuthOutcome;
import org.keycloak.adapters.AuthenticatedActionsHandler;
import org.keycloak.adapters.spi.InMemorySessionIdMapper;
import org.keycloak.adapters.KeycloakConfigResolver;
import org.keycloak.adapters.KeycloakDeployment;
import org.keycloak.adapters.KeycloakDeploymentBuilder;
import org.keycloak.adapters.NodesRegistrationManagement;
import org.keycloak.adapters.PreAuthActionsHandler;
import org.keycloak.adapters.spi.SessionIdMapper;
import org.keycloak.adapters.spi.UserSessionManagement;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class KeycloakOIDCFilter implements Filter {
    protected AdapterDeploymentContext deploymentContext;
    protected SessionIdMapper idMapper = new InMemorySessionIdMapper();
    protected NodesRegistrationManagement nodesRegistrationManagement;
    private final static Logger log = Logger.getLogger(""+KeycloakOIDCFilter.class);

    @Override
    public void init(final FilterConfig filterConfig) throws ServletException {
        String configResolverClass = filterConfig.getInitParameter("keycloak.config.resolver");
        if (configResolverClass != null) {
            try {
                KeycloakConfigResolver configResolver = (KeycloakConfigResolver) getClass().getClassLoader().loadClass(configResolverClass).newInstance();
                deploymentContext = new AdapterDeploymentContext(configResolver);
                log.log(Level.INFO, "Using {0} to resolve Keycloak configuration on a per-request basis.", configResolverClass);
            } catch (Exception ex) {
                log.log(Level.FINE, "The specified resolver {0} could NOT be loaded. Keycloak is unconfigured and will deny all requests. Reason: {1}", new Object[]{configResolverClass, ex.getMessage()});
                deploymentContext = new AdapterDeploymentContext(new KeycloakDeployment());
            }
        } else {
            String fp = filterConfig.getInitParameter("keycloak.config.file");
            InputStream is = null;
            if (fp != null) {
                try {
                    is = new FileInputStream(fp);
                } catch (FileNotFoundException e) {
                    throw new RuntimeException(e);
                }
            } else {
                String path = "/WEB-INF/keycloak.json";
                String pathParam = filterConfig.getInitParameter("keycloak.config.path");
                if (pathParam != null) path = pathParam;
                is = filterConfig.getServletContext().getResourceAsStream(path);
            }
            KeycloakDeployment kd;
            if (is == null) {
                log.fine("No adapter configuration. Keycloak is unconfigured and will deny all requests.");
                kd = new KeycloakDeployment();
            } else {
                kd = KeycloakDeploymentBuilder.build(is);
            }
            deploymentContext = new AdapterDeploymentContext(kd);
            log.fine("Keycloak is using a per-deployment configuration.");
        }
        filterConfig.getServletContext().setAttribute(AdapterDeploymentContext.class.getName(), deploymentContext);
        nodesRegistrationManagement = new NodesRegistrationManagement();
    }


    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        OIDCServletHttpFacade facade = new OIDCServletHttpFacade(request, response);
        KeycloakDeployment deployment = deploymentContext.resolveDeployment(facade);
        if (deployment == null || !deployment.isConfigured()) {
            response.sendError(403);
            log.fine("deployment not configured");
            return;
        }

        PreAuthActionsHandler preActions = new PreAuthActionsHandler(new UserSessionManagement() {
            @Override
            public void logoutAll() {
                if (idMapper != null) {
                    idMapper.clear();
                }
            }

            @Override
            public void logoutHttpSessions(List<String> ids) {
                for (String id : ids) {
                    idMapper.removeSession(id);
                }

            }
        }, deploymentContext, facade);

        if (preActions.handleRequest()) {
            return;
        }


        nodesRegistrationManagement.tryRegister(deployment);
        OIDCFilterSessionStore tokenStore = new OIDCFilterSessionStore(request, facade, 100000, deployment, idMapper);
        tokenStore.checkCurrentToken();


        FilterRequestAuthenticator authenticator = new FilterRequestAuthenticator(deployment, tokenStore, facade, request, 8443);
        AuthOutcome outcome = authenticator.authenticate();
        if (outcome == AuthOutcome.AUTHENTICATED) {
            log.fine("AUTHENTICATED");
            if (facade.isEnded()) {
                return;
            }
            AuthenticatedActionsHandler actions = new AuthenticatedActionsHandler(deployment, facade);
            if (actions.handledRequest()) {
                return;
            } else {
                HttpServletRequestWrapper wrapper = tokenStore.buildWrapper();
                chain.doFilter(wrapper, res);
                return;
            }
        }
        AuthChallenge challenge = authenticator.getChallenge();
        if (challenge != null) {
            log.fine("challenge");
            challenge.challenge(facade);
            return;
        }
        response.sendError(403);

    }

    @Override
    public void destroy() {

    }
}
