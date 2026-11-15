package com.pyfold.ubuntucontrol

/** Одна команда: [arg] — подсказка для ввода параметра (подставляется вместо %s в кавычках). */
data class Cmd(
    val title: String,
    val cmd: String,
    val confirm: String? = null,
    val arg: String? = null,
    val hint: String? = null,
)

sealed interface Target {
    data object Terminal : Target
    data class Menu(val title: String, val items: List<Cmd>) : Target
    data class Run(val cmd: Cmd) : Target
}

data class RingItem(val emoji: String, val label: String, val target: Target)

object Actions {
    private val network = Target.Menu("Сеть", listOf(
        Cmd("Адреса интерфейсов", "ip -br a"),
        Cmd("Wi‑Fi: уровень сигнала", "iw dev 2>/dev/null | awk '/Interface/{print \$2}' | xargs -r -I{} sh -c 'iw dev {} link; echo; iw dev {} station dump | head -20'"),
        Cmd("Маршруты и шлюз", "ip route"),
        Cmd("Открытые порты", "ss -tulpn 2>/dev/null"),
        Cmd("Пинг (интернет)", "ping -c 4 -W 2 1.1.1.1 && ping -c 2 -W 2 google.com"),
        Cmd("DNS", "resolvectl status | head -40"),
        Cmd("Внешний IP", "curl -s -m 8 https://api.ipify.org; echo"),
        Cmd("Пинг хоста…", "ping -c 4 -W 2 %s", arg = "Хост или IP", hint = "8.8.8.8"),
        Cmd("Перезапустить сеть", "sudo -n systemctl restart systemd-networkd && echo Сеть перезапущена",
            confirm = "Перезапустить сетевую службу? Соединение может ненадолго прерваться."),
        Cmd("Файрвол (ufw)", "sudo -n ufw status verbose"),
    ))

    private val services = Target.Menu("Службы", listOf(
        Cmd("Запущенные службы", "systemctl list-units --type=service --state=running --no-pager --no-legend | awk '{print \$1, \$4}'"),
        Cmd("Упавшие службы", "systemctl --failed --no-pager"),
        Cmd("Статус службы…", "systemctl status %s --no-pager -l", arg = "Имя службы", hint = "ssh"),
        Cmd("Перезапустить службу…", "sudo -n systemctl restart %s && echo Перезапущено: %s", arg = "Имя службы", hint = "nullgram",
            confirm = "Перезапустить выбранную службу?"),
        Cmd("Остановить службу…", "sudo -n systemctl stop %s && echo Остановлено: %s", arg = "Имя службы", confirm = "Остановить службу?"),
        Cmd("Запустить службу…", "sudo -n systemctl start %s && echo Запущено: %s", arg = "Имя службы"),
        Cmd("Автозапуск (включённые)", "systemctl list-unit-files --state=enabled --no-pager --no-legend | awk '{print \$1}'"),
        Cmd("Таймеры cron/systemd", "systemctl list-timers --no-pager; echo; crontab -l 2>/dev/null"),
    ))

    private val docker = Target.Menu("Docker", listOf(
        Cmd("Контейнеры", "docker ps -a --format 'table {{.Names}}\\t{{.Status}}\\t{{.Image}}'"),
        Cmd("Нагрузка контейнеров", "docker stats --no-stream --format 'table {{.Name}}\\t{{.CPUPerc}}\\t{{.MemUsage}}'"),
        Cmd("Логи контейнера…", "docker logs --tail 80 %s 2>&1", arg = "Имя контейнера", hint = "chat-helper-bot-1"),
        Cmd("Перезапустить контейнер…", "docker restart %s", arg = "Имя контейнера", hint = "chat-helper-bot-1", confirm = "Перезапустить контейнер?"),
        Cmd("Образы", "docker images --format 'table {{.Repository}}\\t{{.Tag}}\\t{{.Size}}'"),
        Cmd("Занятое место", "docker system df"),
        Cmd("Очистка неиспользуемого", "docker system prune -f", confirm = "Удалить остановленные контейнеры и неиспользуемые образы?"),
    ))

    private val disk = Target.Menu("Диск и память", listOf(
        Cmd("Свободное место", "df -h -x tmpfs -x devtmpfs -x squashfs"),
        Cmd("Что занимает место (/)", "sudo -n du -xh / --max-depth=1 2>/dev/null | sort -h | tail -15"),
        Cmd("Диски и разделы", "lsblk -o NAME,SIZE,TYPE,MOUNTPOINT,FSTYPE"),
        Cmd("Память", "free -h"),
        Cmd("SMART / состояние диска", "sudo -n smartctl -H /dev/sda 2>&1 | tail -8"),
        Cmd("Крупные файлы в домашней папке", "du -ah ~ 2>/dev/null | sort -h | tail -15"),
    ))

