FROM ubuntu:18.04

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

# Install vdr-tools from the debian package
RUN curl -L https://github.com/Bhutan-NDI/verity/releases/download/v2.19.0.0/libvdrtools_0.8.6-bionic_amd64.deb --output libvdrtools.deb && \
    dpkg -i libvdrtools.deb && rm libvdrtools.deb

# copy verity-application-assembly.jar and kanela-agent.jar from jars folder into the container
ADD jars/*.jar /usr/lib/verity-application/

# copy static Verity configuration files
ADD configuration/* /etc/verity/verity-application/

# Add scripts
ADD scripts/json_to_config.py /usr/local/bin/json_to_config.py
ADD scripts/entrypoint.sh /usr/local/bin/entrypoint.sh

CMD [ "/usr/local/bin/entrypoint.sh" ]

EXPOSE 9000
