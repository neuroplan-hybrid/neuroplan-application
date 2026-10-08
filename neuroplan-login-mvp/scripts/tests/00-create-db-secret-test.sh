#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="$ROOT/scripts/00-create-db-secret.sh"
WORKDIR="$(mktemp -d)"
cleanup() {
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

MOCK_KUBE="$WORKDIR/mock-kubectl"
CREATE_MARKER="$WORKDIR/secret-created"

cat > "$MOCK_KUBE" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail

args="$*"
case "$args" in
  *"config get-contexts rosa-test"*)
    exit 0
    ;;
  *"get namespace neuroplan"*)
    exit 0
    ;;
  *"get secret neuroplan-auth-secrets -o name"*)
    echo 'Error from server (NotFound): secrets "neuroplan-auth-secrets" not found' >&2
    exit 1
    ;;
  *"create secret generic neuroplan-auth-secrets"*)
    for arg in "$@"; do
      case "$arg" in
        --from-file=*)
          source_path="${arg#*=}"
          source_path="${source_path#*=}"
          test -s "$source_path"
          ;;
      esac
    done
    : > "$MOCK_CREATE_MARKER"
    exit 0
    ;;
  *"get secret neuroplan-auth-secrets -o go-template="*)
    printf 'DB_PASSWORD DB_USERNAME JWT_SECRET_BASE64\n'
    exit 0
    ;;
esac

echo "unexpected mock kubectl invocation: $args" >&2
exit 1
MOCK
chmod 700 "$MOCK_KUBE"

printf 'ir_app\nunit-test-password\n' | \
  MOCK_CREATE_MARKER="$CREATE_MARKER" \
  KUBE_CLI="$MOCK_KUBE" \
  KUBE_CONTEXT=rosa-test \
  NAMESPACE=neuroplan \
  ROSA_TARGET_CONFIRM=true \
  JWT_SECRET_BASE64=unit-test-jwt \
  "$SCRIPT" >/dev/null

test -f "$CREATE_MARKER"
echo "00-create-db-secret NotFound path: PASS"
