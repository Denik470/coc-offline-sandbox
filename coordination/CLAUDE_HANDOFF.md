# Передача проекта Claude — 24 октября 2026

## Цель и границы результата

Продолжить локальный автономный Android-клиент Clash of Clans 18.647.5 с оригинальной графикой и интерфейсом. Пользователь предпочёл оригинальный интерфейс замене на самостоятельно нарисованный. Нужны деревня, управление, автономный бой и сохранение локальных изменений. Полного готового клиента пока НЕТ. Не путать рабочую нативную логику без графики с готовой игрой.

Работай на этом же Mac с существующими файлами. Это передача работы, не просьба заново копировать телефон или строить альтернативный веб-интерфейс. Антигравити остановлен из-за лимитов. Пользователь бережёт токены: небольшие точные чтения, воспроизводимые проверки и минимум повторных полных сборок. Не запускать других моделей/агентов без отдельного разрешения. Не обещать работу в фоне после окончания ответа.

## Абсолютные пути

- Workspace: `/Users/1poppe/Documents/Codex/2026-10-23/new-chat`
- Материалы: `/Users/1poppe/Documents/Codex/2026-10-23/new-chat/outputs/clash-analysis-18.647.5`
- Рабочий репозиторий: `/Users/1poppe/Documents/Codex/2026-10-23/new-chat/outputs/clash-analysis-18.647.5/android-offline`
- Лабораторные исследования: материалы + `/local-lab`
- Утилиты и временные сборки: workspace + `/work`
- Decompiled Java: материалы + `/decompiled-java/sources`
- Канонические ресурсы: материалы + `/assets-current`
- Распакованные CSV: материалы + `/tables-current`
- Нативные оригиналы: материалы + `/native`
- Восстановленный ELF для анализа/отдельной логики: материалы + `/deep/native-code-restored/libg.so`

Git инициализирован, но до передачи коммитов не было. Весь текущий код может быть untracked. Не делай reset/clean/stash, не удаляй файлы пользователя. `.gitignore` оставляет в Git в основном исходники: наличие APK/дампов на диске не означает, что они попадут в commit/clone. Для передачи на другую машину одного git clone недостаточно.

## Что установить

**На этом Mac перечисленное уже есть и наличие проверено. Переустановка не нужна.** Нужен только Claude с доступом к локальной папке, терминалу и разрешением локальной сети для ADB. В режиме веб-чата без локальных инструментов продолжить сборку/проверку невозможно.

- JDK: `/opt/homebrew/opt/openjdk`, javac в `bin/javac`.
- SDK: workspace `/work/android-sdk`, platform android-36, build-tools 36.0.0.
- NDK: SDK `/ndk/29.0.14206865`, toolchain `toolchains/llvm/prebuilt/darwin-x86_64`.
- ADB: workspace `/work/deep-tools/adb36/platform-tools/adb` (36.0.2).
- Python для reverse engineering: workspace `/work/deep-tools/venv/bin/python` (pyelftools, capstone, unicorn). Обычный Apple python3 не содержит этих модулей.
- Ghidra 12.2: workspace `/work/deep-tools/ghidra/ghidra_12.2_PUBLIC/support/analyzeHeadless`.
- Graphify 0.9.95: `/opt/homebrew/bin/graphify`, изолированное окружение workspace `/work/graphify-env`; официальный пакет называется `graphifyy`, репозиторий https://github.com/Graphify-Labs/graphify .
- Obsidian 1.14.4: `/Applications/Obsidian.app`, необязателен для сборки.

На новой машине нужны эти версии SDK/NDK, совместимый JDK, Python venv с зависимостями из local-lab/requirements-native.txt и requirements-observe.txt по необходимости; потребуется перенос всего дерева материалов и корректировка жёстко заданных путей build.py. Не устанавливай Frida ради текущего сбоя: он воспроизводится в нашем APK без подключения к процессу оригинальной игры.

## Что читать и в каком порядке

