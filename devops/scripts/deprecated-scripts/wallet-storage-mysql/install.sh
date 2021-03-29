#!/usr/bin/env bash

# below command is useful if you want remove existing installed mysql library/components (common, client, server)
# sudo apt-get remove --purge mysql-\*

MYSQL_SERVER_VERSION 5.7.29-0ubuntu0.16.04.1

echo "mysql-server-$MYSQL_SERVER_VERSION mysql-server/root_password password root" | sudo debconf-set-selections
echo "mysql-server-$MYSQL_SERVER_VERSION mysql-server/root_password_again password root" | sudo debconf-set-selections

sudo apt-get update
sudo apt install -y mysql-server
sudo systemctl start mysql

mysql -u"root" -p"root" -e "CREATE USER if not exists msuser@'%' IDENTIFIED BY 'mspassword';"
mysql -u"root" -p"root" -e "GRANT ALL ON *.* TO 'msuser'@'%';"
mysql -u"root" -p"root" -e "FLUSH PRIVILEGES;"
