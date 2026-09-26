# MecanumBot

Проект: мобильная платформа на меканум-колёсах. Полный дизайн — DESIGN.md, протокол — protocol/PROTOCOL.md (источник истины для обеих сторон; заменяет DESIGN.md §3).

## Компоненты
- firmware/ — PlatformIO, ESP32-C6, Arduino core 3.x. Тесты: `cd firmware && pio test -e native`. Сборка: `pio run -e esp32c6`.
- android-app/ — Kotlin/Compose. Тесты core: `./gradlew :core:test`.
- pilot-web/ — статика, копируется в android-app/app/src/main/assets/pilot/.
- protocol/ — PROTOCOL.md, vectors.json и генератор gen_vectors.py (эталонная реализация на Python).
- hardware/wiring.md — питание и распиновка; docs/measurements.md — результаты измерений этапа 0a.

## Правила
- Не использовать Serial.print в прошивке: USB занят протоколом. Логи только через LOG-кадры (log_info).
- Не вызывать delay() в loop(). Без RTOS-задач.
- Изменение протокола: сначала PROTOCOL.md и gen_vectors.py, затем `python3 protocol/gen_vectors.py`, затем обе реализации, затем тесты. vectors.json вручную не править; `python3 protocol/gen_vectors.py --check` проверяет, что он актуален.
- Тесты firmware и Android читают protocol/vectors.json напрямую, копий не держать.
- Любая команда движения подчиняется failsafe (`failsafe_ms`, по умолчанию 300 мс). Не добавлять пути, которые могут крутить мотор без «пульса».
- В Android не трогать DTR/RTS на USB-порту.
- Модули lib/protocol, lib/kinematics, lib/failsafe и android core — без зависимостей от Arduino/Android, они должны собираться в native-тестах.
- Пины и константы только из include/config.h.
- Отправка по USB из прошивки только при Serial.availableForWrite() достаточном для кадра; никогда не блокировать loop().
- Приложение: любая работа с железом идёт через интерфейс Link; FakeLink должен поддерживать все типы кадров, чтобы UI разрабатывался без ESP32.
- STOP и failsafe всегда мгновенные, без slew; brake по умолчанию.
