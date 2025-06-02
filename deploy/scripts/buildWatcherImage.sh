#!/bin/bash

# Load image to minikube when built
eval $(minikube docker-env)

PROTODIR=/media/tim/ExtraDrive1/Projects/009-SecureKeyAndCertRotation/deploy

cd $PROTODIR/docker

cp $PROTODIR/../pulsarWatcher/target/watcher-0.1.jar ./watcher-0.1.jar

docker build -t library/watcher:1.0 -f WatcherDockerFile .

rm watcher-0.1.jar

cd ../scripts
