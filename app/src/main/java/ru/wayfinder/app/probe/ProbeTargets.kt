package ru.wayfinder.app.probe

/**
 * Цели зондов. Вынесено отдельно, чтобы легко править список без лазанья по логике.
 *
 * Группы подобраны под диагностику белых списков:
 *  - NEUTRAL   — зарубежные IP вне белого списка (контроль L3-фильтра по IP);
 *  - WHITELIST — эталоны из белого списка (на них проверяем SNI-фильтр);
 *  - SUBNETS   — IP кандидатов-подсетей, где можно поднять входную ноду;
 *  - SNI_SET   — набор SNI для TLS-зонда на один IP.
 */
object ProbeTargets {

    data class Host(val name: String, val ip: String, val note: String = "")

    // Зарубежные, заведомо не в белом списке. Принимают TCP:443, если сеть без фильтра.
    val NEUTRAL = listOf(
        Host("Cloudflare", "1.1.1.1"),
        Host("Google", "8.8.8.8"),
        Host("Quad9", "9.9.9.9"),
    )

    // Эталоны из белого списка. Резолвятся динамически (IP меняются), но для
    // TLS-SNI теста нам нужен именно живой IP — его подставит раннер после DNS.
    val WHITELIST = listOf(
        Host("Яндекс", "", "ya.ru"),
        Host("VK", "", "vk.com"),
        Host("Mail.ru", "", "mail.ru"),
        Host("Сбер", "", "sberbank.ru"),
        Host("Госуслуги", "", "gosuslugi.ru"),
    )

    // Кандидаты-подсети под входную ноду. Берём РЕАЛЬНЫЙ сайт, который хостится у
    // провайдера (поле note = домен): раннер резолвит его в IPv4 и проверяет TCP:443.
    // Случайные IP вроде x.x.x.1 бесполезны — на них нет сервера. Если сайт провайдера
    // доступен под белыми списками, его подсети, скорее всего, в списке.
    // ip = запасной адрес на случай, если DNS отравлен и домен не резолвится
    // (у друга на Tele2 cloud.yandex.ru не разрешался из-за подмены DNS).
    val SUBNETS = listOf(
        Host("Yandex Cloud", "213.180.204.1", "cloud.yandex.ru"),
        Host("VK Cloud", "95.163.254.192", "mcs.mail.ru"),
        Host("Selectel", "85.119.149.3", "selectel.ru"),
        Host("Timeweb", "185.65.148.89", "timeweb.com"),
    )

    // SNI для TLS-зонда на один IP. Ключевой вопрос — совпадает ли результат
    // для "белого-чужого" SNI с "белым-родным": если да, ТСПУ не сверяет SNI с IP.
    data class SniCase(val kind: String, val sni: String)
    val SNI_SET = listOf(
        SniCase("родной", ""),                  // подставляется из имени хоста
        SniCase("запрещённый", "www.instagram.com"),
        SniCase("белый-чужой", "vk.com"),       // разрешён, но не родной для IP
    )

    // Расширенный перебор SNI-доноров: прогоняется TLS к зарубежному IP с каждым
    // из этих имён. Какие проходят — те «живые» как SNI сквозь фильтр оператора.
    // Нужно, чтобы понять, какие домены вообще пропускаются (диагностика фильтрации
    // по имени), а какие молча режутся.
    val SNI_DONORS = listOf(
        // российские
        "ya.ru", "yandex.ru", "dzen.ru", "vk.com", "vk.ru", "mail.ru", "ok.ru",
        "avito.ru", "ozon.ru", "wildberries.ru", "gosuslugi.ru", "sberbank.ru",
        "vtb.ru", "tbank.ru", "rutube.ru", "kinopoisk.ru", "2gis.ru", "rzd.ru",
        // зарубежные / глобальные
        "amazon.com", "www.microsoft.com", "www.apple.com", "www.google.com",
        "github.com", "www.cloudflare.com", "www.samsung.com", "www.yahoo.com",
    )

    // IP, к которому ведётся перебор SNI-доноров (нейтральный зарубежный узел,
    // принимающий TCP:443). Берётся из нейтральных целей.
    const val SNI_SCAN_IP = "1.1.1.1"

    // Домены для проверки работы DNS-резолвера оператора.
    val DNS_NAMES = listOf(
        "ya.ru",                 // белый
        "google.com",            // нейтральный
        "www.instagram.com",     // заблокированный
        "rutracker.org",         // заблокированный
    )

    // Заблокированные домены — для них подставной IP в ответе = подмена DNS.
    val BLOCKED_DNS_NAMES = setOf("www.instagram.com", "rutracker.org")

    // Домен туннеля больше не здесь — он чувствительный и живёт в Config
    // (приходит из секретов сборки), чтобы можно было держать репозиторий открытым.
}
