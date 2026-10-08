#!/usr/bin/env bash
# 기존 인증 Secret의 JWT 서명 키를 보존한 채 DB 접속 정보만 교체한다.
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
"$KUBE_CLI" get namespace "$NAMESPACE" >/dev/null
"$KUBE_CLI" -n "$NAMESPACE" get secret "$SECRET_NAME" >/dev/null   || { echo "[FAIL] existing secret ${NAMESPACE}/${SECRET_NAME} is required" >&2; exit 2; }

JWT_SECRET_BEFORE="$("$KUBE_CLI" -n "$NAMESPACE" get secret "$SECRET_NAME"   -o jsonpath='{.data.JWT_SECRET_BASE64}')"
[[ -n "$JWT_SECRET_BEFORE" ]]   || { echo "[FAIL] existing secret has no JWT_SECRET_BASE64; refusing DB-only update" >&2; exit 2; }

read -r -p "DB username [ir_app]: " DB_USERNAME
DB_USERNAME="${DB_USERNAME:-ir_app}"
read -r -s -p "DB password: " DB_PASSWORD
echo
[[ -n "$DB_PASSWORD" ]] || { echo "[FAIL] DB password is empty" >&2; exit 2; }

SECRET_DIR="$(mktemp -d /tmp/neuroplan-db-secret-update.XXXXXX)"
cleanup() {
  rm -f -- "$SECRET_DIR/patch.json"
  rmdir -- "$SECRET_DIR" 2>/dev/null || true
}
trap cleanup EXIT
chmod 0700 "$SECRET_DIR"
umask 077

"$KUBE_CLI" -n "$NAMESPACE" create secret generic "$SECRET_NAME"   --from-literal=DB_USERNAME="$DB_USERNAME"   --from-literal=DB_PASSWORD="$DB_PASSWORD"   --dry-run=client -o json >"$SECRET_DIR/patch.json"
unset DB_PASSWORD

"$KUBE_CLI" -n "$NAMESPACE" patch secret "$SECRET_NAME"   --type=merge --patch-file="$SECRET_DIR/patch.json" >/dev/null

JWT_SECRET_AFTER="$("$KUBE_CLI" -n "$NAMESPACE" get secret "$SECRET_NAME"   -o jsonpath='{.data.JWT_SECRET_BASE64}')"
[[ "$JWT_SECRET_BEFORE" == "$JWT_SECRET_AFTER" ]]   || { echo "[FAIL] JWT_SECRET_BASE64 changed; DB update has been rejected" >&2; exit 1; }

echo "[PASS] ${NAMESPACE}/${SECRET_NAME} DB credentials updated; JWT signing key preserved"
"$KUBE_CLI" -n "$NAMESPACE" get secret "$SECRET_NAME"   -o jsonpath='keys={.data.DB_USERNAME},{.data.DB_PASSWORD},{.data.JWT_SECRET_BASE64}{"\n"}'
