# Source++ Platform Docker image

## Running the Source++ Platform container

Start your container binding the external port 5445.

```
docker run -d --name=spp-platform -p 5445:5445 sourceplusplus/spp-platform
```

## Environment

SPP_DISABLE_JWT
SPP_DISABLE_TLS
SPP_CLUSTER_URL
SPP_CLUSTER_NAME

## How to use the container

Further documentation can be found at https://docs.sourceplusplus.com/installation/docker/.
