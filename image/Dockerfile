FROM eclipse-temurin

COPY corrupted-mainframe.jar plugins config.json /root/

WORKDIR /root/
ENTRYPOINT ["java"]
CMD ["-jar", "corrupted-mainframe.jar"]
