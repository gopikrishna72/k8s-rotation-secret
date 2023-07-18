#!/bin/bash
set -xeuo pipefail

. utils.sh
printenv > /tmp/printenv_local.debug

main() {
  deployConjur
  ./run_with_summon.sh
}

deployConjur() {
  pushd ..
    git clone --single-branch --branch master \
        git@github.com:cyberark/kubernetes-conjur-deploy \
        kubernetes-conjur-deploy-$UNIQUE_TEST_ID

    cmd="./start"
    if [ $CONJUR_DEPLOYMENT = "oss" ]; then
        cmd="$cmd --oss"
    fi
    apt-get update && apt-get install -y google-cloud-sdk-gke-gcloud-auth-plugin
    cd kubernetes-conjur-deploy-$UNIQUE_TEST_ID && $cmd
  popd
}

main
