# Agency bootstrapping APIs

**Note:** These APIs are only callable from IP addresses whitelisted in
[`internal-api.conf`](http://bit.ly/2JmXrqn). [By default, only localhost
is allowed](../../guidelines/provide-safe-and-helpful-defaults.md).

## Setup agency agent key

If called for first time, will create agency's agent (wallet, key etc)

If called for any other times, will see 'forbidden' response.

### with seed
    curl -H "Content-Type: application/json" -X POST <http agency url>/agency/internal/setup/key -d '{"seed":"11111111111111111111111111111111"}'

**Note:** above-mentioned seed is an example seed, you can use any 32 character seed
    
### without seed
    curl -H "Content-Type: application/json" -X POST <http agency url>/agency/internal/setup/key

## Bootstrap agency DID to ledger
Agency admin will then have to get its **DID** and **verkey** and give it to any trust anchor
who can then bootstrap it into ledger.


## Setup agency agent’s endpoint

**Notes:** 
* once agency key is bootstrapped into ledger:

If called for first time, this will add the agency's endpoint to ledger

If called for any other times, will see 'forbidden' response.

    curl -X POST <agency-url>/agency/internal/setup/endpoint
    
**Note:** when you hit above api, agency uses configuration to build the endpoint and then use ATTRIB txn to 
update it in the ledger, it looks something like this (offcourse exact value would depend on your configuration):

    attribute-name  attribute-value
    url             http://<agent-domain>/agency/msg 
