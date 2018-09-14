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

import static org.eclipse.che.workspace.infrastructure.kubernetes.Constants.CHE_VOLUME_NAME_LABEL;
import static org.eclipse.che.workspace.infrastructure.kubernetes.Constants.CHE_WORKSPACE_ID_LABEL;
import static org.eclipse.che.workspace.infrastructure.kubernetes.namespace.KubernetesObjectUtil.newPVC;
import static org.eclipse.che.workspace.infrastructure.kubernetes.namespace.KubernetesObjectUtil.newVolumeMount;
import static org.eclipse.che.workspace.infrastructure.kubernetes.namespace.KubernetesObjectUtil.putLabel;
import static org.eclipse.che.workspace.infrastructure.kubernetes.provision.LogsVolumeMachineProvisioner.LOGS_VOLUME_NAME;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import javax.inject.Inject;
import javax.inject.Named;
import org.eclipse.che.api.core.model.workspace.config.Volume;
import org.eclipse.che.api.core.model.workspace.runtime.RuntimeIdentity;
import org.eclipse.che.api.workspace.server.spi.InfrastructureException;
import org.eclipse.che.workspace.infrastructure.kubernetes.Names;
import org.eclipse.che.workspace.infrastructure.kubernetes.environment.KubernetesEnvironment;
import org.eclipse.che.workspace.infrastructure.kubernetes.namespace.KubernetesNamespaceFactory;
import org.eclipse.che.workspace.infrastructure.kubernetes.namespace.pvc.WorkspaceVolumesStrategy;

public class EmptyDirVolumeStrategy implements WorkspaceVolumesStrategy {

  public static final String EMPTY_DIR_STRATEGY = "emptyDir";

  private final String pvcNamePrefix;
  private final String pvcQuantity;
  private final String pvcAccessMode;
  private final KubernetesNamespaceFactory factory;

  @Inject
  public EmptyDirVolumeStrategy(
      @Named("che.infra.kubernetes.pvc.name") String pvcNamePrefix,
      @Named("che.infra.kubernetes.pvc.quantity") String pvcQuantity,
      @Named("che.infra.kubernetes.pvc.access_mode") String pvcAccessMode,
      KubernetesNamespaceFactory factory) {
    this.pvcNamePrefix = pvcNamePrefix;
    this.pvcQuantity = pvcQuantity;
    this.pvcAccessMode = pvcAccessMode;
    this.factory = factory;
  }

  @Override
  public void provision(KubernetesEnvironment k8sEnv, RuntimeIdentity identity)
      throws InfrastructureException {
    final Map<String, PersistentVolumeClaim> claims = k8sEnv.getPersistentVolumeClaims();
    final String workspaceId = identity.getWorkspaceId();
    // fetches all existing PVCs related to given workspace and groups them by volume name
    final Map<String, PersistentVolumeClaim> volumeName2PVC =
        groupByVolumeName(
            factory
                .create(workspaceId)
                .persistentVolumeClaims()
                .getByLabel(CHE_WORKSPACE_ID_LABEL, workspaceId));
    for (Pod pod : k8sEnv.getPods().values()) {
      final PodSpec podSpec = pod.getSpec();
      for (Container container : podSpec.getContainers()) {
        final String machineName = Names.machineName(pod, container);
        Map<String, Volume> volumes = k8sEnv.getMachines().get(machineName).getVolumes();
        addMachineVolumes(workspaceId, claims, volumeName2PVC, pod, container, volumes);
      }
    }
  }

  @Override
  public void prepare(KubernetesEnvironment k8sEnv, String workspaceId)
      throws InfrastructureException {}

  private void addMachineVolumes(
      String workspaceId,
      Map<String, PersistentVolumeClaim> provisionedClaims,
      Map<String, PersistentVolumeClaim> existingVolumeName2PVC,
      Pod pod,
      Container container,
      Map<String, Volume> volumes)
      throws InfrastructureException {
    if (volumes.isEmpty()) {
      return;
    }
    final Map<String, PersistentVolumeClaim> provisionedVolumeName2PVC =
        groupByVolumeName(provisionedClaims.values());

    for (Entry<String, Volume> volumeEntry : volumes.entrySet()) {
      final String volumePath = volumeEntry.getValue().getPath();
      final String volumeName =
          LOGS_VOLUME_NAME.equals(volumeEntry.getKey())
              ? volumeEntry.getKey() + '-' + pod.getMetadata().getName()
              : volumeEntry.getKey();
      final PersistentVolumeClaim pvc;
      // checks whether PVC for given workspace and volume exists on remote
      if (existingVolumeName2PVC.containsKey(volumeName)) {
        pvc = existingVolumeName2PVC.get(volumeName);
      }
      // checks whether PVC for given volume provisioned previously
      else if (provisionedVolumeName2PVC.containsKey(volumeName)) {
        pvc = provisionedVolumeName2PVC.get(volumeName);
      }
      // when no existing and provisioned PVC found then create new one
      else {
        final String uniqueName = Names.generateName(pvcNamePrefix + '-');
        pvc = newPVC(uniqueName, pvcAccessMode, pvcQuantity);
        putLabel(pvc, CHE_WORKSPACE_ID_LABEL, workspaceId);
        putLabel(pvc, CHE_VOLUME_NAME_LABEL, volumeName);
        provisionedClaims.put(uniqueName, pvc);
      }

      // binds pvc to pod and container
      container
          .getVolumeMounts()
          .add(
              newVolumeMount(
                  pvc.getMetadata().getName(),
                  volumePath,
                  getSubPath(workspaceId, volumeName, Names.machineName(pod, container))));
      addVolumeIfAbsent(pod.getSpec(), pvc.getMetadata().getName());
    }
  }

  @Override
  public void cleanup(String workspaceId) throws InfrastructureException {}

  private void addVolumeIfAbsent(PodSpec podSpec, String pvcUniqueName) {
    if (podSpec.getVolumes().stream().noneMatch(volume -> volume.getName().equals(pvcUniqueName))) {
      podSpec
          .getVolumes()
          .add(new VolumeBuilder().withName(pvcUniqueName).withNewEmptyDir().endEmptyDir().build());
    }
  }

  private String getSubPath(String workspaceId, String volumeName, String machineName) {
    // logs must be located inside the folder related to the machine because few machines can
    // contain the identical agents and in this case, a conflict is possible.
    if (LOGS_VOLUME_NAME.equals(volumeName)) {
      return workspaceId + '/' + volumeName + '/' + machineName;
    }
    return workspaceId + '/' + volumeName;
  }

  /** Groups list of given PVCs by volume name */
  private Map<String, PersistentVolumeClaim> groupByVolumeName(
      Collection<PersistentVolumeClaim> pvcs) throws InfrastructureException {
    final Map<String, PersistentVolumeClaim> grouped = new HashMap<>();
    for (PersistentVolumeClaim pvc : pvcs) {
      final ObjectMeta metadata = pvc.getMetadata();
      final String volumeName;
      if (metadata != null
          && metadata.getLabels() != null
          && (volumeName = metadata.getLabels().get(CHE_VOLUME_NAME_LABEL)) != null) {
        grouped.put(volumeName, pvc);
      }
    }
    return grouped;
  }
}
