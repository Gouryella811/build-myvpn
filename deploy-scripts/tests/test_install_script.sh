#!/usr/bin/env bash
# Automated test suite for deploy-scripts/install_singbox.sh
# Runs locally on macOS or Linux with isolated mocked binaries (no real VPS or Docker required).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_SCRIPT="${SCRIPT_DIR}/install_singbox.sh"

TMP_BASE="$(mktemp -d)"
trap 'rm -rf "$TMP_BASE"' EXIT

PASS_COUNT=0
FAIL_COUNT=0

assert_eq() {
  local name="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    echo "  [PASS] ${name}"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    echo "  [FAIL] ${name}: expected='${expected}' actual='${actual}'" >&2
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

validate_json_lines() {
  local output="$1"
  python3 - "$output" <<'PYEOF'
import json, sys
lines = [l for l in sys.argv[1].splitlines() if l.strip()]
assert len(lines) > 0, "Expected at least one JSON line"
for i, line in enumerate(lines, 1):
    try:
        obj = json.loads(line)
        assert "step" in obj and "status" in obj and "code" in obj
    except Exception as e:
        raise SystemExit(f"Invalid JSON on line {i}: {line} ({e})")
PYEOF
}

setup_mock_env() {
  local case_dir="$1"
  local os_id="${2:-ubuntu}"
  local os_ver="${3:-24.04}"
  local cc_now="${4:-cubic}"
  local cc_avail="${5:-cubic reno bbr}"

  mkdir -p "${case_dir}/root/usr/local/bin" "${case_dir}/root/etc/sysctl.d" "${case_dir}/bin" "${case_dir}/proc"

  cat > "${case_dir}/os-release" <<EOF
ID=${os_id}
VERSION_ID="${os_ver}"
EOF
  printf '%s\n' "$cc_now" > "${case_dir}/proc/tcp_cc"
  printf '%s\n' "$cc_avail" > "${case_dir}/proc/tcp_avail"

  # Mock sing-box binary (pre-installed at target version so no network download is needed)
  cat > "${case_dir}/root/usr/local/bin/sing-box" <<'EOF'
#!/usr/bin/env bash
case "${1:-}" in
  version)
    echo "sing-box version 1.12.12" ;;
  generate)
    case "${2:-}" in
      uuid) echo "11111111-2222-3333-4444-555555555555" ;;
      reality-keypair)
        echo "PrivateKey: mock_priv_key_xyz"
        echo "PublicKey: mock_pub_key_abc" ;;
      rand)
        if [[ "${4:-}" == "8" ]]; then echo "a1b2c3d4e5f60718"; else echo "hy2pass0123456789abcdef0123456789"; fi ;;
    esac ;;
  check)
    python3 -m json.tool "${3}" >/dev/null ;;
esac
EOF
  chmod +x "${case_dir}/root/usr/local/bin/sing-box"

  # Mock sysctl & ss & modprobe
  cat > "${case_dir}/bin/sysctl" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  cat > "${case_dir}/bin/ss" <<'EOF'
#!/usr/bin/env bash
if [[ -n "${MOCK_PORT_BUSY:-}" ]]; then
  echo "LISTEN 0 511 0.0.0.0:443 0.0.0.0:* users:((\"nginx\",pid=123,fd=6))"
fi
EOF
  cat > "${case_dir}/bin/modprobe" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
  chmod +x "${case_dir}/bin/sysctl" "${case_dir}/bin/ss" "${case_dir}/bin/modprobe"
}

echo "=== Test 1: Bash syntax check (bash -n) ==="
bash -n "$TARGET_SCRIPT"
assert_eq "bash -n syntax valid" "0" "$?"

echo "=== Test 2: Default reality install on Ubuntu 24.04 (auto BBR + TCP-only) ==="
C2="${TMP_BASE}/c2"
setup_mock_env "$C2" "ubuntu" "24.04" "cubic" "cubic reno bbr"
OUT2="$(PATH="${C2}/bin:${PATH}" \
  BUILD_MYVPN_ROOT_PREFIX="${C2}/root" \
  BUILD_MYVPN_OS_RELEASE="${C2}/os-release" \
  BUILD_MYVPN_PROC_TCP_CC="${C2}/proc/tcp_cc" \
  BUILD_MYVPN_PROC_TCP_AVAIL="${C2}/proc/tcp_avail" \
  BUILD_MYVPN_UNAME_M="x86_64" \
  BUILD_MYVPN_UNAME_R="6.8.0-45-generic" \
  BUILD_MYVPN_VIRT="kvm" \
  BUILD_MYVPN_FIXED_SNI="www.microsoft.com" \
  bash "$TARGET_SCRIPT" --host 203.0.113.10 --protocol reality)"
