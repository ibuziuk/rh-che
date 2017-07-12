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

import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.kubernetes.api.model.ObjectReference;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class ServiceAccountTokenProvider {
    private static final Logger LOG = LoggerFactory.getLogger(ServiceAccountTokenProvider.class);

    private String token;

    @PostConstruct
    public void init() {
        try (OpenShiftClient client = new DefaultOpenShiftClient()) {
            String token = client.getConfiguration().getOauthToken();
            this.token = "Wsagent " + token;
            LOG.info("Token from postconstructor block {}", token);
            String namespace = client.getConfiguration().getNamespace();
            LOG.info("Namespace {}", namespace);
//            ServiceAccount serviceAccount = client.serviceAccounts().inNamespace(namespace).withName("che").get();
//            LOG.info("ServiceAccount number of secrets {}", serviceAccount.getSecrets().size());
//            ObjectReference objectReference = serviceAccount.getSecrets().get(0);
//            LOG.info("Secret Name {}", objectReference.getName());
//            LOG.info("Secret field path {}", objectReference.getFieldPath());
//            LOG.info("Secret UID {}", objectReference.getUid());
//          LOG.info("Secret's field path", objectReference.getFieldPath());
        }
    }

    public String getToken() {
        return token;
    }
}
