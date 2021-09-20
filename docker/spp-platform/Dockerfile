FROM centos:8.3.2011

RUN mkdir /opt/sourceplusplus
WORKDIR /opt/sourceplusplus

RUN mkdir /opt/sourceplusplus/config
ADD ./config/spp-platform.yml /opt/sourceplusplus/config

ADD ./spp-platform /opt/sourceplusplus
ADD ./spp-cli /opt/sourceplusplus

RUN mkdir /opt/sourceplusplus/probe
ADD ./probe/spp-probe-*.jar /opt/sourceplusplus/probe

ENTRYPOINT ["/opt/sourceplusplus/spp-platform"]