validate_json_lines "$OUT2"
assert_eq "JSON Lines valid (reality)" "0" "$?"
BBR2="$(printf '%s\n' "$OUT2" | tail -n1 | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["node"]["bbr_status"])')"
HY2_EN2="$(printf '%s\n' "$OUT2" | tail -n1 | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["node"]["hysteria2"]["enabled"])')"
assert_eq "BBR enabled on Ubuntu 24.04 KVM" "enabled" "$BBR2"
assert_eq "Hysteria2 disabled by default" "False" "$HY2_EN2"
assert_eq "BBR sysctl.d file created" "true" "$( [[ -f "${C2}/root/etc/sysctl.d/99-build-myvpn-bbr.conf" ]] && echo true || echo false )"
assert_eq "UDP sysctl.d file NOT created for reality" "false" "$( [[ -f "${C2}/root/etc/sysctl.d/99-build-myvpn-udp.conf" ]] && echo true || echo false )"

echo "=== Test 3: Opt-in dual mode (TCP 443 REALITY + UDP 443 Hysteria2 pure IPv4 cert pinning) ==="
C3="${TMP_BASE}/c3"
setup_mock_env "$C3" "debian" "12" "bbr" "cubic reno bbr"
OUT3="$(PATH="${C3}/bin:${PATH}" \
  BUILD_MYVPN_ROOT_PREFIX="${C3}/root" \
  BUILD_MYVPN_OS_RELEASE="${C3}/os-release" \
  BUILD_MYVPN_PROC_TCP_CC="${C3}/proc/tcp_cc" \
  BUILD_MYVPN_PROC_TCP_AVAIL="${C3}/proc/tcp_avail" \
  BUILD_MYVPN_UNAME_M="aarch64" \
  BUILD_MYVPN_UNAME_R="6.1.0-25-arm64" \
  BUILD_MYVPN_VIRT="kvm" \
  BUILD_MYVPN_FIXED_SNI="www.apple.com" \
  bash "$TARGET_SCRIPT" --host 203.0.113.20 --protocol dual)"
validate_json_lines "$OUT3"
BBR3="$(printf '%s\n' "$OUT3" | tail -n1 | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["node"]["bbr_status"])')"
TLS3="$(printf '%s\n' "$OUT3" | tail -n1 | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["node"]["hysteria2"]["tls_mode"])')"
PIN3="$(printf '%s\n' "$OUT3" | tail -n1 | python3 -c 'import json,sys; d=json.load(sys.stdin); print(len(d["node"]["hysteria2"]["certificate_sha256"]) > 30)')"
INBOUND_COUNT="$(python3 -c "import json; print(len(json.load(open('${C3}/root/etc/build-myvpn/config.json'))['inbounds']))")"
assert_eq "BBR idempotent already_enabled" "already_enabled" "$BBR3"
assert_eq "Hysteria2 IPv4 uses self_signed_pinned" "self_signed_pinned" "$TLS3"
assert_eq "Base64 DER certificate_sha256 generated" "True" "$PIN3"
assert_eq "Dual mode writes 2 inbounds on port 443" "2" "$INBOUND_COUNT"

echo "=== Test 4: --disable-hy2 strips UDP listener while preserving REALITY ==="
OUT4="$(PATH="${C3}/bin:${PATH}" \
  BUILD_MYVPN_ROOT_PREFIX="${C3}/root" \
  bash "$TARGET_SCRIPT" --disable-hy2)"
validate_json_lines "$OUT4"
INBOUND_AFTER="$(python3 -c "import json; d=json.load(open('${C3}/root/etc/build-myvpn/config.json'))['inbounds']; print(len(d), d[0]['type'])")"
assert_eq "Only vless inbound remains after --disable-hy2" "1 vless" "$INBOUND_AFTER"
assert_eq "UDP sysctl file removed" "false" "$( [[ -f "${C3}/root/etc/sysctl.d/99-build-myvpn-udp.conf" ]] && echo true || echo false )"

