#!/bin/sh
# uc-lockdown — аварийная блокировка сети с сохранением Tailscale (устанавливает приложение Ubuntu Control).
#   uc-lockdown on <inbound|full> [минут]   включить (минуты = автоотключение, 0 = до отмены)
#   uc-lockdown off                          выключить
#   uc-lockdown status                       OFF | ON mode=... until=...
# inbound — закрыты все входящие, кроме Tailscale; исходящий трафик работает.
# full    — закрыто всё (вход, выход, контейнеры Docker), кроме Tailscale и DNS/DHCP.
# Правила живут только в памяти: перезагрузка сервера снимает блокировку.
T=uc_lockdown
STATE=/run/uc-lockdown
SELF=/usr/local/sbin/uc-lockdown
NFT=$(command -v nft) || { echo "ОШИБКА: nft не установлен"; exit 2; }

off() {
  $NFT delete table inet $T 2>/dev/null
  systemctl stop uc-lockdown-expire.timer uc-lockdown-expire.service 2>/dev/null
  rm -f "$STATE.mode" "$STATE.until"
}

case "${1:-status}" in
  status)
    if $NFT list table inet $T >/dev/null 2>&1; then
      echo "ON mode=$(cat $STATE.mode 2>/dev/null) until=$(cat $STATE.until 2>/dev/null || echo 0)"
    else
      echo "OFF"
    fi
    ;;
  off)
    off
    echo "OFF"
    ;;
  on)
    mode="${2:-inbound}"
    mins="${3:-0}"
    case "$mode" in inbound|full) ;; *) echo "ОШИБКА: режим должен быть inbound или full"; exit 2 ;; esac
    case "$mins" in ''|*[!0-9]*) echo "ОШИБКА: время — число минут"; exit 2 ;; esac
    if ! ip link show tailscale0 >/dev/null 2>&1; then
      echo "ОШИБКА: интерфейс tailscale0 не найден — локдаун отменён, чтобы не потерять доступ"; exit 2
    fi
    if command -v tailscale >/dev/null 2>&1 && ! tailscale status >/dev/null 2>&1; then
      echo "ОШИБКА: Tailscale не в сети — локдаун отменён, чтобы не потерять доступ"; exit 2
    fi

    rules='table inet uc_lockdown {
  chain input {
    type filter hook input priority -100; policy accept;
    iif "lo" accept
    iifname "tailscale0" accept
    ct state established,related accept
    udp dport 41641 accept
    udp sport 67 udp dport 68 accept
    ip protocol icmp accept
    meta l4proto ipv6-icmp accept
    drop
  }'
    if [ "$mode" = full ]; then
      rules="$rules"'
  chain output {
    type filter hook output priority -100; policy accept;
    oif "lo" accept
    oifname "tailscale0" accept
    meta skuid 0 accept
    udp dport { 53, 67 } accept
    tcp dport 53 accept
    ip protocol icmp accept
    meta l4proto ipv6-icmp accept
    drop
  }
  chain forward {
    type filter hook forward priority -100; policy accept;
    iifname "tailscale0" accept
    oifname "tailscale0" accept
    drop
  }'
    fi
    rules="$rules
}"
    printf '%s\n' "$rules" | $NFT -c -f - || { echo "ОШИБКА: правила не прошли проверку"; exit 2; }
    off
    printf '%s\n' "$rules" | $NFT -f - || { echo "ОШИБКА: не удалось применить правила"; exit 2; }
    echo "$mode" > "$STATE.mode"
    if [ "$mins" -gt 0 ]; then
      if ! systemd-run --quiet --unit=uc-lockdown-expire --on-active="${mins}m" "$SELF" off; then
        $NFT delete table inet $T 2>/dev/null
        rm -f "$STATE.mode"
        echo "ОШИБКА: не удалось поставить таймер автоотключения — локдаун отменён"; exit 2
      fi
      echo $(( $(date +%s) + mins * 60 )) > "$STATE.until"
    else
      echo 0 > "$STATE.until"
    fi
    echo "ON mode=$mode until=$(cat $STATE.until)"
    ;;
  *)
    echo "Использование: uc-lockdown on <inbound|full> [минут] | off | status"; exit 2
    ;;
esac
