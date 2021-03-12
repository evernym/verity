#!/usr/bin/env python3

import platform
import re
import subprocess

E = '\033[0m'  # reset
R = '\033[31m'  # red
G = '\033[32m'  # green
O = '\033[33m'  # orange
Y = '\033[93m'  # yellow

_FLOATING_POINT_VERSION_PAT = re.compile(r'(\d+\.\d+)')


def eq_ver(txt, ver):
    found_ver = get_float_from_version(txt)
    return found_ver == ver


def has_ver(txt):
    return get_float_from_version(txt) is not None


def get_float_from_version(txt):
    m = _FLOATING_POINT_VERSION_PAT.search(txt)
    if m:
        return float(m.group())
    return


def run_version_cmd(cmd):
    try:
        return subprocess.run(cmd, stderr=subprocess.STDOUT, stdout=subprocess.PIPE).stdout.decode('utf-8').strip()
    except FileNotFoundError:
        return ""


def run_check_cmd(cmd):
    return subprocess.run(cmd, stderr=subprocess.STDOUT, stdout=subprocess.PIPE).returncode


def run_cmd(cmd):
    rtn = subprocess.run(cmd, shell=False, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    return rtn.stdout.decode('utf-8').strip()


def print_detection(looking_for, found, is_ok, indent):
    i = " " * indent
    to_print = i + O + "Detected " + str(looking_for) + ": " + E + str(found).replace("\n", " -- ") + " "
    if is_ok:
        ok = G + "[OK]" + E
    else:
        ok = R + "[BAD]" + E

    print(to_print + ok)


def convert_ubuntu_variants(dist):
    if len(dist) != 3:
        return None

    if dist[0] == 'LinuxMint' and dist[1].startswith("19"):
        rtn = ('Ubuntu', '18.04', 'bionic')

        return rtn
    elif dist[0] == 'Ubuntu' and "18.04":
        return dist
    elif dist[0] == 'Ubuntu' and "16.04":
        return dist
    else:
        return None


def check_os(indent=2):
    print(Y+"[Optional] "+E+"Checking OS:")
    system = platform.system()

    if system == 'Linux':
        print_detection("OS", system, True, indent)
        dist = platform.linux_distribution()
        print_detection("Distribution", dist, True, indent)
        ubuntu_dist = convert_ubuntu_variants(dist)
        print_detection("Ubuntu Distribution", ubuntu_dist, ubuntu_dist is not None, indent)
    else:
        print_detection("OS", system, False, indent)
        ubuntu_dist = None

    if ubuntu_dist is None:
        print((" " * indent) + Y + "Automatic setup actions require Ubuntu" + E)
        print((" " * indent) + Y + "Other Detections will still be attempt" + E)

    return ubuntu_dist


def check_certificate(indent=2):
    print("Checking Certificate:")
    can_curl_gitlab = run_check_cmd(['curl', '-s', '-o', '/dev/null', 'https://gitlab.corp.evernym.com/'])
    print_detection("Evernym Certificate", "", can_curl_gitlab == 0, indent)


def check_scala(indent=2):
    print("Checking Scala:")
    jdk_ver = run_version_cmd(["javac", "-version"])
    print_detection("JDK", jdk_ver, eq_ver(jdk_ver, 1.8), indent)
    sbt_ver = run_version_cmd(["sbt", "--version"])
    print_detection("SBT", sbt_ver, has_ver(sbt_ver), indent)


def check_apt_repos(ubuntu_dist, indent=2):
    print("Checking Apt Repos:")
    if ubuntu_dist is not None:
        repos = run_cmd(['grep', '-rhE', '^deb ', '/etc/apt/']).splitlines()
        repos = map(lambda x: x.strip("deb "), repos)
        repos = list(repos)
        evernym_agency_dev = 'https://repo.corp.evernym.com/deb evernym-ubuntu main'
        print_detection("Evernym Repo", "", evernym_agency_dev in repos, indent)
    pass


def check_native_libs(ubuntu_dist, indent=2):
    print("Checking Native Libs:")
    pass


def check_docker(indent=2):
    print("Checking Docker:")
    ver = run_version_cmd(["docker", "--version"])
    print_detection("Docker", ver, has_ver(ver), indent)


def check_devlab(indent=2):
    print("Checking Docker:")
    ver = run_version_cmd(["devlab", "-v"])
    print_detection("Docker", ver, has_ver(ver), indent)


def main():
    ubuntu_dist = check_os()
    check_certificate()
    check_scala()
    check_apt_repos(ubuntu_dist)
    check_native_libs(ubuntu_dist)
    check_docker()
    check_devlab()
    pass


if __name__ == "__main__":
    main()