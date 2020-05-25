#!/usr/bin/env bash

# Provides methods:
#   checkAllCreds
#   installDependencies
#   archiveArtifacts

source .ci/cico_utils.sh

echo "****** Starting functional tests $(date) ******"
start=$(date +%s)

function printHelp {
  YELLOW="\\033[93;1m"
  WHITE="\\033[0;1m"
  GREEN="\\033[32;1m"
  NC="\\033[0m" # No Color
  
  echo -e "${YELLOW}$(basename "$0") ${WHITE}[-u <username>] [-p <passwd>] [-r <url>]" 
  echo -e "\n${NC}Script for running functional tests against production or prod-preview environment."
  echo -e "${GREEN}where:${WHITE}"
  echo -e "-u    username for openshift account"
  echo -e "-p    password for openshift account"
  echo -e "-r    URL of Rh-che"
  echo -e "${NC}All paramters are mandatory.\n"
}

while getopts "hu:p:m:o:r:" opt; do
  case $opt in
    h) printHelp
      exit 0
      ;;
    u) export USERNAME=$OPTARG
      ;;
    p) export PASSWORD=$OPTARG
      ;;
    o) export OFFLINE_TOKEN=$OPTARG
      ;;
    r) export HOST_URL=$OPTARG
      ;;
    \?)
      echo "\"$opt\" is an invalid option!"
      exit 1
      ;;
    :)
      echo "Option \"$opt\" needs an argument."
      exit 1
      ;;
  esac
done

#install scl on demand, to prevent "scl: command not found" issue
installScl

#Get cluster to be able to get logs. Related to issue: https://github.com/redhat-developer/che-functional-tests/issues/476
if [[ "$USERNAME" == *"preview"* ]] || [[ "$PR_CHECK_BUILD" == "true" ]] || [[ "$JOB_NAME" == *"saas"* ]]; then
  API_SERVER_URL="https://api.prod-preview.openshift.io"
else
  API_SERVER_URL="https://api.openshift.io"
fi

events_file="events_report.txt"
touch $events_file

