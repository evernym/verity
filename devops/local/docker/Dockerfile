FROM ubuntu:18.04

ENV DEBIAN_FRONTEND="noninteractive" \
    TZ="Etc/UTC" \
    LIBVDRTOOLS_VERSION="0.8.6" \
    VERITY_HTTP_PORT=4901


WORKDIR /root

RUN apt-get update && \
    apt-get install -y \
        apt-transport-https \
        curl \
        default-jdk \
        software-properties-common \
        libzmq3-dev \
        libsodium23 \
        libssl1.1

# Set JAVA_HOME (use value for defualt-jdk in Ubuntu 18)
ENV JAVA_HOME /usr/lib/jvm/java-1.11.0-openjdk-amd64/

RUN curl https://gitlab.com/evernym/verity/vdr-tools/-/package_files/27311920/download --output libvdrtools_0.8.4-bionic_amd64.deb
RUN dpkg -i libvdrtools_0.8.4-bionic_amd64.deb
RUN rm -f libvdrtools_0.8.4-bionic_amd64.deb


# copy verity-application-assembly.jar and kanela-agent.jar from jars folder into the container
ADD jars/*.jar /usr/lib/verity-application/

# copy static Verity configuration files
ADD configuration/common/* /etc/verity/verity-application/configuration/common/
ADD configuration/cas/* /etc/verity/verity-application/configuration/cas/
ADD configuration/vas/* /etc/verity/verity-application/configuration/vas/
ADD configuration/eas/* /etc/verity/verity-application/configuration/eas/

# Add entrypoint script
ADD scripts/entrypoint.sh /usr/local/bin/entrypoint.sh

ARG APP_TYPE
ENV APP_TYPE=$APP_TYPE

EXPOSE $VERITY_HTTP_PORT $VERITY_AKKA_MANAGEMENT_HTTP_PORT

ENTRYPOINT [ "/usr/local/bin/entrypoint.sh" ]
