FROM eclipse-temurin:25-jre
LABEL authors="Andreas Kreitschmann"

# Add the application jar
COPY target/scala-3.*/ai-context-server.jar /app/ai-context-server.jar

# expose server port
EXPOSE 8080

# Run the jar
ENTRYPOINT ["java", "-Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider", "-jar", "/app/ai-context-server.jar"]