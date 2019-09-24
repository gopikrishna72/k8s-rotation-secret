#!/bin/bash -eu

. utils.sh

set_namespace $TEST_APP_NAMESPACE_NAME

echo "Publish docker image"
docker tag "cyberark-secrets-provider-for-k8s:dev" "${DOCKER_REGISTRY_PATH}/${TEST_APP_NAMESPACE_NAME}/secrets-provider"
docker push "${DOCKER_REGISTRY_PATH}/${TEST_APP_NAMESPACE_NAME}/secrets-provider"

echo "Enable image pull"
$cli delete secret dockerpullsecret --ignore-not-found=true
# TODO: replace the following with `oc create secret`
$cli secrets new-dockercfg dockerpullsecret \
      --docker-server=${DOCKER_REGISTRY_PATH} \
      --docker-username=_ \
      --docker-password=$($cli whoami -t) \
      --docker-email=_
$cli secrets add serviceaccount/default secrets/dockerpullsecret --for=pull

readonly K8S_CONFIG_DIR="k8s-config"

$cli delete clusterrole secrets-access --ignore-not-found=true
$cli create -f $K8S_CONFIG_DIR/secrets-access-role.yml

./$K8S_CONFIG_DIR/secrets-access-role-binding.yml.sh | $cli create -f -

./$K8S_CONFIG_DIR/test-env.yml.sh > ./$K8S_CONFIG_DIR/generated/test-env.yml
$cli exec "$($cli get pods --namespace $CONJUR_NAMESPACE_NAME | grep conjur-cluster -m 1 |  awk '{print $1}')" --namespace $CONJUR_NAMESPACE_NAME cat /opt/conjur/etc/ssl/conjur-master.pem  | while read i; do printf "    %19s\n" "$i"; done  >> $K8S_CONFIG_DIR/generated/test-env.yml
$cli create -f $K8S_CONFIG_DIR/generated/test-env.yml

$cli create -f $K8S_CONFIG_DIR/k8s-secret.yml

