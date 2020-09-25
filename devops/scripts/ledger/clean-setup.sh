#!/usr/bin/env bash
taa_enable=${LIB_INDY_LEDGER_TAA_ENABLED:-true}
echo "taa_enable: ${taa_enable}"

docker stop agency_indy_pool_ledger
docker rm agency_indy_pool_ledger
docker run -itd --env TAA_ENABLE=${taa_enable} -p 9701-9708:9701-9708 --name agency_indy_pool_ledger agency_indy_pool
