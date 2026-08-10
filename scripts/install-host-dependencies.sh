#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_NAME="${0##*/}"
readonly DOCKER_KEY_URL="https://download.docker.com/linux/ubuntu/gpg"
readonly DOCKER_REPOSITORY_URL="https://download.docker.com/linux/ubuntu"
readonly GH_KEY_URL="https://cli.github.com/packages/githubcli-archive-keyring.gpg"
readonly GH_REPOSITORY_URL="https://cli.github.com/packages"
readonly GH_KEY_FINGERPRINT_1="2C6106201985B60E6C7AC87323F3D4EA75716059"
readonly GH_KEY_FINGERPRINT_2="7F38BBB59D064DBCB3D84D725612B36462313325"

temporary_directory=""

usage() {
  cat <<EOF
Usage: ./${SCRIPT_NAME}

Install the host-side dependencies required to build and operate this project
on Ubuntu/WSL2:

  - Docker Engine, Docker CLI, Buildx, and Docker Compose plugin
  - GNU Make
  - Git and GitHub CLI (gh)
  - curl, jq, OpenSSL, envsubst, grep, and ripgrep (rg)

Node.js/npm, Java, Maven, PostgreSQL, Keycloak, and Playwright are intentionally
excluded because this project runs them in containers.

Options:
  --remove-docker-conflicts
      Deprecated compatibility option. Conflicting Docker packages are now
      replaced automatically. Docker data under /var/lib/docker is not removed.
  -h, --help
      Show this help.
EOF
}

log() {
  printf '[%s] %s\n' "${SCRIPT_NAME}" "$*"
}

fail() {
  printf '[%s] ERROR: %s\n' "${SCRIPT_NAME}" "$*" >&2
  exit 1
}

cleanup() {
  if [[ -n "${temporary_directory}" && -d "${temporary_directory}" ]]; then
    rm -rf -- "${temporary_directory}"
  fi
}

trap cleanup EXIT

for argument in "$@"; do
  case "${argument}" in
    --remove-docker-conflicts)
      # Retained so existing documentation and automation do not break.
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "Unknown option: ${argument}. Run ./${SCRIPT_NAME} --help."
      ;;
  esac
done

[[ -r /etc/os-release ]] || fail "/etc/os-release could not be read."
# shellcheck disable=SC1091
source /etc/os-release

[[ "${ID:-}" == "ubuntu" ]] || fail "This script supports Ubuntu only (detected: ${ID:-unknown})."
[[ "$(dpkg --print-architecture)" =~ ^(amd64|arm64|armhf|s390x|ppc64el)$ ]] \
  || fail "The current architecture is not supported by Docker Engine for Ubuntu."

if [[ "${EUID}" -eq 0 ]]; then
  SUDO=()
  invoking_user="${SUDO_USER:-root}"
else
  command -v sudo >/dev/null 2>&1 || fail "sudo is required. Install sudo or run this script as root."
  sudo -v
  SUDO=(sudo)
  invoking_user="${USER}"
fi

command -v apt-get >/dev/null 2>&1 || fail "apt-get is required."
command -v dpkg >/dev/null 2>&1 || fail "dpkg is required."

log "Installing base command-line dependencies..."
"${SUDO[@]}" apt-get update
"${SUDO[@]}" apt-get install -y \
  ca-certificates \
  curl \
  gettext-base \
  git \
  gnupg \
  grep \
  jq \
  make \
  openssl \
  ripgrep

temporary_directory="$(mktemp -d)"

log "Configuring Docker's official APT repository..."
"${SUDO[@]}" install -m 0755 -d /etc/apt/keyrings
curl -fsSL "${DOCKER_KEY_URL}" -o "${temporary_directory}/docker.asc"
docker_key_fingerprint="$(
  gpg --show-keys --with-colons "${temporary_directory}/docker.asc" |
    awk -F: '$1 == "fpr" { print $10; exit }'
)"
[[ -n "${docker_key_fingerprint}" ]] || fail "Docker's signing key could not be inspected."
"${SUDO[@]}" install -m 0644 "${temporary_directory}/docker.asc" /etc/apt/keyrings/docker.asc

ubuntu_codename="${UBUNTU_CODENAME:-${VERSION_CODENAME:-}}"
[[ -n "${ubuntu_codename}" ]] || fail "The Ubuntu codename could not be determined."
architecture="$(dpkg --print-architecture)"

