FROM apache/skywalking-oap-server:8.9.1

RUN rm /skywalking/config/log4j2.xml
ADD ./log4j2.xml /skywalking/config/log4j2.xml

ADD ./spp-processor-*.jar /skywalking/oap-libs/

RUN printf "\nspp-live-instrument:\n  selector: \${SPP_LIVE_INSTRUMENT:default}\n  default:\n" \
    >> /skywalking/config/application.yml

RUN printf "\nspp-log-summary:\n  selector: \${SPP_LOG_SUMMARY:default}\n  default:\n" \
    >> /skywalking/config/application.yml

RUN printf "\nexporter:\n  selector: exporter\n  exporter:\n" \
    >> /skywalking/config/application.yml