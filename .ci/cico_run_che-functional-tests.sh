#!/usr/bin/env bash
# Copyright (c) 2018 Red Hat, Inc.
# All rights reserved. This program and the accompanying materials
# are made available under the terms of the Eclipse Public License v1.0
# which accompanies this distribution, and is available at
# http://www.eclipse.org/legal/epl-v10.html

set -x
set -e
set +o nounset

/usr/sbin/setenforce 0

yum install --assumeyes git

echo "Downloading che-functional-tests repo"

#git clone git@github.com:redhat-developer/che-functional-tests.git
git -c http.sslVerify=false clone https://github.com/ScrewTSW/che-functional-tests.git
cd ./che-functional-tests
git checkout 193-add-custom-che-server-url

echo "Downloading done."
echo "Running functional tests against ${RH_CHE_AUTOMATION_SERVER_DEPLOYMENT_URL}"

./cico/cico_run_EE_tests.sh ./cico/config_rh_che_automated