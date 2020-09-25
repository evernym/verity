#!/bin/sh
set -e
#set -o xtrace
BUSINESS_LICENSE_GRACE_DAYS="32" # REMOVE ME, should come from CI/CD ENVIRONMENT


LICENSE_CHANGE_DATE_FILE="LICENSE_CHANGE_DATE.txt"
SECONDS_IN_DAY=86400
WINDOW_DAYS=$BUSINESS_LICENSE_GRACE_DAYS
WINDOW_SECONDS=$((WINDOW_DAYS * SECONDS_IN_DAY))


if [ -f "$LICENSE_CHANGE_DATE_FILE" ]; then
  LICENSE_DATE=$(cat $LICENSE_CHANGE_DATE_FILE)
  LICENSE_DATE_UNIX=$(date --date="$LICENSE_DATE" +%s)

  COMMIT_LICENSE_DATE=$(git show -s --format=%ci HEAD)
  COMMIT_DATE_UNIX=$(git show -s --format=%ct HEAD)

  echo "License Date Check:"
  echo "Grace window is $WINDOW_DAYS day(s) ($WINDOW_SECONDS sec)"
  echo "License Date: $LICENSE_DATE -- ($LICENSE_DATE_UNIX)"
  echo "Commit Date:  $COMMIT_LICENSE_DATE -- ($COMMIT_DATE_UNIX)"

  DIFFERENCE=$((COMMIT_DATE_UNIX - LICENSE_DATE_UNIX))
  DIFFERENCE_DAYS=$((DIFFERENCE / SECONDS_IN_DAY))
  echo "Difference: $DIFFERENCE_DAYS day(s) ($DIFFERENCE sec)"
  echo

  if [ "$DIFFERENCE" -lt "0" ]; then
    echo "**** [FAILURE] ****"
    echo "LICENSE_CHANGE_DATE.txt cannot have a date in the future compared to the commit"
    exit 1
  fi

  if [ "$DIFFERENCE" -gt "$WINDOW_SECONDS" ]; then
    echo "**** [FAILURE] ****"
    echo "LICENSE_CHANGE_DATE.txt is older than the allowed $WINDOW_DAYS day(s)"
    echo "LICENSE_CHANGE_DATE.txt MUST BE updated before this test will pass"
    exit 1
  fi

  echo "**** [SUCCESS] ****"
  echo "LICENSE_CHANGE_DATE.txt is within the given -- $WINDOW_DAYS day(s) -- grace window"
else
  echo "**** [FAILURE] ****"
  echo "License date file was not found at '$LICENSE_CHANGE_DATE_FILE'"
  echo "License date MUST EXIST"
  exit 1
fi