# This is intended to help with setting up a docker environment to run integration tests
# like it is run in the gitlab CI/CD pipeline using a local devlab environment.
#
# intended to be used with the build environment docker container (see: $EVERNYM_REGISTRY_HOST/dev/containers/build-verity)
#
# **WARNING**
# This is a helper at best. It may NOT be portable (there might be assumptions about my local environment)
# and it is not actively maintained. Variables and Services may be added or removed without consideration of this
# script

# General vars
export MESSAGE_PROGRESS_TRACKING_ENABLED="true"

# For the integration tests to set up a usable bucket
export WALLET_BACKUP_BUCKET="evernym-wallet-backup"
export WALLET_BACKUP_S3_ENDPOINT="http://s3server:8000"

# These are used by the s3 service in .intergration jobs
export REMOTE_MANAGEMENT_DISABLE=1
export ENDPOINT="s3server"

# Ledger Pool
export POOL_HOST="indy-pool"
export TAA_ENABLE="true"

# For MYSQL docker container service
export MYSQL_ROOT_PASSWORD="root"
export MYSQL_DATABASE="wallet"
export MYSQL_USER="msuser"
export MYSQL_PASSWORD="mspassword"

# For yourls service
export YOURLS_SITE="http://yourls:8080"
export YOURLS_USER="yourlsuser"
export YOURLS_PASS="yourlspass"

export TEST_TYPE="Integration"

echo "172.17.0.1      indy-pool" >> /etc/hosts
echo "172.17.0.1      mysql" >> /etc/hosts
echo "172.17.0.1      dynamodb" >> /etc/hosts
echo "172.17.0.1      s3server" >> /etc/hosts
echo "172.17.0.1      yourls" >> /etc/hosts