    private val procs = Target.Menu("Процессы", listOf(
        Cmd("Топ по CPU", "ps aux --sort=-%cpu | head -15 | cut -c1-140"),
        Cmd("Топ по памяти", "ps aux --sort=-%mem | head -15 | cut -c1-140"),
        Cmd("Нагрузка (top)", "top -bn1 | head -20"),
        Cmd("Аптайм и нагрузка", "uptime; echo; cat /proc/loadavg"),
        Cmd("Температура", "for f in /sys/class/thermal/thermal_zone*/temp; do echo \"\$(basename \$(dirname \$f)): \$((\$(cat \$f)/1000)) °C\"; done"),
        Cmd("Завершить процесс (PID)…", "kill %s && echo Отправлен сигнал: %s", arg = "PID", confirm = "Завершить процесс?"),
        Cmd("Кто в системе", "who; echo; last -n 10"),
    ))

    private val logs = Target.Menu("Журналы", listOf(
        Cmd("Системный журнал (100)", "journalctl -n 100 --no-pager"),
        Cmd("Только ошибки", "journalctl -p err -n 60 --no-pager"),
        Cmd("Журнал службы…", "journalctl -u %s -n 100 --no-pager", arg = "Имя службы", hint = "nullgram"),
        Cmd("Попытки входа по SSH", "journalctl -u ssh -n 60 --no-pager | grep -iE 'accepted|failed|invalid' | tail -30"),
        Cmd("Ядро (dmesg)", "dmesg 2>/dev/null | tail -50 || journalctl -k -n 50 --no-pager"),
        Cmd("Последние загрузки", "journalctl --list-boots --no-pager | tail"),
    ))

    private val updates = Target.Menu("Обновления", listOf(
        Cmd("Проверить обновления", "sudo -n apt-get update -qq && apt list --upgradable 2>/dev/null"),
        Cmd("Установить обновления", "sudo -n apt-get -y upgrade 2>&1 | tail -30", confirm = "Установить все доступные обновления?"),
        Cmd("Нужна ли перезагрузка", "[ -f /var/run/reboot-required ] && echo 'Да, нужна перезагрузка' || echo 'Нет, не нужна'"),
        Cmd("Очистка пакетов", "sudo -n apt-get -y autoremove 2>&1 | tail -10", confirm = "Удалить ненужные пакеты?"),
        Cmd("Версия системы", "lsb_release -ds; uname -r"),
    ))

    private val tailscale = Target.Menu("Tailscale", listOf(
        Cmd("Статус", "sudo -n tailscale status"),
        Cmd("Мой IP", "tailscale ip"),
        Cmd("Funnel (публичные адреса)", "sudo -n tailscale funnel status"),
        Cmd("Проверка сети (netcheck)", "tailscale netcheck 2>&1 | head -30"),
        Cmd("Пинг устройства…", "tailscale ping -c 3 %s", arg = "Имя или IP", hint = "macbook-air"),
        Cmd("Перезапустить Tailscale", "sudo -n systemctl restart tailscaled && echo OK", confirm = "Перезапустить Tailscale? Приложение переподключится само."),
    ))

    private val bots = Target.Menu("Боты и сервисы", listOf(
        Cmd("Состояние", "echo '== nullgram =='; systemctl is-active nullgram; cd /opt/chat-helper 2>/dev/null && docker compose ps --format 'table {{.Name}}\\t{{.Status}}'"),
        Cmd("Перезапустить chat-helper (бот)", "cd /opt/chat-helper && docker compose restart bot", confirm = "Перезапустить бота?"),
        Cmd("Перезапустить nullgram", "sudo -n systemctl restart nullgram && echo OK", confirm = "Перезапустить сервер nullgram?"),
        Cmd("Логи бота (60)", "cd /opt/chat-helper && docker compose logs --tail 60 bot 2>&1 | cut -c1-200"),
        Cmd("Логи nullgram", "journalctl -u nullgram -n 60 --no-pager"),
        Cmd("Бэкап данных бота", "cd /opt/chat-helper && docker compose exec -T postgres pg_dump -U chat -d chat -Fc > ~/chat-backup-\$(date +%F-%H%M).dump && ls -lh ~/chat-backup-*.dump | tail -3"),
    ))

    val ring: List<RingItem> = listOf(
        RingItem("⌨️", "Терминал", Target.Terminal),
        RingItem("🌐", "Сеть", network),
        RingItem("⚙️", "Службы", services),
        RingItem("🐳", "Docker", docker),
        RingItem("💾", "Диск", disk),
        RingItem("📊", "Процессы", procs),
        RingItem("📜", "Журналы", logs),
        RingItem("⬆️", "Обновления", updates),
        RingItem("🔗", "Tailscale", tailscale),
        RingItem("🤖", "Боты", bots),
        RingItem("🔄", "Перезагр.", Target.Run(Cmd("Перезагрузка", "sudo -n systemctl reboot",
            confirm = "Перезагрузить сервер? Все сервисы будут недоступны пару минут."))),
        RingItem("⏻", "Выключить", Target.Run(Cmd("Выключение", "sudo -n systemctl poweroff",
            confirm = "ВЫКЛЮЧИТЬ сервер? Включить его удалённо будет нельзя — только кнопкой на самом устройстве."))),
    )
}
