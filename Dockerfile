FROM amazoncorretto:21

WORKDIR /app

COPY target/custom-http-framework.jar app.jar

ENV PORT=35000

EXPOSE 35000

ENTRYPOINT [ "java", "-jar", "app.jar" ]
