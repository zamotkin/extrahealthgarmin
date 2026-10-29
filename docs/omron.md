# **Продуктовая и архитектурная спецификация: Интеграция тонометра Omron M7 Intelli IT с платформой Garmin Connect**

> **Статус:** оригинальное дизайн-исследование (PRD, 2025). Реализованный проводной протокол
> описан в [omron-protocol.md](omron-protocol.md); в поставляемом приложении v3 тонометр
> подключается как источник кровяного давления (Settings → Connections → Add source), а
> показания собираются на вкладке Measurements. Документ сохранён как историческая основа
> проектных решений.

## **Введение и стратегический контекст разработки продукта**

Современная экосистема интернета медицинских вещей (Internet of Medical Things, IoMT) характеризуется высокой степенью фрагментации и проприетарной изолированностью. Производители медицинского оборудования, такие как Omron, традиционно выстраивают закрытые экосистемы, привязывая пользователей к собственным мобильным приложениям (в частности, Omron Connect) и облачным сервисам. Это создает значительные барьеры для пользователей, стремящихся агрегировать свои биометрические данные в единой кроссплатформенной аналитической среде, такой как Garmin Connect, которая исторически доминирует в сегменте спортивной и фитнес-аналитики.  
Проект с открытым исходным кодом extrahealthgarmin изначально создавался как архитектурный мост для синхронизации данных смарт-весов с платформой Garmin. Тем не менее, его архитектура обладает потенциалом для расширения и поддержки других медицинских Bluetooth-устройств. Настоящий документ представляет собой исчерпывающую продуктовую и техническую спецификацию (Product Requirements Document) для создания независимого программного модуля в рамках Android-приложения extrahealthgarmin. Разрабатываемый модуль предназначен для прямого взаимодействия с тонометром Omron M7 Intelli IT (модель HEM-7361T-ALRU) по протоколу Bluetooth Low Energy (BLE), извлечения измерений артериального давления и пульса в обход проприетарного приложения Omron Connect.  
Первоначальный запрос предполагал использование CSV-файлов как обходного пути для загрузки данных в Garmin. Однако с учетом интеграции официальной библиотеки fit-java-sdk, целевой архитектурой становится прямая генерация нативных бинарных файлов формата FIT. Это обеспечит стопроцентную совместимость с платформой Garmin Connect, устраняя проблемы хрупкого парсинга текстовых файлов.

## **Фундаментальный анализ протокола Bluetooth Low Energy**

Для обеспечения стабильной работы приложения extrahealthgarmin с аппаратным обеспечением Omron необходимо учитывать фундаментальные отличия протокола Bluetooth Low Energy от классического Bluetooth (BR/EDR). Технология BLE была разработана для минимизации энергопотребления, что является критическим фактором для портативных медицинских устройств, работающих от батареек, таких как M7 Intelli IT1.  
На физическом уровне BLE использует 40 радиочастотных каналов, из которых только 3 канала (37, 38 и 39\) выделены исключительно для широковещательных сообщений (Advertising), в то время как остальные 37 используются для двунаправленной передачи данных после установления соединения1. Время, необходимое для передачи стандартного пакета данных в среде BLE, варьируется от 0,6 миллисекунд до 1,2 миллисекунд, в отличие от 22,5 миллисекунд в классическом протоколе BR/EDR1. Подобная архитектура обеспечивает снижение энергопотребления в 10–20 раз по сравнению со старыми стандартами Bluetooth1.  
Передача данных в BLE организована в виде дискретных временных интервалов, называемых событиями (Events). Длина и частота этих событий динамически определяются мастером (в данном контексте — смартфоном на базе Android) для событий соединения и рекламодателем (тонометром) для рекламных событий1. Это означает, что Android-модуль extrahealthgarmin должен корректно обрабатывать тайм-ауты и задержки, возникающие из\-за агрессивных механизмов энергосбережения самого тонометра.  
Для проведения реверс-инжиниринга проприетарных протоколов, подобных тем, что используются в устройствах Omron, применяются два параллельных метода исследования. Первый метод заключается в перехвате и инспекции пакетов Bluetooth с использованием встроенной функции "Журнал отслеживания Bluetooth HCI" (Bluetooth HCI snoop log), которая записывает весь трафик в файл btsnoop\_hci.log2. Последующий анализ в Wireshark позволяет выявить шестнадцатеричные паттерны запросов и ответов3. Второй метод предполагает декомпиляцию оригинального Android-приложения (APK) с использованием таких инструментов, как dex2jar и JD-GUI, для изучения Java-кода1.

