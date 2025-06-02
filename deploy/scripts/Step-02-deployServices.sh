#!/bin/bash

# The following scripting is based upon a Pulsar tutorial for running Pulsar in Kubernetes on
# minikube. The url for the tuturial is:
# https://pulsar.apache.org/docs/4.0.x/getting-started-helm/

# Step 1 - Deploys and configures the following:
#            a) Deploys a fresh minikube with minikube addons (dashboard, metallb);
#            c) Configures Metallb loadbalancer
#            d) Deploys Kubernetes Gateway API CRDs (cert-manager deploy uses)
#            e) Deploys istio in Ambient mode.
#            f) Deploys Cert-Manager
#            g) Deploys Pulsar and all required components into the Cluster
#            h) Sets pulsar namespace to istio ambient mode which initiates mTLS between pods
#            f) Tests access from the Pulsar CLI client
#            g) Allows running of the simple java test program (eclipse, maven) found in the
#               pulsar-client directory within this project.

# This learning prototypes were developed and tested using the following:
#   a) Ubuntu                 - 20.04.6 LTS
#   b) Minikube               - 1.34.0
#   c) Kubernetes             - 1.31.0
#   d) Docker                 - 27.2.0
#   e) Metallb                - 0.9.6
#   f) Kubernetes Gateway API - 1.2.0
#   g) Istio (Ambient Mode)   - 1.23.2
#   h) Cert-Manager           - 1.15.5
#   i) Machine config - Processor - Intel® Core™ i7-7700K CPU @ 4.20GHz × 8 
#                       Memory    - 64 GB
#  
###########################################################################################         
# Open a new terminal 3
# Delete prior minikube ( if used and configured prior)
minikube delete -p services

# Start minikube - configure the settings to your requirements and hardware
# Note - normally I use kvm2 as the vm-driver. However istio cni in ambient mode does not
# currently work with kvm2 due to cni incompatibility. The work around is to use the 
# docker vm-driver.
minikube start -p services --cpus 3 --memory 12288 --vm-driver docker --cni kindnet --disk-size 100g

# Not really necessary as the minikube start configures kubectl to the servers instance.
minikube profile services

# Addons
minikube addons enable dashboard

# Deploy the addon loadbalancer metallb
minikube addons enable metallb

# Configure loadbalancer ip address range within the same range as the minikube ip
# The configuration is a start ip ( ie. 192.168.49.20 ) and an end ip that makes a 
# range of 10 ip addresses. The range should not overlap the minikube ip
minikube ip
minikube addons configure metallb
  -- Enter Load Balancer Start IP: 
  -- Enter Load Balancer End IP:

# Start dashboard
minikube dashboard

############## Open up a new (4th) terminal ###################################
# Install the Kubernetes Gateway API CRDs (experimental also includes standard)
kubectl apply -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.2.0/experimental-install.yaml

# Install istio in ambient mode
istioctl install --set values.pilot.env.PILOT_ENABLE_ALPHA_GATEWAY_API=true --set profile=ambient --skip-confirmation

#### Install cert-manager with the following steps ####
# Create cert-manger namespace
kubectl create namespace cert-manager

# Deploy cert-manager gateway CRDs
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.15.5/cert-manager.crds.yaml

# Deploy cert-manager with gateway api enabled including the experimental gateway apis
helm install cert-manager --version 1.15 jetstack/cert-manager --namespace cert-manager \
  --set config.apiVersion="controller.config.cert-manager.io/v1alpha1" \
  --set config.kind="ControllerConfiguration" \
  --set config.enableGatewayAPI=true \
  --set "extraArgs={--feature-gates=ExperimentalGatewayAPISupport=true}"

# Project Directory
PROTODIR=/media/tim/ExtraDrive1/Projects/009-SecureKeyAndCertRotation/deploy
SCHEMADIR=/media/tim/ExtraDrive1/Projects/009-SecureKeyAndCertRotation/service-core/src/main/resources/avro

cat > ${PROTODIR}/kube-core/avro-schema.yaml <<EOF
apiVersion: v1
kind: ConfigMap
metadata:
  name: "avro-schema-config"
