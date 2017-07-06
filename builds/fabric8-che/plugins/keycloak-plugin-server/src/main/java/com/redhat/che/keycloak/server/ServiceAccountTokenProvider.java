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

import javax.annotation.PostConstruct;
import javax.inject.Singleton;

import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;

@Singleton
public class ServiceAccountTokenProvider {
    private String token;

    @PostConstruct
    public void init() {
        try (OpenShiftClient client = new DefaultOpenShiftClient()) {
            this.token = client.getConfiguration().getOauthToken();
        }
    }

    public String getToken() {
        return token;
    }
}
