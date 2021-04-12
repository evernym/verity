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
  sudo apt-get install -y --allow-downgrades libindy-async=1.95.0~1353-xenial libnullpay-async=1.95.0~1353-xenial libvcx-async-test=0.11.0-xenial~9999
else
  if ! grep -q "deb https://repo.sovrin.org/sdk/deb bionic master" /etc/apt/sources.list /etc/apt/sources.list.d/*; then
    sudo add-apt-repository "deb https://repo.sovrin.org/sdk/deb bionic master"
  fi

  sudo apt-get update

  echo "Installing bionic dependencies..."
  # All other debian-based systems get the bionic build
  sudo apt-get install -y --allow-downgrades libindy-async=1.95.0~1353-bionic libnullpay-async=1.95.0~1353-bionic libvcx-async-test=0.11.0-bionic~9999
fi