1. Этот файл, затем `AGENTS.md` в корне репозитория.
2. `STATUS.json`, `renderer-blocker.json`, `coordination/CODEX_REPORT.md` — актуальный прогресс; поля readiness намеренно false.
3. `local-lab/frontend-integration-notes.md` — наиболее свежая цепочка открытия деревни и последние исправления.
4. `dependency-db-renderer-progress.txt.crash` и `dependency-db-renderer-progress.txt` в репозитории — последний сбой.
5. Graphify `explain submitLocalHome` → точечные части `jni/probe.cpp`, `src/lab/coc/offline/RenderActivity.java`, `build.py`, `check_renderer.py`.
6. Для предыдущих деталей: `local-lab/README.md`, `local-lab/native_layouts.hpp`, `local-lab/frontend-decompiled/`, `tools/native_query.py`, `tools/ExportClashJNI.java`.
7. `coordination/UI_NATIVE_CONTRACT.md` — только черновик интерфейса для будущей интеграции; не доказательство реализации.

Старые README/заметки могут описывать уже исправленные проблемы. Доверять датированным свежим экспериментам и логам выше. Например, предупреждение о неверной установке и падение базы зависимостей уже исправлены.

## Graphify и Obsidian

Есть локальный AST-граф `graphify-out/graph.json`: около 501 узла, 790 связей. Следуй AGENTS: сначала query/explain, затем читай конкретные исходники. Пример из корня репозитория:

```sh
/opt/homebrew/bin/graphify explain submitLocalHome
/opt/homebrew/bin/graphify query 'RenderActivity build check_renderer' --budget 1200
/opt/homebrew/bin/graphify update .
```

После изменения кода обновляй AST-граф. Семантический анализ документов, облачные API, фоновые watcher и дополнительные модели не нужны. Граф не декомпилирует libg.so и не заменяет ARM64-анализ. Codex-скилл установлен в `/Users/1poppe/.codex/skills/graphify/SKILL.md`; установка скилла в Claude необязательна, CLI работает самостоятельно. Не нужно слепо выполнять все пункты полного pipeline скилла ради обычной работы.

Obsidian vault: материалы `/research-vault`, 487 экспортированных заметок + `Project status.md`. Открытие vault в GUI не было подтверждено; при необходимости выбрать Open folder as vault. Этот экспорт уже может отставать от последних изменений, источники и STATUS актуальнее. Никаких учётных записей/синхронизации/MCP для Obsidian не требуется.

## Что реально работает

- Отдельный пакет `lab.coc.offline`, INTERNET permission отсутствует. Оригинальный `com.supercell.clashofclans` не изменяли.
- MainActivity в процессе `:logic` запускает восстановленную нативную логику. Таблицы, ClientAvatar/ClientHome, обе деревни и NPC attack mode проверены.
- 113 CSV, 6 compiled indices, 119 проходов read/link. Первая деревня 419 зданий, 48 ловушек, 37 препятствий; вторая 236 зданий, 21 ловушка, 1 препятствие.
- Боевая проверка: создание NPC, клонирование аватара, постановка бойца, 2048 нативных тиков, движение, HP 4500→0, удаление бойца. Это не подтверждает завершение полноценного боя/награды/сохранение прогресса. Для NPC пока используется копия собственной деревни.
- RenderActivity нормально загружает оригинальные support/libg через Android loader, вызывает JNI/lifecycle/FMOD, создаёт поверхность и рисует оригинальный загрузочный экран.
- Таблицы реального renderer: present116/read116/linked115. Индексы4/54/90 отсутствуют и в эталонном registry; unlinked32 сам по себе не доказанная ошибка.
- Локальная отправка дома через оригинальный frontend entry `0xb7fe94` проверена: ClientAvatar и ClientHome созданы, frontend kind=1, mode=0, pending home=1. **Завершение loadHomeState и деревня на экране НЕ подтверждены.**

## Сборки и команды

Из корня репозитория:

```sh
python3 build.py                    # компактная диагностическая сборка
python3 build.py --home-assets      # добавляет 189 файлов каталога домашней сцены
```

Gradle не используется: build.py вызывает clang++, aapt2, javac, d8, zipalign/apksigner. Результат всегда `offline-lab.apk` и при следующей сборке перезаписывается. Assets/classes/dex staging теперь очищается в начале сборки, чтобы большая сборка не загрязняла компактную.

Артефакты:
- `offline-combat-lab.apk` — сохранённая рабочая автономная нативная боевая лаборатория (~18 МБ).
- `offline-renderer-loading-lab.apk` — старый сохранённый оригинальный загрузочный экран; НЕ содержит все свежие исправления.
- `offline-home-assets-lab.apk` — сохранённый промежуточный тест 219 МБ ДО добавления assetdependencies.flat; не выдавать за последнюю исправленную версию.
- `offline-lab.apk` — последняя сборка 219 МБ с home assets, assetdependencies.flat и SIGTRAP diagnostic; установлена на телефон и воспроизвела следующий SIGSEGV.
- `home-assets-manifest.json` — 189 каталоговых файлов, 197392522 bytes. `sc/decos.sc` отсутствует в исходной копии и отмечен как missing. База зависимостей включается отдельно и не входит в эти189.