docker_source="${temporary_directory}/docker.sources"
cat >"${docker_source}" <<EOF
Types: deb
URIs: ${DOCKER_REPOSITORY_URL}
Suites: ${ubuntu_codename}
Components: stable
Architectures: ${architecture}
Signed-By: /etc/apt/keyrings/docker.asc
EOF
"${SUDO[@]}" install -m 0644 "${docker_source}" /etc/apt/sources.list.d/docker.sources

conflicting_packages=(
  docker.io
  docker-compose
  docker-compose-v2
  docker-doc
  podman-docker
  containerd
  runc
)
installed_conflicts=()
for package in "${conflicting_packages[@]}"; do
  if dpkg-query -W -f='${db:Status-Abbrev}' "${package}" 2>/dev/null | grep -q '^ii'; then
    installed_conflicts+=("${package}")
  fi
done

if ((${#installed_conflicts[@]} > 0)); then
  log "Replacing Docker packages that conflict with Docker's official packages: ${installed_conflicts[*]}"
  log "Existing Docker images, containers, and volumes under /var/lib/docker will be preserved."
  if [[ -d /run/systemd/system ]] && command -v systemctl >/dev/null 2>&1; then
    log "Stopping the existing Docker service and socket before replacement..."
    "${SUDO[@]}" systemctl stop docker.service docker.socket
  fi
  "${SUDO[@]}" apt-get remove -y "${installed_conflicts[@]}"
fi

log "Configuring GitHub CLI's official APT repository..."
curl -fsSL "${GH_KEY_URL}" -o "${temporary_directory}/githubcli-archive-keyring.gpg"
mapfile -t gh_key_fingerprints < <(
  gpg --show-keys --with-colons "${temporary_directory}/githubcli-archive-keyring.gpg" |
    awk -F: '$1 == "fpr" { print $10 }'
)
gh_key_is_trusted=false
for fingerprint in "${gh_key_fingerprints[@]}"; do
  if [[ "${fingerprint}" == "${GH_KEY_FINGERPRINT_1}" || "${fingerprint}" == "${GH_KEY_FINGERPRINT_2}" ]]; then
    gh_key_is_trusted=true
    break
  fi
done
[[ "${gh_key_is_trusted}" == "true" ]] || fail "GitHub CLI's signing key fingerprint did not match an official fingerprint."
"${SUDO[@]}" install -m 0644 \
  "${temporary_directory}/githubcli-archive-keyring.gpg" \
  /etc/apt/keyrings/githubcli-archive-keyring.gpg

gh_source="${temporary_directory}/github-cli.list"
printf 'deb [arch=%s signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] %s stable main\n' \
  "${architecture}" "${GH_REPOSITORY_URL}" >"${gh_source}"
"${SUDO[@]}" install -m 0644 "${gh_source}" /etc/apt/sources.list.d/github-cli.list

log "Installing Docker Engine, Compose, Buildx, and GitHub CLI..."
"${SUDO[@]}" apt-get update
"${SUDO[@]}" apt-get install -y \
  containerd.io \
  docker-buildx-plugin \
  docker-ce \
  docker-ce-cli \
  docker-compose-plugin \
  gh

if [[ -d /run/systemd/system ]] && command -v systemctl >/dev/null 2>&1; then
  log "Enabling and starting Docker..."
  "${SUDO[@]}" systemctl daemon-reload
  "${SUDO[@]}" systemctl reset-failed docker.service docker.socket || true
  "${SUDO[@]}" systemctl enable docker.service docker.socket
  "${SUDO[@]}" systemctl restart docker.socket
  "${SUDO[@]}" systemctl restart docker.service
elif command -v service >/dev/null 2>&1; then
  log "Starting Docker..."
  "${SUDO[@]}" service docker restart
else
  fail "Docker was installed, but no supported service manager was found."
fi

if [[ "${invoking_user}" != "root" ]]; then
  if ! id -nG "${invoking_user}" | tr ' ' '\n' | grep -qx docker; then
    log "Adding ${invoking_user} to the docker group..."
    "${SUDO[@]}" usermod -aG docker "${invoking_user}"
    docker_group_changed=true
  else
    docker_group_changed=false
  fi
else
  docker_group_changed=false
fi

log "Verifying installed commands..."
docker --version
docker compose version
docker buildx version
make --version | sed -n '1p'
git --version
gh --version | sed -n '1p'
rg --version | sed -n '1p'
"${SUDO[@]}" docker info --format 'Docker daemon: Server {{.ServerVersion}}'

printf '\nHost dependencies were installed successfully.\n'
if [[ "${docker_group_changed}" == "true" ]]; then
  printf 'Sign out of WSL and open it again (or run "newgrp docker") before using Docker without sudo.\n'
fi
printf 'Run "gh auth login" separately when GitHub authentication is needed.\n'
