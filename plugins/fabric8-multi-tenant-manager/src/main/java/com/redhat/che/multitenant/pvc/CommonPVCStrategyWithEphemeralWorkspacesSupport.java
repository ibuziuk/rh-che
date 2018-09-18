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
package com.redhat.che.multitenant.pvc;

import static org.eclipse.che.workspace.infrastructure.kubernetes.namespace.KubernetesObjectUtil.newVolumeMount;
import static org.eclipse.che.workspace.infrastructure.kubernetes.provision.LogsVolumeMachineProvisioner.LOGS_VOLUME_NAME;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import java.util.Map;
import java.util.Map.Entry;
import javax.inject.Inject;
import javax.inject.Named;
import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.model.workspace.config.Volume;
import org.eclipse.che.api.core.model.workspace.runtime.RuntimeIdentity;
import org.eclipse.che.api.workspace.server.WorkspaceManager;
import org.eclipse.che.api.workspace.server.model.impl.WorkspaceImpl;
import org.eclipse.che.api.workspace.server.spi.InfrastructureException;
import org.eclipse.che.workspace.infrastructure.kubernetes.Names;
import org.eclipse.che.workspace.infrastructure.kubernetes.environment.KubernetesEnvironment;
import org.eclipse.che.workspace.infrastructure.kubernetes.namespace.KubernetesNamespaceFactory;
import org.eclipse.che.workspace.infrastructure.kubernetes.namespace.pvc.CommonPVCStrategy;
import org.eclipse.che.workspace.infrastructure.kubernetes.namespace.pvc.PVCSubPathHelper;

/**
 * Extends {@code CommonPVCStrategy} and provides support of the ephemeral workspaces based on the
 * `emptyDir` volumes. If workspace config contains `mountSources` attribute which is set to true,
 * than instead of using regular {@code CommonPVCStrategy}, each volume would be represented as an
 * `emptyDir`. NOTE: no data would be persisted once between ephemeral workspace restarts, since
 *
 * @see <a href="https://kubernetes.io/docs/concepts/storage/volumes/#emptydir">EmptyDir</a>
 * @author Ilya Buziuk
 */
public class CommonPVCStrategyWithEphemeralWorkspacesSupport extends CommonPVCStrategy {
  private static final String MOUNT_SOURCES_ATTRIBUTE = "mountSources";

  private final WorkspaceManager workspaceManager;
  private final String volumeNamePrefix;

  @Inject
  public CommonPVCStrategyWithEphemeralWorkspacesSupport(
      @Named("che.infra.kubernetes.pvc.name") String pvcName,
      @Named("che.infra.kubernetes.pvc.quantity") String pvcQuantity,
      @Named("che.infra.kubernetes.pvc.access_mode") String pvcAccessMode,
      @Named("che.infra.kubernetes.pvc.precreate_subpaths") boolean preCreateDirs,
      PVCSubPathHelper pvcSubPathHelper,
      KubernetesNamespaceFactory factory,
      WorkspaceManager workspaceManager) {
    super(pvcName, pvcQuantity, pvcAccessMode, preCreateDirs, pvcSubPathHelper, factory);
    this.workspaceManager = workspaceManager;
    this.volumeNamePrefix = pvcName;
  }

  @Override
  public void provision(KubernetesEnvironment k8sEnv, RuntimeIdentity identity)
      throws InfrastructureException {
    String workspaceId = identity.getWorkspaceId();
    if (isEphemeral(workspaceId)) {
      for (Pod pod : k8sEnv.getPods().values()) {
        final PodSpec podSpec = pod.getSpec();
        for (Container container : podSpec.getContainers()) {
          final String machineName = Names.machineName(pod, container);
          Map<String, Volume> volumes = k8sEnv.getMachines().get(machineName).getVolumes();
          addMachineVolumes(workspaceId, pod, container, volumes);
        }
      }
    } else {
      super.provision(k8sEnv, identity);
    }
  }

  @Override
  public void cleanup(String workspaceId) throws InfrastructureException {
    if (!isEphemeral(workspaceId)) {
      super.cleanup(workspaceId);
    }
  }

  @Override
  public void prepare(KubernetesEnvironment k8sEnv, String workspaceId)
      throws InfrastructureException {
    if (!isEphemeral(workspaceId)) {
      super.prepare(k8sEnv, workspaceId);
    }
  }

  private void addMachineVolumes(
      String workspaceId, Pod pod, Container container, Map<String, Volume> volumes) {
    if (volumes.isEmpty()) {
      return;
    }
    for (Entry<String, Volume> volumeEntry : volumes.entrySet()) {
      final String volumePath = volumeEntry.getValue().getPath();
      final String volumeName =
          LOGS_VOLUME_NAME.equals(volumeEntry.getKey())
              ? volumeEntry.getKey() + '-' + pod.getMetadata().getName()
              : volumeEntry.getKey();
      final String uniqueVolumeMountName = Names.generateName(volumeNamePrefix + '-');
      container
          .getVolumeMounts()
          .add(
              newVolumeMount(
                  uniqueVolumeMountName,
                  volumePath,
                  getSubPath(workspaceId, volumeName, Names.machineName(pod, container))));
      addEmptyDirVolumeIfAbsent(pod.getSpec(), uniqueVolumeMountName);
    }
  }

  private void addEmptyDirVolumeIfAbsent(PodSpec podSpec, String uniqueVolumeMountName) {
    if (podSpec
        .getVolumes()
        .stream()
        .noneMatch(volume -> volume.getName().equals(uniqueVolumeMountName))) {
      podSpec
          .getVolumes()
          .add(
              new VolumeBuilder()
                  .withName(uniqueVolumeMountName)
                  .withNewEmptyDir()
                  .endEmptyDir()
                  .build());
    }
  }

  private String getSubPath(String workspaceId, String volumeName, String machineName) {
    // logs must be located inside the folder related to the machine because
    // few machines can
    // contain the identical agents and in this case, a conflict is
    // possible.
    if (LOGS_VOLUME_NAME.equals(volumeName)) {
      return workspaceId + '/' + volumeName + '/' + machineName;
    }
    return workspaceId + '/' + volumeName;
  }

  /**
   * @param workspaceId
   * @return true if workspace config contains `mountSources` attribute which is set to true, false
   *     otherwise
   */
  private boolean isEphemeral(String workspaceId) {
    try {
      WorkspaceImpl workspace = workspaceManager.getWorkspace(workspaceId);
      String mountSources = workspace.getConfig().getAttributes().get(MOUNT_SOURCES_ATTRIBUTE);
      return !Boolean.parseBoolean(mountSources);
    } catch (NotFoundException | ServerException e) {
      throw new RuntimeException("Failed to load workspace info" + e.getMessage(), e);
    }
  }
}
