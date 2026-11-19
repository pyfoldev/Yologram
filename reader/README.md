# Читалка

PWA без сборки: аниме, книги и комиксы на русском, только бесплатные API без ключей.

Запуск: `python3 -m http.server 8080` в этой папке, затем http://localhost:8080

| Раздел | Источник |
|---|---|
| Аниме | AniLibria API v1 (`anilibria.top/api/v1`, v3 закрыт) + HLS |
| Книги | Gutendex, Викитека, Open Library, Google Books, Internet Archive |
| Комиксы | Internet Archive (subject: comics/manga) |

- Закладки: долгое нажатие или правый клик по карточке. Хранятся в localStorage.
- Google Books работает и без ключа, но с общей квотой. Свой бесплатный ключ: `localStorage.setItem('gkey','"КЛЮЧ"')`.
- Права на контент AniLibria и части книг на стороне источников.
