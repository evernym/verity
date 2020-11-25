#!/usr/bin/env bash

# repo location: https://repo.sovrin.org/sdk/lib/apt/[xenial|bionic]/stable/

sudo apt-key adv --keyserver keyserver.ubuntu.com --recv-keys 68DB5E88

codeName=$(lsb_release -c | awk '{print $2}')
if [ "$codeName" = "xenial" ]
then
  if ! grep -q "deb https://repo.sovrin.org/sdk/deb xenial master" /etc/apt/sources.list /etc/apt/sources.list.d/*; then
    sudo add-apt-repository "deb https://repo.sovrin.org/sdk/deb xenial master"
  fi

  sudo apt-get update

  echo "Installing xenial dependencies..."
  sudo apt-get install -y --allow-downgrades libindy=1.15.0~1560-xenial
else
  if ! grep -q "deb https://repo.sovrin.org/sdk/deb bionic master" /etc/apt/sources.list /etc/apt/sources.list.d/*; then
    sudo add-apt-repository "deb https://repo.sovrin.org/sdk/deb bionic master"
  fi

  sudo apt-get update

  echo "Installing bionic dependencies..."
  # All other debian-based systems get the bionic build
  sudo apt-get install -y --allow-downgrades libindy=1.15.0~1560-bionic
fi
