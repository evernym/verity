# Verity Devlab

Allows quickly bring up, resting and down the external services for typical `verity` environment. Currently, it manages the following services:
1. dynamodb - event storage
2. mysql - for wallet data storage
3. pool - indy ledger network
4. s3 - large object storage
5. yourls - url shortener

## Install `devlab`
Follow instructions at [the opensource devlab github repo](https://github.com/evernym/devlab):

## Bringing up
Run the following command to bring up the devlab environment in the `devlab` directory in the verity repo (ex. <verity-repo>/devlab):

```devlab up```

## Reseting
Run the following command to completely reset the devlab environment in the `devlab` directory in the verity repo (ex. <verity-repo>/devlab):

```devlab reset --full```

## Downing
Run the following command to bring down the devlab environment in the `devlab` directory in the verity repo (ex. <verity-repo>/devlab):

```devlab down```

## Status
Run the following command to check the status of the devlab environment in the `devlab` directory in the verity repo (ex. <verity-repo>/devlab):

```devlab status```

Example output (notice the useful links)
```## COMPONENT STATUS ##
   ------------------------------------------------------------------------------------------------
   |    Component     |     Container Name     |  Status  |        Health        | Docker exposed |
   ------------------------------------------------------------------------------------------------
   | verity-dynamodb  | verity-dynamodb-devlab | up       |       healthy        | 8000(tcp)      |
   | verity-mysql     | verity-mysql-devlab    | up       |       healthy        | 3306(tcp)      |
   | verity-pool      | verity-pool-devlab     | up       |       healthy        | 9701-9708(tcp) |
   |                  |                        |          |                      | 5678-5679(tcp) |
   | verity-s3        | verity-s3-devlab       | up       |       healthy        | 8001(tcp)      |
   | verity-yourls    | verity-yourls-devlab   | up       |       healthy        | 8080(tcp)      |
   ------------------------------------------------------------------------------------------------
   
   ## LINKS ##
   -----------------------------------------------------------------------------------------------------------------------------------
   |    Component     |                 Link(s)                  |                              Comment                              |
   -----------------------------------------------------------------------------------------------------------------------------------
   | verity-dynamodb  | http://192.168.1.12:8000/shell           | Dynamodb Shell                                                    |
   | verity-pool      | http://192.168.1.12:5679/genesis.txt     | Genesis pool txn for ledger                                       |
   | verity-yourls    | http://192.168.1.12:8080/admin/          | URL shortener admin page (Creds: yourlsuser:yourlspass)           |
   -----------------------------------------------------------------------------------------------------------------------------------
```