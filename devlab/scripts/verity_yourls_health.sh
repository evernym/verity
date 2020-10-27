#!/bin/bash
set -e

# Using bash because the YOURLS docker container don't have python on it

if curl -s -o /dev/null http://localhost/admin ; then
   echo '{"status":{"health":"healthy"},"links":[{"link":"http://{host_ip}:8080/admin/","comment":"URL shortener admin page (Creds: yourlsuser:yourlspass)"}]}'
else
   echo '{"status":{"health":"unhealthy"}}'
fi