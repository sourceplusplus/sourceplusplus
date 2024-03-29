FROM apache/skywalking-oap-server:9.6.0-java17

#Install git
RUN apt-get update && apt-get install -y git

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

RUN printf "\nspp-live-insight:\n  selector: \${SPP_LIVE_INSIGHT:default}\n  default:\n" \
    >> /skywalking/config/application.yml

RUN printf "\nspp-live-instrument:\n  selector: \${SPP_LIVE_INSTRUMENT:default}\n  default:\n" \
    >> /skywalking/config/application.yml

RUN printf "\nexporter:\n  selector: \${SPP_LIVE_VIEW:default}\n  default:\n" \
    >> /skywalking/config/application.yml

# replace default receiver-meter with spp-receiver-meter
RUN sed -i -z 's/receiver-meter:\n  selector: \${SW_RECEIVER_METER:default\}\n  default:/receiver-meter:\n  selector: \${SW_RECEIVER_METER:spp-receiver-meter}\n  spp-receiver-meter:/' /skywalking/config/application.yml

# replace default receiver-trace with spp-receiver-trace
RUN sed -i -z 's/receiver-trace:\n  selector: \${SW_RECEIVER_TRACE:default\}\n  default:/receiver-trace:\n  selector: \${SW_RECEIVER_TRACE:spp-receiver-trace}\n  spp-receiver-trace:/' /skywalking/config/application.yml

# replace default event-analyzer with spp-event-analyzer
RUN sed -i -z 's/event-analyzer:\n  selector: \${SW_EVENT_ANALYZER:default}\n  default:/event-analyzer:\n  selector: \${SW_EVENT_ANALYZER:spp-event-analyzer}\n  spp-event-analyzer:/' /skywalking/config/application.yml

# replace default receiver-jvm with spp-receiver-jvm
RUN sed -i -z 's/receiver-jvm:\n  selector: \${SW_RECEIVER_JVM:default\}\n  default:/receiver-jvm:\n  selector: \${SW_RECEIVER_JVM:spp-receiver-jvm}\n  spp-receiver-jvm:/' /skywalking/config/application.yml

# replace default receiver-log with spp-receiver-log
RUN sed -i -z 's/receiver-log:\n  selector: \${SW_RECEIVER_LOG:default\}\n  default:/receiver-log:\n  selector: \${SW_RECEIVER_LOG:spp-receiver-log}\n  spp-receiver-log:/' /skywalking/config/application.yml

# replace default receiver-register with spp-receiver-register
RUN sed -i -z 's/receiver-register:\n  selector: \${SW_RECEIVER_REGISTER:default\}\n  default:/receiver-register:\n  selector: \${SW_RECEIVER_REGISTER:spp-receiver-register}\n  spp-receiver-register:/' /skywalking/config/application.yml

ENV SW_CORE_REST_PORT=12801
ENV SW_RECEIVER_AWS_FIREHOSE_HTTP_PORT=12802
ENV SW_SEARCHABLE_TAG_KEYS="spp.instrument_id,spp.instrument_type,http.method,http.status_code,rpc.status_code,db.type,db.instance,mq.queue,mq.topic,mq.broker"
