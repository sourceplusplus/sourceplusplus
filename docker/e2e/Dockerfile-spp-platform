FROM openjdk:11-jre

RUN mkdir /opt/sourceplusplus
WORKDIR /opt/sourceplusplus

RUN mkdir /opt/sourceplusplus/config
ADD ./config /opt/sourceplusplus/config

ADD ./spp-platform-*.jar /opt/sourceplusplus

RUN mkdir -p /tmp/spp-probe/ca
ADD ./config/spp-platform.crt /tmp/spp-probe/ca/ca.crt

ADD ./spp-probe.yml /tmp/spp-probe
ADD ./spp-probe-*.jar /tmp/spp-probe/spp-probe.jar

ENV SPP_DELETE_PROBE_DIRECTORY_ON_BOOT=false

ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS

CMD java ${JAVA_OPTS} -jar /opt/sourceplusplus/spp-platform-*.jar