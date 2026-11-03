#!/usr/bin/env bash
# Installs what sky needs on Debian / Ubuntu, only what isn't there yet, and writes down what it added in
# tools/.installed, so tools/uninstall-deps.sh can take exactly that away again (and nothing that was there before).
#
#   tools/install-deps.sh          Java 21, Maven, python3-venv, curl, git, and Docker + Compose if there's no docker
#
# The apps' own dependencies come by themselves and stay in their places: Maven's libraries in ~/.m2, scapy in
# .venv, the images in Docker (see tools/uninstall-deps.sh --docker-data).

set -eu
cd "$(dirname "$0")"
RECORD="$PWD/.installed"
command -v apt-get >/dev/null || { echo "Only for Debian / Ubuntu (apt). Install by hand: Java 21, Maven, python3-venv, Docker + Compose 2.20+"; exit 1; }

PACKAGES=(openjdk-21-jdk maven python3-venv curl git)
command -v docker >/dev/null || PACKAGES+=(docker.io docker-compose-v2)

NEW=()
for package in "${PACKAGES[@]}"; do
    dpkg-query -W -f='${Status}' "$package" 2>/dev/null | grep -q "install ok installed" || NEW+=("$package")
done

if [ ${#NEW[@]} -gt 0 ]; then
    echo "Installing: ${NEW[*]}"
    sudo apt-get update
    sudo apt-get install -y "${NEW[@]}"
    for package in "${NEW[@]}"; do echo "package $package" >> "$RECORD"; done
else
    echo "All the packages are there already"
fi

# The client starts its containers (psql, mongosh, python, java, ...) as you, so you need to be in the docker group
if command -v docker >/dev/null && ! id -nG "$USER" | tr ' ' '\n' | grep -qx docker; then
    sudo usermod -aG docker "$USER"
    echo "group docker" >> "$RECORD"
    echo "Added $USER to the docker group: log out and back in (or run: newgrp docker) before ./run.sh"
fi

[ -f "$RECORD" ] && echo "What was added is in $RECORD; tools/uninstall-deps.sh takes it away"
echo
docker compose version 2>/dev/null || echo "Note: no docker compose yet (needs 2.20+)"
java -version 2>&1 | head -1
mvn -version 2>/dev/null | head -1
