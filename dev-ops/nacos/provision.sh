#!/bin/sh
set -eu

NACOS_ADDR="${NACOS_ADDR:-http://nacos:8848}"
GROUP="${NACOS_GROUP:-AI_GROUP}"
CONFIG_DIR="${CONFIG_DIR:-/nacos-config}"
TOKEN="${AI_GROUP_INTERNAL_TOKEN:-change-me-internal-token-32bytes-ok}"
SECRET="${AI_GROUP_IDENTITY_SIGNING_SECRET:-change-me-signing-secret-32bytes-ok}"

publish() {
  data_id="$1"
  type="$2"
  file="$3"
  echo "publishing ${data_id}"
  curl -fsS -X POST "${NACOS_ADDR}/nacos/v1/cs/configs" \
    --data-urlencode "dataId=${data_id}" \
    --data-urlencode "group=${GROUP}" \
    --data-urlencode "type=${type}" \
    --data-urlencode "content@${file}"
  echo
}

i=0
while [ "$i" -lt 60 ]; do
  if curl -fsS "${NACOS_ADDR}/nacos/actuator/health" >/dev/null; then
    break
  fi
  i=$((i + 1))
  sleep 2
done

# Nacos 2.4 does not create the default admin until this API runs.
# Java clients still POST /auth/login with nacos/nacos even when NACOS_AUTH_ENABLE=false.
init_body="$(curl -sS -X POST "${NACOS_ADDR}/nacos/v1/auth/users/admin" \
  --data-urlencode "username=nacos" \
  --data-urlencode "password=nacos" || true)"
echo "nacos admin init: ${init_body}"

identity_tmp="$(mktemp)"
sed -e "s|\${AI_GROUP_INTERNAL_TOKEN:change-me-internal-token-32bytes-ok}|${TOKEN}|g" \
    -e "s|\${AI_GROUP_IDENTITY_SIGNING_SECRET:change-me-signing-secret-32bytes-ok}|${SECRET}|g" \
    "${CONFIG_DIR}/ai-group-identity.yml" > "${identity_tmp}"

publish "ai-group-identity.yml" "yaml" "${identity_tmp}"
publish "gateway-routes.yml" "yaml" "${CONFIG_DIR}/gateway-routes.yml"
publish "gateway-sentinel-flow.json" "json" "${CONFIG_DIR}/gateway-sentinel-flow.json"
publish "gateway-sentinel-degrade.json" "json" "${CONFIG_DIR}/gateway-sentinel-degrade.json"
publish "pay-sentinel-feign.json" "json" "${CONFIG_DIR}/pay-sentinel-feign.json"
publish "pay-sentinel-feign-flow.json" "json" "${CONFIG_DIR}/pay-sentinel-feign-flow.json"
rm -f "${identity_tmp}"
echo "nacos provision complete"