Wi-Fi-передача 219 МБ занимает минуты. Не пересобирай/переустанавливай без нового конкретного изменения. Скрипт проверки ограничивает наблюдение 10 секундами, install timeout180с. Во время длинной операции сообщай пользователю состояние.

```sh
python3 check_renderer.py --serial 192.168.31.93:35495 --label next-check --seconds 10 --install --local-home
```

Скрипт снимает screenshot только если foreground — наш RenderActivity, читает run-as logs, сохраняет exit-info при уходе activity и в finally останавливает только `lab.coc.offline`. Оригинальная игра/лаунчер на экране после падения не является нашим успешным рендером. Для более длительной проверки сначала осознанно измени ограничение скрипта; не думай, что --seconds60 уже работает.

## Телефон и разрешения

OnePlus CPH2767, Android16 API36, root KSU. Последний работающий адрес **192.168.31.93:35495**, Mac192.168.31.83. USB-переходник нестабилен; пользоваться Wi-Fi.

```sh
ADB=/Users/1poppe/Documents/Codex/2026-10-23/new-chat/work/deep-tools/adb36/platform-tools/adb
"$ADB" connect 192.168.31.93:35495
"$ADB" devices -l
"$ADB" -s 192.168.31.93:35495 shell getprop ro.product.model
```

Всегда указывай -s: устройство может отображаться также дублирующимся mDNS именем. IP/порт могут измениться. Новое сопряжение в последней сессии не понадобилось — доверие сохранено. Код722290 из старой переписки истёк, не использовать. Если порт сменился — спросить текущий адрес; pairing требует отдельного pairing port и нового кода.

Пользователь разрешил подключение, копирование данных игры, исследование, установку нашего APK и ранее временную Frida с root для наблюдения; не трактовать это как разрешение на неограниченное вмешательство в другие приложения. Текущий этап не требует root/Frida. Не удаляй оригинальную игру: её установленная package metadata пока нужна support loader. Не извлекай/не публикуй учётные токены и персональные данные в промпты или логи.

## Последний блокер — начинать здесь

Сначала устранили отсутствие InputSDKProvider: локальный Java registry actions/groups/contexts, без Google PC service, файл `src/com/supercell/titan/inputsdk/InputSDKProvider.java`. Нативный frontend mode1 теперь создаётся полностью и принимает дом.

Следующий SIGTRAP:
- `trap-origin-renderer-progress.txt.crash` PC libg+`0xc8b634`.
- Call chain b7e060 → b8b488 → b7e0fc → b8a22c.
- `c8b560` открывает `data/assetdependencies.flat`, проверяет m_loaded, иначе assert `Failed to load Asset Dependency Database`.
- Канонический файл367КиБ был пропущен упаковщиком; добавлен в build.py. Проверка dependency-db подтвердила: этот SIGTRAP исчез. Assert не отключали.

**Актуальный сбой:** `dependency-db-renderer-progress.txt.crash`
- Несколько worker threads получают SIGSEGV, fault0.
- PC libg+`0x1141f24`, LR+`0x1012b04`.
- Стек: `101bcec`, `1085734`, `10852b0`, `117d09c`, `117ce64`, `117cda0`, `117dcb0`.
- 1141f20: `ldr x0,[x0]`; 1141f24: `ldr x8,[x0]` — нулевой underlying stream.
- В1012a40…1012ae8: filename libc++string вx2, сохраняетсяx19; raw C filename вx22; вызов11425ac открывает поток, затем1012b00→1141f20 читает 2байта SC header без проверки нулевого потока. После него читает4байта version, проверяет S/C.
- **Имя неоткрытого файла пока неизвестно. Не объявлять определённый texture виновным без наблюдения.** Может не хватать дополнительных файлов/зависимостей или не совпадать путь; нужны evidence.