data:
  --from-file=$SCHEMADIR/KyberExchangeMessage.avsc
EOF

kubectl create -n metadata configmap avro-schemas --from-file=$SCHEMADIR/
##################################################################
# Start a separate terminal - for servers cluster and create a port-forward
minikube profile servers

kubectl port-forward -n pulsar svc/pulsar-proxy 6651:6651 --address=0.0.0.0

## End - Return to prior terminal
# Switch to Servers cluster to obtain the pulsar proxy tls certs.
minikube profile servers

kubectl get secret pulsar-tls-proxy -n pulsar -o "jsonpath={.data['ca\.crt']}" | base64 -d > $PROTODIR/gen/ca.crt
kubectl get secret pulsar-tls-proxy -n pulsar -o "jsonpath={.data['tls\.crt']}" | base64 -d > $PROTODIR/gen/tls.crt

#PROXY_HOST=$(kubectl get svc -n pulsar pulsar-proxy --output jsonpath='{.status.loadBalancer.ingress[0].ip}')
#echo $PROXY_HOST

# Generate the pulsar-proxy url to be used by the metadata service client. As minikube clusters
# are isolated the services cluster will know nothing about the pulsar-proxy loadbalancer 
# external ip. However the cluster does know about its host ip address and can reach it. Thus
# with the port-forward above the client will route first to the host which tehn forwards it to
# the external ip address of the pulsar-proxy service.
HOST_IP=$(hostname -I | cut -f1 -d' ')
PROXY_URL="pulsar+ssl://$HOST_IP:6651"
echo $PROXY_URL

# Switch back to services cluster
minikube profile services

/bin/bash $PROTODIR/scripts/buildMetadataImage.sh

kubectl create namespace metadata

kubectl create secret generic -n metadata pulsar-client-tls --from-file=$PROTODIR/gen/tls.crt --from-file=$PROTODIR/gen/ca.crt

cat > ${PROTODIR}/kube-metadata/metadata-configmap.yaml <<EOF
apiVersion: v1
kind: ConfigMap
metadata:
  name: "metadata-svc-config"
data:
  kubeClusterName: "services"
  pulsarUseTLS:    "true"
  pulsarUrl:       $PROXY_URL
  tlsSecret:      "pulsar-client-tls"   # Must match secret name which contains ca.crt and ca.crt
  caCertFilePath:  "/app/certs/ca.crt"    # Must match volume mount
EOF

kubectl -n metadata apply -f $PROTODIR/kube-metadata/metadata-configmap.yaml
kubectl -n metadata apply -f $PROTODIR/kube-metadata/metadata-pvc.yaml
kubectl -n metadata apply -f $PROTODIR/kube-metadata/metadata-sa.yaml
kubectl -n metadata apply -f $PROTODIR/kube-metadata/metadata-rbac.yaml
kubectl -n metadata apply -f $PROTODIR/kube-metadata/metadata.yaml
#kubectl -n metadata apply -f $PROTODIR/kube-metadata/metadata-service.yaml

# Deploy Watcher

# Switch to servers cluster
minikube profile servers

kubectl create -n pulsar configmap avro-schemas --from-file=$SCHEMADIR/
#kubectl create -n pulsar configmap avro-schemas --from-file=$SCHEMADIR/KyberExchangeMessage.avsc

# We need to build the docker file image.
/bin/bash $PROTODIR/scripts/buildWatcherImage.sh

kubectl -n pulsar apply -f $PROTODIR/kube-watch/watcher-sa.yaml
#kubectl -n pulsar apply -f $PROTODIR/kube-watch/watcher-networkpolicy.yaml
kubectl -n pulsar apply -f $PROTODIR/kube-watch/watcher-configmap.yaml
kubectl -n pulsar apply -f $PROTODIR/kube-watch/watcher-rbac.yaml
kubectl -n pulsar apply -f $PROTODIR/kube-watch/watcher.yaml
kubectl -n pulsar apply -f $PROTODIR/kube-watch/watcher-service.yaml

