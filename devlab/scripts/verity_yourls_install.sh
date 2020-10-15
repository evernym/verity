#!/bin/bash

# Using bash because the YOURLS docker container don't have python on it

tried=10
until curl --fail -s -o /dev/null -XPOST http://localhost/admin/install.php --data 'install=Install+YOURLS'
do
  sleep 3
  tries=$((tries-1))
  if [ "$tries" -eq "0" ]
  then
    echo "Unable to install yourls after $tried attempt(s)"
    exit 1
  fi
done