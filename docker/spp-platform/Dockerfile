FROM openjdk:11-jre

RUN mkdir /opt/sourceplusplus
WORKDIR /opt/sourceplusplus

RUN mkdir /opt/sourceplusplus/config
ADD ./config/spp-platform.yml /opt/sourceplusplus/config

ADD ./spp-platform-*.jar /opt/sourceplusplus

RUN curl -O -J -L https://github.com/sourceplusplus/interface-cli/releases/download/0.5.4/spp-cli-0.5.4-linux64.zip \
    && unzip spp-cli-*-linux64.zip \
    && chmod +x spp-cli \
    && mv spp-cli /usr/local/bin

ARG JAVA_OPTS
ENV JAVA_OPTS=$JAVA_OPTS

ENTRYPOINT java ${JAVA_OPTS} -jar /opt/sourceplusplus/spp-platform-*.jar