## **Спецификация профиля GATT и идентификаторы служб (UUID)**

Для двунаправленной передачи данных используется протокол атрибутов (Attribute Protocol, ATT) с иерархической моделью данных: профиль GATT, службы (Services) и характеристики (Characteristics)1. Каждая из них идентифицируется уникальным UUID5.  
Для тонометра Omron M7 Intelli IT разрабатываемый модуль должен взаимодействовать со следующими узлами GATT:

| Наименование службы или характеристики | Идентификатор (UUID) | Поддерживаемые операции (Свойства) | Архитектурное назначение и описание |
| :---- | :---- | :---- | :---- |
| Generic Access Service | 0x1800 | Read | Стандартная служба с именем устройства и внешним видом5. |
| Generic Attribute Service | 0x1801 | Read | Базовая служба GATT5. |
| Device Information Service | 0x180A | Read | Служба статической информации (версии, серийные номера)6. |
| Battery Service | 0x180F | Read, Notify | Уровень заряда батарей тонометра6. |
| Current Time Service | 0x1805 | Read, Write | Стандартизированная служба синхронизации времени6. |
| Blood Pressure Service | 0x1810 | Не применимо | Корневая медицинская служба6. |
| Blood Pressure Measurement | 0x2A35 | Indicate | Ключевая характеристика результатов измерений6. |
| Blood Pressure Feature | 0x2A49 | Read | Функциональные возможности тонометра6. |

Доступ к медицинским данным блокируется встроенным ПО Omron до прохождения криптографической аутентификации6.

## **Проприетарная модель аутентификации и сопряжения (Pairing & Bonding)**

Интеграция требует реализации сложного конечного автомата (State Machine) для обхода ошибки GATT\_INSUFFICIENT\_AUTHENTICATION5.

### **Фаза 1: Аппаратная инициация и режим сопряжения**

Пользователь зажимает кнопку Bluetooth на корпусе тонометра на 3–5 секунд. Мигающий символ "-P-" (Pairing) означает, что устройство готово к сопряжению10.

### **Фаза 2: Обнаружение и SMP Bonding**

Android-устройство использует BluetoothLeScanner. Тонометры Omron поддерживают модель аутентификации "Just Works" с обменом ключами ECDH2. Однако этот процесс на уровне ОС недостаточен для Omron.

### **Фаза 3: Регистрация ключа на прикладном уровне**

Модуль должен прочитать специальный блок системных настроек начиная с адреса 0x0010, сконструировать 38-байтовый регистрационный блок и отправить его обратно в EEPROM тонометра13.

### **Фаза 4: Визуальное подтверждение**

Успех регистрации подтверждается символом "\[OK\]" на дисплее10. Важно: M7 Intelli IT не может работать одновременно с официальным приложением и сторонним; старое сопряжение нужно предварительно удалить10.

## **Протокол прямого доступа к памяти (EEPROM) и извлечение данных**

Стандартная подписка на характеристику 0x2A35 ненадежна, так как после прочтения запись помечается как "отправленная" и больше не транслируется6.  
Для гарантированного извлечения используется прямой доступ к EEPROM:

* Старт сессии: команда 0x08 (размер 8 байт, с контрольной суммой CRC)11.  
* Чтение блока: команда 0x010011.  
* Запись блока: команда 0x01c011.  
* Конец сессии: команда 0x0f0011.

Архитектура должна поддерживать двух пользователей (USER 1 и USER 2), запрашивая только новые измерения11.

## **Декодирование медицинских данных: Стандарт IEEE 11073-20601 SFLOAT**

Медицинские показатели кодируются в стандарте IEEE 11073-20601 в формате SFLOAT (medfloat16)9. Формат SFLOAT (2 байта, Little Endian):

