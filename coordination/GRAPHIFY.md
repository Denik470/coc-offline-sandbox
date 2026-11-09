# Graphify и Obsidian

Graphify 0.9.95 установлен из официального Graphify-Labs/graphify, commit 92741d162b2094ffc2fed58cfbd8ee823e9da62c. Команда graphify доступна через /opt/homebrew/bin/graphify, окружение находится в work/graphify-env от корня workspace.

Установлен Codex skill (глобально и в проекте); он доступен со следующего сообщения. Правила проекта: AGENTS.md.

Первый индекс: 33 исходных файла, 460 узлов и 746 связей. dangling edges: 0. Только --code-only: LLM-этап, документы, изображения и бинарники не индексировались. Облачный сервис, API-ключи и фоновые наблюдатели не включались. Реальное сокращение расхода токенов в нашем рабочем процессе пока не измерялось.

Команды из android-offline:

```sh
graphify query "submitLocalHome" --budget 600
graphify explain submitLocalHome
graphify extract . --code-only
graphify cluster-only .
graphify export obsidian --dir ../research-vault
```

Для обновления исходников использовать локальный AST-режим; не запускать LLM-индексацию документации без явной необходимости. Не индексировать гигабайты игровых ресурсов. Граф помогает искать связи в коде, но не восстанавливает native-функции из .so.

Obsidian 1.14.4 установлен из официального релиза через Homebrew. Хранилище ../research-vault содержит экспорт кода и локальную заметку Project status.md. Не включены Sync, Publish, аккаунт или community plugins.
