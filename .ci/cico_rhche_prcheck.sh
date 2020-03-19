#!/usr/bin/env bash
# Copyright (c) 2018 Red Hat, Inc.
# All rights reserved. This program and the accompanying materials
# are made available under the terms of the Eclipse Public License v1.0
# which accompanies this distribution, and is available at
# http://www.eclipse.org/legal/epl-v10.html

set +x
set -e

echo "****** Starting RH-Che PR check $(date) ******"
total_start_time=$(date +%s)
export PR_CHECK_BUILD="true"
export BASEDIR=$(pwd)
export DEV_CLUSTER_URL=https://api.che-dev.x6e0.p1.openshiftapps.com:6443/

eval "$(./env-toolkit load -f jenkins-env.json -r \
        ^DEVSHIFT_TAG_LEN$ \
        ^QUAY_ \
        ^KEYCLOAK \
        ^BUILD_NUMBER$ \
        ^JOB_NAME$ \
        ^ghprbPullId \
        ^RH_CHE \
        ^GIT_COMMIT)"

source ./config
source .ci/functional_tests_utils.sh

echo "Checking credentials:"
checkAllCreds

echo "Installing dependencies:"
start=$(date +%s)
installDependencies
stop=$(date +%s)
instal_dep_duration=$(($stop - $start))
echo "Installing all dependencies lasted $instal_dep_duration seconds."

export PROJECT_NAMESPACE=prcheck-${RH_PULL_REQUEST_ID}
export DOCKER_IMAGE_TAG="${RH_TAG_DIST_SUFFIX}"-"${RH_PULL_REQUEST_ID}"
getMavenVersion # Checking the maven version for debugging https://github.com/redhat-developer/rh-che/issues/1716
CHE_VERSION=$(getVersionFromPom)
export CHE_VERSION

echo "Running ${JOB_NAME} PR: #${RH_PULL_REQUEST_ID}, build number #${BUILD_NUMBER} for che-version:${CHE_VERSION}"
.ci/cico_build_deploy_test_rhche.sh

end_time=$(date +%s)
whole_check_duration=$(($end_time - $total_start_time))
echo "****** PR check ended at $(date) and whole run took $whole_check_duration seconds. ******"
