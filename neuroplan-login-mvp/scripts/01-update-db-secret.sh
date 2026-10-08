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
command -v jq >/dev/null 2>&1 || { echo "[FAIL] jq not found" >&2; exit 1; }
"$KUBE_CLI" get namespace "$NAMESPACE" >/dev/null
"$KUBE_CLI" -n "$NAMESPACE" get secret "$SECRET_NAME" >/dev/null \
  || { echo "[FAIL] existing secret ${NAMESPACE}/${SECRET_NAME} is required" >&2; exit 2; }

JWT_SECRET_BEFORE="$("$KUBE_CLI" -n "$NAMESPACE" get secret "$SECRET_NAME" \
  -o jsonpath='{.data.JWT_SECRET_BASE64}')"
[[ -n "$JWT_SECRET_BEFORE" ]] \
  || { echo "[FAIL] existing secret has no JWT_SECRET_BASE64; refusing DB-only update" >&2; exit 2; }

read -r -p "DB username [ir_app]: " DB_USERNAME
DB_USERNAME="${DB_USERNAME:-ir_app}"
read -r -s -p "DB password: " DB_PASSWORD
echo
[[ -n "$DB_PASSWORD" ]] || { echo "[FAIL] DB password is empty" >&2; exit 2; }

SECRET_DIR="$(mktemp -d /tmp/neuroplan-db-secret-update.XXXXXX)"
cleanup() {
  rm -f -- \
    "$SECRET_DIR/DB_USERNAME" \
    "$SECRET_DIR/DB_PASSWORD" \
    "$SECRET_DIR/patch.json"
  rmdir -- "$SECRET_DIR" 2>/dev/null || true
}
trap cleanup EXIT
chmod 0700 "$SECRET_DIR"
umask 077

printf '%s' "$DB_USERNAME" >"$SECRET_DIR/DB_USERNAME"
printf '%s' "$DB_PASSWORD" >"$SECRET_DIR/DB_PASSWORD"
unset DB_PASSWORD

# dry-run 결과에서 data만 남겨 DB_USERNAME·DB_PASSWORD만 merge patch한다.
"$KUBE_CLI" -n "$NAMESPACE" create secret generic "$SECRET_NAME" \
  --from-file=DB_USERNAME="$SECRET_DIR/DB_USERNAME" \
  --from-file=DB_PASSWORD="$SECRET_DIR/DB_PASSWORD" \
  --dry-run=client -o json \
  | jq '{data: .data}' >"$SECRET_DIR/patch.json"

"$KUBE_CLI" -n "$NAMESPACE" patch secret "$SECRET_NAME" \
  --type=merge --patch-file="$SECRET_DIR/patch.json" >/dev/null

JWT_SECRET_AFTER="$("$KUBE_CLI" -n "$NAMESPACE" get secret "$SECRET_NAME" \
  -o jsonpath='{.data.JWT_SECRET_BASE64}')"
[[ "$JWT_SECRET_BEFORE" == "$JWT_SECRET_AFTER" ]] \
  || { echo "[FAIL] JWT_SECRET_BASE64 changed unexpectedly; inspect the Secret before rollout" >&2; exit 1; }

echo "[PASS] ${NAMESPACE}/${SECRET_NAME} DB credentials updated; JWT signing key preserved"
"$KUBE_CLI" -n "$NAMESPACE" get secret "$SECRET_NAME" \
  -o go-template='{{range $k, $v := .data}}{{$k}} {{end}}{{"\n"}}'
