FROM bellsoft/liberica-openjdk-alpine:21

COPY target/*.jar /assistant-service.jar

ENTRYPOINT ["java", "-jar", "/assistant-service.jar"]