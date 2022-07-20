FROM apache/skywalking-oap-server:9.1.0

ADD ./config/spp-platform.yml /skywalking/config/

RUN rm /skywalking/config/log4j2.xml
ADD ./log4j2.xml /skywalking/config/log4j2.xml

ADD ./spp-platform-*.jar /skywalking/oap-libs/
ADD ./spp-live-*.jar /skywalking/oap-libs/

RUN printf "\nspp-live-core:\n  selector: \${SPP_LIVE_CORE:default}\n  default:\n" \
    >> /skywalking/config/application.yml \

RUN printf "\nspp-live-instrument:\n  selector: \${SPP_LIVE_INSTRUMENT:default}\n  default:\n" \
    >> /skywalking/config/application.yml

RUN printf "\nexporter:\n  selector: exporter\n  exporter:\n" \
    >> /skywalking/config/application.yml

ADD ./ui-initialized-templates /skywalking/config/ui-initialized-templates

RUN sed -i -e 's/SW_CORE_REST_PORT:12800/SW_CORE_REST_PORT:12801/g' /skywalking/config/application.yml
RUN sed -i -e 's/SW_CORE_GRPC_PORT:11800/SW_CORE_GRPC_PORT:11801/g' /skywalking/config/application.yml
