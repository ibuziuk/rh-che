/*******************************************************************************
 * Copyright (c) 2017 Red Hat inc.

 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat - Initial Contribution
 *******************************************************************************/
package com.redhat.che.keycloak.server;

import java.io.IOException;

import javax.inject.Inject;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;

public class KeycloakAuthenticationFilter extends org.keycloak.adapters.servlet.KeycloakOIDCFilter {
    
    private static final Logger LOG = LoggerFactory.getLogger(KeycloakAuthenticationFilter.class);

    @Inject
    ServiceAccountTokenProvider serviceAccountTokenProvider;

    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        String authHeader = request.getHeader("Authorization");
        String requestURI = request.getRequestURI();
        String requestScheme = req.getScheme();

        if (authHeader == null) {
            LOG.debug("No 'Authorization' header for {}", requestURI);
        }

        if (isSystemStateRequest(requestURI) || isWebsocketRequest(requestURI, requestScheme)
                || isInternalRequest(authHeader)) {
            LOG.debug("Skipping {}", requestURI);
            chain.doFilter(req, res);
        } else {
            super.doFilter(req, res, chain);
            LOG.debug("{} status : {}", request.getRequestURL(), ((HttpServletResponse) res).getStatus());
        }
    }

    /**
     * @param requestURI
     * @return true if request is made against system state endpoint which is
     *         used in OpenShift liveness & readiness probes, false otherwise
     */
    private boolean isSystemStateRequest(String requestURI) {
        return requestURI.endsWith("/api/system/state");
    }

    /**
     * @param authHeader
     * @return true if 'Authorization' header contains valid service account token, false otherwise
     */
    private boolean isInternalRequest(String authHeader) {
        LOG.info("Header {}", authHeader);
        LOG.info("Token {}", serviceAccountTokenProvider.getToken());
        LOG.info("Equals {}", serviceAccountTokenProvider.getToken().equals(authHeader));
        if (authHeader.startsWith("Wsagent")) {
            LOG.info("Wsagent validation");
            String token = authHeader.replaceFirst("Wsagent ", "");
            Config config = new ConfigBuilder().withOauthToken(token).build();
            try (OpenShiftClient client = new DefaultOpenShiftClient(config)) {
                String namespace = client.getConfiguration().getNamespace();
                LOG.info("Internal Namespace {}", namespace);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
//        return serviceAccountTokenProvider.getToken().equals(authHeader);
    }

    private boolean isWebsocketRequest(String requestURI, String requestScheme) {
        return requestURI.endsWith("/ws") || requestURI.endsWith("/eventbus") || requestScheme.equals("ws")
                || requestScheme.equals("wss") || requestURI.contains("/websocket/");
    }

}
