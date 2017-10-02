FROM ubuntu:16.04

RUN apt-get update && \
    apt-get -y upgrade && \
    apt-get -y install python3-software-properties software-properties-common debconf-utils

RUN add-apt-repository -y ppa:webupd8team/java && \
    apt-get update && \
    echo "oracle-java8-installer shared/accepted-oracle-license-v1-1 select true" | debconf-set-selections && \
    apt-get -y install oracle-java8-installer

WORKDIR /app

ARG SP_VERSION
ENV SP_VERSION $SP_VERSION

ADD server/target/scala-2.11/science-parse-server-assembly-$SP_VERSION.jar /app/science-parse-server-assembly-$SP_VERSION.jar

RUN java -Xmx8g -jar /app/science-parse-server-assembly-$SP_VERSION.jar --downloadModelOnly

EXPOSE 8080

CMD java -Xmx8g -jar science-parse-server-assembly-$SP_VERSION.jar
