FROM eclipse-temurin

ADD corrupted-mainframe.jar config.json /root/
ADD plugins/ /root/plugins

WORKDIR /root/
ENTRYPOINT ["java"]
CMD ["-jar", "corrupted-mainframe.jar"]
