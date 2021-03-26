#!/usr/bin/env bash

#
# libmysqlstorage has been rolled in to libindy so this script is not needed
#

# Add evernym's verification key
curl https://repo.corp.evernym.com/repo.corp.evenym.com-sig.key | sudo apt-key add -

if ! grep -q "deb https://repo.corp.evernym.com/deb evernym-agency-dev-ubuntu main" /etc/apt/sources.list /etc/apt/sources.list.d/*; then
  sudo add-apt-repository "deb https://repo.corp.evernym.com/deb evernym-agency-dev-ubuntu main"
fi

sudo apt-get update
sudo apt-get install -y libmysqlstorage=0.1.11