if [[ "$PR_CHECK_BUILD" == "true" || "$USE_CHE_LATEST_SNAPSHOT" == true ]]; then
  OC_CLUSTER_URL=$(curl -s -X GET --header 'Accept: application/json' "$API_SERVER_URL/api/users?filter\\[username\\]=$RH_CHE_AUTOMATION_CHE_PREVIEW_USERNAME" | jq '.data[0].attributes.cluster')
  OC_CLUSTER_URL="$(echo "${OC_CLUSTER_URL//\"/}")"
  oc login -u $RH_CHE_AUTOMATION_CHE_PREVIEW_USERNAME -p $RH_CHE_AUTOMATION_CHE_PREVIEW_PASSWORD $OC_CLUSTER_URL
  oc get events -n ${RH_CHE_AUTOMATION_CHE_PREVIEW_USERNAME}-che -w -o json > $events_file &
else
  OC_CLUSTER_URL=$(curl -s -X GET --header 'Accept: application/json' "$API_SERVER_URL/api/users?filter\\[username\\]=$USERNAME" | jq '.data[0].attributes.cluster')
  OC_CLUSTER_URL="$(echo "${OC_CLUSTER_URL//\"/}")"
  oc login -u $USERNAME -p $PASSWORD $OC_CLUSTER_URL
  oc get events -n ${USERNAME}-che -w -o json > $events_file &
fi

echo "API_SERVER_URL=$API_SERVER_URL"
echo "OPENSHIFT_URL=$OC_CLUSTER_URL"

#This format allows us to see username even if it is placed in Jenkins credential store. 
USERNAME_TO_PRINT="${USERNAME:0:3} ${USERNAME:3:${#USERNAME}}"
if [ -z "$USERNAME" ]; then
  USERNAME_TO_PRINT="${RH_CHE_AUTOMATION_CHE_PREVIEW_USERNAME:0:3} ${RH_CHE_AUTOMATION_CHE_PREVIEW_USERNAME:3:${#RH_CHE_AUTOMATION_CHE_PREVIEW_USERNAME}}"
fi
echo "User name printed in format: 3 first letters, space, the rest of letters.    $USERNAME_TO_PRINT"

set +e
#PR CHECK
if [[ "$PR_CHECK_BUILD" == "true" ]]; then
  HOST_URL=$(echo ${RH_CHE_AUTOMATION_SERVER_DEPLOYMENT_URL} | cut -d"/" -f 3)
  echo "Running test against developer cluster. URL: $HOST_URL"
  CHE_OSIO_AUTH_ENDPOINT="https://auth.prod-preview.openshift.io"
  path="$(pwd)"
  
  mkdir report

  #check version in rh-che pom.xml to use correct version of tests.
  getMavenVersion # Checking the maven version for debugging https://github.com/redhat-developer/rh-che/issues/1716
  version=$(getVersionFromPom)
  if [ -z "${version}" ]; then
    echo "[ERROR]: Could not find version in pom.xml."
    exit 1
  fi
  rhche_image="quay.io/openshiftio/rhchestage-rh-che-e2e-tests:${version}"

  #reuse image if exists or build new image for test
  docker pull $rhche_image > /dev/null 2>&1
  docker_pull_exit_code=$?

  if [[ $docker_pull_exit_code == 0 ]]; then
      echo "Rebuilding test image based on ${version} upstream version."
      docker build --build-arg TAG=${version} -t e2e_tests dockerfiles/e2e-saas
      rhche_image=e2e_tests
  else
    echo "Could not found RH-Che tests image with tag ${version}."
    if [ $(curl -X GET https://quay.io/api/v1/repository/eclipse/che-e2e/tag/${version}/images | jq .status) == null ]; then
      echo "Upstream image with tag ${version} found. Building own RH-Che image based on Che image with ${version} tag."
      docker build --build-arg TAG=${version} -t e2e_tests dockerfiles/e2e-saas
      rhche_image=e2e_tests
    else
      echo "Could not found Che test image with tag ${version}. Building own RH-Che image based on Che image with nightly tag."
      docker build --build-arg TAG=nightly -t e2e_tests dockerfiles/e2e-saas
      rhche_image=e2e_tests
    fi
  fi
  
  #increase timeout for load page to workaround https://github.com/redhat-developer/rh-che/issues/1604
  docker run \
     -v $path/report:/tmp/rh-che/local_tests/report:Z \
     -v $path/e2e-saas/:/tmp/rh-che/local_tests:Z \
     -e USERNAME=$RH_CHE_AUTOMATION_CHE_PREVIEW_USERNAME \
     -e PASSWORD=$RH_CHE_AUTOMATION_CHE_PREVIEW_PASSWORD \
     -e URL=https://$HOST_URL \
     -e TEST_SUITE=test-java-maven \
     -e TS_SELENIUM_LOAD_PAGE_TIMEOUT=180000 \
     --shm-size=256m \
  $rhche_image
  RESULT=$?
  
  mkdir -p ./rhche/${JOB_NAME}/${BUILD_NUMBER}/e2e_report
  cp -r ./report ./rhche/${JOB_NAME}/${BUILD_NUMBER}/e2e_report

else
  if [[ -z $USERNAME || -z $PASSWORD || -z $HOST_URL ]]; then
      echo "Please check if all credentials for user are set."
      exit 1
  fi
  
  #PRODUCTION
  if [[ "$HOST_URL" == "che.openshift.io" ]]; then
    getMavenVersion
    TAG=$(getVersionFromProd)
    echo "Running test with user $USERNAME against prod environment with version $TAG."

    path="$(pwd)"
    mkdir report
    
    #increase timeout for load page to workaround https://github.com/redhat-developer/rh-che/issues/1604
    if [[ "$JOB_NAME" == *"flaky"* ]]; then
      docker run \
        -v $path/report:/tmp/rh-che/e2e-saas/report:Z \
        -e USERNAME=$USERNAME \
        -e PASSWORD=$PASSWORD \
        -e URL=https://$HOST_URL \
        -e TEST_SUITE=test-java-maven \
        -e TS_SELENIUM_LOAD_PAGE_TIMEOUT=180000 \
        --shm-size=256m \
      quay.io/openshiftio/rhchestage-rh-che-e2e-tests:$TAG
      RESULT=$?
    else
      docker run \
        -v $path/report:/tmp/rh-che/e2e-saas/report:Z \
        -e USERNAME=$USERNAME \
        -e PASSWORD=$PASSWORD \
        -e URL=https://$HOST_URL \
        -e TEST_SUITE=test-java-maven \
        -e TS_SELENIUM_LOAD_PAGE_TIMEOUT=180000 \
        --shm-size=256m \
      quay.io/openshiftio/rhchestage-rh-che-e2e-tests:$TAG
      RESULT=$?
    fi
    
    mkdir -p ./rhche/${JOB_NAME}/${BUILD_NUMBER}/e2e_report
    cp -r ./report/ ./rhche/${JOB_NAME}/${BUILD_NUMBER}/e2e_report
    
  #PROD-PREVIEW
  else
    getMavenVersion
    TAG=$(getVersionFromProdPreview)
    echo "Running test with user $USERNAME against prod-preview environment with version $TAG."
  
    path="$(pwd)"
    mkdir report
    
    #increase timeout for load page to workaround https://github.com/redhat-developer/rh-che/issues/1604
    docker run \
      -v $path/report:/tmp/rh-che/e2e-saas/report:Z \
      -e USERNAME=$USERNAME \
      -e PASSWORD=$PASSWORD \
      -e URL=https://$HOST_URL \
      -e TEST_SUITE=test-java-maven \
      -e TS_SELENIUM_LOAD_PAGE_TIMEOUT=180000 \
      --shm-size=256m \
    quay.io/openshiftio/rhchestage-rh-che-e2e-tests:$TAG
    RESULT=$?
      
    mkdir -p ./rhche/${JOB_NAME}/${BUILD_NUMBER}/e2e_report
    cp -r ./report/ ./rhche/${JOB_NAME}/${BUILD_NUMBER}/e2e_report
  
  fi
fi
set -e

end=$(date +%s)
test_duration=$(($end - $start))
echo "Running functional tests lasted $test_duration seconds."

start=$(date +%s)
archiveArtifacts
end=$(date +%s)
archive_duration=$(($end - $start))
echo "Archiving artifacts lasted $archive_duration seconds."

if [[ $RESULT == 0 ]]; then
  echo "Tests result: SUCCESS"
else
  echo "Tests result: FAILURE"
fi

exit $RESULT	
