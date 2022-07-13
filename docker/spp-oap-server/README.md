# Source++ Processor Docker image

## Running the Source++ Processor container

Start your container.

```
docker run -d --name=spp-oap-server sourceplusplus/spp-oap-server
```

## Environment

| Name                          | Required | Default Value | 
|:------------------------------|----------|---------------|
| SPP_PLATFORM_HOST             | yes      |               |
| SPP_PLATFORM_PORT             | yes      |               |
| SPP_HTTP_SSL_ENABLED          | no       | true          |
| SPP_PLATFORM_SSL_TRUST_ALL    | no       | false         |
| SPP_PLATFORM_CERTIFICATE      | no       |               |
| SPP_PLATFORM_CERTIFICATE_FILE | no       |               |

## How to use the container

Further documentation can be found at https://docs.sourceplusplus.com/installation/docker/.