echo "=== Test 5: OpenVZ/LXC container skips BBR safely ==="
C5="${TMP_BASE}/c5"
setup_mock_env "$C5" "ubuntu" "22.04" "cubic" "cubic reno"
OUT5="$(PATH="${C5}/bin:${PATH}" \
  BUILD_MYVPN_ROOT_PREFIX="${C5}/root" \
  BUILD_MYVPN_OS_RELEASE="${C5}/os-release" \
  BUILD_MYVPN_PROC_TCP_CC="${C5}/proc/tcp_cc" \
  BUILD_MYVPN_PROC_TCP_AVAIL="${C5}/proc/tcp_avail" \
  BUILD_MYVPN_UNAME_M="x86_64" \
  BUILD_MYVPN_UNAME_R="5.15.0" \
  BUILD_MYVPN_VIRT="lxc" \
  BUILD_MYVPN_FIXED_SNI="www.microsoft.com" \
  bash "$TARGET_SCRIPT" --host 203.0.113.30 --dry-run)"
validate_json_lines "$OUT5"
BBR5="$(printf '%s\n' "$OUT5" | tail -n1 | python3 -c 'import json,sys; print(json.load(sys.stdin)["bbr_status"])')"
assert_eq "LXC container skips BBR cleanly" "skipped_container" "$BBR5"

echo "=== Test 6: Legacy kernel (<4.9) skips BBR safely without reboot ==="
C6="${TMP_BASE}/c6"
setup_mock_env "$C6" "debian" "11" "cubic" "cubic reno"
OUT6="$(PATH="${C6}/bin:${PATH}" \
  BUILD_MYVPN_ROOT_PREFIX="${C6}/root" \
  BUILD_MYVPN_OS_RELEASE="${C6}/os-release" \
  BUILD_MYVPN_PROC_TCP_CC="${C6}/proc/tcp_cc" \
  BUILD_MYVPN_PROC_TCP_AVAIL="${C6}/proc/tcp_avail" \
  BUILD_MYVPN_UNAME_M="x86_64" \
  BUILD_MYVPN_UNAME_R="4.4.0-210-generic" \
  BUILD_MYVPN_VIRT="kvm" \
  bash "$TARGET_SCRIPT" --host 203.0.113.31 --dry-run)"
validate_json_lines "$OUT6"
BBR6="$(printf '%s\n' "$OUT6" | tail -n1 | python3 -c 'import json,sys; print(json.load(sys.stdin)["bbr_status"])')"
assert_eq "Old kernel (<4.9) reports unsupported_kernel" "unsupported_kernel" "$BBR6"

echo "=== Test 7: Port 443 conflict emits ERR_PORT_IN_USE ==="
C7="${TMP_BASE}/c7"
setup_mock_env "$C7" "ubuntu" "24.04" "cubic" "cubic bbr"
set +e
OUT7="$(MOCK_PORT_BUSY=1 PATH="${C7}/bin:${PATH}" \
  BUILD_MYVPN_ROOT_PREFIX="${C7}/root" \
  BUILD_MYVPN_OS_RELEASE="${C7}/os-release" \
  BUILD_MYVPN_UNAME_M="x86_64" \
  bash "$TARGET_SCRIPT" --host 203.0.113.40 --protocol reality)"
RC7=$?
set -e
validate_json_lines "$OUT7"
CODE7="$(printf '%s\n' "$OUT7" | tail -n1 | python3 -c 'import json,sys; print(json.load(sys.stdin)["code"])')"
assert_eq "Port conflict exits non-zero" "1" "$RC7"
assert_eq "Port conflict emits ERR_PORT_IN_USE" "ERR_PORT_IN_USE" "$CODE7"

echo "=== Test 8: --uninstall removes BBR config, service, and config dir ==="
OUT8="$(PATH="${C2}/bin:${PATH}" \
  BUILD_MYVPN_ROOT_PREFIX="${C2}/root" \
  bash "$TARGET_SCRIPT" --uninstall)"
validate_json_lines "$OUT8"
assert_eq "BBR sysctl file removed on uninstall" "false" "$( [[ -f "${C2}/root/etc/sysctl.d/99-build-myvpn-bbr.conf" ]] && echo true || echo false )"
assert_eq "Config dir removed on uninstall" "false" "$( [[ -d "${C2}/root/etc/build-myvpn" ]] && echo true || echo false )"

echo ""
echo "Summary: ${PASS_COUNT} passed, ${FAIL_COUNT} failed"
(( FAIL_COUNT == 0 ))
