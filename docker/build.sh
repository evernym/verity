#!/bin/bash

while IFS='=' read -r key value; do
  args+=" --build-arg $key=$value"
done < cas.env

echo $args

docker build $args -t dev-cas .