#!/bin/bash
set -e

# Using bash because this a clone of YOURLS script. If it get more complex, it should convert to python

if curl -s -o /dev/null http://localhost:8000/shell ; then
   echo '{"status":{"health":"healthy"},"links":[{"link":"http://{host_ip}:8000/shell","comment":"Dynamodb Shell"}]}'
else
   echo '{"status":{"health":"unhealthy"}}'
fi