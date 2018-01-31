/*
 * Copyright (c) 2018 Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.che.multitenant;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UserCheTenantDataValidator {
  private static final Logger LOG = LoggerFactory.getLogger(UserCheTenantDataValidator.class);

  private UserCheTenantDataValidator() {}

  public static void validate(final UserCheTenantData data) {
    if (data == null) {
      LOG.error("'UserCheTenantData' can not be null");
      throw new NullPointerException("'UserCheTenantData' can not be null");
    } else if (StringUtils.isBlank(data.getClusterUrl())) {
      LOG.error("'ClusterUrl' can not be blank: {}", data);
      throw new IllegalArgumentException("'ClusterUrl' can not be blank");
    } else if (StringUtils.isBlank(data.getRouteBaseSuffix())) {
      LOG.error("'RouteBaseSuffix' can not be blank: {}", data);
      throw new IllegalArgumentException("'RouteBaseSuffix' can not be blank");
    } else if (StringUtils.isBlank(data.getNamespace())) {
      LOG.error("'Namespace' can not be blank: {}", data);
      throw new IllegalArgumentException("'Namespace' can not be blank");
    }
  }
}
