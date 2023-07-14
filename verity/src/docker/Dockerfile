FROM ubuntu:18.04

ARG LIBVDRTOOLS_VERSION
ARG EVERNYM_CERTIFICATE

WORKDIR /root

RUN apt-get update && \
    apt-get install -y \
        apt-transport-https \
        curl \
        default-jdk \
        software-properties-common

# Set JAVA_HOME (use value for defualt-jdk in Ubuntu 18)
ENV JAVA_HOME /usr/lib/jvm/java-1.11.0-openjdk-amd64/

# Add Evernym Cert (needed to access Evernym repo)
RUN mkdir -p /usr/local/share/ca-certificates && \
    cat "$EVERNYM_CERTIFICATE" >> /usr/local/share/ca-certificates/Evernym_Root_CA.crt && \
    update-ca-certificates

# Setup apt for Evernym repositories
RUN curl https://repo.corp.evernym.com/repo.corp.evenym.com-sig.key | apt-key add - && \
    add-apt-repository "deb https://repo.corp.evernym.com/deb evernym-agency-dev-ubuntu main" && \
    add-apt-repository "deb https://repo.corp.evernym.com/deb evernym-ubuntu main"

# Install libvdrtools libraries
RUN apt-get update && \
    apt-get install -y \
        libvdrtools=${LIBVDRTOOLS_VERSION}-bionic

# cleanup and remove Evernym internal repo
RUN apt-get autoremove -y && \
    apt-get clean && \
    sed -i '/repo\.corp\.evernym\.com/d' /etc/apt/sources.list && \
    rm -rf /var/lib/apt/lists/* \
    ; exit 0

# copy verity-application-assembly.jar and kanela-agent.jar from jars folder into the container
ADD jars/*.jar /usr/lib/verity-application/

# copy static Verity configuration files
ADD configuration/* /etc/verity/verity-application/

# Add scripts
ADD scripts/json_to_config.py /usr/local/bin/json_to_config.py
ADD scripts/entrypoint.sh /usr/local/bin/entrypoint.sh

CMD [ "/usr/local/bin/entrypoint.sh" ]

EXPOSE 9000
