#!/usr/bin/env bash

# For libindy
sudo apt-key adv --keyserver keyserver.ubuntu.com --recv-keys 68DB5E88

# Add evernym's verification key
curl https://repo.corp.evernym.com/repo.corp.evenym.com-sig.key | sudo apt-key add -

if ! grep -q "deb https://repo.corp.evernym.com/deb evernym-agency-dev-ubuntu main" /etc/apt/sources.list /etc/apt/sources.list.d/*; then
  sudo add-apt-repository "deb https://repo.corp.evernym.com/deb evernym-agency-dev-ubuntu main"
fi

sudo apt remove -y libvcx libindy libnullpay
sudo apt-get update

codeName=$(lsb_release -c | awk '{print $2}')
if [ "$codeName" = "xenial" ]
then
  if ! grep -q "deb https://repo.sovrin.org/sdk/deb xenial master" /etc/apt/sources.list /etc/apt/sources.list.d/*; then
    sudo add-apt-repository "deb https://repo.sovrin.org/sdk/deb xenial master"
  fi

  sudo apt-get update
  echo "Installing xenial dependencies..."
  sudo apt-get install -y --allow-downgrades libindy=1.15.0~1618-xenial libnullpay=1.15.0~1618-xenial libvcx=0.10.1-xenial~1131
else
  if ! grep -q "deb https://repo.sovrin.org/sdk/deb bionic master" /etc/apt/sources.list /etc/apt/sources.list.d/*; then
    sudo add-apt-repository "deb https://repo.sovrin.org/sdk/deb bionic master"
  fi

  sudo apt-get update

  echo "Installing bionic dependencies..."
  # All other debian-based systems get the bionic build
  sudo apt-get install -y --allow-downgrades libindy=1.15.0~1618-bionic libnullpay=1.15.0~1618-bionic libvcx=0.10.1-bionic~1131
fi
