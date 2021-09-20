# Source++ Processor Docker image

## Running the Source++ Processor container

Start your container binding the external port 5445.

```
docker run -d --name=spp-oap-server sourceplusplus/spp-oap-server
```

## Environment

SPP_PLATFORM_HOST
SPP_PLATFORM_PORT
SPP_DISABLE_TLS
SPP_PLATFORM_SSL_TRUST_ALL
SPP_PLATFORM_CERTIFICATE
SPP_PLATFORM_CERTIFICATE_FILE

## How to use the container

Further documentation can be found at https://docs.sourceplusplus.com/installation/docker/.
