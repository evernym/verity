#!/usr/bin/env bash

repo="deb https://dl.bintray.com/sbt/debian /"
sources_file="/etc/apt/sources.list.d/sbt.list"
grep -q -F "${repo}" ${sources_file} || echo "${repo}" | sudo tee -a ${sources_file}
sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 2EE0EA64E40A89B84B2DF73499E82A75642AC823
sudo apt-get update
sudo apt-get install -y sbt
