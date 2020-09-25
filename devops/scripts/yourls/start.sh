#!/bin/bash

echo "Starting yourls docker container"
docker run -d --rm --name yourls -p 8080:80 -e YOURLS_SITE=http://localhost:8080 -e YOURLS_USER=yourlsuser -e YOURLS_PASS=yourlspass gitlab.corp.evernym.com:4567/dev/containers/yourls-mysql:2283cd0c

SERVER_IP=$(docker exec -it yourls hostname -i | tr -d '\r')

echo "Wait until the container is ready"
until $(curl --output /dev/null --silent --head --fail http://$SERVER_IP/admin/install.ph); do
	printf '.'
	sleep 2
done

curl -XPOST ${SERVER_IP}/admin/install.php --data 'install=Install+YOURLS'