Предлагаемый следующий шаг: записать имя файла при открытии в SC loader, используя ограниченный диагностический код в нашем процессе. На этой точке x22 сохраняет raw filename; проверяй ABI/указатель перед чтением. Уже есть signal recorder recordAbort в probe.cpp, SIGABRT/SEGV/BUS/ILL/TRAP. Не маскируй ошибку возвратом успеха или пропуском assert: нужно восстановить корректные данные/инициализацию. Затем определить минимальное замыкание ресурсов через assetdependencies.flat/каталог и добавить только необходимые файлы. Не копируй заново гигабайты без причины.

## Важные native детали

Адреса ниже ELF-relative. **Ghidra imagebase +0x100000**: в именах export-файлов и FUN адресах Ghidra надо вычитать0x100000, прежде чем использовать как offset runtime.

- Original graphics: native/libg.so и support через обычный System.loadLibrary. Не заменять графический libg восстановленным headless образом.
- Для headless отдельно `libg_headless.so`: DT_INIT→RET e49608, 341 initializer patches, f95a10 NOP только analyticsJNI. Исходные evidence не изменять.
- Recovered SHA256 `7864a07552e76ab4ea7af8d8110ca015b7d0ba427f6d25114ad7dec494ba8d36` проверяется build.py.
- Support повторный JNI_OnLoad опасен и уже завершал наш процесс. Инициализировать один раз, не повторять прежний эксперимент.
- Support JNI failure исправили `<queries>` оригинального package и smxuhbhxh.ac/aj bridge; JNI version65538.
- GameMode ctor f92500, sizeof98, LogicGame+10, loadHomeState f94734.
- ClientAvatar ctor cd2cd4, calloc800 достаточно.
- ClientHome ctor efbb58, sizeof78; JSON setter efbcb8, JSON ScString pointer+20. Calendar+38/events+50 должны быть непустыми указателями на сконструированные пустые строки: ctor11fce50, setterfcb698.
- ScString16bytes: capacity/hash=-1, length, union inline/pointer. Не путать с libc++ std::string, используемой в graphics loader.
- Controller global1838e88, ctor b79bec sizeof340, vtable1710478, GameMode+160.
- GUI manager global1832ed8; b8b94c(manager,1) создаёт controller (начальный mode3 — networkloading).
- Home accept b7fe94(controller,home,avatar,0,0,1,0,0,ptrToZero8,0,ptrToZero8,0). Полная текущая сигнатура в submitLocalHome. Подача при beforeLogicCallback180, флаг --local-home; default renderer не делает экспериментального перехода.
- Pending home+f8, avatar+100, kind+128, progressfloat+2d4. b7e0c0 ждёт готовность загрузчика и затем вызывает f94734. Прямая отправка принятого home не равна завершённому load.
- NativeHTTPClientManager offline callback обязан передавать null body, не empty byte[], иначе нативный JSON parser падает.
- TitanWebView.close не должен повторно вызывать onWebViewClosed: это уже исправляли.

## Утилиты анализа

Из workspace:
```sh
work/deep-tools/venv/bin/python outputs/clash-analysis-18.647.5/tools/native_query.py 0x1012a40 --size 192
```

Image.read/disasm/callers/prologue в tools/native_query.py. Prologue heuristic иногда неточен/None. ELF без section headers: relocations получать через PT_DYNAMIC/DT_RELA, а не iter_sections() (иначе молча пусто).

Ghidra saved projects: материалы `/ghidra-project`, проект ClashResearch (libg.so), SupportResearch (support). ExportClashJNI.java и JSON targets позволяют декомпилировать отдельные функции без полного повторного анализа.
Для headless Ghidra задать JAVA_HOME `/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`, JAVA_TOOL_OPTIONS с `-Duser.home=.../work/deep-tools/ghidra-user -Djava.awt.headless=true`.

## Критерии следующего результата

1. Доказать конкретный filename/инициализацию, вызывающие null SC stream, и исправить причину.
2. Получить screenshot именно lab.coc.offline с оригинальной деревней; подтвердить GameMode/home loaded, а не только pending=1.
3. Затем touch/lifecycle и автономный бой с оригинальной графикой, локальные команды и сохранение. Серверные Login/OwnHomeData/шифрование не реализованы; текущий путь использует локальный frontend entry.
4. Проверять регрессии по мере затронутых подсистем, не гонять все тесты после каждого документа. Обновлять STATUS и handoff evidence, не выдавать промежуточный APK за полный клиент.

Пользователю сейчас ничего нажимать не требуется. Если подключение перестанет работать или потребуется новое сопряжение — сообщить конкретную проблему и нужное действие. По последним проверкам ADB и установка работали.
