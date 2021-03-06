/*
 * Copyright 2014 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @author tags. All rights reserved.
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
package org.keycloak.adapters.saml.undertow;

import io.undertow.security.api.AuthenticationMechanism;
import io.undertow.security.api.AuthenticationMechanismFactory;
import io.undertow.security.idm.Account;
import io.undertow.security.idm.Credential;
import io.undertow.security.idm.IdentityManager;
import io.undertow.server.handlers.form.FormParserFactory;
import io.undertow.servlet.ServletExtension;
import io.undertow.servlet.api.AuthMethodConfig;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.LoginConfig;
import io.undertow.servlet.api.ServletSessionConfig;
import org.jboss.logging.Logger;
import org.keycloak.adapters.saml.DefaultSamlDeployment;
import org.keycloak.adapters.saml.SamlConfigResolver;
import org.keycloak.adapters.saml.SamlDeployment;
import org.keycloak.adapters.saml.SamlDeploymentContext;
import org.keycloak.adapters.saml.config.parsers.DeploymentBuilder;
import org.keycloak.adapters.saml.config.parsers.ResourceLoader;
import org.keycloak.adapters.undertow.UndertowUserSessionManagement;
import org.keycloak.saml.common.exceptions.ParsingException;

import javax.servlet.ServletContext;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Map;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class SamlServletExtension implements ServletExtension {

    protected static Logger log = Logger.getLogger(SamlServletExtension.class);

    // todo when this DeploymentInfo method of the same name is fixed.
    public boolean isAuthenticationMechanismPresent(DeploymentInfo deploymentInfo, final String mechanismName) {
        LoginConfig loginConfig = deploymentInfo.getLoginConfig();
        if (loginConfig != null) {
            for (AuthMethodConfig method : loginConfig.getAuthMethods()) {
                if (method.getName().equalsIgnoreCase(mechanismName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static InputStream getConfigInputStream(ServletContext context) {
        InputStream is = null;
        if (is == null) {
            String path = context.getInitParameter("keycloak.config.file");
            if (path == null) {
                log.debug("using /WEB-INF/keycloak-saml.xml");
                is = context.getResourceAsStream("/WEB-INF/keycloak-saml.xml");
            } else {
                try {
                    is = new FileInputStream(path);
                } catch (FileNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return is;
    }


    @Override
    @SuppressWarnings("UseSpecificCatch")
    public void handleDeployment(DeploymentInfo deploymentInfo, final ServletContext servletContext) {
        if (!isAuthenticationMechanismPresent(deploymentInfo, "KEYCLOAK-SAML")) {
            log.debug("auth-method is not keycloak saml!");
            return;
        }
        log.debug("SamlServletException initialization");

        // Possible scenarios:
        // 1) The deployment has a keycloak.config.resolver specified and it exists:
        //    Outcome: adapter uses the resolver
        // 2) The deployment has a keycloak.config.resolver and isn't valid (doesn't exists, isn't a resolver, ...) :
        //    Outcome: adapter is left unconfigured
        // 3) The deployment doesn't have a keycloak.config.resolver , but has a keycloak.json (or equivalent)
        //    Outcome: adapter uses it
        // 4) The deployment doesn't have a keycloak.config.resolver nor keycloak.json (or equivalent)
        //    Outcome: adapter is left unconfigured

        SamlConfigResolver configResolver;
        String configResolverClass = servletContext.getInitParameter("keycloak.config.resolver");
        SamlDeploymentContext deploymentContext = null;
        if (configResolverClass != null) {
            try {
                throw new RuntimeException("Not implemented yet");
                //configResolver = (SamlConfigResolver) deploymentInfo.getClassLoader().loadClass(configResolverClass).newInstance();
                //deploymentContext = new AdapterDeploymentContext(configResolver);
                //log.info("Using " + configResolverClass + " to resolve Keycloak configuration on a per-request basis.");
            } catch (Exception ex) {
                log.warn("The specified resolver " + configResolverClass + " could NOT be loaded. Keycloak is unconfigured and will deny all requests. Reason: " + ex.getMessage());
                //deploymentContext = new AdapterDeploymentContext(new KeycloakDeployment());
            }
        } else {
            InputStream is = getConfigInputStream(servletContext);
            final SamlDeployment deployment;
            if (is == null) {
                log.warn("No adapter configuration.  Keycloak is unconfigured and will deny all requests.");
                deployment = new DefaultSamlDeployment();
            } else {
                try {
                    ResourceLoader loader = new ResourceLoader() {
                        @Override
                        public InputStream getResourceAsStream(String resource) {
                            return servletContext.getResourceAsStream(resource);
                        }
                    };
                    deployment = new DeploymentBuilder().build(is, loader);
                } catch (ParsingException e) {
                    throw new RuntimeException(e);
                }
            }
            deploymentContext = new SamlDeploymentContext(deployment);
            log.debug("Keycloak is using a per-deployment configuration.");
        }

        servletContext.setAttribute(SamlDeploymentContext.class.getName(), deploymentContext);
        UndertowUserSessionManagement userSessionManagement = new UndertowUserSessionManagement();
        final ServletSamlAuthMech mech = createAuthMech(deploymentInfo, deploymentContext, userSessionManagement);


        // setup handlers

        deploymentInfo.addAuthenticationMechanism("KEYCLOAK-SAML", new AuthenticationMechanismFactory() {
            @Override
            public AuthenticationMechanism create(String s, FormParserFactory formParserFactory, Map<String, String> stringStringMap) {
                return mech;
            }
        }); // authentication

        deploymentInfo.setIdentityManager(new IdentityManager() {
            @Override
            public Account verify(Account account) {
                return account;
            }

            @Override
            public Account verify(String id, Credential credential) {
                throw new IllegalStateException("Should never be called in Keycloak flow");
            }

            @Override
            public Account verify(Credential credential) {
                throw new IllegalStateException("Should never be called in Keycloak flow");
            }
        });

        log.debug("Setting jsession cookie path to: " + deploymentInfo.getContextPath());
        ServletSessionConfig cookieConfig = new ServletSessionConfig();
        cookieConfig.setPath(deploymentInfo.getContextPath());
        deploymentInfo.setServletSessionConfig(cookieConfig);

     }

    protected ServletSamlAuthMech createAuthMech(DeploymentInfo deploymentInfo, SamlDeploymentContext deploymentContext, UndertowUserSessionManagement userSessionManagement) {
        return new ServletSamlAuthMech(deploymentContext, userSessionManagement, getErrorPage(deploymentInfo));
    }

    protected String getErrorPage(DeploymentInfo deploymentInfo) {
        LoginConfig loginConfig = deploymentInfo.getLoginConfig();
        String errorPage = null;
        if (loginConfig != null) {
            errorPage = loginConfig.getErrorPage();
        }
        return errorPage;
    }
}
