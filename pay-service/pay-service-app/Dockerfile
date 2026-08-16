# 基础镜像
FROM openjdk:8-jre-slim

LABEL org.opencontainers.image.title="pay-service"

# 配置
ENV PARAMS=""

# 时区
ENV TZ=PRC
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 添加应用
ADD target/pay-service-app.jar /pay-service-app.jar

ENTRYPOINT ["sh","-c","java -jar $JAVA_OPTS /pay-service-app.jar $PARAMS"]