> 1. **Мантисса (12 бит)**: в дополнительном коде (two's complement).  
> 2. **Экспонента (4 бита)**: в дополнительном коде.

Математическая формула:  
![][image1]  
Пример для байтов 136, 244 (0x88 и 0xF4, слово 0xF488): экспонента 0xF (десятичное \-1), мантисса 0x488 (1160). Расчет: ![][image2] мм рт. ст9.

## **Экосистема Garmin Connect: Отказ от CSV в пользу FIT-файлов**

Ранее пользователи были вынуждены использовать хрупкие методы импорта через CSV-файлы. Парсер Garmin Connect крайне строг и ограничен: любой сдвиг в кодировке, локали (запятая или точка), а также внутренние сбои на серверах Garmin приводили к сообщению "import complete", хотя данные не отображались в профиле14.  
Стандарт FIT (Flexible and Interoperable Data Transfer) является главным бинарным протоколом экосистемы Garmin16. Протокол предназначен для обеспечения кроссплатформенности, компактности и расширяемости данных16. Использование официального комплекта разработчика fit-java-sdk нативно решает задачу интеграции на платформе Android16.

### **Интеграция fit-java-sdk**

В архитектуре extrahealthgarmin генерация данных об артериальном давлении будет полностью реализована через официальную Java-версию FIT SDK (доступную в пространстве имен com.garmin.fit)17.  
В профиле FIT определен глобальный номер сообщения 51 (blood\_pressure), который содержит стандартизированные поля для систолического давления, диастолического давления, пульса и идентификатора пользователя18.  
Процесс обработки будет выглядеть следующим образом:

> 1. Извлеченные из тонометра и декодированные SFLOAT данные передаются в новый модуль FitEncoder.  
> 2. С использованием fit-java-sdk инициализируется файл (File ID) и создаются объекты BloodPressureMesg17.  
> 3. SDK автоматически управляет сериализацией бинарного файла и вычисляет обязательные контрольные суммы (CRC) заголовка и окончания файла, предотвращая отбраковку загрузок серверами Garmin16.

Сформированный .fit файл обладает стопроцентной совместимостью как с веб\-интерфейсом Garmin Connect (для ручной загрузки), так и с внутренними API платформы.

## **Архитектура клиентского приложения Android и управление состояниями**

Интеграция модуля опирается на Clean Architecture и Kotlin Coroutines. Для решения проблемы нестабильности Bluetooth на Android (ошибка GATT ERROR 133\) ядром клиента выступает конечный автомат (State Machine).  
Слой управления состояниями:

* IDLE: Ожидание действий пользователя.  
* SCANNING: Поиск UUID 0x1810.  
* CONNECTING: Подключение без autoConnect.  
* DISCOVERING\_SERVICES: Обязательный вызов discoverServices()6.  
* AUTHENTICATING: Чтение/запись ключей в EEPROM (адрес 0x0010)13.  
* ENABLING\_NOTIFICATIONS: Запись значения 0x0200 в дескриптор 0x2902 для 0x2A356.  
* READING\_DATA: Декодирование пакетов в доменные модели.  
* DISCONNECTING: Освобождение ресурсов (gatt.close()).

GATT-операции строго последовательны: следующая выполняется только после коллбэка от предыдущей6.

### **Модуль экспорта и синхронизации (FIT Export)**

Вместо подверженного ошибкам формирования CSV, на уровне ViewModel будет реализован класс экспорта бинарных FIT-файлов с использованием fit-java-sdk16.  
Приложение будет поддерживать два пути передачи данных:

> 1. **Локальный экспорт FIT:** Сгенерированный файл передается в систему через Android FileProvider и Intent.ACTION\_SEND, позволяя пользователю сохранить его или переслать (Share sheet) для последующей загрузки в веб\-интерфейс.  
> 2. **Прямая API-синхронизация (опционально):** При желании, приложение может автоматизировать загрузку этого файла или использовать эмуляцию API (аналогично python-garminconnect) для отправки JSON21. Однако, наличие надежного .fit файла полностью удовлетворяет потребность в переносимом, оригинальном для Garmin формате16.

## **Дизайн пользовательского интерфейса (UI/UX) и сценарии использования**

Интерфейс на базе Jetpack Compose будет включать три ключевых сценария:

> 1. **Процесс первичного сопряжения (Pairing Flow)**: Пошаговые инструкции ("Нажмите и удерживайте кнопку Bluetooth..."). Кнопка "Начать поиск" и анимированный индикатор прогресса (Поиск \-\> Подключение \-\> Аутентификация)10.  
> 2. **Панель мониторинга и управление данными (Dashboard)**:  
>    Отображение последних извлеченных измерений. Переключатель "Автоматическая синхронизация с Garmin".  
> 3. **Настройки системы и экспорт (Settings)**: Выбор профиля пользователя тонометра (USER 1 или USER 2), чтобы избежать смешивания медицинских данных12. Кнопка "Экспортировать несинхронизированные данные в FIT", реализующая сохранение/поделиться бинарным файлом.

## **Нефункциональные требования, безопасность и отказоустойчивость**

* **Криптография:** Учетные данные Garmin и 38-байтовый регистрационный ключ Omron шифруются в локальном хранилище через androidx.security.crypto.  
* **Хранение данных:** SQLite (Room) выступает в роли единственного источника истины до момента успешной выгрузки FIT-файла.  
* **Сбои Bluetooth:** Взаимодействие с тонометром подвержено помехам (официальная инструкция запрещает использование устройства рядом с сильными ЭМИ)24. Протокол обмена должен обрабатывать разрывы и возобновлять загрузку недоставленных пакетов.  
* **Энергоэффективность:** Предпочтителен интерактивный режим (передний план) запуска синхронизации для экономии заряда батареи устройства, а не фоновый CompanionDeviceManager.

## **Фазы реализации продукта**

> 1. **Прототипирование криптографии Omron:** Анализ дампов btsnoop\_hci.log на реальном тонометре для верификации шестнадцатеричных команд 0x08, 0x0100, 0x01c0 и 38-байтового ключа3.  
> 2. **Разработка ядра BLE (Kotlin):** Создание State Machine для управления подключением, аутентификацией и математического парсинга SFLOAT6.  
> 3. **Интеграция Garmin FIT SDK:** Интеграция com.garmin.fit в Android-проект. Разработка FitEncoder для преобразования сущностей Room в бинарные BloodPressureMesg (id 51\) файлы16.  
> 4. **Разработка UI и объединение компонентов:** Создание интерфейса на Jetpack Compose, реализация обмена файлами через FileProvider, тестирование стабильности приложения в условиях потери связи.

*Данный материал предоставляется исключительно в информационных целях. Для получения медицинских консультаций или постановки диагноза обратитесь к специалисту.*

#### **Works cited**

> 1. 2\. Application Protocol Reverse Engineering, [https\://reverse-engineering-ble-devices.readthedocs.io/en/latest/protocol\_reveng/00\_protocol\_reveng.html](https://reverse-engineering-ble-devices.readthedocs.io/en/latest/protocol_reveng/00_protocol_reveng.html)  
> 2. Practical Introduction to BLE GATT Reverse Engineering, [https\://jcjc-dev.com/2023/03/19/reversing-domyos-el500-elliptical/](https://jcjc-dev.com/2023/03/19/reversing-domyos-el500-elliptical/)  
> 3. How to support a new scale · oliexdev/openScale Wiki \- GitHub, [https\://github.com/oliexdev/openScale/wiki/How-to-support-a-new-scale](https://github.com/oliexdev/openScale/wiki/How-to-support-a-new-scale)  
> 4. Reverse Engineering a specific bluetooth communication protocol, [https\://stackoverflow.com/questions/47118816/reverse-engineering-a-specific-bluetooth-communication-protocol](https://stackoverflow.com/questions/47118816/reverse-engineering-a-specific-bluetooth-communication-protocol)  
> 5. Reverse Engineering Smart Bluetooth Low Energy Devices : 11 Steps, [https\://www\.instructables.com/Reverse-Engineering-Smart-Bluetooth-Low-Energy-Dev/](https://www.instructables.com/Reverse-Engineering-Smart-Bluetooth-Low-Energy-Dev/)  
> 6. CoreBluetooth and Omron Evolv Blood Pressure Monitor, [https\://stackoverflow.com/questions/68496497/corebluetooth-and-omron-evolv-blood-pressure-monitor](https://stackoverflow.com/questions/68496497/corebluetooth-and-omron-evolv-blood-pressure-monitor)  
> 7. Assigned Numbers \- Bluetooth, [https\://www\.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Assigned\_Numbers/out/en/Assigned\_Numbers.pdf](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Assigned_Numbers/out/en/Assigned_Numbers.pdf)  
> 8. WindowsデスクトップアプリでBLEのGATTで体温計と血圧計と通信, [https\://qiita.com/gebo/items/41da7474936845d77d06](https://qiita.com/gebo/items/41da7474936845d77d06)  
> 9. get values(pressure blood) from ble device in flutter \- Stack Overflow, [https\://stackoverflow.com/questions/78161511/get-valuespressure-blood-from-ble-device-in-flutter](https://stackoverflow.com/questions/78161511/get-valuespressure-blood-from-ble-device-in-flutter)  
> 10. eigger/hass-omron: Omron BLE Home Assistant Integration \- GitHub, [https\://github.com/eigger/hass-omron](https://github.com/eigger/hass-omron)  
> 11. GitHub \- userx14/omblepy: Cli tool to read records from omron blood, [https\://github.com/userx14/omblepy](https://github.com/userx14/omblepy)  
> 12. M7 Intelli IT (HEM-7322T-E) Instruction Manual \- QualeScegliere.it, [https\://immagini.qualescegliere.it/manuali/B01KVO1I7S.pdf](https://immagini.qualescegliere.it/manuali/B01KVO1I7S.pdf)  
> 13. Omron BLE BPM (HEM-7142T2) Custom Integration, [https\://community.home-assistant.io/t/omron-ble-bpm-hem-7142t2-custom-integration/1003286](https://community.home-assistant.io/t/omron-ble-bpm-hem-7142t2-custom-integration/1003286)  
> 14. Importing Blood Pressure Data in Connect?? : r/Garmin \- Reddit, [https\://www\.reddit.com/r/Garmin/comments/14lyrmx/importing\_blood\_pressure\_data\_in\_connect/](https://www.reddit.com/r/Garmin/comments/14lyrmx/importing_blood_pressure_data_in_connect/)  
> 15. CSV format to import blood pressure \- Garmin Connect Web, [https\://forums.garmin.com/apps-software/mobile-apps-web/f/garmin-connect-web/354920/csv-format-to-import-blood-pressure](https://forums.garmin.com/apps-software/mobile-apps-web/f/garmin-connect-web/354920/csv-format-to-import-blood-pressure)  
> 16. Getting Familiar with FIT \- FIT SDK | Garmin Developers, [https\://developer.garmin.com/fit/overview/](https://developer.garmin.com/fit/overview/)  
> 17. fitsdk/java/com/garmin/fit/MesgBroadcaster.java at master \- GitHub, [https\://github.com/dgaff/fitsdk/blob/master/java/com/garmin/fit/MesgBroadcaster.java](https://github.com/dgaff/fitsdk/blob/master/java/com/garmin/fit/MesgBroadcaster.java)  
> 18. fit package \- github.com/tormoder/fit \- Go Packages, [https\://pkg.go.dev/github.com/tormoder/fit](https://pkg.go.dev/github.com/tormoder/fit)  
> 19. Where to get a COMPLETE list of FIT profile and message definitions?, [https\://forums.garmin.com/developer/fit-sdk/f/discussion/301794/where-to-get-a-complete-list-of-fit-profile-and-message-definitions?pifragment-1304=3](https://forums.garmin.com/developer/fit-sdk/f/discussion/301794/where-to-get-a-complete-list-of-fit-profile-and-message-definitions?pifragment-1304=3)  
> 20. Where to get a COMPLETE list of FIT profile and message definitions?, [https\://forums.garmin.com/developer/fit-sdk/f/discussion/301794/where-to-get-a-complete-list-of-fit-profile-and-message-definitions/1925707](https://forums.garmin.com/developer/fit-sdk/f/discussion/301794/where-to-get-a-complete-list-of-fit-profile-and-message-definitions/1925707)  
> 21. Python 3 API wrapper for Garmin Connect to get statistics ... \- GitHub, [https\://github.com/cyberjunky/python-garminconnect](https://github.com/cyberjunky/python-garminconnect)  
> 22. Releases · cyberjunky/python-garminconnect \- GitHub, [https\://github.com/cyberjunky/python-garminconnect/releases](https://github.com/cyberjunky/python-garminconnect/releases)  
> 23. Garmin MCP Server by Taxuspt \- Glama.ai, [https\://glama.ai/mcp/servers/Taxuspt/garmin\_mcp](https://glama.ai/mcp/servers/Taxuspt/garmin_mcp)  
> 24. M7 Intelli IT (ALRU) Instruction Manual \- Omron, [https\://omron.medtechpro.ru/netcat\_files/61/95/Rukovodstvo\_po\_expluatatsii\_tonometr\_M7\_Intelli\_IT\_HEM\_7361T\_ALRU.pdf](https://omron.medtechpro.ru/netcat_files/61/95/Rukovodstvo_po_expluatatsii_tonometr_M7_Intelli_IT_HEM_7361T_ALRU.pdf)

[image1]: <data:image/png;base64,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>

[image2]: <data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAALUAAAAWCAYAAACG293pAAAEsUlEQVR4Xu2ZXagVVRTHV6JJSB9KqVSUUGBWitqb9iAURYE9KPUUBCqiiVHgi4hyQQTRQjILI+1agiKGig9BH4iJookPKmKFGmgfZlZqZWUf5v7fNfvePf+715w5M8d7ObB/sDhn/9eaOTN7r9mz9zoiiUQi0QIGOPuCxUSiXXnX2WPO/mdHItHupKTuQxY4m8siMcjZ3ywSo5z9IDp4O/Oubl4TPc/Pzh4lX18zVIoT7X5nn4vGfEq+KhT9Vn+w0dkYFokHnJ1jkZjk7FfR+1tFviIWix73h7OZ5KvEZtHkwoXAXsy7u/lKemKKBmWts3+C9jZnHwVtcMnZsqD9p7PlQbsvuMPZGWl8T1Mk7xtP7SrUPb4V7HV2VXru/cG8u4snnJ2Xnpgf8+4c+yWf9MedLQzaFoj7JGgfc7YvaNemKKk9/kmMEVsvon0xaMdihkW0vqQoqaHz2wuTwIGgjYeyyBjrt/qDiWIndUhRUi+V/D3dnrU/DLQYt0i8L6DdxmJV6iY19JOkjaT2FYkfD+15FglUDrBUKGI2CyWwknq4qI7PkI8zvSp1jm01rUhq+NaThuVKIw5LvC+grWOxKnWS2j91b2TtJ0XX3wxi/mVRVD/KYoTfnY1gMeM70fVvs1hJvUTieqfE9bLUObbV1E1qjDN807P2M4GvEVa/W3olcKKqSY1XNHSsoz5wNsTZbum9qUQMzsFAj72qY2BDcRdp3zsbTVpZrE7cIXH9TYnrjfjL2QVnPzn7xdlneXe/UDept4j68JbtEH2TfiNaKGiE1e+WXgmcaB6LhJXUb0v8YtA+Qm0MKBM7togwsZHQjXbvRVi/vUfi+uuiOj9Y1wtUJyx7z9kG0Ro4lgDvOBvcdVQ5fFI/xA4CMdg0Mr6AwGMKzb+1Lax+t/RK4ETzWSSspH5VVN9E+n+Z7sF3VD8Y6NiNNwMSGwn9MDuaxOpE3EtMXyOqD2RHG+KTeiw7CMSg/MocEvXxXsbq0xArxtIrgRO9xCJhJfULovos0jke37FZZKCfYLEBmB1QWbmHHU1idaK1psaMGNPbEZ/U49hBIIZnY9Ap6uO9jNWnIVaMpVcCJ3qZRYKT1HOrqM7lL8ymYbx1wdBQ4y4LOtjvsJHYdwe+ZrGuabKo3urqR7OsaNKwaS+LT2rU34tATFia9Twl6uPln9WnIb9JPAbalyxWBSd7hUXCSmoAnUsxfHNvURvckGllX+dhQnvQ4XeSVha+xhDo00hDBSY2a7UjPqknsINATGzZCODjciw0Xk4uovZzEu93aI+wWAVfMMfauAieeUNmSG8f2vjDhbVwY4K/oGMVkRioHNzHYgYqC1wXL0NRUuPf0LAE6R/AUYHWzjwtej9T2UEgxqpOvS+6d/LcLBofjgUmS2j8Vzu0OUF7ZabVYqvorvZb0VIMPvHDXIrDq+KsaAwMGzTMVryWWi16Uaeyz2fz7i7uFfXtEv2rGvXlMtwojZcZHSwUgNkd9xHeU+wVi+rNZdFSJa778by7LUF5DuMcjju000EMNuAYY4yP7yPkAHKB2S7aN+hDfMY2719L79LhTaLxB0X7GWVPTByJRCKRSCQSiUQikUjU5xoX4ZpWMJ3pIAAAAABJRU5ErkJggg==>