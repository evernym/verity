FROM evernymdev/verity-server-dev:stable
WORKDIR /root
RUN apt-key adv --keyserver keyserver.ubuntu.com --recv-keys 97080EBDA5D46DAF

RUN apt-get update && apt-get install -y \
    npm \
    net-tools \
    ; exit 0

RUN rm /etc/verity/verity-application/application.conf
ADD configuration/application.conf /etc/verity/verity-application/application.conf

ENV RUN_MODE="docker"

ADD verity_backchannel.js verity_backchannel.js
ADD package.json package.json
ADD package-lock.json package-lock.json

RUN npm install -g npm@5.6.0
RUN npm install

ADD run_verity.sh run_verity.sh
ADD run_and_listen.sh run_and_listen.sh

ADD verity-assembly-0.4.0-SNAPSHOT.jar verity-assembly-0.4.0-SNAPSHOT.jar

ENV LEDGER_URL=http://dev.bcovrin.vonx.io

EXPOSE 9020-9021
EXPOSE 8473

RUN echo "Starting Verity Backchannel"

ENTRYPOINT ["node", "verity_backchannel.js"]