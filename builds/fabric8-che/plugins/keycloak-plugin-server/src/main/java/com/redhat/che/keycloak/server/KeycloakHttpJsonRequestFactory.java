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

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.validation.constraints.NotNull;

import org.eclipse.che.api.core.rest.DefaultHttpJsonRequestFactory;
import org.eclipse.che.api.core.rest.HttpJsonRequest;
import org.eclipse.che.api.core.rest.shared.dto.Link;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class KeycloakHttpJsonRequestFactory extends DefaultHttpJsonRequestFactory {
    private static final Logger LOG = LoggerFactory.getLogger(KeycloakHttpJsonRequestFactory.class);

    @Inject
    ServiceAccountTokenProvider serviceAccountTokenProvider;

    @Override
    public HttpJsonRequest fromUrl(@NotNull String url) {
        LOG.debug("setAuthorizationHeader for {}", url);
        String token = serviceAccountTokenProvider.getToken();
        return super.fromUrl(url).setAuthorizationHeader(token); 
    }

    @Override
    public HttpJsonRequest fromLink(@NotNull Link link) {
        LOG.debug("setAuthorizationHeader for {}", link);
        String token = serviceAccountTokenProvider.getToken();
        return super.fromLink(link).setAuthorizationHeader(token);
    }

}