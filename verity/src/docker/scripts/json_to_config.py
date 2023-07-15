import argparse
import json


def main():
    args = parse_args()
    convert_json_to_conf(args.json_file, args.conf_file)


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument('-j', '--json-file', required=True)
    parser.add_argument('-c', '--conf-file', required=True)
    return parser.parse_args()


def convert_json_to_conf(json_path, conf_path):
    with(open(json_path)) as json_file:
        data = json.loads(json_file.read())

    with(open(conf_path, "wt")) as conf_file:
        for key, value in data.items():

            if value is None:
                print(f'WARNING: value of the {key} parameter is None and will be omitted')
                continue

            conf_file.write(f"export {key}='{value}'\n")


if __name__ == "__main__":
    main()
