#!/bin/bash
# gen-openvpn-certs.sh

PROTODIR=/media/tim/ExtraDrive1/Projects/009-SecureKeyAndCertRotation/deploy

# Create certificates directory
mkdir -p $PROTODIR/gen/openvpn-certs
cd $PROTODIR/gen/openvpn-certs

# Generate CA
openssl genrsa -out ca.key 4096
openssl req -new -x509 -days 3650 -key ca.key -out ca.crt -subj "/C=US/ST=CA/L=SF/O=MyOrg/CN=OpenVPN-CA"

# Generate server certificate
openssl genrsa -out server.key 4096
openssl req -new -key server.key -out server.csr -subj "/C=US/ST=CA/L=SF/O=MyOrg/CN=openvpn-server"
openssl x509 -req -days 3650 -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out server.crt

# Generate client certificate
openssl genrsa -out client.key 4096
openssl req -new -key client.key -out client.csr -subj "/C=US/ST=CA/L=SF/O=MyOrg/CN=openvpn-client"
openssl x509 -req -days 3650 -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out client.crt

# Generate Diffie-Hellman parameters
openssl dhparam -out dh2048.pem 2048

# Generate TLS auth key
openvpn --genkey --secret ta.key

echo "Certificates generated successfully!"
