#!/usr/bin/env bash

# Custom fork suffix - change this to customize your version
FORK_SUFFIX="-pars5555"

source tailscale.version || echo >&2 "no tailscale.version file found"
if [[ -z "${VERSION_LONG}" ]]; then
    exit 1
fi

# Append fork suffix to version
echo "-X tailscale.com/version.longStamp=${VERSION_LONG}${FORK_SUFFIX}"
echo "-X tailscale.com/version.shortStamp=${SHORT_VERSION}"
echo "-X tailscale.com/version.gitCommitStamp=${COMMIT}"
echo "-X tailscale.com/version.extraGitCommitStamp="
