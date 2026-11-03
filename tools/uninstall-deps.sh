#!/usr/bin/env bash
# Takes away what tools/install-deps.sh added (the packages and the docker group, from tools/.installed), and
# what running sky made here (.venv, logs). Nothing that was installed before.
#
#   tools/uninstall-deps.sh                  the packages and group it added, .venv, logs
#   tools/uninstall-deps.sh --docker-data    also the test stack's containers, volumes and images, and the images
#                                            the client's cards pulled (postgres, mysql, mongo, redis, python, java)
#   tools/uninstall-deps.sh --maven-cache    also ~/.m2/repository (Maven's downloads; shared with any other Maven
#                                            project on this machine, which would download them again)
#   tools/uninstall-deps.sh --purge          remove the packages with their config files too (apt purge)

set -u
cd "$(dirname "$0")"
RECORD="$PWD/.installed"
ROOT="$(cd .. && pwd)"
DOCKER_DATA="" MAVEN_CACHE="" APT_REMOVE="remove"
for option in "$@"; do
    case "$option" in
        --docker-data) DOCKER_DATA=1 ;;
        --maven-cache) MAVEN_CACHE=1 ;;
        --purge) APT_REMOVE="purge" ;;
        -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
        *) echo "Unknown option $option (--docker-data, --maven-cache, --purge)"; exit 1 ;;
    esac
done

# Docker first, while it's still installed
if [ -n "$DOCKER_DATA" ] && command -v docker >/dev/null; then
    echo "Removing the test stack (containers, volumes, its images)…"
    (cd "$ROOT" && docker compose down -v --rmi all --remove-orphans)
    echo "Removing the images the client's cards used…"
    docker image rm postgres:alpine mysql:8.4 mongo:7 redis:7-alpine python:3-alpine eclipse-temurin:21-jdk-alpine 2>/dev/null || true
fi

echo "Removing $ROOT/.venv and $ROOT/logs…"
sudo rm -rf "$ROOT/.venv" "$ROOT/logs"     # sudo: the sniffers ran as root and may have left root-owned files

if [ -n "$MAVEN_CACHE" ]; then
    echo "Removing ~/.m2/repository…"
    rm -rf "$HOME/.m2/repository"
fi

if [ -f "$RECORD" ]; then
    PACKAGES=$(sed -n 's/^package //p' "$RECORD" | sort -u)
    if grep -qx "group docker" "$RECORD"; then
        sudo gpasswd -d "$USER" docker && echo "Removed $USER from the docker group (takes effect at the next login)"
    fi
    if [ -n "$PACKAGES" ]; then
        echo "Removing the packages install-deps.sh added: $PACKAGES"
        sudo apt-get "$APT_REMOVE" -y $PACKAGES && sudo apt-get autoremove -y
    fi
    rm -f "$RECORD"
else
    echo "No $RECORD: install-deps.sh didn't install anything here, so no packages are removed"
fi
echo "Done. The sky folder itself is still there: delete it to finish (rm -rf $ROOT)"
