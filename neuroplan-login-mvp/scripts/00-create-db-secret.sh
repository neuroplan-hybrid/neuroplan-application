#!/usr/bin/env bash
set -Eeuo pipefail

NAMESPACE="${NAMESPACE:-application}"
SECRET_NAME="${SECRET_NAME:-neuroplan-auth-secrets}"
KUBE_CLI="${KUBE_CLI:-}"

if [[ -z "$KUBE_CLI" ]]; then
  if command -v kubectl >/dev/null 2>&1; then KUBE_CLI=kubectl
  elif command -v oc >/dev/null 2>&1; then KUBE_CLI=oc
  else echo "[FAIL] kubectl or oc not found" >&2; exit 1
  fi
fi
command -v "$KUBE_CLI" >/dev/null 2>&1 || { echo "[FAIL] $KUBE_CLI not found" >&2; exit 1; }
command -v openssl >/dev/null 2>&1 || { echo "[FAIL] openssl not found" >&2; exit 1; }
"$KUBE_CLI" get namespace "$NAMESPACE" >/dev/null

read -r -p "DB username [ir_app]: " DB_USERNAME
DB_USERNAME="${DB_USERNAME:-ir_app}"
read -r -s -p "DB password: " DB_PASSWORD
echo
[[ -n "$DB_PASSWORD" ]] || { echo "[FAIL] DB password is empty" >&2; exit 2; }

SECRET_DIR="$(mktemp -d /tmp/neuroplan-auth-secret.XXXXXX)"
cleanup() {
  rm -f -- \
    "$SECRET_DIR/DB_USERNAME" \
    "$SECRET_DIR/DB_PASSWORD" \
    "$SECRET_DIR/JWT_SECRET_BASE64"
  rmdir -- "$SECRET_DIR" 2>/dev/null || true
}
trap cleanup EXIT
chmod 0700 "$SECRET_DIR"
umask 077
printf '%s' "$DB_USERNAME" >"$SECRET_DIR/DB_USERNAME"
printf '%s' "$DB_PASSWORD" >"$SECRET_DIR/DB_PASSWORD"
unset DB_PASSWORD

# ROSA 최초 Secret 생성 시 기존 On-Prem JWT 키를 환경변수로 전달할 수 있다.
# 값이 없으면 새 키를 생성한다. DB 전환에는 01-update-db-secret.sh를 사용한다.
if [[ -n "${JWT_SECRET_BASE64:-}" ]]; then
  [[ "$JWT_SECRET_BASE64" != *
"$KUBE_CLI" -n "$NAMESPACE" create secret generic "$SECRET_NAME" \
  --from-file=DB_USERNAME="$SECRET_DIR/DB_USERNAME" \
  --from-file=DB_PASSWORD="$SECRET_DIR/DB_PASSWORD" \
  --from-file=JWT_SECRET_BASE64="$SECRET_DIR/JWT_SECRET_BASE64" \
  --dry-run=client -o yaml | "$KUBE_CLI" apply -f -

echo "[PASS] secret ${NAMESPACE}/${SECRET_NAME} created or updated"
"$KUBE_CLI" -n "$NAMESPACE" get secret "$SECRET_NAME" \
  -o jsonpath='keys={.data.DB_USERNAME},{.data.DB_PASSWORD},{.data.JWT_SECRET_BASE64}{"\n"}'
\n'* && "$JWT_SECRET_BASE64" != *
"$KUBE_CLI" -n "$NAMESPACE" create secret generic "$SECRET_NAME" \
  --from-file=DB_USERNAME="$SECRET_DIR/DB_USERNAME" \
  --from-file=DB_PASSWORD="$SECRET_DIR/DB_PASSWORD" \
  --from-file=JWT_SECRET_BASE64="$SECRET_DIR/JWT_SECRET_BASE64" \
  --dry-run=client -o yaml | "$KUBE_CLI" apply -f -

echo "[PASS] secret ${NAMESPACE}/${SECRET_NAME} created or updated"
"$KUBE_CLI" -n "$NAMESPACE" get secret "$SECRET_NAME" \
  -o jsonpath='keys={.data.DB_USERNAME},{.data.DB_PASSWORD},{.data.JWT_SECRET_BASE64}{"\n"}'
\r'* ]] \
    || { echo "[FAIL] JWT_SECRET_BASE64 must be one line" >&2; exit 2; }
  printf '%s' "$JWT_SECRET_BASE64" >"$SECRET_DIR/JWT_SECRET_BASE64"
else
  openssl rand -base64 48 | tr -d '\r\n' >"$SECRET_DIR/JWT_SECRET_BASE64"
fi
unset JWT_SECRET_BASE64

"$KUBE_CLI" -n "$NAMESPACE" create secret generic "$SECRET_NAME" \
  --from-file=DB_USERNAME="$SECRET_DIR/DB_USERNAME" \
  --from-file=DB_PASSWORD="$SECRET_DIR/DB_PASSWORD" \
  --from-file=JWT_SECRET_BASE64="$SECRET_DIR/JWT_SECRET_BASE64" \
  --dry-run=client -o yaml | "$KUBE_CLI" apply -f -

echo "[PASS] secret ${NAMESPACE}/${SECRET_NAME} created or updated"
"$KUBE_CLI" -n "$NAMESPACE" get secret "$SECRET_NAME" \
  -o jsonpath='keys={.data.DB_USERNAME},{.data.DB_PASSWORD},{.data.JWT_SECRET_BASE64}{"\n"}'
