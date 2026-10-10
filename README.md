# PatchWall Actions

Пункт управления для Xiaomi Mi TV на кнопке Xiaomi на пульте. Проверено на MiTV-MOOQ1 (Android TV 11)

## Кнопка Xiaomi

- одно нажатие: действие из настроек. По умолчанию подсветка вкл/выкл (последний уровень <-> 0), можно таймер сна +30 мин, сцену Ночь или открыть меню
- удержание: действие из настроек. По умолчанию переключение источника по кругу: главный экран, подключённые HDMI, снова главный. Можно меню, подсветку, таймер или сцену Ночь
- двойное нажатие: меню поверх текущего приложения
- нажатие при открытом меню закрывает его. Ещё меню закрывается по "Назад", Home и когда открылось другое приложение

## Меню

Сетка из 4 колонок, стрелки вверх/вниз сохраняют колонку. ОК выполняет действие, долгий ОК открывает системные настройки пункта

- Яркость: слайдер 10-100, ОК на нём = вкл/выкл. Пока его двигают, затемнение пропадает, остальное меню скрывается на 1.5 с
- Источник: Главный / HDMI 1-3. Вход без сигнала не переключается, показывает подсказку
- Картинка: Стандарт / Яркий / Спорт / Кино (`picture_mode` 7 / 3 / 2 / 9)
- Звук: Стандарт / Кино / Новости / Игра (`sound_style` 0-3). Dolby на этой модели нет
- Таймер сна: выкл / 30 / 60 / 90 мин. По окончании TV засыпает (`GLOBAL_ACTION_LOCK_SCREEN` из службы)
- Ночь: подсветка 20, синий фильтр, таймер 60 мин. Повторное нажатие возвращает как было
- Wi-Fi вкл/выкл, Amnezia (статус VPN, ОК открывает приложение), Синий свет, Настройки

## Настройки

Иконка в списке приложений или плитка в меню:

- что делает одно нажатие
- статус доступов с кнопками: служба спец. возможностей, не выгружать из памяти, имя сети Wi-Fi
- команды adb для того, что выдаётся только через adb
- какие плитки показывать

## Как это устроено

Кнопка Xiaomi (Android keycode 192) до приложений не доходит. Прошивка обрабатывает её в `MiTVWindowPolicyInjector` и на отпускание запускает PatchWall (`com.mitv.tvhome.atv`) явным интентом, подменить его нельзя

- нажатия читаются из logcat: прошивка пишет `handleAction192` на нажатие и отпускание и `onLongPress:192` на удержание. Нужен `READ_LOGS`. Без него приложение считает нажатием появление PatchWall, двойное нажатие так распознаётся хуже
- PatchWall удаляется для пользователя (`pm uninstall -k --user 0`). Прошивка по кнопке пытается его запустить, не находит и ничего не показывает, а нажатие всё равно пишется в журнал. Если PatchWall оставить, служба закрывает его "назад", но он успевает мелькнуть
- служба не перехватывает кнопки (`flagRequestFilterKeyEvents` нет), поэтому не бывает залипаний, как у tvQuickActions и Button Mapper
- подсветка, картинка, звук и синий фильтр это `Settings.Global` (`picture_backlight`, `picture_mode`, `sound_style`, `picture_blue_light`), их применяет `TVMenuSettingsService`. У каждого входа своя подсветка. Нужен `WRITE_SECURE_SETTINGS`, без него плитки показывают подсказку
- Wi-Fi переключается через `WifiManager.setWifiEnabled`, поэтому `targetSdkVersion 28`

Отключать PatchWall (`pm disable-user`) или ставить `patchwall_enable=false` нельзя: тогда прошивка по кнопке открывает Live TV. Только удалять для пользователя

## Сборка

Без gradle, нужны JDK 17 и Android SDK (build-tools 36.0.0, platforms/android-30) в `%LOCALAPPDATA%\Android\Sdk` или `ANDROID_HOME`

```sh
sh build.sh   # out/patchwall-actions.apk
```

Ключ подписи `patchwall-actions.keystore` лежит рядом и в git не попадает. Без него обновить уже установленное приложение не получится, только удалить и поставить заново

## Установка

```sh
adb shell settings put global verifier_verify_adb_installs 0   # иначе Play Protect не даёт поставить apk со службой
adb install -r --no-incremental out/patchwall-actions.apk

adb shell pm grant com.criit.patchwallactions android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant com.criit.patchwallactions android.permission.READ_LOGS
adb shell dumpsys deviceidle whitelist +com.criit.patchwallactions
adb shell pm uninstall -k --user 0 com.mitv.tvhome.atv   # чтобы PatchWall не мелькал
```

Дальше на TV открыть PatchWall Actions и включить службу и "не выгружать из памяти" кнопками в разделе Доступ. Или через adb, сохранив Twilight:

```sh
adb shell settings put secure enabled_accessibility_services com.urbandroid.lux/com.urbandroid.lux.TwilightService:com.criit.patchwallactions/com.criit.patchwallactions.MiKeyService
```

После переустановки apk службу нужно перевключить (сначала без неё, потом с ней)

## Удаление

```sh
adb uninstall com.criit.patchwallactions
adb shell cmd package install-existing com.mitv.tvhome.atv   # вернуть PatchWall
```

## Ограничения

- одно нажатие срабатывает через 0.4 с: приложение ждёт, не будет ли второго
- обновление прошивки может вернуть PatchWall, тогда команду удаления нужно повторить (в настройках приложения это видно в строке "PatchWall не мелькает")
- у Amnezia нет внешнего API: переключить сервер или подключиться из меню нельзя
- Bluetooth в меню нет специально: пульт Xiaomi работает через Bluetooth
