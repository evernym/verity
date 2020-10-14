#!/usr/bin/env python3
import argparse
import json
import sys
import time
from urllib.request import urlopen

interval = 2

def eprint(*args, **kwargs):
    print(*args, file=sys.stderr, **kwargs)

def test_genesis_is_avaiable(tries, interval_sec):
    for t in range(0, tries):
        if t > 0:
            eprint("sleeping for " + str(interval_sec))
            time.sleep(interval_sec)
        eprint("Trying to retrieve genesis file.")
        try:
            r = urlopen("http://0.0.0.0:5679/genesis.txt").read()
            return True
        except OSError as e:
            pass

    return False

def main():
    parser = argparse.ArgumentParser(description='Health check for verity-pool')
    parser.add_argument("-w", "--wait", help="wait for healthy state", action="store_true")
    args = parser.parse_args()

    tries = 1
    if args.wait:
        tries = 30

    state = test_genesis_is_avaiable(tries, interval)

    health = "healthy" if state else "unhealthy"

    status = {
        "status": {
            "health": health
        },
        "links": [
            {
                "link": "http://{host_ip}:5679/genesis.txt",
                "comment": "Genesis pool txn for ledger"
            }
        ]
    }
    print(json.dumps(status))

if __name__ == "__main__":
    main()