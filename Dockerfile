FROM ubuntu:16.04

RUN apt-get update
RUN apt-get -y install python3-software-properties software-properties-common debconf-utils
RUN add-apt-repository -y ppa:webupd8team/java
RUN apt-get update

RUN echo "oracle-java8-installer shared/accepted-oracle-license-v1-1 select true" | debconf-set-selections
RUN apt-get -y install oracle-java8-installer

WORKDIR /app

ARG SP_VERSION

ADD server/target/scala-2.11/science-parse-server-$SP_VERSION.jar /app/science-parse-server-$SP_VERSION.jar

RUN java -Xmx8g -jar /app/science-parse-server-$SP_VERSION.jar --downloadModelOnly

EXPOSE 8080

CMD java -Xmx8g -jar science-parse-server-$SP_VERSION.jar --disableFeedback
