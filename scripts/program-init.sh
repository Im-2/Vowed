. "$(dirname "$0")/wsl-env.sh"
cd /tmp && rm -rf vowed_init && anchor init vowed_init --no-install --test-template rust 2>&1 | tail -15; ls -R /tmp/vowed_init | head -30
