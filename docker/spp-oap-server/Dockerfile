FROM apache/skywalking-oap-server:8.8.1

RUN rm /skywalking/config/log4j2.xml
ADD ./log4j2.xml /skywalking/config/log4j2.xml

ADD ./spp-processor-*.jar /skywalking/oap-libs/

RUN echo -e "\nspp-live-instrument:" >> /skywalking/config/application.yml
RUN echo -e "  selector: \${SPP_LIVE_INSTRUMENT:default}" >> /skywalking/config/application.yml
RUN echo -e "  default:" >> /skywalking/config/application.yml

RUN echo -e "\nexporter:" >> /skywalking/config/application.yml
RUN echo -e "  selector: exporter" >> /skywalking/config/application.yml
RUN echo -e "  exporter:" >> /skywalking/config/application.yml

RUN echo -e "\nspp-log-summary:" >> /skywalking/config/application.yml
RUN echo -e "  selector: \${SPP_LOG_SUMMARY:default}" >> /skywalking/config/application.yml
RUN echo -e "  default:" >> /skywalking/config/application.yml