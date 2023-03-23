FROM apache/skywalking-oap-server:v9.4.0

#Replace JDK 11 with JDK 17
RUN rm -rf /opt/java/openjdk
RUN apt-get update && apt-get install -y openjdk-17-jdk
RUN ln -s /usr/lib/jvm/java-17-openjdk-amd64 /opt/java/openjdk

ADD ./config/spp-platform.yml /skywalking/config/

RUN rm /skywalking/config/log4j2.xml
ADD ./log4j2.xml /skywalking/config/log4j2.xml

ADD ./spp-platform-*.jar /skywalking/oap-libs/
ADD ./spp-live-*.jar /skywalking/oap-libs/

RUN printf "\nspp-platform-core:\n  selector: \${SPP_PLATFORM_CORE:default}\n  default:\n" \
    >> /skywalking/config/application.yml

RUN printf "\nspp-platform-storage:\n  selector: \${SPP_PLATFORM_STORAGE:default}\n  default:\n" \
    >> /skywalking/config/application.yml

RUN printf "\nspp-platform-bridge:\n  selector: \${SPP_PLATFORM_BRIDGE:default}\n  default:\n" \
    >> /skywalking/config/application.yml

RUN printf "\nspp-live-instrument:\n  selector: \${SPP_LIVE_INSTRUMENT:default}\n  default:\n" \
    >> /skywalking/config/application.yml

RUN printf "\nexporter:\n  selector: \${SPP_LIVE_VIEW:default}\n  default:\n" \
    >> /skywalking/config/application.yml

ENV SW_CORE_REST_PORT=12801
ENV SW_RECEIVER_AWS_FIREHOSE_HTTP_PORT=12802
ENV SW_SEARCHABLE_TAG_KEYS="spp.instrument_id,spp.instrument_type,http.method,http.status_code,rpc.status_code,db.type,db.instance,mq.queue,mq.topic,mq.broker"
