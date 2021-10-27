FROM centos:8.3.2011

RUN mkdir /opt/sourceplusplus
WORKDIR /opt/sourceplusplus

RUN mkdir /opt/sourceplusplus/config
ADD ./config/spp-platform.yml /opt/sourceplusplus/config

ADD ./spp-platform /opt/sourceplusplus
ADD ./spp-cli /opt/sourceplusplus

ENTRYPOINT ["/opt/sourceplusplus/spp-platform"]