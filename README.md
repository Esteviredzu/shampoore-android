# shampoore-android

Android-клиент для [shampoore-core](https://github.com/Esteviredzu/shampoore-core).

## Быстрый старт

1. Откройте проект в Android Studio.
2. Дождитесь окончания **Gradle Sync**. При первой синхронизации будет скачан готовый
   `shampoore.aar` (Go-ядро) из
   [релизов shampoore-core](https://github.com/EstEviredzu/shampoore-core/releases)
   и закэширован в `app/build/shampoore-core/`.
3. Подключите устройство или запустите эмулятор.
4. Нажмите **Run** (зелёная стрелка).

Версия ядра, которая скачивается, задаётся переменной `coreVersion` в начале
`app/build.gradle.kts`.

## Сборка ядра локально

Если вы хотите собрать Go-ядро самостоятельно, сделайте это **один раз**:

### 1. Установите зависимости

- Go 1.23+: https://go.dev/dl/
- Android NDK: https://developer.android.com/ndk
- gomobile и gobind:

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init
```

### 2. Склонируйте и соберите ядро

```bash
git clone https://github.com/Esteviredzu/shampoore-core
cd shampoore-core
./build-aar.sh
```

В корне репозитория ядра появится `shampoore.aar`.

### 3. Скопируйте AAR в этот проект

```bash
cp shampoore.aar ../shampoore-android/app/libs/shampoore.aar
```

Если папки `app/libs/` ещё нет, создайте её:

```bash
mkdir -p ../shampoore-android/app/libs
```

### 4. Sync и Run

Откройте Android Studio, дождитесь Sync, нажмите Run. Gradle обнаружит AAR в
`app/libs/shampoore.aar` и пропустит скачивание.

## Переключение между скачанным и локальным AAR

- **Использовать автоматически скачанный AAR** - удалите ручной файл:
  ```bash
  rm -f app/libs/shampoore.aar
  ```
  При следующем Sync скачается версия, указанная в `coreVersion`.

- **Использовать локально собранный AAR** - положите свой `shampoore.aar` в
  `app/libs/shampoore.aar`. Gradle подхватит его автоматически.

## Обновление версии ядра

1. Опубликуйте новый релиз в `shampoore-core` с прикреплённым `shampoore.aar`
   (тег `vX.Y.Z`).
2. В `app/build.gradle.kts` измените:
   ```kotlin
   val coreVersion = "1.0.0"
   ```
   на новую версию, например `"1.1.0"`.
3. Sync и Run. Новый AAR скачается и закэшируется рядом со старым.

Папка кэша `app/build/shampoore-core/` хранит все версии, которые вы использовали.
`Clean Project` её очищает, и при следующем Sync скачается версия из `coreVersion`
заново.

## Как выбирается AAR

- Если `app/libs/shampoore.aar` существует - используется он. Скачивание не
  выполняется.
- Иначе, если существует `app/build/shampoore-core/shampoore-<coreVersion>.aar`
  (кэш), используется этот файл.
- Иначе файл скачивается с
  `https://github.com/EstEviredzu/shampoore-core/releases/download/v<coreVersion>/shampoore.aar`
  и кэшируется в `app/build/shampoore-core/`.

Если скачивание не удалось (нет релиза для этой версии, проблемы с сетью), Sync
завершится с ошибкой и подсказкой обратиться к этому README.