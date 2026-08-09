FROM mcr.microsoft.com/devcontainers/base:debian

# ca-certificates, curl, git are already in the devcontainers base image.
# The three tools below all run before devbox is usable, so they have to
# come from apt — devbox packages live under /home/vscode (masked by the
# agent-home volume) and require the vscode-user shellenv to be on PATH.
#
# gosu:  drops privileges in the entrypoint. Invoked by root before any
#        user profile is set up, so it can't come from devbox.
# jq:    merges devbox.stack.json + devbox.tools.json at build time
#        (see cli/lib/devbox.bash). Bootstrap: we need jq to install
#        devbox's config, so it can't itself come from devbox.
# rsync: refreshes the image-side /home/vscode snapshot into the
#        agent-home volume in app-init.sh. Root-invoked and it's the
#        very tool syncing devbox into the volume — can't live in
#        the volume it syncs.
RUN apt-get update \
    && apt-get install -y --no-install-recommends gosu jq rsync \
    && rm -rf /var/lib/apt/lists/*

COPY --chmod=755 sandcat/scripts/app-init.sh /usr/local/bin/app-init.sh
COPY --chmod=755 sandcat/scripts/app-user-init.sh /usr/local/bin/app-user-init.sh
COPY --chown=vscode:vscode sandcat/tmux.conf /home/vscode/.tmux.conf

USER vscode

ENV LANG="en_US.UTF-8"

# Install Claude Code (native binary — no Node.js required).
RUN curl -fsSL https://claude.ai/install.sh | bash
# Install rtk (Rust Token Killer) — compresses shell command output so AI
# agents consume fewer tokens per command. Installed system-wide so the
# agent-home volume can't mask the binary on upgrade. Disable at init
# time with `sandcat init --features no-rtk` or `SANDCAT_RTK=false`.
USER root
RUN curl -fsSL https://raw.githubusercontent.com/rtk-ai/rtk/master/install.sh | RTK_INSTALL_DIR=/usr/local/bin sh
USER vscode

USER root
# Prep /nix owned by vscode. Single-user Nix (which we need — see below)
# requires a user-writable /nix.
RUN mkdir -m 0755 /nix && chown vscode /nix
# Install devbox launcher. The installer writes /usr/local/bin/devbox as
# mode 711 (root:root, no read bit for others). It's a bash launcher, not
# a native binary, so bash can't read the shebang as non-root and exec
# fails with EACCES. Force 0755 so vscode can execute it.
RUN curl -fsSL https://get.jetify.com/devbox | bash -s -- -f \
    && chmod 0755 /usr/local/bin/devbox

USER vscode
# Preinstall Nix in single-user mode BEFORE devbox gets a chance.
# `devbox global install` on a fresh system triggers Determinate's
# `nix-installer`, which defaults to multi-user (systemd-managed daemon)
# whenever sudo is available. Containers based on debian have sudo but
# no systemd, so the daemon never starts, and later `nix` commands fail
# with "cannot connect to socket at /nix/var/nix/daemon-socket/socket"
# and "opening lock file /nix/var/nix/db/big-lock: Permission denied".
# The classic nixos.org installer with --no-daemon reliably yields a
# single-user install that works under vscode's UID.
RUN curl -fsSL -L https://nixos.org/nix/install \
    | sh -s -- --no-daemon --no-modify-profile --yes
ENV PATH="/home/vscode/.nix-profile/bin:${PATH}"

# Split into two layers so the common case (user drops a tool into
# devbox.tools.json) doesn't invalidate the expensive stack install.
#
# Layer A — stack packages only. Cached whenever devbox.stack.json is
# unchanged. This is the ~11-minute step on a cold build; keeping it
# out of the same COPY as devbox.tools.json lets a tools-only edit
# skip it entirely.
COPY --chown=vscode:vscode devbox.stack.json /home/vscode/.local/share/devbox/global/default/devbox.json
RUN --mount=type=cache,target=/home/vscode/.cache/nix,uid=1000,gid=1000 \
    . /home/vscode/.nix-profile/etc/profile.d/nix.sh \
 && devbox global install

# Layer B — merge stack + user tools, install the delta, and bump the
# Nix priority of tools entries so they win file collisions against stack
# entries (e.g. tools' openjdk17 wins over stack's temurin-bin-25 for
# bin/java). /nix/store from Layer A carries over via the image filesystem,
# so devbox here only downloads what's new in the merged config.
#
# Tools override behavior:
#   * Same package name (jq): the merge drops stack's entry; tools' entry
#     is the only one devbox installs. No priority bump needed.
#   * Cross-family collision (openjdk17 vs temurin-bin-25): merge keeps
#     both; devbox installs both; then we snapshot which manifest entries
#     appeared in this layer (tools additions) and re-add each of their
#     store paths with --priority 3. Nix priority-based collision then
#     picks the priority-3 entry, and the sandcat Java block resolves
#     JAVA_HOME through bin/java to the tools JDK.
#
# devbox.loc[k] is the optional-file COPY idiom: picks up a committed
# devbox.lock without failing when absent.
COPY --chown=vscode:vscode devbox.stack.json devbox.tools.json /tmp/
COPY --chown=vscode:vscode devbox.loc[k] /home/vscode/.local/share/devbox/global/default/
RUN --mount=type=cache,target=/home/vscode/.cache/nix,uid=1000,gid=1000 \
    NIX_PROFILE=$HOME/.local/share/devbox/global/default/.devbox/nix/profile/default \
 && jq -r '[.. | .storePaths? // empty] | flatten | .[]' $NIX_PROFILE/manifest.json \
    | sort -u > /tmp/paths.before.txt \
 && jq -s 'def pkgname: split("@")[0]; (.[1].packages // []) as $tools | ($tools | map(pkgname)) as $tnames | {packages: (((.[0].packages // []) | map(select(pkgname as $n | $tnames | index($n) | not))) + $tools | unique)}' /tmp/devbox.stack.json /tmp/devbox.tools.json \
    > /home/vscode/.local/share/devbox/global/default/devbox.json \
 && . /home/vscode/.nix-profile/etc/profile.d/nix.sh \
 && devbox global install \
 && jq -r '[.. | .storePaths? // empty] | flatten | .[]' $NIX_PROFILE/manifest.json \
    | sort -u > /tmp/paths.after.txt \
 && comm -23 /tmp/paths.after.txt /tmp/paths.before.txt > /tmp/tools-paths.txt \
 && while IFS= read -r storepath; do \
      [ -n "$storepath" ] || continue; \
      nix --extra-experimental-features "nix-command flakes" \
          profile add --priority 3 --profile $NIX_PROFILE "$storepath"; \
    done < /tmp/tools-paths.txt \
 && rm /tmp/devbox.stack.json /tmp/devbox.tools.json /tmp/paths.before.txt /tmp/paths.after.txt /tmp/tools-paths.txt

# BuildKit --mount=type=cache above persists ~/.cache/nix (Nix's HTTP
# cache for .nar downloads) between builds on the same host. First cold
# build is unchanged (empty cache); subsequent stack changes reuse
# already-downloaded .nar files. Not portable to CI without a persistent
# cache action; harmless if absent (the RUN just downloads fresh).

USER root
# Hook packages into every shell via the sandcat profile.d sourcing
# machinery (app-init.sh sources /etc/profile.d/sandcat-*.sh into login
# shells and /etc/bash.bashrc). Source nix.sh first so `devbox` and
# the packages it manages resolve.
RUN printf '%s\n' \
    '. /home/vscode/.nix-profile/etc/profile.d/nix.sh 2>/dev/null || true' \
    'if command -v devbox >/dev/null 2>&1; then' \
    '    eval "$(devbox global shellenv 2>/dev/null)" || true' \
    'fi' > /etc/profile.d/sandcat-devbox.sh
USER vscode

# If a JDK is installed via devbox (java or scala stack, or a user entry
# in devbox.tools.json), bake JAVA_HOME and JAVA_TOOL_OPTIONS into .bashrc
# so VS Code's env probe picks them up before the entrypoint runs. Without
# JAVA_HOME, JVM tooling like Metals fails to find the JDK.
#
# JDK-distribution-agnostic detection: devbox always exposes bin/java in
# its profile as a symlink to the actual JDK inside /nix/store/. Following
# that symlink and stripping bin/ gives the canonical JAVA_HOME for
# whichever distribution the user picked (openjdk, temurin-bin-*, jdk,
# jetbrains.jdk*, or any future one) — no hardcoded layout assumptions.
# Every valid JDK derivation has $JAVA_HOME/lib/security/cacerts inside.
#
# JAVA_TOOL_OPTIONS points to a trust store copy that app-user-init.sh
# populates with the mitmproxy CA at runtime; until then it holds the
# default Java CAs (harmless).
RUN JAVA_BIN="$HOME/.local/share/devbox/global/default/.devbox/nix/profile/default/bin/java"; \
    if [ -e "$JAVA_BIN" ]; then \
      DEVBOX_JAVA="$(dirname $(dirname $(readlink -f "$JAVA_BIN")))"; \
      dir="$HOME/.local/share/sandcat"; mkdir -p "$dir"; \
      ln -sfn "$DEVBOX_JAVA" "$dir/java-home"; \
      { echo ''; \
        echo '# sandcat-java-env'; \
        echo '[ -L "$HOME/.local/share/sandcat/java-home" ] && export JAVA_HOME="$HOME/.local/share/sandcat/java-home"'; \
        echo '[ -f "$HOME/.local/share/sandcat/cacerts" ] && export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$HOME/.local/share/sandcat/cacerts -Djavax.net.ssl.trustStorePassword=changeit"'; \
      } >> "$HOME/.bashrc"; \
    fi

# Pre-create ~/.claude so Docker bind-mounts (CLAUDE.md, agents/, commands/)
# don't cause it to be created as root-owned.
RUN mkdir -p /home/vscode/.claude
RUN echo 'alias claude-yolo="claude --dangerously-skip-permissions"' >> /home/vscode/.bashrc

USER root
# Snapshot the image-side /home/vscode state that the agent-home volume
# will mask at runtime (devbox profile with symlinks into /nix/store, the
# sandcat helper dir with java-home + baseline cacerts, and .bashrc env
# hooks). app-init.sh rsyncs this back into the volume when the snapshot
# hash changes, so rebuilds that add/remove packages or switch JDKs take
# effect without `docker compose down -v` (which would wipe auth Claude
# Code and force the IDE backend to re-upload).
#
# The hash covers merged devbox.json + .bashrc — the two files that
# capture "what packages devbox installed" and "what env we bake". Any
# stack/tools/Java change flips at least one of them; unchanged rebuilds
# leave the hash stable so app-init.sh skips the sync entirely.
RUN mkdir -p /opt/sandcat/snapshots \
 && cp -a /home/vscode/.local/share/devbox /opt/sandcat/snapshots/devbox \
 && if [ -d /home/vscode/.local/share/sandcat ]; then \
      cp -a /home/vscode/.local/share/sandcat /opt/sandcat/snapshots/sandcat; \
    fi \
 && cp /home/vscode/.bashrc /opt/sandcat/snapshots/bashrc \
 && sha256sum \
      /opt/sandcat/snapshots/devbox/global/default/devbox.json \
      /opt/sandcat/snapshots/bashrc \
    | sha256sum | cut -d' ' -f1 > /opt/sandcat/snapshots/hash

ENTRYPOINT ["/usr/local/bin/app-init.sh"]
