FROM openjdk:11-jre

RUN mkdir /opt/sourceplusplus
WORKDIR /opt/sourceplusplus

RUN mkdir /opt/sourceplusplus/config
ADD ./config/spp-platform.yml /opt/sourceplusplus/config

ADD ./spp-platform-*.jar /opt/sourceplusplus

ENTRYPOINT java -jar /opt/sourceplusplus/spp-platform-*.jar