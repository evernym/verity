#!/usr/bin/env bash

# repo location: https://repo.sovrin.org/sdk/lib/apt/xenial/stable/

sudo apt-key adv --keyserver keyserver.ubuntu.com --recv-keys 68DB5E88

if ! grep -q "deb https://repo.sovrin.org/sdk/deb xenial stable" /etc/apt/sources.list /etc/apt/sources.list.d/*; then
  sudo add-apt-repository "deb https://repo.sovrin.org/sdk/deb xenial stable"
fi

sudo apt-get update
sudo apt-get install -y indy-cli
