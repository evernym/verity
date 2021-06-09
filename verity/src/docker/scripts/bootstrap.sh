#!/bin/bash

echo
echo -n "Waiting for Verity to start listening."
until curl -q 127.0.0.1:9000/agency > /dev/null 2>&1
do
    sleep 1
done
echo

echo "Bootstrapping agency"
if curl -s localhost:9000/agency | grep -q 'verKey' ; then
    echo "Agency indicates it is already bootstrapped. Skipping"
else
  if [ ! -z "$VERITY_SEED" ] ; then
      curl -fSs -H "Content-Type: application/json" -X POST localhost:9000/agency/internal/setup/key -d "{\"seed\":\"$VERITY_SEED\"}" > >(tee /tmp/did_verkey)
      curl_rc=$?
  else
      echo "Seed not given"
      curl -fSs -H "Content-Type: application/json" -X POST localhost:9000/agency/internal/setup/key
      curl_rc=$?
  fi
  echo
  if [ $curl_rc -eq 0 ] ; then
      if [ ! -z "$VERITY_SEED" ] ; then
          echo "Finalizing Bootstrap by setting 'endpoint'"
          curl -Ss -X POST localhost:9000/agency/internal/setup/endpoint
          if [ $? -ne 0 ] ; then
              echo -e "\nErrors ocurred finalizing enpoint setup" >&2
              exit 1
          fi
          echo
      else
          echo "Please Add the DID/Verkey from above to your ledger and then call: curl -X POST localhost:9000/agency/internal/setup/endpoint"
      fi
  else
      echo "Errors occurred during bootstrapping key setup, aborting" >&2
      exit 1
  fi
fi

echo "Verity is bootstrapped."
echo