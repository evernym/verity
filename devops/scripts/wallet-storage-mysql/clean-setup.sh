#!/usr/bin/env bash

SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"

mysqlun="msuser"

mysqlpw="mspassword"

export MYSQL_PWD=$mysqlpw

mysql -s --user="$mysqlun" -e "DROP DATABASE IF EXISTS wallet;"

mysql -s --user="$mysqlun" -e "CREATE DATABASE IF NOT EXISTS wallet DEFAULT CHARACTER SET latin1;"

mysql -s --user="$mysqlun" -D wallet < "$SCRIPT_DIR/create-tables.sql"

unset MYSQL_PWD