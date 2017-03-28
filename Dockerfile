FROM ubuntu:16.04

RUN apt-get update
RUN apt-get -y install python3-software-properties software-properties-common debconf-utils
RUN add-apt-repository -y ppa:webupd8team/java
RUN apt-get update

RUN echo "oracle-java8-installer shared/accepted-oracle-license-v1-1 select true" | debconf-set-selections
RUN apt-get -y install oracle-java8-installer

WORKDIR /app

ADD server/target/scala-2.11/science-parse-science-parse-server-1.2.6-SNAPSHOT.jar /app/science-parse-server-1.2.6-SNAPSHOT.jar

CMD java -jar science-parse-server-1.2.6-SNAPSHOT.jar --disableFeedback
