/*
 * Copyright (c) 2016-2018 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.che.multitenant;

import static com.redhat.che.multitenant.pvc.NoneWorkspacePVCStrategy.NONE_STRATEGY;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.MapBinder;
import com.redhat.che.multitenant.pvc.NoneWorkspacePVCStrategy;
import org.eclipse.che.inject.DynaModule;
import org.eclipse.che.workspace.infrastructure.kubernetes.KubernetesClientFactory;
import org.eclipse.che.workspace.infrastructure.kubernetes.namespace.pvc.WorkspaceVolumesStrategy;
import org.eclipse.che.workspace.infrastructure.openshift.OpenShiftClientFactory;
import org.eclipse.che.workspace.infrastructure.openshift.OpenShiftEnvironmentProvisioner;
import org.eclipse.che.workspace.infrastructure.openshift.project.OpenShiftProjectFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@DynaModule
public class Fabric8MultiTenantModule extends AbstractModule {

  private static final Logger LOGGER = LoggerFactory.getLogger(Fabric8MultiTenantModule.class);

  @Override
  protected void configure() {
    LOGGER.info("Configuring {}", this.getClass().getName());

    bind(OpenShiftClientFactory.class).to(Fabric8OpenShiftClientFactory.class);
    bind(KubernetesClientFactory.class).to(Fabric8OpenShiftClientFactory.class);
    bind(OpenShiftProjectFactory.class).to(Fabric8OpenShiftProjectFactory.class);
    bind(OpenShiftEnvironmentProvisioner.class).to(RhCheInfraEnvironmentProvisioner.class);

    MapBinder<String, WorkspaceVolumesStrategy> volumesStrategies =
        MapBinder.newMapBinder(binder(), String.class, WorkspaceVolumesStrategy.class);
    volumesStrategies.addBinding(NONE_STRATEGY).to(NoneWorkspacePVCStrategy.class);
  }
}
