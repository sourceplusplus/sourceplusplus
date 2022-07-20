# Source++ Platform Docker image

## Running the Source++ Platform container

Start your container binding the external ports 11800 & 12800.

```
docker run -d --name=spp-platform -p 11800:11800 -p 12800:12800 sourceplusplus/spp-platform
```

## Environment

| Name                 | Required | Default Value | 
|:---------------------|----------|---------------|
| SPP_JWT_ENABLED      | no       | true          |
| SPP_HTTP_SSL_ENABLED | no       | true          |

## How to use the container

Further documentation can be found at https://docs.sourceplusplus.com/installation/docker/.
